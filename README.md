# BedwarsResourceHud

A Fabric client mod for Minecraft 26.2 that shows a small HUD overlay with your Bedwars
resource counts. Iron, gold, diamond, and emerald are tracked by default; arrows and
golden apples are available too. Each tracked item shows your current **inventory**
amount, your cached **ender chest** amount, and the **total** of the two.

## Features

- Live inventory tracking, updated every tick.
- Ender chest tracking: reads live contents whenever you have the ender chest open, and
  keeps showing the last-known amount once you close it.
- Detects punching an ender chest to deposit resources into it (a Hypixel Bedwars
  mechanic that never opens a screen) by watching for a matching inventory drop right
  after the punch, and adds that to the cached ender chest total.
- Configurable item list - toggle iron, gold, diamond, emerald, arrows, golden apples,
  and enchanted golden apples on or off individually.
- Generator countdown: watches for iron/gold/diamond/emerald spawning as dropped item
  entities to estimate when each generator will spawn again.
- Optional per-item spawn alerts (sound and/or action bar message) when a generator
  spawns a tracked resource.
- Repositionable: drag the HUD wherever you want it on screen.
- Checks GitHub for new releases on startup, with an in-game chat notice and a
  download link in the config screen when an update is available.

## Configuring the HUD

This mod adds a config entry to [Mod Menu](https://modrinth.com/mod/modmenu) (optional
dependency, install it if you don't already have it). Open **Mods → BedwarsResourceHud
→ config button** for the main options page (which items are tracked, spawn alerts,
Hypixel-only mode), and use the **Reposition HUD** button there to drag the panel
wherever you want it, then **Save & Back**. Everything is saved to
`config/bedwarsresourcehud.json`.

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
