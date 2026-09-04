## World Switcher 1.5.0

Modded player data becomes configurable per key, worlds can be put into shared groups,
and a player's stored state can be moved between worlds.

### ⚠ Breaking change

**Cosmetic Armor Reworked is now per world**, including its hide-vanilla-armor flags. If you
want the old, global behaviour, set this in the new config file and reload:

```toml
"cosmeticarmorreworked:cosarmor" = false
```

`/wsc shareinventory` keeps working and is migrated automatically to the group `default`.

### Configurable modded player data

World Switcher already swapped everything mods store as NeoForge data attachments (all of
Curios) or in the persistent player NBT (Waystones, Quark). Mods that keep their own storage
were out of reach — Cosmetic Armor Reworked writes its own `playerdata/<uuid>.cosarmor` files,
so its slots stayed global no matter what.

Rather than supporting mods one at a time, 1.5.0 makes the whole thing configurable and
self-documenting. A generated file, `<world-save>/serverconfig/worldswitcher-playerdata.toml`,
lists **every** piece of player data found on your server with the owning mod and a description:

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

`true` = kept per world group, `false` = global. Unlisted keys default to `true`. Your choices
survive every regeneration; only the comments are refreshed and new keys appended.

Attachment types carry no label of their own — the game only hands out the id — so descriptions
come from a curated list shipped with the mod, the owning mod's display name, and a guess from
the shape of the stored data ("looks like an inventory (24 item stacks found)").

- `/wsc config playerdata scan` — everything found, with mod names and descriptions
- `/wsc config playerdata set <key> <true|false>` — change one entry live
- `/wsc config playerdata write|reload`

Mods with their own storage are reached through a small **bridge** interface; Cosmetic Armor
Reworked is the first one. Bridges are dependency-free and simply inactive when the mod is absent.

### World groups

Every world used to be its own inventory group, with one exception: a world could be flagged to
share `default`. Now any set of worlds can share a group:

```
/wsc group set hub_nether hub
/wsc group set hub_end hub
/wsc group list
```

Worlds in one group share a single player state — which is what "keep these worlds in sync"
means in practice, rather than two states reconciled against each other.

### Transferring player state

```
/wsc player state show <player>
/wsc player state copy|move <player> <from> <to>
/wsc player state swap <player> <a> <b>
/wsc player state clear <player> <world>
```

Works for offline players too. Position and dimension deliberately do not travel; everything
else does, including the full modded state. If the player is online in the group being written,
the new state is applied immediately, without a relog.

### Game mode per world

A world can hand out a game mode, either as a **default** that seeds a player's first visit and
then lets their own remembered per-world mode take over, or **forced**, re-applied on every entry:

```
/wsc world gamemode creative_build creative
/wsc world gamemode adventure_map adventure forced
/wsc world gamemode creative_build none
```

Forced applies immediately to everyone already in the world. It is enforced on entry, not on a
timer, so a player who may run `/gamemode` can still change it until their next entry.

### Restricting who may enter a world

```
/wsc world access staff_area 2     # ops only
/wsc world access staff_area 0     # open again
```

Enforced for `/ws`, for portals leading into the world, and at login — someone who logs out in a
world that has since been restricted is moved to the default world with their default state.
Restricted worlds also drop out of `/ws` tab completion for players who cannot enter them.
`/wsc player tp` bypasses the check on purpose: it is OP-gated already, and putting someone in is
an explicit admin decision.

Note that vanilla has no per-level command — `/op` always grants `op-permission-level` from
`server.properties` (default `4`). On a plain server the meaningful values are `0` (everyone) and
anything `>= 1` (ops only); finer levels need a permission mod.

### Reorganised commands

`/wsc` is grouped by what an action operates on — `world`, `player`, `group`, `config` — with
`help`, `confirm` and `cancel` at the top. `/wsc help <topic>` shows a category.

**All 13 previous commands still work** as aliases and print a one-line pointer to their
replacement. They are removed no earlier than 2.0.0.

| Old | New |
|---|---|
| `/wsc list` `info` `create` `import` `rename` `load` `unload` `delete` `gamerule` `difficulty` | `/wsc world …` |
| `/wsc tp` | `/wsc player tp` |
| `/wsc hooks` | `/wsc config hooks` |
| `/wsc shareinventory <world> true\|false` | `/wsc group set <world> default` / `/wsc group unset <world>` |
