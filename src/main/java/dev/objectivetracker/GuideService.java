package dev.objectivetracker;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldMapTracker;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import dev.objectivetracker.guide.Guide;
import dev.objectivetracker.guide.GuideStep;
import dev.objectivetracker.guide.Objective;
import dev.objectivetracker.hud.GuideHud;
import dev.objectivetracker.progress.PlayerProgress;
import dev.objectivetracker.progress.ProgressStore;
import dev.objectivetracker.waypoint.BlockFinder;
import dev.objectivetracker.waypoint.WaypointMarkerProvider.Waypoint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Vector3d;

/**
 * Evaluates each player's current guide step against their inventory/position, advances them
 * when it is satisfied, and keeps their HUD in sync. Every method that touches a player's
 * components must run on that player's world thread.
 */
public final class GuideService {
    /** Safety cap on how many steps can be auto-completed in a single evaluation. */
    private static final int MAX_ADVANCE_PER_TICK = 64;
    /** How often the block search for a player's waypoint is re-run. */
    private static final long WAYPOINT_SCAN_NANOS = 2_000_000_000L;
    /** Within this many blocks the waypoint counts as reached and the arrow is hidden. */
    private static final int ARRIVED_DISTANCE = 3;

    private final Guide guide;
    private final ProgressStore store;
    private final HytaleLogger logger;
    private final Map<UUID, GuideHud> huds = new ConcurrentHashMap<>();
    private final BlockFinder finder = new BlockFinder();
    /** Read by the world map thread through {@link dev.objectivetracker.waypoint.WaypointMarkerProvider}. */
    private final Map<UUID, Waypoint> waypoints = new ConcurrentHashMap<>();
    private final Map<UUID, Search> searches = new ConcurrentHashMap<>();
    private boolean arrowsAvailable;

    public GuideService(Guide guide, ProgressStore store, HytaleLogger logger) {
        this.guide = guide;
        this.store = store;
        this.logger = logger;
    }

    public Guide guide() {
        return guide;
    }

    public ProgressStore store() {
        return store;
    }

    public Map<UUID, Waypoint> waypoints() {
        return waypoints;
    }

    public BlockFinder finder() {
        return finder;
    }

    /** Set once at startup: whether the arrow images from this plugin's asset pack were loaded. */
    public void setArrowsAvailable(boolean available) {
        arrowsAvailable = available;
    }

    /** Called from the scheduler: hops onto each world's thread and refreshes its players. */
    public void tickAll() {
        for (World world : Universe.get().getWorlds().values()) {
            world.execute(() -> {
                for (PlayerRef playerRef : world.getPlayerRefs()) {
                    try {
                        refresh(playerRef);
                    } catch (RuntimeException e) {
                        logger.atWarning().withCause(e).log("Guide refresh failed for %s", playerRef.getUsername());
                    }
                }
            });
        }
    }

    /** Re-evaluates and redraws one player. Must run on the player's world thread. */
    public void refresh(PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> entityStore = ref.getStore();
        Player player = entityStore.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        PlayerProgress progress = progressFor(playerRef.getUuid());
        Snapshot snapshot = Snapshot.capture(entityStore, ref, player, playerRef);

        advance(playerRef, progress, snapshot);
        updateWaypoint(playerRef, entityStore.getExternalData().getWorld(), progress, snapshot);
        syncHud(playerRef, player, progress, snapshot);
    }

    /** Drops the cached HUD so the next refresh re-creates it (e.g. after a world change). */
    public void forgetHud(UUID uuid) {
        huds.remove(uuid);
    }

    public void onDisconnect(UUID uuid) {
        huds.remove(uuid);
        waypoints.remove(uuid);
        searches.remove(uuid);
        store.unload(uuid);
    }

    // ---- progress manipulation (commands) ----

    public PlayerProgress progressFor(UUID uuid) {
        PlayerProgress progress = store.get(uuid);
        if (!guide.id.equals(progress.guideId) || progress.currentStep == null || guide.indexOf(progress.currentStep) < 0) {
            if (progress.finished && guide.id.equals(progress.guideId)) {
                return progress;
            }
            progress.guideId = guide.id;
            progress.finished = false;
            progress.currentStep = firstIncomplete(progress, 0);
            if (progress.currentStep == null) {
                progress.finished = true;
            }
            progress.dirty = true;
        }
        return progress;
    }

    public GuideStep currentStep(PlayerProgress progress) {
        if (progress.finished) {
            return null;
        }
        return guide.step(guide.indexOf(progress.currentStep));
    }

    /** Marks the current step complete and moves on. Returns the step that was completed. */
    public GuideStep completeCurrent(PlayerRef playerRef, PlayerProgress progress, boolean skipped) {
        GuideStep step = completeSilently(progress);
        if (step != null) {
            announce(playerRef, step, skipped);
        }
        return step;
    }

    private GuideStep completeSilently(PlayerProgress progress) {
        GuideStep step = currentStep(progress);
        if (step == null) {
            return null;
        }
        progress.completedSteps.add(step.id);
        moveTo(progress, firstIncomplete(progress, step.index + 1));
        return step;
    }

    /** Goes back one step (un-completing it) so it shows on the HUD again. */
    public GuideStep back(PlayerProgress progress) {
        int index = progress.finished ? guide.steps.size() : guide.indexOf(progress.currentStep);
        if (index <= 0) {
            return null;
        }
        GuideStep previous = guide.step(index - 1);
        progress.completedSteps.remove(previous.id);
        moveTo(progress, previous.id);
        return previous;
    }

    public boolean jumpTo(PlayerProgress progress, int oneBasedIndex) {
        GuideStep step = guide.step(oneBasedIndex - 1);
        if (step == null) {
            return false;
        }
        progress.completedSteps.removeIf(id -> guide.indexOf(id) >= step.index);
        moveTo(progress, step.id);
        return true;
    }

    public void reset(PlayerProgress progress) {
        progress.completedSteps.clear();
        moveTo(progress, guide.steps.isEmpty() ? null : guide.steps.get(0).id);
    }

    public void setHudVisible(PlayerRef playerRef, Player player, PlayerProgress progress, boolean visible) {
        progress.hudVisible = visible;
        progress.dirty = true;
        if (!visible) {
            huds.remove(playerRef.getUuid());
            player.getHudManager().removeCustomHud(playerRef, GuideHud.KEY);
        }
    }

    public void setWaypointsEnabled(PlayerRef playerRef, PlayerProgress progress, boolean enabled) {
        progress.waypoints = enabled;
        progress.dirty = true;
        if (!enabled) {
            waypoints.remove(playerRef.getUuid());
            searches.remove(playerRef.getUuid());
        }
    }

    private void moveTo(PlayerProgress progress, String stepId) {
        progress.currentStep = stepId;
        progress.finished = stepId == null;
        progress.dirty = true;
    }

    private String firstIncomplete(PlayerProgress progress, int from) {
        for (int i = from; i < guide.steps.size(); i++) {
            GuideStep step = guide.steps.get(i);
            if (!progress.completedSteps.contains(step.id)) {
                return step.id;
            }
        }
        return null;
    }

    // ---- evaluation ----

    private void advance(PlayerRef playerRef, PlayerProgress progress, Snapshot snapshot) {
        GuideStep first = null;
        boolean firstSkipped = false;
        int advanced = 0;
        while (advanced < MAX_ADVANCE_PER_TICK) {
            GuideStep step = currentStep(progress);
            if (step == null) {
                break;
            }
            boolean skip = step.skipIf.stream().anyMatch(o -> isMet(o, snapshot));
            boolean done = !step.needsConfirmation() && step.objectives.stream().allMatch(o -> isMet(o, snapshot));
            if (!skip && !done) {
                break;
            }
            completeSilently(progress);
            if (first == null) {
                first = step;
                firstSkipped = skip && !done;
            }
            advanced++;
        }
        if (advanced == 1) {
            announce(playerRef, first, firstSkipped);
        } else if (advanced > 1) {
            // Typically a returning or experienced player: one summary instead of a burst of popups.
            GuideStep now = currentStep(progress);
            playerRef.sendMessage(Message.raw("[Guide] Caught up " + advanced + " steps you've already done"
                + (now == null ? "." : ". Next: " + now.title)).color("#6fcf73"));
        }
    }

    private static boolean isMet(Objective o, Snapshot s) {
        return switch (o.kind()) {
            case ITEM -> s.itemCount(o) >= o.count;
            case RESOURCE -> s.resourceCount(o.id) >= o.count;
            case ZONE -> s.inZone(o.id);
            case BELOW -> s.y <= o.count;
            case MANUAL -> false;
        };
    }

    private void announce(PlayerRef playerRef, GuideStep step, boolean skipped) {
        if (skipped) {
            playerRef.sendMessage(Message.raw("[Guide] Skipped \"" + step.title + "\" - you're already past it.").color("#9aa4b1"));
            return;
        }
        NotificationUtil.sendNotification(
            playerRef.getPacketHandler(), Message.raw("Guide step complete"), Message.raw(step.title), NotificationStyle.Success);
        playerRef.sendMessage(Message.raw("[Guide] Completed: " + step.title).color("#6fcf73"));
    }

    // ---- waypoints ----

    /** Re-runs the block search when the target changes or every couple of seconds. */
    private void updateWaypoint(PlayerRef playerRef, World world, PlayerProgress progress, Snapshot snapshot) {
        UUID uuid = playerRef.getUuid();
        GuideStep step = currentStep(progress);
        Target target = !progress.waypoints || step == null ? null : targetFor(step, snapshot);
        if (target == null) {
            waypoints.remove(uuid);
            searches.remove(uuid);
            return;
        }
        long now = System.nanoTime();
        Search last = searches.get(uuid);
        if (last != null && last.target.equals(target) && last.world.equals(world.getName()) && now - last.time < WAYPOINT_SCAN_NANOS) {
            return;
        }
        BlockFinder.Hit hit = BlockFinder.nearest(world, snapshot.x, snapshot.y, snapshot.z, finder.resolve(target.blocks));
        searches.put(uuid, new Search(target, world.getName(), now));
        if (hit == null) {
            waypoints.remove(uuid);
        } else {
            waypoints.put(uuid, new Waypoint(world.getName(), hit.x(), hit.y(), hit.z(), target.label));
        }
    }

    /** The first unfinished objective with blocks to find, else the step's own target (e.g. a bench). */
    private static Target targetFor(GuideStep step, Snapshot snapshot) {
        for (Objective o : step.objectives) {
            if (o.find != null && !o.find.isEmpty() && !isMet(o, snapshot)) {
                return new Target(o.find, o.displayLabel());
            }
        }
        if (step.find != null && !step.find.isEmpty()) {
            return new Target(step.find, step.findLabel == null || step.findLabel.isBlank() ? "Target" : step.findLabel);
        }
        return null;
    }

    /** The text next to the arrow (the arrow itself is driven by {@link #tickArrows}). */
    private String waypointText(UUID uuid, PlayerProgress progress, GuideStep step, Snapshot snapshot, boolean arrows) {
        Target target = !progress.waypoints || step == null ? null : targetFor(step, snapshot);
        if (target == null) {
            return "";
        }
        Waypoint waypoint = waypoints.get(uuid);
        if (waypoint == null || !waypoint.label().equals(target.label)) {
            Search last = searches.get(uuid);
            return last != null && last.target.equals(target) ? "No " + target.label + " in sight yet - keep exploring." : "";
        }
        double dx = waypoint.x() + 0.5 - snapshot.x;
        double dz = waypoint.z() + 0.5 - snapshot.z;
        int dy = waypoint.y() - (int) Math.floor(snapshot.y);
        long distance = Math.round(Math.sqrt(dx * dx + dz * dz + dy * dy));
        if (distance <= ARRIVED_DISTANCE) {
            return waypoint.label() + ": right here";
        }
        StringBuilder line = new StringBuilder(waypoint.label()).append("\n").append(distance).append("m");
        if (!arrows) {
            // No arrow images available: fall back to describing the direction in words.
            line.append(' ').append(HEADINGS[direction(dx, dz, snapshot.yaw, HEADINGS.length)]);
        }
        if (Math.abs(dy) >= 3) {
            line.append(", ").append(Math.abs(dy)).append(dy < 0 ? " below" : " above");
        }
        return line.toString();
    }

    /**
     * Called every world tick on the world thread: turns each player's arrow to follow their
     * camera. Only reads the player's position and head rotation, so it's cheap; the HUD only
     * sends a packet when the arrow actually changes direction.
     */
    public void tickArrows(World world) {
        if (!arrowsAvailable) {
            return;
        }
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            GuideHud hud = huds.get(playerRef.getUuid());
            if (hud != null) {
                hud.showArrow(arrowDirection(playerRef, world));
            }
        }
    }

    private int arrowDirection(PlayerRef playerRef, World world) {
        Waypoint waypoint = waypoints.get(playerRef.getUuid());
        Transform transform = playerRef.getTransform();
        Rotation3f head = playerRef.getHeadRotation();
        if (waypoint == null || transform == null || head == null || !waypoint.world().equals(world.getName())) {
            return -1;
        }
        Vector3d position = transform.getPosition();
        double dx = waypoint.x() + 0.5 - position.x;
        double dz = waypoint.z() + 0.5 - position.z;
        double dy = waypoint.y() - Math.floor(position.y);
        if (Math.round(Math.sqrt(dx * dx + dz * dz + dy * dy)) <= ARRIVED_DISTANCE) {
            return -1;
        }
        return direction(dx, dz, head.yaw(), GuideHud.ARROW_DIRECTIONS);
    }

    private static final String[] HEADINGS = {
        "ahead", "ahead-right", "to the right", "behind-right", "behind", "behind-left", "to the left", "ahead-left"
    };

    /**
     * Which of {@code sectors} equal slices (dx, dz) falls in, relative to the way the player is
     * facing: 0 = straight ahead, counting clockwise (to the right).
     */
    private static int direction(double dx, double dz, float yaw, int sectors) {
        // Same convention as Transform.getDirection(pitch, yaw): yaw 0 faces -Z, and the player's
        // right is facing x up (right-handed, Y up).
        double facingX = -Math.sin(yaw);
        double facingZ = -Math.cos(yaw);
        double forward = dx * facingX + dz * facingZ;
        double right = dx * -facingZ + dz * facingX;
        double degrees = Math.toDegrees(Math.atan2(right, forward));
        return (int) Math.floorMod(Math.round(degrees / (360.0 / sectors)), (long) sectors);
    }

    private record Target(List<String> blocks, String label) {
    }

    private record Search(Target target, String world, long time) {
    }

    // ---- HUD ----

    private void syncHud(PlayerRef playerRef, Player player, PlayerProgress progress, Snapshot snapshot) {
        if (!progress.hudVisible) {
            return;
        }
        HudManager hudManager = player.getHudManager();
        GuideHud hud = huds.get(playerRef.getUuid());
        CustomUIHud attached = hudManager.getCustomHud(GuideHud.KEY);
        if (hud == null || attached != hud) {
            hud = new GuideHud(playerRef, arrowsAvailable);
            huds.put(playerRef.getUuid(), hud);
            hudManager.addCustomHud(playerRef, hud);
        }
        hud.render(buildView(playerRef.getUuid(), progress, snapshot, hud.hasArrows()));
    }

    private GuideHud.View buildView(UUID uuid, PlayerProgress progress, Snapshot snapshot, boolean arrows) {
        int total = guide.steps.size();
        GuideStep step = currentStep(progress);
        if (step == null) {
            return new GuideHud.View(
                guide.title,
                "Guide complete!",
                "You've worked through every step. Use /guide reset to start over, or /guide step <n> to revisit one.",
                List.of(),
                "",
                "",
                total + " / " + total + " steps");
        }
        List<GuideHud.Line> lines = new ArrayList<>();
        for (Objective o : step.objectives) {
            if (lines.size() == GuideHud.MAX_OBJECTIVES) {
                break;
            }
            lines.add(describe(o, snapshot));
        }
        String footer = "Step " + (step.index + 1) + " / " + total
            + (step.needsConfirmation() ? "   -   /guide done when finished" : "   -   /guide help");
        return new GuideHud.View(
            nullToEmpty(step.chapterTitle),
            nullToEmpty(step.title),
            nullToEmpty(step.text),
            lines,
            waypointText(uuid, progress, step, snapshot, arrows),
            step.hint == null || step.hint.isBlank() ? "" : "Tip: " + step.hint,
            footer);
    }

    private static GuideHud.Line describe(Objective o, Snapshot s) {
        String label = o.displayLabel();
        return switch (o.kind()) {
            case ITEM, RESOURCE -> {
                int have = o.kind() == Objective.Type.ITEM ? s.itemCount(o) : s.resourceCount(o.id);
                boolean done = have >= o.count;
                yield new GuideHud.Line(box(done) + label + "   " + Math.min(have, o.count) + "/" + o.count, done);
            }
            case ZONE -> {
                boolean done = s.inZone(o.id);
                yield new GuideHud.Line(box(done) + label, done);
            }
            case BELOW -> {
                boolean done = s.y <= o.count;
                yield new GuideHud.Line(box(done) + label + "   (Y " + (int) Math.floor(s.y) + ")", done);
            }
            case MANUAL -> new GuideHud.Line(box(false) + label, false);
        };
    }

    private static String box(boolean done) {
        return done ? "[X]  " : "[  ]  ";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Inventory counts and position captured once per refresh. */
    private static final class Snapshot {
        final Map<String, Integer> items = new HashMap<>();
        final Map<String, Integer> resources = new HashMap<>();
        double x;
        double y;
        double z;
        float yaw;
        String zone = "";
        String region = "";

        static Snapshot capture(Store<EntityStore> entityStore, Ref<EntityStore> ref, Player player, PlayerRef playerRef) {
            Snapshot s = new Snapshot();
            ItemContainer inventory = InventoryComponent.getCombined(entityStore, ref, InventoryComponent.EVERYTHING);
            if (inventory != null) {
                inventory.forEach((slot, stack) -> s.add(stack));
            }
            Transform transform = playerRef.getTransform();
            if (transform != null) {
                Vector3d position = transform.getPosition();
                s.x = position.x;
                s.y = position.y;
                s.z = position.z;
                s.yaw = transform.getRotation().yaw();
            }
            Rotation3f head = playerRef.getHeadRotation();
            if (head != null) {
                s.yaw = head.yaw();
            }
            WorldMapTracker tracker = player.getWorldMapTracker();
            if (tracker != null) {
                WorldMapTracker.ZoneDiscoveryInfo info = tracker.getCurrentZone();
                if (info != null) {
                    s.zone = nullToEmpty(info.zoneName()).toLowerCase(Locale.ROOT);
                    s.region = nullToEmpty(info.regionName()).toLowerCase(Locale.ROOT);
                }
            }
            return s;
        }

        private void add(ItemStack stack) {
            if (ItemStack.isEmpty(stack)) {
                return;
            }
            int qty = stack.getQuantity();
            items.merge(stack.getItemId(), qty, Integer::sum);
            Item item = stack.getItem();
            if (item == null || item.getResourceTypes() == null) {
                return;
            }
            for (ItemResourceType type : item.getResourceTypes()) {
                if (type != null && type.id != null) {
                    resources.merge(type.id, qty * Math.max(1, type.quantity), Integer::sum);
                }
            }
        }

        int itemCount(Objective o) {
            int total = 0;
            for (String id : o.itemIds()) {
                total += items.getOrDefault(id, 0);
            }
            return total;
        }

        int resourceCount(String id) {
            return resources.getOrDefault(id, 0);
        }

        boolean inZone(String prefix) {
            String p = prefix.toLowerCase(Locale.ROOT);
            return region.startsWith(p) || zone.startsWith(p);
        }
    }
}
