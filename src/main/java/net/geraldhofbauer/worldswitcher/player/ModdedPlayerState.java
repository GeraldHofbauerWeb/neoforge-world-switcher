package net.geraldhofbauer.worldswitcher.player;

import net.geraldhofbauer.worldswitcher.Config;
import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.geraldhofbauer.worldswitcher.player.data.PlayerDataBridge;
import net.geraldhofbauer.worldswitcher.player.data.PlayerDataBridges;
import net.geraldhofbauer.worldswitcher.player.data.PlayerDataConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Capture/apply of MODDED per-player state, dependency-free. Three mechanisms cover everything
 * mods persist on a player in 1.21.1:
 *
 * <ol>
 * <li><b>NeoForge data attachments</b> (e.g. Curios' {@code curios:inventory}):
 *     {@code serializeAttachments} is public and returns a flat compound keyed by attachment id.
 *     The apply side ({@code deserializeAttachments}) is protected and merges, so we clear via
 *     the public {@code removeData} per currently-populated id and then invoke it reflectively
 *     (official mappings at runtime — the name is stable).</li>
 * <li><b>Persistent data</b> ({@code getPersistentData()}, saved under {@code NeoForgeData},
 *     e.g. Waystones): the live tag is public and mutable — plain NBT surgery, no reflection.</li>
 * <li><b>Tough As Nails</b> stores thirst/temperature in mixin fields serialized straight into
 *     the player NBT — not reachable via 1. or 2. Its API objects expose public
 *     {@code addAdditionalSaveData}/{@code readAdditionalSaveData}, used via cached reflection;
 *     fresh defaults come from instantiating a new data object. TAN re-syncs clients itself
 *     (tick diff).</li>
 * <li><b>{@link PlayerDataBridge}s</b> for mods that keep player data in their own storage, out of
 *     reach of 1. and 2. — Cosmetic Armor Reworked writes its own {@code .cosarmor} files, for
 *     instance. Each such mod needs its own integration; they are registered in
 *     {@link PlayerDataBridges}.</li>
 * </ol>
 *
 * <p>Which individual keys take part is decided per key by {@link PlayerDataConfig}, on top of the
 * coarse master switches in {@link Config}.</p>
 *
 * <p>Timing matters: mods sync their state to the client when the player joins the destination
 * level ({@code EntityJoinLevelEvent}, e.g. Curios), so apply must run BEFORE the teleport —
 * {@link PlayerStateManager} owns that sequencing. Any reflection failure disables only the
 * affected mechanism (logged once); a world switch must never crash on modded state.</p>
 */
public final class ModdedPlayerState {

    /** Snapshot sub-tag holding the serialized data attachments, keyed by attachment id. */
    public static final String KEY_ATTACHMENTS = "attachments";
    /** Snapshot sub-tag holding the player's persistent NBT, keyed by its top-level keys. */
    public static final String KEY_PERSISTENT = "persistentData";
    /** Snapshot sub-tag holding one compound per {@link PlayerDataBridge}, keyed by bridge id. */
    public static final String KEY_BRIDGES = "bridges";
    /**
     * Config key for the Tough As Nails integration. TAN keeps its legacy snapshot layout (see
     * {@code tan*} below) rather than moving into {@link #KEY_BRIDGES}, so existing saves keep
     * working — but it is listed and toggled like a bridge.
     */
    public static final String TAN_KEY = "toughasnails:player_data";
    private static final String KEY_TAN_THIRST = "tanThirst";
    private static final String KEY_TAN_TEMPERATURE = "tanTemperature";
    private static final String KEY_TAN_CLEMENCY = "tanClimateClemency";

    @Nullable
    private static Method deserializeAttachmentsMethod;
    private static boolean attachmentsBroken;

    private ModdedPlayerState() {
    }

    /** Fast path for callers: false when every mechanism is disabled. */
    public static boolean anyEnabled() {
        return Config.separateInventories()
                && (Config.swapModAttachments() || Config.swapPersistentData()
                        || (Config.swapToughAsNails() && TanHooks.available())
                        || PlayerDataBridges.anyAvailable());
    }

    /** True when Tough As Nails is installed and its API bound — used by the config catalog. */
    public static boolean tanAvailable() {
        return TanHooks.available();
    }

    /** A data attachment takes part unless excluded outright or marked global. */
    private static boolean swapAttachment(String key) {
        return !Config.attachmentExcludes().contains(key) && PlayerDataConfig.isPerWorld(key);
    }

    /** A persistent-NBT key takes part unless excluded outright or marked global. */
    private static boolean swapPersistentKey(String key) {
        return !Config.persistentDataExcludes().contains(key) && PlayerDataConfig.isPerWorld(key);
    }

    public static void capture(ServerPlayer player, CompoundTag out) {
        if (!Config.separateInventories()) {
            return;
        }
        if (Config.swapModAttachments() && !attachmentsBroken) {
            CompoundTag attachments = player.serializeAttachments(player.registryAccess());
            if (attachments != null) {
                for (String key : List.copyOf(attachments.getAllKeys())) {
                    if (!swapAttachment(key)) {
                        attachments.remove(key);
                    }
                }
                out.put(KEY_ATTACHMENTS, attachments);
            }
        }
        if (Config.swapPersistentData()) {
            CompoundTag persistent = player.getPersistentData().copy();
            for (String key : List.copyOf(persistent.getAllKeys())) {
                if (!swapPersistentKey(key)) {
                    persistent.remove(key);
                }
            }
            out.put(KEY_PERSISTENT, persistent);
        }
        if (Config.swapToughAsNails() && PlayerDataConfig.isPerWorld(TAN_KEY) && TanHooks.available()) {
            TanHooks.capture(player, out);
        }
        captureBridges(player, out);
    }

    /** Applies the modded portion of a stored snapshot (absent sub-tags reset to defaults). */
    public static void apply(ServerPlayer player, CompoundTag tag) {
        if (!Config.separateInventories()) {
            return;
        }
        if (Config.swapModAttachments() && !attachmentsBroken) {
            clearAttachments(player);
            CompoundTag stored = tag.getCompound(KEY_ATTACHMENTS);
            // Filter again on apply — the config may have changed since the snapshot was taken.
            for (String key : List.copyOf(stored.getAllKeys())) {
                if (!swapAttachment(key)) {
                    stored.remove(key);
                }
            }
            if (!stored.isEmpty()) {
                deserializeAttachments(player, stored);
            }
            // Curios initializes its inventory only at entity construction or lazily after a
            // pending deserialize (markDeserialized). Two cases after our swap:
            // - restored data: markDeserialized is set — Curios' own capability wrapper inits
            //   it on the next access (before its join sync). Calling reset() here TOO would
            //   init a second time, and init() starts with curios.clear() after the pending
            //   data was already consumed — wiping the just-restored items.
            // - fresh (cleared, nothing deserialized): the default CurioInventory would stay
            //   uninitialized forever (no slots in the GUI until relog) — reset() rebuilds the
            //   empty slot layout.
            if (!stored.contains(CuriosHooks.ATTACHMENT_ID)
                    && swapAttachment(CuriosHooks.ATTACHMENT_ID)
                    && CuriosHooks.available()) {
                CuriosHooks.resetInventory(player);
            }
        }
        if (Config.swapPersistentData()) {
            applyPersistentData(player, tag.getCompound(KEY_PERSISTENT));
        }
        if (Config.swapToughAsNails() && PlayerDataConfig.isPerWorld(TAN_KEY) && TanHooks.available()) {
            TanHooks.apply(player, tag);
        }
        applyBridges(player, tag);
    }

    /** First visit of a world group: modded state resets to defaults. */
    public static void applyFresh(ServerPlayer player) {
        apply(player, new CompoundTag());
    }

    // ------------------------------------------------------------------ attachments

    /** Removes all currently-populated serializable attachments except the excluded ones. */
    private static void clearAttachments(ServerPlayer player) {
        CompoundTag current = player.serializeAttachments(player.registryAccess());
        if (current == null) {
            return;
        }
        for (String key : current.getAllKeys()) {
            if (!swapAttachment(key)) {
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(key);
            AttachmentType<?> type = id != null ? NeoForgeRegistries.ATTACHMENT_TYPES.get(id) : null;
            if (type != null) {
                player.removeData(type);
            }
        }
    }

    private static void deserializeAttachments(ServerPlayer player, CompoundTag tag) {
        try {
            if (deserializeAttachmentsMethod == null) {
                Method method = AttachmentHolder.class.getDeclaredMethod("deserializeAttachments",
                        HolderLookup.Provider.class, CompoundTag.class);
                method.setAccessible(true);
                deserializeAttachmentsMethod = method;
            }
            deserializeAttachmentsMethod.invoke(player, player.registryAccess(), tag);
        } catch (ReflectiveOperationException e) {
            attachmentsBroken = true;
            WorldSwitcherMod.LOGGER.error(
                    "Cannot access AttachmentHolder.deserializeAttachments — disabling per-world "
                            + "attachment swapping for this session", e);
        }
    }

    // ------------------------------------------------------------------ persistent data

    private static void applyPersistentData(ServerPlayer player, CompoundTag stored) {
        CompoundTag live = player.getPersistentData();
        for (String key : List.copyOf(live.getAllKeys())) {
            if (swapPersistentKey(key)) {
                live.remove(key);
            }
        }
        for (String key : stored.getAllKeys()) {
            if (swapPersistentKey(key)) {
                live.put(key, stored.get(key).copy());
            }
        }
    }

    // ------------------------------------------------------------------ bridges

    private static void captureBridges(ServerPlayer player, CompoundTag out) {
        CompoundTag bridges = new CompoundTag();
        for (PlayerDataBridge bridge : PlayerDataBridges.all()) {
            if (!bridge.available() || !PlayerDataConfig.isPerWorld(bridge.id())) {
                continue;
            }
            CompoundTag captured = bridge.capture(player);
            if (captured != null) {
                bridges.put(bridge.id(), captured);
            }
        }
        if (!bridges.isEmpty()) {
            out.put(KEY_BRIDGES, bridges);
        }
    }

    private static void applyBridges(ServerPlayer player, CompoundTag tag) {
        CompoundTag bridges = tag.getCompound(KEY_BRIDGES);
        for (PlayerDataBridge bridge : PlayerDataBridges.all()) {
            if (!bridge.available() || !PlayerDataConfig.isPerWorld(bridge.id())) {
                continue;
            }
            // Absent = the player has never been in this world group: start fresh.
            bridge.apply(player, bridges.contains(bridge.id()) ? bridges.getCompound(bridge.id()) : null);
        }
    }

    // ------------------------------------------------------------------ Tough As Nails

    /**
     * Lazy reflective bridge into TAN's public API: {@code ITANPlayer.getThirstData()} /
     * {@code getTemperatureData()} / {@code get/setClimateClemencyGranted}, and the data
     * objects' public {@code addAdditionalSaveData}/{@code readAdditionalSaveData}. Fresh
     * defaults are produced by round-tripping a newly constructed data object — no TAN NBT
     * keys are hardcoded here.
     */
    private static final class TanHooks {

        private static boolean initialized;
        private static boolean available;

        private static Method getThirstData;
        private static Method getTemperatureData;
        private static Method getClemency;
        private static Method setClemency;
        private static Method thirstSave;
        private static Method thirstRead;
        private static Method temperatureSave;
        private static Method temperatureRead;
        private static java.lang.reflect.Constructor<?> thirstCtor;
        private static java.lang.reflect.Constructor<?> temperatureCtor;

        private TanHooks() {
        }

        static synchronized boolean available() {
            if (!initialized) {
                initialized = true;
                try {
                    Class<?> tanPlayer = Class.forName("toughasnails.api.player.ITANPlayer");
                    getThirstData = tanPlayer.getMethod("getThirstData");
                    getTemperatureData = tanPlayer.getMethod("getTemperatureData");
                    getClemency = tanPlayer.getMethod("getClimateClemencyGranted");
                    setClemency = tanPlayer.getMethod("setClimateClemencyGranted", boolean.class);
                    Class<?> thirstData = Class.forName("toughasnails.thirst.ThirstData");
                    thirstCtor = thirstData.getConstructor();
                    thirstSave = thirstData.getMethod("addAdditionalSaveData", CompoundTag.class);
                    thirstRead = thirstData.getMethod("readAdditionalSaveData", CompoundTag.class);
                    Class<?> temperatureData = Class.forName("toughasnails.temperature.TemperatureData");
                    temperatureCtor = temperatureData.getConstructor();
                    temperatureSave = temperatureData.getMethod("addAdditionalSaveData", CompoundTag.class);
                    temperatureRead = temperatureData.getMethod("readAdditionalSaveData", CompoundTag.class);
                    available = true;
                    WorldSwitcherMod.LOGGER.info("Tough As Nails detected — thirst/temperature "
                            + "join the per-world player state");
                } catch (ClassNotFoundException e) {
                    available = false; // TAN not installed — expected, stay quiet
                } catch (ReflectiveOperationException e) {
                    available = false;
                    WorldSwitcherMod.LOGGER.warn("Tough As Nails found but its API changed — "
                            + "per-world thirst/temperature disabled", e);
                }
            }
            return available;
        }

        static void capture(ServerPlayer player, CompoundTag out) {
            try {
                CompoundTag thirst = new CompoundTag();
                thirstSave.invoke(getThirstData.invoke(player), thirst);
                out.put(KEY_TAN_THIRST, thirst);
                CompoundTag temperature = new CompoundTag();
                temperatureSave.invoke(getTemperatureData.invoke(player), temperature);
                out.put(KEY_TAN_TEMPERATURE, temperature);
                out.putBoolean(KEY_TAN_CLEMENCY, (Boolean) getClemency.invoke(player));
            } catch (ReflectiveOperationException e) {
                disable(e);
            }
        }

        static void apply(ServerPlayer player, CompoundTag tag) {
            try {
                CompoundTag thirst = tag.contains(KEY_TAN_THIRST)
                        ? tag.getCompound(KEY_TAN_THIRST) : defaults(thirstCtor, thirstSave);
                thirstRead.invoke(getThirstData.invoke(player), thirst);
                CompoundTag temperature = tag.contains(KEY_TAN_TEMPERATURE)
                        ? tag.getCompound(KEY_TAN_TEMPERATURE) : defaults(temperatureCtor, temperatureSave);
                temperatureRead.invoke(getTemperatureData.invoke(player), temperature);
                if (tag.contains(KEY_TAN_CLEMENCY)) {
                    setClemency.invoke(player, tag.getBoolean(KEY_TAN_CLEMENCY));
                }
            } catch (ReflectiveOperationException e) {
                disable(e);
            }
        }

        private static CompoundTag defaults(java.lang.reflect.Constructor<?> ctor, Method save)
                throws ReflectiveOperationException {
            CompoundTag tag = new CompoundTag();
            save.invoke(ctor.newInstance(), tag);
            return tag;
        }

        private static void disable(ReflectiveOperationException e) {
            available = false;
            WorldSwitcherMod.LOGGER.error("Tough As Nails integration failed at runtime — "
                    + "per-world thirst/temperature disabled", e);
        }
    }

    /**
     * Reflective bridge into Curios' public API ({@code CuriosApi.getCuriosInventory} +
     * {@code ICuriosItemHandler.reset()}), used to re-initialize the curios inventory after an
     * attachment swap. Absent Curios = silently off.
     */
    private static final class CuriosHooks {

        static final String ATTACHMENT_ID = "curios:inventory";

        private static boolean initialized;
        private static boolean available;

        private static Method getCuriosInventory;
        private static Method reset;

        private CuriosHooks() {
        }

        static synchronized boolean available() {
            if (!initialized) {
                initialized = true;
                try {
                    Class<?> curiosApi = Class.forName("top.theillusivec4.curios.api.CuriosApi");
                    getCuriosInventory = curiosApi.getMethod("getCuriosInventory",
                            net.minecraft.world.entity.LivingEntity.class);
                    Class<?> handler = Class.forName(
                            "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
                    reset = handler.getMethod("reset");
                    available = true;
                    WorldSwitcherMod.LOGGER.info("Curios detected — re-initializing its slots "
                            + "after per-world attachment swaps");
                } catch (ClassNotFoundException e) {
                    available = false; // Curios not installed — expected
                } catch (ReflectiveOperationException e) {
                    available = false;
                    WorldSwitcherMod.LOGGER.warn("Curios found but its API changed — slot "
                            + "re-initialization after world switches disabled", e);
                }
            }
            return available;
        }

        static void resetInventory(ServerPlayer player) {
            try {
                java.util.Optional<?> handler =
                        (java.util.Optional<?>) getCuriosInventory.invoke(null, player);
                if (handler.isPresent()) {
                    reset.invoke(handler.get());
                }
            } catch (ReflectiveOperationException e) {
                available = false;
                WorldSwitcherMod.LOGGER.error("Curios reset failed at runtime — slot "
                        + "re-initialization after world switches disabled", e);
            }
        }
    }

    /** Merges the modded sub-tags of {@code from} into {@code into} (used on respawn/login). */
    public static void mergeModdedInto(CompoundTag from, CompoundTag into) {
        for (String key : new ArrayList<>(List.of(KEY_ATTACHMENTS, KEY_PERSISTENT, KEY_BRIDGES,
                KEY_TAN_THIRST, KEY_TAN_TEMPERATURE, KEY_TAN_CLEMENCY))) {
            if (from.contains(key)) {
                into.put(key, from.get(key).copy());
            } else {
                into.remove(key);
            }
        }
    }
}
