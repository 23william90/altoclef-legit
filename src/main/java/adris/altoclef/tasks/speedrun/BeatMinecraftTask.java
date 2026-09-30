package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.tasks.construction.MineBlockTask;
import adris.altoclef.tasks.construction.MineBlockTask.ToolTier;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.resources.GetItemTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

public class BeatMinecraftTask extends Task {

    private final AltoClef mod;
    private Task currentSubTask;
    private long enteredEndTimestamp = 0;

    public BeatMinecraftTask(AltoClef mod) {
        this.mod = mod;
    }

    @Override
    protected void onStart() {
        setDebugState("Initializing Beat Minecraft Task (Full Reactive Task Tree)...");
        currentSubTask = null;
        enteredEndTimestamp = 0;
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            setDebugState("Waiting for player to load into world...");
            return null;
        }

        LocalPlayer player = mc.player;
        if (player.isDeadOrDying()) {
            if (currentSubTask != null) {
                currentSubTask.stop(null);
                currentSubTask = null;
            }
            setDebugState("Player died. Waiting for respawn...");
            return null;
        }

        // Pure Reactive Task Tree: Check every requirement dynamically from Dragon down to Root!
        Task nextAction = evaluateSpeedrunTree(mc, player);
        if (nextAction != null && !nextAction.equals(currentSubTask)) {
            if (currentSubTask != null && !currentSubTask.isFinished()) {
                currentSubTask.stop(nextAction);
            }
            currentSubTask = nextAction;
        }

        return currentSubTask;
    }

    /**
     * Pure Reactive Speedrun Task Tree:
     * Evaluates the current dimension and inventory status.
     * Skips any already-acquired intermediate items (e.g. iron ingots found in village, diamonds in temple).
     */
    private Task evaluateSpeedrunTree(Minecraft mc, LocalPlayer player) {
        String dimension = mc.level != null ? mc.level.dimension().toString().toLowerCase() : "overworld";

        // 1. THE END: SLAY THE ENDER DRAGON
        if (dimension.contains("the_end")) {
            if (enteredEndTimestamp == 0) {
                enteredEndTimestamp = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() - enteredEndTimestamp < 2500) {
                setDebugState("Tree: Waiting for End terrain & chunks to stabilize...");
                return null;
            }
            setDebugState("Tree: Slaying the Ender Dragon");
            return new MineBlockTask(mod, "end stone / pillars", "end_stone obsidian bedrock", 64);
        }

        // 2. THE NETHER: BLAZE RODS & ENDER PEARLS
        if (dimension.contains("nether")) {
            int blazeRods = InventoryManager.countItems(player, "blaze_rod");
            int pearls = InventoryManager.countItems(player, "ender_pearl");

            if (blazeRods < 7) {
                setDebugState("Tree [Nether]: Gathering Blaze Rods (" + blazeRods + "/7)");
                return new MineBlockTask(mod, "blaze rod / spawner", "spawner nether_bricks", 7);
            }

            if (pearls < 12) {
                setDebugState("Tree [Nether]: Gathering Gold / Bartering for Ender Pearls (" + pearls + "/12)");
                return new MineBlockTask(mod, "gold ore (for bartering)", "nether_gold_ore gold_block", 32);
            }

            // Both blaze rods and pearls secured -> return through portal to Overworld
            setDebugState("Tree [Nether]: Returning to Overworld Portal");
            return new MineBlockTask(mod, "nether portal", "nether_portal obsidian", 1);
        }

        // 3. OVERWORLD: TREE PROGRESSION
        int blazeRods = InventoryManager.countItems(player, "blaze_rod");
        int pearls = InventoryManager.countItems(player, "ender_pearl");
        int eyeOfEnder = InventoryManager.countItems(player, "ender_eye", "eye_of_ender");

        // Step 3a: Stronghold & End Portal Navigation
        if (eyeOfEnder >= 12 || (pearls >= 12 && blazeRods >= 7)) {
            if (eyeOfEnder < 12) {
                setDebugState("Tree: Crafting Eyes of Ender");
                return new CraftInTableTask("ender_eye");
            }
            setDebugState("Tree: Locating Stronghold and End Portal Frame");
            return new MineBlockTask(
                    mod, "stronghold stone",
                    "end_portal_frame stone_bricks cracked_stone_bricks mossy_stone_bricks",
                    1
            );
        }

        // Step 3b: Nether Preparation & Portal
        int obsidian = InventoryManager.countItems(player, "obsidian");
        int flintAndSteel = InventoryManager.countItems(player, "flint_and_steel", "fire_charge");
        boolean hasShield = isShieldEquipped(player) || InventoryManager.countItems(player, "shield") > 0;
        boolean hasBucket = InventoryManager.countItems(player, "bucket", "water_bucket") > 0;
        ToolTier pickTier = MineBlockTask.getPlayerPickaxeTier(player);

        // Gear Check: Ensure Shield for defense
        if (!hasShield && InventoryManager.countItems(player, "iron_ingot") > 0) {
            setDebugState("Tree: Equipping Shield for combat defense");
            return new GetItemTask(mod, "shield", 1);
        }

        // Gear Check: Ensure Bucket for MLG / water / lava
        if (!hasBucket && InventoryManager.countItems(player, "iron_ingot") >= 3) {
            setDebugState("Tree: Crafting Water Bucket");
            return new GetItemTask(mod, "bucket", 1);
        }

        // Tool Check: Need Diamond Pickaxe to mine obsidian
        if (pickTier.getLevel() < ToolTier.DIAMOND.getLevel()) {
            setDebugState("Tree: Acquiring Diamond Pickaxe (Prerequisite Tree)");
            return new GetItemTask(mod, "diamond_pickaxe", 1);
        }

        // Resource Check: Need 10 Obsidian for Nether Portal
        if (obsidian < 10) {
            setDebugState("Tree: Mining Obsidian for Nether Portal (" + obsidian + "/10)");
            return new MineBlockTask(mod, "obsidian", "obsidian", 10);
        }

        // Resource Check: Need Flint & Steel to ignite Nether Portal
        if (flintAndSteel < 1) {
            setDebugState("Tree: Acquiring Flint & Steel");
            return new GetItemTask(mod, "flint_and_steel", 1);
        }

        // Enter Nether Portal
        setDebugState("Tree: Constructing & Entering Nether Portal");
        return new MineBlockTask(mod, "nether portal", "nether_portal obsidian", 1);
    }

    private boolean isShieldEquipped(LocalPlayer player) {
        if (player == null) return false;
        ItemStack offhand = player.getOffhandItem();
        return offhand != null && !offhand.isEmpty() &&
                (offhand.is(net.minecraft.world.item.Items.SHIELD) || InventoryManager.getItemName(offhand).contains("shield"));
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (stopped() && currentSubTask != null) {
            currentSubTask.stop(interruptTask);
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BeatMinecraftTask;
    }

    @Override
    protected String toDebugString() {
        return "Beat Minecraft (Task Tree Architecture)";
    }
}
