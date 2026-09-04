package net.geraldhofbauer.worldswitcher.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.geraldhofbauer.worldswitcher.Config;
import net.geraldhofbauer.worldswitcher.util.Messages;
import net.geraldhofbauer.worldswitcher.world.DynamicDimensionManager;
import net.geraldhofbauer.worldswitcher.world.DynamicServerLevel;
import net.geraldhofbauer.worldswitcher.world.PerWorldLevelData;
import net.geraldhofbauer.worldswitcher.world.WorldRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-world game rules, time and weather command layer.
 *
 * <p>Re-registers the vanilla {@code /gamerule}, {@code /time} and {@code /weather} literals;
 * Brigadier merges nodes of the same name and replaces their executors. The replacements are
 * context-sensitive ("hybrid"): executed inside a managed world they act on that world only,
 * everywhere else they keep exact vanilla semantics. {@code /execute in worldswitcher:<id> run
 * …} therefore targets a specific world from anywhere. Config is intentionally only read inside
 * the executors — server config is not loaded yet when commands register.</p>
 *
 * <p>Three rules are mirrored on the client but only transmitted in the login packet
 * ({@code doImmediateRespawn}, {@code doLimitedCrafting}, {@code reducedDebugInfo}); their
 * vanilla change-callbacks broadcast to every player regardless of dimension. Per-world writes
 * therefore bypass the callback and {@link #syncClientRules} re-sends the current level's flags
 * to exactly the affected players (also called on every dimension change and respawn).</p>
 */
public final class GameRuleHelper {

    private GameRuleHelper() {
    }

    public static boolean isManaged(ServerLevel level) {
        return WorldRegistry.NAMESPACE.equals(level.dimension().location().getNamespace());
    }

    /** The source's level if it is a managed world (and the feature is enabled), else null. */
    @Nullable
    private static ServerLevel managedLevel(CommandSourceStack source, boolean enabled) {
        ServerLevel level = source.getLevel();
        return enabled && isManaged(level) ? level : null;
    }

    private static String worldName(ServerLevel level) {
        String group = WorldRegistry.groupOf(level.dimension());
        WorldRegistry.WorldEntry entry = WorldRegistry.get(level.getServer()).byId(group);
        return entry != null ? entry.name() : group;
    }

    // ------------------------------------------------------------------ registration

    public static void registerOverrides(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerGameRule(dispatcher);
        registerTime(dispatcher);
        registerWeather(dispatcher);
        registerDifficulty(dispatcher);
    }

    private static void registerGameRule(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> gamerule = Commands.literal("gamerule")
                .requires(source -> source.hasPermission(2));
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                gamerule.then(Commands.literal(key.getId())
                        .executes(context -> queryRule(context.getSource(), key))
                        .then(type.createArgument("value")
                                .executes(context -> setRule(context, key))));
            }
        });
        dispatcher.register(gamerule);
    }

    private static void registerTime(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("time")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("set")
                        .then(Commands.literal("day").executes(context -> setTime(context.getSource(), 1000)))
                        .then(Commands.literal("noon").executes(context -> setTime(context.getSource(), 6000)))
                        .then(Commands.literal("night").executes(context -> setTime(context.getSource(), 13000)))
                        .then(Commands.literal("midnight").executes(context -> setTime(context.getSource(), 18000)))
                        .then(Commands.argument("time", TimeArgument.time())
                                .executes(context -> setTime(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "time")))))
                .then(Commands.literal("add")
                        .then(Commands.argument("time", TimeArgument.time())
                                .executes(context -> addTime(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "time"))))));
    }

    private static void registerWeather(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> weather = Commands.literal("weather")
                .requires(source -> source.hasPermission(2));
        weather.then(weatherKind("clear"));
        weather.then(weatherKind("rain"));
        weather.then(weatherKind("thunder"));
        dispatcher.register(weather);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> weatherKind(String kind) {
        return Commands.literal(kind)
                .executes(context -> setWeather(context.getSource(), kind, -1))
                .then(Commands.argument("duration", TimeArgument.time(1))
                        .executes(context -> setWeather(context.getSource(), kind,
                                IntegerArgumentType.getInteger(context, "duration"))));
    }

    // ------------------------------------------------------------------ /gamerule

    private static <T extends GameRules.Value<T>> int setRule(CommandContext<CommandSourceStack> context,
                                                              GameRules.Key<T> key) {
        CommandSourceStack source = context.getSource();
        ServerLevel managed = managedLevel(source, Config.perWorldGameRules());
        if (managed == null) {
            // Vanilla path including the rule's callback (e.g. spawn chunk reload); afterwards
            // restore the correct per-world flags on clients the global broadcast overwrote.
            T rule = source.getServer().getGameRules().getRule(key);
            rule.setFromArgument(context, "value");
            source.sendSuccess(() -> Component.translatable("commands.gamerule.set",
                    key.getId(), rule.toString()), true);
            resyncAfterGlobalChange(source.getServer(), key);
            return rule.getCommandResult();
        }
        T rule = managed.getGameRules().getRule(key);
        applyWithoutCallback(rule, context);
        afterWorldRuleChange(managed, key);
        String world = worldName(managed);
        source.sendSuccess(() -> Component.translatable("commands.gamerule.set", key.getId(), rule.toString())
                .append(Messages.info(" (world '" + world + "')")), true);
        return rule.getCommandResult();
    }

    private static <T extends GameRules.Value<T>> int queryRule(CommandSourceStack source, GameRules.Key<T> key) {
        ServerLevel managed = managedLevel(source, Config.perWorldGameRules());
        GameRules rules = managed != null ? managed.getGameRules() : source.getServer().getGameRules();
        T rule = rules.getRule(key);
        MutableComponent message = Component.translatable("commands.gamerule.query", key.getId(), rule.toString());
        if (managed != null) {
            message.append(Messages.info(" (world '" + worldName(managed) + "')"));
        }
        source.sendSuccess(() -> message, false);
        return rule.getCommandResult();
    }

    /**
     * Rule callbacks only take the server and act globally (broadcasts, spawn chunk reload) —
     * wrong for a per-world instance, so bypass them. Vanilla only has boolean/int values;
     * exotic modded types fall back to the callback-firing path.
     */
    private static <T extends GameRules.Value<T>> void applyWithoutCallback(
            T rule, CommandContext<CommandSourceStack> context) {
        if (rule instanceof GameRules.BooleanValue booleanValue) {
            booleanValue.set(BoolArgumentType.getBool(context, "value"), null);
        } else if (rule instanceof GameRules.IntegerValue integerValue) {
            integerValue.set(IntegerArgumentType.getInteger(context, "value"), null);
        } else {
            rule.setFromArgument(context, "value");
        }
    }

    private static void afterWorldRuleChange(ServerLevel level, GameRules.Key<?> key) {
        WorldRegistry.get(level.getServer()).setGameRules(
                WorldRegistry.groupOf(level.dimension()), level.getGameRules().createTag());
        if (isClientSyncedRule(key)) {
            for (ServerPlayer player : level.players()) {
                syncClientRules(player);
            }
        }
    }

    private static void resyncAfterGlobalChange(MinecraftServer server, GameRules.Key<?> key) {
        if (!Config.perWorldGameRules() || !isClientSyncedRule(key)) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.serverLevel().getGameRules() != server.getGameRules()) {
                syncClientRules(player);
            }
        }
    }

    private static boolean isClientSyncedRule(GameRules.Key<?> key) {
        return key.equals(GameRules.RULE_DO_IMMEDIATE_RESPAWN)
                || key.equals(GameRules.RULE_LIMITED_CRAFTING)
                || key.equals(GameRules.RULE_REDUCEDDEBUGINFO);
    }

    /**
     * Sends the three client-mirrored rule flags for the player's current level. Vanilla only
     * transmits them at login, so this runs after every dimension change and respawn.
     */
    public static void syncClientRules(ServerPlayer player) {
        if (!Config.perWorldGameRules()) {
            return;
        }
        GameRules rules = player.serverLevel().getGameRules();
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.IMMEDIATE_RESPAWN,
                rules.getBoolean(GameRules.RULE_DO_IMMEDIATE_RESPAWN) ? 1.0F : 0.0F));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.LIMITED_CRAFTING,
                rules.getBoolean(GameRules.RULE_LIMITED_CRAFTING) ? 1.0F : 0.0F));
        player.connection.send(new ClientboundEntityEventPacket(player,
                rules.getBoolean(GameRules.RULE_REDUCEDDEBUGINFO) ? (byte) 22 : (byte) 23));
    }

    // ------------------------------------------------------------------ /time

    private static int setTime(CommandSourceStack source, int time) {
        boolean perWorld = Config.perWorldTimeAndWeather();
        ServerLevel managed = managedLevel(source, perWorld);
        if (managed != null) {
            managed.setDayTime(time);
        } else {
            for (ServerLevel level : source.getServer().getAllLevels()) {
                // Vanilla loops all levels; skip managed ones so the global command does not
                // overwrite their own clocks (their setter is a no-op when the feature is off).
                if (!(perWorld && isManaged(level))) {
                    level.setDayTime(time);
                }
            }
        }
        source.sendSuccess(() -> Component.translatable("commands.time.set", time), true);
        return (int) (source.getLevel().getDayTime() % 24000L);
    }

    private static int addTime(CommandSourceStack source, int amount) {
        boolean perWorld = Config.perWorldTimeAndWeather();
        ServerLevel managed = managedLevel(source, perWorld);
        if (managed != null) {
            managed.setDayTime(managed.getDayTime() + amount);
        } else {
            for (ServerLevel level : source.getServer().getAllLevels()) {
                if (!(perWorld && isManaged(level))) {
                    level.setDayTime(level.getDayTime() + amount);
                }
            }
        }
        int result = (int) (source.getLevel().getDayTime() % 24000L);
        source.sendSuccess(() -> Component.translatable("commands.time.set", result), true);
        return result;
    }

    // ------------------------------------------------------------------ /weather

    private static int setWeather(CommandSourceStack source, String kind, int duration) {
        ServerLevel managed = managedLevel(source, Config.perWorldTimeAndWeather());
        ServerLevel target = managed != null ? managed : source.getServer().overworld();
        String translationKey;
        switch (kind) {
            case "rain" -> {
                target.setWeatherParameters(0, resolveDuration(target, duration, ServerLevel.RAIN_DURATION),
                        true, false);
                translationKey = "commands.weather.set.rain";
            }
            case "thunder" -> {
                target.setWeatherParameters(0, resolveDuration(target, duration, ServerLevel.THUNDER_DURATION),
                        true, true);
                translationKey = "commands.weather.set.thunder";
            }
            default -> {
                target.setWeatherParameters(resolveDuration(target, duration, ServerLevel.RAIN_DELAY), 0,
                        false, false);
                translationKey = "commands.weather.set.clear";
            }
        }
        MutableComponent message = Component.translatable(translationKey);
        if (managed != null) {
            message.append(Messages.info(" (world '" + worldName(managed) + "')"));
        }
        source.sendSuccess(() -> message, true);
        return duration;
    }

    private static int resolveDuration(ServerLevel level, int time, IntProvider provider) {
        return time == -1 ? provider.sample(level.getRandom()) : time;
    }

    // ------------------------------------------------------------------ /difficulty

    private static void registerDifficulty(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> difficulty = Commands.literal("difficulty")
                .requires(source -> source.hasPermission(2))
                .executes(context -> queryDifficulty(context.getSource()));
        for (Difficulty value : Difficulty.values()) {
            difficulty.then(Commands.literal(value.getKey())
                    .executes(context -> setDifficulty(context.getSource(), value)));
        }
        dispatcher.register(difficulty);
    }

    private static int queryDifficulty(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Difficulty difficulty = level.getDifficulty();
        MutableComponent message = Component.translatable("commands.difficulty.query",
                difficulty.getDisplayName());
        if (Config.perWorldDifficulty() && isManaged(level)) {
            message.append(Messages.info(" (world '" + worldName(level) + "')"));
        }
        source.sendSuccess(() -> message, false);
        return difficulty.getId();
    }

    private static int setDifficulty(CommandSourceStack source, Difficulty difficulty) {
        ServerLevel managed = managedLevel(source, Config.perWorldDifficulty());
        if (managed != null) {
            return setWorldDifficulty(source, managed, difficulty);
        }
        MinecraftServer server = source.getServer();
        if (server.getWorldData().getDifficulty() == difficulty) {
            source.sendFailure(Component.translatable("commands.difficulty.failure", difficulty.getKey()));
            return 0;
        }
        server.setDifficulty(difficulty, true);
        // Vanilla just clobbered every level's spawn flags with the global difficulty —
        // restore the managed worlds' own settings. (The difficulty packets are fine: vanilla
        // sends each player the value of their own level.)
        reapplyManagedSpawnFlags(server);
        source.sendSuccess(() -> Component.translatable("commands.difficulty.success",
                difficulty.getDisplayName()), true);
        return 0;
    }

    /**
     * {@code /wsc world difficulty default <value>} — the vanilla dimensions share the server's own
     * difficulty, so this is plain {@code /difficulty}, just callable from any world.
     */
    private static int setVanillaDifficulty(CommandSourceStack source, Difficulty difficulty) {
        MinecraftServer server = source.getServer();
        if (server.getWorldData().getDifficulty() == difficulty) {
            source.sendFailure(Component.translatable("commands.difficulty.failure", difficulty.getKey()));
            return 0;
        }
        server.setDifficulty(difficulty, true);
        source.sendSuccess(() -> Component.translatable("commands.difficulty.success",
                difficulty.getDisplayName())
                .append(Messages.info(" (world '" + WorldRegistry.DEFAULT_GROUP + "')")), true);
        return 1;
    }

    private static int setWorldDifficulty(CommandSourceStack source, ServerLevel level, Difficulty difficulty) {
        PerWorldLevelData data = level instanceof DynamicServerLevel dynamic ? dynamic.perWorldData() : null;
        if (data == null) {
            // The vanilla group has no per-world data — its difficulty IS the server difficulty,
            // so set that, exactly like plain /difficulty does.
            return setVanillaDifficulty(source, difficulty);
        }
        if (!data.ownDifficulty()) {
            source.sendFailure(Messages.error("perWorldDifficulty is disabled in the server config."));
            return 0;
        }
        if (data.getDifficulty() == difficulty) {
            source.sendFailure(Component.translatable("commands.difficulty.failure", difficulty.getKey()));
            return 0;
        }
        MinecraftServer server = source.getServer();
        data.setDifficulty(difficulty);
        WorldRegistry.get(server).setDifficulty(WorldRegistry.groupOf(level.dimension()), difficulty);
        DynamicDimensionManager.applySpawnSettings(server, level, difficulty);
        var packet = new ClientboundChangeDifficultyPacket(difficulty,
                server.getWorldData().isDifficultyLocked());
        for (ServerPlayer player : level.players()) {
            player.connection.send(packet);
        }
        String world = worldName(level);
        source.sendSuccess(() -> Component.translatable("commands.difficulty.success",
                difficulty.getDisplayName()).append(Messages.info(" (world '" + world + "')")), true);
        return 0;
    }

    private static void reapplyManagedSpawnFlags(MinecraftServer server) {
        if (!Config.perWorldDifficulty()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level instanceof DynamicServerLevel dynamic
                    && dynamic.perWorldData() != null && dynamic.perWorldData().ownDifficulty()) {
                DynamicDimensionManager.applySpawnSettings(server, level, level.getDifficulty());
            }
        }
    }

    /** Explicit world targeting: {@code /wsc world difficulty <world> [value]}. */
    public static LiteralArgumentBuilder<CommandSourceStack> buildWscDifficultyNode() {
        RequiredArgumentBuilder<CommandSourceStack, String> worldArg =
                Commands.argument("world", StringArgumentType.word())
                        .suggests(WorldSuggestions.SWITCH_TARGETS)
                        .executes(context -> {
                            ServerLevel level = resolveWorldLevel(context);
                            if (level == null) {
                                return 0;
                            }
                            Difficulty difficulty = level.getDifficulty();
                            String world = worldName(level);
                            context.getSource().sendSuccess(() -> Component.translatable(
                                    "commands.difficulty.query", difficulty.getDisplayName())
                                    .append(Messages.info(" (world '" + world + "')")), false);
                            return difficulty.getId();
                        });
        for (Difficulty value : Difficulty.values()) {
            worldArg.then(Commands.literal(value.getKey()).executes(context -> {
                ServerLevel level = resolveWorldLevel(context);
                return level != null ? setWorldDifficulty(context.getSource(), level, value) : 0;
            }));
        }
        return Commands.literal("difficulty").then(worldArg);
    }

    /**
     * Resolves the "world" argument to a loaded managed level (no feature-config check).
     * {@code default} targets the vanilla group, i.e. the overworld — so a world's difficulty can
     * be set from anywhere, not only while standing in it.
     */
    @Nullable
    private static ServerLevel resolveWorldLevel(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "world");
        if (WorldRegistry.isDefaultGroup(name)) {
            return source.getServer().overworld();
        }
        WorldRegistry.WorldEntry entry = WorldRegistry.get(source.getServer()).byName(name);
        if (entry == null) {
            source.sendFailure(Messages.error("Unknown world: " + name));
            return null;
        }
        ServerLevel level = source.getServer().getLevel(entry.dimensionKey());
        if (level == null) {
            source.sendFailure(Messages.error("World '" + entry.name() + "' is unloaded — run ")
                    .append(Messages.runCommand("/wsc world load " + entry.name(), "/wsc world load " + entry.name(),
                            ChatFormatting.YELLOW))
                    .append(Messages.error(" first.")));
            return null;
        }
        return level;
    }

    // ------------------------------------------------------------------ /wsc world gamerule <world> …

    /** Explicit world targeting from anywhere: query, set, or list overrides of a world. */
    public static LiteralArgumentBuilder<CommandSourceStack> buildWscGameruleNode() {
        RequiredArgumentBuilder<CommandSourceStack, String> worldArg =
                Commands.argument("world", StringArgumentType.word())
                        .suggests(WorldSuggestions.SWITCH_TARGETS)
                        .executes(GameRuleHelper::listWorldRules);
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                worldArg.then(Commands.literal(key.getId())
                        .executes(context -> queryWorldRule(context, key))
                        .then(type.createArgument("value")
                                .executes(context -> setWorldRule(context, key))));
            }
        });
        return Commands.literal("gamerule").then(worldArg);
    }

    @Nullable
    private static ServerLevel resolveWorldTarget(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "world");
        if (WorldRegistry.isDefaultGroup(name)) {
            // The vanilla group always uses the vanilla rules, so perWorldGameRules does not apply.
            return source.getServer().overworld();
        }
        WorldRegistry.WorldEntry entry = WorldRegistry.get(source.getServer()).byName(name);
        if (entry == null) {
            source.sendFailure(Messages.error("Unknown world: " + name));
            return null;
        }
        if (!Config.perWorldGameRules()) {
            source.sendFailure(Messages.error("perWorldGameRules is disabled in the server config."));
            return null;
        }
        ServerLevel level = source.getServer().getLevel(entry.dimensionKey());
        if (level == null) {
            source.sendFailure(Messages.error("World '" + entry.name() + "' is unloaded — run ")
                    .append(Messages.runCommand("/wsc world load " + entry.name(), "/wsc world load " + entry.name(),
                            ChatFormatting.YELLOW))
                    .append(Messages.error(" first.")));
            return null;
        }
        return level;
    }

    private static <T extends GameRules.Value<T>> int setWorldRule(CommandContext<CommandSourceStack> context,
                                                                   GameRules.Key<T> key) {
        ServerLevel level = resolveWorldTarget(context);
        if (level == null) {
            return 0;
        }
        T rule = level.getGameRules().getRule(key);
        applyWithoutCallback(rule, context);
        afterWorldRuleChange(level, key);
        String world = worldName(level);
        context.getSource().sendSuccess(() -> Component.translatable("commands.gamerule.set",
                key.getId(), rule.toString()).append(Messages.info(" (world '" + world + "')")), true);
        return rule.getCommandResult();
    }

    private static <T extends GameRules.Value<T>> int queryWorldRule(CommandContext<CommandSourceStack> context,
                                                                     GameRules.Key<T> key) {
        ServerLevel level = resolveWorldTarget(context);
        if (level == null) {
            return 0;
        }
        T rule = level.getGameRules().getRule(key);
        String world = worldName(level);
        context.getSource().sendSuccess(() -> Component.translatable("commands.gamerule.query",
                key.getId(), rule.toString()).append(Messages.info(" (world '" + world + "')")), false);
        return rule.getCommandResult();
    }

    /** Bare {@code /wsc world gamerule <world>}: rules that differ from the global (default) values. */
    private static int listWorldRules(CommandContext<CommandSourceStack> context) {
        ServerLevel level = resolveWorldTarget(context);
        if (level == null) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        GameRules worldRules = level.getGameRules();
        GameRules globalRules = source.getServer().getGameRules();
        String world = worldName(level);

        List<Component> lines = new ArrayList<>();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                String worldValue = worldRules.getRule(key).toString();
                String globalValue = globalRules.getRule(key).toString();
                if (!worldValue.equals(globalValue)) {
                    lines.add(Component.literal("  ")
                            .append(Messages.suggestCommand(key.getId(),
                                    "/wsc world gamerule " + world + " " + key.getId() + " ", ChatFormatting.AQUA))
                            .append(Component.literal(" = " + worldValue).withStyle(ChatFormatting.WHITE))
                            .append(Messages.info("  (default world: " + globalValue + ")")));
                }
            }
        });

        if (lines.isEmpty()) {
            source.sendSuccess(() -> Messages.info("World '" + world
                    + "' has no game rule overrides (all values match the default world)."), false);
        } else {
            source.sendSuccess(() -> Messages.highlight("Game rule overrides in '" + world + "' ("
                    + lines.size() + "):"), false);
            for (Component line : lines) {
                source.sendSuccess(() -> line, false);
            }
        }
        return lines.size();
    }
}
