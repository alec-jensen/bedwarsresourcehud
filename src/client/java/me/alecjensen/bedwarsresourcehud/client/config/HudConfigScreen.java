package me.alecjensen.bedwarsresourcehud.client.config;

import me.alecjensen.bedwarsresourcehud.client.VersionChecker;
import me.alecjensen.bedwarsresourcehud.client.hud.ResourceType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

public class HudConfigScreen extends Screen
{
    private static final int COLUMN_1_X_OFFSET = -150;
    private static final int COLUMN_2_X_OFFSET = 10;
    private static final int ROW_HEIGHT = 22;
    private static final int VIEWPORT_TOP = 24;
    private static final int VIEWPORT_MARGIN_BOTTOM = 32;
    private static final int SCROLL_SPEED = 16;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_MARGIN = 4;
    private static final int SCROLLBAR_MIN_THUMB = 20;

    private final Screen parent;
    private final HudConfig config;
    private final Map<ResourceType, Button> alertSoundButtons = new EnumMap<>(ResourceType.class);
    private Button bedAlarmSoundButton;
    private final List<ScrollEntry> scrollEntries = new ArrayList<>();
    private int highlightWarningBaseY;
    private int contentBottom;
    private int viewportBottom;
    private double scrollAmount;
    private boolean draggingScrollbar;

    private record ScrollEntry(AbstractWidget widget, int baseY)
    {
    }

    public HudConfigScreen(Screen parent)
    {
        super(Component.literal("Bedwars Resource HUD"));
        this.parent = parent;
        this.config = HudConfig.get();
    }

    @Override
    protected void init()
    {
        scrollEntries.clear();
        alertSoundButtons.clear();
        scrollAmount = 0;
        viewportBottom = this.height - VIEWPORT_MARGIN_BOTTOM;

        int centerX = this.width / 2;
        int y = VIEWPORT_TOP + 4;

        addScrollable(Button.builder(Component.literal("Reposition HUD"), button -> this.minecraft.gui.setScreen(new HudPositionScreen(this)))
                .bounds(centerX + COLUMN_1_X_OFFSET, y, 140, 20)
                .build(), y);
        addScrollable(Button.builder(Component.literal("Custom Items"), button -> this.minecraft.gui.setScreen(new CustomItemsScreen(this)))
                .bounds(centerX + COLUMN_2_X_OFFSET, y, 140, 20)
                .build(), y);
        y += ROW_HEIGHT;

        addScrollable(Checkbox.builder(Component.literal("Only show on Hypixel"), this.font)
                .pos(centerX + COLUMN_1_X_OFFSET, y)
                .selected(config.onlyShowOnHypixel)
                .onValueChange((checkbox, value) -> config.onlyShowOnHypixel = value)
                .build(), y);
        y += ROW_HEIGHT + 12;

        for (ResourceType type : ResourceType.values())
        {
            int index = type.ordinal();
            int columnX = centerX + (index % 2 == 0 ? COLUMN_1_X_OFFSET : COLUMN_2_X_OFFSET);
            int rowY = y + (index / 2) * ROW_HEIGHT;

            addScrollable(Checkbox.builder(Component.literal(type.getDisplayName()), this.font)
                    .pos(columnX, rowY)
                    .selected(config.isItemEnabled(type))
                    .onValueChange((checkbox, value) -> config.setItemEnabled(type, value))
                    .build(), rowY);
        }
        y += ((ResourceType.values().length + 1) / 2) * ROW_HEIGHT + 12;

        int alertRow = 0;
        for (ResourceType type : ResourceType.values())
        {
            if (!type.hasGenerator())
            {
                continue;
            }

            int rowY = y + alertRow * ROW_HEIGHT;

            addScrollable(Checkbox.builder(Component.literal(type.getDisplayName() + " alert"), this.font)
                    .pos(centerX + COLUMN_1_X_OFFSET, rowY)
                    .selected(config.isAlertEnabled(type))
                    .onValueChange((checkbox, value) -> config.setAlertEnabled(type, value))
                    .build(), rowY);

            Button soundButton = addScrollable(Button.builder(alertSoundButtonLabel(type), button ->
                    this.minecraft.gui.setScreen(new AlertSoundScreen(this, type.getDisplayName() + " Alert Sound",
                            () -> config.getAlertSound(type), sound -> config.setAlertSound(type, sound),
                            () -> config.getSoundVolumeMultiplier(type.name()), value -> config.setSoundVolumeMultiplier(type.name(), value),
                            () -> this.onAlertSoundSelected(type))))
                    .bounds(centerX + COLUMN_2_X_OFFSET, rowY, 130, 20)
                    .build(), rowY);
            alertSoundButtons.put(type, soundButton);

            alertRow++;
        }
        y += alertRow * ROW_HEIGHT + 12;

        addScrollable(Checkbox.builder(Component.literal("Show generator waypoints"), this.font)
                .pos(centerX + COLUMN_1_X_OFFSET, y)
                .selected(config.showGeneratorWaypoints)
                .onValueChange((checkbox, value) -> config.showGeneratorWaypoints = value)
                .build(), y);
        addScrollable(Checkbox.builder(Component.literal("Warn near void edges"), this.font)
                .pos(centerX + COLUMN_2_X_OFFSET, y)
                .selected(config.showVoidEdgeWarning)
                .onValueChange((checkbox, value) -> config.showVoidEdgeWarning = value)
                .build(), y);
        y += ROW_HEIGHT;

        addScrollable(Checkbox.builder(Component.literal("Warn of fall damage"), this.font)
                .pos(centerX + COLUMN_1_X_OFFSET, y)
                .selected(config.showFallDamageWarning)
                .onValueChange((checkbox, value) -> config.showFallDamageWarning = value)
                .build(), y);
        y += ROW_HEIGHT;

        addScrollable(Checkbox.builder(Component.literal("Show threat radar"), this.font)
                .pos(centerX + COLUMN_1_X_OFFSET, y)
                .selected(config.showThreatRadar)
                .onValueChange((checkbox, value) -> config.showThreatRadar = value)
                .build(), y);
        addScrollable(buildRangeSlider(centerX + COLUMN_2_X_OFFSET, y, 140, "Radar range", 10, 80,
                () -> config.threatRadarRange, value -> config.threatRadarRange = value), y);
        y += ROW_HEIGHT;

        addScrollable(Checkbox.builder(Component.literal("Show all bed ESP"), this.font)
                .pos(centerX + COLUMN_1_X_OFFSET, y)
                .selected(config.showBedEsp)
                .onValueChange((checkbox, value) -> config.showBedEsp = value)
                .build(), y);
        y += ROW_HEIGHT;

        addScrollable(Checkbox.builder(Component.literal("Bed alarm"), this.font)
                .pos(centerX + COLUMN_1_X_OFFSET, y)
                .selected(config.bedAlarmEnabled)
                .onValueChange((checkbox, value) -> config.bedAlarmEnabled = value)
                .build(), y);
        addScrollable(buildRangeSlider(centerX + COLUMN_2_X_OFFSET, y, 140, "Bed alarm range", 5, 60,
                () -> config.bedAlarmRadius, value -> config.bedAlarmRadius = value), y);
        y += ROW_HEIGHT;

        bedAlarmSoundButton = addScrollable(Button.builder(bedAlarmSoundButtonLabel(), button ->
                this.minecraft.gui.setScreen(new AlertSoundScreen(this, "Bed Alarm Sound",
                        config::getBedAlarmSound, config::setBedAlarmSound,
                        () -> config.getSoundVolumeMultiplier(HudConfig.BED_ALARM_VOLUME_KEY),
                        value -> config.setSoundVolumeMultiplier(HudConfig.BED_ALARM_VOLUME_KEY, value),
                        this::onBedAlarmSoundSelected)))
                .bounds(centerX + COLUMN_1_X_OFFSET, y, 140, 20)
                .build(), y);
        addScrollable(buildRangeSlider(centerX + COLUMN_2_X_OFFSET, y, 140, "Bed alarm debounce (s)", 0, 30,
                () -> config.bedAlarmDebounceSeconds, value -> config.bedAlarmDebounceSeconds = value), y);
        y += ROW_HEIGHT + 12;

        addScrollable(Checkbox.builder(Component.literal("Alert sound"), this.font)
                .pos(centerX + COLUMN_1_X_OFFSET, y)
                .selected(config.alertSoundEnabled)
                .onValueChange((checkbox, value) -> config.alertSoundEnabled = value)
                .build(), y);
        addScrollable(Checkbox.builder(Component.literal("Action bar message"), this.font)
                .pos(centerX + COLUMN_2_X_OFFSET, y)
                .selected(config.alertActionBarEnabled)
                .onValueChange((checkbox, value) -> config.alertActionBarEnabled = value)
                .build(), y);
        y += ROW_HEIGHT + 12;

        highlightWarningBaseY = y;
        y += 12;

        addScrollable(Checkbox.builder(Component.literal("Highlight sneaking players"), this.font)
                .pos(centerX + COLUMN_1_X_OFFSET, y)
                .selected(config.highlightSneakingPlayers)
                .onValueChange((checkbox, value) -> config.highlightSneakingPlayers = value)
                .build(), y);
        addScrollable(Checkbox.builder(Component.literal("Highlight invisible players"), this.font)
                .pos(centerX + COLUMN_2_X_OFFSET, y)
                .selected(config.highlightInvisiblePlayers)
                .onValueChange((checkbox, value) -> config.highlightInvisiblePlayers = value)
                .build(), y);
        y += ROW_HEIGHT;

        addScrollable(Checkbox.builder(Component.literal("Show their nametag too"), this.font)
                .pos(centerX + COLUMN_1_X_OFFSET, y)
                .selected(config.highlightShowNametag)
                .onValueChange((checkbox, value) -> config.highlightShowNametag = value)
                .build(), y);
        y += ROW_HEIGHT;

        addScrollable(Checkbox.builder(Component.literal("Highlight all enemies"), this.font)
                .pos(centerX + COLUMN_1_X_OFFSET, y)
                .selected(config.highlightEnemies)
                .onValueChange((checkbox, value) -> config.highlightEnemies = value)
                .build(), y);
        y += ROW_HEIGHT;

        contentBottom = y;

        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose())
                .bounds(centerX - 50, this.height - 26, 100, 20)
                .build());

        VersionChecker.UpdateInfo update = VersionChecker.getAvailableUpdate();
        if (update != null)
        {
            URI downloadUri = URI.create(update.downloadUrl());
            this.addRenderableWidget(Button.builder(Component.literal("Update available: v" + update.latestVersion()),
                            ConfirmLinkScreen.confirmLink(this, downloadUri))
                    .bounds(10, this.height - 26, 180, 20)
                    .build());
        }

        applyScroll();
    }

    private <T extends AbstractWidget> T addScrollable(T widget, int baseY)
    {
        this.addWidget(widget);
        scrollEntries.add(new ScrollEntry(widget, baseY));
        return widget;
    }

    private double maxScroll()
    {
        return Math.max(0, contentBottom - (viewportBottom - VIEWPORT_TOP));
    }

    private void applyScroll()
    {
        scrollAmount = Math.max(0, Math.min(scrollAmount, maxScroll()));
        int offset = (int) Math.round(scrollAmount);

        for (ScrollEntry entry : scrollEntries)
        {
            int widgetY = entry.baseY() - offset;
            entry.widget().setY(widgetY);
            entry.widget().visible = widgetY + entry.widget().getHeight() >= VIEWPORT_TOP && widgetY <= viewportBottom;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY)
    {
        if (maxScroll() <= 0)
        {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        scrollAmount -= scrollY * SCROLL_SPEED;
        applyScroll();
        return true;
    }

    private int scrollbarX()
    {
        return this.width - SCROLLBAR_MARGIN - SCROLLBAR_WIDTH;
    }

    private int scrollbarThumbHeight()
    {
        double viewport = viewportBottom - VIEWPORT_TOP;
        double content = viewport + maxScroll();
        int thumb = (int) Math.round(viewport * (viewport / content));
        return Math.max(SCROLLBAR_MIN_THUMB, Math.min(thumb, (int) viewport));
    }

    private int scrollbarThumbY()
    {
        double max = maxScroll();
        if (max <= 0)
        {
            return VIEWPORT_TOP;
        }
        int usableTrack = (viewportBottom - VIEWPORT_TOP) - scrollbarThumbHeight();
        return VIEWPORT_TOP + (int) Math.round((scrollAmount / max) * usableTrack);
    }

    private boolean isOverScrollbar(double mouseX, double mouseY)
    {
        int trackX = scrollbarX();
        return mouseX >= trackX && mouseX <= trackX + SCROLLBAR_WIDTH && mouseY >= VIEWPORT_TOP && mouseY <= viewportBottom;
    }

    private void dragScrollbarTo(double mouseY)
    {
        int thumbHeight = scrollbarThumbHeight();
        double usableTrack = Math.max(1, (viewportBottom - VIEWPORT_TOP) - thumbHeight);
        double fraction = (mouseY - VIEWPORT_TOP - thumbHeight / 2.0) / usableTrack;
        scrollAmount = Math.max(0, Math.min(1, fraction)) * maxScroll();
        applyScroll();
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick)
    {
        if (event.button() == 0 && maxScroll() > 0 && isOverScrollbar(event.x(), event.y()))
        {
            draggingScrollbar = true;
            dragScrollbarTo(event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY)
    {
        if (draggingScrollbar)
        {
            dragScrollbarTo(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event)
    {
        if (draggingScrollbar)
        {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    private AbstractSliderButton buildRangeSlider(int x, int y, int width, String label, double min, double max, DoubleSupplier getter, DoubleConsumer setter)
    {
        double initialNormalized = (getter.getAsDouble() - min) / (max - min);
        return new AbstractSliderButton(x, y, width, 20, Component.empty(), initialNormalized)
        {
            {
                this.updateMessage();
            }

            @Override
            protected void updateMessage()
            {
                double blocks = min + this.value * (max - min);
                this.setMessage(Component.literal(String.format("%s: %.0f", label, blocks)));
            }

            @Override
            protected void applyValue()
            {
                setter.accept(min + this.value * (max - min));
            }
        };
    }

    private Component alertSoundButtonLabel(ResourceType type)
    {
        return Component.literal("Sound: " + config.getAlertSound(type).getLabel());
    }

    public void onAlertSoundSelected(ResourceType type)
    {
        Button button = alertSoundButtons.get(type);
        if (button != null)
        {
            button.setMessage(alertSoundButtonLabel(type));
        }
    }

    private Component bedAlarmSoundButtonLabel()
    {
        return Component.literal("Bed Alarm Sound: " + config.getBedAlarmSound().getLabel());
    }

    private void onBedAlarmSoundSelected()
    {
        if (bedAlarmSoundButton != null)
        {
            bedAlarmSoundButton.setMessage(bedAlarmSoundButtonLabel());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)
    {
        this.extractTransparentBackground(graphics);

        graphics.enableScissor(0, VIEWPORT_TOP, this.width, viewportBottom);
        for (ScrollEntry entry : scrollEntries)
        {
            entry.widget().extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
        int warningY = highlightWarningBaseY - (int) Math.round(scrollAmount);
        graphics.centeredText(this.font, Component.literal("Ignores walls - only use on a server that allows it"), this.width / 2, warningY, 0xFFFF5555);
        graphics.disableScissor();

        if (maxScroll() > 0)
        {
            int trackX = scrollbarX();
            graphics.fill(trackX, VIEWPORT_TOP, trackX + SCROLLBAR_WIDTH, viewportBottom, 0x60000000);
            int thumbY = scrollbarThumbY();
            int thumbHeight = scrollbarThumbHeight();
            int thumbColor = draggingScrollbar ? 0xFFFFFFFF : 0xC0CCCCCC;
            graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, thumbColor);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.getTitle(), this.width / 2, 12, 0xFFFFFFFF);
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
