package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.MineBlockTask;
import adris.altoclef.tasksystem.Task;

public class GetItemTask extends Task {

    private final AltoClef mod;
    private final String item;
    private final int targetCount;
    private MineBlockTask mineSubTask;

    public GetItemTask(AltoClef mod, String item, int targetCount) {
        this.mod = mod;
        this.item = item;
        this.targetCount = targetCount;
    }

    public GetItemTask(String item) {
        this(AltoClef.getInstance(), item, 1);
    }

    public GetItemTask(String item, int count) {
        this(AltoClef.getInstance(), item, count);
    }

    @Override
    protected void onStart() {
        setDebugState("Acquiring " + targetCount + "x " + item);
        String resolvedBlocks = resolveBlocksForItem(item);
        mineSubTask = new MineBlockTask(mod, item, resolvedBlocks, targetCount);
    }

    @Override
    protected Task onTick() {
        setDebugState("Acquiring " + item);
        if (mineSubTask == null) {
            String resolvedBlocks = resolveBlocksForItem(item);
            mineSubTask = new MineBlockTask(mod, item, resolvedBlocks, targetCount);
        }
        if (mineSubTask.isFinished()) {
            return null;
        }
        return mineSubTask;
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (mineSubTask != null) {
            mineSubTask.stop(interruptTask);
        }
    }

    @Override
    public boolean isFinished() {
        return mineSubTask != null && mineSubTask.isFinished();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetItemTask task) {
            return task.item.equalsIgnoreCase(this.item) && task.targetCount == this.targetCount;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Get Item: " + targetCount + "x " + item;
    }

    private static String resolveBlocksForItem(String item) {
        String lower = item.toLowerCase().replace(" ", "_");
        if (lower.contains("log") || lower.equals("wood")) {
            return "oak_log,birch_log,spruce_log,jungle_log,acacia_log,dark_oak_log,mangrove_log,cherry_log,pale_oak_log";
        }
        if (lower.contains("plank")) {
            return "oak_planks,birch_planks,spruce_planks,jungle_planks,acacia_planks,dark_oak_planks,mangrove_planks,cherry_planks";
        }
        if (lower.contains("diamond")) {
            return "diamond_ore,deepslate_diamond_ore";
        }
        if (lower.contains("iron")) {
            return "iron_ore,deepslate_iron_ore,raw_iron_block";
        }
        if (lower.contains("coal")) {
            return "coal_ore,deepslate_coal_ore";
        }
        if (lower.contains("gold")) {
            return "gold_ore,deepslate_gold_ore,nether_gold_ore";
        }
        if (lower.contains("copper")) {
            return "copper_ore,deepslate_copper_ore";
        }
        if (lower.contains("emerald")) {
            return "emerald_ore,deepslate_emerald_ore";
        }
        if (lower.contains("lapis")) {
            return "lapis_ore,deepslate_lapis_ore";
        }
        if (lower.contains("redstone")) {
            return "redstone_ore,deepslate_redstone_ore";
        }
        if (lower.contains("stone") || lower.contains("cobble")) {
            return "cobblestone,stone,deepslate,cobbled_deepslate";
        }
        if (lower.contains("sand")) {
            return "sand,red_sand";
        }
        if (lower.contains("dirt")) {
            return "dirt,grass_block";
        }
        return lower;
    }
}
