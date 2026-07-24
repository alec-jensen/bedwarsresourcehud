package me.alecjensen.bedwarsresourcehud.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class HudConfig
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("bedwarsresourcehud.json");

    private static HudConfig instance;

    public int x = 10;
    public int y = 10;

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
        if (Files.exists(CONFIG_PATH))
        {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8))
            {
                HudConfig loaded = GSON.fromJson(reader, HudConfig.class);
                if (loaded != null)
                {
                    return loaded;
                }
            }
            catch (IOException ignored)
            {
            }
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
        catch (IOException ignored)
        {
        }
    }
}
