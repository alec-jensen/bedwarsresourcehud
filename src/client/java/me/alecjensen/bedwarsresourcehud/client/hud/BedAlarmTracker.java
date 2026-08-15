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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
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
 *
 * Only runs between a confirmed match start (see onMatchStart) and the next level change - without
 * that gate, this would also start scanning the moment you're anywhere Bed Wars-related, including
 * the Bed Wars portal/practice lobby (its own scoreboard sidebar says "BED WARS" too, same as an
 * actual arena), latch onto some bed-shaped prop there, and blast the alert sound at everyone who's
 * just normal foot traffic walking near it. The sidebar title alone can't tell those apart - only
 * the literal "match has begun" system message can.
 */
public final class BedAlarmTracker
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BedwarsResourceHud/BedAlarmTracker");

    private static final int BED_SCAN_INTERVAL_TICKS = 40;
    private static final int BED_SCAN_HORIZONTAL_RADIUS = 24;
    // Some maps put the spawn pad noticeably above or below the actual bed platform - 6 was too
    // tight and missed those entirely no matter how long it kept retrying at the same spot.
    private static final int BED_SCAN_VERTICAL_RADIUS = 16;
    private static final int BED_SCAN_MAX_ATTEMPTS = 20;

    // A player who just respawned can briefly read as teammate-less/enemy-colored for a tick or
    // two before their team assignment catches up client-side - most noticeable when a teammate
    // respawns right next to your own bed after a void death, which looks exactly like an intruder
    // walking in if taken at face value on the very first tick. Requiring this many *consecutive*
    // ticks of "in radius and not a teammate" before alerting rides out that gap; a real intruder
    // closing in on foot is still well past this by the time they're worth alerting about.
    private static final int INTRUDER_CONFIRM_TICKS = 6;

    private static BlockPos bedPos;
    // Captured once, on the first scan attempt, and reused for every retry - re-centering each
    // retry on the player's live position instead meant a slow chunk load on the very first
    // attempt (right as you spawn, before nearby chunks are guaranteed to be in) combined with
    // just walking off toward the shop/generator would make later retries search around wherever
    // you'd wandered to instead of back where your bed actually is, and fail outright.
    private static BlockPos scanAnchor;
    private static int bedScanAttempts;
    private static int tickCounter;
    private static boolean matchActive;
    // Confirmed intruders only - i.e. already past INTRUDER_CONFIRM_TICKS - used purely to detect
    // the outside-to-inside transition that triggers a fresh alert.
    private static Set<UUID> playersInside = Set.of();
    // Consecutive-tick counters for "in radius and not a teammate right now", per UUID; reset the
    // instant that stops being true for someone (radius exit, or a - possibly late-arriving -
    // teammate reassignment), so a real streak has to be earned freshly each time.
    private static final Map<UUID, Integer> candidateStreaks = new HashMap<>();
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

    /**
     * Called by ChatDiagnosticsLogger the moment it sees Hypixel's "Protect your bed and destroy
     * the enemy beds." system message - the one text that's guaranteed to mean an actual match
     * just began, as opposed to merely standing somewhere Bed Wars-related.
     */
    public static void onMatchStart()
    {
        LOGGER.info("Bed Wars match start detected - arming bed alarm");
        matchActive = true;
    }

    public static void reset()
    {
        if (bedPos != null || bedScanAttempts > 0)
        {
            LOGGER.info("Resetting bed alarm tracker (bed was at {})", bedPos);
        }
        bedPos = null;
        scanAnchor = null;
        bedScanAttempts = 0;
        matchActive = false;
        playersInside = Set.of();
        candidateStreaks.clear();
        lastAlertTick.clear();
    }

    public static void tick(Minecraft client)
    {
        ClientLevel level = client.level;
        Player self = client.player;
        if (level == null || self == null || !matchActive)
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

            if (scanAnchor == null)
            {
                scanAnchor = self.blockPosition();
                LOGGER.info("Anchoring bed scan at {}", scanAnchor);
            }

            bedScanAttempts++;
            BlockPos livePos = self.blockPosition();
            // Try the original spawn anchor first (covers a bed that was there all along but got
            // missed early on by a chunk that hadn't loaded yet), then fall back to wherever the
            // player actually is right now (covers a bed that's simply too far from spawn to be
            // in range of the anchor at all, on maps where you have to walk toward your base).
            BlockPos found = findNearestBed(level, scanAnchor);
            if (found == null && !livePos.equals(scanAnchor))
            {
                found = findNearestBed(level, livePos);
            }
            if (found != null)
            {
                bedPos = found;
                LOGGER.info("Located own bed at {} on attempt {}", found, bedScanAttempts);
            }
            else if (bedScanAttempts >= BED_SCAN_MAX_ATTEMPTS)
            {
                LOGGER.warn("Failed to locate own bed after {} attempts (anchor {}, last position {}) - bed alarm disabled for this match",
                        bedScanAttempts, scanAnchor, livePos);
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

        // Canonicalize to the FOOT half so this lines up with AllBedsTracker's own bed keys
        // (used for the "Your Bed" label) regardless of which half happened to be nearest.
        if (nearest != null)
        {
            BlockState state = level.getBlockState(nearest);
            if (state.getValue(BedBlock.PART) == BedPart.HEAD)
            {
                nearest = nearest.relative(BedBlock.getConnectedDirection(state));
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
            candidateStreaks.clear();
            return;
        }

        double radiusSq = config.bedAlarmRadius * config.bedAlarmRadius;
        Vec3 bedCenter = Vec3.atCenterOf(bedPos);
        Set<UUID> currentCandidates = new HashSet<>();
        Set<UUID> currentlyInside = new HashSet<>();

        for (AbstractClientPlayer other : level.players())
        {
            if (other == self || TeamUtil.isLikelyNpc(other.getGameProfile().name()))
            {
                continue;
            }

            boolean isCandidate = !TeamUtil.isTeammate(self, other) && other.position().distanceToSqr(bedCenter) <= radiusSq;
            if (!isCandidate)
            {
                continue;
            }

            UUID uuid = other.getUUID();
            currentCandidates.add(uuid);
            int streak = candidateStreaks.merge(uuid, 1, Integer::sum);
            if (streak < INTRUDER_CONFIRM_TICKS)
            {
                continue;
            }

            currentlyInside.add(uuid);
            if (!playersInside.contains(uuid))
            {
                onIntruderEntered(config, other);
            }
        }

        // Anyone who dropped out of candidacy (left the radius, or now correctly reads as a
        // teammate) loses their progress - a later intrusion has to earn the streak again.
        candidateStreaks.keySet().retainAll(currentCandidates);
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
