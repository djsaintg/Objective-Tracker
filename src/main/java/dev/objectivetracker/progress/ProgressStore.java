package dev.objectivetracker.progress;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Loads and saves {@link PlayerProgress} as {@code <data dir>/progress/<uuid>.json}. */
public final class ProgressStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path directory;
    private final HytaleLogger logger;
    private final Map<UUID, PlayerProgress> cache = new ConcurrentHashMap<>();

    public ProgressStore(Path dataDirectory, HytaleLogger logger) {
        this.directory = dataDirectory.resolve("progress");
        this.logger = logger;
    }

    public PlayerProgress get(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::read);
    }

    public void save(UUID uuid) {
        PlayerProgress progress = cache.get(uuid);
        if (progress != null && progress.dirty) {
            write(uuid, progress);
        }
    }

    public void saveAll() {
        cache.forEach((uuid, progress) -> {
            if (progress.dirty) {
                write(uuid, progress);
            }
        });
    }

    /** Saves and forgets a player's progress (on disconnect). */
    public void unload(UUID uuid) {
        save(uuid);
        cache.remove(uuid);
    }

    private PlayerProgress read(UUID uuid) {
        Path file = directory.resolve(uuid + ".json");
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                PlayerProgress progress = GSON.fromJson(reader, PlayerProgress.class);
                if (progress != null) {
                    return progress;
                }
            } catch (IOException | RuntimeException e) {
                logger.atWarning().withCause(e).log("Could not read guide progress for %s, starting fresh", uuid);
            }
        }
        return new PlayerProgress();
    }

    private synchronized void write(UUID uuid, PlayerProgress progress) {
        try {
            Files.createDirectories(directory);
            Path tmp = directory.resolve(uuid + ".json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(progress, writer);
            }
            Files.move(tmp, directory.resolve(uuid + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            progress.dirty = false;
        } catch (IOException e) {
            logger.atWarning().withCause(e).log("Could not save guide progress for %s", uuid);
        }
    }
}
