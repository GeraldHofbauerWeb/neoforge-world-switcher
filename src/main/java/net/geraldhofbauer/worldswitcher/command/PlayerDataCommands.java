package net.geraldhofbauer.worldswitcher.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.geraldhofbauer.worldswitcher.Config;
import net.geraldhofbauer.worldswitcher.player.data.PlayerDataBridge;
import net.geraldhofbauer.worldswitcher.player.data.PlayerDataBridges;
import net.geraldhofbauer.worldswitcher.player.data.PlayerDataCatalog;
import net.geraldhofbauer.worldswitcher.player.data.PlayerDataConfig;
import net.geraldhofbauer.worldswitcher.player.data.PlayerDataKey;
import net.geraldhofbauer.worldswitcher.player.data.PlayerDataSource;
import net.geraldhofbauer.worldswitcher.util.Messages;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;

/**
 * {@code /wsc config playerdata …} — which modded player data is per world and which is global.
 *
 * <p>The interesting one is {@code scan}: it lists everything found on this server, including keys
 * that are not in the config file yet, with the owning mod and a description. Attachment types carry
 * no label of their own, so those descriptions come from a bundled dictionary, the mod list, and a
 * guess based on the shape of the stored data.</p>
 */
public final class PlayerDataCommands {

    /**
     * Configured keys plus the known bridges — for the {@code set} argument. Deliberately reads the
     * config rather than rescanning: a full scan walks every stored snapshot, which is far too much
     * work to repeat on each keystroke, and the config already lists everything discovered so far.
     */
    public static final SuggestionProvider<CommandSourceStack> KEYS = (context, builder) -> {
        List<String> keys = new java.util.ArrayList<>(PlayerDataConfig.modes().keySet());
        for (PlayerDataBridge bridge : PlayerDataBridges.all()) {
            if (!keys.contains(bridge.id())) {
                keys.add(bridge.id());
            }
        }
        java.util.Collections.sort(keys);
        return SharedSuggestionProvider.suggest(keys, builder);
    };

    private PlayerDataCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("playerdata")
                .executes(PlayerDataCommands::status)
                .then(Commands.literal("status")
                        .executes(PlayerDataCommands::status))
                .then(Commands.literal("list")
                        .executes(PlayerDataCommands::list))
                .then(Commands.literal("scan")
                        .executes(PlayerDataCommands::scan))
                .then(Commands.literal("set")
                        .then(Commands.argument("key", StringArgumentType.string())
                                .suggests(KEYS)
                                .then(Commands.argument("perWorld", BoolArgumentType.bool())
                                        .executes(PlayerDataCommands::set))))
                .then(Commands.literal("write")
                        .executes(PlayerDataCommands::write))
                .then(Commands.literal("reload")
                        .executes(PlayerDataCommands::reload));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        List<PlayerDataKey> keys = PlayerDataCatalog.scan(server);

        source.sendSuccess(() -> Messages.highlight("Modded player data"), false);
        source.sendSuccess(() -> Messages.info("  master switches: attachments="
                + Config.swapModAttachments() + ", persistent NBT=" + Config.swapPersistentData()
                + ", Tough As Nails=" + Config.swapToughAsNails()), false);
        for (PlayerDataSource kind : PlayerDataSource.values()) {
            long count = keys.stream().filter(key -> key.source() == kind).count();
            long usable = keys.stream().filter(key -> key.source() == kind && key.usable()).count();
            source.sendSuccess(() -> Messages.info("  " + kind.title() + ": " + count
                    + (usable != count ? " (" + usable + " swappable)" : "")), false);
        }
        List<PlayerDataBridge> bridges = PlayerDataBridges.available();
        source.sendSuccess(() -> Messages.info("  active bridges: " + (bridges.isEmpty() ? "none"
                : bridges.stream().map(PlayerDataBridge::id).reduce((a, b) -> a + ", " + b).orElse("")))
                , false);
        source.sendSuccess(() -> Messages.info("  configured global (not per world): "
                + PlayerDataConfig.globalCount()), false);
        source.sendSuccess(() -> Messages.info("File: serverconfig/worldswitcher-playerdata.toml — "
                + "edit and /wsc config playerdata reload, or use ")
                .append(Messages.suggestCommand("/wsc config playerdata set",
                        "/wsc config playerdata set ", ChatFormatting.YELLOW)), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Map<String, Boolean> modes = PlayerDataConfig.modes();
        source.sendSuccess(() -> Messages.highlight("Configured player-data keys (" + modes.size()
                + "):"), false);
        modes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> source.sendSuccess(() -> line(entry.getKey(), entry.getValue()), false));
        if (modes.isEmpty()) {
            source.sendSuccess(() -> Messages.info("  (nothing configured — everything is per world)"),
                    false);
        }
        return modes.size();
    }

    private static int scan(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<PlayerDataKey> keys = PlayerDataCatalog.scan(source.getServer());
        source.sendSuccess(() -> Messages.highlight("Player data found on this server ("
                + keys.size() + "):"), false);
        for (PlayerDataSource kind : PlayerDataSource.values()) {
            List<PlayerDataKey> section = keys.stream().filter(key -> key.source() == kind).toList();
            if (section.isEmpty()) {
                continue;
            }
            source.sendSuccess(() -> Messages.highlight("  " + kind.title()), false);
            for (PlayerDataKey key : section) {
                source.sendSuccess(() -> Component.literal("  ")
                        .append(line(key.id(), PlayerDataConfig.isPerWorld(key.id()))), false);
                source.sendSuccess(() -> Component.literal("      ")
                        .append(Component.literal(PlayerDataCatalog.modNameFor(key) + " — "
                                + key.description()).withStyle(ChatFormatting.DARK_GRAY)), false);
                if (!key.usable()) {
                    source.sendSuccess(() -> Component.literal("      ")
                            .append(Component.literal("not swappable — this entry has no effect")
                                    .withStyle(ChatFormatting.DARK_GRAY)), false);
                }
            }
        }
        return keys.size();
    }

    private static Component line(String key, boolean perWorld) {
        return Component.literal("  ")
                .append(Messages.suggestCommand(key,
                        "/wsc config playerdata set \"" + key + "\" " + !perWorld, ChatFormatting.AQUA))
                .append(Component.literal("  " + (perWorld ? "per world" : "global"))
                        .withStyle(perWorld ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
    }

    private static int set(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String key = StringArgumentType.getString(context, "key");
        boolean perWorld = BoolArgumentType.getBool(context, "perWorld");
        PlayerDataConfig.set(source.getServer(), key, perWorld);
        source.sendSuccess(() -> Messages.success("Player data ")
                .append(Messages.highlight(key))
                .append(Messages.info(perWorld
                        ? " is now kept per world group."
                        : " is now global (shared by all worlds).")), true);
        return 1;
    }

    private static int write(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PlayerDataConfig.write(source.getServer());
        source.sendSuccess(() -> Messages.info("Regenerated serverconfig/worldswitcher-playerdata.toml "
                + "— existing choices kept, comments refreshed, new keys appended."), true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PlayerDataConfig.reload(source.getServer());
        source.sendSuccess(() -> Messages.info("Reloaded the player-data config: "
                + PlayerDataConfig.modes().size() + " key(s), " + PlayerDataConfig.globalCount()
                + " global. Check the log for any parse warnings."), true);
        return 1;
    }
}
