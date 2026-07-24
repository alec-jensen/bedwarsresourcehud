# BedwarsResourceHud

A Fabric client mod for Minecraft 26.2 that shows a small HUD overlay with your Bedwars
resource counts: iron, gold, diamond, and emerald. Each resource shows your current
**inventory** amount, your cached **ender chest** amount, and the **total** of the two.

## Features

- Live inventory tracking, updated every tick.
- Ender chest tracking: reads live contents whenever you have the ender chest open, and
  keeps showing the last-known amount once you close it.
- Detects punching an ender chest to deposit resources into it (a Hypixel Bedwars
  mechanic that never opens a screen) by watching for a matching inventory drop right
  after the punch, and adds that to the cached ender chest total.
- Repositionable: drag the HUD wherever you want it on screen.

## Configuring the HUD position

This mod adds a config entry to [Mod Menu](https://modrinth.com/mod/modmenu) (optional
dependency, install it if you don't already have it). Open **Mods → BedwarsResourceHud
→ config button**, then drag the panel to wherever you want it and hit **Done**. The
position is saved to `config/bedwarsresourcehud.json`.

If Mod Menu isn't installed, the HUD still works fine at its default position
(top-left); you just won't have a way to reposition it in-game.

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
