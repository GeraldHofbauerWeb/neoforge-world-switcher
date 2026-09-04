package net.geraldhofbauer.worldswitcher.world;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.geraldhofbauer.worldswitcher.Config;
import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.geraldhofbauer.worldswitcher.util.Messages;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Imports world save folders from the configured container folder ({@code worldsFolder}, root or
 * subpaths) by copying their overworld data into the server save under
 * {@code dimensions/worldswitcher/<id>/}. The source folder is never modified.
 */
public final class ImportService {

    /** Overworld data folders worth copying; everything else (DIM-1, playerdata, ...) is skipped. */
    private static final Set<String> COPY_FOLDERS = Set.of("region", "entities", "poi", "data");

    private static final ExecutorService COPY_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "worldswitcher-import");
        thread.setDaemon(true);
        return thread;
    });

    /** Folders under worldsFolder (depth ≤ 3) containing a level.dat or region/ folder. */
    public static final SuggestionProvider<CommandSourceStack> IMPORT_CANDIDATES = (context, builder) ->
            SharedSuggestionProvider.suggest(
                    listImportCandidates(context.getSource().getServer()).stream()
                            // subpaths contain '/', which an unquoted Brigadier string token
                            // cannot hold — suggest them pre-quoted
                            .map(candidate -> candidate.contains("/") ? "\"" + candidate + "\"" : candidate),
                    builder);

    private ImportService() {
    }

    public static Path containerFolder(MinecraftServer server) {
        Path configured = Path.of(Config.worldsFolder());
        Path root = server.getServerDirectory();
        return (configured.isAbsolute() ? configured : root.resolve(configured)).normalize();
    }

    public static List<String> listImportCandidates(MinecraftServer server) {
        Path container = containerFolder(server);
        if (!Files.isDirectory(container)) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(container, 3)) {
            walk.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve("level.dat"))
                            || Files.isDirectory(dir.resolve("region")))
                    .filter(dir -> !dir.equals(container))
                    .forEach(dir -> candidates.add(
                            container.relativize(dir).toString().replace('\\', '/')));
        } catch (IOException e) {
            WorldSwitcherMod.LOGGER.warn("Cannot scan worlds folder {}", container, e);
        }
        return candidates;
    }

    /**
     * Validates and imports. Feedback goes to {@code source}; the copy optionally runs on a
     * background thread and the world is registered + loaded on the server thread afterwards.
     */
    public static void importWorld(CommandSourceStack source, String sourceRelPath, String nameArg) {
        MinecraftServer server = source.getServer();
        Path container = containerFolder(server);
        Path sourceFolder = container.resolve(sourceRelPath).normalize();

        if (!sourceFolder.startsWith(container)) {
            source.sendFailure(Messages.error("Source path escapes the worlds folder: " + sourceRelPath));
            return;
        }
        if (!Files.isDirectory(sourceFolder)) {
            source.sendFailure(Messages.error("Not a folder: " + sourceFolder));
            return;
        }
        if (!Files.isRegularFile(sourceFolder.resolve("level.dat"))
                && !Files.isDirectory(sourceFolder.resolve("region"))) {
            source.sendFailure(Messages.error(
                    "No world found in " + sourceFolder + " (needs level.dat or region/)"));
            return;
        }

        String name = nameArg != null ? nameArg : sourceFolder.getFileName().toString();
        String id = slugify(name);
        if (!WorldRegistry.ID_PATTERN.matcher(id).matches()) {
            source.sendFailure(Messages.error("Cannot derive a valid world id from '" + name
                    + "' — use: /wsc world import \"" + sourceRelPath + "\" as <name>"));
            return;
        }
        WorldRegistry registry = WorldRegistry.get(server);
        if (registry.byId(id) != null || registry.nameTaken(name)) {
            source.sendFailure(Messages.error("A world with this name already exists: " + name));
            return;
        }

        ResourceKey<net.minecraft.world.level.Level> dimensionKey = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(WorldRegistry.NAMESPACE, id));
        Path targetFolder = DynamicDimensionManager.storageSource(server)
                .getDimensionPath(dimensionKey).normalize();
        if (Files.exists(targetFolder)) {
            source.sendFailure(Messages.error("Target folder already exists: " + targetFolder));
            return;
        }

        // A recently touched session.lock suggests the source world is open somewhere — region
        // files could be mid-write. Warn, but don't block; the source is only read.
        try {
            Path sessionLock = sourceFolder.resolve("session.lock");
            if (Files.exists(sessionLock) && Files.getLastModifiedTime(sessionLock).toInstant()
                    .isAfter(Instant.now().minus(10, ChronoUnit.MINUTES))) {
                source.sendSuccess(() -> Messages.error(
                        "Warning: source world seems to be in use (fresh session.lock) — "
                                + "the copy may be inconsistent. Stop the source server first."), false);
            }
        } catch (IOException ignored) {
            // best-effort warning only
        }

        LevelDatInfo levelDat = LevelDatInfo.read(sourceFolder);
        String relPath = container.relativize(sourceFolder).toString().replace('\\', '/');
        source.sendSuccess(() -> Messages.info("Importing '" + relPath + "' as '" + name + "'..."), false);

        Runnable copyAndRegister = () -> {
            try {
                copyOverworldData(sourceFolder, targetFolder);
            } catch (IOException e) {
                WorldSwitcherMod.LOGGER.error("Import copy failed for '{}'", name, e);
                server.execute(() -> source.sendFailure(
                        Messages.error("Import of '" + name + "' failed: " + e.getMessage())));
                return;
            }
            server.execute(() -> {
                WorldRegistry liveRegistry = WorldRegistry.get(server);
                WorldRegistry.WorldEntry entry = new WorldRegistry.WorldEntry(
                        id, name, levelDat.seed(), levelDat.spawnPos(), levelDat.spawnAngle(),
                        false, System.currentTimeMillis(), relPath);
                liveRegistry.put(entry);
                // The imported world keeps its own clock, rules and difficulty from its
                // level.dat (missing values fall back to the global ones at first load).
                liveRegistry.setImportedState(id, levelDat.dayTime(), levelDat.gameRules(),
                        levelDat.difficulty());
                DynamicDimensionManager.getOrCreateLevel(server, entry);
                source.sendSuccess(() -> Messages.success("Imported ")
                        .append(Messages.runCommand(name, "/ws " + name, ChatFormatting.AQUA))
                        .append(Messages.info(" (seed " + levelDat.seed() + ") — click to switch")), true);
            });
        };

        if (Config.importCopyAsync()) {
            COPY_EXECUTOR.submit(copyAndRegister);
        } else {
            copyAndRegister.run();
        }
    }

    private static void copyOverworldData(Path sourceFolder, Path targetFolder) throws IOException {
        Files.createDirectories(targetFolder);
        for (String folderName : COPY_FOLDERS) {
            Path sourceSub = sourceFolder.resolve(folderName);
            if (!Files.isDirectory(sourceSub)) {
                continue;
            }
            Path targetSub = targetFolder.resolve(folderName);
            Files.walkFileTree(sourceSub, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(targetSub.resolve(sourceSub.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, targetSub.resolve(sourceSub.relativize(file)),
                            StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    public static void shutdown() {
        COPY_EXECUTOR.shutdown();
        try {
            if (!COPY_EXECUTOR.awaitTermination(30, TimeUnit.SECONDS)) {
                WorldSwitcherMod.LOGGER.warn("Import copy still running during server stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String slugify(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }
}
