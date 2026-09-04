package net.geraldhofbauer.worldswitcher.player.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/**
 * A bridge to a mod that keeps player data in its own storage, out of reach of both NeoForge data
 * attachments and the persistent player NBT — Cosmetic Armor Reworked, for instance, writes
 * {@code <save>/playerdata/<uuid>.cosarmor} itself.
 *
 * <p>Implementations are dependency-free: they bind to the target mod reflectively and report
 * {@link #available()} false when it is absent. The contract mirrors the existing Tough As Nails and
 * Curios hooks — a missing class stays silent, an unexpected API shape warns once, a runtime failure
 * logs an error and disables the bridge permanently. <b>A world switch must never crash on modded
 * state.</b></p>
 *
 * <p>Timing: {@link #apply} runs BEFORE the teleport, because mods sync their state to the client
 * when the player joins the destination level. Implementations that cache client-side state should
 * force a re-sync themselves.</p>
 */
public interface PlayerDataBridge {

    /** Config key, namespaced like a ResourceLocation: {@code <modid>:<what>}. */
    String id();

    /** Mod id this bridge targets — used for the display name and for the config comment. */
    String modId();

    /** One short line describing what this bridge swaps; becomes a comment in the config file. */
    String description();

    /** Lazy, cached availability check. False when the target mod is absent or its API changed. */
    boolean available();

    /** Captures the current state, or null when there is nothing to store. */
    @Nullable
    CompoundTag capture(ServerPlayer player);

    /** Applies a stored state; {@code null} means "reset to a fresh state" (first visit of a world). */
    void apply(ServerPlayer player, @Nullable CompoundTag tag);
}
