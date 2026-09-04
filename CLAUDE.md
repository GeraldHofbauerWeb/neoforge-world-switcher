# CLAUDE.md

Guidance for AI assistants (Claude / Junie) working on this repository.

## ⚠️ When you take over, always

- **Track your work in the GitHub issue tracker.** Before starting a task, check for a
  matching issue at <https://github.com/GeraldHofbauerWeb/neoforge-world-switcher/issues> (create
  one if it's missing), and reference/close it when the work is done. Every non-trivial
  change should be traceable to an issue.
- **Keep the changelogs up to date.** Any user-facing change must be recorded in:
  - `CHANGELOG.md` — the canonical, German changelog. Format: [Keep a Changelog],
    [Semantic Versioning]. Add entries under `## [Unreleased]` while developing; rename it
    to `## [x.y.z] - YYYY-MM-DD` on release.
  - `modrinth/changelog-<version>.md` — the English changelog block that gets pasted into
    the Modrinth version's changelog field.

[Keep a Changelog]: https://keepachangelog.com/en/1.0.0/
[Semantic Versioning]: https://semver.org/spec/v2.0.0.html

## Project at a glance

- **Mod:** World Switcher (`mod_id=worldswitcher`), a **server-side-only** Multiverse-style
  world manager: switch worlds with `/ws`, manage them with `/wsc`, per-player/per-world
  player state, named world groups, per-world gamerules/time/weather/difficulty, per-world
  default/forced game mode, permission-gated worlds, and command hooks.
- **Group:** `net.geraldhofbauer.worldswitcher` · **License:** MIT · **Author:** Gerald Hofbauer
- **Loader / MC:** NeoForge `21.0.167`, Minecraft `1.21.1` (range `[1.21,1.21.1]`).
- **Current version:** `1.5.0` (see `mod_version` in `gradle.properties`).
- **Repo:** <https://github.com/GeraldHofbauerWeb/neoforge-world-switcher> (branch `main`).
- Vanilla 1.21.1 clients can join without the mod (client unsupported / server required).

## Build & test

```bash
./gradlew build      # produces build/libs/worldswitcher-<version>.jar
```

The built jar is auto-copied into `test-server/mods/`. There are no automated tests for the
gameplay flows — validation is manual (via `test-server/`, NeoForge 1.21.1).

## Release workflow (bump → build → tag → GitHub → Modrinth)

1. Bump `mod_version` in `gradle.properties`.
2. Update the changelogs (see the note above): `CHANGELOG.md` (German) and
   `modrinth/changelog-<version>.md` (English).
3. `./gradlew build` and sanity-check the jar in `build/libs/`.
4. Commit and push to `main` (this runs `.github/workflows/build.yml` → artifact only).
5. Create and push an annotated tag `vX.Y.Z` — this triggers
   `.github/workflows/release.yml`, which builds the jar and publishes a public GitHub
   Release with it attached.
6. **Modrinth (manual upload):**
   - Upload only the main `build/libs/worldswitcher-<version>.jar` (no `-sources`/`-slim`).
   - Loader: `NeoForge` · Game versions: `1.21.1` (+ `1.21`) · Channel: `Release`.
   - Environment: server `required`, client `unsupported`.
   - Project body = `modrinth/description.md`; version changelog = `modrinth/changelog-<version>.md`.

## Deployment (Gerald's own server)

- SSH: `gerry@82.165.95.152` (user is in the `docker` group; runs AMP instances).
- **Sebs Modpack v4** (Gerald's own server, played with friends) = AMP instance
  `AMP_SebsModpackv401`, world save `survival_world`, mods folder `/AMP/Minecraft/mods/`
  (files owned by `amp:amp`). Restart via `docker restart`.
- Always **delete the old `worldswitcher-*.jar`** before copying the new one in.
- ⚠️ Leave the v3 instance `AMP_SebsModpackv302` untouched unless explicitly asked.

## Per-world config files (per world save, under `<world-save>/serverconfig/`)

- `worldswitcher-server.toml` — the NeoForge `ModConfigSpec` options (master switches).
- `worldswitcher-hooks.json` — command hooks, see below.
- `worldswitcher-playerdata.toml` — **generated**, per-key control over which modded player data
  is per world (`true`) and which is global (`false`). Written from `PlayerDataCatalog`, which
  enumerates the attachment registry, observes persistent-NBT keys and lists the registered
  bridges. Never hand-author it from scratch: `/wsc config playerdata write` regenerates it,
  keeping existing choices. On the live server:
  `/AMP/Minecraft/survival_world/serverconfig/worldswitcher-playerdata.toml`.

## Adding support for a mod that stores player data itself

Mods reachable via NeoForge data attachments or the persistent player NBT need **no** code — they
are swapped generically. Only mods with their own storage need work: implement
`player/data/PlayerDataBridge` (dependency-free reflection, `ClassNotFoundException` → silently
off) and register it in `PlayerDataBridges.ALL`. See `bridge/CosArmorBridge` as the template, and
add a line to `src/main/resources/data/worldswitcher/playerdata_labels.json` so the generated
config explains it.

## Command hooks

- Per world-save JSON: `<world-save>/serverconfig/worldswitcher-hooks.json`
  (on the server: `/AMP/Minecraft/survival_world/serverconfig/worldswitcher-hooks.json`).
- Events: `firstPlayerJoin` (0→1), `lastPlayerLeave` (1→0), `playerMoved` (world switch).
- Variables: `{{worldName}}`, `{{worldId}}`, `{{playerName}}`, `{{playerUuid}}`.
- `as: server` (OP 4, output suppressed, positioned at the world) or `as: player`; default
  from `hookDefaultRunAs`. Global hooks run before per-world hooks. Master toggle
  `enableCommandHooks`. Live reload/inspect via `/wsc config hooks reload` and
  `/wsc config hooks status`.
- See `README.md` for full docs and ready-made examples (e.g. pausing
  `doDaylightCycle`/`doWeatherCycle`/`doSeasonCycle` while a world is empty).

## Git conventions

- Never commit on your own — only when explicitly asked. Add Junie as co-author:
  `--trailer "Co-authored-by: Junie <junie@jetbrains.com>"`.
- Do **not** commit `.junie/` (git-ignored session/plan files) or local `test-server/`
  runtime files (`server.properties`, `config/worldedit/worldedit.properties`).
