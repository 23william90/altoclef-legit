package adris.altoclef.tasks.movement;

import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;

public class GotoTask extends Task {

    private final String destination;
    private boolean finished = false;

    public GotoTask(String destination) {
        this.destination = destination;
    }

    @Override
    protected void onStart() {
        finished = false;
        setDebugState("Navigating to: " + destination);
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null) {
                primary.getCommandManager().execute("goto " + destination);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    protected Task onTick() {
        setDebugState("Pathing to " + destination);
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null) {
                if (!primary.getPathingBehavior().isPathing() && !primary.getCustomGoalProcess().isActive()) {
                    finished = true;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null) {
                primary.getPathingBehavior().cancelEverything();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GotoTask task) {
            return task.destination.equalsIgnoreCase(this.destination);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Goto: " + destination;
    }
}
