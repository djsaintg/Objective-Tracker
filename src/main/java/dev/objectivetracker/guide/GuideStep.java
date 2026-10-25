package dev.objectivetracker.guide;

import java.util.List;

/** A single step of a guide, shown one at a time on the HUD. */
public final class GuideStep {
    public String id;
    public String title;
    public String text;
    public String hint;
    public List<Objective> objectives = List.of();
    /** If the player meets ANY of these, the step is skipped (e.g. already owns a better tool). */
    public List<Objective> skipIf = List.of();
    /** Block ids to point a waypoint at when no unfinished objective has its own (e.g. the bench to craft at). */
    public List<String> find;
    /** Name shown for {@link #find} targets, e.g. "Workbench". */
    public String findLabel;

    /** Filled in by {@link GuideLoader}. */
    public transient String chapterTitle;
    public transient int index;

    /** Steps with no objectives, or any manual objective, only complete via /guide done. */
    public boolean needsConfirmation() {
        return objectives.isEmpty() || objectives.stream().anyMatch(o -> o.kind() == Objective.Type.MANUAL);
    }
}
