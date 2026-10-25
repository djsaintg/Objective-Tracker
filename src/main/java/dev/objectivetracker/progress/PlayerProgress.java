package dev.objectivetracker.progress;

import java.util.HashSet;
import java.util.Set;

/** Per-player guide state, saved as JSON. */
public final class PlayerProgress {
    public String guideId;
    /** Id of the step currently shown. Stored by id so guide edits don't scramble progress. */
    public String currentStep;
    public Set<String> completedSteps = new HashSet<>();
    public boolean hudVisible = true;
    public boolean waypoints = true;
    public boolean finished;

    public transient boolean dirty;
}
