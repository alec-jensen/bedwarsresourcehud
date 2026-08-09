# BedwarsResourceHud

A Fabric client mod for Minecraft 26.2 that shows a small HUD overlay with your Bedwars
resource counts, plus a set of situational-awareness tools (generator waypoints, bed
tracking, a threat radar, and void/fall-damage warnings). Iron, gold, diamond, and
emerald are tracked by default; arrows and golden apples are available too, and any
other item can be added by its registry ID. Each tracked item shows your current
**inventory** amount, your cached **ender chest** amount, and the **total** of the two.

## Features

### Resource tracking

- Live inventory tracking, updated every tick.
- Ender chest tracking: reads live contents whenever you have the ender chest open, and
  keeps showing the last-known amount once you close it.
- Detects punching an ender chest to deposit resources into it (a Hypixel Bedwars
  mechanic that never opens a screen) by watching for a matching inventory drop right
  after the punch, and adds that to the cached ender chest total.
- Configurable item list - toggle iron, gold, diamond, emerald, arrows, and golden
  apples on or off individually, plus a **Custom Items** screen to track any other
  item by its registry ID (e.g. `diamond_sword`).

### Generators

- Generator countdown: watches for iron/gold/diamond/emerald spawning as dropped item
  entities to estimate when each generator will spawn again, and re-learns the interval
  immediately after a Forge upgrade instead of showing a stale countdown.
- Waypoint markers over every generator you've discovered, with live item count and
  time-to-next-spawn once you're looking at one.
- Optional per-item spawn alerts (sound and/or action bar message) when a generator
  spawns a tracked resource, with a large selectable sound list and per-alert volume.

### Bed awareness

- Discovers and marks every bed found on the map as you explore it.
- Bed alarm: locates your own bed automatically and fires a sound/action-bar alert the
  moment an enemy first gets within a configurable radius of it, even after the bed is
  broken.

### Combat awareness

- Threat radar: an edge-of-screen indicator pointing toward nearby enemies who are
  currently off-screen, colored by their team.
- Highlights sneaking and/or invisible enemy players with a glowing outline (optionally
  forcing their nametag to show too), and can optionally highlight all enemies the same way.
- Void edge and fall damage warnings: draws a line along nearby ledges that lead into a
  bottomless drop or a damaging fall, accounting for Feather Falling, Protection,
  Resistance, Jump Boost, and Slow Falling.

### Other

- Repositionable: drag the HUD wherever you want it on screen.
- Checks GitHub for new releases on startup, with an in-game chat notice and a
  download link in the config screen when an update is available.

> [!WARNING]
> Bed ESP, generator waypoints, the threat radar, and the enemy glow highlight all
> render through walls and terrain. Only enable these on servers/game modes that
> permit that kind of assistance.

## Configuring the HUD

This mod adds a config entry to [Mod Menu](https://modrinth.com/mod/modmenu) (optional
dependency, install it if you don't already have it). Open **Mods → BedwarsResourceHud
→ config button** for the main options page - tracked items, generator alerts and their
sounds, void edge/fall damage/threat radar/bed alarm/highlighting toggles, and
Hypixel-only mode. From there:

- **Reposition HUD** drags the resource panel wherever you want it.
- **Custom Items** lets you add/remove any item tracked by registry ID.

Everything is saved to `config/bedwarsresourcehud.json`.

If Mod Menu isn't installed, the HUD still works fine at its default position and
settings (top-left, iron/gold/diamond/emerald enabled); you just won't have a way to
change anything in-game.

## Requirements

- Minecraft 26.2
- [Fabric Loader](https://fabricmc.net/use/) 0.19.3+
- [Fabric API](https://modrinth.com/mod/fabric-api) 0.155.2+26.2
- Java 25
- [Mod Menu](https://modrinth.com/mod/modmenu) 20.0.1+ (optional, for the position config screen)

## Building from source

```bash
./gradlew build
```

The built jar will be in `build/libs/`.

## License

[GPL-3.0](LICENSE.txt)
