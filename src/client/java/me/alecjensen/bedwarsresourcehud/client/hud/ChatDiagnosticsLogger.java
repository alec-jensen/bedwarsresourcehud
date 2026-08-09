package me.alecjensen.bedwarsresourcehud.client.hud;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logs every chat and system/game message the client receives. Bedwars generator events (tier
 * upgrades, "Diamond Generator activating", etc.) are usually announced this way, so this is a
 * research aid for finding a text-based signal that could confirm/speed up generator detection
 * instead of waiting on repeated item-pile growth.
 *
 * Also reacts to two specific, confirmed message shapes. "X purchased Iron/Gold Forge" - that
 * upgrade speeds up the purchasing team's own iron/gold generator, which makes the interval
 * GeneratorTracker learned before the purchase wrong (too slow) until it happens to observe a
 * fresh spawn. Rather than wait that out, or hardcode a guessed speed-up percentage (which would
 * likely be wrong for this specific server anyway), the affected generator's interval is just
 * invalidated the moment the purchase is seen, so the countdown honestly shows "unknown" for one
 * cycle instead of a confidently wrong number, and re-learns the new, faster interval immediately.
 *
 * And "Protect your bed and destroy the enemy beds." - the fixed tip line Hypixel sends in the
 * banner right as a match actually begins. Unlike "is this server/lobby Bed Wars-related at all"
 * (true for the portal/practice lobby too, not just real arenas), this message is only ever sent
 * once an actual match has started, so it's what arms BedAlarmTracker instead of it running
 * everywhere Bed Wars-flavored.
 */
public final class ChatDiagnosticsLogger
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BedwarsResourceHud/ChatDiagnostics");
    private static final Pattern FORGE_PURCHASE_PATTERN = Pattern.compile("^(.+?) purchased (Iron|Gold) Forge$");
    private static final String MATCH_START_TIP = "Protect your bed and destroy the enemy beds.";

    private ChatDiagnosticsLogger()
    {
    }

    public static void register()
    {
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, boundChatType, receptionTimestamp) ->
                LOGGER.info("chat: {}", message.getString()));

        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
        {
            LOGGER.info("game (overlay={}): {}", overlay, message.getString());
            onForgePurchase(message);
            onMatchStart(message);
        });
    }

    private static void onMatchStart(Component message)
    {
        if (message.getString().contains(MATCH_START_TIP))
        {
            BedAlarmTracker.onMatchStart();
        }
    }

    private static void onForgePurchase(Component message)
    {
        Matcher matcher = FORGE_PURCHASE_PATTERN.matcher(message.getString());
        if (!matcher.matches())
        {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        Player self = client.player;
        ClientLevel level = client.level;
        if (self == null || level == null)
        {
            return;
        }

        String purchaser = matcher.group(1);
        boolean isSelf = purchaser.equals("You");
        boolean isTeammate = !isSelf && level.players().stream()
                .anyMatch(other -> other != self && other.getName().getString().equals(purchaser) && TeamUtil.isTeammate(self, other));

        if (!isSelf && !isTeammate)
        {
            // An enemy team's own Forge purchase doesn't affect anything we're tracking as "ours",
            // and we don't map generator locations to teams at all, so there's nothing safe to do
            // with it here.
            return;
        }

        ResourceType type = matcher.group(2).equalsIgnoreCase("Iron") ? ResourceType.IRON : ResourceType.GOLD;
        GeneratorTracker.onForgeUpgrade(type, self.position());
    }
}
