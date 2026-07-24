package me.alecjensen.bedwarsresourcehud.client.config;

import me.alecjensen.bedwarsresourcehud.client.hud.HudRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class HudPositionScreen extends Screen
{
    private final Screen parent;
    private final HudConfig config;
    private boolean dragging;
    private int dragOffsetX;
    private int dragOffsetY;

    public HudPositionScreen(Screen parent)
    {
        super(Component.literal("Bedwars HUD Position"));
        this.parent = parent;
        this.config = HudConfig.get();
    }

    @Override
    protected void init()
    {
        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose())
                .bounds(this.width / 2 - 50, this.height - 30, 100, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)
    {
        this.extractTransparentBackground(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, Component.literal("Drag the panel to reposition it"), this.width / 2, 20, 0xFFFFFFFF);

        // The live HUD has no background (fully transparent), so draw a faint outline here only,
        // purely so the panel bounds are visible while editing its position.
        graphics.outline(config.x - 2, config.y - 2, config.x + HudRenderer.getPanelWidth() + 2, config.y + HudRenderer.getPanelHeight() + 2, 0x80FFFFFF);
        HudRenderer.render(graphics, config.x, config.y);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
    {
        if (event.button() == 0 && isInsidePanel(event.x(), event.y()))
        {
            dragging = true;
            dragOffsetX = (int) event.x() - config.x;
            dragOffsetY = (int) event.y() - config.y;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY)
    {
        if (dragging)
        {
            config.x = clamp((int) event.x() - dragOffsetX, 0, this.width - HudRenderer.getPanelWidth());
            config.y = clamp((int) event.y() - dragOffsetY, 0, this.height - HudRenderer.getPanelHeight());
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event)
    {
        if (event.button() == 0 && dragging)
        {
            dragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    private boolean isInsidePanel(double mouseX, double mouseY)
    {
        return mouseX >= config.x && mouseX <= config.x + HudRenderer.getPanelWidth()
                && mouseY >= config.y && mouseY <= config.y + HudRenderer.getPanelHeight();
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
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
