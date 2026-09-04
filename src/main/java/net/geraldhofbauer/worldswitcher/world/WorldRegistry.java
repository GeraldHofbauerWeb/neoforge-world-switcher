package net.geraldhofbauer.worldswitcher.world;

import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Persistent registry of all managed worlds, stored in {@code world/data/worldswitcher_worlds.dat}.
 *
 * <p>Each world has a fixed {@code id} (dimension key {@code worldswitcher:<id>}, inventory group)
 * and a user-facing {@code name} that can be renamed freely without touching stored player state
 * or bed spawns.</p>
 */
public class WorldRegistry extends SavedData {

    public static final String NAMESPACE = WorldSwitcherMod.MODID;
    public static final String DEFAULT_GROUP = "default";
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");

    private static final String DATA_NAME = "worldswitcher_worlds";

    /** id → entry, insertion-ordered for stable /wsc world list output. */
    private final Map<String, WorldEntry> entries = new LinkedHashMap<>();

    public static final class WorldEntry {
        private final String id;
        private String name;
        private long seed;
        @Nullable
        private BlockPos spawnPos;
        private float spawnAngle;
        private boolean unloaded;
        private final long importedAt;
        private final String sourcePath;
        /** Per-world game rules snapshot; null = not owned yet (inherits global on first load). */
        @Nullable
        private CompoundTag gameRules;
        /** Per-world day time; -1 = not owned yet (inherits the overworld's on first load). */
        private long dayTime = -1L;
        private int clearWeatherTime;
        private int rainTime;
        private int thunderTime;
        private boolean raining;
        private boolean thundering;
        /** Per-world difficulty; null = not owned yet (inherits the global one on first load). */
        @Nullable
        private net.minecraft.world.Difficulty difficulty;
        /**
         * Inventory group this world belongs to; empty = its own {@link #id}. Worlds sharing a group
         * share one per-player state ("auto-sync"). The reserved group {@value #DEFAULT_GROUP} is the
         * vanilla dimensions' group — a world put there keeps the player's default-world items
         * (what {@code shareDefaultInventory} meant before 1.5.0). Game rules, time, weather and
         * difficulty stay per-world regardless of the group.
         */
        private String inventoryGroup = "";
        /**
         * Game mode this world hands out; null = it has no opinion (the player keeps whatever mode
         * they had, which is what every world did before 1.6.0). Applied on the first visit of the
         * world's inventory group, or on every entry when {@link #forceGameMode} is set.
         */
        @Nullable
        private net.minecraft.world.level.GameType defaultGameMode;
        /** Whether {@link #defaultGameMode} is re-applied on every entry, not just the first. */
        private boolean forceGameMode;
        /**
         * Minimum permission level a player needs to enter this world; 0 = everyone. Checked for
         * {@code /ws}, for portals that lead into the world and at login — but not for
         * {@code /wsc player tp}, which is an admin action and deliberately bypasses it.
         */
        private int requiredPermissionLevel;
        /** Live level data while the world is loaded — the registry serializes from it. */
        @Nullable
        private PerWorldLevelData liveData;

        public WorldEntry(String id, String name, long seed, @Nullable BlockPos spawnPos, float spawnAngle,
                          boolean unloaded, long importedAt, String sourcePath) {
            this.id = id;
            this.name = name;
            this.seed = seed;
            this.spawnPos = spawnPos;
            this.spawnAngle = spawnAngle;
            this.unloaded = unloaded;
            this.importedAt = importedAt;
            this.sourcePath = sourcePath;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public long seed() {
            return seed;
        }

        @Nullable
        public BlockPos spawnPos() {
            return spawnPos;
        }

        public float spawnAngle() {
            return spawnAngle;
        }

        public boolean unloaded() {
            return unloaded;
        }

        public long importedAt() {
            return importedAt;
        }

        public String sourcePath() {
            return sourcePath;
        }

        @Nullable
        public CompoundTag gameRules() {
            return gameRules;
        }

        public long dayTime() {
            return dayTime;
        }

        public int clearWeatherTime() {
            return clearWeatherTime;
        }

        public int rainTime() {
            return rainTime;
        }

        public int thunderTime() {
            return thunderTime;
        }

        public boolean raining() {
            return raining;
        }

        public boolean thundering() {
            return thundering;
        }

        @Nullable
        public net.minecraft.world.Difficulty difficulty() {
            return difficulty;
        }

        /** The inventory group this world belongs to — its own id unless it was grouped elsewhere. */
        public String inventoryGroup() {
            return inventoryGroup.isEmpty() ? id : inventoryGroup;
        }

        /** True if this world was put into a group other than its own. */
        public boolean grouped() {
            return !inventoryGroup.isEmpty() && !inventoryGroup.equals(id);
        }

        /** The game mode this world hands out, or null when it has no opinion. */
        @Nullable
        public net.minecraft.world.level.GameType defaultGameMode() {
            return defaultGameMode;
        }

        /** True when {@link #defaultGameMode()} is re-applied on every entry, not just the first. */
        public boolean forceGameMode() {
            return forceGameMode;
        }

        /** Minimum permission level needed to enter; 0 = everyone. */
        public int requiredPermissionLevel() {
            return requiredPermissionLevel;
        }

        /** True if this world shares the {@value #DEFAULT_GROUP} inventory group (keep-inventory). */
        public boolean sharesDefaultInventory() {
            return DEFAULT_GROUP.equals(inventoryGroup());
        }

        void attachLiveData(PerWorldLevelData data) {
            this.liveData = data;
        }

        /** Flush + detach on unload, so the stored values are what the next load starts from. */
        void detachLiveData() {
            flushLiveState();
            this.liveData = null;
        }

        /** Copies the live level's rules/time/weather into the persisted fields. */
        void flushLiveState() {
            PerWorldLevelData data = liveData;
            if (data == null) {
                return;
            }
            if (data.ownGameRules()) {
                gameRules = data.getGameRules().createTag();
            }
            if (data.ownDifficulty()) {
                difficulty = data.getDifficulty();
            }
            if (data.ownTimeAndWeather()) {
                dayTime = data.getDayTime();
                clearWeatherTime = data.getClearWeatherTime();
                rainTime = data.getRainTime();
                thunderTime = data.getThunderTime();
                raining = data.isRaining();
                thundering = data.isThundering();
            }
        }

        public ResourceKey<Level> dimensionKey() {
            return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(NAMESPACE, id));
        }
    }

    public static WorldRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WorldRegistry::new, WorldRegistry::load), DATA_NAME);
    }

    public static WorldRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldRegistry registry = new WorldRegistry();
        ListTag list = tag.getList("worlds", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            BlockPos spawn = entryTag.contains("spawnX")
                    ? new BlockPos(entryTag.getInt("spawnX"), entryTag.getInt("spawnY"), entryTag.getInt("spawnZ"))
                    : null;
            WorldEntry entry = new WorldEntry(
                    entryTag.getString("id"),
                    entryTag.getString("name"),
                    entryTag.getLong("seed"),
                    spawn,
                    entryTag.getFloat("spawnAngle"),
                    entryTag.getBoolean("unloaded"),
                    entryTag.getLong("importedAt"),
                    entryTag.getString("sourcePath"));
            if (entryTag.contains("gameRules", Tag.TAG_COMPOUND)) {
                entry.gameRules = entryTag.getCompound("gameRules");
            }
            entry.dayTime = entryTag.contains("dayTime") ? entryTag.getLong("dayTime") : -1L;
            entry.clearWeatherTime = entryTag.getInt("clearWeatherTime");
            entry.rainTime = entryTag.getInt("rainTime");
            entry.thunderTime = entryTag.getInt("thunderTime");
            entry.raining = entryTag.getBoolean("raining");
            entry.thundering = entryTag.getBoolean("thundering");
            if (entryTag.contains("difficulty")) {
                entry.difficulty = net.minecraft.world.Difficulty.byName(entryTag.getString("difficulty"));
            }
            if (entryTag.contains("inventoryGroup", Tag.TAG_STRING)) {
                entry.inventoryGroup = entryTag.getString("inventoryGroup");
            } else if (entryTag.getBoolean("shareDefaultInventory")) {
                // Pre-1.5.0 saves only knew "shares the default group or not".
                entry.inventoryGroup = DEFAULT_GROUP;
            }
            if (entryTag.contains("defaultGameMode", Tag.TAG_STRING)) {
                entry.defaultGameMode = net.minecraft.world.level.GameType
                        .byName(entryTag.getString("defaultGameMode"), null);
            }
            entry.forceGameMode = entryTag.getBoolean("forceGameMode");
            entry.requiredPermissionLevel = entryTag.getInt("requiredPermissionLevel");
            registry.entries.put(entry.id(), entry);
        }
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (WorldEntry entry : entries.values()) {
            entry.flushLiveState();
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("id", entry.id);
            entryTag.putString("name", entry.name);
            entryTag.putLong("seed", entry.seed);
            if (entry.spawnPos != null) {
                entryTag.putInt("spawnX", entry.spawnPos.getX());
                entryTag.putInt("spawnY", entry.spawnPos.getY());
                entryTag.putInt("spawnZ", entry.spawnPos.getZ());
            }
            entryTag.putFloat("spawnAngle", entry.spawnAngle);
            entryTag.putBoolean("unloaded", entry.unloaded);
            entryTag.putLong("importedAt", entry.importedAt);
            entryTag.putString("sourcePath", entry.sourcePath);
            if (entry.gameRules != null) {
                entryTag.put("gameRules", entry.gameRules.copy());
            }
            entryTag.putLong("dayTime", entry.dayTime);
            entryTag.putInt("clearWeatherTime", entry.clearWeatherTime);
            entryTag.putInt("rainTime", entry.rainTime);
            entryTag.putInt("thunderTime", entry.thunderTime);
            entryTag.putBoolean("raining", entry.raining);
            entryTag.putBoolean("thundering", entry.thundering);
            if (entry.difficulty != null) {
                entryTag.putString("difficulty", entry.difficulty.getKey());
            }
            entryTag.putString("inventoryGroup", entry.inventoryGroup);
            if (entry.defaultGameMode != null) {
                entryTag.putString("defaultGameMode", entry.defaultGameMode.getName());
            }
            entryTag.putBoolean("forceGameMode", entry.forceGameMode);
            entryTag.putInt("requiredPermissionLevel", entry.requiredPermissionLevel);
            // Kept for 1.4.x compatibility: an older build still understands this flag.
            entryTag.putBoolean("shareDefaultInventory", entry.sharesDefaultInventory());
            list.add(entryTag);
        }
        tag.put("worlds", list);
        return tag;
    }

    public Collection<WorldEntry> entries() {
        return entries.values();
    }

    @Nullable
    public WorldEntry byId(String id) {
        return entries.get(id);
    }

    /** Resolves a user-facing name (falls back to id lookup so both always work). */
    @Nullable
    public WorldEntry byName(String name) {
        for (WorldEntry entry : entries.values()) {
            if (entry.name.equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return entries.get(name);
    }

    public boolean nameTaken(String name) {
        return byName(name) != null;
    }

    public void put(WorldEntry entry) {
        entries.put(entry.id(), entry);
        setDirty();
    }

    public void remove(String id) {
        if (entries.remove(id) != null) {
            setDirty();
        }
    }

    public void rename(String id, String newName) {
        WorldEntry entry = entries.get(id);
        if (entry != null) {
            entry.name = newName;
            setDirty();
        }
    }

    public void setUnloaded(String id, boolean unloaded) {
        WorldEntry entry = entries.get(id);
        if (entry != null && entry.unloaded != unloaded) {
            entry.unloaded = unloaded;
            setDirty();
        }
    }

    public void setGameRules(String id, CompoundTag rulesTag) {
        WorldEntry entry = entries.get(id);
        if (entry != null) {
            entry.gameRules = rulesTag;
            setDirty();
        }
    }

    /** Seeds time, rules + difficulty from an imported world's level.dat (before first load). */
    public void setImportedState(String id, long dayTime, @Nullable CompoundTag rulesTag,
                                 @Nullable net.minecraft.world.Difficulty difficulty) {
        WorldEntry entry = entries.get(id);
        if (entry != null) {
            entry.dayTime = dayTime;
            entry.gameRules = rulesTag;
            entry.difficulty = difficulty;
            setDirty();
        }
    }

    public void setDifficulty(String id, net.minecraft.world.Difficulty difficulty) {
        WorldEntry entry = entries.get(id);
        if (entry != null) {
            entry.difficulty = difficulty;
            setDirty();
        }
    }

    /**
     * Puts a world into an inventory group. Pass {@code null} or the world's own id to give it its
     * own group again. Returns true if the group actually changed.
     */
    public boolean setInventoryGroup(String id, @Nullable String group) {
        WorldEntry entry = entries.get(id);
        if (entry == null) {
            return false;
        }
        String normalized = group == null || group.equals(id) ? "" : group;
        if (entry.inventoryGroup.equals(normalized)) {
            return false;
        }
        entry.inventoryGroup = normalized;
        setDirty();
        return true;
    }

    /**
     * Sets a world's game-mode policy. {@code mode == null} clears it (the world stops having an
     * opinion); {@code forced} re-applies it on every entry instead of only the first visit.
     */
    public void setGameMode(String id, @Nullable net.minecraft.world.level.GameType mode, boolean forced) {
        WorldEntry entry = entries.get(id);
        if (entry != null) {
            entry.defaultGameMode = mode;
            entry.forceGameMode = mode != null && forced;
            setDirty();
        }
    }

    /** Sets the minimum permission level needed to enter a world; 0 = everyone. */
    public void setRequiredPermissionLevel(String id, int level) {
        WorldEntry entry = entries.get(id);
        if (entry != null && entry.requiredPermissionLevel != level) {
            entry.requiredPermissionLevel = level;
            setDirty();
        }
    }

    /**
     * Whether a player may enter a world. Vanilla dimensions are never restricted; a missing entry
     * means the world is gone, which the callers report separately.
     */
    public static boolean mayEnter(net.minecraft.server.level.ServerPlayer player, WorldEntry entry) {
        return entry.requiredPermissionLevel() <= 0 || player.hasPermissions(entry.requiredPermissionLevel());
    }

    /** Whether a player may enter the world backing a dimension (vanilla dimensions: always). */
    public static boolean mayEnter(net.minecraft.server.level.ServerPlayer player,
                                   MinecraftServer server, ResourceKey<Level> dimension) {
        String group = groupOf(dimension);
        if (DEFAULT_GROUP.equals(group)) {
            return true;
        }
        WorldEntry entry = get(server).byId(group);
        return entry == null || mayEnter(player, entry);
    }

    /** Toggles whether a world shares the default inventory group. Returns true if it changed. */
    public boolean setShareDefaultInventory(String id, boolean share) {
        return setInventoryGroup(id, share ? DEFAULT_GROUP : null);
    }

    /**
     * All inventory groups in use, group id → member worlds, insertion-ordered. The
     * {@value #DEFAULT_GROUP} group is always present (it holds the vanilla dimensions) even when no
     * managed world was put into it.
     */
    public Map<String, java.util.List<WorldEntry>> groups() {
        Map<String, java.util.List<WorldEntry>> byGroup = new LinkedHashMap<>();
        byGroup.put(DEFAULT_GROUP, new java.util.ArrayList<>());
        for (WorldEntry entry : entries.values()) {
            byGroup.computeIfAbsent(entry.inventoryGroup(), key -> new java.util.ArrayList<>()).add(entry);
        }
        return byGroup;
    }

    public void setSpawn(String id, BlockPos pos, float angle) {
        WorldEntry entry = entries.get(id);
        if (entry != null) {
            entry.spawnPos = pos;
            entry.spawnAngle = angle;
            setDirty();
        }
    }

    /**
     * Inventory group of a dimension: all vanilla dimensions share the {@value #DEFAULT_GROUP}
     * group, every managed world is its own group (keyed by its fixed id).
     */
    public static String groupOf(ResourceKey<Level> dimension) {
        ResourceLocation location = dimension.location();
        return NAMESPACE.equals(location.getNamespace()) ? location.getPath() : DEFAULT_GROUP;
    }

    /**
     * Inventory group of a dimension, honouring the per-world {@link WorldEntry#inventoryGroup()}:
     * a world put into another group reports that group, so every world in it shares one per-player
     * state. Unlike {@link #groupOf}, this is only about player-state grouping — game rules,
     * time, weather and difficulty stay keyed by the world's own id.
     */
    public static String inventoryGroupOf(MinecraftServer server, ResourceKey<Level> dimension) {
        String group = groupOf(dimension);
        if (DEFAULT_GROUP.equals(group)) {
            return group;
        }
        WorldEntry entry = get(server).byId(group);
        return entry != null ? entry.inventoryGroup() : group;
    }
}
