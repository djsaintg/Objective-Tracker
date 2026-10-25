package dev.objectivetracker.waypoint;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Finds the nearest block of a set of types around a position. Only sections that are already
 * loaded are looked at (nothing is loaded or generated), and each section's palette is checked
 * first, so sections without a match cost almost nothing.
 */
public final class BlockFinder {
    /** Horizontal search radius in 32-block sections (2 = up to ~80 blocks away). */
    private static final int RADIUS_SECTIONS = 2;
    /** Vertical search radius in sections. */
    private static final int VERTICAL_SECTIONS = 3;

    private final Map<List<String>, IntList> resolved = new ConcurrentHashMap<>();

    /**
     * Block-type indexes matching any of {@code patterns}. A pattern is a block id where {@code *}
     * matches anything (e.g. {@code Ore_Copper*}); matching ignores case and includes block states.
     */
    public IntList resolve(List<String> patterns) {
        return resolved.computeIfAbsent(List.copyOf(patterns), BlockFinder::compute);
    }

    private static IntList compute(List<String> patterns) {
        List<Pattern> regexes = patterns.stream().map(BlockFinder::toRegex).toList();
        var assetMap = BlockType.getAssetMap();
        IntArrayList ids = new IntArrayList();
        for (String key : assetMap.getAssetMap().keySet()) {
            // State variants are keyed "*<id>_State_..."
            String name = key.startsWith("*") ? key.substring(1) : key;
            if (regexes.stream().anyMatch(r -> r.matcher(name).matches())) {
                int index = assetMap.getIndex(key);
                if (index >= 0 && !ids.contains(index)) {
                    ids.add(index);
                }
            }
        }
        return ids;
    }

    private static Pattern toRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (String part : glob.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        // Block states (a lit furnace, an upgraded bench) are separate types named "<id>_State_...".
        regex.append("(_State_.*)?");
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    /** The nearest matching block to (x, y, z), or null if none is loaded within range. */
    public static Hit nearest(World world, double x, double y, double z, IntList ids) {
        if (ids.isEmpty()) {
            return null;
        }
        ChunkStore chunks = world.getChunkStore();
        Store<ChunkStore> store = chunks.getStore();
        int sx = ChunkUtil.chunkCoordinate(x);
        int sy = ChunkUtil.chunkCoordinate(y);
        int sz = ChunkUtil.chunkCoordinate(z);

        // Visit sections closest-first so the search can stop as soon as no remaining section
        // could hold anything nearer than the best hit (common blocks like stone end almost at once).
        List<Candidate> candidates = new ArrayList<>();
        for (int cx = sx - RADIUS_SECTIONS; cx <= sx + RADIUS_SECTIONS; cx++) {
            for (int cz = sz - RADIUS_SECTIONS; cz <= sz + RADIUS_SECTIONS; cz++) {
                for (int cy = sy - VERTICAL_SECTIONS; cy <= sy + VERTICAL_SECTIONS; cy++) {
                    double dx = axisGap(x, cx);
                    double dy = axisGap(y, cy);
                    double dz = axisGap(z, cz);
                    candidates.add(new Candidate(cx, cy, cz, dx * dx + dy * dy + dz * dz));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::minDistance));

        Search search = new Search(x, y, z);
        for (Candidate c : candidates) {
            if (c.minDistance >= search.bestDistance) {
                break;
            }
            // Null for sections that aren't loaded (or are outside the world); never loads anything.
            Ref<ChunkStore> ref = chunks.getChunkSectionReference(c.x, c.y, c.z);
            if (ref == null || !ref.isValid()) {
                continue;
            }
            BlockSection section = store.getComponent(ref, BlockSection.getComponentType());
            if (section == null || !section.containsAny(ids)) {
                continue;
            }
            int baseX = c.x << ChunkUtil.BITS;
            int baseY = c.y << ChunkUtil.BITS;
            int baseZ = c.z << ChunkUtil.BITS;
            section.find(ids, (int index) -> search.offer(
                baseX + ChunkUtil.xFromIndex(index),
                baseY + ChunkUtil.yFromIndex(index),
                baseZ + ChunkUtil.zFromIndex(index)));
        }
        return search.best;
    }

    /** Distance along one axis from {@code v} to the nearest edge of section {@code s} (0 if inside). */
    private static double axisGap(double v, int s) {
        double min = (double) (s << ChunkUtil.BITS);
        double max = min + ChunkUtil.SIZE;
        return v < min ? min - v : v > max ? v - max : 0;
    }

    private record Candidate(int x, int y, int z, double minDistance) {
    }

    public record Hit(int x, int y, int z) {
    }

    private static final class Search {
        final double x;
        final double y;
        final double z;
        double bestDistance = Double.MAX_VALUE;
        Hit best;

        Search(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        void offer(int bx, int by, int bz) {
            double dx = bx + 0.5 - x;
            double dy = by + 0.5 - y;
            double dz = bz + 0.5 - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestDistance) {
                bestDistance = d;
                best = new Hit(bx, by, bz);
            }
        }
    }
}
