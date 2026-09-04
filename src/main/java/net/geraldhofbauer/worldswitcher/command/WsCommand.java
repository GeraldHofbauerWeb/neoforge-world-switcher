package net.geraldhofbauer.worldswitcher.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.geraldhofbauer.worldswitcher.Config;
import net.geraldhofbauer.worldswitcher.player.PlayerStateManager;
import net.geraldhofbauer.worldswitcher.util.Messages;
import net.geraldhofbauer.worldswitcher.world.DynamicDimensionManager;
import net.geraldhofbauer.worldswitcher.world.WorldRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /ws <world>} — switch the executing player to another world.
 */
public final class WsCommand {

    private WsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ws")
                .requires(source -> source.hasPermission(Config.wsPermissionLevel()))
                .executes(WsCommand::executeList)
                .then(Commands.argument("world", StringArgumentType.word())
                        .suggests(WorldSuggestions.ACCESSIBLE_WORLDS)
                        .executes(WsCommand::executeSwitch)));
    }

    /** Bare {@code /ws}: list the switchable worlds instead of a Brigadier usage error. */
    private static int executeList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var entries = WorldRegistry.get(source.getServer()).entries();
        String current = source.getEntity() instanceof ServerPlayer player
                ? WorldRegistry.groupOf(player.level().dimension()) : "";

        source.sendSuccess(() -> Messages.info("Usage: /ws <world> — available worlds:"), false);
        var defaultLine = net.minecraft.network.chat.Component.literal("  ")
                .append(Messages.runCommand(WorldRegistry.DEFAULT_GROUP, "/ws default",
                        net.minecraft.ChatFormatting.AQUA));
        if (WorldRegistry.DEFAULT_GROUP.equals(current)) {
            defaultLine.append(Messages.info("  (you are here)"));
        }
        source.sendSuccess(() -> defaultLine, false);
        ServerPlayer viewer = source.getEntity() instanceof ServerPlayer player ? player : null;
        for (WorldRegistry.WorldEntry entry : entries) {
            if (viewer != null && !WorldRegistry.mayEnter(viewer, entry)) {
                continue;
            }
            var line = net.minecraft.network.chat.Component.literal("  ")
                    .append(Messages.runCommand(entry.name(), "/ws " + entry.name(),
                            net.minecraft.ChatFormatting.AQUA));
            if (entry.unloaded()) {
                line.append(net.minecraft.network.chat.Component.literal("  unloaded")
                        .withStyle(net.minecraft.ChatFormatting.RED));
            } else if (entry.id().equals(current)) {
                line.append(Messages.info("  (you are here)"));
            }
            source.sendSuccess(() -> line, false);
        }
        return entries.size() + 1;
    }

    private static int executeSwitch(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        String worldName = StringArgumentType.getString(context, "world");
        return switchToWorld(source, player, worldName, true);
    }

    /**
     * Shared by /ws and /wsc player tp. Returns the command result (1 = success).
     *
     * @param enforceAccess whether the world's required permission level applies — true for
     *                      {@code /ws}, false for {@code /wsc player tp}, which is an OP-gated
     *                      admin action and may put a player anywhere
     */
    static int switchToWorld(CommandSourceStack source, ServerPlayer player, String worldName,
                             boolean enforceAccess) {
        MinecraftServer server = source.getServer();
        String currentGroup = WorldRegistry.groupOf(player.level().dimension());

        if (WorldRegistry.DEFAULT_GROUP.equalsIgnoreCase(worldName)) {
            if (WorldRegistry.DEFAULT_GROUP.equals(currentGroup)) {
                source.sendSuccess(() -> Messages.info("Already in the default world."), false);
                return 1;
            }
            PlayerStateManager.switchPlayer(player, server.overworld());
            source.sendSuccess(() -> Messages.success("Switched to ").append(Messages.highlight("default")), true);
            announceSwitch(server, player, WorldRegistry.DEFAULT_GROUP, "/ws " + WorldRegistry.DEFAULT_GROUP);
            return 1;
        }

        WorldRegistry.WorldEntry entry = WorldRegistry.get(server).byName(worldName);
        if (entry == null) {
            source.sendFailure(Messages.error("Unknown world: " + worldName));
            return 0;
        }
        if (entry.unloaded()) {
            source.sendFailure(Messages.error("World '" + entry.name() + "' is unloaded — ask an admin to run ")
                    .append(Messages.runCommand("/wsc world load " + entry.name(), "/wsc world load " + entry.name(),
                            net.minecraft.ChatFormatting.YELLOW)));
            return 0;
        }
        if (enforceAccess && !WorldRegistry.mayEnter(player, entry)) {
            source.sendFailure(Messages.error("You do not have permission to enter world '"
                    + entry.name() + "'."));
            return 0;
        }
        if (entry.id().equals(currentGroup)) {
            source.sendSuccess(() -> Messages.info("Already in world '" + entry.name() + "'."), false);
            return 1;
        }

        ServerLevel target = DynamicDimensionManager.getLoadedLevel(server, entry);
        if (target == null) {
            source.sendFailure(Messages.error("World '" + entry.name() + "' is not loaded."));
            return 0;
        }

        PlayerStateManager.switchPlayer(player, target);
        source.sendSuccess(() -> Messages.success("Switched to ").append(Messages.highlight(entry.name())), true);
        announceSwitch(server, player, entry.name(), "/ws " + entry.name());
        return 1;
    }

    /**
     * Announces a completed switch to every other online player with a clickable world name
     * ({@code /ws <world>}) so they can follow. No-op when {@code announceSwitches} is off.
     */
    private static void announceSwitch(MinecraftServer server, ServerPlayer player,
                                       String worldLabel, String joinCommand) {
        if (!Config.announceSwitches()) {
            return;
        }
        var message = net.minecraft.network.chat.Component.empty()
                .append(player.getDisplayName())
                .append(Messages.info(" switched to "))
                .append(Messages.runCommand(worldLabel, joinCommand, net.minecraft.ChatFormatting.AQUA))
                .append(Messages.info(" — click to join"));
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (!online.getUUID().equals(player.getUUID())) {
                online.sendSystemMessage(message);
            }
        }
    }
}
