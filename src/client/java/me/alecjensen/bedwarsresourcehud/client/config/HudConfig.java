package me.alecjensen.bedwarsresourcehud.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import me.alecjensen.bedwarsresourcehud.client.hud.AlertSound;
import me.alecjensen.bedwarsresourcehud.client.hud.CustomTrackedItem;
import me.alecjensen.bedwarsresourcehud.client.hud.ResourceType;
import me.alecjensen.bedwarsresourcehud.client.hud.TrackedItem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class HudConfig
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BedwarsResourceHud/HudConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("bedwarsresourcehud.json");

    private static HudConfig instance;

    public int x = 10;
    public int y = 10;
    public boolean onlyShowOnHypixel = false;

    public Set<String> enabledItems = defaultEnabledItemNames();
    public Set<String> alertItems = defaultAlertItemNames();
    public boolean alertSoundEnabled = true;
    public boolean alertActionBarEnabled = false;
    public Map<String, String> itemAlertSounds = new HashMap<>();
    // Keyed the same as itemAlertSounds (ResourceType name, or "BED_ALARM") - the per-slot volume
    // for that sound, since vanilla sound events vary wildly in inherent volume and there's no way
    // to know in advance which one someone will pick for which alert.
    public Map<String, Double> soundVolumeMultipliers = new HashMap<>();
    public boolean highlightSneakingPlayers = false;
    public boolean highlightInvisiblePlayers = false;
    public boolean highlightEnemies = false;
    public boolean highlightShowNametag = false;

    public boolean showGeneratorWaypoints = true;
    public boolean showVoidEdgeWarning = true;
    public boolean showFallDamageWarning = true;
    public boolean showThreatRadar = true;
    public double threatRadarRange = 40.0;

    public boolean showBedEsp = true;

    public boolean bedAlarmEnabled = true;
    public double bedAlarmRadius = 25.0;
    public double bedAlarmDebounceSeconds = 10.0;
    public String bedAlarmSoundName = AlertSound.WITHER_SPAWN.name();

    public static final String BED_ALARM_VOLUME_KEY = "BED_ALARM";

    public List<String> customItems = new ArrayList<>();

    private static Set<String> defaultEnabledItemNames()
    {
        return Arrays.stream(ResourceType.values())
                .filter(ResourceType::isDefaultEnabled)
                .map(Enum::name)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static Set<String> defaultAlertItemNames()
    {
        return Arrays.stream(ResourceType.values())
                .filter(type -> type == ResourceType.DIAMOND || type == ResourceType.EMERALD)
                .map(Enum::name)
                .collect(Collectors.toCollection(HashSet::new));
    }

    public boolean isItemEnabled(ResourceType type)
    {
        return enabledItems.contains(type.name());
    }

    public void setItemEnabled(ResourceType type, boolean enabled)
    {
        if (enabled)
        {
            enabledItems.add(type.name());
        }
        else
        {
            enabledItems.remove(type.name());
        }
    }

    public boolean isAlertEnabled(ResourceType type)
    {
        return alertItems.contains(type.name());
    }

    public void setAlertEnabled(ResourceType type, boolean enabled)
    {
        if (enabled)
        {
            alertItems.add(type.name());
        }
        else
        {
            alertItems.remove(type.name());
        }
    }

    /** Enabled ResourceType entries followed by every resolvable custom item, in that order. */
    public List<TrackedItem> getAllTrackedItems()
    {
        List<TrackedItem> items = new ArrayList<>();
        for (ResourceType type : ResourceType.values())
        {
            if (isItemEnabled(type))
            {
                items.add(type);
            }
        }
        for (String itemId : customItems)
        {
            resolveItem(itemId).ifPresent(item -> items.add(new CustomTrackedItem(item, itemId)));
        }
        return items;
    }

    /**
     * Adds a custom item by its registry ID (e.g. "minecraft:diamond_sword" or just
     * "diamond_sword"). Returns false if the ID doesn't resolve to a real item or is already
     * tracked.
     */
    public boolean addCustomItem(String rawId)
    {
        Identifier id = Identifier.tryParse(rawId.trim());
        if (id == null)
        {
            return false;
        }

        String normalized = id.toString();
        if (customItems.contains(normalized) || resolveItem(normalized).isEmpty())
        {
            return false;
        }

        customItems.add(normalized);
        return true;
    }

    public void removeCustomItem(String itemId)
    {
        customItems.remove(itemId);
    }

    public static Optional<Item> resolveItem(String itemId)
    {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null)
        {
            return Optional.empty();
        }
        return BuiltInRegistries.ITEM.getOptional(id);
    }

    public AlertSound getAlertSound(ResourceType type)
    {
        String name = itemAlertSounds.get(type.name());
        return name != null ? AlertSound.fromName(name) : AlertSound.EXPERIENCE_ORB;
    }

    public void setAlertSound(ResourceType type, AlertSound sound)
    {
        itemAlertSounds.put(type.name(), sound.name());
    }

    public double getSoundVolumeMultiplier(String key)
    {
        return soundVolumeMultipliers.getOrDefault(key, 1.0);
    }

    public void setSoundVolumeMultiplier(String key, double multiplier)
    {
        soundVolumeMultipliers.put(key, multiplier);
    }

    public AlertSound getBedAlarmSound()
    {
        return AlertSound.fromName(bedAlarmSoundName);
    }

    public void setBedAlarmSound(AlertSound sound)
    {
        bedAlarmSoundName = sound.name();
    }

    public static HudConfig get()
    {
        if (instance == null)
        {
            instance = load();
        }
        return instance;
    }

    private static HudConfig load()
    {
        if (!Files.exists(CONFIG_PATH))
        {
            LOGGER.info("No config file at {}, using defaults", CONFIG_PATH);
            return new HudConfig();
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8))
        {
            HudConfig loaded = GSON.fromJson(reader, HudConfig.class);
            if (loaded != null)
            {
                LOGGER.info("Loaded config from {}", CONFIG_PATH);
                return loaded;
            }
            LOGGER.warn("Config file at {} parsed to null (empty file?), using defaults", CONFIG_PATH);
        }
        // JsonParseException (e.g. JsonSyntaxException) isn't an IOException - a hand-edited or
        // corrupted config file would otherwise throw straight out of get() instead of falling back
        // to defaults like every other failure mode here does.
        catch (IOException | JsonParseException e)
        {
            LOGGER.warn("Failed to load config from {}, using defaults", CONFIG_PATH, e);
        }
        return new HudConfig();
    }

    public void save()
    {
        try
        {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8))
            {
                GSON.toJson(this, writer);
            }
        }
        catch (IOException e)
        {
            LOGGER.warn("Failed to save config to {}", CONFIG_PATH, e);
        }
    }
}
