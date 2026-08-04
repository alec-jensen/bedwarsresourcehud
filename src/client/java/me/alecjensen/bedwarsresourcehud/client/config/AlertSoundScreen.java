package me.alecjensen.bedwarsresourcehud.client.config;

import me.alecjensen.bedwarsresourcehud.client.hud.AlertSound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Every AlertSound option as a two-column list of rows: clicking a row highlights it and
 * immediately plays it (at whatever volume multiplier is currently set), so browsing the list
 * previews as you go instead of needing a separate preview click. Confirm commits whatever's
 * currently highlighted, along with the volume multiplier slider. Generalized over getter/setter
 * pairs rather than a specific ResourceType so it can back any sound-picking screen (per-item
 * alerts, the bed alarm).
 */
public class AlertSoundScreen extends Screen
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BedwarsResourceHud/AlertSoundScreen");
    private static final double VOLUME_MIN = 0.0;
    private static final double VOLUME_MAX = 10.0;
    private static final int ROW_HEIGHT = 20;
    private static final int LIST_TOP = 28;
    private static final int COLUMN_WIDTH = 200;
    private static final int COLUMN_GAP = 20;
    private static final int HIGHLIGHT_COLOR = 0x80FFFFFF;

    private final Screen parent;
    private final HudConfig config;
    private final Supplier<AlertSound> getter;
    private final Consumer<AlertSound> setter;
    private final DoubleSupplier volumeGetter;
    private final DoubleConsumer volumeSetter;
    private final Runnable onConfirmed;
    private final Map<AlertSound, Button> rowButtons = new EnumMap<>(AlertSound.class);
    private AlertSound highlighted;
    private double highlightedVolume;
    private SimpleSoundInstance lastPreview;

    public AlertSoundScreen(Screen parent, String title, Supplier<AlertSound> getter, Consumer<AlertSound> setter,
                             DoubleSupplier volumeGetter, DoubleConsumer volumeSetter, Runnable onConfirmed)
    {
        super(Component.literal(title));
        this.parent = parent;
        this.config = HudConfig.get();
        this.getter = getter;
        this.setter = setter;
        this.volumeGetter = volumeGetter;
        this.volumeSetter = volumeSetter;
        this.onConfirmed = onConfirmed;
        this.highlighted = getter.get();
        this.highlightedVolume = volumeGetter.getAsDouble();
    }

    @Override
    protected void init()
    {
        int centerX = this.width / 2;
        AlertSound[] options = AlertSound.values();
        int rowsPerColumn = (options.length + 1) / 2;

        for (int i = 0; i < options.length; i++)
        {
            AlertSound sound = options[i];
            int column = i / rowsPerColumn;
            int row = i % rowsPerColumn;
            int columnX = centerX + (column == 0 ? -(COLUMN_WIDTH + COLUMN_GAP / 2) : COLUMN_GAP / 2);
            int rowY = LIST_TOP + row * ROW_HEIGHT;

            Button rowButton = this.addRenderableWidget(Button.builder(rowLabel(sound), button -> selectRow(sound))
                    .bounds(columnX, rowY, COLUMN_WIDTH, 20)
                    .build());
            rowButtons.put(sound, rowButton);
        }

        int listBottom = LIST_TOP + rowsPerColumn * ROW_HEIGHT;
        this.addRenderableWidget(buildVolumeSlider(centerX - 150, listBottom + 10, 300));

        int bottomY = this.height - 26;
        this.addRenderableWidget(Button.builder(Component.literal("Confirm"), button ->
                {
                    setter.accept(highlighted);
                    volumeSetter.accept(highlightedVolume);
                    onConfirmed.run();
                    this.onClose();
                })
                .bounds(centerX - 105, bottomY, 100, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> this.onClose())
                .bounds(centerX + 5, bottomY, 100, 20)
                .build());
    }

    private AbstractSliderButton buildVolumeSlider(int x, int y, int width)
    {
        // Clamped defensively - a value saved before the range dropped from 0-40 to 0-10 could
        // still be sitting on disk out of range until this slider is touched.
        double initialNormalized = Math.max(0.0, Math.min(1.0, (highlightedVolume - VOLUME_MIN) / (VOLUME_MAX - VOLUME_MIN)));
        return new AbstractSliderButton(x, y, width, 20, Component.empty(), initialNormalized)
        {
            {
                this.updateMessage();
            }

            @Override
            protected void updateMessage()
            {
                double multiplier = VOLUME_MIN + this.value * (VOLUME_MAX - VOLUME_MIN);
                this.setMessage(Component.literal(String.format("Volume: %.1fx", multiplier)));
            }

            @Override
            protected void applyValue()
            {
                highlightedVolume = VOLUME_MIN + this.value * (VOLUME_MAX - VOLUME_MIN);
                playPreview();
            }
        };
    }

    private void selectRow(AlertSound sound)
    {
        highlighted = sound;
        for (Map.Entry<AlertSound, Button> entry : rowButtons.entrySet())
        {
            entry.getValue().setMessage(rowLabel(entry.getKey()));
        }
        playPreview();
    }

    /**
     * Always previews at whatever the volume slider is currently set to, whether triggered by
     * picking a sound or by moving the slider itself. Stops any still-ringing previous preview
     * first - dragging the slider fires this many times a second, and without that, a few seconds
     * of dragging left a pile of overlapping in-flight sounds whose leftover tail could easily be
     * mistaken for "the volume doesn't do anything, it's still audible even at 0".
     */
    private void playPreview()
    {
        if (lastPreview != null)
        {
            Minecraft.getInstance().getSoundManager().stop(lastPreview);
        }
        lastPreview = highlighted.play((float) highlightedVolume);
    }

    private Component rowLabel(AlertSound sound)
    {
        return Component.literal(sound == highlighted ? "> " + sound.getLabel() : sound.getLabel());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)
    {
        this.extractTransparentBackground(graphics);

        int centerX = this.width / 2;
        AlertSound[] options = AlertSound.values();
        int rowsPerColumn = (options.length + 1) / 2;
        int highlightedIndex = Arrays.asList(options).indexOf(highlighted);
        int column = highlightedIndex / rowsPerColumn;
        int row = highlightedIndex % rowsPerColumn;
        int columnX = centerX + (column == 0 ? -(COLUMN_WIDTH + COLUMN_GAP / 2) : COLUMN_GAP / 2);
        int rowY = LIST_TOP + row * ROW_HEIGHT;
        graphics.fill(columnX - 2, rowY - 2, columnX + COLUMN_WIDTH + 2, rowY + 22, HIGHLIGHT_COLOR);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.getTitle(), this.width / 2, 12, 0xFFFFFFFF);
    }

    @Override
    public void onClose()
    {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
