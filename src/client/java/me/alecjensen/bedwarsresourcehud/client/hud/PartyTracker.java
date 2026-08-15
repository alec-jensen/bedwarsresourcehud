package me.alecjensen.bedwarsresourcehud.client.hud;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Tracks who's actually in your Hypixel party, from the join/leave system messages Hypixel sends -
 * a name-based signal that's completely unaffected by the scoreboard team-color flakiness TeamUtil
 * already has to work around (see its class doc). Needed because that flakiness isn't just a one-
 * tick startup race: a party member's connection dropping and reconnecting mid-match (a real,
 * repeatedly observed message: "the party leader ... has disconnected ... they have 5 minutes to
 * rejoin") re-adds their player entity and re-syncs their scoreboard team from scratch, and for
 * however long that resync takes, they'd otherwise read as an enemy standing right next to your own
 * bed. A party member's name doesn't change across any of that, so it's a much sturdier fallback
 * signal than live entity/team state for "is this actually a teammate".
 *
 * On Hypixel, queueing into a team game as a party keeps the whole party on one team, which is the
 * only reason it's safe to treat "in my party" as synonymous with "on my team" here.
 */
public final class PartyTracker
{
    private static final Set<String> members = Collections.synchronizedSet(new HashSet<>());

    private PartyTracker()
    {
    }

    public static void add(String name)
    {
        members.add(name.toLowerCase(Locale.ROOT));
    }

    public static void remove(String name)
    {
        members.remove(name.toLowerCase(Locale.ROOT));
    }

    public static void clear()
    {
        members.clear();
    }

    public static boolean isMember(String name)
    {
        return members.contains(name.toLowerCase(Locale.ROOT));
    }
}
