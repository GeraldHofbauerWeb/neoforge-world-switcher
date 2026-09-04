package net.geraldhofbauer.worldswitcher.hooks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory representation of {@code serverconfig/worldswitcher-hooks.json}: admin-defined command
 * lists per {@link HookEvent}, both {@code global} and per world.
 *
 * <p>Parsing is intentionally lenient — malformed entries are logged and skipped rather than
 * failing the whole file, and each hook may be written as a bare command string or as a
 * {@code {command, as}} object. When the file is missing a commented example is written and an
 * empty config returned; on a parse error an empty config is returned so callers can keep the
 * previously loaded config.</p>
 */
public final class CommandHooksConfig {

    /** Hooks that fire for every world, keyed by event. */
    private final Map<HookEvent, List<CommandHook>> global;
    /** Hooks that fire for a specific world id, keyed by world id then event. */
    private final Map<String, Map<HookEvent, List<CommandHook>>> perWorld;

    private CommandHooksConfig(Map<HookEvent, List<CommandHook>> global,
                               Map<String, Map<HookEvent, List<CommandHook>>> perWorld) {
        this.global = global;
        this.perWorld = perWorld;
    }

    /** An empty config (no hooks). */
    public static CommandHooksConfig empty() {
        return new CommandHooksConfig(new EnumMap<>(HookEvent.class), new LinkedHashMap<>());
    }

    /**
     * Loads the config from {@code path}, generating a commented example file when it is absent
     * and returning an empty config (logging the cause) on any read/parse error.
     */
    public static CommandHooksConfig load(Path path) {
        if (!Files.exists(path)) {
            writeExample(path);
            return empty();
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                WorldSwitcherMod.LOGGER.warn("worldswitcher-hooks.json: root is not a JSON object — ignoring");
                return empty();
            }
            return parse(root.getAsJsonObject());
        } catch (Exception e) {
            WorldSwitcherMod.LOGGER.error("Failed to read worldswitcher-hooks.json — keeping hooks empty", e);
            return empty();
        }
    }

    private static CommandHooksConfig parse(JsonObject root) {
        Map<HookEvent, List<CommandHook>> global = new EnumMap<>(HookEvent.class);
        if (root.has("global") && root.get("global").isJsonObject()) {
            parseEventMap(root.getAsJsonObject("global"), "global", global);
        }
        Map<String, Map<HookEvent, List<CommandHook>>> perWorld = new LinkedHashMap<>();
        if (root.has("worlds") && root.get("worlds").isJsonObject()) {
            JsonObject worlds = root.getAsJsonObject("worlds");
            for (Map.Entry<String, JsonElement> worldEntry : worlds.entrySet()) {
                String worldId = worldEntry.getKey();
                if (!worldEntry.getValue().isJsonObject()) {
                    WorldSwitcherMod.LOGGER.warn("worldswitcher-hooks.json: world '{}' is not an object — skipping",
                            worldId);
                    continue;
                }
                Map<HookEvent, List<CommandHook>> events = new EnumMap<>(HookEvent.class);
                parseEventMap(worldEntry.getValue().getAsJsonObject(), "worlds." + worldId, events);
                if (!events.isEmpty()) {
                    perWorld.put(worldId, events);
                }
            }
        }
        return new CommandHooksConfig(global, perWorld);
    }

    private static void parseEventMap(JsonObject object, String context, Map<HookEvent, List<CommandHook>> target) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            HookEvent event = HookEvent.byJsonKey(entry.getKey());
            if (event == null) {
                WorldSwitcherMod.LOGGER.warn("worldswitcher-hooks.json: unknown event '{}' in {} — skipping",
                        entry.getKey(), context);
                continue;
            }
            if (!entry.getValue().isJsonArray()) {
                WorldSwitcherMod.LOGGER.warn("worldswitcher-hooks.json: {}.{} must be an array — skipping",
                        context, entry.getKey());
                continue;
            }
            List<CommandHook> hooks = new ArrayList<>();
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                CommandHook hook = parseHook(element, context + "." + entry.getKey());
                if (hook != null) {
                    hooks.add(hook);
                }
            }
            if (!hooks.isEmpty()) {
                target.put(event, hooks);
            }
        }
    }

    @Nullable
    private static CommandHook parseHook(JsonElement element, String context) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return new CommandHook(element.getAsString());
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (!object.has("command") || !object.get("command").isJsonPrimitive()) {
                WorldSwitcherMod.LOGGER.warn("worldswitcher-hooks.json: hook in {} is missing a 'command' — skipping",
                        context);
                return null;
            }
            String command = object.get("command").getAsString();
            HookRunAs as = object.has("as") ? HookRunAs.byName(object.get("as").getAsString()) : null;
            if (object.has("as") && as == null) {
                WorldSwitcherMod.LOGGER.warn("worldswitcher-hooks.json: hook in {} has an invalid 'as' "
                        + "(expected server/player) — using the default", context);
            }
            return new CommandHook(command, as);
        }
        WorldSwitcherMod.LOGGER.warn("worldswitcher-hooks.json: hook in {} must be a string or object — skipping",
                context);
        return null;
    }

    /** Global hooks first, then the world-specific hooks for {@code worldId}, for {@code event}. */
    public List<CommandHook> hooksFor(HookEvent event, String worldId) {
        List<CommandHook> result = new ArrayList<>(global.getOrDefault(event, List.of()));
        Map<HookEvent, List<CommandHook>> worldHooks = perWorld.get(worldId);
        if (worldHooks != null) {
            result.addAll(worldHooks.getOrDefault(event, List.of()));
        }
        return result;
    }

    /** Total number of global hooks across all events. */
    public int globalHookCount() {
        return global.values().stream().mapToInt(List::size).sum();
    }

    /** Total number of per-world hooks across all worlds and events. */
    public int perWorldHookCount() {
        return perWorld.values().stream()
                .flatMap(m -> m.values().stream())
                .mapToInt(List::size)
                .sum();
    }

    /** The configured world ids (those with at least one hook). */
    public java.util.Set<String> worldIds() {
        return perWorld.keySet();
    }

    /** Total number of hooks configured for {@code worldId} across all events (0 if none). */
    public int hookCount(String worldId) {
        Map<HookEvent, List<CommandHook>> worldHooks = perWorld.get(worldId);
        return worldHooks == null ? 0 : worldHooks.values().stream().mapToInt(List::size).sum();
    }

    private static void writeExample(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, EXAMPLE, StandardCharsets.UTF_8);
            WorldSwitcherMod.LOGGER.info("Generated example command-hooks config at {}", path);
        } catch (IOException e) {
            WorldSwitcherMod.LOGGER.error("Could not write example worldswitcher-hooks.json", e);
        }
    }

    /**
     * Commented example written when the file is absent. JSON has no comments, so guidance lives in
     * a "_comment" key (ignored by the parser) and a fully working sample configuration.
     */
    private static final String EXAMPLE = """
            {
              "_comment": [
                "World Switcher command hooks. Runs server commands automatically on world events.",
                "Events: firstPlayerJoin (world 0 -> 1 players), lastPlayerLeave (world 1 -> 0),",
                "playerMoved (a player switches world). World identity uses the World Switcher grouping:",
                "the vanilla overworld/nether/end are one world 'default'; each managed world uses its id.",
                "Variables: {{worldName}}, {{worldId}}, {{playerName}}, {{playerUuid}}.",
                "Each hook is either a plain command string, or { \\"command\\": \\"...\\", \\"as\\": \\"server|player\\" }.",
                "'as' overrides the global hookDefaultRunAs config option (server = OP 4, player = own perms).",
                "'global' hooks run for every world, before the matching per-world hooks.",
                "Reload after editing with /wsc config hooks reload. This example is inert until you edit it."
              ],
              "global": {
              },
              "worlds": {
                "creative": {
                  "firstPlayerJoin": [
                    "say The creative world just opened up!"
                  ],
                  "playerMoved": [
                    { "command": "gamemode creative {{playerName}}", "as": "server" }
                  ],
                  "lastPlayerLeave": [
                    "weather clear"
                  ]
                },
                "default": {
                  "playerMoved": [
                    { "command": "gamemode survival {{playerName}}", "as": "server" }
                  ]
                }
              }
            }
            """;
}
