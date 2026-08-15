package me.alecjensen.bedwarsresourcehud.client;

import me.alecjensen.bedwarsresourcehud.client.hud.AllBedsTracker;
import me.alecjensen.bedwarsresourcehud.client.hud.BedAlarmTracker;
import me.alecjensen.bedwarsresourcehud.client.hud.BedEspRenderer;
import me.alecjensen.bedwarsresourcehud.client.hud.ChatDiagnosticsLogger;
import me.alecjensen.bedwarsresourcehud.client.hud.GeneratorTracker;
import me.alecjensen.bedwarsresourcehud.client.hud.GeneratorWaypointRenderer;
import me.alecjensen.bedwarsresourcehud.client.hud.HudRenderer;
import me.alecjensen.bedwarsresourcehud.client.hud.ResourceTracker;
import me.alecjensen.bedwarsresourcehud.client.hud.ThreatRadarRenderer;
import me.alecjensen.bedwarsresourcehud.client.hud.VoidEdgeRenderer;
import me.alecjensen.bedwarsresourcehud.client.hud.VoidEdgeTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Locale;

public class BedwarsresourcehudClient implements ClientModInitializer
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BedwarsResourceHud");

    // Not an exact title match: real servers (Hypixel included) send their own styled/colored
    // title text for this screen rather than vanilla's plain "container.enderchest" translation,
    // so comparing Components for equality would never match.
    private static final String ENDER_CHEST_TITLE_FRAGMENT = "ender chest";

    // Hypixel moves you between matches via internal BungeeCord hops, not a fresh connection, so
    // ClientPlayConnectionEvents.JOIN only ever fires once for an entire multi-hour session - it
    // never sees the ~10 "Sending you to mini..." transitions between matches. Each of those does
    // swap in a brand new ClientLevel instance, though, so watching for that reference to change is
    // what actually catches "we're in a new match now" and resets the trackers before they start
    // mixing generator/inventory state from a map that's long over into the current one.
    private static ClientLevel lastLevel;
    private static boolean updateNotified;
    private static boolean updateToastShown;
    // Vanilla toasts play their default sound (SoundEvents.UI_TOAST_IN) automatically the first
    // time a given SystemToastId is shown, so a plain SystemToast.add() covers both the "notify
    // on launch" ask and the accompanying sound with no extra sound-playing code needed here.
    // Public so MouseHandlerMixin (a different package) can look this toast up in the manager to
    // detect clicks on it - vanilla toasts have no click support of their own to hook into.
    public static final SystemToast.SystemToastId UPDATE_TOAST_ID = new SystemToast.SystemToastId();

    @Override
    public void onInitializeClient()
    {
        HudRenderer.register();
        GeneratorWaypointRenderer.register();
        ThreatRadarRenderer.register();
        VoidEdgeRenderer.register();
        BedEspRenderer.register();
        ChatDiagnosticsLogger.register();
        VersionChecker.checkAsync();

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
        {
            if (level.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST)
            {
                ResourceTracker.onEnderChestPunched(player, hand);
            }
            return InteractionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(BedwarsresourcehudClient::onClientTick);
    }

    private static void onClientTick(Minecraft client)
    {
        // Deliberately not gated on client.player - this should surface as soon as the check
        // finishes, which happens well before anyone actually joins a world (right from the title
        // screen), not just once someone is in-game to receive the chat message below.
        if (!updateToastShown && VersionChecker.hasChecked())
        {
            updateToastShown = true;
            VersionChecker.UpdateInfo update = VersionChecker.getAvailableUpdate();
            if (update != null)
            {
                SystemToast.add(client.gui.toastManager(), UPDATE_TOAST_ID,
                        Component.literal("BedwarsResourceHud update available"),
                        Component.literal("v" + update.latestVersion() + " - see the mod config to download"));
            }
        }

        if (client.player == null)
        {
            return;
        }

        if (!updateNotified && VersionChecker.hasChecked())
        {
            updateNotified = true;
            VersionChecker.UpdateInfo update = VersionChecker.getAvailableUpdate();
            if (update != null)
            {
                client.player.sendSystemMessage(buildUpdateMessage(update));
            }
        }

        if (client.level != lastLevel)
        {
            LOGGER.info("Level changed ({} -> {}), resetting trackers", lastLevel, client.level);
            ResourceTracker.reset();
            GeneratorTracker.reset();
            BedAlarmTracker.reset();
            AllBedsTracker.reset();
            lastLevel = client.level;
        }

        ResourceTracker.tick(client.player);
        GeneratorTracker.tick(client);
        VoidEdgeTracker.tick(client);
        BedAlarmTracker.tick(client);
        AllBedsTracker.tick(client);

        Screen screen = client.gui.screen();
        if (screen instanceof AbstractContainerScreen<?> containerScreen
                && containerScreen.getMenu() instanceof ChestMenu chestMenu
                && containerScreen.getTitle().getString().toLowerCase(Locale.ROOT).contains(ENDER_CHEST_TITLE_FRAGMENT))
        {
            ResourceTracker.updateFromOpenEnderChest(chestMenu.getContainer());
        }
    }

    private static MutableComponent buildUpdateMessage(VersionChecker.UpdateInfo update)
    {
        MutableComponent link = Component.literal("[Download]")
                .withStyle(style -> style.withClickEvent(new ClickEvent.OpenUrl(URI.create(update.downloadUrl())))
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true));

        return Component.literal("[BedwarsResourceHud] ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("A new version (" + update.latestVersion() + ") is available! ")
                        .withStyle(ChatFormatting.YELLOW))
                .append(link);
    }
}
