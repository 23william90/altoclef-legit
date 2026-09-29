package adris.altoclef;

import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.SendChatEvent;
import adris.altoclef.eventbus.events.TitleScreenEntryEvent;
import adris.altoclef.ui.MessagePriority;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.fabricmc.api.ModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;

public class AltoClef implements ModInitializer {

    private static AltoClef instance;
    private static CommandExecutor commandExecutor;
    private Settings settings = new Settings();
    private boolean loaded = false;
    private boolean paused = false;

    public static boolean inGame() {
        return Minecraft.getInstance().player != null && Minecraft.getInstance().getConnection() != null;
    }

    public static CommandExecutor getCommandExecutor() {
        return commandExecutor;
    }

    public static AltoClef getInstance() {
        return instance;
    }

    public boolean isLoaded() {
        return loaded;
    }

    @Override
    public void onInitialize() {
        instance = this;
        EventBus.subscribe(TitleScreenEntryEvent.class, evt -> onInitializeLoad());
    }

    public synchronized void ensureInitialized() {
        if (!loaded) {
            onInitializeLoad();
        }
    }

    public synchronized void onInitializeLoad() {
        if (loaded) return;
        loaded = true;

        commandExecutor = new CommandExecutor(this);
        initializeCommands();

        Settings.load(newSettings -> {
            settings = newSettings;
            applyLegitMovementSettings(settings.isLegitMovement());
        });

        EventBus.subscribe(SendChatEvent.class, evt -> {
            String line = evt.message;
            if (commandExecutor != null && commandExecutor.isClientCommand(line)) {
                evt.cancel();
                commandExecutor.execute(line);
            }
        });

        Debug.logInternal("AltoClef 26.3 initialized successfully!");
    }

    private void initializeCommands() {
        try {
            AltoClefCommands.init();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void applyLegitMovementSettings(boolean enabled) {
        try {
            BaritoneAPI.getSettings().legitMovement.value = enabled;
        } catch (Throwable ignored) {
        }
    }

    public void stopTasks() {
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null) {
                primary.getCommandManager().execute("cancel");
            }
        } catch (Throwable ignored) {
        }
        log("Stopped all tasks.");
    }

    public Settings getModSettings() {
        return settings;
    }

    public LocalPlayer getPlayer() {
        return Minecraft.getInstance().player;
    }

    public ClientLevel getWorld() {
        return Minecraft.getInstance().level;
    }

    public void log(String message) {
        log(message, MessagePriority.TIMELY);
    }

    public void log(String message, MessagePriority priority) {
        Debug.logMessage(message);
    }

    public void logWarning(String message) {
        Debug.logWarning(message);
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean pausing) {
        this.paused = pausing;
    }
}
