package net.geraldhofbauer.worldswitcher.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.geraldhofbauer.worldswitcher.player.PlayerStateManager;
import net.geraldhofbauer.worldswitcher.player.PlayerStateStore;
import net.geraldhofbauer.worldswitcher.util.Messages;
import net.geraldhofbauer.worldswitcher.world.WorldRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import com.mojang.brigadier.suggestion.SuggestionProvider;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /wsc group …} — inventory groups. Worlds in the same group share one per-player state, so
 * putting two worlds together is what "keep these worlds in sync" means in practice: there is one
 * inventory, not two that are continuously reconciled.
 *
 * <p>Every world starts in a group of its own, named after its id. The reserved group
 * {@value WorldRegistry#DEFAULT_GROUP} is the one all vanilla dimensions belong to — a world put
 * there is exactly the old {@code shareinventory} behaviour.</p>
 */
public final class GroupCommands {

    /** Existing group names plus "default" — for the group argument. */
    public static final SuggestionProvider<CommandSourceStack> GROUPS = (context, builder) ->
            SharedSuggestionProvider.suggest(
                    WorldRegistry.get(context.getSource().getServer()).groups().keySet(), builder);

    private GroupCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("group")
                .executes(GroupCommands::list)
                .then(Commands.literal("list")
                        .executes(GroupCommands::list))
                .then(Commands.literal("info")
                        .then(Commands.argument("group", StringArgumentType.word())
                                .suggests(GROUPS)
                                .executes(GroupCommands::info)))
                .then(Commands.literal("set")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(WorldSuggestions.REGISTERED_WORLDS)
                                .then(Commands.argument("group", StringArgumentType.word())
                                        .suggests(GROUPS)
                                        .executes(GroupCommands::set))))
                .then(Commands.literal("unset")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(WorldSuggestions.REGISTERED_WORLDS)
                                .executes(GroupCommands::unset)));
    }

    // ------------------------------------------------------------------ list / info

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        Map<String, List<WorldRegistry.WorldEntry>> groups = WorldRegistry.get(server).groups();

        source.sendSuccess(() -> Messages.highlight("Inventory groups (" + groups.size() + "):"), false);
        groups.forEach((group, members) -> {
            List<String> names = new ArrayList<>();
            if (WorldRegistry.DEFAULT_GROUP.equals(group)) {
                names.add("the vanilla dimensions");
            }
            members.forEach(entry -> names.add(entry.name()));
            source.sendSuccess(() -> Component.literal("  ")
                    .append(Messages.highlight(group))
                    .append(Messages.info("  " + String.join(", ", names)))
                    .append(Component.literal("  " + snapshotCount(server, group) + " stored")
                            .withStyle(ChatFormatting.DARK_GRAY)), false);
        });
        source.sendSuccess(() -> Messages.info("Worlds in one group share a single player state. "
                + "Use /wsc group set <world> <group>."), false);
        return groups.size();
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        String group = StringArgumentType.getString(context, "group");
        List<WorldRegistry.WorldEntry> members = WorldRegistry.get(server).groups().get(group);
        if (members == null) {
            source.sendFailure(Messages.error("No such group: " + group));
            return 0;
        }
        source.sendSuccess(() -> Messages.highlight("Group '" + group + "'"), false);
        if (WorldRegistry.DEFAULT_GROUP.equals(group)) {
            source.sendSuccess(() -> Messages.info("  includes all vanilla dimensions "
                    + "(overworld, nether, end)"), false);
        }
        for (WorldRegistry.WorldEntry entry : members) {
            source.sendSuccess(() -> Messages.info("  " + entry.name()
                    + (entry.grouped() ? "  (moved here from its own group)" : "")), false);
        }
        if (members.isEmpty() && !WorldRegistry.DEFAULT_GROUP.equals(group)) {
            source.sendSuccess(() -> Messages.info("  (no worlds — only stored player state left)"), false);
        }
        source.sendSuccess(() -> Messages.info("  stored player states: "
                + snapshotCount(server, group)), false);
        return 1;
    }

    private static int snapshotCount(MinecraftServer server, String group) {
        PlayerStateStore store = PlayerStateStore.get(server);
        int count = 0;
        for (UUID player : store.knownPlayers()) {
            if (store.getSnapshot(player, group) != null) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ set / unset

    private static int set(CommandContext<CommandSourceStack> context) {
        WorldRegistry.WorldEntry entry = resolve(context);
        if (entry == null) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        String group = StringArgumentType.getString(context, "group");
        if (!WorldRegistry.ID_PATTERN.matcher(group).matches()) {
            source.sendFailure(Messages.error(
                    "Invalid group name (allowed: a-z 0-9 _ -, max 32 chars): " + group));
            return 0;
        }
        if (entry.inventoryGroup().equals(group)) {
            source.sendSuccess(() -> Messages.info("World '" + entry.name()
                    + "' is already in group '" + group + "'."), false);
            return 0;
        }
        return setGroup(source, entry, group);
    }

    private static int unset(CommandContext<CommandSourceStack> context) {
        WorldRegistry.WorldEntry entry = resolve(context);
        if (entry == null) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        if (!entry.grouped()) {
            source.sendSuccess(() -> Messages.info("World '" + entry.name()
                    + "' already has its own group."), false);
            return 0;
        }
        return setGroup(source, entry, entry.id());
    }

    /**
     * Moves a world into an inventory group, asking to confirm first. Also the implementation behind
     * the deprecated {@code /wsc shareinventory}.
     *
     * <p>Moving a world between groups changes which stored state its visitors see. That is never
     * destructive — the old group's snapshots stay on disk and {@code /wsc player state copy} can
     * fetch them back — but it is surprising enough to confirm, so the prompt says exactly how many
     * stored states and online players are affected.
     */
    public static int setGroup(CommandSourceStack source, WorldRegistry.WorldEntry entry,
                               String group) {
        MinecraftServer server = source.getServer();
        String from = entry.inventoryGroup();
        int affected = 0;
        for (var player : server.getPlayerList().getPlayers()) {
            if (WorldRegistry.groupOf(player.level().dimension()).equals(entry.id())) {
                affected++;
            }
        }
        int online = affected;
        int stored = snapshotCount(server, group);

        Component question = Messages.error("Move world '" + entry.name() + "' from inventory group '"
                + from + "' to '" + group + "'?")
                .append(Component.literal("\n"))
                .append(Messages.info("  Players there will see group '" + group + "'s state from now on"
                        + (stored > 0 ? " (" + stored + " already stored)" : " (nothing stored yet — "
                                + "they start fresh)") + "."))
                .append(Component.literal("\n"))
                .append(Messages.info("  " + online + " player(s) online in it right now; the old "
                        + "group's state is kept."));

        return PendingConfirmations.request(source, question, confirming -> {
            WorldRegistry registry = WorldRegistry.get(server);
            WorldRegistry.WorldEntry current = registry.byId(entry.id());
            if (current == null) {
                confirming.sendFailure(Messages.error("World no longer exists."));
                return 0;
            }
            if (registry.setInventoryGroup(current.id(), group)) {
                PlayerStateManager.onInventoryGroupChanged(server, current.id());
            }
            confirming.sendSuccess(() -> Messages.success("World ")
                    .append(Messages.highlight(current.name()))
                    .append(Messages.info(" is now in inventory group '" + group + "'"
                            + (WorldRegistry.DEFAULT_GROUP.equals(group)
                                    ? " — players keep their default-world items here." : "."))), true);
            return 1;
        });
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
