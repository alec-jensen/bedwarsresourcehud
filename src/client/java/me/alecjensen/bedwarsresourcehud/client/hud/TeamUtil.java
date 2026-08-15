package me.alecjensen.bedwarsresourcehud.client.hud;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Entity#isAlliedTo compares literal scoreboard Team OBJECT equality - it's true only if both
 * entities are on the exact same PlayerTeam (or their teams are configured as mutually allied).
 * That's the right check on a normal server where one shared team represents each Bedwars side.
 * This server instead hands out what looks like a distinct per-player team object (confirmed via
 * logs: two players known to be real teammates were treated as enemies by isAlliedTo, and plenty
 * of genuine enemy players had a null getTeam() entirely, which isAlliedTo can't work with either
 * way), so relying on team identity is unreliable here.
 *
 * Team COLOR turned out to still be consistent and correct (it's what the radar markers already
 * use), so "same side" is determined by comparing team color instead of team object identity -
 * that holds regardless of whether each player got their own uniquely-named team under the hood.
 * It's still not bulletproof, though: it's live entity/scoreboard state, so it resyncs from
 * scratch (briefly reading as no-color/mismatched) whenever a player's entity gets re-added -
 * which reconnecting mid-match does, and Hypixel party members really do disconnect and rejoin
 * mid-match. PartyTracker is checked as a fallback specifically because it isn't live state at
 * all - a party member's name doesn't change no matter how their connection or entity does.
 */
public final class TeamUtil
{
    private TeamUtil()
    {
    }

    public static boolean isTeammate(Entity self, Entity other)
    {
        if (self == other || self.getTeamColor() == other.getTeamColor())
        {
            return true;
        }
        return other instanceof Player player && PartyTracker.isMember(player.getGameProfile().name());
    }

    /**
     * This server represents shop/lobby NPCs as fake player entities with a raw profile name
     * containing a literal section-sign formatting code (e.g. "§kpriu0lPheaFvFA") - real
     * Mojang account names can never contain that character, so it's a reliable fingerprint. A
     * missing scoreboard team is NOT used as a signal here (see class doc) since it also matches
     * plenty of genuine enemy players on this server.
     */
    public static boolean isLikelyNpc(String profileName)
    {
        return profileName.indexOf('§') >= 0;
    }
}
