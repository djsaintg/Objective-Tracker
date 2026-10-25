package dev.objectivetracker.waypoint;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Puts each player's current guide target on their world map and compass. The world map polls
 * providers itself and removes markers that stop being reported, so clearing a target is enough.
 */
public final class WaypointMarkerProvider implements WorldMapManager.MarkerProvider {
    public static final String KEY = "objectiveTracker";
    private static final String ICON = "Coordinate.png";

    private final Map<UUID, Waypoint> waypoints;

    public WaypointMarkerProvider(Map<UUID, Waypoint> waypoints) {
        this.waypoints = waypoints;
    }

    @Override
    public void update(@Nonnull World world, @Nonnull Player player, @Nonnull MarkersCollector collector) {
        // Called on the world thread (the player's map tracker tick), so reading components is safe.
        Ref<EntityStore> ref = player.getReference();
        PlayerRef playerRef = ref == null || !ref.isValid() ? null : ref.getStore().getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }
        Waypoint waypoint = waypoints.get(playerRef.getUuid());
        if (waypoint == null || !waypoint.world().equals(world.getName())) {
            return;
        }
        Transform transform = new Transform(waypoint.x() + 0.5, waypoint.y() + 0.5, waypoint.z() + 0.5);
        collector.addIgnoreViewDistance(
            new MapMarkerBuilder("ObjectiveTracker-" + playerRef.getUuid(), ICON, transform)
                .withCustomName("Guide: " + waypoint.label())
                .build());
    }

    /** Where a player's current objective can be found. */
    public record Waypoint(String world, int x, int y, int z, String label) {
    }
}
