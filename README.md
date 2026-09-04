<p align="center">
  <img src="assets/icon.svg" width="140" alt="World Switcher icon">
</p>

# World Switcher

Server-side-only NeoForge 1.21.1 mod for running multiple worlds on one server, Multiverse-style:
switch between them with a command, import existing world saves from a folder, and keep a separate
player state (inventory & more) per world.

**No client mod needed** — vanilla 1.21.1 clients can join. The mod registers no items, blocks or
network payloads; custom worlds use the vanilla `minecraft:overworld` dimension type with a
`worldswitcher:*` dimension key, which vanilla clients accept.

> 🤖 **AI Collaboration Notice**: The bulk of this project was developed in collaboration with
> Anthropic's Claude AI (with JetBrains' Junie as a complementary assistant). The AI helped with
> code implementation, documentation, and project structure. While the core ideas and direction
> came from human creativity, the AI's assistance made this project more robust and feature-complete.
> We believe in transparency about AI usage while celebrating the potential of human-AI
> collaboration in software development.

## Commands

### `/ws <world>` — switch world

Allowed for all players by default (`wsPermissionLevel` config). `default` is the vanilla world
group (overworld/nether/end) and always available. Tab completion lists all worlds.

### `/wsc <category> <action>` — server management (OP level 2+)

`/wsc` on its own lists the categories; `/wsc help <topic>` (`worlds`, `players`, `groups`,
`config`) shows the details.

**`/wsc world`** — the worlds themselves:

| Action | Description |
|---|---|
| `list` | All worlds with load state and player counts (names clickable → `/ws`) |
| `info <world>` | Seed, spawn, folder, disk size, inventory group, import origin |
| `create <name> [seed]` | Create a fresh world (random seed if omitted) |
| `import <source> [as <name>]` | Copy a world save from the worlds folder (subpaths need quotes: `"backups/old"`) |
| `rename <world> <newName>` | Rename — inventories, spawns and the world folder are untouched |
| `load` / `unload <world>` | Load/unload at runtime; unload moves players to the default spawn |
| `delete <world>` | Delete world + data + stored inventories (asks for confirmation) |
| `gamerule <world> [<rule> [value]]` | Per-world game rules; without a rule, lists this world's overrides |
| `difficulty <world> [value]` | Per-world difficulty |
| `gamemode <world> [none\|<mode> [forced]]` | The game mode this world hands out; without a value, shows the current setting |
| `access <world> [0-4]` | Minimum permission level to enter; without a value, shows the current setting |

**`/wsc player`** — one player at a time:

| Action | Description |
|---|---|
| `tp <player> <world>` | Switch another player |
| `state show <player>` | Which world groups they have stored state in, with a one-line summary each |
| `state copy <player> <from> <to>` | Copy their stored state to another world (source kept) |
| `state move <player> <from> <to>` | Same, then reset the source to a fresh start |
| `state swap <player> <a> <b>` | Exchange two worlds' stored state |
| `state clear <player> <world>` | Reset one world to a fresh start |

See [Transferring player state](#transferring-player-state-between-worlds) for what does and does
not travel along.

**`/wsc group`** — which worlds share one inventory:

| Action | Description |
|---|---|
| `list` | All inventory groups with their worlds and how many player states are stored |
| `info <group>` | The worlds in a group, and its stored player states |
| `set <world> <group>` | Put a world into a group — worlds in one group share a single player state |
| `unset <world>` | Give a world its own group back |

**`/wsc config`** — server-side settings:

| Action | Description |
|---|---|
| `playerdata [status]` | Overview: what was found, how much of it is global |
| `playerdata list` | The keys currently configured, and their mode |
| `playerdata scan` | Everything found on this server, with mod names and descriptions |
| `playerdata set <key> <true\|false>` | `true` = per world, `false` = global; writes the config file |
| `playerdata write` / `reload` | Regenerate the file (keeping your choices) or re-read it |
| `hooks [status\|reload]` | Command hooks: `status` (default) shows the configured hooks, `reload` re-reads the JSON file live |

Plus `/wsc confirm` and `/wsc cancel` for the clickable confirmation prompts.

#### Deprecated aliases

Every command that existed before 1.5.0 still works from the top level and prints a pointer to
its replacement. They are removed in 2.0.0.

| Old | New |
|---|---|
| `/wsc list` `info` `create` `import` `rename` `load` `unload` `delete` `gamerule` `difficulty` | `/wsc world …` |
| `/wsc tp` | `/wsc player tp` |
| `/wsc hooks` | `/wsc config hooks` |
| `/wsc shareinventory <world> true\|false` | `/wsc group set <world> default` / `/wsc group unset <world>` |

### Per-world game rules, time, weather and difficulty

Every managed world has its own game rules, day time, weather and difficulty (config
`perWorldGameRules` / `perWorldTimeAndWeather` / `perWorldDifficulty`). The vanilla
`/gamerule`, `/time`, `/weather` and `/difficulty` commands are context-sensitive: executed
**inside a managed world** they change only that world, executed in the vanilla dimensions they
behave exactly like vanilla (global). To target a world from anywhere:
`/wsc gamerule|difficulty <world> …` or `/execute in worldswitcher:<id> run time|weather|… …`.
Per-world difficulty covers peaceful hostile-despawn, monster spawn rules and regional
difficulty; clients are shown the difficulty of the world they are in.

- New worlds start with a copy of the current global rules and the overworld's clock; imported
  worlds keep the day time and game rules from their `level.dat`.
- `doDaylightCycle`/`doWeatherCycle` work per world (frozen clock, eternal rain, …). Sleeping
  skips only that world's night.
- Still global by nature: `sendCommandFeedback`, `logAdminCommands`, `spawnChunkRadius`.
- Works with modded rules too — e.g. Serene Seasons' `doSeasonCycle` (see below).

### Game mode per world

A world can hand out a game mode, in one of two flavours:

```
/wsc world gamemode creative_build creative           # default: seeds the FIRST visit only
/wsc world gamemode adventure_map adventure forced    # forced: re-applied on EVERY entry
/wsc world gamemode creative_build none               # the world stops having an opinion
```

**default** is the starting value for a world: the first time a player arrives they get that mode,
and from then on the world remembers whatever mode they were last in there — the normal per-world
player state. Good for "this is the creative world" without locking anyone in.

**forced** re-applies on every entry, which is what an adventure map or a lobby wants. Setting it
applies immediately to everyone already in the world. It is enforced on entry, not continuously: a
player who may run `/gamemode` can still change it while they are inside, and it snaps back the
next time they enter.

Without a per-world player state (`separateInventories = false`) there is no remembered mode for a
default to seed, so only `forced` has an effect there — the command says so when you set one.

### Restricting who may enter a world

Each world has a minimum permission level, `0` by default (everyone):

```
/wsc world access staff_area 2     # ops only
/wsc world access staff_area 0     # open again
/wsc world access staff_area       # show the current level
```

It is enforced in three places: `/ws`, portals that lead into the world, and login — someone who
logs out in a world that has since been restricted is moved to the default world with their default
state, the same way an unloaded world is reconciled. Restricted worlds also disappear from `/ws`
tab completion and from the bare `/ws` list for players who cannot enter them.

`/wsc player tp <player> <world>` deliberately **bypasses** the check: it is already OP-gated, and
moving someone into a restricted world is an explicit admin decision. Setting a level while players
are inside asks for confirmation and then moves out everyone who lacks it.

> **Vanilla has no per-level command.** `/op <player>` always grants the level from
> `op-permission-level` in `server.properties` (default `4`) — there is no `/op <player> <level>`,
> and `/deop` drops you to `0`. On a plain server the meaningful values are therefore `0`
> (everyone) and anything `>= 1` (ops only). Finer levels are useful with a permission mod, or by
> editing the `"level"` field in `ops.json` and restarting.

### Serene Seasons

[Serene Seasons](https://modrinth.com/mod/serene-seasons) already keeps its season progress per
dimension, so it combines nicely: add your world to `whitelisted_dimensions` in
`config/sereneseasons/seasons.toml` (e.g. `"worldswitcher:creative"`) and it gets its own season
state; `doSeasonCycle` can be toggled per world like any other rule.

### Importing worlds

Put world save folders (the folder containing `level.dat`) into `<server>/worlds/` — directly or
in subfolders. `/wsc import` copies **only the overworld data** (`region`, `entities`, `poi`,
`data`) into the server save under `dimensions/worldswitcher/<id>/`; the source folder is never
modified. Seed and spawn are read from `level.dat`, so terrain keeps generating correctly beyond
the pre-generated area. Old worlds (e.g. 1.18) are upgraded chunk-by-chunk on first visit —
expect brief lag spikes in old regions.

Stop the source server before importing a live world (a fresh `session.lock` triggers a warning).

## Per-world player state

With `separateInventories = true` (default), each world keeps its own player state per player:
inventory, ender chest, XP, health, hunger, potion effects, game mode and last position. The
vanilla dimensions count as one group `default`. First visit to a world = fresh start at its
spawn.

### World groups (shared inventories, "auto-sync")

Every world starts in an inventory group of its own, named after the world. Put two worlds into
the same group and they share **one** player state — that is what keeping worlds in sync means in
practice; there is one inventory, not two that are reconciled against each other:

```
/wsc group set hub_nether hub      # hub_nether now uses hub's player state
/wsc group set hub_end hub
/wsc group list
```

The group `default` is the one all vanilla dimensions belong to. Putting a world there means
players **keep their default-world items** when they enter it — the old `shareinventory` flag,
which still works as a deprecated alias and is migrated automatically.

`/wsc group unset <world>` gives a world its own group back. Regrouping while players are inside
re-groups them in place: their state under the old group is kept (so `/wsc player state copy` can
fetch it back), and modded client views like the Curios HUD may lag until the next relog.

### Modded player state

Modded data is swapped along with the vanilla state, dependency-free, through four mechanisms:

- **NeoForge data attachments**: everything mods attach to the player, e.g. the whole **Curios**
  inventory (charm/ring/necklace/… slots, incl. Elytra Slot and a worn toolbelt).
- **Persistent player NBT** (the `NeoForgeData` tag): used e.g. by Waystones and Quark.
- **Bridges** for mods that keep player data in their own storage, where neither of the above can
  reach it. Currently: **Cosmetic Armor Reworked**, which writes its own
  `playerdata/<uuid>.cosarmor` files.
- **Tough As Nails**: thirst and temperature (TAN stores them outside all of the above;
  integrated via its API, inactive without TAN).

Which individual pieces are per world and which stay global is decided **per key** in a generated,
commented file — `<world-save>/serverconfig/worldswitcher-playerdata.toml`. It lists everything
found on this server with the owning mod and a description, so you never have to guess what an id
means:

```toml
# Curios API
#   All Curios accessory slots — rings, charms, belts, and whatever other mods
#   add to them (Elytra Slot, Caelus, a worn tool belt, ...)
"curios:inventory" = true

# Carry On
#   The block or entity the player is currently carrying on their head
"carryon:carry_on_data" = false

# CosmeticArmorReworked
#   Cosmetic armor slots (stored by the mod in playerdata/<uuid>.cosarmor)
"cosmeticarmorreworked:cosarmor" = true
```

`true` = kept per world group, `false` = global. Keys that are not listed default to `true`. The
file is regenerated on every server start: your choices are preserved, comments refreshed and
newly discovered keys appended. Inspect and change it live with `/wsc config playerdata scan`
and `/wsc config playerdata set <key> <true|false>`.

Descriptions are best-effort. Attachment types carry no label of their own — the game only gives
us the id — so they come from a small curated list shipped with the mod, the owning mod's display
name, and a guess derived from the shape of the stored data ("looks like an inventory (24 item
stacks found)").

The coarse master switches in `worldswitcher-server.toml` (`swapModAttachments`,
`swapPersistentData`, `swapToughAsNails`) still apply on top: a key is swapped only when both
agree. The older `attachmentExcludes` / `persistentDataExcludes` lists keep working as hard
"always global" overrides and seed the new file the first time it is generated.

### Transferring player state between worlds

`/wsc player state copy|move|swap|clear` moves one player's stored state between worlds — useful
after regrouping worlds, or when someone left their gear in the wrong place:

```
/wsc player state show Steve
/wsc player state copy Steve default skyblock
```

Offline players work too; the store is keyed by UUID. **Position and dimension never travel** —
the target keeps its own last position, or its spawn if it has none. Everything else does,
including the full modded state (Curios, persistent NBT, bridges such as Cosmetic Armor, Tough As
Nails). If the player happens to be online in the group being written, the new state is applied to
them immediately, without a relog.

Handled edge cases:

Handled edge cases:

- **Nether portals inside the default group** don't swap anything.
- **Portals that cross world groups** (e.g. a nether portal built in an imported world) swap the
  state like `/ws` would (`handlePortalGroupChanges`; set to `false` to block such travel).
- **Death in world X without a bed there** respawns you in the default world with your default
  state; world X keeps the post-death state (your items drop at the death spot as usual).
- **World deleted/unloaded while you were offline in it**: on login you're reconciled into the
  overworld with your default state.

## Command hooks

Run admin-defined commands automatically on world events — for example enforce a game mode when a
player enters your creative world, broadcast a message, or reset an empty world. Hooks live in a
separate JSON file per world save: `world/serverconfig/worldswitcher-hooks.json` (an inert,
commented example is generated on first server start). Toggle the whole system with
`enableCommandHooks` (default on).

**Events** (world identity uses the World Switcher grouping — the vanilla overworld/nether/end are
one world `default`, each managed world is its own; an overworld→nether portal does **not** count
as a move):

| Event (JSON key) | Fires when |
|---|---|
| `firstPlayerJoin` | A world goes from 0 to 1 players (the first player enters it) |
| `lastPlayerLeave` | A world goes from 1 to 0 players (the last player leaves it) |
| `playerMoved` | A player switches world (source world ≠ destination world) |

**Variables** substituted in every command: `{{worldName}}`, `{{worldId}}` (the id or `default`),
`{{playerName}}`, `{{playerUuid}}`.

**Run-as** — each hook may set `"as": "server"` or `"as": "player"`; without it the global
`hookDefaultRunAs` (default `server`) applies. `server` runs at OP level 4 with output suppressed,
positioned at the world; `player` runs as the triggering player, bound by their own permission
level. A hook is written either as a plain command string or as `{ "command": "...", "as": "..." }`.

On each trigger, **global** hooks run first, then the matching **per-world** hooks. On a switch
A → B the order is: `lastPlayerLeave` of A (if it emptied), `firstPlayerJoin` of B (if it was
empty), then `playerMoved` of B (variables = destination). Failing commands are logged and never
interrupt the switch/login/logout. Edit the file and apply it live with `/wsc hooks reload`.

```json
{
  "global": {
    "playerMoved": ["title {{playerName}} title {\"text\":\"{{worldName}}\"}"]
  },
  "worlds": {
    "creative": {
      "firstPlayerJoin": ["say The creative world just opened up!"],
      "playerMoved": [{ "command": "gamemode creative {{playerName}}", "as": "server" }],
      "lastPlayerLeave": ["weather clear"]
    },
    "default": {
      "playerMoved": [{ "command": "gamemode survival {{playerName}}", "as": "server" }]
    }
  }
}
```

### Example: pause the day/night cycle when a world is empty

A common request is to only let time advance while someone is actually in a world — the sun
"freezes" as soon as the last player leaves and "resumes" when the first player comes back. Wire
the `doDaylightCycle` game rule to `firstPlayerJoin`/`lastPlayerLeave`. Shown here for the vanilla
`default` world (overworld/nether/end), but it works the same for any managed world by using its
id instead of `default`:

```json
{
  "worlds": {
    "default": {
      "firstPlayerJoin": [{ "command": "gamerule doDaylightCycle true", "as": "server" }],
      "lastPlayerLeave": [{ "command": "gamerule doDaylightCycle false", "as": "server" }]
    }
  }
}
```

`firstPlayerJoin` fires when the world goes from 0 → 1 players (resume time) and `lastPlayerLeave`
when it goes 1 → 0 (stop time), so the cycle only ever runs while the world is occupied. Because
the hook runs `as: server` positioned at the world, the game rule is applied to that world
(honouring `perWorldTimeAndWeather`). You can freeze more than just the sun by adding
`gamerule doWeatherCycle false` / `true` and `gamerule randomTickSpeed 0` / `3` alongside it.

If you run [Serene Seasons](https://modrinth.com/mod/serene-seasons), it adds a `doSeasonCycle`
game rule that behaves the same way, so you can pause the seasonal cycle for empty worlds too — just
add `gamerule doSeasonCycle false` / `true` next to the others:

```json
{
  "worlds": {
    "default": {
      "firstPlayerJoin": [
        { "command": "gamerule doDaylightCycle true", "as": "server" },
        { "command": "gamerule doWeatherCycle true", "as": "server" },
        { "command": "gamerule doSeasonCycle true", "as": "server" }
      ],
      "lastPlayerLeave": [
        { "command": "gamerule doDaylightCycle false", "as": "server" },
        { "command": "gamerule doWeatherCycle false", "as": "server" },
        { "command": "gamerule doSeasonCycle false", "as": "server" }
      ]
    }
  }
}
```

## Config (`world/serverconfig/worldswitcher-server.toml`)

| Option | Default | Description |
|---|---|---|
| `separateInventories` | `true` | Per-world player state |
| `wsPermissionLevel` | `0` | Permission level for `/ws` (0 = everyone) |
| `worldsFolder` | `worlds` | Import container folder (relative to server root) |
| `autoLoadOnStartup` | `true` | Re-load registered worlds at server start |
| `restoreLastPosition` | `true` | `/ws` returns you to your last position in that world |
| `swapGamemode` | `true` | Game mode is part of the per-world state (first visit keeps the current one) |
| `handlePortalGroupChanges` | `true` | Swap state on cross-group portal travel (else cancel it) |
| `importCopyAsync` | `true` | Copy imports on a background thread |
| `perWorldGameRules` | `true` | Each world keeps its own game rules |
| `perWorldTimeAndWeather` | `true` | Each world keeps its own day time and weather |
| `perWorldDifficulty` | `true` | Each world keeps its own difficulty |
| `swapModAttachments` | `true` | Master switch for NeoForge data attachments (Curios etc.) |
| `attachmentExcludes` | `[]` | Legacy override: attachment ids that always stay global |
| `swapPersistentData` | `true` | Master switch for the persistent player NBT (`NeoForgeData`) |
| `persistentDataExcludes` | `[]` | Legacy override: persistent-data keys that always stay global |
| `swapToughAsNails` | `true` | Master switch for Tough As Nails thirst/temperature |
| `announceSwitches` | `true` | Broadcast a clickable "player switched to X" message so others can click to follow |
| `enableCommandHooks` | `true` | Master toggle for the command-hook system (see [Command hooks](#command-hooks)) |
| `hookDefaultRunAs` | `server` | Default run-as for hooks without their own `as` (`server` = OP 4, `player` = own perms) |

Per-key decisions live in a second, generated file next to it —
`worldswitcher-playerdata.toml`, see [Modded player state](#modded-player-state).

## Known behavior

- With `perWorldGameRules`/`perWorldTimeAndWeather`/`perWorldDifficulty` disabled, game rules,
  time, weather and difficulty are shared across all worlds (derived from the overworld, like
  the vanilla nether/end).
- Always global: hardcore, the difficulty lock, `sendCommandFeedback`, `logAdminCommands`,
  `spawnChunkRadius`, and anything a mod reads from `server.getWorldData()` directly.
- Teleports by mods that bypass the standard dimension-change event swap the modded player
  state after the fact — client-side views like the Curios HUD may lag behind until the next
  world switch or relog (server state is always correct).
- Custom worlds don't appear in `level.dat`'s world-gen settings; they are tracked in
  `world/data/worldswitcher_worlds.dat` and recreated at startup.
- Other mods that iterate all levels (maps like BlueMap, etc.) will see the custom worlds and may
  need their own per-dimension config.
- `/execute in worldswitcher:<id> run ...` works as usual — handy for debugging.
- A **forced** game mode is applied when a player enters the world, not on a timer — someone with
  permission to run `/gamemode` can still change it until their next entry.
- **Cosmetic Armor Reworked** is per world from 1.5.0 on, including its hide-vanilla-armor flags.
  Set `"cosmeticarmorreworked:cosarmor" = false` in `worldswitcher-playerdata.toml` for the old,
  global behaviour.
- Player data keys that are **not** listed in `worldswitcher-playerdata.toml` are per world. New
  mods therefore start out separated, not shared.
- The attachment section of that file also lists attachments that belong to blocks, items or other
  entities — the registry has no notion of what an attachment attaches to. Those are grouped at the
  bottom under "never seen on a player" and toggling them does nothing.

## Building

```
./gradlew build          # jar lands in build/libs/ and is auto-copied to test-server/mods/
```

NeoForge 21.0.167, Minecraft 1.21.1, Java 21, Gradle 8.14.3. The `test-server/` folder holds a
local dedicated server for manual testing (not committed; install NeoForge there with
`java -jar neoforge-installer.jar --install-server .`).

## About AI Assistance

This project demonstrates the potential of human-AI collaboration in software development. The AI
assistant helped with:

- Code implementation
- Documentation writing
- Project structure
- CI/CD setup
- Bug fixes

While the AI provided technical assistance, all creative decisions, feature ideas, and project
direction came from human input. We believe this transparency about AI usage is important for the
open-source community.
