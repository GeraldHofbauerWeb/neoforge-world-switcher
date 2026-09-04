package net.geraldhofbauer.worldswitcher.player.data;

/**
 * Where a piece of modded player data lives. Every mechanism World Switcher can swap belongs to
 * exactly one of these — the generated config file is grouped by them.
 */
public enum PlayerDataSource {

    /** NeoForge data attachments, serialized into the player NBT under {@code neoforge:attachments}. */
    ATTACHMENT("Attachments (NeoForge data attachments)"),

    /** Top-level keys of {@code player.getPersistentData()}, saved as the {@code NeoForgeData} tag. */
    PERSISTENT("Persistent player NBT (the NeoForgeData tag)"),

    /** Mods that keep their own storage, reached through a World Switcher bridge. */
    BRIDGE("Bridges (mods that store player data themselves)");

    private final String title;

    PlayerDataSource(String title) {
        this.title = title;
    }

    /** Section heading used in the generated TOML and in command output. */
    public String title() {
        return title;
    }
}
