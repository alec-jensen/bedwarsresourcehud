package me.alecjensen.bedwarsresourcehud.client.hud;

import me.alecjensen.bedwarsresourcehud.client.config.HudConfig;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The client never has a persistent, always-synced view of the ender chest's contents: opening it
 * spins up a fresh throwaway Container on the client side (see ChestMenu's client-side factory)
 * that only gets populated while the screen is open, and punching the chest to deposit resources
 * doesn't open a screen at all. So we cache the last-seen contents from the open menu ourselves,
 * and layer a heuristic on top for the no-screen punch deposit: watch for an inventory count drop
 * in the ticks right after a punch and attribute it to the chest.
 *
 * Tracked items are keyed by TrackedItem#getConfigId() rather than the item itself, since custom
 * items are created fresh each time HudConfig#getAllTrackedItems is called.
 */
public final class ResourceTracker
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BedwarsResourceHud/ResourceTracker");

    // 0.5s was sized against loopback/singleplayer testing; widened to give real remote-server
    // latency (Hypixel included) headroom to actually deliver the resulting inventory update
    // before the watch window closes.
    private static final int PUNCH_DEPOSIT_WATCH_TICKS = 20;

    private static final Map<String, Integer> inventoryCounts = new HashMap<>();
    private static final Map<String, Integer> enderChestBaseline = new HashMap<>();
    private static final Map<String, Integer> punchDepositExtra = new HashMap<>();
    private static final Map<String, Integer> punchWatchStart = new HashMap<>();
    private static int punchWatchTicksLeft = 0;

    // Captured from whatever was in hand at the moment of the punch - the expected deposit,
    // rather than a guess inferred from whatever inventory change happens to occur afterward.
    private static String punchHeldItemId;
    private static int punchHeldItemCount;

    private ResourceTracker()
    {
    }

    public static int getInventoryCount(TrackedItem item)
    {
        return inventoryCounts.getOrDefault(item.getConfigId(), 0);
    }

    public static int getEnderChestCount(TrackedItem item)
    {
        return enderChestBaseline.getOrDefault(item.getConfigId(), 0) + punchDepositExtra.getOrDefault(item.getConfigId(), 0);
    }

    public static int getTotal(TrackedItem item)
    {
        return getInventoryCount(item) + getEnderChestCount(item);
    }

    public static void tick(Player player)
    {
        List<TrackedItem> items = HudConfig.get().getAllTrackedItems();

        for (TrackedItem item : items)
        {
            inventoryCounts.put(item.getConfigId(), player.getInventory().countItem(item.getItem()));
        }

        if (punchWatchTicksLeft > 0)
        {
            boolean anyDeposit = false;
            for (TrackedItem item : items)
            {
                String id = item.getConfigId();
                int before = punchWatchStart.getOrDefault(id, 0);
                int after = inventoryCounts.getOrDefault(id, 0);
                int decreased = before - after;
                int deposited;

                if (id.equals(punchHeldItemId) && punchHeldItemCount > 0)
                {
                    // The item+quantity that was actually in hand when the chest was punched is
                    // the expected deposit - capping the credit at that instead of trusting an
                    // arbitrary inventory decrease means something unrelated happening to the same
                    // item type in the same window (picking more up, losing some to combat) can't
                    // inflate what gets credited beyond what was really held.
                    deposited = Math.min(Math.max(decreased, 0), punchHeldItemCount);
                    if (deposited > 0)
                    {
                        punchHeldItemCount -= deposited;
                        if (decreased != deposited)
                        {
                            LOGGER.warn("Punch-deposit: {} decreased by {} but only {} matches the held quantity - crediting {}",
                                    id, decreased, deposited, deposited);
                        }
                    }
                }
                else
                {
                    deposited = Math.max(decreased, 0);
                }

                if (deposited > 0)
                {
                    punchDepositExtra.merge(id, deposited, Integer::sum);
                    LOGGER.info("Punch-deposit: {} x{} (inventory {} -> {})", id, deposited, before, after);
                    anyDeposit = true;
                }
                punchWatchStart.put(id, after);
            }

            punchWatchTicksLeft--;
            if (punchWatchTicksLeft == 0 && !anyDeposit)
            {
                // Not necessarily wrong (maybe the punch didn't actually deposit anything, or the
                // player wasn't carrying any tracked item), but this is the one silent-failure mode
                // of the whole heuristic - if the real deposit lands after the watch window closes,
                // it's simply never attributed, and this is the only place that would show it.
                LOGGER.info("Punch-deposit watch window closed with no deposit detected");
            }
        }
    }

    /**
     * Called every tick the ender chest screen is open, reading straight from its (live-syncing)
     * container. Once this runs, the baseline is authoritative again, so any punch-inferred extra
     * from before this sync would be double-counted and is cleared.
     */
    public static void updateFromOpenEnderChest(Container container)
    {
        Map<String, Integer> updated = new HashMap<>();
        for (TrackedItem item : HudConfig.get().getAllTrackedItems())
        {
            int count = container.countItem(item.getItem());
            enderChestBaseline.put(item.getConfigId(), count);
            updated.put(item.getConfigId(), count);
        }
        punchDepositExtra.replaceAll((id, value) -> 0);
        LOGGER.info("Ender chest opened, baseline synced: {}", updated);
    }

    public static void onEnderChestPunched(Player player, InteractionHand hand)
    {
        punchWatchStart.clear();
        punchWatchStart.putAll(inventoryCounts);
        punchWatchTicksLeft = PUNCH_DEPOSIT_WATCH_TICKS;

        punchHeldItemId = null;
        punchHeldItemCount = 0;
        ItemStack held = player.getItemInHand(hand);
        if (!held.isEmpty())
        {
            for (TrackedItem item : HudConfig.get().getAllTrackedItems())
            {
                if (item.getItem() == held.getItem())
                {
                    punchHeldItemId = item.getConfigId();
                    punchHeldItemCount = held.getCount();
                    break;
                }
            }
        }

        LOGGER.info("Ender chest punched, watching inventory for {} ticks (held: {} x{})",
                PUNCH_DEPOSIT_WATCH_TICKS, punchHeldItemId, punchHeldItemCount);
    }

    public static void reset()
    {
        LOGGER.info("Reset");
        inventoryCounts.clear();
        enderChestBaseline.clear();
        punchDepositExtra.clear();
        punchWatchStart.clear();
        punchWatchTicksLeft = 0;
        punchHeldItemId = null;
        punchHeldItemCount = 0;
    }
}
