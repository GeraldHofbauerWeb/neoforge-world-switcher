package net.geraldhofbauer.worldswitcher.player.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.geraldhofbauer.worldswitcher.player.ModdedPlayerState;
import net.geraldhofbauer.worldswitcher.player.PlayerStateStore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Discovers every piece of modded player data present on this server and describes it in human
 * terms, so the generated config file and {@code /wsc config playerdata scan} can name things rather
 * than just listing opaque ids.
 *
 * <p>What is discoverable, and what is not:</p>
 * <ul>
 * <li><b>Data attachments</b> are a real registry, so they can be enumerated in full. Whether one is
 *     serializable is not public API — it is read reflectively from {@code AttachmentType.serializer},
 *     with a graceful fallback to "assume yes".</li>
 * <li><b>Persistent-NBT keys</b> have no registry at all. They can only be observed, so they are
 *     collected from online players and from every stored snapshot (which covers offline ones).</li>
 * <li><b>Bridges</b> come from {@link PlayerDataBridges} — mods with their own storage cannot be
 *     found generically, each needs an integration.</li>
 * </ul>
 *
 * <p>Attachment types carry no display name, description or "this is an inventory" flag — the id is
 * all the game gives us. Descriptions therefore come from three fallbacks: a curated dictionary
 * bundled with the mod, the owning mod's display name via {@link ModList}, and a guess derived from
 * the shape of the stored NBT.</p>
 */
public final class PlayerDataCatalog {

    private static final String LABELS_RESOURCE = "/data/worldswitcher/playerdata_labels.json";

    @Nullable
    private static Map<String, Label> labels;
    @Nullable
    private static Field serializerField;
    private static boolean serializerFieldChecked;

    private record Label(@Nullable String mod, @Nullable String what) {
    }

    private PlayerDataCatalog() {
    }

    /**
     * Everything found on this server, grouped by source and sorted by id inside each group.
     * Safe to call at any time after server start.
     */
    public static List<PlayerDataKey> scan(MinecraftServer server) {
        Map<String, Tag> attachmentSamples = new TreeMap<>();
        Map<String, Tag> persistentSamples = new TreeMap<>();
        collectSamples(server, attachmentSamples, persistentSamples);

        List<PlayerDataKey> keys = new ArrayList<>();

        // Attachments: the registry is the full truth, samples only add the description.
        Map<String, Boolean> attachmentIds = new TreeMap<>();
        for (ResourceLocation id : NeoForgeRegistries.ATTACHMENT_TYPES.keySet()) {
            attachmentIds.put(id.toString(), serializable(NeoForgeRegistries.ATTACHMENT_TYPES.get(id)));
        }
        attachmentIds.forEach((id, usable) -> keys.add(new PlayerDataKey(
                id, PlayerDataSource.ATTACHMENT, namespaceOf(id), modName(namespaceOf(id)),
                describe(id, attachmentSamples.get(id)), usable, attachmentSamples.containsKey(id))));

        // Persistent NBT: observation only, and the key is all we have to guess an owner from.
        persistentSamples.forEach((key, sample) -> {
            String modId = guessModOf(key);
            keys.add(new PlayerDataKey(key, PlayerDataSource.PERSISTENT, modId, modName(modId),
                    describe(key, sample), true, true));
        });

        for (PlayerDataBridge bridge : PlayerDataBridges.all()) {
            keys.add(new PlayerDataKey(bridge.id(), PlayerDataSource.BRIDGE, bridge.modId(),
                    modName(bridge.modId()), bridge.description(), bridge.available(),
                    bridge.available()));
        }
        if (ModdedPlayerState.tanAvailable()) {
            String tanMod = ResourceLocation.tryParse(ModdedPlayerState.TAN_KEY) != null
                    ? namespaceOf(ModdedPlayerState.TAN_KEY) : null;
            keys.add(new PlayerDataKey(ModdedPlayerState.TAN_KEY, PlayerDataSource.BRIDGE, tanMod,
                    modName(tanMod), describe(ModdedPlayerState.TAN_KEY, null), true, true));
        }
        return keys;
    }

    /** Display name of a mod, or a readable fallback when it is not installed / has no namespace. */
    public static String modName(@Nullable String modId) {
        if (modId == null || modId.isEmpty()) {
            return "unknown mod";
        }
        if ("minecraft".equals(modId) || "neoforge".equals(modId)) {
            return "minecraft".equals(modId) ? "Minecraft" : "NeoForge";
        }
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(modId);
    }

    /**
     * Persistent-NBT keys carry no owner. Two conventions cover most of them: a namespaced key such
     * as {@code quark:trying_crawl}, and a key that is simply the mod id ({@code insanelib}).
     */
    @Nullable
    private static String guessModOf(String key) {
        if (key.indexOf(':') >= 0) {
            String namespace = key.substring(0, key.indexOf(':'));
            return ModList.get().isLoaded(namespace) ? namespace : null;
        }
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return ModList.get().isLoaded(lower) ? lower : null;
    }

    // ------------------------------------------------------------------ sampling

    private static void collectSamples(MinecraftServer server, Map<String, Tag> attachments,
                                       Map<String, Tag> persistent) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CompoundTag live = player.serializeAttachments(player.registryAccess());
            if (live != null) {
                for (String key : live.getAllKeys()) {
                    Tag value = live.get(key);
                    if (value != null) {
                        attachments.putIfAbsent(key, value);
                    }
                }
            }
            CompoundTag persistentTag = player.getPersistentData();
            for (String key : persistentTag.getAllKeys()) {
                Tag value = persistentTag.get(key);
                if (value != null) {
                    persistent.putIfAbsent(key, value);
                }
            }
        }
        // Stored snapshots cover players who are offline right now.
        PlayerStateStore store = PlayerStateStore.get(server);
        for (UUID player : store.knownPlayers()) {
            for (String group : store.groupsFor(player)) {
                CompoundTag snapshot = store.getSnapshot(player, group);
                if (snapshot == null) {
                    continue;
                }
                CompoundTag stored = snapshot.getCompound(ModdedPlayerState.KEY_ATTACHMENTS);
                for (String key : stored.getAllKeys()) {
                    Tag value = stored.get(key);
                    if (value != null) {
                        attachments.putIfAbsent(key, value);
                    }
                }
                CompoundTag storedPersistent = snapshot.getCompound(ModdedPlayerState.KEY_PERSISTENT);
                for (String key : storedPersistent.getAllKeys()) {
                    Tag value = storedPersistent.get(key);
                    if (value != null) {
                        persistent.putIfAbsent(key, value);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ description

    private static String describe(String id, @Nullable Tag sample) {
        Label label = labels().get(id);
        if (label != null && label.what() != null) {
            return label.what();
        }
        String guess = guessFromShape(sample);
        return guess != null ? guess : "unknown — no description available";
    }

    /**
     * Best-effort guess from the stored NBT: item stacks serialize as a compound with an {@code id}
     * and a count, so finding those is a strong hint that a key holds an inventory.
     */
    @Nullable
    private static String guessFromShape(@Nullable Tag sample) {
        if (sample == null) {
            return null;
        }
        int stacks = countItemStacks(sample, 0);
        if (stacks > 0) {
            return "looks like an inventory (" + stacks + " item stack" + (stacks == 1 ? "" : "s")
                    + " found)";
        }
        if (sample instanceof CompoundTag compound) {
            int size = compound.getAllKeys().size();
            return "unknown — a compound with " + size + " key" + (size == 1 ? "" : "s");
        }
        if (sample instanceof ListTag list) {
            return "unknown — a list with " + list.size() + " entr" + (list.size() == 1 ? "y" : "ies");
        }
        return "unknown — a single " + sample.getType().getName() + " value";
    }

    private static int countItemStacks(Tag tag, int depth) {
        if (depth > 6) {
            return 0;
        }
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("id", Tag.TAG_STRING)
                    && (compound.contains("count") || compound.contains("Count"))) {
                return 1;
            }
            int total = 0;
            for (String key : compound.getAllKeys()) {
                Tag child = compound.get(key);
                if (child != null) {
                    total += countItemStacks(child, depth + 1);
                }
            }
            return total;
        }
        if (tag instanceof ListTag list) {
            int total = 0;
            for (Tag child : list) {
                total += countItemStacks(child, depth + 1);
            }
            return total;
        }
        return 0;
    }

    private static synchronized Map<String, Label> labels() {
        if (labels == null) {
            Map<String, Label> parsed = new LinkedHashMap<>();
            try (InputStream stream = PlayerDataCatalog.class.getResourceAsStream(LABELS_RESOURCE)) {
                if (stream != null) {
                    JsonElement root = JsonParser.parseReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8));
                    if (root.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                            if (!entry.getValue().isJsonObject()) {
                                continue; // the "_comment" array
                            }
                            JsonObject value = entry.getValue().getAsJsonObject();
                            parsed.put(entry.getKey(), new Label(
                                    value.has("mod") ? value.get("mod").getAsString() : null,
                                    value.has("what") ? value.get("what").getAsString() : null));
                        }
                    }
                }
            } catch (Exception e) {
                WorldSwitcherMod.LOGGER.warn("Could not read the bundled player-data label list — "
                        + "config comments fall back to guesses", e);
            }
            labels = parsed;
        }
        return labels;
    }

    /** Mod display name for a key, preferring the curated dictionary for keys without a namespace. */
    public static String modNameFor(PlayerDataKey key) {
        Label label = labels().get(key.id());
        if (label != null && label.mod() != null) {
            return label.mod();
        }
        return key.modName();
    }

    // ------------------------------------------------------------------ attachment internals

    @Nullable
    private static String namespaceOf(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location != null ? location.getNamespace() : null;
    }

    /**
     * Whether an attachment type persists at all. {@code AttachmentType.serializer} is package
     * private with no accessor, so this reads it reflectively; if that ever stops working we assume
     * serializable, which only makes the generated file slightly noisier.
     */
    private static boolean serializable(@Nullable AttachmentType<?> type) {
        if (type == null) {
            return false;
        }
        if (!serializerFieldChecked) {
            serializerFieldChecked = true;
            try {
                Field field = AttachmentType.class.getDeclaredField("serializer");
                field.setAccessible(true);
                serializerField = field;
            } catch (ReflectiveOperationException | RuntimeException e) {
                WorldSwitcherMod.LOGGER.debug("Cannot inspect AttachmentType.serializer — listing "
                        + "all attachment types as swappable", e);
            }
        }
        if (serializerField == null) {
            return true;
        }
        try {
            return serializerField.get(type) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

}
