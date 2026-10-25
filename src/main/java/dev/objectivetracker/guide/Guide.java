package dev.objectivetracker.guide;

import java.util.ArrayList;
import java.util.List;

/** A loaded guide: chapters of steps, flattened into one ordered list for progress tracking. */
public final class Guide {
    public String id;
    public String title;
    public List<Chapter> chapters = List.of();

    public transient List<GuideStep> steps = new ArrayList<>();

    public GuideStep step(int index) {
        if (index < 0 || index >= steps.size()) {
            return null;
        }
        return steps.get(index);
    }

    public int indexOf(String stepId) {
        for (GuideStep step : steps) {
            if (step.id.equals(stepId)) {
                return step.index;
            }
        }
        return -1;
    }

    public static final class Chapter {
        public String title;
        public List<GuideStep> steps = List.of();
    }
}
