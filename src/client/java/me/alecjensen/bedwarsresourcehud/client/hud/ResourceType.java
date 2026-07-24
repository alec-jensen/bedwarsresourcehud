package me.alecjensen.bedwarsresourcehud.client.hud;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum ResourceType
{
    IRON(Items.IRON_INGOT, 0xFFFFFFFF),
    GOLD(Items.GOLD_INGOT, 0xFFFFAA00),
    DIAMOND(Items.DIAMOND, 0xFF55FFFF),
    EMERALD(Items.EMERALD, 0xFF55FF55);

    private final Item item;
    private final int color;

    ResourceType(Item item, int color)
    {
        this.item = item;
        this.color = color;
    }

    public Item getItem()
    {
        return item;
    }

    public int getColor()
    {
        return color;
    }
}
