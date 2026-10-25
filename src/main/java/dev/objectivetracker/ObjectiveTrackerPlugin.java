package dev.objectivetracker;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.objectivetracker.command.GuideCommand;
import dev.objectivetracker.guide.Guide;
import dev.objectivetracker.guide.GuideLoader;
import dev.objectivetracker.progress.ProgressStore;
import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

public final class ObjectiveTrackerPlugin extends JavaPlugin {
    private static final String GUIDE_NAME = "survival";

    private GuideService service;
    private ScheduledFuture<?> ticker;
    private ScheduledFuture<?> autosave;

    public ObjectiveTrackerPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        Guide guide;
        try {
            GuideLoader.exportDefault(getDataDirectory(), GUIDE_NAME);
            guide = GuideLoader.load(getDataDirectory(), GUIDE_NAME, getLogger());
        } catch (IOException e) {
            throw new IllegalStateException("Could not load the survival guide", e);
        }
        service = new GuideService(guide, new ProgressStore(getDataDirectory(), getLogger()), getLogger());

        getCommandRegistry().registerCommand(new GuideCommand(service));
        getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
    }

    @Override
    protected void start() {
        GuideLoader.validateItems(service.guide(), getLogger());
        ticker = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
        autosave = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(service.store()::saveAll, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    protected void shutdown() {
        if (ticker != null) {
            ticker.cancel(false);
        }
        if (autosave != null) {
            autosave.cancel(false);
        }
        if (service != null) {
            service.store().saveAll();
        }
    }

    private void tick() {
        try {
            service.tickAll();
        } catch (RuntimeException e) {
            getLogger().atWarning().withCause(e).log("Guide tick failed");
        }
    }

    private void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) {
            return;
        }
        PlayerRef playerRef = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }
        // The client starts with a fresh HUD whenever it (re)joins a world, so rebuild ours.
        service.forgetHud(playerRef.getUuid());
        service.refresh(playerRef);
    }

    private void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        service.onDisconnect(event.getPlayerRef().getUuid());
    }
}
