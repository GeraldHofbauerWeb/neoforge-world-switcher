package net.geraldhofbauer.worldswitcher.player;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player, per-world-group state snapshots, stored in
 * {@code world/data/worldswitcher_playerstate.dat} on the overworld.
 */
public class PlayerStateStore extends SavedData {

    private static final String DATA_NAME = "worldswitcher_playerstate";

    private static final class PlayerRecord {
        String currentGroup = "";
        final Map<String, CompoundTag> snapshots = new HashMap<>();
    }

    private final Map<UUID, PlayerRecord> players = new HashMap<>();

    public static PlayerStateStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PlayerStateStore::new, PlayerStateStore::load), DATA_NAME);
    }

    public static PlayerStateStore load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerStateStore store = new PlayerStateStore();
        CompoundTag playersTag = tag.getCompound("players");
        for (String uuidKey : playersTag.getAllKeys()) {
            CompoundTag recordTag = playersTag.getCompound(uuidKey);
            PlayerRecord record = new PlayerRecord();
            record.currentGroup = recordTag.getString("currentGroup");
            CompoundTag snapshotsTag = recordTag.getCompound("snapshots");
            for (String group : snapshotsTag.getAllKeys()) {
                record.snapshots.put(group, snapshotsTag.getCompound(group));
            }
            store.players.put(UUID.fromString(uuidKey), record);
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag playersTag = new CompoundTag();
        for (Map.Entry<UUID, PlayerRecord> entry : players.entrySet()) {
            CompoundTag recordTag = new CompoundTag();
            recordTag.putString("currentGroup", entry.getValue().currentGroup);
            CompoundTag snapshotsTag = new CompoundTag();
            entry.getValue().snapshots.forEach(snapshotsTag::put);
            recordTag.put("snapshots", snapshotsTag);
            playersTag.put(entry.getKey().toString(), recordTag);
        }
        tag.put("players", playersTag);
        return tag;
    }

    @Nullable
    public CompoundTag getSnapshot(UUID player, String group) {
        PlayerRecord record = players.get(player);
        return record != null ? record.snapshots.get(group) : null;
    }

    public void putSnapshot(UUID player, String group, CompoundTag snapshot) {
        players.computeIfAbsent(player, uuid -> new PlayerRecord()).snapshots.put(group, snapshot);
        setDirty();
    }

    /** Empty string = never tracked (treat as the group the player is currently in). */
    public String getCurrentGroup(UUID player) {
        PlayerRecord record = players.get(player);
        return record != null ? record.currentGroup : "";
    }

    public void setCurrentGroup(UUID player, String group) {
        players.computeIfAbsent(player, uuid -> new PlayerRecord()).currentGroup = group;
        setDirty();
    }

    /** Drops one player's snapshot for a group. Returns true if there was one. */
    public boolean removeSnapshot(UUID player, String group) {
        PlayerRecord record = players.get(player);
        if (record == null || record.snapshots.remove(group) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    /** The groups this player has a stored snapshot for, sorted for stable command output. */
    public java.util.List<String> groupsFor(UUID player) {
        PlayerRecord record = players.get(player);
        if (record == null) {
            return java.util.List.of();
        }
        java.util.List<String> groups = new java.util.ArrayList<>(record.snapshots.keySet());
        java.util.Collections.sort(groups);
        return groups;
    }

    /** Every player the store has ever tracked — including offline ones. */
    public java.util.Set<UUID> knownPlayers() {
        return java.util.Set.copyOf(players.keySet());
    }

    /** Drops all stored snapshots of a deleted world group. */
    public void removeGroup(String group) {
        boolean changed = false;
        for (PlayerRecord record : players.values()) {
            changed |= record.snapshots.remove(group) != null;
        }
        if (changed) {
            setDirty();
        }
    }
}
