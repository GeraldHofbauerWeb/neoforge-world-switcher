# Changelog
All notable changes to World Switcher will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.5.0] - 2026-09-04
### Added
- **Konfigurierbare modded Player-Daten** — statt jeden Mod einzeln zu unterstützen, gibt es jetzt
  eine generierte, kommentierte Datei `<world-save>/serverconfig/worldswitcher-playerdata.toml`.
  Sie listet **jedes** auf dem Server gefundene Stück Spielerdaten mit besitzendem Mod und
  Beschreibung auf, jeweils mit einem einfachen `true` (pro Welt) / `false` (global).
  Nicht gelistete Keys sind pro Welt. Die Datei wird bei jedem Serverstart aufgefrischt:
  Entscheidungen bleiben erhalten, Kommentare werden erneuert, neue Keys angehängt.
  Beschreibungen kommen aus einer kuratierten Liste im Jar, dem Mod-Anzeigenamen aus der
  ModList und einer Heuristik über die Form der gespeicherten NBT-Daten („looks like an
  inventory (24 item stacks found)") — Attachment-Typen haben von sich aus **kein** Label,
  das Spiel liefert nur die ID.
- **Bridge-SPI für Mods mit eigenem Storage** — Mods, die Spielerdaten weder als NeoForge-Attachment
  noch im Persistent-NBT ablegen, sind generisch nicht erreichbar. Erste Implementierung:
  **Cosmetic Armor Reworked**, das seine Slots in eigene `playerdata/<uuid>.cosarmor`-Dateien
  schreibt und deshalb bisher weltübergreifend blieb. Dependency-frei über Reflection; ohne den
  Mod inaktiv, ohne Logspam.
- **Echte benannte Weltengruppen** (`/wsc group list|info|set|unset`) — bisher konnte eine Welt nur
  mit `default` teilen. Jetzt lassen sich beliebig viele Welten in eine gemeinsame Gruppe legen;
  Welten in einer Gruppe teilen sich **einen** Spielerzustand. Das ist auch die Antwort auf
  „Welten synchron halten": eine Gruppe statt zwei ständig abgeglichener Zustände.
  `shareinventory` wird automatisch als Gruppe `default` migriert.
- **Gamemode pro Welt** (`/wsc world gamemode <welt> [none|<modus> [forced]]`) — bisher behielt man
  beim Erstbesuch einfach seinen aktuellen Modus (`PlayerSnapshot.applyFresh` fasst den Gamemode
  bewusst nicht an). Jetzt kann eine Welt einen Modus vorgeben, in zwei Varianten: **default**
  setzt ihn nur beim Erstbesuch der Inventargruppe, danach gilt wieder der pro Welt gemerkte Modus
  des Spielers („das ist die Creative-Welt"); **forced** setzt ihn bei jedem Betreten
  („hier bleibt jeder im Adventure-Modus") und greift beim Setzen sofort für alle, die schon drin
  stehen. Durchgesetzt wird beim Betreten, nicht laufend. Ohne per-Welt-Zustand
  (`separateInventories = false`) gibt es keinen gemerkten Modus, den ein Default setzen könnte —
  dort wirkt nur `forced`, worauf der Befehl auch hinweist.
- **Zugangsbeschränkte Welten** (`/wsc world access <welt> [0-4]`) — Mindest-Permission-Level pro
  Welt, Default `0` (alle). Geprüft bei `/ws`, bei Portalen, die in die Welt führen, und beim
  Login: wer sich in einer inzwischen gesperrten Welt einloggt, wird mit seinem Default-Zustand in
  die Standardwelt versetzt (dieselbe Reconcile-Logik wie bei entladenen Welten). Gesperrte Welten
  verschwinden aus der `/ws`-Tab-Completion und aus der `/ws`-Liste für Spieler ohne Zugang.
  `/wsc player tp` umgeht die Sperre bewusst — der Befehl ist ohnehin OP-gated, und jemanden
  hineinzusetzen ist eine bewusste Admin-Entscheidung. Beim Setzen eines Levels werden Spieler, die
  drin stehen und es nicht haben, nach Rückfrage in die Standardwelt geholt.
  ⚠️ Vanilla kennt keinen Befehl, um einzelne Level zu vergeben: `/op` vergibt immer
  `op-permission-level` aus der `server.properties` (Default `4`). Auf einem Server ohne
  Permission-Mod sind praktisch nur `0` und „≥ 1" unterscheidbar.
- **Spielerzustand zwischen Welten übertragen** (`/wsc player state show|copy|move|swap|clear`) —
  pro Spieler, auch offline (der Store ist UUID-basiert). Position und Dimension wandern bewusst
  **nicht** mit; alles andere schon, inklusive des kompletten modded States. Steht der Spieler
  online in der Zielgruppe, wird der neue Zustand sofort angewendet, ohne Relog.

### Changed
- **Cosmetic Armor ist ab sofort pro Welt getrennt** (inkl. der Flags zum Ausblenden der
  Vanilla-Rüstung). Für das alte, globale Verhalten
  `"cosmeticarmorreworked:cosarmor" = false` in `worldswitcher-playerdata.toml` setzen.
- **`/wsc` ist nach Substantiven gruppiert** — `world`, `player`, `group`, `config`; `help`,
  `confirm` und `cancel` bleiben oben. `/wsc help <thema>` zeigt die jeweilige Kategorie.
  **Alle 13 bisherigen Pfade funktionieren weiter** als Aliase (Brigadier-Redirects auf dieselbe
  Implementierung, keine Kopien) und weisen beim Aufruf einmal auf den Ersatz hin.
  Entfernung frühestens in 2.0.0.
- `attachmentExcludes` / `persistentDataExcludes` bleiben als harte „immer global"-Overrides
  bestehen und füllen die neue Datei beim ersten Erzeugen vor. Die `swap*`-Optionen sind jetzt
  Master-Schalter über den Per-Key-Entscheidungen: geswappt wird nur, wenn beide zustimmen.
- Bestätigungspflichtige Aktionen laufen über einen gemeinsamen `/wsc confirm`/`/wsc cancel`-Kanal
  (30 s Timeout), den jetzt auch Gruppenwechsel und Zustands-Transfers nutzen.

### Migration
- `worldswitcher_worlds.dat` bekommt pro Welt ein `inventoryGroup`-Feld. Alte Saves werden beim
  Laden migriert (`shareDefaultInventory = true` → Gruppe `default`); das alte Flag wird weiterhin
  mitgeschrieben, damit ein Downgrade auf 1.4.x die Welt weiter korrekt liest.

## [1.4.0] - 2026-07-19
### Added
- **Command-Hooks** (`enableCommandHooks`, Default an): Admins können in
  `serverconfig/worldswitcher-hooks.json` pro Welt und global Befehlslisten hinterlegen, die
  automatisch bei drei Welt-Events laufen — `firstPlayerJoin` (Welt geht 0 → 1 Spieler),
  `lastPlayerLeave` (1 → 0) und `playerMoved` (ein Spieler wechselt die Welt). In jedem Befehl
  werden `{{worldName}}`, `{{worldId}}`, `{{playerName}}` und `{{playerUuid}}` ersetzt. Welt-Identität
  folgt der World-Switcher-Gruppierung (Overworld/Nether/End = eine Welt `default`, jede verwaltete
  Welt eigen; ein Overworld→Nether-Portal zählt nicht als Wechsel). Globale Hooks laufen vor den
  Welt-Hooks. Jeder Hook läuft wahlweise als Server (`as: server`, OP 4, Ausgabe unterdrückt, an
  der Welt positioniert) oder als auslösender Spieler (`as: player`); ohne Angabe greift
  `hookDefaultRunAs` (Default `server`). Fehlerhafte Befehle werden geloggt und unterbrechen den
  Wechsel/Login/Logout nie. Eine kommentierte Beispiel-Datei wird beim ersten Start erzeugt; mit
  `/wsc hooks reload` lässt sich die Datei live neu laden (`/wsc hooks status` zeigt die Zählung).
- **Klickbare Switch-Ankündigung** (`announceSwitches`, Default an): wechselt ein Spieler die
  Welt (`/ws` oder `/wsc tp`), sehen alle anderen Online-Spieler eine Chat-Meldung „<Spieler>
  switched to <Welt>" — der Weltname ist ein klickbarer `/ws <welt>`-Link, sodass sie mit einem
  Klick nachjoinen können. Über `announceSwitches = false` abschaltbar.
- **Geteiltes Inventar pro Welt** (`/wsc shareinventory <world> [true|false]`): eine Welt kann
  als „keep-inventory" markiert werden — sie nutzt dann die `default`-Inventargruppe statt einer
  eigenen, d. h. Spieler behalten beim Betreten ihre Default-Welt-Items (kein separates
  Welt-Inventar). Nur der Spielerzustand wird geteilt — Gamerules, Zeit, Wetter und Difficulty
  bleiben pro Welt. Umschalten während Spieler drin sind gruppiert sie an Ort und Stelle um (der
  vorherige Zustand bleibt für ein Zurückschalten erhalten; modded Client-HUDs wie Curios können
  bis zum nächsten Relog nachhinken). `/wsc list` markiert solche Welten mit `keep-inv`, `/wsc
  info` zeigt den Status. (Erster Schritt Richtung frei konfigurierbarer Weltgruppen.)
### Changed
- `persistentDataExcludes` ist jetzt standardmäßig leer (die `WaystonesData`-Ausnahme brachte
  in der Praxis nichts — Waystones verhalten sich ohnehin pro Welt).

## [1.2.0] - 2026-07-03
### Added
- **Modded Spielerstatus pro Welt** (Teil von `separateInventories`): drei Mechanismen decken
  alles ab, was Mods am Spieler speichern — ohne Mod-Abhängigkeiten:
  - **NeoForge-Data-Attachments** (`swapModAttachments`, Default an): z. B. **Curios-Slots**
    (Charm/Ring/Necklace/… inkl. Elytra-Slot und getragenem Toolbelt) wechseln pro Welt.
    Ausnahmen über `attachmentExcludes`.
  - **Persistent-Data** (`swapPersistentData`, Default an): Spieler-NBT unter `NeoForgeData`;
    einzelne Keys per `persistentDataExcludes` ausnehmbar (Default: `WaystonesData`).
  - **Tough As Nails** (`swapToughAsNails`, Default an): Durst + Temperatur pro Welt
    (per Reflection über die TAN-API; ohne TAN wirkungslos).
- **Difficulty pro Welt** (`perWorldDifficulty`, Default an): `/difficulty` wirkt in einer
  verwalteten Welt nur auf diese (inkl. Peaceful-Despawn, Mob-Spawnregeln, Regional-Difficulty);
  neu `/wsc difficulty <world> [value]`. Importe übernehmen die Difficulty aus der `level.dat`,
  der Client sieht beim Weltwechsel automatisch den richtigen Wert.
- **Sicherer Relog nach Welt-Entladung**: Wer offline in einer inzwischen entladenen/gelöschten
  Welt war, wird beim Login nicht mehr an den alten Koordinaten in der Overworld abgesetzt
  (Erstickungs-/Sturzgefahr), sondern an die letzte Default-Position bzw. den Spawn teleportiert.
### Fixed
- Modded Spielerdaten (z. B. Curios) wurden beim Weltwechsel bisher gar nicht getrennt und
  „lecken" zwischen Welten — jetzt Teil des Per-Welt-Snapshots (s. o.).

## [1.1.0] - 2026-07-03
### Added
- **Gamerules pro Welt** (`perWorldGameRules`, Default an): jede verwaltete Welt hat ihre eigenen
  Gamerules — `keepInventory`, `mobGriefing`, `randomTickSpeed` usw. wirken nur dort. Neue Welten
  starten mit einer Kopie der globalen Rules, Importe übernehmen die Rules aus ihrer `level.dat`.
  Funktioniert auch mit Mod-Rules (z. B. Serene Seasons' `doSeasonCycle`).
- **Tageszeit + Wetter pro Welt** (`perWorldTimeAndWeather`, Default an): eigene Uhr und eigenes
  Wetter je Welt — ewige Nacht, Dauerregen, eingefrorene Zeit (`doDaylightCycle false`) sind
  jetzt pro Welt möglich. Schlafen überspringt nur die Nacht der eigenen Welt. Importe übernehmen
  die Uhrzeit aus ihrer `level.dat`.
- **Kontextsensitive Vanilla-Commands**: `/gamerule`, `/time` und `/weather` wirken in einer
  verwalteten Welt nur auf diese, in den Vanilla-Dimensionen global wie bisher.
  `/execute in worldswitcher:<id> run …` targetet eine Welt von überall; neu außerdem
  `/wsc gamerule <world> [<rule> [value]]` inkl. Override-Liste (ohne Rule-Argument).
- Die drei client-seitigen Rules (`doImmediateRespawn`, `reducedDebugInfo`, `doLimitedCrafting`)
  werden bei jedem Weltwechsel/Respawn an den Client nachsynchronisiert (Vanilla sendet sie nur
  beim Login).
- Blankes `/ws` zeigt die Weltliste (klickbar, mit „you are here"-Markierung) statt eines
  Brigadier-Usage-Fehlers; blankes `/wsc` bzw. `/wsc help` zeigt eine Aktions-Übersicht.
### Changed
- `swapGamemode` ist jetzt standardmäßig **an**: der Gamemode ist Teil des Per-Welt-Status —
  beim Wechsel wird der zuletzt in der Zielwelt genutzte Modus wiederhergestellt (Erstbesuch
  behält den aktuellen). Bestehende Server behalten ihren Config-Wert.
### Fixed
- `/wsc list` zeigt jetzt auch die `default`-Welt (mit Spielerzahl) und markiert die Welt des
  Aufrufers mit „(you are here)" — vorher wirkte die Liste nach einem Tod (Respawn in der
  Overworld) wie ein kaputter Spieler-Zähler.
- Cross-World-Tod mit unterschiedlichem `keepInventory`: Vanilla droppt nach der Regel der
  Todeswelt, stellt aber nach der Regel der Respawn-Welt wieder her — bei `true`→`false` wären
  Items ersatzlos verschwunden. Die Regel der Todeswelt ist jetzt maßgeblich.

## [1.0.0] - 2026-07-02
### Added
- **`/ws <welt>`**: Weltwechsel zur Laufzeit ohne Server-Neustart (Multiverse-artig). Für alle
  Spieler erlaubt (Permission-Level konfigurierbar), Tab-Completion, `default` = Vanilla-Welten
  (Overworld/Nether/End).
- **`/wsc`-Verwaltung (OP 2+)**: `list` (klickbare Namen), `info` (Seed/Spawn/Ordner/Disk-Size),
  `create <name> [seed]`, `import <ordner> [as <name>]`, `rename`, `load`/`unload`,
  `tp <spieler> <welt>`, `delete` mit Confirm/Cancel-Buttons (30 s Timeout).
- **Welt-Import**: kopiert Weltordner aus `worlds/` (Root oder Subpfade, z. B. `"backups/alt"`)
  in den Server-Save — nur Overworld-Daten (`region`, `entities`, `poi`, `data`), Quelle bleibt
  unangetastet. Seed + Spawn werden aus der `level.dat` gelesen (auch Pre-1.16-Format), Terrain
  generiert hinter der importierten Grenze mit dem Original-Seed weiter. Alte Welten (z. B.
  1.18/1.20) werden beim ersten Chunk-Besuch von Vanilla-DFU aktualisiert.
- **Inventar-Trennung pro Spieler pro Welt** (`separateInventories`, Default an): kompletter
  Spielerstatus — Inventar, Enderchest, XP, Health/Hunger, Effekte, letzte Position. Abgedeckte
  Randfälle: Portale innerhalb der Default-Gruppe (kein Swap), gruppen-übergreifende Portale
  (Swap wie `/ws`, abschaltbar), Tod ohne Bett in der Zielwelt (Post-Death-Status bleibt in der
  Todeswelt), Login-Reconciliation nach Welt-Löschung/-Entladung während man offline drin war.
- **Umbenennen ohne Datenverlust**: Welt-`id` (Dimension-Key + Inventar-Gruppe) ist fix und vom
  umbenennbaren Anzeigenamen getrennt — Inventare, Betten und Ordner überleben jede Umbenennung.
- **Server-side-only**: keine Items/Blöcke/Netzwerk-Payloads — Vanilla-1.21.1-Clients können
  ohne Mod joinen (Custom-Dimension-Key mit Vanilla-Overworld-Dimension-Type). End-to-end mit
  echtem Vanilla-Protokoll-Client (mineflayer) verifiziert: 13/13 Checks.
- **Persistenz**: Welten-Registry (`worldswitcher_worlds.dat`) + Spielerstatus
  (`worldswitcher_playerstate.dat`) als SavedData; registrierte Welten werden beim Server-Start
  automatisch neu geladen (`autoLoadOnStartup`).
