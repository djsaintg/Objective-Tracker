package dev.objectivetracker;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates each player's current guide step against their inventory/position, advances them
 * when it is satisfied, and keeps their HUD in sync. Every method that touches a player's
 * components must run on that player's world thread.
 */
public final class GuideService {
    /** Safety cap on how many steps can be auto-completed in a single evaluation. */
    private static final int MAX_ADVANCE_PER_TICK = 64;

    private final Guide guide;
    private final ProgressStore store;
    private final HytaleLogger logger;
    private final Map<UUID, GuideHud> huds = new ConcurrentHashMap<>();

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
        syncHud(playerRef, player, progress, snapshot);
    }

    /** Drops the cached HUD so the next refresh re-creates it (e.g. after a world change). */
    public void forgetHud(UUID uuid) {
        huds.remove(uuid);
    }

    public void onDisconnect(UUID uuid) {
        huds.remove(uuid);
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

    // ---- HUD ----

    private void syncHud(PlayerRef playerRef, Player player, PlayerProgress progress, Snapshot snapshot) {
        if (!progress.hudVisible) {
            return;
        }
        HudManager hudManager = player.getHudManager();
        GuideHud hud = huds.get(playerRef.getUuid());
        CustomUIHud attached = hudManager.getCustomHud(GuideHud.KEY);
        if (hud == null || attached != hud) {
            hud = new GuideHud(playerRef);
            huds.put(playerRef.getUuid(), hud);
            hudManager.addCustomHud(playerRef, hud);
        }
        hud.render(buildView(progress, snapshot));
    }

    private GuideHud.View buildView(PlayerProgress progress, Snapshot snapshot) {
        int total = guide.steps.size();
        GuideStep step = currentStep(progress);
        if (step == null) {
            return new GuideHud.View(
                guide.title,
                "Guide complete!",
                "You've worked through every step. Use /guide reset to start over, or /guide step <n> to revisit one.",
                List.of(),
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
        double y;
        String zone = "";
        String region = "";

        static Snapshot capture(Store<EntityStore> entityStore, Ref<EntityStore> ref, Player player, PlayerRef playerRef) {
            Snapshot s = new Snapshot();
            ItemContainer inventory = InventoryComponent.getCombined(entityStore, ref, InventoryComponent.EVERYTHING);
            if (inventory != null) {
                inventory.forEach((slot, stack) -> s.add(stack));
            }
            if (playerRef.getTransform() != null) {
                s.y = playerRef.getTransform().getPosition().y;
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
