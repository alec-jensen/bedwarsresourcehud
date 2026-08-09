package me.alecjensen.bedwarsresourcehud.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class VersionChecker
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BedwarsResourceHud");
    private static final String MOD_ID = "bedwarsresourcehud";
    // Deliberately the release *list* (newest first), not GitHub's /releases/latest - that
    // endpoint only ever returns the newest non-prerelease, so it 404s outright as long as every
    // published release stays marked prerelease, which is how this project publishes right now.
    private static final String RELEASES_API = "https://api.github.com/repos/alec-jensen/bedwarsresourcehud/releases?per_page=1";

    public record UpdateInfo(String latestVersion, String downloadUrl)
    {
    }

    private static volatile UpdateInfo availableUpdate;
    private static volatile boolean checked;

    private VersionChecker()
    {
    }

    public static void checkAsync()
    {
        Thread thread = new Thread(VersionChecker::check, "BedwarsResourceHud-VersionCheck");
        thread.setDaemon(true);
        thread.start();
    }

    private static void check()
    {
        try
        {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RELEASES_API))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
            {
                LOGGER.warn("Version check failed: GitHub API returned HTTP {}", response.statusCode());
                return;
            }

            JsonArray releases = JsonParser.parseString(response.body()).getAsJsonArray();
            if (releases.isEmpty())
            {
                LOGGER.info("Version check: no releases published yet");
                return;
            }

            JsonObject json = releases.get(0).getAsJsonObject();
            String tag = json.get("tag_name").getAsString();
            String downloadUrl = json.get("html_url").getAsString();
            String latest = tag.startsWith("v") ? tag.substring(1) : tag;

            String current = FabricLoader.getInstance().getModContainer(MOD_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse(latest);

            if (isNewer(latest, current))
            {
                availableUpdate = new UpdateInfo(latest, downloadUrl);
                LOGGER.info("BedwarsResourceHud update available: {} (current: {})", latest, current);
            }
        }
        catch (Exception e)
        {
            LOGGER.warn("Failed to check for BedwarsResourceHud updates", e);
        }
        finally
        {
            checked = true;
        }
    }

    private static boolean isNewer(String latest, String current)
    {
        try
        {
            return Version.parse(latest).compareTo(Version.parse(current)) > 0;
        }
        catch (Exception e)
        {
            // Fall back to a straight inequality check if either string isn't valid semver.
            return !latest.equals(current);
        }
    }

    public static boolean hasChecked()
    {
        return checked;
    }

    public static UpdateInfo getAvailableUpdate()
    {
        return availableUpdate;
    }
}
