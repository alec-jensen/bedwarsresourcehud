package me.alecjensen.bedwarsresourcehud.client.hud;

import me.alecjensen.bedwarsresourcehud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A proximity alarm for your own bed that doesn't depend on a teammate calling it out: finds the
 * nearest bed block to wherever you're standing right after a match starts (Bedwars spawns you
 * right next to your own bed, and there's exactly one bed per team, so "nearest bed to spawn" is
 * a reliable stand-in for "my bed" without needing to work out your team's color), then every tick
 * checks the real distance from every enemy to that fixed point and fires a loud alert the moment
 * one first crosses into range - regardless of whether that enemy is even near you.
 *
 * Deliberately keeps using the bed's location even after the bed itself is broken - someone closing
 * in on your base to finish you off is if anything more worth knowing about once the bed's gone.
 */
public final class BedAlarmTracker
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BedwarsResourceHud/BedAlarmTracker");

    private static final int BED_SCAN_INTERVAL_TICKS = 40;
    private static final int BED_SCAN_HORIZONTAL_RADIUS = 20;
    private static final int BED_SCAN_VERTICAL_RADIUS = 6;
    private static final int BED_SCAN_MAX_ATTEMPTS = 20;

    private static BlockPos bedPos;
    private static int bedScanAttempts;
    private static int tickCounter;
    private static Set<UUID> playersInside = Set.of();
    // Per-intruder, not global: a genuinely new threat (or the same one back after a real absence)
    // should still alert immediately even if someone else just tripped the alarm a second ago.
    private static final Map<UUID, Integer> lastAlertTick = new HashMap<>();

    private BedAlarmTracker()
    {
    }

    public static BlockPos getBedPos()
    {
        return bedPos;
    }

    public static void reset()
    {
        if (bedPos != null || bedScanAttempts > 0)
        {
            LOGGER.info("Resetting bed alarm tracker (bed was at {})", bedPos);
        }
        bedPos = null;
        bedScanAttempts = 0;
        playersInside = Set.of();
        lastAlertTick.clear();
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

        if (bedPos == null)
        {
            if (bedScanAttempts >= BED_SCAN_MAX_ATTEMPTS || tickCounter % BED_SCAN_INTERVAL_TICKS != 0)
            {
                return;
            }

            bedScanAttempts++;
            BlockPos found = findNearestBed(level, self.blockPosition());
            if (found != null)
            {
                bedPos = found;
                LOGGER.info("Located own bed at {} on attempt {}", found, bedScanAttempts);
            }
            else if (bedScanAttempts >= BED_SCAN_MAX_ATTEMPTS)
            {
                LOGGER.warn("Failed to locate own bed after {} attempts near {} - bed alarm disabled for this match",
                        bedScanAttempts, self.blockPosition());
            }
            return;
        }

        tickAlarm(level, self);
    }

    private static BlockPos findNearestBed(ClientLevel level, BlockPos center)
    {
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (int dx = -BED_SCAN_HORIZONTAL_RADIUS; dx <= BED_SCAN_HORIZONTAL_RADIUS; dx++)
        {
            for (int dy = -BED_SCAN_VERTICAL_RADIUS; dy <= BED_SCAN_VERTICAL_RADIUS; dy++)
            {
                for (int dz = -BED_SCAN_HORIZONTAL_RADIUS; dz <= BED_SCAN_HORIZONTAL_RADIUS; dz++)
                {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!(level.getBlockState(pos).getBlock() instanceof BedBlock))
                    {
                        continue;
                    }

                    double distSq = pos.distSqr(center);
                    if (distSq < nearestDistSq)
                    {
                        nearestDistSq = distSq;
                        nearest = pos;
                    }
                }
            }
        }
        return nearest;
    }

    private static void tickAlarm(ClientLevel level, Player self)
    {
        HudConfig config = HudConfig.get();
        if (!config.bedAlarmEnabled)
        {
            playersInside = Set.of();
            return;
        }

        double radiusSq = config.bedAlarmRadius * config.bedAlarmRadius;
        Vec3 bedCenter = Vec3.atCenterOf(bedPos);
        Set<UUID> currentlyInside = new HashSet<>();

        for (AbstractClientPlayer other : level.players())
        {
            if (other == self || TeamUtil.isTeammate(self, other) || TeamUtil.isLikelyNpc(other.getGameProfile().name()))
            {
                continue;
            }

            if (other.position().distanceToSqr(bedCenter) > radiusSq)
            {
                continue;
            }

            currentlyInside.add(other.getUUID());
            if (!playersInside.contains(other.getUUID()))
            {
                onIntruderEntered(config, other);
            }
        }

        playersInside = currentlyInside;
    }

    /**
     * Fires every time an intruder transitions from outside the radius to inside it - but the
     * actual sound/message is debounced per-UUID so someone jittering in and out right at the
     * boundary (or briefly stepping out and back) doesn't spam the alert. A real absence longer
     * than the debounce window, or a different intruder entirely, still alerts right away.
     */
    private static void onIntruderEntered(HudConfig config, AbstractClientPlayer intruder)
    {
        UUID uuid = intruder.getUUID();
        String name = intruder.getGameProfile().name();
        LOGGER.info("Bed alarm: {} entered the {}-block radius around your bed", name, config.bedAlarmRadius);

        int debounceTicks = (int) (config.bedAlarmDebounceSeconds * 20);
        Integer lastTick = lastAlertTick.get(uuid);
        if (lastTick != null && tickCounter - lastTick < debounceTicks)
        {
            LOGGER.info("Bed alarm: suppressing repeat alert for {} (debounced)", name);
            return;
        }
        lastAlertTick.put(uuid, tickCounter);

        if (config.alertSoundEnabled)
        {
            AlertSound sound = config.getBedAlarmSound();
            float multiplier = (float) config.getSoundVolumeMultiplier(HudConfig.BED_ALARM_VOLUME_KEY);
            sound.play(multiplier);
        }

        if (config.alertActionBarEnabled)
        {
            Component message = Component.literal(name + " is near your bed!").withColor(0xFFFF5555);
            Minecraft.getInstance().gui.hud.setOverlayMessage(message, false);
        }
    }
}
