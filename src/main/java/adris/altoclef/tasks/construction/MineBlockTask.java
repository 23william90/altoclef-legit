package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public class MineBlockTask extends Task {

    private final AltoClef mod;
    private final String resourceName;
    private final String blockTarget;
    private final int targetCount;
    private final Block[] resolvedBlocks;
    private final String[] blockNames;
    private boolean finished = false;
    private int cooldown = 0;

    public MineBlockTask(AltoClef mod, String resourceName, String blockTarget, int targetCount) {
        this.mod = mod;
        this.resourceName = resourceName;
        this.blockTarget = blockTarget;
        this.targetCount = targetCount;

        List<Block> blocks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String[] tokens = blockTarget.split("[,\\s]+");
        for (String token : tokens) {
            token = token.trim();
            if (token.isEmpty()) continue;
            names.add(token);
            try {
                Identifier id = Identifier.tryParse(token.contains(":") ? token : "minecraft:" + token);
                if (id != null) {
                    Block b = BuiltInRegistries.BLOCK.getValue(id);
                    if (b != null && b != Blocks.AIR) {
                        blocks.add(b);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        this.resolvedBlocks = blocks.toArray(new Block[0]);
        this.blockNames = names.toArray(new String[0]);
    }

    @Override
    protected void onStart() {
        finished = false;
        cooldown = 0;
        setDebugState("Target: " + targetCount + "x " + resourceName);
        startMining();
    }

    private void startMining() {
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null) {
                if (resolvedBlocks.length > 0) {
                    primary.getMineProcess().mine(targetCount, resolvedBlocks);
                } else if (blockNames.length > 0) {
                    primary.getMineProcess().mineByName(targetCount, blockNames);
                }
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
            onStop(null);
            return null;
        }

        if (cooldown-- <= 0) {
            cooldown = 20; // Check every 1 second
            try {
                IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (primary != null && !primary.getMineProcess().isActive() && !primary.getPathingBehavior().isPathing()) {
                    startMining();
                }
            } catch (Throwable ignored) {
            }
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
