package net.geraldhofbauer.worldswitcher;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue SEPARATE_INVENTORIES = BUILDER
            .comment("Keep a separate player state (inventory, ender chest, XP, health, hunger,",
                    "effects, last position) per world group. The vanilla dimensions",
                    "(overworld/nether/end) count as one group 'default'.")
            .define("separateInventories", true);

    private static final ModConfigSpec.IntValue WS_PERMISSION_LEVEL = BUILDER
            .comment("Permission level required to use /ws (0 = all players).",
                    "/wsc always requires permission level 2 (OP).")
            .defineInRange("wsPermissionLevel", 0, 0, 4);

    private static final ModConfigSpec.ConfigValue<String> WORLDS_FOLDER = BUILDER
            .comment("Folder containing importable world saves, relative to the server root",
                    "(absolute paths allowed). /wsc world import scans this folder including subpaths.")
            .define("worldsFolder", "worlds");

    private static final ModConfigSpec.BooleanValue AUTO_LOAD_ON_STARTUP = BUILDER
            .comment("Recreate all registered (not explicitly unloaded) worlds at server startup.")
            .define("autoLoadOnStartup", true);

    private static final ModConfigSpec.BooleanValue RESTORE_LAST_POSITION = BUILDER
            .comment("/ws returns players to their last position in the target world",
                    "(otherwise always the world spawn).")
            .define("restoreLastPosition", true);

    private static final ModConfigSpec.BooleanValue SWAP_GAMEMODE = BUILDER
            .comment("Include the game mode in the per-world player state: switching worlds",
                    "restores the mode you last had there (first visit keeps the current one).")
            .define("swapGamemode", true);

    private static final ModConfigSpec.BooleanValue HANDLE_PORTAL_GROUP_CHANGES = BUILDER
            .comment("If a portal or another mod teleports a player across world groups",
                    "(e.g. a nether portal inside an imported world), swap the player state",
                    "like /ws would. If false, such teleports are cancelled.")
            .define("handlePortalGroupChanges", true);

    private static final ModConfigSpec.BooleanValue IMPORT_COPY_ASYNC = BUILDER
            .comment("Copy imported world folders on a background thread (the world is",
                    "registered on the server thread once the copy finishes).")
            .define("importCopyAsync", true);

    private static final ModConfigSpec.BooleanValue SWAP_MOD_ATTACHMENTS = BUILDER
            .comment("Master switch for modded player data stored as NeoForge data attachments",
                    "(e.g. Curios slots 'curios:inventory'). Which individual attachments take",
                    "part is decided per key in serverconfig/worldswitcher-playerdata.toml —",
                    "this switch turns the whole mechanism off. Only active when",
                    "separateInventories is on.")
            .define("swapModAttachments", true);

    private static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> ATTACHMENT_EXCLUDES = BUILDER
            .comment("Legacy override: attachment ids that always stay global, no matter what",
                    "worldswitcher-playerdata.toml says, e.g. [\"carryon:carry_on_data\"]. Kept for",
                    "compatibility — prefer setting the key to false in that file instead. Entries",
                    "here seed it as global the first time it is generated.")
            .defineListAllowEmpty("attachmentExcludes", java.util.List.of(), () -> "",
                    o -> o instanceof String s && net.minecraft.resources.ResourceLocation.tryParse(s) != null);

    private static final ModConfigSpec.BooleanValue SWAP_PERSISTENT_DATA = BUILDER
            .comment("Master switch for the player's persistent NBT (getPersistentData /",
                    "'NeoForgeData', used e.g. by Waystones and Quark). Individual top-level keys",
                    "are decided in serverconfig/worldswitcher-playerdata.toml.")
            .define("swapPersistentData", true);

    private static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> PERSISTENT_DATA_EXCLUDES = BUILDER
            .comment("Legacy override: persistent-data keys that always stay global, no matter",
                    "what worldswitcher-playerdata.toml says, e.g. [\"WaystonesData\"]. Kept for",
                    "compatibility — prefer setting the key to false in that file instead. Entries",
                    "here seed it as global the first time it is generated.")
            .defineListAllowEmpty("persistentDataExcludes", java.util.List.of(), () -> "",
                    o -> o instanceof String);

    private static final ModConfigSpec.BooleanValue SWAP_TOUGH_AS_NAILS = BUILDER
            .comment("Master switch for Tough As Nails thirst and temperature in the per-world",
                    "state (no effect when TAN is not installed). Can also be turned off per key",
                    "as 'toughasnails:player_data' in worldswitcher-playerdata.toml.")
            .define("swapToughAsNails", true);

    private static final ModConfigSpec.BooleanValue PER_WORLD_DIFFICULTY = BUILDER
            .comment("Each managed world keeps its own difficulty. /difficulty executed inside a",
                    "managed world changes only that world; in the vanilla dimensions it stays",
                    "global. If false, all worlds share the global difficulty.")
            .define("perWorldDifficulty", true);

    private static final ModConfigSpec.BooleanValue PER_WORLD_GAME_RULES = BUILDER
            .comment("Each managed world keeps its own game rules. /gamerule executed inside a",
                    "managed world changes only that world; in the vanilla dimensions it stays",
                    "global. If false, all worlds share the overworld's rules.")
            .define("perWorldGameRules", true);

    private static final ModConfigSpec.BooleanValue PER_WORLD_TIME_AND_WEATHER = BUILDER
            .comment("Each managed world keeps its own day time and weather. /time and /weather",
                    "executed inside a managed world change only that world. If false, time and",
                    "weather are inherited from the overworld (like the vanilla nether/end).")
            .define("perWorldTimeAndWeather", true);

    private static final ModConfigSpec.BooleanValue ANNOUNCE_SWITCHES = BUILDER
            .comment("Broadcast a clickable chat message to all other online players when someone",
                    "switches worlds, so they can click the world name to follow.")
            .define("announceSwitches", true);

    private static final ModConfigSpec.BooleanValue ENABLE_COMMAND_HOOKS = BUILDER
            .comment("Master toggle for the command-hook system. When on, admin-defined commands in",
                    "serverconfig/worldswitcher-hooks.json run automatically on world events",
                    "(first player joins a world, last player leaves, a player switches world).")
            .define("enableCommandHooks", true);

    private static final ModConfigSpec.ConfigValue<String> HOOK_DEFAULT_RUN_AS = BUILDER
            .comment("Default run-as for hooks that don't set their own 'as': 'server' runs at OP",
                    "level 4 with output suppressed and positioned at the world, 'player' runs as",
                    "the triggering player (bound by their own permission level).")
            .defineInList("hookDefaultRunAs", "server", java.util.Arrays.asList("server", "player"));

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    public static boolean separateInventories() {
        return SEPARATE_INVENTORIES.get();
    }

    public static int wsPermissionLevel() {
        return WS_PERMISSION_LEVEL.get();
    }

    public static String worldsFolder() {
        return WORLDS_FOLDER.get();
    }

    public static boolean autoLoadOnStartup() {
        return AUTO_LOAD_ON_STARTUP.get();
    }

    public static boolean restoreLastPosition() {
        return RESTORE_LAST_POSITION.get();
    }

    public static boolean swapGamemode() {
        return SWAP_GAMEMODE.get();
    }

    public static boolean handlePortalGroupChanges() {
        return HANDLE_PORTAL_GROUP_CHANGES.get();
    }

    public static boolean importCopyAsync() {
        return IMPORT_COPY_ASYNC.get();
    }

    public static boolean perWorldGameRules() {
        return PER_WORLD_GAME_RULES.get();
    }

    public static boolean perWorldTimeAndWeather() {
        return PER_WORLD_TIME_AND_WEATHER.get();
    }

    public static boolean perWorldDifficulty() {
        return PER_WORLD_DIFFICULTY.get();
    }

    public static boolean swapModAttachments() {
        return SWAP_MOD_ATTACHMENTS.get();
    }

    public static java.util.List<? extends String> attachmentExcludes() {
        return ATTACHMENT_EXCLUDES.get();
    }

    public static boolean swapPersistentData() {
        return SWAP_PERSISTENT_DATA.get();
    }

    public static java.util.List<? extends String> persistentDataExcludes() {
        return PERSISTENT_DATA_EXCLUDES.get();
    }

    public static boolean swapToughAsNails() {
        return SWAP_TOUGH_AS_NAILS.get();
    }

    public static boolean announceSwitches() {
        return ANNOUNCE_SWITCHES.get();
    }

    public static boolean enableCommandHooks() {
        return ENABLE_COMMAND_HOOKS.get();
    }

    public static String hookDefaultRunAs() {
        return HOOK_DEFAULT_RUN_AS.get();
    }
}
