package net.geraldhofbauer.worldswitcher.player.data;

import javax.annotation.Nullable;

/**
 * One discovered piece of modded player data, as shown in {@code /wsc config playerdata scan} and
 * written as a commented entry into {@code worldswitcher-playerdata.toml}.
 *
 * @param id          the key as it appears in the config file — an attachment id
 *                    ({@code curios:inventory}), a persistent-NBT key ({@code WaystonesData}) or a
 *                    bridge id ({@code cosmeticarmorreworked:cosarmor})
 * @param source      which mechanism it belongs to
 * @param modId       owning mod id when derivable (attachment/bridge namespace), else null
 * @param modName     display name of the owning mod, or a best-effort fallback
 * @param description one short line of what it holds; may be a heuristic guess
 * @param usable      false for an attachment that cannot be serialized, or a bridge whose mod is
 *                    absent — listed for transparency, but toggling it does nothing
 * @param seen        true when it was actually observed on a player or in a stored snapshot
 */
public record PlayerDataKey(String id, PlayerDataSource source, @Nullable String modId,
                            String modName, String description, boolean usable, boolean seen) {
}
