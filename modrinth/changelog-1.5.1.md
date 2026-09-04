## World Switcher 1.5.1

A follow-up to 1.5.0: `default` now works as a target wherever it makes sense, and says why
where it does not.

### `default` in `/wsc world …`

`default` is the name of the vanilla group (overworld/nether/end), not a managed world, so it has
no world entry behind it. `/ws default` and `/wsc player state … default` already understood that,
but `/wsc world gamemode default creative` answered `Unknown world: default` — confusing, because
the name is valid elsewhere. Now:

- `/wsc world gamemode default <mode> [forced]` and `/wsc world info default` work.
- `/wsc world gamerule default …` and `/wsc world difficulty default …` target the overworld, so
  you can set them from any world instead of only while standing there.
- `/wsc world access default` is refused on purpose: the default world is where players are sent
  when another world turns them away, so it has to stay reachable.
- `/wsc world rename|load|unload|delete default` and `/wsc group set|unset default` now explain
  the actual reason instead of reporting an unknown world.

### Fixed

- **Forced game modes now hold on a world switch.** A switch restores the stored per-world state —
  the remembered game mode included — across several handlers within the same tick, and the policy
  was applied in the middle of that, where a later handler could still overwrite it. It now runs at
  the end of the tick and gets the last word; the mode settles one tick later.

### Also

- `/wsc help` now lists `config playerdata list` and `confirm` / `cancel`. Both were in the README
  and in tab completion, but missing from the help players actually see.
