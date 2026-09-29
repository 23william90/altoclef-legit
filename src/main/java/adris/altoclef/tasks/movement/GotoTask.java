package adris.altoclef.tasks.movement;

import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;

import baritone.api.pathing.goals.Goal;
import net.minecraft.client.Minecraft;

public class GotoTask extends Task {

    private final String destination;
    private boolean finished = false;
    private long lastResumeTime = 0;
    private int failCount = 0;

    public GotoTask(String destination) {
        this.destination = destination;
    }

    @Override
    protected void onStart() {
        finished = false;
        failCount = 0;
        setDebugState("Navigating to: " + destination);
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null) {
                primary.getCommandManager().execute("goto " + destination);
                lastResumeTime = System.currentTimeMillis();
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
                Minecraft mc = Minecraft.getInstance();
                Goal goal = primary.getCustomGoalProcess().getGoal();
                if (goal != null && mc.player != null && goal.isInGoal(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ())) {
                    finished = true;
                    return null;
                }

                if (!primary.getPathingBehavior().isPathing() && !primary.getCustomGoalProcess().isActive()) {
                    if (goal != null && mc.player != null && goal.isInGoal(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ())) {
                        finished = true;
                    } else if (System.currentTimeMillis() - lastResumeTime > 1000) {
                        lastResumeTime = System.currentTimeMillis();
                        primary.getCommandManager().execute("goto " + destination);
                    }
                } else {
                    failCount = 0;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (interruptTask == null) {
            try {
                IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (primary != null) {
                    primary.getPathingBehavior().cancelEverything();
                }
            } catch (Throwable ignored) {
            }
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
