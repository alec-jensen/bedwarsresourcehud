package me.alecjensen.bedwarsresourcehud.client.hud;

import me.alecjensen.bedwarsresourcehud.client.config.HudConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public final class HudRenderer
{
    private static final Identifier ELEMENT_ID = Identifier.fromNamespaceAndPath("bedwarsresourcehud", "resources");

    private static final int PADDING = 6;
    private static final int ROW_HEIGHT = 12;
    private static final int ICON_COLUMN_WIDTH = 18;
    private static final int COLUMN_WIDTH = 34;
    private static final int PANEL_WIDTH = ICON_COLUMN_WIDTH + COLUMN_WIDTH * 3 + PADDING * 2;
    private static final int PANEL_HEIGHT = PADDING * 2 + ROW_HEIGHT * (ResourceType.values().length + 1);
    private static final int HEADER_COLOR = 0xFFAAAAAA;

    private HudRenderer()
    {
    }

    public static void register()
    {
        HudElementRegistry.addLast(ELEMENT_ID, (graphics, tickCounter) ->
        {
            if (Minecraft.getInstance().player == null)
            {
                return;
            }

            HudConfig config = HudConfig.get();
            render(graphics, config.x, config.y);
        });
    }

    public static int getPanelWidth()
    {
        return PANEL_WIDTH;
    }

    public static int getPanelHeight()
    {
        return PANEL_HEIGHT;
    }

    public static void render(GuiGraphicsExtractor graphics, int x, int y)
    {
        Font font = Minecraft.getInstance().font;

        int textY = y + PADDING;
        int invColumnX = x + PADDING + ICON_COLUMN_WIDTH;
        int enderColumnX = invColumnX + COLUMN_WIDTH;
        int totalColumnX = enderColumnX + COLUMN_WIDTH;

        graphics.text(font, Component.literal("Inv"), invColumnX, textY, HEADER_COLOR, true);
        graphics.text(font, Component.literal("Ender"), enderColumnX, textY, HEADER_COLOR, true);
        graphics.text(font, Component.literal("Total"), totalColumnX, textY, HEADER_COLOR, true);

        int rowY = textY + ROW_HEIGHT;
        for (ResourceType type : ResourceType.values())
        {
            graphics.item(new ItemStack(type.getItem()), x + PADDING, rowY - 1);

            int color = type.getColor();
            graphics.text(font, Component.literal(String.valueOf(ResourceTracker.getInventoryCount(type))), invColumnX, rowY + 2, color, true);
            graphics.text(font, Component.literal(String.valueOf(ResourceTracker.getEnderChestCount(type))), enderColumnX, rowY + 2, color, true);
            graphics.text(font, Component.literal(String.valueOf(ResourceTracker.getTotal(type))), totalColumnX, rowY + 2, color, true);

            rowY += ROW_HEIGHT;
        }
    }
}
