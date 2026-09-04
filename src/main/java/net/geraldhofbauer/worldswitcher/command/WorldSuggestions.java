package net.geraldhofbauer.worldswitcher.command;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.geraldhofbauer.worldswitcher.world.WorldRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.ArrayList;
import java.util.List;

public final class WorldSuggestions {

    /** All registered world names plus the "default" group — for /ws and /wsc player tp. */
    public static final SuggestionProvider<CommandSourceStack> SWITCH_TARGETS = (context, builder) -> {
        List<String> names = new ArrayList<>();
        names.add(WorldRegistry.DEFAULT_GROUP);
        for (WorldRegistry.WorldEntry entry : WorldRegistry.get(context.getSource().getServer()).entries()) {
            names.add(entry.name());
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    /**
     * Like {@link #SWITCH_TARGETS}, but without the worlds the executing player may not enter —
     * for {@code /ws}. The admin commands keep using {@link #SWITCH_TARGETS} and see everything.
     */
    public static final SuggestionProvider<CommandSourceStack> ACCESSIBLE_WORLDS = (context, builder) -> {
        List<String> names = new ArrayList<>();
        names.add(WorldRegistry.DEFAULT_GROUP);
        var viewer = context.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                ? player : null;
        for (WorldRegistry.WorldEntry entry : WorldRegistry.get(context.getSource().getServer()).entries()) {
            if (viewer == null || WorldRegistry.mayEnter(viewer, entry)) {
                names.add(entry.name());
            }
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    /** All registered world names — for /wsc world info/unload/delete/rename. */
    public static final SuggestionProvider<CommandSourceStack> REGISTERED_WORLDS = (context, builder) ->
            SharedSuggestionProvider.suggest(
                    WorldRegistry.get(context.getSource().getServer()).entries().stream()
                            .map(WorldRegistry.WorldEntry::name),
                    builder);

    /** Only unloaded registered worlds — for /wsc world load. */
    public static final SuggestionProvider<CommandSourceStack> UNLOADED_WORLDS = (context, builder) ->
            SharedSuggestionProvider.suggest(
                    WorldRegistry.get(context.getSource().getServer()).entries().stream()
                            .filter(WorldRegistry.WorldEntry::unloaded)
                            .map(WorldRegistry.WorldEntry::name),
                    builder);

    private WorldSuggestions() {
    }
}
