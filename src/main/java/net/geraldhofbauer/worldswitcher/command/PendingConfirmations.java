package net.geraldhofbauer.worldswitcher.command;

import net.geraldhofbauer.worldswitcher.util.Messages;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * The one {@code /wsc confirm} / {@code /wsc cancel} channel, shared by every destructive action
 * (deleting a world, regrouping worlds, overwriting stored player state).
 *
 * <p>One pending request per command source; asking again replaces the previous one. Requests expire
 * after 30 seconds so a forgotten prompt can never be confirmed by accident later on.</p>
 */
public final class PendingConfirmations {

    /** Sentinel "UUID" for non-player command sources (console). */
    private static final UUID CONSOLE_UUID = new UUID(0L, 0L);
    private static final long TIMEOUT_MS = 30_000;

    private record Pending(ToIntFunction<CommandSourceStack> action, long requestedAt) {
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private PendingConfirmations() {
    }

    private static UUID sourceKey(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player.getUUID() : CONSOLE_UUID;
    }

    /**
     * Asks for confirmation, printing {@code question} followed by clickable Confirm/Cancel buttons.
     * The action runs on {@code /wsc confirm} and gets the confirming source, which may differ from
     * the requesting one only in that time has passed.
     */
    public static int request(CommandSourceStack source, Component question,
                              ToIntFunction<CommandSourceStack> action) {
        PENDING.put(sourceKey(source), new Pending(action, System.currentTimeMillis()));
        source.sendSuccess(() -> question, false);
        source.sendSuccess(() -> Component.literal("  ")
                .append(Messages.runCommand("[Confirm]", "/wsc confirm", ChatFormatting.RED))
                .append(Component.literal("  "))
                .append(Messages.runCommand("[Cancel]", "/wsc cancel", ChatFormatting.GRAY))
                .append(Messages.info("  (expires in 30s)")), false);
        return 1;
    }

    public static int confirm(CommandSourceStack source) {
        Pending pending = PENDING.remove(sourceKey(source));
        if (pending == null || System.currentTimeMillis() - pending.requestedAt() > TIMEOUT_MS) {
            source.sendFailure(Messages.error("Nothing to confirm (or the request expired)."));
            return 0;
        }
        return pending.action().applyAsInt(source);
    }

    public static int cancel(CommandSourceStack source) {
        boolean removed = PENDING.remove(sourceKey(source)) != null;
        source.sendSuccess(() -> Messages.info(removed ? "Cancelled." : "Nothing to cancel."), false);
        return removed ? 1 : 0;
    }
}
