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
 *
 * Also feeds PartyTracker from Hypixel's own party join/leave messages - see that class for why
 * party membership matters here at all.
 */
public final class ChatDiagnosticsLogger
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BedwarsResourceHud/ChatDiagnostics");
    private static final Pattern FORGE_PURCHASE_PATTERN = Pattern.compile("^(.+?) purchased (Iron|Gold) Forge$");
    private static final String MATCH_START_TIP = "Protect your bed and destroy the enemy beds.";

    private static final Pattern YOU_JOINED_PARTY_PATTERN = Pattern.compile("^You have joined (.+?)'s party!$");
    // Deliberately NOT "X has joined (n/16)!" - that one turned out to be a Bedwars lobby
    // population broadcast (n/16 = lobby slot count), not a party message. It fires for every
    // stranger who joins the same public lobby, party or not, and would have quietly marked real
    // enemies as guaranteed allies.
    private static final Pattern PLAYER_JOINED_PARTY_PATTERN = Pattern.compile("^(.+?) joined the party\\.?$");
    private static final Pattern PLAYER_LEFT_PARTY_PATTERN = Pattern.compile("^(.+?) has left the party\\.?$");
    private static final Pattern PLAYER_REMOVED_FROM_PARTY_PATTERN = Pattern.compile("^(.+?) has been removed from the party\\.?$");
    private static final Pattern YOU_LEFT_PARTY_PATTERN = Pattern.compile("^You (?:have )?left the party\\.?$");
    private static final Pattern PARTY_DISBANDED_PATTERN = Pattern.compile("^The party (?:was disbanded|has been disbanded).*$");

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
            onPartyMessage(message);
        });
    }

    private static void onMatchStart(Component message)
    {
        if (message.getString().contains(MATCH_START_TIP))
        {
            BedAlarmTracker.onMatchStart();
        }
    }

    private static void onPartyMessage(Component message)
    {
        String text = message.getString();

        Matcher youJoined = YOU_JOINED_PARTY_PATTERN.matcher(text);
        if (youJoined.matches())
        {
            PartyTracker.add(youJoined.group(1));
            return;
        }

        Matcher playerJoined = PLAYER_JOINED_PARTY_PATTERN.matcher(text);
        if (playerJoined.matches())
        {
            PartyTracker.add(playerJoined.group(1));
            return;
        }

        Matcher playerLeft = PLAYER_LEFT_PARTY_PATTERN.matcher(text);
        if (playerLeft.matches())
        {
            PartyTracker.remove(playerLeft.group(1));
            return;
        }

        Matcher playerRemoved = PLAYER_REMOVED_FROM_PARTY_PATTERN.matcher(text);
        if (playerRemoved.matches())
        {
            PartyTracker.remove(playerRemoved.group(1));
            return;
        }

        if (YOU_LEFT_PARTY_PATTERN.matcher(text).matches() || PARTY_DISBANDED_PATTERN.matcher(text).matches())
        {
            PartyTracker.clear();
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
