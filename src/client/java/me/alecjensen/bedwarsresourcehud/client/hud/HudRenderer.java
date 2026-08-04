package me.alecjensen.bedwarsresourcehud.client.hud;

import com.mojang.authlib.GameProfile;
import me.alecjensen.bedwarsresourcehud.client.config.HudConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.List;
import java.util.Locale;

public final class HudRenderer
{
    private static final Identifier ELEMENT_ID = Identifier.fromNamespaceAndPath("bedwarsresourcehud", "resources");

    private static final int PADDING = 4;
    private static final int ICON_SIZE = 16;
    private static final int TOTAL_ICON_BOX_SIZE = 18;
    private static final int TOTAL_ICON_SUB_SIZE = 13;
    private static final int HEADER_ROW_HEIGHT = TOTAL_ICON_BOX_SIZE;
    // Has to be at least ICON_SIZE (16px icons in a 12px row overlapped into the next row down,
    // which is why the icon and its number never quite lined up) - the extra couple pixels on top
    // give a bit of breathing room between rows instead of the icons touching edge to edge.
    private static final int ROW_HEIGHT = 18;
    private static final int ICON_COLUMN_WIDTH = ICON_SIZE;
    private static final int COLUMN_WIDTH = 20;

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
            if (config.onlyShowOnHypixel && !isConnectedToHypixel())
            {
                return;
            }

            render(graphics, config.x, config.y);
        });
    }

    private static boolean isConnectedToHypixel()
    {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null)
        {
            return false;
        }

        ServerData serverData = connection.getServerData();
        return serverData != null && serverData.ip != null && serverData.ip.toLowerCase(Locale.ROOT).contains("hypixel.net");
    }

    public static int getPanelWidth()
    {
        return ICON_COLUMN_WIDTH + COLUMN_WIDTH * 3 + PADDING * 2;
    }

    public static int getPanelHeight()
    {
        return PADDING * 2 + HEADER_ROW_HEIGHT + ROW_HEIGHT * HudConfig.get().getAllTrackedItems().size();
    }

    public static void render(GuiGraphicsExtractor graphics, int x, int y)
    {
        Font font = Minecraft.getInstance().font;
        List<TrackedItem> items = HudConfig.get().getAllTrackedItems();

        int headerY = y + PADDING;
        int invColumnX = x + PADDING + ICON_COLUMN_WIDTH;
        int enderColumnX = invColumnX + COLUMN_WIDTH;
        int totalColumnX = enderColumnX + COLUMN_WIDTH;

        // Constructing an ItemStack requires the item registry's data components to be bound,
        // which is only true once a world/connection is active (not, e.g., at the title screen,
        // where Mod Menu's config screen can still be opened) - crashes with
        // "Components not bound yet" otherwise, so icons are skipped entirely outside a world.
        boolean canRenderItems = Minecraft.getInstance().player != null;

        if (canRenderItems)
        {
            ItemStack playerHeadIcon = createPlayerHeadIcon();
            ItemStack enderChestIcon = new ItemStack(Items.ENDER_CHEST);

            int singleIconY = headerY + (HEADER_ROW_HEIGHT - ICON_SIZE) / 2;
            graphics.item(playerHeadIcon, invColumnX + (COLUMN_WIDTH - ICON_SIZE) / 2, singleIconY);
            graphics.item(enderChestIcon, enderColumnX + (COLUMN_WIDTH - ICON_SIZE) / 2, singleIconY);

            int totalBoxX = totalColumnX + (COLUMN_WIDTH - TOTAL_ICON_BOX_SIZE) / 2;
            drawTotalHeaderIcon(graphics, playerHeadIcon, enderChestIcon, totalBoxX, headerY);
        }

        int rowIconY = (ROW_HEIGHT - ICON_SIZE) / 2;
        int rowTextY = (ROW_HEIGHT - font.lineHeight) / 2;

        int rowY = headerY + HEADER_ROW_HEIGHT;
        for (TrackedItem item : items)
        {
            if (canRenderItems)
            {
                graphics.item(new ItemStack(item.getItem()), x + PADDING + (ICON_COLUMN_WIDTH - ICON_SIZE) / 2, rowY + rowIconY);
            }

            int color = item.getColor();
            drawCenteredText(graphics, font, String.valueOf(ResourceTracker.getInventoryCount(item)), invColumnX, COLUMN_WIDTH, rowY + rowTextY, color);
            drawCenteredText(graphics, font, String.valueOf(ResourceTracker.getEnderChestCount(item)), enderColumnX, COLUMN_WIDTH, rowY + rowTextY, color);
            drawCenteredText(graphics, font, String.valueOf(ResourceTracker.getTotal(item)), totalColumnX, COLUMN_WIDTH, rowY + rowTextY, color);

            rowY += ROW_HEIGHT;
        }
    }

    private static void drawCenteredText(GuiGraphicsExtractor graphics, Font font, String text, int columnX, int columnWidth, int y, int color)
    {
        int drawX = columnX + (columnWidth - font.width(text)) / 2;
        graphics.text(font, Component.literal(text), drawX, y, color, true);
    }

    /**
     * Both icons are drawn bigger than half the box, so they overlap in the shared corner.
     * The ender chest is drawn first and the player head second, so the head's opaque render
     * simply covers the overlapping corner instead of the two icons blending together.
     */
    private static void drawTotalHeaderIcon(GuiGraphicsExtractor graphics, ItemStack playerHeadIcon, ItemStack enderChestIcon, int boxX, int boxY)
    {
        drawScaledIcon(graphics, enderChestIcon, boxX + TOTAL_ICON_BOX_SIZE - TOTAL_ICON_SUB_SIZE, boxY + TOTAL_ICON_BOX_SIZE - TOTAL_ICON_SUB_SIZE, TOTAL_ICON_SUB_SIZE);
        drawScaledIcon(graphics, playerHeadIcon, boxX, boxY, TOTAL_ICON_SUB_SIZE);
    }

    private static void drawScaledIcon(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y, int size)
    {
        float scale = (float) size / ICON_SIZE;
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale);
        graphics.item(stack, 0, 0);
        graphics.pose().popMatrix();
    }

    private static ItemStack createPlayerHeadIcon()
    {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        Player player = Minecraft.getInstance().player;
        if (player != null)
        {
            stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(resolveProfileWithSkin(player)));
        }
        return stack;
    }

    /**
     * Player#getGameProfile() only carries name+UUID over real multiplayer - the actual skin
     * texture properties arrive separately via the tab list (PlayerInfo), so that's what has to
     * be used to get a real skin instead of the default Steve render.
     */
    private static GameProfile resolveProfileWithSkin(Player player)
    {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null)
        {
            PlayerInfo playerInfo = connection.getPlayerInfo(player.getUUID());
            if (playerInfo != null)
            {
                return playerInfo.getProfile();
            }
        }
        return player.getGameProfile();
    }
}
