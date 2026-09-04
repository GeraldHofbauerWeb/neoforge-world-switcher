package net.geraldhofbauer.worldswitcher.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.geraldhofbauer.worldswitcher.Config;
import net.geraldhofbauer.worldswitcher.player.PlayerStateManager;
import net.geraldhofbauer.worldswitcher.util.Messages;
import net.geraldhofbauer.worldswitcher.world.WorldRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /wsc world gamemode} and {@code /wsc world access} — the two policies a world can impose
 * on the players in it.
 *
 * <p>A world's game mode is either a <b>default</b>, which seeds the first visit and then lets the
 * player's own per-world mode take over, or <b>forced</b>, which re-applies on every entry — the
 * difference between "this is a creative world" and "nobody leaves adventure mode here".</p>
 *
 * <p>Access is a minimum permission level. It gates {@code /ws}, portals leading into the world and
 * login; {@code /wsc player tp} deliberately bypasses it, because moving someone in is an explicit
 * admin decision.</p>
 */
public final class WorldPolicyCommands {

    private WorldPolicyCommands() {
    }

    // ------------------------------------------------------------------ /wsc world gamemode

    public static LiteralArgumentBuilder<CommandSourceStack> buildGameModeNode() {
        var worldArg = Commands.argument("world", StringArgumentType.word())
                .suggests(WorldSuggestions.SWITCH_TARGETS)
                .executes(WorldPolicyCommands::queryGameMode);
        worldArg.then(Commands.literal("none")
                .executes(context -> setGameMode(context, null, false)));
        for (GameType type : GameType.values()) {
            worldArg.then(Commands.literal(type.getName())
                    .executes(context -> setGameMode(context, type, false))
                    .then(Commands.literal("forced")
                            .executes(context -> setGameMode(context, type, true))));
        }
        return Commands.literal("gamemode").then(worldArg);
    }

    private static int queryGameMode(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "world");
        if (WorldRegistry.isDefaultGroup(name)) {
            WorldRegistry registry = WorldRegistry.get(source.getServer());
            source.sendSuccess(() -> Messages.info("World '" + WorldRegistry.DEFAULT_GROUP
                    + "' game mode: " + describe(registry.defaultGroupGameMode(),
                            registry.defaultGroupForceGameMode())), false);
            return 1;
        }
        WorldRegistry.WorldEntry entry = resolve(context);
        if (entry == null) {
            return 0;
        }
        source.sendSuccess(() -> Messages.info("World '" + entry.name() + "' game mode: "
                + describeGameMode(entry)), false);
        return 1;
    }

    /** One line for {@code /wsc world info} and the query form. */
    public static String describeGameMode(WorldRegistry.WorldEntry entry) {
        return describe(entry.defaultGameMode(), entry.forceGameMode());
    }

    /** Same, for a policy that does not belong to a {@link WorldRegistry.WorldEntry}. */
    public static String describe(@Nullable GameType mode, boolean forced) {
        if (mode == null) {
            return "not set (players keep their own)";
        }
        return forced
                ? mode.getName() + " (forced on every entry)"
                : mode.getName() + " (default on the first visit only)";
    }

    private static int setGameMode(CommandContext<CommandSourceStack> context, @Nullable GameType mode,
                                   boolean forced) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        if (WorldRegistry.isDefaultGroup(StringArgumentType.getString(context, "world"))) {
            return setDefaultGroupGameMode(source, server, mode, forced);
        }
        WorldRegistry.WorldEntry entry = resolve(context);
        if (entry == null) {
            return 0;
        }
        WorldRegistry.get(server).setGameMode(entry.id(), mode, forced);

        if (mode == null) {
            source.sendSuccess(() -> Messages.success("World ").append(Messages.highlight(entry.name()))
                    .append(Messages.info(" no longer sets a game mode.")), true);
            return 1;
        }
        // Forced takes effect for everyone standing there right now; a default only ever seeds a
        // first visit, so applying it retroactively would overwrite modes players already have.
        int changed = 0;
        if (forced) {
            for (ServerPlayer player : playersIn(server, entry)) {
                PlayerStateManager.applyWorldGameMode(player, player.serverLevel());
                changed++;
            }
        }
        int affected = changed;
        source.sendSuccess(() -> Messages.success("World ").append(Messages.highlight(entry.name()))
                .append(Messages.info(" game mode: " + describeGameMode(entry)
                        + (forced ? " — applied to " + affected + " player(s) currently there" : "")))
                , true);
        if (!forced && !Config.separateInventories()) {
            source.sendSuccess(() -> Messages.info("  Note: separateInventories is off, so there is no "
                    + "per-world mode to seed — only 'forced' has an effect."), false);
        }
        return 1;
    }

    /**
     * The {@value WorldRegistry#DEFAULT_GROUP} group covers every vanilla dimension, so "players
     * currently there" means everyone in the overworld, nether or end.
     */
    private static int setDefaultGroupGameMode(CommandSourceStack source, MinecraftServer server,
                                               @Nullable GameType mode, boolean forced) {
        WorldRegistry registry = WorldRegistry.get(server);
        registry.setDefaultGroupGameMode(mode, forced);
        if (mode == null) {
            source.sendSuccess(() -> Messages.success("World ")
                    .append(Messages.highlight(WorldRegistry.DEFAULT_GROUP))
                    .append(Messages.info(" no longer sets a game mode.")), true);
            return 1;
        }
        int changed = 0;
        if (forced) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (WorldRegistry.DEFAULT_GROUP.equals(
                        WorldRegistry.groupOf(player.level().dimension()))) {
                    PlayerStateManager.applyWorldGameMode(player, player.serverLevel());
                    changed++;
                }
            }
        }
        int affected = changed;
        source.sendSuccess(() -> Messages.success("World ")
                .append(Messages.highlight(WorldRegistry.DEFAULT_GROUP))
                .append(Messages.info(" game mode: " + describe(mode, forced)
                        + (forced ? " — applied to " + affected + " player(s) currently there" : "")))
                , true);
        return 1;
    }

    // ------------------------------------------------------------------ /wsc world access

    public static LiteralArgumentBuilder<CommandSourceStack> buildAccessNode() {
        return Commands.literal("access")
                .then(Commands.argument("world", StringArgumentType.word())
                        .suggests(WorldSuggestions.REGISTERED_WORLDS)
                        .executes(WorldPolicyCommands::queryAccess)
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 4))
                                .executes(WorldPolicyCommands::setAccess)));
    }

    private static int queryAccess(CommandContext<CommandSourceStack> context) {
        if (rejectDefaultGroup(context, "restricted")) {
            return 0;
        }
        WorldRegistry.WorldEntry entry = resolve(context);
        if (entry == null) {
            return 0;
        }
        context.getSource().sendSuccess(() -> Messages.info("World '" + entry.name() + "' access: "
                + describeAccess(entry)), false);
        return 1;
    }

    /** One line for {@code /wsc world info} and the query form. */
    public static String describeAccess(WorldRegistry.WorldEntry entry) {
        int level = entry.requiredPermissionLevel();
        return level <= 0 ? "open to everyone" : "permission level " + level + " and up";
    }

    private static int setAccess(CommandContext<CommandSourceStack> context) {
        if (rejectDefaultGroup(context, "restricted")) {
            return 0;
        }
        WorldRegistry.WorldEntry entry = resolve(context);
        if (entry == null) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        int level = IntegerArgumentType.getInteger(context, "level");

        List<ServerPlayer> losing = new ArrayList<>();
        for (ServerPlayer player : playersIn(server, entry)) {
            if (level > 0 && !player.hasPermissions(level)) {
                losing.add(player);
            }
        }
        if (losing.isEmpty()) {
            return applyAccess(source, entry, level, List.of());
        }
        Component question = Messages.error("Restrict world '" + entry.name()
                + "' to permission level " + level + "?")
                .append(Component.literal("\n"))
                .append(Messages.info("  " + losing.size() + " player(s) in it right now do not have "
                        + "that level and will be moved to the default world."));
        return PendingConfirmations.request(source, question,
                confirming -> applyAccess(confirming, entry, level, losing));
    }

    private static int applyAccess(CommandSourceStack source, WorldRegistry.WorldEntry entry, int level,
                                   List<ServerPlayer> eject) {
        MinecraftServer server = source.getServer();
        WorldRegistry.get(server).setRequiredPermissionLevel(entry.id(), level);
        int moved = 0;
        for (ServerPlayer player : eject) {
            // Re-check: they may have logged out or been op'd since the prompt.
            if (player.hasPermissions(level) || player.hasDisconnected()) {
                continue;
            }
            player.sendSystemMessage(Messages.error("World '" + entry.name()
                    + "' is now restricted — you were moved to the default world."));
            PlayerStateManager.switchPlayer(player, server.overworld());
            moved++;
        }
        int total = moved;
        source.sendSuccess(() -> Messages.success("World ").append(Messages.highlight(entry.name()))
                .append(Messages.info(" access: " + describeAccess(entry)
                        + (total > 0 ? " — moved " + total + " player(s) out" : ""))), true);
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    /** The online players standing in this world (its own dimension, not its inventory group). */
    private static List<ServerPlayer> playersIn(MinecraftServer server, WorldRegistry.WorldEntry entry) {
        List<ServerPlayer> found = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (WorldRegistry.groupOf(player.level().dimension()).equals(entry.id())) {
                found.add(player);
            }
        }
        return found;
    }

    /**
     * The default group is where players are sent when a world turns them away, so it must stay
     * reachable — there is nowhere to fall back to. Says so instead of "Unknown world: default".
     */
    private static boolean rejectDefaultGroup(CommandContext<CommandSourceStack> context, String what) {
        if (!WorldRegistry.isDefaultGroup(StringArgumentType.getString(context, "world"))) {
            return false;
        }
        context.getSource().sendFailure(Messages.error("The '" + WorldRegistry.DEFAULT_GROUP
                + "' world cannot be " + what + " — it is where players are sent when another world "
                + "turns them away, so it has to stay reachable."));
        return true;
    }

    @Nullable
    private static WorldRegistry.WorldEntry resolve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "world");
        WorldRegistry.WorldEntry entry = WorldRegistry.get(context.getSource().getServer()).byName(name);
        if (entry == null) {
            context.getSource().sendFailure(Messages.error("Unknown world: " + name));
        }
        return entry;
    }
}
