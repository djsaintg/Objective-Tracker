package dev.objectivetracker.guide;

import java.util.List;

/**
 * One checkable requirement inside a guide step.
 *
 * <ul>
 *   <li>{@code item}     - hold {@code count} of any of {@code ids} (exact item ids)</li>
 *   <li>{@code resource} - hold {@code count} of items tagged with resource type {@code id} (e.g. Rubble, Wood_Trunk, Rock)</li>
 *   <li>{@code zone}     - stand in a map region whose name starts with {@code id} (e.g. Zone2)</li>
 *   <li>{@code below}    - be at or below Y = {@code count}</li>
 *   <li>{@code manual}   - the player confirms it themselves with /guide done</li>
 * </ul>
 *
 * <p>{@code find} optionally lists block ids (with {@code *} wildcards) to point a waypoint at
 * while this objective is unfinished, e.g. {@code ["Ore_Copper*"]}.
 */
public final class Objective {
    public String type = "item";
    public String id;
    public List<String> ids;
    public int count = 1;
    public String label;
    public List<String> find;

    public Type kind() {
        return Type.parse(type);
    }

    public List<String> itemIds() {
        if (ids != null && !ids.isEmpty()) {
            return ids;
        }
        return id == null ? List.of() : List.of(id);
    }

    public String displayLabel() {
        if (label != null && !label.isBlank()) {
            return label;
        }
        return id != null ? id : String.join(" / ", itemIds());
    }

    /** True when the objective has a numeric "have / need" progress to show. */
    public boolean isCounted() {
        Type t = kind();
        return t == Type.ITEM || t == Type.RESOURCE;
    }

    public enum Type {
        ITEM, RESOURCE, ZONE, BELOW, MANUAL;

        static Type parse(String raw) {
            if (raw == null) {
                return ITEM;
            }
            return switch (raw.trim().toLowerCase()) {
                case "resource" -> RESOURCE;
                case "zone" -> ZONE;
                case "below", "depth" -> BELOW;
                case "manual" -> MANUAL;
                default -> ITEM;
            };
        }
    }
}
