package dev.objectivetracker.hud;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * The guide panel drawn on the right side of the screen. Markup is inline (same approach as the
 * built-in SpectatingHud) so the plugin needs no asset pack. Values are pushed with selector
 * updates and only re-sent when they change.
 */
public final class GuideHud extends CustomUIHud {
    public static final String KEY = "ObjectiveTracker";
    public static final int MAX_OBJECTIVES = 6;

    private static final String COLOR_DONE = "#6fcf73";
    private static final String COLOR_PENDING = "#e6e9ee";

    private static final String DOCUMENT = buildDocument();

    private View shown;

    public GuideHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, KEY);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commands) {
        commands.appendInline(null, DOCUMENT);
        View view = shown;
        shown = null;
        if (view != null) {
            apply(view, commands);
        }
    }

    /** Sends only the parts of {@code view} that differ from what the client already shows. */
    public void render(@Nonnull View view) {
        if (view.equals(shown)) {
            return;
        }
        UICommandBuilder commands = new UICommandBuilder();
        apply(view, commands);
        update(false, commands);
    }

    private void apply(View view, UICommandBuilder c) {
        View old = shown;
        if (old == null || !old.chapter.equals(view.chapter)) {
            c.set("#OtChapter.Text", view.chapter);
        }
        if (old == null || !old.title.equals(view.title)) {
            c.set("#OtTitle.Text", view.title);
        }
        if (old == null || !old.body.equals(view.body)) {
            c.set("#OtBody.Text", view.body);
            c.set("#OtBody.Visible", !view.body.isEmpty());
        }
        for (int i = 0; i < MAX_OBJECTIVES; i++) {
            Line line = i < view.objectives.size() ? view.objectives.get(i) : null;
            Line before = old == null || i >= old.objectives.size() ? null : old.objectives.get(i);
            if (old != null && Objects.equals(line, before)) {
                continue;
            }
            String id = "#OtObj" + i;
            if (line == null) {
                c.set(id + ".Visible", false);
            } else {
                c.set(id + ".Visible", true);
                c.set(id + ".Text", line.text);
                c.set(id + ".Style.TextColor", line.done ? COLOR_DONE : COLOR_PENDING);
            }
        }
        if (old == null || !old.hint.equals(view.hint)) {
            c.set("#OtHint.Text", view.hint);
            c.set("#OtHint.Visible", !view.hint.isEmpty());
        }
        if (old == null || !old.footer.equals(view.footer)) {
            c.set("#OtFooter.Text", view.footer);
        }
        shown = view;
    }

    /** Everything the panel displays. */
    public record View(String chapter, String title, String body, List<Line> objectives, String hint, String footer) {
    }

    public record Line(String text, boolean done) {
    }

    private static String buildDocument() {
        StringBuilder objectives = new StringBuilder();
        for (int i = 0; i < MAX_OBJECTIVES; i++) {
            objectives.append("""
                    Label #OtObj%d {
                      Anchor: (Top: 3);
                      Visible: false;
                      Style: (FontSize: 15, TextColor: #e6e9ee, Wrap: true);
                    }
                """.formatted(i));
        }
        return """
            Group #OtPanel {
              Anchor: (Top: 180, Right: 20, Width: 330);
              LayoutMode: Top;
              Background: #0c1118(0.72);
              Padding: (Horizontal: 14, Vertical: 12);

              Label #OtChapter {
                Style: (FontSize: 12, TextColor: #e0b85a, RenderBold: true, RenderUppercase: true, LetterSpacing: 1.0, Wrap: true);
              }

              Label #OtTitle {
                Anchor: (Top: 4);
                Style: (FontSize: 19, TextColor: #ffffff, RenderBold: true, Wrap: true);
              }

              Label #OtBody {
                Anchor: (Top: 6);
                Style: (FontSize: 14, TextColor: #b9c3cf, Wrap: true);
              }

              Group #OtObjectives {
                Anchor: (Top: 8);
                LayoutMode: Top;
            %s
              }

              Label #OtHint {
                Anchor: (Top: 8);
                Visible: false;
                Style: (FontSize: 13, TextColor: #8fb8e6, Wrap: true);
              }

              Label #OtFooter {
                Anchor: (Top: 10);
                Style: (FontSize: 11, TextColor: #7d8794, Wrap: true);
              }
            }
            """.formatted(objectives);
    }
}
