package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class MineBlockTask extends Task {

    private final AltoClef mod;
    private final String resourceName;
    private final String blockTarget;
    private final int targetCount;
    private boolean finished = false;

    public MineBlockTask(AltoClef mod, String resourceName, String blockTarget, int targetCount) {
        this.mod = mod;
        this.resourceName = resourceName;
        this.blockTarget = blockTarget;
        this.targetCount = targetCount;
    }

    @Override
    protected void onStart() {
        finished = false;
        setDebugState("Target: " + targetCount + "x " + resourceName);
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null) {
                primary.getCommandManager().execute("mine " + blockTarget);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    protected Task onTick() {
        int current = getCurrentCount();
        setDebugState(current + " / " + targetCount + " " + resourceName);

        if (current >= targetCount) {
            finished = true;
            return null;
        }

        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null && !primary.getMineProcess().isActive() && !primary.getPathingBehavior().isPathing()) {
                // Restart mining if idle
                primary.getCommandManager().execute("mine " + blockTarget);
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
                primary.getMineProcess().cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    private int getCurrentCount() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        Inventory inv = mc.player.getInventory();
        int count = 0;
        String lowerTarget = resourceName.toLowerCase();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                String itemName = stack.getItem().toString().toLowerCase();
                if (itemName.contains(lowerTarget) || lowerTarget.contains(itemName)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof MineBlockTask task) {
            return task.blockTarget.equals(this.blockTarget) && task.targetCount == this.targetCount;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Mining " + resourceName + " (" + blockTarget + ")";
    }
}
