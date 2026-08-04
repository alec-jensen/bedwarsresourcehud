package me.alecjensen.bedwarsresourcehud.client.hud;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum ResourceType implements TrackedItem
{
    IRON(Items.IRON_INGOT, 0xFFFFFFFF, "Iron", true, true),
    GOLD(Items.GOLD_INGOT, 0xFFFFAA00, "Gold", true, true),
    DIAMOND(Items.DIAMOND, 0xFF55FFFF, "Diamond", true, true),
    EMERALD(Items.EMERALD, 0xFF55FF55, "Emerald", true, true),
    ARROW(Items.ARROW, 0xFFCCCCCC, "Arrows", false, false),
    GOLDEN_APPLE(Items.GOLDEN_APPLE, 0xFFFFCC33, "Golden Apple", false, false);

    private final Item item;
    private final int color;
    private final String displayName;
    private final boolean defaultEnabled;
    private final boolean hasGenerator;

    ResourceType(Item item, int color, String displayName, boolean defaultEnabled, boolean hasGenerator)
    {
        this.item = item;
        this.color = color;
        this.displayName = displayName;
        this.defaultEnabled = defaultEnabled;
        this.hasGenerator = hasGenerator;
    }

    @Override
    public Item getItem()
    {
        return item;
    }

    @Override
    public int getColor()
    {
        return color;
    }

    @Override
    public String getConfigId()
    {
        return name();
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public boolean isDefaultEnabled()
    {
        return defaultEnabled;
    }

    /**
     * Whether this item spawns from a physical Bedwars generator (iron/gold/diamond/emerald),
     * as opposed to being shop-only (arrows, golden apples) - controls whether spawn tracking,
     * alerts, and the countdown column apply to it at all.
     */
    @Override
    public boolean hasGenerator()
    {
        return hasGenerator;
    }
}
