package net.geraldhofbauer.worldswitcher.player.data;

import net.geraldhofbauer.worldswitcher.player.data.bridge.CosArmorBridge;

import javax.annotation.Nullable;
import java.util.List;

/** The built-in {@link PlayerDataBridge}s. Add new mod integrations here. */
public final class PlayerDataBridges {

    private static final List<PlayerDataBridge> ALL = List.of(new CosArmorBridge());

    private PlayerDataBridges() {
    }

    /** Every registered bridge, whether or not its mod is installed. */
    public static List<PlayerDataBridge> all() {
        return ALL;
    }

    /** Only the bridges whose target mod is present and whose API bound successfully. */
    public static List<PlayerDataBridge> available() {
        return ALL.stream().filter(PlayerDataBridge::available).toList();
    }

    /** True when at least one bridge can do something — used to gate the whole mechanism. */
    public static boolean anyAvailable() {
        for (PlayerDataBridge bridge : ALL) {
            if (bridge.available()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static PlayerDataBridge byId(String id) {
        for (PlayerDataBridge bridge : ALL) {
            if (bridge.id().equals(id)) {
                return bridge;
            }
        }
        return null;
    }
}
