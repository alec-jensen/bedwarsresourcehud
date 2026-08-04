package me.alecjensen.bedwarsresourcehud.client.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Lets players track any item in the game by its registry ID (e.g. "diamond_sword"), not just the
 * curated resource list. Rebuilds itself (via a fresh instance) after every add/remove instead of
 * patching the widget list in place, since that's simpler than tracking row widgets individually.
 */
public class CustomItemsScreen extends Screen
{
    private static final int ROW_HEIGHT = 20;
    private static final int LIST_TOP = 70;

    private final Screen parent;
    private final HudConfig config;
    private EditBox itemIdBox;
    private String errorMessage;

    public CustomItemsScreen(Screen parent)
    {
        super(Component.literal("Custom Tracked Items"));
        this.parent = parent;
        this.config = HudConfig.get();
    }

    @Override
    protected void init()
    {
        int centerX = this.width / 2;

        itemIdBox = new EditBox(this.font, centerX - 150, 32, 220, 20, Component.literal("Item ID"));
        itemIdBox.setHint(Component.literal("diamond_sword"));
        this.addRenderableWidget(itemIdBox);

        this.addRenderableWidget(Button.builder(Component.literal("Add"), button -> onAdd())
                .bounds(centerX + 75, 32, 75, 20)
                .build());

        List<String> items = config.customItems;
        for (int i = 0; i < items.size(); i++)
        {
            String itemId = items.get(i);
            int rowY = LIST_TOP + i * ROW_HEIGHT;

            this.addRenderableWidget(Button.builder(Component.literal("X"), button ->
                    {
                        config.removeCustomItem(itemId);
                        this.minecraft.gui.setScreen(new CustomItemsScreen(parent));
                    })
                    .bounds(centerX + 130, rowY, 20, 20)
                    .build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> this.onClose())
                .bounds(centerX - 50, this.height - 26, 100, 20)
                .build());
    }

    private void onAdd()
    {
        String rawId = itemIdBox.getValue();
        if (rawId.isBlank())
        {
            return;
        }

        if (config.addCustomItem(rawId))
        {
            itemIdBox.setValue("");
            errorMessage = null;
            this.minecraft.gui.setScreen(new CustomItemsScreen(parent));
        }
        else
        {
            errorMessage = "Not a valid or already-tracked item: " + rawId;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)
    {
        this.extractTransparentBackground(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.getTitle(), this.width / 2, 12, 0xFFFFFFFF);

        if (errorMessage != null)
        {
            graphics.centeredText(this.font, Component.literal(errorMessage), this.width / 2, 56, 0xFFFF5555);
        }

        boolean canRenderItems = Minecraft.getInstance().player != null;
        int centerX = this.width / 2;
        List<String> items = config.customItems;
        for (int i = 0; i < items.size(); i++)
        {
            String itemId = items.get(i);
            int rowY = LIST_TOP + i * ROW_HEIGHT;

            if (canRenderItems)
            {
                HudConfig.resolveItem(itemId).ifPresent(item -> graphics.item(new ItemStack(item), centerX - 150, rowY + 2));
            }
            graphics.text(this.font, Component.literal(itemId), centerX - 128, rowY + 6, 0xFFFFFFFF, true);
        }
    }

    @Override
    public void onClose()
    {
        config.save();
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
