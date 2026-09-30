package adris.altoclef;

import adris.altoclef.chains.DeathMenuChain;
import adris.altoclef.chains.FoodChain;
import adris.altoclef.chains.MLGBucketFallChain;
import adris.altoclef.chains.MobDefenseChain;
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
import adris.altoclef.control.InventoryManager;
import adris.altoclef.control.RenderDistanceManager;
import net.fabricmc.api.ModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Blocks;
import java.util.ArrayList;
import java.util.Arrays;

public class AltoClef implements ModInitializer {

    private static AltoClef instance;
    private static CommandExecutor commandExecutor;
    private TaskRunner taskRunner;
    private UserTaskChain userTaskChain;
    private MobDefenseChain mobDefenseChain;
    private FoodChain foodChain;
    private MLGBucketFallChain mlgBucketFallChain;
    private DeathMenuChain deathMenuChain;
    private CommandStatusOverlay commandStatusOverlay;
    private Task storedTask;
    private Settings settings = new Settings();
    private boolean loaded = false;
    private boolean paused = false;
    private boolean tabCompleterRegistered = false;
    private final InventoryManager inventoryManager = new InventoryManager();

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
        mobDefenseChain = new MobDefenseChain(taskRunner);
        foodChain = new FoodChain(taskRunner);
        mlgBucketFallChain = new MLGBucketFallChain(taskRunner);
        deathMenuChain = new DeathMenuChain(taskRunner);
        commandStatusOverlay = new CommandStatusOverlay();

        // Clean shutdown handler to prevent Mojang ClientShutdownWatchdog (-8) on client exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null && baritone.getPathingBehavior().isPathing()) {
                    baritone.getPathingBehavior().forceCancel();
                }
            } catch (Throwable ignored) {
            }
            Thread forceExit = new Thread(() -> {
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                }
                Runtime.getRuntime().halt(0);
            }, "AltoClef-CleanExitTimer");
            forceExit.setDaemon(true);
            forceExit.start();
        }, "AltoClef-ShutdownHook"));

        // Configure Baritone rendering, lines, and pillaring/throwaway block settings
        try {
            baritone.api.Settings s = BaritoneAPI.getSettings();
            s.renderPath.value = true;
            s.renderPathAsLine.value = true;
            s.renderGoal.value = true;
            s.renderSelectionBoxes.value = true;

            // Enable pillaring, bridging, jumping, and inventory movement for Baritone
            s.allowPlace.value = true;
            s.allowBreak.value = true;
            s.allowSprint.value = true;
            s.allowParkour.value = true;
            s.allowParkourPlace.value = true;
            s.allowParkourAscend.value = true;
            s.allowInventory.value = true; // Enables moving throwaways from main inventory to hotbar!
            s.blockPlacementPenalty.value = 25.0; // High placement cost so Baritone prefers walking/jumping instead of wasting blocks!
            s.jumpPenalty.value = 1.0;
            s.exploreForBlocks.value = true;
            s.mineScanDroppedItems.value = true;
            s.acceptableThrowawayItems.value = new ArrayList<>(Arrays.asList(
                    Blocks.DIRT.asItem(),
                    Blocks.COBBLESTONE.asItem(),
                    Blocks.COBBLED_DEEPSLATE.asItem(),
                    Blocks.NETHERRACK.asItem(),
                    Blocks.STONE.asItem(),
                    Blocks.SANDSTONE.asItem(),
                    Blocks.DEEPSLATE.asItem(),
                    Blocks.TUFF.asItem(),
                    Blocks.ANDESITE.asItem(),
                    Blocks.DIORITE.asItem(),
                    Blocks.GRANITE.asItem()
            ));

            // Prevent Baritone from ever breaking harmless grass or flowers during pathing
            s.blocksToDisallowBreaking.value = new ArrayList<>(Arrays.asList(
                    Blocks.SHORT_GRASS,
                    Blocks.TALL_GRASS,
                    Blocks.FERN,
                    Blocks.LARGE_FERN,
                    Blocks.DEAD_BUSH,
                    Blocks.DANDELION,
                    Blocks.POPPY,
                    Blocks.BLUE_ORCHID,
                    Blocks.ALLIUM,
                    Blocks.AZURE_BLUET,
                    Blocks.RED_TULIP,
                    Blocks.ORANGE_TULIP,
                    Blocks.WHITE_TULIP,
                    Blocks.PINK_TULIP,
                    Blocks.OXEYE_DAISY,
                    Blocks.CORNFLOWER,
                    Blocks.LILY_OF_THE_VALLEY,
                    Blocks.WITHER_ROSE,
                    Blocks.TORCHFLOWER,
                    Blocks.SUNFLOWER,
                    Blocks.LILAC,
                    Blocks.ROSE_BUSH,
                    Blocks.PEONY,
                    Blocks.PITCHER_PLANT
            ));
        } catch (Throwable ignored) {
        }

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

        Debug.logInternal("AltoClef 26.3 initialized successfully with MobDefense, FoodChain, and MLG systems!");
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

        // Run automatic inventory management (armor, tools, hotbar throwaways, shields)
        inventoryManager.tick(this);

        // Tick dynamic render distance manager (auto-reverting search boosts)
        RenderDistanceManager.tick(Minecraft.getInstance());

        if (settings != null && settings.isLegitMovement() && inGame()) {
            tickLegitMovement();
        }
    }

    public InventoryManager getInventoryManager() {
        return inventoryManager;
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

    public MobDefenseChain getMobDefenseChain() {
        return mobDefenseChain;
    }

    public FoodChain getFoodChain() {
        return foodChain;
    }

    public MLGBucketFallChain getMlgBucketFallChain() {
        return mlgBucketFallChain;
    }

    public DeathMenuChain getDeathMenuChain() {
        return deathMenuChain;
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
