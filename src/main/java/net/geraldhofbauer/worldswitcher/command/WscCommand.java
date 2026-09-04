package net.geraldhofbauer.worldswitcher.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.geraldhofbauer.worldswitcher.Config;
import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.geraldhofbauer.worldswitcher.hooks.CommandHookService;
import net.geraldhofbauer.worldswitcher.hooks.CommandHooksConfig;
import net.geraldhofbauer.worldswitcher.util.Messages;
import net.geraldhofbauer.worldswitcher.world.DynamicDimensionManager;
import net.geraldhofbauer.worldswitcher.world.ImportService;
import net.geraldhofbauer.worldswitcher.world.WorldRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code /wsc <category> <action> [args]} — world management, OP level 2+.
 *
 * <p>The tree is grouped by what an action operates on: {@code world}, {@code player}, {@code group}
 * and {@code config}, with {@code help}, {@code confirm} and {@code cancel} at the top because they
 * belong to no category (the latter two are what the clickable confirmation buttons run).</p>
 *
 * <p>Every command that existed before 1.5.0 is still registered at the top level as a deprecated
 * alias, so nothing that is written down in a server's docs stops working. Running one prints a
 * one-line pointer to its replacement. The aliases are implemented as brigadier redirects into the
 * canonical nodes — never as copies — so there is exactly one implementation of each action; they
 * are scheduled for removal in 2.0.0.</p>
 */
public final class WscCommand {

    /** Old path → new path, for the deprecated top-level aliases. */
    private static final List<String[]> ALIASES = List.of(
            new String[] {"list", "world list"},
            new String[] {"info", "world info"},
            new String[] {"create", "world create"},
            new String[] {"import", "world import"},
            new String[] {"rename", "world rename"},
            new String[] {"load", "world load"},
            new String[] {"unload", "world unload"},
            new String[] {"delete", "world delete"},
            new String[] {"gamerule", "world gamerule"},
            new String[] {"difficulty", "world difficulty"},
            new String[] {"tp", "player tp"},
            new String[] {"hooks", "config hooks"});

    private static final List<String> HELP_TOPICS = List.of("worlds", "players", "groups", "config");

    private WscCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(Commands.literal("wsc")
                .requires(source -> source.hasPermission(2))
                .executes(WscCommand::executeHelp)
                .then(Commands.literal("help")
                        .executes(WscCommand::executeHelp)
                        .then(Commands.argument("topic", StringArgumentType.word())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(HELP_TOPICS, builder))
                                .executes(WscCommand::executeHelpTopic)))
                .then(worldNode())
                .then(playerNode())
                .then(GroupCommands.build())
                .then(configNode())
                .then(Commands.literal("confirm")
                        .executes(context -> PendingConfirmations.confirm(context.getSource())))
                .then(Commands.literal("cancel")
                        .executes(context -> PendingConfirmations.cancel(context.getSource())))
                // shareinventory predates named groups and does not map 1:1 onto one node, so it
                // keeps its own thin handlers instead of being a redirect.
                .then(Commands.literal("shareinventory")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(WorldSuggestions.REGISTERED_WORLDS)
                                .executes(deprecated("/wsc shareinventory",
                                        "/wsc group info", WscCommand::executeShareInventoryQuery))
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(deprecated("/wsc shareinventory <world> <bool>",
                                                "/wsc group set <world> default",
                                                WscCommand::executeShareInventorySet)))))
                .then(E2eTestHook.enabled() ? E2eTestHook.buildDebugNode()
                        : Commands.literal("debug").requires(source -> false)));

        for (String[] alias : ALIASES) {
            addAlias(root, alias[0], alias[1]);
        }
    }

    // ------------------------------------------------------------------ categories

    private static LiteralArgumentBuilder<CommandSourceStack> worldNode() {
        return Commands.literal("world")
                .executes(WscCommand::executeList)
                .then(Commands.literal("list")
                        .executes(WscCommand::executeList))
                .then(Commands.literal("info")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(WorldSuggestions.REGISTERED_WORLDS)
                                .executes(WscCommand::executeInfo)))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> executeCreate(context, null))
                                .then(Commands.argument("seed", LongArgumentType.longArg())
                                        .executes(context ->
                                                executeCreate(context, LongArgumentType.getLong(context, "seed"))))))
                .then(Commands.literal("import")
                        .then(Commands.argument("source", StringArgumentType.string())
                                .suggests(ImportService.IMPORT_CANDIDATES)
                                .executes(context -> executeImport(context, null))
                                .then(Commands.literal("as")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(context -> executeImport(context,
                                                        StringArgumentType.getString(context, "name")))))))
                .then(Commands.literal("rename")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(WorldSuggestions.REGISTERED_WORLDS)
                                .then(Commands.argument("newName", StringArgumentType.word())
                                        .executes(WscCommand::executeRename))))
                .then(Commands.literal("load")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(WorldSuggestions.UNLOADED_WORLDS)
                                .executes(WscCommand::executeLoad)))
                .then(Commands.literal("unload")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(WorldSuggestions.REGISTERED_WORLDS)
                                .executes(WscCommand::executeUnload)))
                .then(Commands.literal("delete")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(WorldSuggestions.REGISTERED_WORLDS)
                                .executes(WscCommand::executeDeleteRequest)))
                .then(GameRuleHelper.buildWscGameruleNode())
                .then(GameRuleHelper.buildWscDifficultyNode())
                .then(WorldPolicyCommands.buildGameModeNode())
                .then(WorldPolicyCommands.buildAccessNode());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> playerNode() {
        return Commands.literal("player")
                .then(Commands.literal("tp")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("world", StringArgumentType.word())
                                        .suggests(WorldSuggestions.SWITCH_TARGETS)
                                        .executes(WscCommand::executeTp))))
                .then(PlayerStateCommands.build());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> configNode() {
        return Commands.literal("config")
                .executes(context -> executeHelpFor(context.getSource(), "config"))
                .then(PlayerDataCommands.build())
                .then(Commands.literal("hooks")
                        .executes(WscCommand::executeHooksStatus)
                        .then(Commands.literal("status")
                                .executes(WscCommand::executeHooksStatus))
                        .then(Commands.literal("reload")
                                .executes(WscCommand::executeHooksReload)));
    }

    // ------------------------------------------------------------------ deprecated aliases

    /**
     * Registers {@code /wsc <alias>} as a redirect into the canonical node, so the old path keeps
     * working with a single implementation behind it. A node can carry both a command and a redirect:
     * the command covers the argument-less form ({@code /wsc list}), the redirect covers everything
     * with arguments, and the redirect modifier is where the deprecation notice is printed.
     */
    private static void addAlias(LiteralCommandNode<CommandSourceStack> root, String alias,
                                 String canonicalPath) {
        CommandNode<CommandSourceStack> target = root;
        for (String segment : canonicalPath.split(" ")) {
            target = target.getChild(segment);
            if (target == null) {
                WorldSwitcherMod.LOGGER.warn("Cannot alias /wsc {} — no such command /wsc {}",
                        alias, canonicalPath);
                return;
            }
        }
        String oldCommand = "/wsc " + alias;
        String newCommand = "/wsc " + canonicalPath;

        LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(alias);
        Command<CommandSourceStack> command = target.getCommand();
        if (command != null) {
            builder.executes(deprecated(oldCommand, newCommand, command));
        }
        if (!target.getChildren().isEmpty()) {
            CommandNode<CommandSourceStack> redirect = target;
            builder.forward(redirect, context -> {
                warnDeprecated(context.getSource(), oldCommand, newCommand);
                return List.of(context.getSource());
            }, false);
        }
        root.addChild(builder.build());
    }

    /** Wraps a handler so it prints the deprecation notice before doing its work. */
    private static Command<CommandSourceStack> deprecated(String oldCommand, String newCommand,
                                                          Command<CommandSourceStack> delegate) {
        return context -> {
            warnDeprecated(context.getSource(), oldCommand, newCommand);
            return delegate.run(context);
        };
    }

    private static void warnDeprecated(CommandSourceStack source, String oldCommand,
                                       String newCommand) {
        source.sendSuccess(() -> Component.literal("⚠ ").withStyle(ChatFormatting.YELLOW)
                .append(Messages.info(oldCommand + " is deprecated — use "))
                .append(Messages.suggestCommand(newCommand, newCommand + " ", ChatFormatting.YELLOW))
                .append(Messages.info(" (the old form is removed in 2.0.0)")), false);
    }

    // ------------------------------------------------------------------ help

    /** Bare {@code /wsc} or {@code /wsc help}: the categories, one line each. */
    private static int executeHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Messages.highlight("World Switcher — /wsc <category> <action>"), false);
        topic(source, "worlds", "world", "create, import, list, load, delete, per-world rules");
        topic(source, "players", "player", "teleport someone, move their stored state between worlds");
        topic(source, "groups", "group", "which worlds share one inventory");
        topic(source, "config", "config", "modded player data, command hooks");
        source.sendSuccess(() -> Messages.info("  confirm / cancel — answer a pending confirmation "
                + "(the [Confirm] and [Cancel] buttons run these)"), false);
        source.sendSuccess(() -> Messages.info("Players switch worlds with /ws <world>."), false);
        return 1;
    }

    private static void topic(CommandSourceStack source, String name, String literal, String summary) {
        source.sendSuccess(() -> Component.literal("  ")
                .append(Messages.runCommand("/wsc " + literal, "/wsc help " + name, ChatFormatting.AQUA))
                .append(Messages.info("  " + summary)), false);
    }

    private static int executeHelpTopic(CommandContext<CommandSourceStack> context) {
        return executeHelpFor(context.getSource(), StringArgumentType.getString(context, "topic"));
    }

    private static int executeHelpFor(CommandSourceStack source, String topic) {
        switch (topic.toLowerCase(Locale.ROOT)) {
            case "worlds", "world" -> {
                source.sendSuccess(() -> Messages.highlight("/wsc world <action>:"), false);
                line(source, "list", "all worlds (clickable)");
                line(source, "info <world>", "seed, spawn, folder, size, inventory group");
                line(source, "create <name> [seed]", "create a fresh world");
                line(source, "import <source> [as <name>]", "copy a world from the worlds folder");
                line(source, "rename <world> <newName>", "rename (inventories survive)");
                line(source, "load/unload <world>", "load or unload at runtime");
                line(source, "delete <world>", "delete world + data (asks to confirm)");
                line(source, "gamerule <world> [<rule> [value]]", "per-world game rules");
                line(source, "difficulty <world> [value]", "per-world difficulty");
                line(source, "gamemode <world> [none|<mode> [forced]]",
                        "game mode this world hands out");
                line(source, "access <world> [0-4]", "minimum permission level to enter");
            }
            case "players", "player" -> {
                source.sendSuccess(() -> Messages.highlight("/wsc player <action>:"), false);
                line(source, "tp <player> <world>", "switch another player");
                line(source, "state show <player>", "which worlds they have stored state in");
                line(source, "state copy <player> <from> <to>", "copy their state to another world");
                line(source, "state move <player> <from> <to>", "copy, then reset the source");
                line(source, "state swap <player> <a> <b>", "exchange two worlds' state");
                line(source, "state clear <player> <world>", "reset one world to a fresh start");
                source.sendSuccess(() -> Messages.info("  Offline players work too. Position never "
                        + "travels with the state."), false);
            }
            case "groups", "group" -> {
                source.sendSuccess(() -> Messages.highlight("/wsc group <action>:"), false);
                line(source, "list", "all inventory groups and their worlds");
                line(source, "info <group>", "worlds in a group, stored player states");
                line(source, "set <world> <group>", "put a world into a group — worlds in one group "
                        + "share a single player state");
                line(source, "unset <world>", "give a world its own group back");
                source.sendSuccess(() -> Messages.info("  The group 'default' is the vanilla "
                        + "dimensions — putting a world there keeps your main items."), false);
            }
            case "config" -> {
                source.sendSuccess(() -> Messages.highlight("/wsc config <action>:"), false);
                line(source, "playerdata [status]", "which modded player data is per world");
                line(source, "playerdata list", "the keys currently configured, and their mode");
                line(source, "playerdata scan", "everything found, with mod names and descriptions");
                line(source, "playerdata set <key> <true|false>", "per world (true) or global (false)");
                line(source, "playerdata write/reload", "regenerate or re-read the config file");
                line(source, "hooks [status|reload]", "command hooks on world events");
            }
            default -> {
                source.sendFailure(Messages.error("Unknown help topic: " + topic
                        + " (try " + String.join(", ", HELP_TOPICS) + ")"));
                return 0;
            }
        }
        return 1;
    }

    private static void line(CommandSourceStack source, String usage, String summary) {
        source.sendSuccess(() -> Messages.info("  " + usage + " — " + summary), false);
    }

    /** {@code /wsc config hooks [status]}: enabled state, default run-as and configured counts. */
    private static int executeHooksStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        CommandHooksConfig config = CommandHookService.config();
        source.sendSuccess(() -> Messages.highlight("Command hooks"), false);
        source.sendSuccess(() -> Messages.info("  enabled: " + Config.enableCommandHooks()), false);
        source.sendSuccess(() -> Messages.info("  default run-as: " + Config.hookDefaultRunAs()), false);
        source.sendSuccess(() -> Messages.info("  global hooks: " + config.globalHookCount()), false);
        source.sendSuccess(() -> Messages.info("  per-world hooks: " + config.perWorldHookCount()
                + " across " + config.worldIds().size() + " world(s)"), false);
        for (String worldId : config.worldIds()) {
            source.sendSuccess(() -> Messages.info("    " + worldId + ": " + config.hookCount(worldId)), false);
        }
        source.sendSuccess(() -> Messages.info("File: serverconfig/worldswitcher-hooks.json "
                + "— edit and /wsc config hooks reload."), false);
        return 1;
    }

    /** {@code /wsc config hooks reload}: reload the JSON file live and report the loaded counts. */
    private static int executeHooksReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        CommandHooksConfig config = CommandHookService.reload(source.getServer());
        source.sendSuccess(() -> Messages.info("Reloaded command hooks: " + config.globalHookCount()
                + " global, " + config.perWorldHookCount() + " per-world across "
                + config.worldIds().size() + " world(s). Check the log for any parse warnings."), true);
        return 1;
    }

    private static WorldRegistry.WorldEntry resolveWorld(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "world");
        WorldRegistry.WorldEntry entry = WorldRegistry.get(context.getSource().getServer()).byName(name);
        if (entry == null) {
            context.getSource().sendFailure(Messages.error("Unknown world: " + name));
        }
        return entry;
    }

    private static int executeList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        var entries = WorldRegistry.get(server).entries();
        String currentGroup = source.getEntity() instanceof ServerPlayer player
                ? WorldRegistry.groupOf(player.level().dimension()) : null;

        source.sendSuccess(() -> Messages.highlight("Worlds (" + (entries.size() + 1) + "):"), false);

        int defaultCount = 0;
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (WorldRegistry.DEFAULT_GROUP.equals(WorldRegistry.groupOf(online.level().dimension()))) {
                defaultCount++;
            }
        }
        var defaultLine = Component.literal("  ")
                .append(Messages.runCommand(WorldRegistry.DEFAULT_GROUP, "/ws default", ChatFormatting.AQUA))
                .append(Component.literal("  loaded").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  " + defaultCount + " player" + (defaultCount == 1 ? "" : "s"))
                        .withStyle(ChatFormatting.GRAY));
        if (WorldRegistry.DEFAULT_GROUP.equals(currentGroup)) {
            defaultLine.append(Component.literal("  (you are here)").withStyle(ChatFormatting.YELLOW));
        }
        source.sendSuccess(() -> defaultLine, false);

        for (WorldRegistry.WorldEntry entry : entries) {
            ServerLevel level = DynamicDimensionManager.getLoadedLevel(server, entry);
            boolean loaded = level != null;
            int playerCount = loaded ? level.players().size() : 0;

            var line = Component.literal("  ")
                    .append(Messages.runCommand(entry.name(), "/ws " + entry.name(), ChatFormatting.AQUA))
                    .append(Component.literal(loaded ? "  loaded" : "  unloaded")
                            .withStyle(loaded ? ChatFormatting.GREEN : ChatFormatting.RED));
            if (loaded) {
                line.append(Component.literal("  " + playerCount + " player" + (playerCount == 1 ? "" : "s"))
                        .withStyle(ChatFormatting.GRAY));
            }
            if (entry.grouped()) {
                line.append(Component.literal("  group: " + entry.inventoryGroup())
                        .withStyle(ChatFormatting.GRAY));
            }
            if (entry.requiredPermissionLevel() > 0) {
                line.append(Component.literal("  \uD83D\uDD12 level " + entry.requiredPermissionLevel())
                        .withStyle(ChatFormatting.GOLD));
            }
            if (entry.defaultGameMode() != null) {
                line.append(Component.literal("  " + entry.defaultGameMode().getName()
                        + (entry.forceGameMode() ? "!" : "")).withStyle(ChatFormatting.GRAY));
            }
            if (entry.id().equals(currentGroup)) {
                line.append(Component.literal("  (you are here)").withStyle(ChatFormatting.YELLOW));
            }
            source.sendSuccess(() -> line, false);
        }
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Messages.info(
                    "  (none — use /wsc world import or /wsc world create)"), false);
        }
        return entries.size() + 1;
    }

    private static int executeInfo(CommandContext<CommandSourceStack> context) {
        WorldRegistry.WorldEntry entry = resolveWorld(context);
        if (entry == null) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerLevel level = DynamicDimensionManager.getLoadedLevel(server, entry);

        Path dimensionPath = DynamicDimensionManager.storageSource(server)
                .getDimensionPath(entry.dimensionKey());
        long diskSize = folderSize(dimensionPath);

        source.sendSuccess(() -> Messages.highlight("World '" + entry.name() + "'"), false);
        source.sendSuccess(() -> Messages.info("  id: " + entry.id()
                + "  dimension: " + entry.dimensionKey().location()), false);
        source.sendSuccess(() -> Messages.info("  status: " + (level != null ? "loaded, "
                + level.players().size() + " players" : "unloaded")), false);
        source.sendSuccess(() -> Messages.info("  seed: " + entry.seed()), false);
        source.sendSuccess(() -> Messages.info("  spawn: "
                + (entry.spawnPos() != null ? entry.spawnPos().toShortString() : "not set")), false);
        source.sendSuccess(() -> Messages.info("  folder: " + dimensionPath + " (" + formatSize(diskSize) + ")"), false);
        source.sendSuccess(() -> Messages.info("  game mode: "
                + WorldPolicyCommands.describeGameMode(entry)), false);
        source.sendSuccess(() -> Messages.info("  access: "
                + WorldPolicyCommands.describeAccess(entry)), false);
        source.sendSuccess(() -> Messages.info("  inventory group: " + entry.inventoryGroup()
                + (entry.sharesDefaultInventory() ? " (players keep their default-world items here)"
                        : entry.grouped() ? " (shared with the other worlds in it)" : " (its own)")),
                false);
        if (!entry.sourcePath().isEmpty()) {
            source.sendSuccess(() -> Messages.info("  imported from: " + entry.sourcePath()), false);
        }
        return 1;
    }

    private static int executeShareInventoryQuery(CommandContext<CommandSourceStack> context) {
        WorldRegistry.WorldEntry entry = resolveWorld(context);
        if (entry == null) {
            return 0;
        }
        context.getSource().sendSuccess(() -> Messages.info("World '" + entry.name()
                + "' inventory group: " + entry.inventoryGroup()), false);
        return 1;
    }

    private static int executeShareInventorySet(CommandContext<CommandSourceStack> context) {
        WorldRegistry.WorldEntry entry = resolveWorld(context);
        if (entry == null) {
            return 0;
        }
        boolean value = BoolArgumentType.getBool(context, "value");
        return GroupCommands.setGroup(context.getSource(), entry,
                value ? WorldRegistry.DEFAULT_GROUP : entry.id());
    }

    private static int executeCreate(CommandContext<CommandSourceStack> context, Long seedArg) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        String name = StringArgumentType.getString(context, "name");

        String id = name.toLowerCase(Locale.ROOT);
        if (!WorldRegistry.ID_PATTERN.matcher(id).matches()) {
            source.sendFailure(Messages.error("Invalid name (allowed: a-z 0-9 _ -, max 32 chars): " + name));
            return 0;
        }
        WorldRegistry registry = WorldRegistry.get(server);
        if (registry.byId(id) != null || registry.nameTaken(name)) {
            source.sendFailure(Messages.error("A world with this name already exists: " + name));
            return 0;
        }

        long seed = seedArg != null ? seedArg : RandomSource.create().nextLong();
        WorldRegistry.WorldEntry entry = new WorldRegistry.WorldEntry(
                id, name, seed, null, 0.0F, false, System.currentTimeMillis(), "");
        registry.put(entry);
        DynamicDimensionManager.getOrCreateLevel(server, entry);

        source.sendSuccess(() -> Messages.success("Created world ")
                .append(Messages.runCommand(name, "/ws " + name, ChatFormatting.AQUA))
                .append(Messages.info(" (seed " + seed + ") — click to switch")), true);
        return 1;
    }

    private static int executeImport(CommandContext<CommandSourceStack> context, String nameArg) {
        CommandSourceStack source = context.getSource();
        String sourcePath = StringArgumentType.getString(context, "source");
        ImportService.importWorld(source, sourcePath, nameArg);
        return 1;
    }

    private static int executeRename(CommandContext<CommandSourceStack> context) {
        WorldRegistry.WorldEntry entry = resolveWorld(context);
        if (entry == null) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        String newName = StringArgumentType.getString(context, "newName");
        WorldRegistry registry = WorldRegistry.get(source.getServer());

        if (registry.nameTaken(newName)) {
            source.sendFailure(Messages.error("A world with this name already exists: " + newName));
            return 0;
        }
        String oldName = entry.name();
        registry.rename(entry.id(), newName);
        source.sendSuccess(() -> Messages.success("Renamed '" + oldName + "' to ")
                .append(Messages.highlight(newName))
                .append(Messages.info(" (id stays '" + entry.id() + "' — inventories and spawns keep working)")), true);
        return 1;
    }

    private static int executeLoad(CommandContext<CommandSourceStack> context) {
        WorldRegistry.WorldEntry entry = resolveWorld(context);
        if (entry == null) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        DynamicDimensionManager.getOrCreateLevel(source.getServer(), entry);
        source.sendSuccess(() -> Messages.success("Loaded world ").append(Messages.highlight(entry.name())), true);
        return 1;
    }

    private static int executeUnload(CommandContext<CommandSourceStack> context) {
        WorldRegistry.WorldEntry entry = resolveWorld(context);
        if (entry == null) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        if (entry.unloaded()) {
            source.sendFailure(Messages.error("World '" + entry.name() + "' is already unloaded."));
            return 0;
        }
        DynamicDimensionManager.unloadLevel(source.getServer(), entry);
        source.sendSuccess(() -> Messages.success("Unloaded world ").append(Messages.highlight(entry.name())), true);
        return 1;
    }

    private static int executeTp(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        String worldName = StringArgumentType.getString(context, "world");
        return WsCommand.switchToWorld(context.getSource(), player, worldName, false);
    }

    private static int executeDeleteRequest(CommandContext<CommandSourceStack> context) {
        WorldRegistry.WorldEntry entry = resolveWorld(context);
        if (entry == null) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        return PendingConfirmations.request(source,
                Messages.error("Delete world '" + entry.name()
                        + "' including ALL its data and stored inventories?"),
                confirming -> {
                    WorldRegistry.WorldEntry current =
                            WorldRegistry.get(confirming.getServer()).byId(entry.id());
                    if (current == null) {
                        confirming.sendFailure(Messages.error("World no longer exists."));
                        return 0;
                    }
                    try {
                        DynamicDimensionManager.deleteWorld(confirming.getServer(), current);
                    } catch (IOException e) {
                        WorldSwitcherMod.LOGGER.error("Failed to delete world '{}'", current.name(), e);
                        confirming.sendFailure(Messages.error("Delete failed: " + e.getMessage()));
                        return 0;
                    }
                    confirming.sendSuccess(() ->
                            Messages.success("World '" + current.name() + "' deleted."), true);
                    return 1;
                });
    }

    private static long folderSize(Path path) {
        if (!Files.exists(path)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        return String.format(Locale.ROOT, "%.1f %sB", bytes / Math.pow(1024, exp), "KMGT".charAt(exp - 1));
    }
}
