package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.WorldMemoryTracker;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.time.Stopwatch;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;

// A task chain that runs a user defined task at the same priority.
// This basically replaces our old Task Runner.
public class UserTaskChain extends SingleTaskChain {

    private final Stopwatch taskStopwatch = new Stopwatch();
    private Runnable currentOnFinish = null;

    private boolean runningIdleTask;
    private boolean nextTaskIdleFlag;

    public UserTaskChain(TaskRunner runner) {
        super(runner);
    }

    private static String prettyPrintTimeDuration(double seconds) {
        int minutes = (int) (seconds / 60);
        int hours = minutes / 60;
        int days = hours / 24;

        String result = "";
        if (days != 0) {
            result += days + " days ";
        }
        if (hours != 0) {
            result += (hours % 24) + " hours ";
        }
        if (minutes != 0) {
            result += (minutes % 60) + " minutes ";
        }
        if (!result.isEmpty()) {
            result += "and ";
        }
        result += String.format("%.3f", (seconds % 60));
        return result;
    }

    @Override
    protected void onTick() {

        // Pause if we're not loaded into a world.
        if (!AltoClef.inGame()) return;

        super.onTick();
    }

    public void cancel(AltoClef mod) {
        WorldMemoryTracker.getInstance().clearBlacklist();
        if (mainTask != null && mainTask.isActive()) {
            stop();
            onTaskFinish(mod);
        }
        mod.getTaskRunner().disable();
        releaseInputsAndBlockBreaking(mod);

        // FIXME kinda junk, the whole pausing should probably be moved to this class
        mod.setStoredTask(null);
        mod.setPaused(false);
    }

    @Override
    public float getPriority() {
        return 50;
    }

    @Override
    public String getName() {
        return "User Tasks";
    }

    public void runTask(AltoClef mod, Task task, Runnable onFinish) {
        runningIdleTask = nextTaskIdleFlag;
        nextTaskIdleFlag = false;

        currentOnFinish = onFinish;

        if (mainTask != null) {
            mainTask.stop(task);
            mainTask = null;
        }

        if (!runningIdleTask) {
            Debug.logMessage("User Task Set: " + task.toString());
        }
        mod.getTaskRunner().enable();
        taskStopwatch.begin();
        setTask(task);

        if (mod.getModSettings().failedToLoad()) {
            Debug.logWarning("Settings file failed to load at some point. Check logs for more info, or delete the" +
                    " file to re-load working settings.");
        }
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        boolean shouldIdle = mod.getModSettings().shouldRunIdleCommandWhenNotActive();
        if (!shouldIdle) {
            // Stop.
            mod.getTaskRunner().disable();
            // Extra reset. Sometimes baritone is laggy and doesn't properly reset our press
            releaseInputsAndBlockBreaking(mod);
        }
        double seconds = taskStopwatch.time();
        Task oldTask = mainTask;
        mainTask = null;
        if (currentOnFinish != null) {
            currentOnFinish.run();
        }
        // our `onFinish` might have triggered more tasks.
        boolean actuallyDone = mainTask == null;
        if (actuallyDone) {
            releaseInputsAndBlockBreaking(mod);
            WorldMemoryTracker.getInstance().clearBlacklist();
            if (!runningIdleTask) {
                Debug.logMessage("User task FINISHED. Took %s seconds.", prettyPrintTimeDuration(seconds));
                EventBus.publish(new TaskFinishedEvent(seconds, oldTask));
            }
            if (shouldIdle) {
                AltoClef.getCommandExecutor().executeWithPrefix(mod.getModSettings().getIdleCommand());
                signalNextTaskToBeIdleTask();
                runningIdleTask = true;
            }
        }
    }

    private void releaseInputsAndBlockBreaking(AltoClef mod) {
        try {
            if (mod.getClientBaritone() != null) {
                if (mod.getClientBaritone().getPathingBehavior() != null) {
                    mod.getClientBaritone().getPathingBehavior().forceCancel();
                }
                if (mod.getClientBaritone().getInputOverrideHandler() != null) {
                    mod.getClientBaritone().getInputOverrideHandler().clearAllKeys();
                    mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                }
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameMode != null) {
                mc.gameMode.stopDestroyBlock();
            }
            if (mc.options != null && mc.options.keyAttack != null) {
                mc.options.keyAttack.setDown(false);
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean isRunningIdleTask() {
        return isActive() && runningIdleTask;
    }

    // The next task will be an idle task.
    public void signalNextTaskToBeIdleTask() {
        nextTaskIdleFlag = true;
    }
}
