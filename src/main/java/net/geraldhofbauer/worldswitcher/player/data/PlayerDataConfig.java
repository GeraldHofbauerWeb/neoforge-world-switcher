package net.geraldhofbauer.worldswitcher.player.data;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key control over which modded player data is kept per world and which stays global, backed by
 * a generated, commented {@code <world-save>/serverconfig/worldswitcher-playerdata.toml}.
 *
 * <p>The file is written by the mod, not hand-authored from scratch: {@link PlayerDataCatalog}
 * discovers everything present on this server and every key is emitted with the owning mod's name
 * and a description above it. Regenerating never loses an admin's decisions — only the comments are
 * refreshed and new keys appended.</p>
 *
 * <p>Anything not mentioned in the file defaults to {@code true} (per world), which is the behaviour
 * World Switcher had before this file existed. The coarse master switches in
 * {@code worldswitcher-server.toml} still apply on top, as do the legacy exclude lists.</p>
 */
public final class PlayerDataConfig {

    private static final String CONFIG_FILE = "serverconfig/worldswitcher-playerdata.toml";
    private static final String LINE = "# ============================================================";

    /** key → per-world. Empty until a server starts; absent keys are per-world by default. */
    private static final Map<String, Boolean> MODES = new ConcurrentHashMap<>();

    private PlayerDataConfig() {
    }

    public static Path configPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(CONFIG_FILE);
    }

    /**
     * Reads the file on server start, generating it from the catalog when missing and topping it up
     * with keys discovered since it was written. Never throws — a broken file falls back to defaults.
     */
    public static void load(MinecraftServer server) {
        Path path = configPath(server);
        MODES.clear();
        if (Files.exists(path)) {
            MODES.putAll(read(path));
        } else {
            MODES.putAll(seedFromLegacyExcludes());
        }
        write(server);
    }

    /** Re-reads the file without regenerating it — {@code /wsc config playerdata reload}. */
    public static void reload(MinecraftServer server) {
        Path path = configPath(server);
        if (!Files.exists(path)) {
            load(server);
            return;
        }
        Map<String, Boolean> parsed = read(path);
        MODES.clear();
        MODES.putAll(parsed);
    }

    /** True when this key is part of the per-world player state. Unknown keys are per-world. */
    public static boolean isPerWorld(String key) {
        return MODES.getOrDefault(key, Boolean.TRUE);
    }

    /** Everything currently configured, sorted by key. */
    public static Map<String, Boolean> modes() {
        return Map.copyOf(MODES);
    }

    /** How many configured keys are set to global. */
    public static long globalCount() {
        return MODES.values().stream().filter(perWorld -> !perWorld).count();
    }

    /** Changes one key and rewrites the file. */
    public static void set(MinecraftServer server, String key, boolean perWorld) {
        MODES.put(key, perWorld);
        write(server);
    }

    /**
     * Regenerates the file from the catalog, keeping every existing decision and refreshing the
     * comments. Keys that are configured but no longer present on the server are preserved in a
     * trailing section so removing a mod temporarily does not silently reset its setting.
     */
    public static void write(MinecraftServer server) {
        List<PlayerDataKey> discovered = PlayerDataCatalog.scan(server);
        StringBuilder out = new StringBuilder();
        appendHeader(out);

        List<String> written = new ArrayList<>();
        for (PlayerDataSource source : PlayerDataSource.values()) {
            List<PlayerDataKey> section = discovered.stream()
                    .filter(key -> key.source() == source)
                    .toList();
            appendSectionHeader(out, source);
            if (section.isEmpty()) {
                out.append("# (none found on this server)\n");
                continue;
            }
            // Attachments are a global registry with no notion of what they attach to, so it also
            // lists ones that belong to blocks, items or other entities. Keeping the ones actually
            // seen on a player at the top keeps the file readable.
            boolean split = source == PlayerDataSource.ATTACHMENT
                    && section.stream().anyMatch(key -> !key.seen());
            for (PlayerDataKey key : section.stream().filter(PlayerDataKey::seen).toList()) {
                appendEntry(out, key);
                written.add(key.id());
            }
            if (split) {
                out.append("\n# ---- never seen on a player ----\n")
                        .append("# Registered by a mod, but no player here has any data in it. Many of\n")
                        .append("# these attach to blocks, items or other entities and will never show\n")
                        .append("# up on a player at all — they are listed only for completeness.\n");
            }
            for (PlayerDataKey key : section.stream().filter(key -> !key.seen()).toList()) {
                appendEntry(out, key);
                written.add(key.id());
            }
        }

        Map<String, Boolean> orphans = new LinkedHashMap<>();
        MODES.forEach((key, perWorld) -> {
            if (!written.contains(key)) {
                orphans.put(key, perWorld);
            }
        });
        if (!orphans.isEmpty()) {
            out.append('\n').append(LINE).append("\n#  Not present on this server right now\n")
                    .append("#  (kept so the setting survives removing and re-adding a mod)\n")
                    .append(LINE).append('\n');
            orphans.forEach((key, perWorld) ->
                    out.append('\n').append(quote(key)).append(" = ").append(perWorld).append('\n'));
        }

        try {
            Path path = configPath(server);
            Files.createDirectories(path.getParent());
            Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            WorldSwitcherMod.LOGGER.error("Could not write the player-data config", e);
        }
    }

    // ------------------------------------------------------------------ rendering

    private static void appendHeader(StringBuilder out) {
        out.append(LINE).append('\n')
                .append("#  World Switcher — per-world modded player data\n")
                .append(LINE).append('\n')
                .append("#\n")
                .append("#  Every piece of modded player data found on this server, one entry each:\n")
                .append("#\n")
                .append("#      true  = kept per world group — every group has its own copy\n")
                .append("#      false = global — one copy shared by all worlds\n")
                .append("#\n")
                .append("#  This file is generated. Your true/false choices are always preserved;\n")
                .append("#  regenerating only refreshes the comments and appends newly found keys.\n")
                .append("#  Keys that are not listed default to true.\n")
                .append("#\n")
                .append("#      /wsc config playerdata scan             what was found, and why\n")
                .append("#      /wsc config playerdata set <key> <bool> change one entry live\n")
                .append("#      /wsc config playerdata reload           re-read this file\n")
                .append("#      /wsc config playerdata write            refresh the comments\n")
                .append("#\n")
                .append("#  The master switches in worldswitcher-server.toml (swapModAttachments,\n")
                .append("#  swapPersistentData, swapToughAsNails) still apply on top — a key is only\n")
                .append("#  swapped when both agree. The older attachmentExcludes and\n")
                .append("#  persistentDataExcludes lists keep working as hard 'always global'\n")
                .append("#  overrides, but this file is the better place for new decisions.\n")
                .append("#\n");
    }

    private static void appendSectionHeader(StringBuilder out, PlayerDataSource source) {
        out.append('\n').append(LINE).append('\n')
                .append("#  ").append(source.title()).append('\n');
        switch (source) {
            case ATTACHMENT -> out.append("#  Registered by mods and stored inside the player's NBT. "
                    + "This is how\n#  Curios and most modern mods keep their data.\n");
            case PERSISTENT -> out.append("#  Top-level keys of the player's persistent NBT tag. Only "
                    + "keys that were\n#  actually seen on a player or in a stored snapshot can be "
                    + "listed here.\n");
            case BRIDGE -> out.append("#  Mods that keep player data in their own storage, reachable "
                    + "only through a\n#  dedicated World Switcher integration.\n");
            default -> { }
        }
        out.append(LINE).append('\n');
    }

    private static void appendEntry(StringBuilder out, PlayerDataKey key) {
        out.append('\n');
        out.append("# ").append(PlayerDataCatalog.modNameFor(key)).append('\n');
        for (String line : wrap(key.description(), 74)) {
            out.append("#   ").append(line).append('\n');
        }
        if (!key.usable()) {
            out.append("#   (not swappable: ")
                    .append(key.source() == PlayerDataSource.BRIDGE
                            ? "the mod is not installed" : "this attachment is not saved to disk")
                    .append(" — this entry has no effect)\n");
        }
        out.append(quote(key.id())).append(" = ").append(isPerWorld(key.id())).append('\n');
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * Keys are always quoted: attachment ids contain a colon, and both they and persistent-NBT keys
     * may contain dots, which TOML would otherwise read as a path into a sub-table.
     */
    private static String quote(String key) {
        return '"' + key.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    // ------------------------------------------------------------------ parsing

    private static Map<String, Boolean> read(Path path) {
        Map<String, Boolean> parsed = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Config config = TomlFormat.instance().createParser().parse(reader);
            flatten(config, "", parsed);
        } catch (IOException | RuntimeException e) {
            WorldSwitcherMod.LOGGER.error("Could not read {} — falling back to per-world for every "
                    + "key. Fix the file and run /wsc config playerdata reload.", path, e);
            return Map.of();
        }
        return parsed;
    }

    /**
     * Rebuilds dotted keys a user may have written unquoted: TOML turns {@code a.b = true} into a
     * sub-table, so the path segments are joined back together.
     */
    private static void flatten(UnmodifiableConfig config, String prefix, Map<String, Boolean> out) {
        for (UnmodifiableConfig.Entry entry : config.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof UnmodifiableConfig nested) {
                flatten(nested, key, out);
            } else if (value instanceof Boolean bool) {
                out.put(key, bool);
            } else {
                WorldSwitcherMod.LOGGER.warn("Ignoring '{}' in the player-data config: expected "
                        + "true or false, got {}", key, value);
            }
        }
    }

    /** First generation inherits the two legacy exclude lists as "global". */
    private static Map<String, Boolean> seedFromLegacyExcludes() {
        Map<String, Boolean> seeded = new LinkedHashMap<>();
        for (String excluded : net.geraldhofbauer.worldswitcher.Config.attachmentExcludes()) {
            seeded.put(excluded, Boolean.FALSE);
        }
        for (String excluded : net.geraldhofbauer.worldswitcher.Config.persistentDataExcludes()) {
            seeded.put(excluded, Boolean.FALSE);
        }
        if (!seeded.isEmpty()) {
            WorldSwitcherMod.LOGGER.info("Seeding the new player-data config with {} key(s) from the "
                    + "existing attachmentExcludes/persistentDataExcludes lists", seeded.size());
        }
        return seeded;
    }

    /** Used by the status command. */
    @Nullable
    public static Boolean configured(String key) {
        return MODES.get(key);
    }
}
