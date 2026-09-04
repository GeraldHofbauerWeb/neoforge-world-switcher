package net.geraldhofbauer.worldswitcher.player.data.bridge;

import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.geraldhofbauer.worldswitcher.player.data.PlayerDataBridge;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Bridge to <a href="https://github.com/zlainsama/CosmeticArmorReworked">Cosmetic Armor Reworked</a>.
 *
 * <p>The mod keeps every player's cosmetic slots in its own file
 * {@code <save>/playerdata/<uuid>.cosarmor}, fronted by an in-memory cache — so neither the data
 * attachment nor the persistent-NBT mechanism can see it, and the slots stay global across worlds.
 * Its public API hands out the live inventory object ({@code CosArmorAPI.getCAStacks(UUID)}), which
 * extends NeoForge's {@code ItemStackHandler}; driving that object is enough, because the mod
 * persists its own cache on save and logout.</p>
 *
 * <p>Only the API entry point needs reflection — the returned object is manipulated through the
 * NeoForge interfaces it implements.</p>
 */
public final class CosArmorBridge implements PlayerDataBridge {

    public static final String ID = "cosmeticarmorreworked:cosarmor";
    private static final String MOD_ID = "cosmeticarmorreworked";

    private boolean initialized;
    private boolean available;

    @Nullable
    private Method getCaStacks;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public String description() {
        return "Cosmetic armor slots (stored by the mod in playerdata/<uuid>.cosarmor)";
    }

    @Override
    public synchronized boolean available() {
        if (!initialized) {
            initialized = true;
            try {
                Class<?> api = Class.forName("lain.mods.cos.api.CosArmorAPI");
                getCaStacks = api.getMethod("getCAStacks", UUID.class);
                available = true;
                WorldSwitcherMod.LOGGER.info("Cosmetic Armor Reworked detected — its cosmetic slots "
                        + "join the per-world player state");
            } catch (ClassNotFoundException e) {
                available = false; // mod not installed — expected, stay quiet
            } catch (ReflectiveOperationException e) {
                available = false;
                WorldSwitcherMod.LOGGER.warn("Cosmetic Armor Reworked found but its API changed — "
                        + "per-world cosmetic armor disabled", e);
            }
        }
        return available;
    }

    @Override
    @Nullable
    public CompoundTag capture(ServerPlayer player) {
        Object stacks = stacks(player);
        if (stacks == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            INBTSerializable<CompoundTag> serializable = (INBTSerializable<CompoundTag>) stacks;
            return serializable.serializeNBT(player.registryAccess());
        } catch (RuntimeException e) {
            disable(e);
            return null;
        }
    }

    @Override
    public void apply(ServerPlayer player, @Nullable CompoundTag tag) {
        Object stacks = stacks(player);
        if (stacks == null) {
            return;
        }
        try {
            IItemHandlerModifiable handler = (IItemHandlerModifiable) stacks;
            if (tag != null && !tag.isEmpty()) {
                @SuppressWarnings("unchecked")
                INBTSerializable<CompoundTag> serializable = (INBTSerializable<CompoundTag>) stacks;
                serializable.deserializeNBT(player.registryAccess(), tag);
                // The client renders cosmetic armor from its own copy, fed by the mod's change
                // listener. Its deserializeNBT does end in onLoad(), which notifies that listener for
                // every slot — but nothing in the public API promises that, and if it ever stops the
                // failure is silent: the server is right and every client keeps showing the previous
                // world's set until relog. Re-setting each slot to its own value goes through
                // setStackInSlot, which always fires the listener, for the price of a handful of
                // packets per world switch.
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    handler.setStackInSlot(slot, handler.getStackInSlot(slot));
                }
            } else {
                // First visit of a world group: fresh, empty cosmetic slots.
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    handler.setStackInSlot(slot, ItemStack.EMPTY);
                }
            }
        } catch (RuntimeException e) {
            disable(e);
        }
    }

    @Nullable
    private Object stacks(ServerPlayer player) {
        if (!available() || getCaStacks == null) {
            return null;
        }
        try {
            return getCaStacks.invoke(null, player.getUUID());
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable(e);
            return null;
        }
    }

    private void disable(Exception e) {
        available = false;
        WorldSwitcherMod.LOGGER.error("Cosmetic Armor Reworked integration failed at runtime — "
                + "per-world cosmetic armor disabled", e);
    }
}
