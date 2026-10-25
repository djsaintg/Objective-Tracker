package dev.objectivetracker.guide;

import com.google.gson.Gson;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import dev.objectivetracker.waypoint.BlockFinder;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Loads a guide from {@code <plugin data dir>/guides/<name>.json} if present (so server owners can
 * edit it without rebuilding), otherwise from the copy bundled in the jar.
 */
public final class GuideLoader {
    private static final Gson GSON = new Gson();

    private GuideLoader() {
    }

    public static Guide load(Path dataDirectory, String name, HytaleLogger logger) throws IOException {
        Path override = dataDirectory.resolve("guides").resolve(name + ".json");
        Guide guide;
        if (Files.isRegularFile(override)) {
            try (Reader reader = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                guide = GSON.fromJson(reader, Guide.class);
            }
            logger.atInfo().log("Loaded guide '%s' from %s", name, override);
        } else {
            String resource = "/guides/" + name + ".json";
            try (InputStream in = GuideLoader.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("Bundled guide not found: " + resource);
                }
                guide = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Guide.class);
            }
        }
        flatten(guide);
        logger.atInfo().log("Guide '%s' has %d steps in %d chapters", guide.title, guide.steps.size(), guide.chapters.size());
        return guide;
    }

    /**
     * Writes the bundled guide to {@code guides/<name>.default.json} as a reference to copy from.
     * It is refreshed on every start so it always matches the installed version; only a file the
     * owner creates as {@code <name>.json} overrides the bundled guide.
     */
    public static void exportDefault(Path dataDirectory, String name) throws IOException {
        Path target = dataDirectory.resolve("guides").resolve(name + ".default.json");
        Files.createDirectories(target.getParent());
        try (InputStream in = GuideLoader.class.getResourceAsStream("/guides/" + name + ".json")) {
            if (in != null) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Logs any item ids in the guide that the running server does not know about. */
    public static void validateItems(Guide guide, HytaleLogger logger) {
        Set<String> unknown = new HashSet<>();
        for (GuideStep step : guide.steps) {
            for (Objective o : step.objectives) {
                checkItems(o, unknown);
            }
            for (Objective o : step.skipIf) {
                checkItems(o, unknown);
            }
        }
        if (!unknown.isEmpty()) {
            logger.atWarning().log("Guide references unknown item ids (those objectives can never complete): %s", unknown);
        }
    }

    /** Warns about waypoint patterns that match no block type (their waypoint would never show). */
    public static void validateBlocks(Guide guide, BlockFinder finder, HytaleLogger logger) {
        Set<String> unmatched = new TreeSet<>();
        for (GuideStep step : guide.steps) {
            checkBlocks(step.find, finder, unmatched);
            for (Objective o : step.objectives) {
                checkBlocks(o.find, finder, unmatched);
            }
        }
        if (!unmatched.isEmpty()) {
            logger.atWarning().log("Guide waypoint patterns match no block type: %s", unmatched);
        }
    }

    private static void checkBlocks(List<String> patterns, BlockFinder finder, Set<String> unmatched) {
        if (patterns == null) {
            return;
        }
        for (String pattern : patterns) {
            if (finder.resolve(List.of(pattern)).isEmpty()) {
                unmatched.add(pattern);
            }
        }
    }

    private static void checkItems(Objective objective, Set<String> unknown) {
        if (objective.kind() != Objective.Type.ITEM) {
            return;
        }
        for (String id : objective.itemIds()) {
            if (Item.getAssetMap().getAsset(id) == null) {
                unknown.add(id);
            }
        }
    }

    private static void flatten(Guide guide) {
        guide.steps.clear();
        Set<String> seen = new HashSet<>();
        for (Guide.Chapter chapter : guide.chapters) {
            for (GuideStep step : chapter.steps) {
                if (step.id == null || !seen.add(step.id)) {
                    throw new IllegalStateException("Guide step id missing or duplicated: " + step.id);
                }
                if (step.objectives == null) {
                    step.objectives = java.util.List.of();
                }
                if (step.skipIf == null) {
                    step.skipIf = java.util.List.of();
                }
                step.chapterTitle = chapter.title;
                step.index = guide.steps.size();
                guide.steps.add(step);
            }
        }
    }
}
