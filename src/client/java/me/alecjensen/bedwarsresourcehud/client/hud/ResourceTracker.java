package me.alecjensen.bedwarsresourcehud.client.hud;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;

import java.util.EnumMap;
import java.util.Map;

/**
 * The client never has a persistent, always-synced view of the ender chest's contents: opening it
 * spins up a fresh throwaway Container on the client side (see ChestMenu's client-side factory)
 * that only gets populated while the screen is open, and punching the chest to deposit resources
 * doesn't open a screen at all. So we cache the last-seen contents from the open menu ourselves,
 * and layer a heuristic on top for the no-screen punch deposit: watch for an inventory count drop
 * in the ticks right after a punch and attribute it to the chest.
 */
public final class ResourceTracker
{
    private static final int PUNCH_DEPOSIT_WATCH_TICKS = 10;

    private static final Map<ResourceType, Integer> inventoryCounts = new EnumMap<>(ResourceType.class);
    private static final Map<ResourceType, Integer> enderChestBaseline = new EnumMap<>(ResourceType.class);
    private static final Map<ResourceType, Integer> punchDepositExtra = new EnumMap<>(ResourceType.class);
    private static final Map<ResourceType, Integer> punchWatchStart = new EnumMap<>(ResourceType.class);
    private static int punchWatchTicksLeft = 0;

    static
    {
        for (ResourceType type : ResourceType.values())
        {
            inventoryCounts.put(type, 0);
            enderChestBaseline.put(type, 0);
            punchDepositExtra.put(type, 0);
        }
    }

    private ResourceTracker()
    {
    }

    public static int getInventoryCount(ResourceType type)
    {
        return inventoryCounts.get(type);
    }

    public static int getEnderChestCount(ResourceType type)
    {
        return enderChestBaseline.get(type) + punchDepositExtra.get(type);
    }

    public static int getTotal(ResourceType type)
    {
        return getInventoryCount(type) + getEnderChestCount(type);
    }

    public static void tick(Player player)
    {
        for (ResourceType type : ResourceType.values())
        {
            inventoryCounts.put(type, player.getInventory().countItem(type.getItem()));
        }

        if (punchWatchTicksLeft > 0)
        {
            for (ResourceType type : ResourceType.values())
            {
                int before = punchWatchStart.get(type);
                int after = inventoryCounts.get(type);
                int deposited = before - after;
                if (deposited > 0)
                {
                    punchDepositExtra.merge(type, deposited, Integer::sum);
                }
                punchWatchStart.put(type, after);
            }

            punchWatchTicksLeft--;
        }
    }

    /**
     * Called every tick the ender chest screen is open, reading straight from its (live-syncing)
     * container. Once this runs, the baseline is authoritative again, so any punch-inferred extra
     * from before this sync would be double-counted and is cleared.
     */
    public static void updateFromOpenEnderChest(Container container)
    {
        for (ResourceType type : ResourceType.values())
        {
            enderChestBaseline.put(type, container.countItem(type.getItem()));
        }
        punchDepositExtra.replaceAll((type, value) -> 0);
    }

    public static void onEnderChestPunched()
    {
        punchWatchStart.putAll(inventoryCounts);
        punchWatchTicksLeft = PUNCH_DEPOSIT_WATCH_TICKS;
    }

    public static void reset()
    {
        for (ResourceType type : ResourceType.values())
        {
            inventoryCounts.put(type, 0);
            enderChestBaseline.put(type, 0);
            punchDepositExtra.put(type, 0);
        }
        punchWatchStart.clear();
        punchWatchTicksLeft = 0;
    }
}
