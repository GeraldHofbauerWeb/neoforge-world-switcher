# World Switcher

Run **multiple worlds on one server** and let players switch between them with a single
command — Multiverse-style, but for NeoForge.

**100% server-side**: no client installation needed. Vanilla 1.21.1 clients can join without the
mod, because World Switcher registers no items, blocks or network payloads — custom worlds use the
vanilla `minecraft:overworld` dimension type with a `worldswitcher:*` key that vanilla clients
accept.

> 🤖 **AI Collaboration Notice**: The bulk of this project was developed in collaboration with
> Anthropic's Claude AI (with JetBrains' Junie as a complementary assistant). The AI helped with
> code implementation, documentation, and project structure. While the core ideas and direction
> came from human creativity, the AI's assistance made this project more robust and feature-complete.
> We believe in transparency about AI usage while celebrating the potential of human-AI
> collaboration in software development.

## Features

- **`/ws <world>`** — switch worlds instantly, no server restart. Bare `/ws` lists all worlds
  (clickable). `default` is the vanilla world group (overworld/nether/end) and is always available.
- **`/wsc`** — full world management for operators: `create <name> [seed]`,
  `import <folder> [as <name>]`, `rename`, `load`, `unload`, `tp <player> <world>`, `info`,
  `delete` (with confirmation).
- **Per-player, per-world player state** (optional, on by default): inventory, ender chest, XP,
  health, hunger, effects, game mode and last position are kept separately for every world. The
  vanilla dimensions (overworld/nether/end) count as one group.
- **Modded player state per world**, dependency-free: NeoForge data attachments (e.g. the whole
  **Curios** inventory), persistent player NBT (`NeoForgeData`, used by Waystones/Quark, …) and
  **Tough As Nails** thirst/temperature are all swapped per world.
- **Per-world game rules, time, weather & difficulty**: each managed world keeps its own clock,
  weather, rules and difficulty. `/gamerule`, `/time`, `/weather` and `/difficulty` are
  context-sensitive; freeze time or keep eternal night per world, and sleeping only skips your own
  world's night. Works with modded rules too, e.g. Serene Seasons' `doSeasonCycle`.
- **Command hooks**: run admin-defined commands automatically on world events —
  `firstPlayerJoin` (0 → 1 players), `lastPlayerLeave` (1 → 0 players) and `playerMoved` (a player
  switches world). Define them globally and per world in
  `serverconfig/worldswitcher-hooks.json`, with `{{worldName}}`, `{{worldId}}`, `{{playerName}}`
  and `{{playerUuid}}` variables and per-hook run-as (`server`/`player`). Live-reload with
  `/wsc hooks reload`. Perfect for pausing `doDaylightCycle`/`doWeatherCycle` (or Serene Seasons'
  `doSeasonCycle`) while a world is empty.
- **Shared inventory worlds** (`/wsc shareinventory <world> true`): mark a world "keep-inventory"
  so it uses the `default` inventory group instead of its own.
- **Clickable switch announcements**: when someone switches world, everyone else sees a chat
  message whose world name is a clickable `/ws <world>` link to follow along.
- **World import done right**: copies only the overworld data, reads seed & spawn from the source
  `level.dat`, so terrain keeps generating seamlessly beyond the imported border. Old worlds
  (1.18+) are upgraded on the fly.
- **Safe renaming**: display names are decoupled from the internal world id — inventories, bed
  spawns and world folders survive renames.
- **Persistent**: registered worlds are re-loaded automatically at server start.

## Edge cases handled

- Nether portals inside the default world group don't touch your inventory.
- Portals that cross world groups swap the player state like `/ws` would (configurable).
- Dying in a world without a bed respawns you in the default world with your default state — your
  items stay at the death spot in the other world.
- If a world is deleted while you're offline in it, you're safely reconciled into the overworld on
  login.

## Configuration

See `world/serverconfig/worldswitcher-server.toml`, e.g. `separateInventories` (default `true`),
`wsPermissionLevel` (`0` = everyone may use `/ws`), `worldsFolder` (import folder, default
`worlds`), `autoLoadOnStartup`, `restoreLastPosition`, `handlePortalGroupChanges`,
`perWorldGameRules`, `perWorldTimeAndWeather`, `perWorldDifficulty`, `announceSwitches` and
`enableCommandHooks` / `hookDefaultRunAs`. Command hooks live in a separate
`world/serverconfig/worldswitcher-hooks.json`.

## Notes

- Always global by nature: hardcore, the difficulty lock, `sendCommandFeedback`,
  `logAdminCommands`, `spawnChunkRadius`.
- Map mods (BlueMap etc.) will see the extra dimensions and may need per-dimension config.
- `/execute in worldswitcher:<id> run ...` works as usual — handy for debugging.

## About AI Assistance

This project demonstrates the potential of human-AI collaboration in software development. The AI
assistant helped with code implementation, documentation, project structure, CI/CD setup and bug
fixes. While the AI provided technical assistance, all creative decisions, feature ideas and
project direction came from human input. We believe this transparency about AI usage is important
for the open-source community.

Full documentation on [GitHub](https://github.com/GeraldHofbauerWeb/neoforge-world-switcher).
