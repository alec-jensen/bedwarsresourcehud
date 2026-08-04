package me.alecjensen.bedwarsresourcehud.client.hud;

import me.alecjensen.bedwarsresourcehud.client.config.HudConfig;
import me.alecjensen.bedwarsresourcehud.mixin.client.ClientLevelAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches for tracked resources spawning as dropped item entities (how Bedwars generators
 * present themselves - a real, visible pile of items growing on the generator block) and uses
 * that to estimate a per-generator respawn interval, plus fires the configured spawn alert.
 * Generators are matched by clustering nearby spawns of the same resource together, since a map
 * can have more than one generator of the same type - including, on some maps, more than one
 * stacked almost directly on top of each other, which is why each cluster is anchored to its
 * first-seen position rather than drifting with the pile, and each keeps its own independent
 * spawn interval instead of sharing one per resource type.
 *
 * This has to tick-scan stack counts rather than just listen for new entities: a generator's item
 * pile is one long-lived ItemEntity that grows via vanilla item merging, so after the first spawn
 * every subsequent one is just a stack-count increase synced onto the same entity, not a new one.
 *
 * Separately, generators that show a floating text hologram are detected instantly via
 * {@link #scanHolograms}, without waiting for any item to spawn at all - see that method.
 */
public final class GeneratorTracker
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BedwarsResourceHud/GeneratorTracker");
    private static final Logger DIAGNOSTICS_LOGGER = LoggerFactory.getLogger("BedwarsResourceHud/GeneratorDiagnostics");

    // Deliberately tighter than a generator pile could plausibly drift, and clusters are anchored
    // (never re-centered) rather than following the pile's current position - both changes exist
    // to keep two distinct generators of the same resource type from being merged into one record
    // when they're placed close together or stacked vertically.
    private static final double CLUSTER_RADIUS_SQ = 3.0 * 3.0;

    /**
     * A cluster only counts as a real generator once it's grown this many times at the same spot.
     * The client can't see who dropped an item (thrower isn't networked), so a one-off item on the
     * ground - a player dropping a diamond, say - can't be told apart from a generator on a single
     * sighting. Requiring repeat growth at the same location before trusting it filters that out,
     * since players essentially never Q-drop the same resource at the same spot repeatedly.
     */
    private static final int CONFIRMATION_SPAWNS = 4;

    // A burst of entities syncing in together (walking back into range, a chunk loading in) can
    // register as several "spawns" a fraction of a second apart, which isn't the real generator
    // cadence - flooring it keeps that burst noise from corrupting the countdown/estimate math.
    // Real Bedwars generators, even maxed-tier ones, don't produce individual item entities faster
    // than this in practice, so anything under it is confidently burst noise, not a genuinely fast
    // generator.
    private static final long MIN_INTERVAL_MILLIS = 2000;
    private static final int MAX_ESTIMATED_EXTRA_CYCLES = 64;
    // Independent of whatever intervalMillis currently is: even if a burst still manages to pin it
    // near the floor, staleness shouldn't be declared (and the estimate shouldn't start climbing)
    // until it's been at least this long in real time since the last observed spawn. Without this,
    // a generator whose recorded interval got flattened to MIN_INTERVAL_MILLIS would flip into the
    // "lost tracking" fallback and start counting up within a second or two of being perfectly fine.
    private static final long MIN_STALE_GRACE_MILLIS = 8000;

    private static final int DIAGNOSTIC_BLOCK_SCAN_DEPTH = 3;
    private static final double DIAGNOSTIC_SEARCH_HORIZONTAL = 3.0;
    private static final double DIAGNOSTIC_SEARCH_UP = 5.0;
    private static final double DIAGNOSTIC_SEARCH_DOWN = 1.0;

    /**
     * Generators that show a floating hologram (an armor-stand stack with lines like "Tier I" /
     * "Emerald" / "Spawns in 55 seconds", confirmed by direct observation) can be detected the
     * instant the level loads, without waiting for any item to actually spawn - the hologram is
     * server-authoritative signage, not an inference from behavior, so it's trusted immediately and
     * skips the usual repeat-growth confirmation entirely.
     */
    private static final int HOLOGRAM_SCAN_INTERVAL_TICKS = 40;
    private static final double HOLOGRAM_GROUP_HORIZONTAL_TOLERANCE = 0.5;
    private static final int HOLOGRAM_GROUND_SCAN_DEPTH = 10;
    private static final int HOLOGRAM_UNMATCHED_SAMPLE_LIMIT = 10;
    private static final Pattern HOLOGRAM_COUNTDOWN_PATTERN = Pattern.compile("(?i)spawns? in (\\d+) second");

    private static final Map<ResourceType, List<GeneratorRecord>> generators = new EnumMap<>(ResourceType.class);
    private static final Map<Integer, Integer> lastKnownStackCounts = new HashMap<>();
    private static int tickCounter;

    private GeneratorTracker()
    {
    }

    public static void tick(Minecraft client)
    {
        ClientLevel level = client.level;
        if (level == null)
        {
            return;
        }

        tickCounter++;
        if (tickCounter % HOLOGRAM_SCAN_INTERVAL_TICKS == 0)
        {
            scanHolograms(level);
        }

        for (List<GeneratorRecord> records : generators.values())
        {
            for (GeneratorRecord record : records)
            {
                record.currentCount = 0;
            }
        }

        // entitiesForRendering() is what's actually drawn (frustum/occlusion culled); iterating the
        // level's full entity set instead means a generator pile behind the player or just outside
        // render culling still gets tracked, not just whatever happens to be on screen right now.
        for (Entity entity : ((ClientLevelAccessor) level).bedwarsresourcehud$getEntities().getAll())
        {
            if (!(entity instanceof ItemEntity itemEntity))
            {
                continue;
            }

            ItemStack stack = itemEntity.getItem();
            for (ResourceType type : ResourceType.values())
            {
                if (!type.hasGenerator() || stack.getItem() != type.getItem())
                {
                    continue;
                }

                int entityId = itemEntity.getId();
                int count = stack.getCount();
                Integer previousCount = lastKnownStackCounts.get(entityId);
                Vec3 position = itemEntity.position();

                ClusterLookup lookup = findOrCreateCluster(type, position);
                GeneratorRecord record = lookup.record();
                // Sum rather than max: this server spawns a fresh, separate item entity per
                // generator cycle instead of growing one persistent stack (confirmed via the spawn
                // diagnostics - every entity is seen once at count 1, never growing), so if nobody's
                // picked anything up there can be several un-merged entities sitting on the same
                // generator at once. Taking the max of any single one under-counted the real pile.
                record.currentCount += count;

                if (previousCount == null || count > previousCount)
                {
                    LOGGER.info("Detected {} spawn (entity {}, count {} -> {}) at {}", type, entityId,
                            previousCount == null ? 0 : previousCount, count, position);
                    if (!record.confirmedByHologram && !record.isConfirmedBySpawns())
                    {
                        logDiagnostics(level, type, itemEntity, record);
                    }
                    // A "spawn" here means either the same entity's stack grew (vanilla item
                    // merging, e.g. a server that doesn't disable it) or a brand new entity showed
                    // up at an already-known cluster (real Hypixel: every generator cycle spawns a
                    // fresh, separate item entity instead of growing one persistent pile - so
                    // previousCount is always null and count-growth alone would never confirm
                    // anything). A new entity at a brand new cluster is neither - that's the first
                    // sighting of this location, not evidence it repeats.
                    if ((previousCount != null && count > previousCount) || (previousCount == null && !lookup.isNewCluster()))
                    {
                        markSpawnEvent(record);
                    }
                    maybeAlert(type);
                }

                lastKnownStackCounts.put(entityId, count);
                break;
            }
        }

        pruneStaleEntityCounts(level);
    }

    /**
     * lastKnownStackCounts is keyed by entity id and never shrinks on its own - on a real server
     * that spawns a fresh entity per generator cycle (see above) that's an unbounded number of ids
     * over a long session, so entries for entities that no longer exist are dropped each tick.
     */
    private static void pruneStaleEntityCounts(ClientLevel level)
    {
        LevelEntityGetter<Entity> entities = ((ClientLevelAccessor) level).bedwarsresourcehud$getEntities();
        lastKnownStackCounts.keySet().removeIf(entityId -> entities.get(entityId) == null);
    }

    private static ClusterLookup findOrCreateCluster(ResourceType type, Vec3 position)
    {
        List<GeneratorRecord> records = generators.computeIfAbsent(type, ignored -> new ArrayList<>());
        for (GeneratorRecord record : records)
        {
            if (record.anchor.distanceToSqr(position) <= CLUSTER_RADIUS_SQ)
            {
                return new ClusterLookup(record, false);
            }
        }

        GeneratorRecord record = new GeneratorRecord();
        record.anchor = position;
        record.lastSpawnMillis = System.currentTimeMillis();
        record.intervalMillis = -1;
        record.spawnCount = 1;
        records.add(record);
        return new ClusterLookup(record, true);
    }

    private static void markSpawnEvent(GeneratorRecord record)
    {
        long now = System.currentTimeMillis();
        record.intervalMillis = Math.max(now - record.lastSpawnMillis, MIN_INTERVAL_MILLIS);
        record.lastSpawnMillis = now;
        record.spawnCount++;
    }

    /**
     * Called when chat confirms an Iron/Gold Forge purchase by the local player or a teammate -
     * invalidates the interval of whichever known generator of that type is closest to where the
     * purchase happened (shops sit right next to your own base, so "closest to the buyer" is a
     * reliable stand-in for "which generator this actually affects"). The record stays confirmed
     * and keeps its position - only the now-stale timing estimate is thrown out.
     */
    public static void onForgeUpgrade(ResourceType type, Vec3 nearPosition)
    {
        List<GeneratorRecord> records = generators.get(type);
        if (records == null)
        {
            return;
        }

        GeneratorRecord nearest = null;
        double nearestDistanceSq = Double.MAX_VALUE;
        for (GeneratorRecord record : records)
        {
            double distanceSq = record.anchor.distanceToSqr(nearPosition);
            if (distanceSq < nearestDistanceSq)
            {
                nearestDistanceSq = distanceSq;
                nearest = record;
            }
        }

        if (nearest == null)
        {
            return;
        }

        LOGGER.info("Forge upgrade detected for {} - invalidating interval at {} (was {}ms)", type, nearest.anchor, nearest.intervalMillis);
        nearest.intervalMillis = -1;
        nearest.hologramCountdownReadMillis = 0;
    }

    /**
     * A real generator only ever produces one resource type at its location; a spot where two
     * different tracked types both grew is a strong tell that it's actually a spot players keep
     * dying at or dumping resources at (a death drops the whole inventory - iron, gold, diamond,
     * whatever was carried - at once, at the same point), not a generator.
     */
    private static boolean hasCrossTypeCollision(ResourceType type, Vec3 anchor)
    {
        for (Map.Entry<ResourceType, List<GeneratorRecord>> entry : generators.entrySet())
        {
            if (entry.getKey() == type)
            {
                continue;
            }
            for (GeneratorRecord other : entry.getValue())
            {
                if (other.anchor.distanceToSqr(anchor) <= CLUSTER_RADIUS_SQ)
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isTrustedGenerator(ResourceType type, GeneratorRecord record)
    {
        if (record.confirmedByHologram)
        {
            return true;
        }
        if (!record.isConfirmedBySpawns())
        {
            return false;
        }
        if (hasCrossTypeCollision(type, record.anchor))
        {
            if (!record.loggedCollisionRejection)
            {
                LOGGER.info("{} at {} is spawn-confirmed but rejected: another resource type has a cluster within {} blocks",
                        type, record.anchor, CLUSTER_RADIUS_SQ);
                record.loggedCollisionRejection = true;
            }
            return false;
        }
        return true;
    }

    /**
     * Groups every currently-loaded named entity by (x, z) - a hologram is a small vertical stack
     * of armor stands sharing the same horizontal position - and, for any group of 2+ where one
     * line's text is a tracked resource's display name, immediately trusts that spot as a real
     * generator. Also opportunistically parses a "Spawns in N seconds" line for a live countdown
     * seed. Ground position is found by scanning straight down from the hologram to the first solid
     * block, since the hologram floats a few blocks above where the actual item pile would rest.
     */
    private static void scanHolograms(ClientLevel level)
    {
        List<Entity> named = new ArrayList<>();
        for (Entity entity : ((ClientLevelAccessor) level).bedwarsresourcehud$getEntities().getAll())
        {
            if (entity.hasCustomName())
            {
                named.add(entity);
            }
        }

        boolean[] consumed = new boolean[named.size()];
        int matchedGroups = 0;
        int unmatchedSamplesLogged = 0;

        for (int i = 0; i < named.size(); i++)
        {
            if (consumed[i])
            {
                continue;
            }
            Entity base = named.get(i);
            List<Entity> group = new ArrayList<>();
            group.add(base);
            consumed[i] = true;

            for (int j = i + 1; j < named.size(); j++)
            {
                if (consumed[j])
                {
                    continue;
                }
                Entity other = named.get(j);
                if (Math.abs(other.getX() - base.getX()) <= HOLOGRAM_GROUP_HORIZONTAL_TOLERANCE
                        && Math.abs(other.getZ() - base.getZ()) <= HOLOGRAM_GROUP_HORIZONTAL_TOLERANCE)
                {
                    group.add(other);
                    consumed[j] = true;
                }
            }

            if (group.size() < 2)
            {
                continue;
            }

            ResourceType matchedType = null;
            Integer countdownSeconds = null;
            double minY = Double.MAX_VALUE;
            List<String> groupTexts = new ArrayList<>();
            for (Entity member : group)
            {
                String text = member.getCustomName() != null ? member.getCustomName().getString() : "";
                groupTexts.add(text);
                if (matchedType == null)
                {
                    matchedType = matchResourceType(text);
                }
                if (countdownSeconds == null)
                {
                    countdownSeconds = parseHologramCountdown(text);
                }
                minY = Math.min(minY, member.getY());
            }

            if (matchedType == null)
            {
                // Not a resource-generator hologram (or the text format doesn't match what we know
                // of yet) - sample a few unmatched groups so the actual wording can be seen and the
                // match loosened further if needed, without flooding the log with every group.
                if (matchedGroups + unmatchedSamplesLogged < HOLOGRAM_UNMATCHED_SAMPLE_LIMIT)
                {
                    LOGGER.info("Unmatched hologram group at ({}, {}, {}): {}", base.getX(), minY, base.getZ(), groupTexts);
                    unmatchedSamplesLogged++;
                }
                continue;
            }

            BlockPos groundPos = findGroundBelow(level, BlockPos.containing(base.getX(), minY, base.getZ()));
            if (groundPos == null)
            {
                continue;
            }

            Vec3 anchor = new Vec3(groundPos.getX() + 0.5, groundPos.getY() + 1, groundPos.getZ() + 0.5);
            ClusterLookup lookup = findOrCreateCluster(matchedType, anchor);
            GeneratorRecord record = lookup.record();
            if (!record.confirmedByHologram)
            {
                LOGGER.info("Confirmed {} generator via hologram at {}", matchedType, anchor);
            }
            record.confirmedByHologram = true;

            if (countdownSeconds != null)
            {
                record.hologramCountdownReadMillis = System.currentTimeMillis();
                record.hologramCountdownSecondsAtRead = countdownSeconds;
            }

            matchedGroups++;
        }

        LOGGER.info("Hologram scan: {} named entities, {} generator groups matched", named.size(), matchedGroups);
    }

    private static ResourceType matchResourceType(String text)
    {
        // Substring rather than exact-equals: the one confirmed sample was a bare "Emerald" line,
        // but other servers/maps may decorate it ("⛏ Iron ⛏", "IRON GENERATOR", colored, etc.) - a
        // contains check still only fires on deliberate resource-name text, not on arbitrary strings.
        for (ResourceType type : ResourceType.values())
        {
            if (type.hasGenerator() && text.toLowerCase(Locale.ROOT).contains(type.getDisplayName().toLowerCase(Locale.ROOT)))
            {
                return type;
            }
        }
        return null;
    }

    private static Integer parseHologramCountdown(String text)
    {
        Matcher matcher = HOLOGRAM_COUNTDOWN_PATTERN.matcher(text);
        if (matcher.find())
        {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private static BlockPos findGroundBelow(ClientLevel level, BlockPos start)
    {
        for (int i = 0; i <= HOLOGRAM_GROUND_SCAN_DEPTH; i++)
        {
            BlockPos candidate = start.below(i);
            if (!level.getBlockState(candidate).isAir())
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Dumps everything client-observable about a candidate generator pile - position, physics
     * state, the blocks underneath it, and any named entities floating nearby - so a real
     * block+hologram signature can be worked out from the logs instead of guessing offsets. Only
     * runs pre-confirmation so it naturally quiets down once a cluster is trusted.
     */
    private static void logDiagnostics(ClientLevel level, ResourceType type, ItemEntity itemEntity, GeneratorRecord record)
    {
        Vec3 pos = itemEntity.position();
        BlockPos blockPos = itemEntity.blockPosition();

        StringBuilder blocksBelow = new StringBuilder();
        for (int offset = 1; offset <= DIAGNOSTIC_BLOCK_SCAN_DEPTH; offset++)
        {
            BlockState state = level.getBlockState(blockPos.below(offset));
            blocksBelow.append('-').append(offset).append('=').append(state.getBlock()).append(' ');
        }

        StringBuilder nearbyNamed = new StringBuilder();
        AABB searchBox = new AABB(
                pos.x - DIAGNOSTIC_SEARCH_HORIZONTAL, pos.y - DIAGNOSTIC_SEARCH_DOWN, pos.z - DIAGNOSTIC_SEARCH_HORIZONTAL,
                pos.x + DIAGNOSTIC_SEARCH_HORIZONTAL, pos.y + DIAGNOSTIC_SEARCH_UP, pos.z + DIAGNOSTIC_SEARCH_HORIZONTAL);
        ((ClientLevelAccessor) level).bedwarsresourcehud$getEntities().get(searchBox, nearby ->
        {
            if (nearby == itemEntity || !nearby.hasCustomName())
            {
                return;
            }
            nearbyNamed.append(String.format("[%s dx=%.1f dy=%.1f dz=%.1f text=\"%s\"] ",
                    nearby.getType(), nearby.getX() - pos.x, nearby.getY() - pos.y, nearby.getZ() - pos.z,
                    nearby.getCustomName().getString()));
        });

        DIAGNOSTICS_LOGGER.info(
                "type={} entity={} spawnCount={} pos={} vel={} onGround={} age={} blocksBelow=[{}] nearbyNamed=[{}]",
                type, itemEntity.getId(), record.spawnCount, pos, itemEntity.getDeltaMovement(), itemEntity.onGround(),
                itemEntity.tickCount, blocksBelow, nearbyNamed.isEmpty() ? "none" : nearbyNamed);
    }

    private static void maybeAlert(ResourceType type)
    {
        HudConfig config = HudConfig.get();
        if (!config.isAlertEnabled(type))
        {
            return;
        }

        if (config.alertSoundEnabled)
        {
            AlertSound sound = config.getAlertSound(type);
            float multiplier = (float) config.getSoundVolumeMultiplier(type.name());
            sound.play(multiplier);
        }

        if (config.alertActionBarEnabled)
        {
            Component message = Component.literal(type.getDisplayName() + " spawned!").withColor(type.getColor() & 0xFFFFFF);
            Minecraft.getInstance().gui.hud.setOverlayMessage(message, false);
        }
    }

    /**
     * Every generator confirmed reliable enough to place a waypoint on, each with its own current
     * pile size and its own countdown - generators of the same type are tracked independently
     * rather than sharing one combined record, since two of the same type won't necessarily behave
     * identically.
     */
    public static List<GeneratorWaypoint> getConfirmedWaypoints()
    {
        List<GeneratorWaypoint> waypoints = new ArrayList<>();
        for (Map.Entry<ResourceType, List<GeneratorRecord>> entry : generators.entrySet())
        {
            ResourceType type = entry.getKey();
            for (GeneratorRecord record : entry.getValue())
            {
                if (isTrustedGenerator(type, record))
                {
                    CountdownInfo info = countdownInfoFor(record);
                    waypoints.add(new GeneratorWaypoint(type, record.anchor, record.currentCount,
                            info.countdownSeconds(), info.staleSeconds(), info.estimatedCount()));
                }
            }
        }
        return waypoints;
    }

    /**
     * Three possible states: actively tracked (a real countdown), never established (no interval
     * estimate exists yet - countdownSeconds/staleSeconds both -1), or stale (staleSeconds >= 0) -
     * overdue by more than a full interval, meaning the generator has likely gone out of range or
     * out of sync range rather than actually running slow. In the stale case there's no live
     * countdown to show, so staleSeconds counts *up* from when we lost track instead, and
     * estimatedCount projects roughly how many more items have probably piled up since, assuming
     * it kept spawning on the same interval while unobserved.
     */
    private record CountdownInfo(int countdownSeconds, int staleSeconds, int estimatedCount)
    {
    }

    private static CountdownInfo countdownInfoFor(GeneratorRecord record)
    {
        // A live hologram reading is server-authoritative and always preferred while it's still
        // fresh; once enough real time has passed that it would have gone negative, fall back to
        // the interval estimated from observed spawns instead of showing a stale number.
        if (record.hologramCountdownReadMillis > 0)
        {
            long elapsedSeconds = (System.currentTimeMillis() - record.hologramCountdownReadMillis) / 1000;
            long remaining = record.hologramCountdownSecondsAtRead - elapsedSeconds;
            if (remaining >= 0)
            {
                return new CountdownInfo((int) remaining, -1, record.currentCount);
            }
        }

        if (record.intervalMillis <= 0)
        {
            return new CountdownInfo(-1, -1, record.currentCount);
        }

        long elapsedSinceLastSpawn = System.currentTimeMillis() - record.lastSpawnMillis;
        long remainingMillis = record.intervalMillis - elapsedSinceLastSpawn;
        long staleThresholdMillis = Math.max(record.intervalMillis, MIN_STALE_GRACE_MILLIS);

        if (remainingMillis < -staleThresholdMillis)
        {
            // Overdue by more than a full interval - a live generator we're still actually
            // tracking should never drift this far past due, so this means we've stopped
            // observing it (out of range, entities not currently loaded by the server) rather
            // than that it's simply slow. Clamping to 0 forever would just freeze there looking
            // broken; count up how long it's been instead, and estimate the pile size assuming it
            // kept spawning on schedule the whole time.
            int staleSeconds = (int) (elapsedSinceLastSpawn / 1000);
            // Bursts of several entities syncing in at once (e.g. right as the player walks back
            // into range) can make intervalMillis measure the gap *within* a burst rather than the
            // real gap *between* generator cycles - sub-second on a busy iron generator isn't
            // unusual. Left uncapped, dividing minutes of stale time by a near-zero interval
            // produces a nonsense estimate in the hundreds, so it's capped at a full stack's worth
            // of extra cycles, which is already generous for "how much has piled up since".
            int estimatedCycles = (int) Math.min(elapsedSinceLastSpawn / record.intervalMillis, MAX_ESTIMATED_EXTRA_CYCLES);
            return new CountdownInfo(-1, staleSeconds, record.currentCount + estimatedCycles);
        }

        return new CountdownInfo((int) Math.max(0, remainingMillis / 1000), -1, record.currentCount);
    }

    public static void reset()
    {
        int clusterCount = generators.values().stream().mapToInt(List::size).sum();
        LOGGER.info("Reset: clearing {} cluster(s) across {} type(s), {} tracked entity id(s)",
                clusterCount, generators.size(), lastKnownStackCounts.size());
        generators.clear();
        lastKnownStackCounts.clear();
        tickCounter = 0;
    }

    public record GeneratorWaypoint(ResourceType type, Vec3 position, int currentCount, int countdownSeconds,
                                     int staleSeconds, int estimatedCount)
    {
    }

    private record ClusterLookup(GeneratorRecord record, boolean isNewCluster)
    {
    }

    private static final class GeneratorRecord
    {
        Vec3 anchor;
        long lastSpawnMillis;
        long intervalMillis;
        int spawnCount;
        int currentCount;
        boolean confirmedByHologram;
        long hologramCountdownReadMillis;
        int hologramCountdownSecondsAtRead;
        boolean loggedCollisionRejection;

        boolean isConfirmedBySpawns()
        {
            return spawnCount >= CONFIRMATION_SPAWNS && intervalMillis > 0;
        }
    }
}
