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
- **`/wsc`** — world management for operators, grouped by what it acts on: `world`
  (`create`, `import`, `rename`, `load`, `unload`, `info`, `delete`, `gamerule`, `difficulty`,
  `gamemode`, `access`), `player` (`tp`, `state …`), `group` and `config`. Every pre-1.5.0
  command still works as a deprecated alias.
- **Per-player, per-world player state** (optional, on by default): inventory, ender chest, XP,
  health, hunger, effects, game mode and last position are kept separately for every world. The
  vanilla dimensions (overworld/nether/end) count as one group.
- **Modded player state per world**, dependency-free, and configurable **per key**: a generated,
  commented `serverconfig/worldswitcher-playerdata.toml` lists everything found on your server —
  NeoForge data attachments (e.g. the whole **Curios** inventory), persistent player NBT
  (`NeoForgeData`, used by Waystones/Quark, …), **Tough As Nails** thirst/temperature, and mods
  that keep their own storage such as **Cosmetic Armor Reworked** — each with the owning mod, a
  description, and a simple `true` (per world) / `false` (global). Inspect and change it live with
  `/wsc config playerdata scan` and `… set <key> <true|false>`.
- **World groups**: put any set of worlds into one inventory group and they share a single player
  state — the honest way to keep worlds in sync. `/wsc group set <world> <group>`. The group
  `default` is the vanilla dimensions, so putting a world there means players keep their main
  items (the old `shareinventory` behaviour, still available as an alias).
- **Move a player's state between worlds**: `/wsc player state show|copy|move|swap|clear`, offline
  players included. Position never travels; everything else does, modded state and all.
- **Per-world game mode**: a world can hand out a mode as a *default* that seeds a player's first
  visit, or *forced*, re-applied on every entry — the difference between "this is the creative
  world" and "nobody leaves adventure mode here". `/wsc world gamemode <world> <mode> [forced]`.
- **Restricted worlds**: give a world a minimum permission level with
  `/wsc world access <world> <0-4>`. Enforced for `/ws`, for portals leading into it and at login;
  restricted worlds also disappear from tab completion for players who cannot enter them.
- **Per-world game rules, time, weather & difficulty**: each managed world keeps its own clock,
  weather, rules and difficulty. `/gamerule`, `/time`, `/weather` and `/difficulty` are
  context-sensitive; freeze time or keep eternal night per world, and sleeping only skips your own
  world's night. Works with modded rules too, e.g. Serene Seasons' `doSeasonCycle`.
- **Command hooks**: run admin-defined commands automatically on world events —
  `firstPlayerJoin` (0 → 1 players), `lastPlayerLeave` (1 → 0 players) and `playerMoved` (a player
  switches world). Define them globally and per world in
  `serverconfig/worldswitcher-hooks.json`, with `{{worldName}}`, `{{worldId}}`, `{{playerName}}`
  and `{{playerUuid}}` variables and per-hook run-as (`server`/`player`). Live-reload with
  `/wsc config hooks reload`. Perfect for pausing `doDaylightCycle`/`doWeatherCycle` (or Serene
  Seasons' `doSeasonCycle`) while a world is empty.
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
`enableCommandHooks` / `hookDefaultRunAs`. Two generated files sit next to it:
`worldswitcher-hooks.json` for the command hooks, and `worldswitcher-playerdata.toml` for the
per-key decisions about which modded player data is per world and which is global.

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
