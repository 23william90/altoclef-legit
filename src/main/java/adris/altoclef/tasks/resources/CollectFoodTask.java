package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.control.RenderDistanceManager;
import adris.altoclef.tasks.construction.MineBlockTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.entity.KillTargetTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Modern Reactive Food Gathering & Survival Engine:
 * Dynamically acquires food through village hay bales, mature crops, cattle hunting,
 * ground drops, and automatic smelting/crafting.
 */
public class CollectFoodTask extends Task {

    private static final Set<String> EDIBLE_FOOD_NAMES = Set.of(
            "bread", "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton",
            "cooked_cod", "cooked_salmon", "baked_potato", "carrot", "apple",
            "golden_apple", "enchanted_golden_apple", "golden_carrot", "sweet_berries"
    );

    private final int targetUnits;
    private Task currentSubTask = null;
    private long lastSearchTime = 0;
    private boolean finished = false;

    public CollectFoodTask(int targetUnits) {
        this.targetUnits = Math.max(1, targetUnits);
    }

    public CollectFoodTask() {
        this(20); // 1 full hunger bar (20 food units) by default
    }

    @Override
    protected void onStart() {
        finished = false;
        currentSubTask = null;
        lastSearchTime = 0;
        setDebugState("Gathering " + targetUnits + " food units (Reactive Survival Tree)...");
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return null;

        // 1. Completion Check: Ready-to-eat food in inventory meets or exceeds target
        int readyFood = calculateReadyFood(player);
        if (readyFood >= targetUnits) {
            setDebugState("Acquired " + readyFood + " / " + targetUnits + " food units!");
            if (currentSubTask != null) {
                currentSubTask.stop();
                currentSubTask = null;
            }
            finished = true;
            return null;
        }

        // 2. Active Subtask Continuation
        // If actively crafting bread/wheat or smelting meat in furnace, let subtask run!
        if (currentSubTask != null && !currentSubTask.isFinished()) {
            if (currentSubTask instanceof CraftInTableTask || currentSubTask instanceof SmeltInFurnaceTask) {
                return currentSubTask;
            }
            if (currentSubTask instanceof KillTargetTask killTask && !killTask.isFinished()) {
                return currentSubTask;
            }
            if (currentSubTask instanceof MineBlockTask mineTask && !mineTask.isFinished()) {
                return currentSubTask;
            }
        }

        // 3. VACUUM DROPPED FOOD: Scoop any edible items on the ground within 20m
        ItemEntity droppedFood = findNearbyDroppedFood(mc, player, 20.0);
        if (droppedFood != null) {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(droppedFood.blockPosition(), 1));
            }
            setDebugState("Picking up dropped food: " + droppedFood.getItem().getHoverName().getString());
            return null;
        }

        // 4. INVENTORY CONVERSION: Convert raw resources into ready-to-eat food
        int totalPotential = calculateTotalFoodPotential(player);
        if (totalPotential >= targetUnits) {
            Task conversion = getFoodConversionTask(player);
            if (conversion != null) {
                return switchSubTask(conversion);
            }
        }

        // 5. HAY BALES (VILLAGES): 1 Hay block = 9 Wheat = 3 Bread (15 hunger units!)
        BlockPos nearbyHay = findNearbyBlock(mc, player, Blocks.HAY_BLOCK, 48);
        if (nearbyHay != null) {
            setDebugState("Found Hay Bale at " + nearbyHay.toShortString() + ". Harvesting for Bread...");
            int neededHay = Math.max(1, (targetUnits - readyFood + 14) / 15);
            return switchSubTask(new MineBlockTask(AltoClef.getInstance(), "hay block", "hay_block", neededHay));
        }

        // 6. MATURE CROPS: Carrots, Potatoes, Mature Wheat, Beetroots
        BlockPos matureCrop = findNearbyMatureCrop(mc, player, 32);
        if (matureCrop != null) {
            BlockState cropState = mc.level.getBlockState(matureCrop);
            String cropName = cropState.getBlock().getDescriptionId().toLowerCase();
            setDebugState("Harvesting mature crop at " + matureCrop.toShortString() + "...");
            return switchSubTask(new MineBlockTask(AltoClef.getInstance(), "mature crop", cropName, 1));
        }

        // 7. PASSIVE CATTLE HUNTING: Adult Cows, Pigs, Chickens, Sheep, Rabbits
        LivingEntity bestAnimal = findBestFoodAnimal(mc, player, 48.0);
        if (bestAnimal != null) {
            setDebugState("Hunting " + bestAnimal.getName().getString() + " for meat...");
            return switchSubTask(new KillTargetTask(bestAnimal));
        }

        // 8. SWEET BERRY BUSHES (Forests / Taigas)
        BlockPos berryBush = findNearbyBerryBush(mc, player, 24);
        if (berryBush != null) {
            setDebugState("Harvesting Sweet Berries at " + berryBush.toShortString() + "...");
            return switchSubTask(new MineBlockTask(AltoClef.getInstance(), "sweet berries", "sweet_berry_bush", 4));
        }

        // 9. IF RAW MEAT EXISTS BUT NOT ENOUGH FOR TARGET: Cook what we currently have!
        Task partialCooking = getFoodConversionTask(player);
        if (partialCooking != null) {
            return switchSubTask(partialCooking);
        }

        // 10. EXPLORATION / SEARCH WANDER: No food in current chunks -> search outward!
        RenderDistanceManager.requestSearchBoost(mc, 16, 60);
        setDebugState("Exploring terrain to locate animals or village...");
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone != null && !baritone.getPathingBehavior().isPathing() && !baritone.getCustomGoalProcess().isActive()) {
            double angle = (player.tickCount / 100.0);
            BlockPos explorePos = player.blockPosition().offset((int) (Math.cos(angle) * 48), 0, (int) (Math.sin(angle) * 48));
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(explorePos, 4));
        }

        return null;
    }

    private Task switchSubTask(Task next) {
        if (currentSubTask == null || !currentSubTask.equals(next)) {
            if (currentSubTask != null && !currentSubTask.isFinished()) {
                currentSubTask.stop(next);
            }
            currentSubTask = next;
        }
        return currentSubTask;
    }

    private Task getFoodConversionTask(LocalPlayer player) {
        // Step A: Convert Hay Bales to Wheat
        if (InventoryManager.countItems(player, "hay_block") > 0) {
            setDebugState("Crafting Wheat from Hay Bales...");
            return new CraftInTableTask("wheat", 9);
        }

        // Step B: Craft Wheat into Bread
        if (InventoryManager.countItems(player, "wheat") >= 3) {
            setDebugState("Crafting Bread from Wheat...");
            return new CraftInTableTask("bread", 1);
        }

        // Step C: Smelt Raw Meats in Furnace
        int rawBeef = InventoryManager.countItems(player, "beef");
        if (rawBeef > 0) return new SmeltInFurnaceTask("beef", rawBeef);

        int rawPork = InventoryManager.countItems(player, "porkchop");
        if (rawPork > 0) return new SmeltInFurnaceTask("porkchop", rawPork);

        int rawMutton = InventoryManager.countItems(player, "mutton");
        if (rawMutton > 0) return new SmeltInFurnaceTask("mutton", rawMutton);

        int rawChicken = InventoryManager.countItems(player, "chicken");
        if (rawChicken > 0) return new SmeltInFurnaceTask("chicken", rawChicken);

        int rawPotato = InventoryManager.countItems(player, "potato");
        if (rawPotato > 0) return new SmeltInFurnaceTask("potato", rawPotato);

        int rawCod = InventoryManager.countItems(player, "cod");
        if (rawCod > 0) return new SmeltInFurnaceTask("cod", rawCod);

        int rawSalmon = InventoryManager.countItems(player, "salmon");
        if (rawSalmon > 0) return new SmeltInFurnaceTask("salmon", rawSalmon);

        return null;
    }

    public static int calculateReadyFood(LocalPlayer player) {
        if (player == null) return 0;
        int ready = 0;
        ready += InventoryManager.countItems(player, "bread") * 5;
        ready += InventoryManager.countItems(player, "cooked_beef") * 8;
        ready += InventoryManager.countItems(player, "cooked_porkchop") * 8;
        ready += InventoryManager.countItems(player, "cooked_chicken") * 6;
        ready += InventoryManager.countItems(player, "cooked_mutton") * 6;
        ready += InventoryManager.countItems(player, "baked_potato") * 5;
        ready += InventoryManager.countItems(player, "cooked_cod") * 5;
        ready += InventoryManager.countItems(player, "cooked_salmon") * 6;
        ready += InventoryManager.countItems(player, "golden_carrot") * 6;
        ready += InventoryManager.countItems(player, "apple") * 4;
        ready += InventoryManager.countItems(player, "golden_apple") * 4;
        ready += InventoryManager.countItems(player, "carrot") * 3;
        ready += InventoryManager.countItems(player, "sweet_berries") * 2;
        return ready;
    }

    public static int calculateTotalFoodPotential(LocalPlayer player) {
        if (player == null) return 0;
        int potential = calculateReadyFood(player);
        potential += InventoryManager.countItems(player, "hay_block") * 15;
        potential += (InventoryManager.countItems(player, "wheat") / 3) * 5;
        potential += InventoryManager.countItems(player, "beef") * 8;
        potential += InventoryManager.countItems(player, "porkchop") * 8;
        potential += InventoryManager.countItems(player, "chicken") * 6;
        potential += InventoryManager.countItems(player, "mutton") * 6;
        potential += InventoryManager.countItems(player, "potato") * 5;
        potential += InventoryManager.countItems(player, "cod") * 5;
        potential += InventoryManager.countItems(player, "salmon") * 6;
        return potential;
    }

    private ItemEntity findNearbyDroppedFood(Minecraft mc, LocalPlayer player, double maxDist) {
        if (mc.level == null) return null;
        double maxDistSq = maxDist * maxDist;
        ItemEntity best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ItemEntity itemEntity && e.isAlive() && !e.isRemoved()) {
                double dSq = player.distanceToSqr(e);
                if (dSq <= maxDistSq && dSq < bestDistSq) {
                    ItemStack stack = itemEntity.getItem();
                    String name = InventoryManager.getItemName(stack);
                    if (isEdibleFood(name) || name.contains("hay_block") || name.contains("wheat") || name.contains("raw")) {
                        best = itemEntity;
                        bestDistSq = dSq;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isEdibleFood(String name) {
        for (String f : EDIBLE_FOOD_NAMES) {
            if (name.contains(f)) return true;
        }
        return false;
    }

    private BlockPos findNearbyBlock(Minecraft mc, LocalPlayer player, net.minecraft.world.level.block.Block targetBlock, int radius) {
        if (mc.level == null) return null;
        BlockPos pPos = player.blockPosition();
        BlockPos bestPos = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int y = -4; y <= 6; y++) {
            for (int x = -radius; x <= radius; x += 2) {
                for (int z = -radius; z <= radius; z += 2) {
                    BlockPos check = pPos.offset(x, y, z);
                    if (mc.level.getBlockState(check).is(targetBlock)) {
                        double dSq = player.distanceToSqr(Vec3.atCenterOf(check));
                        if (dSq < bestDistSq) {
                            bestDistSq = dSq;
                            bestPos = check;
                        }
                    }
                }
            }
        }
        return bestPos;
    }

    private BlockPos findNearbyMatureCrop(Minecraft mc, LocalPlayer player, int radius) {
        if (mc.level == null) return null;
        BlockPos pPos = player.blockPosition();
        BlockPos bestPos = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int y = -3; y <= 3; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos check = pPos.offset(x, y, z);
                    BlockState state = mc.level.getBlockState(check);
                    if (state.getBlock() instanceof CropBlock crop) {
                        if (crop.isMaxAge(state)) {
                            double dSq = player.distanceToSqr(Vec3.atCenterOf(check));
                            if (dSq < bestDistSq) {
                                bestDistSq = dSq;
                                bestPos = check;
                            }
                        }
                    }
                }
            }
        }
        return bestPos;
    }

    private BlockPos findNearbyBerryBush(Minecraft mc, LocalPlayer player, int radius) {
        if (mc.level == null) return null;
        BlockPos pPos = player.blockPosition();
        BlockPos bestPos = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int y = -3; y <= 3; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos check = pPos.offset(x, y, z);
                    BlockState state = mc.level.getBlockState(check);
                    if (state.is(Blocks.SWEET_BERRY_BUSH)) {
                        try {
                            int age = state.getValue(SweetBerryBushBlock.AGE);
                            if (age >= 2) {
                                double dSq = player.distanceToSqr(Vec3.atCenterOf(check));
                                if (dSq < bestDistSq) {
                                    bestDistSq = dSq;
                                    bestPos = check;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        return bestPos;
    }

    private LivingEntity findBestFoodAnimal(Minecraft mc, LocalPlayer player, double maxDist) {
        if (mc.level == null) return null;
        double maxDistSq = maxDist * maxDist;
        LivingEntity best = null;
        double bestScore = -1;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity living) || !living.isAlive() || living.isRemoved()) continue;
            if (living.isBaby()) continue;

            double distSq = player.distanceToSqr(living);
            if (distSq > maxDistSq) continue;

            int hungerValue = 0;
            if (living instanceof Cow || living instanceof Pig) {
                hungerValue = 8;
            } else if (living instanceof Chicken || living instanceof Sheep) {
                hungerValue = 6;
            } else if (living instanceof Rabbit) {
                hungerValue = 5;
            }

            if (hungerValue > 0) {
                // Score = hunger / distance (prefer high-nutrition close animals)
                double dist = Math.max(1.0, Math.sqrt(distSq));
                double score = hungerValue / dist;
                if (score > bestScore) {
                    bestScore = score;
                    best = living;
                }
            }
        }
        return best;
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (currentSubTask != null && !currentSubTask.isFinished()) {
            currentSubTask.stop(interruptTask);
            currentSubTask = null;
        }
    }

    @Override
    public boolean isFinished() {
        if (finished) return true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            if (calculateReadyFood(mc.player) >= targetUnits) {
                finished = true;
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CollectFoodTask t) {
            return t.targetUnits == this.targetUnits;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Collect Food (" + targetUnits + " units)";
    }
}
