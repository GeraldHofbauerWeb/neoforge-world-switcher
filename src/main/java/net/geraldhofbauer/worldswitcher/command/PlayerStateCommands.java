package net.geraldhofbauer.worldswitcher.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.geraldhofbauer.worldswitcher.player.PlayerStateManager;
import net.geraldhofbauer.worldswitcher.player.PlayerStateStore;
import net.geraldhofbauer.worldswitcher.util.Messages;
import net.geraldhofbauer.worldswitcher.world.WorldRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /wsc player state …} — moving one player's stored per-world state between worlds.
 *
 * <p>Position and dimension never travel: a snapshot's coordinates belong to the world it was taken
 * in, and {@code restoreLastPosition} would otherwise drop the player into the wrong place. The
 * target keeps whatever position it already had, or falls back to its spawn. Everything else moves,
 * including the full modded state — Curios, persistent NBT, bridges such as Cosmetic Armor, and
 * Tough As Nails.</p>
 *
 * <p>Offline players work as well, because the store is keyed by UUID. When the player happens to be
 * online in the group being written, the new state is also applied to them live — writing only the
 * store would let their live state overwrite it again on their next world switch.</p>
 */
public final class PlayerStateCommands {

    /** Keys that describe where a snapshot was taken — never carried across a transfer. */
    private static final List<String> POSITION_KEYS =
            List.of("dimension", "posX", "posY", "posZ", "yaw", "pitch");

    /** Online players plus everyone the state store has ever seen. */
    public static final SuggestionProvider<CommandSourceStack> KNOWN_PLAYERS = (context, builder) -> {
        MinecraftServer server = context.getSource().getServer();
        List<String> names = new ArrayList<>(server.getPlayerList().getPlayerNamesArray().length);
        names.addAll(List.of(server.getPlayerList().getPlayerNamesArray()));
        for (UUID uuid : PlayerStateStore.get(server).knownPlayers()) {
            profileName(server, uuid).filter(name -> !names.contains(name)).ifPresent(names::add);
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    private record Target(UUID uuid, String name, @Nullable ServerPlayer online) {
    }

    private PlayerStateCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("state")
                .then(Commands.literal("show")
                        .then(playerArg().executes(PlayerStateCommands::show)))
                .then(Commands.literal("copy")
                        .then(playerArg().then(fromArg().then(toArg()
                                .executes(context -> transfer(context, false))))))
                .then(Commands.literal("move")
                        .then(playerArg().then(fromArg().then(toArg()
                                .executes(context -> transfer(context, true))))))
                .then(Commands.literal("swap")
                        .then(playerArg().then(fromArg().then(toArg()
                                .executes(PlayerStateCommands::swap)))))
                .then(Commands.literal("clear")
                        .then(playerArg().then(Commands.argument("world", StringArgumentType.word())
                                .suggests(WorldSuggestions.SWITCH_TARGETS)
                                .executes(PlayerStateCommands::clear))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
            playerArg() {
        return Commands.argument("player", StringArgumentType.word()).suggests(KNOWN_PLAYERS);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
            fromArg() {
        return Commands.argument("from", StringArgumentType.word())
                .suggests(WorldSuggestions.SWITCH_TARGETS);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
            toArg() {
        return Commands.argument("to", StringArgumentType.word())
                .suggests(WorldSuggestions.SWITCH_TARGETS);
    }

    // ------------------------------------------------------------------ show

    private static int show(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        Target target = resolvePlayer(source, StringArgumentType.getString(context, "player"));
        if (target == null) {
            return 0;
        }
        PlayerStateStore store = PlayerStateStore.get(server);
        List<String> groups = store.groupsFor(target.uuid());
        String current = target.online() != null
                ? PlayerStateManager.trackedGroup(server, target.online())
                : store.getCurrentGroup(target.uuid());

        source.sendSuccess(() -> Messages.highlight("Stored player state for " + target.name()
                + " (" + groups.size() + " group" + (groups.size() == 1 ? "" : "s") + "):"), false);
        for (String group : groups) {
            CompoundTag snapshot = store.getSnapshot(target.uuid(), group);
            String summary = snapshot == null ? "(empty)" : summarize(snapshot);
            boolean live = group.equals(current);
            source.sendSuccess(() -> Component.literal("  ")
                    .append(Messages.highlight(group))
                    .append(Messages.info("  " + summary + (live ? "  (currently in this group)" : ""))),
                    false);
        }
        if (groups.isEmpty()) {
            source.sendSuccess(() -> Messages.info("  (none — this player has not been tracked yet)"),
                    false);
        }
        return groups.size();
    }

    private static String summarize(CompoundTag snapshot) {
        // Vanilla only saves occupied slots, so the list size is the stack count.
        int stacks = snapshot.getList("inventory", Tag.TAG_COMPOUND).size();
        ListTag ender = snapshot.getList("enderChest", Tag.TAG_COMPOUND);
        String mode = snapshot.contains("gamemode")
                ? GameType.byId(snapshot.getInt("gamemode")).getName() : "?";
        return stacks + " stack" + (stacks == 1 ? "" : "s") + ", " + ender.size() + " in ender chest, "
                + "level " + snapshot.getInt("xpLevel") + ", " + mode;
    }

    // ------------------------------------------------------------------ copy / move / swap / clear

    private static int transfer(CommandContext<CommandSourceStack> context, boolean move) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        Target target = resolvePlayer(source, StringArgumentType.getString(context, "player"));
        if (target == null) {
            return 0;
        }
        String fromGroup = resolveGroup(source, StringArgumentType.getString(context, "from"));
        String toGroup = resolveGroup(source, StringArgumentType.getString(context, "to"));
        if (fromGroup == null || toGroup == null) {
            return 0;
        }
        if (fromGroup.equals(toGroup)) {
            source.sendFailure(Messages.error("Both worlds are in the same inventory group ('"
                    + fromGroup + "') — there is nothing to transfer."));
            return 0;
        }
        PlayerStateStore store = PlayerStateStore.get(server);
        CompoundTag from = store.getSnapshot(target.uuid(), fromGroup);
        if (from == null) {
            source.sendFailure(Messages.error(target.name() + " has no stored state for group '"
                    + fromGroup + "'."));
            return 0;
        }
        boolean overwrites = store.getSnapshot(target.uuid(), toGroup) != null;

        Component question = Messages.error((move ? "Move" : "Copy") + " " + target.name()
                + "'s player state from group '" + fromGroup + "' to '" + toGroup + "'?")
                .append(Component.literal("\n"))
                .append(Messages.info(overwrites
                        ? "  This overwrites the state they already had in '" + toGroup + "'."
                        : "  They have no state in '" + toGroup + "' yet."))
                .append(Component.literal("\n"))
                .append(Messages.info(move
                        ? "  Group '" + fromGroup + "' is reset to a fresh start afterwards."
                        : "  Group '" + fromGroup + "' keeps its state."))
                .append(Component.literal("\n"))
                .append(Messages.info("  Position is not carried over."));

        return PendingConfirmations.request(source, question, confirming -> {
            CompoundTag stored = store.getSnapshot(target.uuid(), fromGroup);
            if (stored == null) {
                confirming.sendFailure(Messages.error("The source state disappeared meanwhile."));
                return 0;
            }
            store.putSnapshot(target.uuid(), toGroup,
                    withPositionOf(stored, store.getSnapshot(target.uuid(), toGroup)));
            if (move) {
                store.removeSnapshot(target.uuid(), fromGroup);
            }
            refreshLive(server, target, toGroup);
            if (move) {
                refreshLive(server, target, fromGroup);
            }
            confirming.sendSuccess(() -> Messages.success((move ? "Moved " : "Copied ")
                    + target.name() + "'s player state ")
                    .append(Messages.info("from '" + fromGroup + "' to '" + toGroup + "'.")), true);
            return 1;
        });
    }

    private static int swap(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        Target target = resolvePlayer(source, StringArgumentType.getString(context, "player"));
        if (target == null) {
            return 0;
        }
        String groupA = resolveGroup(source, StringArgumentType.getString(context, "from"));
        String groupB = resolveGroup(source, StringArgumentType.getString(context, "to"));
        if (groupA == null || groupB == null) {
            return 0;
        }
        if (groupA.equals(groupB)) {
            source.sendFailure(Messages.error("Both worlds are in the same inventory group ('"
                    + groupA + "') — there is nothing to swap."));
            return 0;
        }
        PlayerStateStore store = PlayerStateStore.get(server);
        if (store.getSnapshot(target.uuid(), groupA) == null
                && store.getSnapshot(target.uuid(), groupB) == null) {
            source.sendFailure(Messages.error(target.name()
                    + " has no stored state in either group."));
            return 0;
        }

        Component question = Messages.error("Swap " + target.name() + "'s player state between groups '"
                + groupA + "' and '" + groupB + "'?")
                .append(Component.literal("\n"))
                .append(Messages.info("  Each group keeps its own position; everything else changes "
                        + "places."));

        return PendingConfirmations.request(source, question, confirming -> {
            CompoundTag a = store.getSnapshot(target.uuid(), groupA);
            CompoundTag b = store.getSnapshot(target.uuid(), groupB);
            if (b != null) {
                store.putSnapshot(target.uuid(), groupA, withPositionOf(b, a));
            } else {
                store.removeSnapshot(target.uuid(), groupA);
            }
            if (a != null) {
                store.putSnapshot(target.uuid(), groupB, withPositionOf(a, b));
            } else {
                store.removeSnapshot(target.uuid(), groupB);
            }
            refreshLive(server, target, groupA);
            refreshLive(server, target, groupB);
            confirming.sendSuccess(() -> Messages.success("Swapped " + target.name()
                    + "'s player state ").append(Messages.info("between '" + groupA + "' and '"
                            + groupB + "'.")), true);
            return 1;
        });
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        Target target = resolvePlayer(source, StringArgumentType.getString(context, "player"));
        if (target == null) {
            return 0;
        }
        String group = resolveGroup(source, StringArgumentType.getString(context, "world"));
        if (group == null) {
            return 0;
        }
        PlayerStateStore store = PlayerStateStore.get(server);
        if (store.getSnapshot(target.uuid(), group) == null) {
            source.sendFailure(Messages.error(target.name() + " has no stored state for group '"
                    + group + "'."));
            return 0;
        }

        Component question = Messages.error("Delete " + target.name() + "'s stored player state for "
                + "group '" + group + "'?")
                .append(Component.literal("\n"))
                .append(Messages.info("  Their next visit there starts fresh. This cannot be undone."));

        return PendingConfirmations.request(source, question, confirming -> {
            store.removeSnapshot(target.uuid(), group);
            refreshLive(server, target, group);
            confirming.sendSuccess(() -> Messages.success("Cleared " + target.name()
                    + "'s state for group '" + group + "'."), true);
            return 1;
        });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The transferred state, with the position of whatever the destination already had — or no
     * position at all, which makes the player arrive at the world spawn.
     */
    private static CompoundTag withPositionOf(CompoundTag state, @Nullable CompoundTag destination) {
        CompoundTag copy = state.copy();
        for (String key : POSITION_KEYS) {
            copy.remove(key);
            if (destination != null && destination.contains(key)) {
                copy.put(key, destination.get(key).copy());
            }
        }
        return copy;
    }

    /**
     * If the player is online and currently tracked in this group, their live state has to follow the
     * store — otherwise it would be written back over the transfer on their next world switch.
     */
    private static void refreshLive(MinecraftServer server, Target target, String group) {
        ServerPlayer player = target.online();
        if (player == null || !PlayerStateManager.trackedGroup(server, player).equals(group)) {
            return;
        }
        PlayerStateManager.applyStoredInPlace(player,
                PlayerStateStore.get(server).getSnapshot(target.uuid(), group));
    }

    /** Resolves a world name (or "default") to its inventory group. */
    @Nullable
    private static String resolveGroup(CommandSourceStack source, String name) {
        if (WorldRegistry.DEFAULT_GROUP.equalsIgnoreCase(name)) {
            return WorldRegistry.DEFAULT_GROUP;
        }
        WorldRegistry.WorldEntry entry = WorldRegistry.get(source.getServer()).byName(name);
        if (entry == null) {
            source.sendFailure(Messages.error("Unknown world: " + name));
            return null;
        }
        return entry.inventoryGroup();
    }

    /** Resolves a name to a player, online or not — the store is keyed by UUID. */
    @Nullable
    private static Target resolvePlayer(CommandSourceStack source, String name) {
        MinecraftServer server = source.getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return new Target(online.getUUID(), online.getGameProfile().getName(), online);
        }
        var cache = server.getProfileCache();
        if (cache != null) {
            var profile = cache.get(name);
            if (profile.isPresent()) {
                UUID uuid = profile.get().getId();
                return new Target(uuid, profile.get().getName(), server.getPlayerList().getPlayer(uuid));
            }
        }
        try {
            UUID uuid = UUID.fromString(name);
            return new Target(uuid, profileName(server, uuid).orElse(name),
                    server.getPlayerList().getPlayer(uuid));
        } catch (IllegalArgumentException ignored) {
            source.sendFailure(Messages.error("Unknown player: " + name
                    + " (offline players work, but the server has to have seen them before — "
                    + "a UUID also works)"));
            return null;
        }
    }

    private static java.util.Optional<String> profileName(MinecraftServer server, UUID uuid) {
        var cache = server.getProfileCache();
        return cache == null ? java.util.Optional.empty()
                : cache.get(uuid).map(com.mojang.authlib.GameProfile::getName);
    }
}
