package adris.altoclef;

import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.commandsystem.TabCompleter;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ClientRenderEvent;
import adris.altoclef.eventbus.events.ClientTickEvent;
import adris.altoclef.eventbus.events.SendChatEvent;
import adris.altoclef.eventbus.events.TitleScreenEntryEvent;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.ui.CommandStatusOverlay;
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
    private TaskRunner taskRunner;
    private UserTaskChain userTaskChain;
    private CommandStatusOverlay commandStatusOverlay;
    private Task storedTask;
    private Settings settings = new Settings();
    private boolean loaded = false;
    private boolean paused = false;
    private boolean tabCompleterRegistered = false;

    public static boolean inGame() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null && mc.getConnection() != null;
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

        taskRunner = new TaskRunner(this);
        userTaskChain = new UserTaskChain(taskRunner);
        commandStatusOverlay = new CommandStatusOverlay();

        Settings.load(newSettings -> {
            settings = newSettings;
            applyLegitMovementSettings(settings.isLegitMovement());
        });

        // Intercept client chat messages for '@' commands
        EventBus.subscribe(SendChatEvent.class, evt -> {
            String line = evt.message;
            if (commandExecutor != null && commandExecutor.isClientCommand(line)) {
                evt.cancel();
                commandExecutor.execute(line);
            }
        });

        // Client tick hook
        EventBus.subscribe(ClientTickEvent.class, evt -> onClientTick());

        // Client HUD render overlay hook
        EventBus.subscribe(ClientRenderEvent.class, evt -> {
            if (commandStatusOverlay != null) {
                commandStatusOverlay.render(this, evt.extractor);
            }
        });

        Debug.logInternal("AltoClef 26.3 initialized successfully with TaskRunner and Overlay!");
    }

    private void onClientTick() {
        // Ensure TabCompleter is registered with Baritone's GameEventHandler
        if (!tabCompleterRegistered) {
            try {
                IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (primary != null) {
                    primary.getGameEventHandler().registerEventListener(new TabCompleter());
                    tabCompleterRegistered = true;
                    Debug.logInternal("AltoClef TabCompleter registered to Baritone GameEventHandler successfully!");
                }
            } catch (Throwable ignored) {
            }
        }

        if (taskRunner != null) {
            taskRunner.tick();
        }

        if (settings != null && settings.isLegitMovement() && inGame()) {
            tickLegitMovement();
        }
    }

    private void tickLegitMovement() {
        try {
            if (!BaritoneAPI.getSettings().legitMovement.value) {
                BaritoneAPI.getSettings().legitMovement.value = true;
            }
        } catch (Throwable ignored) {
        }
    }

    private void initializeCommands() {
        try {
            AltoClefCommands.init();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void runUserTask(Task task) {
        runUserTask(task, () -> {});
    }

    public void runUserTask(Task task, Runnable onFinish) {
        ensureInitialized();
        if (userTaskChain != null) {
            userTaskChain.runTask(this, task, onFinish);
        }
    }

    public void cancelUserTask() {
        if (userTaskChain != null) {
            userTaskChain.cancel(this);
        }
        stopTasks();
        if (commandStatusOverlay != null) {
            commandStatusOverlay.resetTimer();
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
    }

    public TaskRunner getTaskRunner() {
        return taskRunner;
    }

    public UserTaskChain getUserTaskChain() {
        return userTaskChain;
    }

    public IBaritone getClientBaritone() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public Task getStoredTask() {
        return storedTask;
    }

    public void setStoredTask(Task task) {
        this.storedTask = task;
    }

    public Object getBehaviour() {
        return null;
    }

    public Settings getModSettings() {
        return settings;
    }

    public LocalPlayer getPlayer() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null ? mc.player : null;
    }

    public ClientLevel getWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null ? mc.level : null;
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
