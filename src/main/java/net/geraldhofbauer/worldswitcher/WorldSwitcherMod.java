package net.geraldhofbauer.worldswitcher;

import net.geraldhofbauer.worldswitcher.command.GameRuleHelper;
import net.geraldhofbauer.worldswitcher.command.WsCommand;
import net.geraldhofbauer.worldswitcher.command.WscCommand;
import net.geraldhofbauer.worldswitcher.world.DynamicDimensionManager;
import net.geraldhofbauer.worldswitcher.world.DynamicServerLevel;
import net.geraldhofbauer.worldswitcher.world.ImportService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side-only Multiverse-style world manager.
 *
 * <p>Registers no items, blocks, network payloads or synced registry content, so vanilla
 * 1.21.1 clients can join a server running this mod without having it installed.</p>
 */
@Mod(WorldSwitcherMod.MODID)
public class WorldSwitcherMod {
    public static final String MODID = "worldswitcher";
    public static final Logger LOGGER = LoggerFactory.getLogger(WorldSwitcherMod.class);

    public WorldSwitcherMod(IEventBus modEventBus, ModContainer modContainer) {
        // SERVER config: lives in <save>/serverconfig/, options are per-world-save
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);

        if (Boolean.getBoolean(net.geraldhofbauer.worldswitcher.command.E2eTestHook.PROPERTY)) {
            net.geraldhofbauer.worldswitcher.command.E2eTestHook.register(modEventBus);
        }

        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(net.geraldhofbauer.worldswitcher.player.PlayerStateManager.EVENTS);
        NeoForge.EVENT_BUS.register(net.geraldhofbauer.worldswitcher.hooks.CommandHookService.EVENTS);

        LOGGER.info("World Switcher initialized");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        WsCommand.register(event.getDispatcher());
        WscCommand.register(event.getDispatcher());
        // Hybrid /gamerule, /time and /weather: per-world inside managed worlds, vanilla
        // behavior everywhere else (the config gate sits inside the executors).
        GameRuleHelper.registerOverrides(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        DynamicDimensionManager.loadPersistedWorlds(event.getServer());
        net.geraldhofbauer.worldswitcher.hooks.CommandHookService.load(event.getServer());
        // After the registries are frozen, so the attachment types are complete.
        net.geraldhofbauer.worldswitcher.player.data.PlayerDataConfig.load(event.getServer());
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof DynamicServerLevel level) {
            DynamicDimensionManager.tickTime(level);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ImportService.shutdown();
    }
}
