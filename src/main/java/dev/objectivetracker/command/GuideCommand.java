package dev.objectivetracker.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.objectivetracker.GuideService;
import dev.objectivetracker.guide.Guide;
import dev.objectivetracker.guide.GuideStep;
import dev.objectivetracker.progress.PlayerProgress;
import javax.annotation.Nonnull;

/** {@code /guide <show|hide|done|skip|back|step|list|reset|help>}, usable by every player. */
public final class GuideCommand extends AbstractCommandCollection {

    public GuideCommand(GuideService service) {
        super("guide", "Step-by-step survival guide");
        addAliases("og");
        // Same group setPermissionGroup(GameMode.Adventure) maps to: every regular player.
        setPermissionGroups("hytale:Adventurer");
        addSubCommand(new Show(service));
        addSubCommand(new Hide(service));
        addSubCommand(new WaypointToggle(service));
        addSubCommand(new Done(service));
        addSubCommand(new Skip(service));
        addSubCommand(new Back(service));
        addSubCommand(new StepTo(service));
        addSubCommand(new ListSteps(service));
        addSubCommand(new Reset(service));
        addSubCommand(new Help());
    }

    private static Message info(String text) {
        return Message.raw("[Guide] " + text).color("#e0b85a");
    }

    /** Shared plumbing: resolves the player's progress, runs the action, saves and redraws. */
    private abstract static class GuideSubCommand extends AbstractPlayerCommand {
        protected final GuideService service;

        GuideSubCommand(String name, String description, GuideService service) {
            super(name, description);
            this.service = service;
        }

        @Override
        protected final void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
        ) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }
            PlayerProgress progress = service.progressFor(playerRef.getUuid());
            run(context, playerRef, player, progress);
            service.store().save(playerRef.getUuid());
            service.refresh(playerRef);
        }

        protected abstract void run(CommandContext context, PlayerRef playerRef, Player player, PlayerProgress progress);
    }

    private static final class Show extends GuideSubCommand {
        Show(GuideService service) {
            super("show", "Show the guide HUD", service);
        }

        @Override
        protected void run(CommandContext context, PlayerRef playerRef, Player player, PlayerProgress progress) {
            service.setHudVisible(playerRef, player, progress, true);
            context.sendMessage(info("Guide shown."));
        }
    }

    private static final class Hide extends GuideSubCommand {
        Hide(GuideService service) {
            super("hide", "Hide the guide HUD", service);
        }

        @Override
        protected void run(CommandContext context, PlayerRef playerRef, Player player, PlayerProgress progress) {
            service.setHudVisible(playerRef, player, progress, false);
            context.sendMessage(info("Guide hidden. /guide show brings it back."));
        }
    }

    private static final class WaypointToggle extends GuideSubCommand {
        WaypointToggle(GuideService service) {
            super("waypoint", "Turn the waypoint marker on or off", service);
        }

        @Override
        protected void run(CommandContext context, PlayerRef playerRef, Player player, PlayerProgress progress) {
            boolean enabled = !progress.waypoints;
            service.setWaypointsEnabled(playerRef, progress, enabled);
            context.sendMessage(info(enabled ? "Waypoints on." : "Waypoints off. /guide waypoint turns them back on."));
        }
    }

    private static final class Done extends GuideSubCommand {
        Done(GuideService service) {
            super("done", "Mark the current step as finished", service);
        }

        @Override
        protected void run(CommandContext context, PlayerRef playerRef, Player player, PlayerProgress progress) {
            if (service.completeCurrent(playerRef, progress, false) == null) {
                context.sendMessage(info("You've already finished the guide."));
            }
        }
    }

    private static final class Skip extends GuideSubCommand {
        Skip(GuideService service) {
            super("skip", "Skip the current step", service);
        }

        @Override
        protected void run(CommandContext context, PlayerRef playerRef, Player player, PlayerProgress progress) {
            if (service.completeCurrent(playerRef, progress, true) == null) {
                context.sendMessage(info("You've already finished the guide."));
            }
        }
    }

    private static final class Back extends GuideSubCommand {
        Back(GuideService service) {
            super("back", "Go back to the previous step", service);
        }

        @Override
        protected void run(CommandContext context, PlayerRef playerRef, Player player, PlayerProgress progress) {
            GuideStep step = service.back(progress);
            context.sendMessage(info(step == null ? "Already at the first step." : "Back to: " + step.title));
        }
    }

    private static final class StepTo extends GuideSubCommand {
        private final RequiredArg<Integer> number;

        StepTo(GuideService service) {
            super("step", "Jump to a step number (see /guide list)", service);
            this.number = withRequiredArg("number", "Step number", ArgTypes.INTEGER);
        }

        @Override
        protected void run(CommandContext context, PlayerRef playerRef, Player player, PlayerProgress progress) {
            int n = number.get(context);
            if (service.jumpTo(progress, n)) {
                context.sendMessage(info("Jumped to step " + n + ": " + service.guide().step(n - 1).title));
            } else {
                context.sendMessage(info("No step " + n + ". The guide has " + service.guide().steps.size() + " steps."));
            }
        }
    }

    private static final class ListSteps extends GuideSubCommand {
        ListSteps(GuideService service) {
            super("list", "List every step in the guide", service);
        }

        @Override
        protected void run(CommandContext context, PlayerRef playerRef, Player player, PlayerProgress progress) {
            Guide guide = service.guide();
            GuideStep current = service.currentStep(progress);
            String chapter = null;
            context.sendMessage(info(guide.title));
            for (GuideStep step : guide.steps) {
                if (!step.chapterTitle.equals(chapter)) {
                    chapter = step.chapterTitle;
                    context.sendMessage(Message.raw(chapter).color("#e0b85a").bold(true));
                }
                boolean done = progress.completedSteps.contains(step.id);
                boolean here = current != null && current.id.equals(step.id);
                String marker = here ? " >> " : done ? " [X] " : " [  ] ";
                String color = here ? "#ffffff" : done ? "#6fcf73" : "#9aa4b1";
                context.sendMessage(Message.raw(marker + (step.index + 1) + ". " + step.title).color(color));
            }
        }
    }

    private static final class Reset extends GuideSubCommand {
        Reset(GuideService service) {
            super("reset", "Start the guide over from step 1", service);
        }

        @Override
        protected void run(CommandContext context, PlayerRef playerRef, Player player, PlayerProgress progress) {
            service.reset(progress);
            context.sendMessage(info("Guide reset to step 1."));
        }
    }

    private static final class Help extends AbstractPlayerCommand {
        Help() {
            super("help", "Show guide commands");
        }

        @Override
        protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
        ) {
            context.sendMessage(info("Commands:"));
            context.sendMessage(Message.raw(" /guide show | hide  - toggle the HUD"));
            context.sendMessage(Message.raw(" /guide waypoint  - toggle the waypoint marker"));
            context.sendMessage(Message.raw(" /guide done  - finish the current step (for steps you confirm yourself)"));
            context.sendMessage(Message.raw(" /guide skip | back  - move forward / backward one step"));
            context.sendMessage(Message.raw(" /guide list  - see every step;  /guide step <n>  - jump to one"));
            context.sendMessage(Message.raw(" /guide reset  - start over"));
        }
    }
}
