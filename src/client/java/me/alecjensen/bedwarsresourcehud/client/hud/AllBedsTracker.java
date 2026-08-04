package me.alecjensen.bedwarsresourcehud.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;

import java.util.HashMap;
import java.util.Map;

/**
 * Every bed discovered anywhere on the map so far, not just your own - unlike BedAlarmTracker
 * (which only ever needs the one nearest your spawn), this scans a radius around wherever you
 * currently are, every cycle, so beds get discovered gradually as you walk around the map rather
 * than needing to already know where they all are on day one. Once found, a bed stays tracked
 * (and keeps its color) until it's re-checked and found to no longer be a bed block - i.e. broken.
 */
public final class AllBedsTracker
{
    private static final int SCAN_INTERVAL_TICKS = 30;
    private static final int SCAN_HORIZONTAL_RADIUS = 24;
    private static final int SCAN_VERTICAL_RADIUS = 8;

    private static volatile Map<BlockPos, DyeColor> beds = Map.of();
    private static int tickCounter;

    private AllBedsTracker()
    {
    }

    public static Map<BlockPos, DyeColor> getBeds()
    {
        return beds;
    }

    public static void reset()
    {
        beds = Map.of();
        tickCounter = 0;
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

        Map<BlockPos, DyeColor> updated = new HashMap<>(beds);
        updated.keySet().removeIf(pos -> !(level.getBlockState(pos).getBlock() instanceof BedBlock));

        BlockPos center = self.blockPosition();
        for (int dx = -SCAN_HORIZONTAL_RADIUS; dx <= SCAN_HORIZONTAL_RADIUS; dx++)
        {
            for (int dy = -SCAN_VERTICAL_RADIUS; dy <= SCAN_VERTICAL_RADIUS; dy++)
            {
                for (int dz = -SCAN_HORIZONTAL_RADIUS; dz <= SCAN_HORIZONTAL_RADIUS; dz++)
                {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (updated.containsKey(pos))
                    {
                        continue;
                    }
                    if (level.getBlockState(pos).getBlock() instanceof BedBlock bed)
                    {
                        updated.put(pos.immutable(), bed.getColor());
                    }
                }
            }
        }

        beds = updated;
    }
}
