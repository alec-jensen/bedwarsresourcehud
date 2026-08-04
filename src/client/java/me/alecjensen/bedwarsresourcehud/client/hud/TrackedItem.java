package me.alecjensen.bedwarsresourcehud.client.hud;

import net.minecraft.world.item.Item;

/**
 * A single row in the HUD: either one of the curated {@link ResourceType} entries (which know
 * about their generator/alert behavior) or a {@link CustomTrackedItem} the player added by item
 * ID, which is always inventory/ender-chest/total tracking only.
 */
public interface TrackedItem
{
    Item getItem();

    int getColor();

    boolean hasGenerator();

    /** Unique key used to store enabled/alert state in the config file. */
    String getConfigId();
}
