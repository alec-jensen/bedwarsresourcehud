package me.alecjensen.bedwarsresourcehud.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds walkable block edges near the player where the next couple of blocks over have no solid
 * ground for a long way down - a void fall waiting to happen, as opposed to just a one-block step
 * down to another platform. Scanning is throttled and cached (block-state lookups over a whole
 * radius every single tick would be wasteful); VoidEdgeRenderer just draws whatever was found last
 * scan.
 *
 * Looks ahead in each direction, following safe step-downs (a staircase, a one-block ledge) for
 * free rather than counting them against the search - so a void at the bottom of a staircase still
 * gets found and warned about on the block you're actually standing on, not just down at the final
 * step where it's easy to miss. Classifies what it finds into three tiers: VOID_NEAR (you're one
 * block from stepping straight into a bottomless drop), VOID_FAR (it takes at least one more hop to
 * get there - an earlier warning), and FALL_DAMAGE (there's solid ground down there, but landing on
 * it would actually hurt, computed from the real vanilla damage formula rather than a fixed depth
 * cutoff).
 *
 * Never looks at columns above the player's own feet - a drop-off on a platform above you isn't
 * something you're about to walk into.
 */
public final class VoidEdgeTracker
{
    private static final int SCAN_RADIUS = 10;
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int SURFACE_SEARCH_RANGE = 3;
    // How far straight down to search before giving up and calling it void. This needs to be deep
    // enough to actually reach real ground on a tall-but-survivable drop (a high bridge looking down
    // at the map's own terrain far below is still a FALL_DAMAGE hazard, not void, no matter how far
    // down that ground is) - 12 was catching plenty of those as false "void" just because they
    // happened to exceed the old search depth. Bedwars islands typically float somewhere in the
    // Y70-120 range with real terrain or bedrock well below that, so this comfortably reaches actual
    // ground for anything within a map; red is now reserved for drops where there's genuinely
    // nothing there.
    private static final int VOID_DEPTH_CHECK = 80;
    // Free step-downs (a staircase, a ledge) don't cost anything against this - only a hop that
    // actually needs a hazard check (the final one, since checkEdge returns right after) does, so
    // this just needs to be generous enough to walk down a reasonably long staircase and still have
    // a hop left over to test what's beyond it.
    private static final int LATERAL_LOOKAHEAD = 6;
    private static final int SAFE_FALL_BLOCKS = 3;
    // The "2 away" orange warning only fires when reaching the void involved less than this much
    // total vertical step-down along the way - a small ledge-then-void case still reads as a
    // useful early heads-up, but once you'd have to drop 3+ blocks just to reach the void-adjacent
    // spot, you were never really standing at the same level as the hazard.
    private static final int FAR_WARNING_MAX_STEP_DOWN = 3;

    // Vanilla enchantment/potion math: Feather Falling -12%/level, Protection -4%/level (these two
    // stack but the combined enchantment reduction caps at 80%), Resistance -20%/level (applied
    // separately, not part of that cap).
    private static final float FEATHER_FALLING_REDUCTION_PER_LEVEL = 0.12f;
    private static final float PROTECTION_REDUCTION_PER_LEVEL = 0.04f;
    private static final float RESISTANCE_REDUCTION_PER_LEVEL = 0.20f;
    private static final float MAX_ENCHANTMENT_REDUCTION = 0.80f;

    private static volatile List<Edge> cachedEdges = List.of();
    private static int tickCounter;

    private VoidEdgeTracker()
    {
    }

    public static List<Edge> getEdges()
    {
        return cachedEdges;
    }

    public static void tick(Minecraft client)
    {
        ClientLevel level = client.level;
        Player self = client.player;
        if (level == null || self == null)
        {
            return;
        }

        tickCounter++;
        if (tickCounter % SCAN_INTERVAL_TICKS != 0)
        {
            return;
        }

        BlockPos playerPos = self.blockPosition();
        List<Edge> edges = new ArrayList<>();
        Map<BlockPos, Double> topYCache = new HashMap<>();
        FallProtection protection = computeFallProtection(self);

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++)
        {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++)
            {
                int x = playerPos.getX() + dx;
                int z = playerPos.getZ() + dz;
                SurfaceHit surface = findSurface(level, x, playerPos.getY(), z, topYCache);
                if (surface == null)
                {
                    continue;
                }

                checkEdge(level, edges, x, surface, z, 1, 0, topYCache, protection);
                checkEdge(level, edges, x, surface, z, -1, 0, topYCache, protection);
                checkEdge(level, edges, x, surface, z, 0, 1, topYCache, protection);
                checkEdge(level, edges, x, surface, z, 0, -1, topYCache, protection);
            }
        }

        cachedEdges = edges;
    }

    /** Everything about the player that reduces (or negates) fall damage, gathered once per scan rather than per column. */
    private static FallProtection computeFallProtection(Player self)
    {
        int featherFalling = getEnchantmentLevel(self, Enchantments.FEATHER_FALLING);
        int protectionLevel = getEnchantmentLevel(self, Enchantments.PROTECTION);
        MobEffectInstance resistance = self.getEffect(MobEffects.RESISTANCE);
        MobEffectInstance jumpBoost = self.getEffect(MobEffects.JUMP_BOOST);
        boolean slowFalling = self.hasEffect(MobEffects.SLOW_FALLING);

        return new FallProtection(
                featherFalling,
                protectionLevel,
                resistance == null ? 0 : resistance.getAmplifier() + 1,
                jumpBoost == null ? 0 : jumpBoost.getAmplifier() + 1,
                slowFalling);
    }

    private static int getEnchantmentLevel(Player self, net.minecraft.resources.ResourceKey<Enchantment> key)
    {
        try
        {
            HolderGetter<Enchantment> enchantments = self.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            Holder<Enchantment> holder = enchantments.getOrThrow(key);
            return EnchantmentHelper.getEnchantmentLevel(holder, self);
        }
        catch (IllegalStateException e)
        {
            return 0;
        }
    }

    /** The highest walkable surface at or below the player's own feet, within SURFACE_SEARCH_RANGE, with headroom above it. */
    private static SurfaceHit findSurface(ClientLevel level, int x, int playerY, int z, Map<BlockPos, Double> cache)
    {
        for (int y = playerY; y >= playerY - SURFACE_SEARCH_RANGE; y--)
        {
            BlockPos pos = new BlockPos(x, y, z);
            if (!isSolid(level, pos, cache) || isSolid(level, pos.offset(0, 1, 0), cache))
            {
                continue;
            }
            return new SurfaceHit(y);
        }
        return null;
    }

    private static void checkEdge(ClientLevel level, List<Edge> edges, int x, SurfaceHit surface, int z, int dirX, int dirZ, Map<BlockPos, Double> cache, FallProtection protection)
    {
        int currentY = surface.blockY();
        int totalStepDown = 0;

        for (int step = 1; step <= LATERAL_LOOKAHEAD; step++)
        {
            int neighborX = x + dirX * step;
            int neighborZ = z + dirZ * step;

            // A step down onto a lower ledge within safe-fall range is still just walking, not a
            // hazard - the lookahead needs to follow it downward so a void that's only reachable
            // by crossing a small step-down (like the block-A/block-B case) still gets found, with
            // the resulting warning anchored back on the origin block instead of down at the step.
            int steppedDownTo = findWalkableStepDown(level, neighborX, currentY, neighborZ, cache);
            if (steppedDownTo != Integer.MIN_VALUE)
            {
                totalStepDown += currentY - steppedDownTo;
                currentY = steppedDownTo;
                continue;
            }

            EdgeType type = classify(level, neighborX, currentY, neighborZ, step, cache, protection);
            if (type == null)
            {
                // Solid ground close enough (or damage-negating) - a safe ledge or stairs, not a hazard.
                return;
            }

            // VOID_FAR (the "2 away" orange warning) only means something as an early heads-up when
            // it's reached by a small dropoff - once getting there requires a bigger step down, you
            // were never on the same eye-level plane as the void anyway, so the preemptive warning
            // stops being useful and it's suppressed rather than shown for any size dropoff.
            if (type == EdgeType.VOID_FAR && totalStepDown >= FAR_WARNING_MAX_STEP_DOWN)
            {
                return;
            }

            // Only draw on the origin block if that face is actually exposed to open air - if the
            // hazard is further off behind a solid neighbor (a wall, another block flush against
            // this one), marking origin's near face would just be painting a line on solid ground
            // you can't even see past, not a useful warning.
            if (isSolid(level, new BlockPos(x + dirX, surface.blockY(), z + dirZ), cache))
            {
                return;
            }

            double top = edgeHeight(level, x, surface.blockY(), z, dirX, dirZ);
            if (dirX != 0)
            {
                int edgeX = dirX > 0 ? x + 1 : x;
                edges.add(new Edge(new Vec3(edgeX, top, z), new Vec3(edgeX, top, z + 1), type));
            }
            else
            {
                int edgeZ = dirZ > 0 ? z + 1 : z;
                edges.add(new Edge(new Vec3(x, top, edgeZ), new Vec3(x + 1, top, edgeZ), type));
            }
            return;
        }
    }

    /** The topmost solid Y within a safe-fall step down from fromY, or Integer.MIN_VALUE if there's nothing solid within that range. */
    private static int findWalkableStepDown(ClientLevel level, int x, int fromY, int z, Map<BlockPos, Double> cache)
    {
        for (int dy = 0; dy <= SAFE_FALL_BLOCKS; dy++)
        {
            if (isSolid(level, new BlockPos(x, fromY - dy, z), cache))
            {
                return fromY - dy;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Null means safe (a real ledge, a damage-negating landing, or damage the player won't actually feel) - not a hazard worth drawing. */
    private static EdgeType classify(ClientLevel level, int x, int fromY, int z, int lateralStep, Map<BlockPos, Double> cache, FallProtection protection)
    {
        if (protection.slowFalling())
        {
            // Negates fall damage outright regardless of what's below - nothing in this direction is a hazard.
            return null;
        }

        for (int depth = 1; depth <= VOID_DEPTH_CHECK; depth++)
        {
            BlockPos landingPos = new BlockPos(x, fromY - depth, z);
            if (negatesFallDamage(level, landingPos))
            {
                return null;
            }
            if (isSolid(level, landingPos, cache))
            {
                if (depth <= SAFE_FALL_BLOCKS + protection.jumpBoostLevel())
                {
                    return null;
                }
                double damage = estimateFallDamage(level, landingPos, depth, protection);
                return damage > 0 ? EdgeType.FALL_DAMAGE : null;
            }
        }
        return lateralStep == 1 ? EdgeType.VOID_NEAR : EdgeType.VOID_FAR;
    }

    /** Landing surfaces that negate fall damage outright, regardless of fall distance - water, cobweb, powder snow, sweet berry bush. */
    private static boolean negatesFallDamage(ClientLevel level, BlockPos pos)
    {
        if (level.getFluidState(pos).is(FluidTags.WATER))
        {
            return true;
        }
        var block = level.getBlockState(pos).getBlock();
        return block == Blocks.COBWEB || block == Blocks.POWDER_SNOW || block == Blocks.SWEET_BERRY_BUSH;
    }

    /**
     * Vanilla's own formula: (blocks fallen - 3 - Jump Boost levels) damage points, reduced by
     * Feather Falling + Protection (combined, capped at 80%), then by Resistance (separate, not
     * part of that cap), then by whatever's actually landed on.
     */
    private static double estimateFallDamage(ClientLevel level, BlockPos landingPos, int fallBlocks, FallProtection protection)
    {
        double raw = fallBlocks - SAFE_FALL_BLOCKS - protection.jumpBoostLevel();
        if (raw <= 0)
        {
            return 0;
        }

        var block = level.getBlockState(landingPos).getBlock();
        double blockMultiplier;
        if (block == Blocks.SLIME_BLOCK)
        {
            blockMultiplier = 0.0;
        }
        else if (block == Blocks.HAY_BLOCK || block == Blocks.HONEY_BLOCK)
        {
            blockMultiplier = 0.2;
        }
        else if (block instanceof BedBlock)
        {
            blockMultiplier = 0.5;
        }
        else
        {
            blockMultiplier = 1.0;
        }

        float enchantReduction = Math.min(MAX_ENCHANTMENT_REDUCTION,
                FEATHER_FALLING_REDUCTION_PER_LEVEL * protection.featherFallingLevel() + PROTECTION_REDUCTION_PER_LEVEL * protection.protectionLevel());
        float resistanceReduction = Math.min(1.0f, RESISTANCE_REDUCTION_PER_LEVEL * protection.resistanceLevel());

        return raw * blockMultiplier * (1.0f - enchantReduction) * (1.0f - resistanceReduction);
    }

    /**
     * Height to draw the line at, sampled right at the origin block's own edge facing the hazard
     * direction rather than its horizontal center - a stair's collision shape is two boxes at two
     * different heights split across its footprint, so a center sample can land on either the
     * riser or the tread depending on orientation. Sampling at the actual outward-facing edge
     * instead gets whichever part of the shape is really there.
     */
    private static double edgeHeight(ClientLevel level, int x, int y, int z, int dirX, int dirZ)
    {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty())
        {
            return y;
        }

        double sampleX = dirX > 0 ? 0.999 : dirX < 0 ? 0.001 : 0.5;
        double sampleZ = dirZ > 0 ? 0.999 : dirZ < 0 ? 0.001 : 0.5;
        return y + shape.max(Direction.Axis.Y, sampleX, sampleZ);
    }

    private static boolean isSolid(ClientLevel level, BlockPos pos, Map<BlockPos, Double> cache)
    {
        return !Double.isNaN(solidTopY(level, pos, cache));
    }

    /**
     * The block's real collision-shape top height (in world Y) at its horizontal center, or NaN if
     * it has no collision at all. Only used for walkability/presence checks (does something occupy
     * this column at all), not for the rendered line height - see edgeHeight for that. Memoized per
     * scan since the same column gets queried from several neighboring directions.
     */
    private static double solidTopY(ClientLevel level, BlockPos pos, Map<BlockPos, Double> cache)
    {
        Double cached = cache.get(pos);
        if (cached != null)
        {
            return cached;
        }

        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        double result = shape.isEmpty() ? Double.NaN : pos.getY() + shape.max(Direction.Axis.Y, 0.5, 0.5);
        cache.put(pos, result);
        return result;
    }

    private record SurfaceHit(int blockY)
    {
    }

    private record FallProtection(int featherFallingLevel, int protectionLevel, int resistanceLevel, int jumpBoostLevel, boolean slowFalling)
    {
    }

    public enum EdgeType
    {
        VOID_NEAR,
        VOID_FAR,
        FALL_DAMAGE
    }

    public record Edge(Vec3 start, Vec3 end, EdgeType type)
    {
    }
}
