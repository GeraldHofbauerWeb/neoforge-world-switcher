package net.geraldhofbauer.worldswitcher.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.geraldhofbauer.worldswitcher.player.data.PlayerDataBridge;
import net.geraldhofbauer.worldswitcher.player.data.PlayerDataBridges;
import net.geraldhofbauer.worldswitcher.util.Messages;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Test-only hook, active ONLY with {@code -Dworldswitcher.e2e=true}: registers a serializable
 * marker attachment and a {@code /wsc debug} subcommand so the per-world attachment, persistent-data
 * and bridge swaps can be exercised end-to-end by a vanilla-protocol test client (mods like Curios
 * register mandatory network payloads and would reject it). The attachment-type registry is not
 * synced to clients, so vanilla clients still join.
 *
 * <p>{@code /wsc debug bridge get|set} reads and writes a {@link PlayerDataBridge}'s state as SNBT,
 * which makes a mod's own storage — Cosmetic Armor's slots, say — scriptable from the server console
 * without any client interaction.</p>
 */
public final class E2eTestHook {

    public static final String PROPERTY = "worldswitcher.e2e";

    @Nullable
    private static Supplier<AttachmentType<String>> e2eMarker;

    private E2eTestHook() {
    }

    public static boolean enabled() {
        return e2eMarker != null;
    }

    /** Called from the mod constructor, only when the system property is set. */
    public static void register(IEventBus modEventBus) {
        DeferredRegister<AttachmentType<?>> attachments =
                DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, WorldSwitcherMod.MODID);
        e2eMarker = attachments.register("e2e_marker",
                () -> AttachmentType.builder(() -> "").serialize(Codec.STRING).build());
        attachments.register(modEventBus);
        WorldSwitcherMod.LOGGER.warn("E2E test hook active ({}=true) — do not use in production", PROPERTY);
    }

    /** {@code /wsc debug attachment|pdata set/get …} — requires an executing player. */
    public static LiteralArgumentBuilder<CommandSourceStack> buildDebugNode() {
        return Commands.literal("debug")
                .then(Commands.literal("attachment")
                        .then(Commands.literal("set")
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .executes(E2eTestHook::attachmentSet)))
                        .then(Commands.literal("get")
                                .executes(E2eTestHook::attachmentGet)))
                .then(Commands.literal("pdata")
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.word())
                                                .executes(E2eTestHook::pdataSet))))
                        .then(Commands.literal("get")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(E2eTestHook::pdataGet))))
                .then(Commands.literal("bridge")
                        .then(Commands.literal("list")
                                .executes(E2eTestHook::bridgeList))
                        .then(Commands.literal("get")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .executes(E2eTestHook::bridgeGet)))
                        .then(Commands.literal("set")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .then(Commands.argument("nbt", StringArgumentType.greedyString())
                                                .executes(E2eTestHook::bridgeSet)))));
    }

    private static int bridgeList(CommandContext<CommandSourceStack> context) {
        for (PlayerDataBridge bridge : PlayerDataBridges.all()) {
            context.getSource().sendSuccess(() -> Messages.info("e2e bridge " + bridge.id()
                    + " available=" + bridge.available()), false);
        }
        return PlayerDataBridges.all().size();
    }

    @Nullable
    private static PlayerDataBridge bridge(CommandContext<CommandSourceStack> context) {
        String id = StringArgumentType.getString(context, "id");
        PlayerDataBridge bridge = PlayerDataBridges.byId(id);
        if (bridge == null || !bridge.available()) {
            context.getSource().sendFailure(Messages.error("e2e bridge " + id + " unavailable"));
        }
        return bridge != null && bridge.available() ? bridge : null;
    }

    private static int bridgeGet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        PlayerDataBridge bridge = bridge(context);
        if (bridge == null) {
            return 0;
        }
        CompoundTag captured = bridge.capture(player);
        context.getSource().sendSuccess(() -> Messages.info("e2e bridge " + bridge.id() + " = "
                + (captured == null ? "<absent>" : captured.toString())), false);
        return 1;
    }

    private static int bridgeSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        PlayerDataBridge bridge = bridge(context);
        if (bridge == null) {
            return 0;
        }
        String snbt = StringArgumentType.getString(context, "nbt");
        bridge.apply(player, "clear".equals(snbt) ? null : TagParser.parseTag(snbt));
        context.getSource().sendSuccess(() -> Messages.info("e2e bridge " + bridge.id() + " applied"),
                false);
        return 1;
    }

    private static int attachmentSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String value = StringArgumentType.getString(context, "value");
        player.setData(e2eMarker.get(), value);
        context.getSource().sendSuccess(() -> Messages.info("e2e attachment = " + value), false);
        return 1;
    }

    private static int attachmentGet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String value = player.getExistingData(e2eMarker.get()).orElse("<absent>");
        context.getSource().sendSuccess(() -> Messages.info("e2e attachment = " + value), false);
        return 1;
    }

    private static int pdataSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String key = StringArgumentType.getString(context, "key");
        String value = StringArgumentType.getString(context, "value");
        player.getPersistentData().putString(key, value);
        context.getSource().sendSuccess(() -> Messages.info("e2e pdata " + key + " = " + value), false);
        return 1;
    }

    private static int pdataGet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String key = StringArgumentType.getString(context, "key");
        String value = player.getPersistentData().contains(key)
                ? player.getPersistentData().getString(key) : "<absent>";
        context.getSource().sendSuccess(() -> Messages.info("e2e pdata " + key + " = " + value), false);
        return 1;
    }
}
