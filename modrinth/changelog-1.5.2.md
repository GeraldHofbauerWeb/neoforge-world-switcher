## World Switcher 1.5.2

Respawning is now per world.

### Per-world respawn point

A player had exactly one respawn point, wherever they last slept. Sleep in a bed in world A, switch
to world B, die there — and you were pulled out of B and back into A. With no bed at all, vanilla's
fallback dropped you into the **overworld**, so a death in a managed world always ended in the
default world.

The bed or respawn anchor is now part of the per-world player state, like the inventory:

- Every world remembers its own bed, swapped along with the rest of the state.
- Dying without a bed in the current world respawns you at **that world's spawn**, not in the
  overworld. The "no respawn block" message still reaches the client, so it is still clear why the
  bed did not work.
- Worlds in the same group share one bed, exactly as they share one inventory — the vanilla
  dimensions are one such group, so dying in the nether still returns you to your overworld bed.

Controlled by the new `perWorldRespawnPoint` option (default on) in
`world/serverconfig/worldswitcher-server.toml`. Set it to `false` for the old behaviour: one global
respawn point for every world. With `separateInventories = false` there is no per-world state to
store it in, so the option has no effect there.

### Also

- `/wsc player state copy|move|swap` leaves the respawn point behind in its own world, the way it
  already leaves position and dimension. A bed that travelled with the state would have pulled the
  player out of the world their state had just been moved into.
