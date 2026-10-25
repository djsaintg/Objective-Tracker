package dev.objectivetracker.waypoint;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.objectivetracker.GuideService;
import javax.annotation.Nonnull;

/**
 * Turns the guide arrows once per world tick (30 per second), in step with the world's own
 * updates, so the arrow follows the camera without beating against a separate timer.
 */
public final class ArrowTickSystem extends TickingSystem<EntityStore> {
    private final GuideService service;
    private final HytaleLogger logger;
    private boolean failed;

    public ArrowTickSystem(GuideService service, HytaleLogger logger) {
        this.service = service;
        this.logger = logger;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        try {
            service.tickArrows(store.getExternalData().getWorld());
        } catch (RuntimeException e) {
            // Never let a HUD problem break the world tick.
            if (!failed) {
                failed = true;
                logger.atWarning().withCause(e).log("Guide arrow update failed (further errors suppressed)");
            }
        }
    }
}
