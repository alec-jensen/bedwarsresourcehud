package me.alecjensen.bedwarsresourcehud.client.hud;

import net.minecraft.world.item.Item;

/**
 * A player-added item tracked by its registry ID (e.g. "minecraft:diamond_sword"). Never has a
 * generator, since we have no way to know whether an arbitrary item actually spawns from one.
 */
public final class CustomTrackedItem implements TrackedItem
{
    private static final int DEFAULT_COLOR = 0xFFFFFFFF;

    private final Item item;
    private final String itemId;

    public CustomTrackedItem(Item item, String itemId)
    {
        this.item = item;
        this.itemId = itemId;
    }

    @Override
    public Item getItem()
    {
        return item;
    }

    @Override
    public int getColor()
    {
        return DEFAULT_COLOR;
    }

    @Override
    public boolean hasGenerator()
    {
        return false;
    }

    @Override
    public String getConfigId()
    {
        return itemId;
    }

    public String getItemId()
    {
        return itemId;
    }
}
