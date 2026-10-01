package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.control.RenderDistanceManager;
import adris.altoclef.tasks.entity.KillTargetTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Modern Reactive Mob Drop Hunting & Looting Engine:
 * Hunts specified mob types and actively vacuums their dropped loot entities
 * until the requested item quantity is secured in inventory.
 */
public class KillAndLootTask extends Task {

    private final String mobQuery;
    private final String itemTarget;
    private final int targetCount;
    private Task currentSubTask = null;
    private Vec3 lastDeathPos = null;
    private int postDeathLootWaitTicks = 0;
    private boolean finished = false;

    public KillAndLootTask(String mobQuery, String itemTarget, int targetCount) {
        this.mobQuery = mobQuery.trim().toLowerCase();
        this.itemTarget = itemTarget.trim().toLowerCase();
        this.targetCount = Math.max(1, targetCount);
    }

    public KillAndLootTask(String mobQuery, String itemTarget) {
        this(mobQuery, itemTarget, 1);
    }

    @Override
    protected void onStart() {
        finished = false;
        currentSubTask = null;
        lastDeathPos = null;
        postDeathLootWaitTicks = 0;
        setDebugState("Hunting " + mobQuery + " for " + targetCount + "x " + itemTarget + "...");
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return null;

        // 1. Completion Check: Player has gathered enough of target item
        int currentCount = InventoryManager.countItems(player, itemTarget);
        if (currentCount >= targetCount) {
            setDebugState("Acquired " + currentCount + " / " + targetCount + " " + itemTarget + "!");
            if (currentSubTask != null) {
                currentSubTask.stop();
                currentSubTask = null;
            }
            finished = true;
            return null;
        }

        // 2. VACUUM DROPPED ITEMS: Search for dropped target item entities within 32 blocks
        ItemEntity droppedItem = findNearbyDroppedTarget(mc, player, 32.0);
        if (droppedItem != null) {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(droppedItem.blockPosition(), 1));
            }
            setDebugState("Collecting dropped " + itemTarget + " (" + (int) player.distanceTo(droppedItem) + "m away)...");
            return null;
        }

        // 3. POST-DEATH LOOT SCOOP: If a mob recently died, navigate directly over its death position
        if (lastDeathPos != null && postDeathLootWaitTicks > 0) {
            postDeathLootWaitTicks--;
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(BlockPos.containing(lastDeathPos), 1));
            }
            setDebugState("Scooping loot from defeated " + mobQuery + "...");
            return null;
        }

        // 4. ACTIVE COMBAT CONTINUATION
        if (currentSubTask != null && !currentSubTask.isFinished()) {
            if (currentSubTask instanceof KillTargetTask killTask) {
                return killTask;
            }
        }

        // If subtask just finished (target died), trigger loot scooping
        if (currentSubTask != null && currentSubTask.isFinished()) {
            currentSubTask.stop();
            currentSubTask = null;
            postDeathLootWaitTicks = 30; // Wait/search for loot for 1.5 seconds
        }

        // 5. LOCATE NEXT MOB TARGET
        LivingEntity nextTarget = findTargetMob(mc, player, 48.0);
        if (nextTarget != null) {
            lastDeathPos = nextTarget.position();
            setDebugState("Engaging " + nextTarget.getName().getString() + " for " + itemTarget + " (" + currentCount + "/" + targetCount + ")");
            currentSubTask = new KillTargetTask(nextTarget);
            return currentSubTask;
        }

        // 6. EXPLORATION / MOB SEARCH WANDER
        RenderDistanceManager.requestSearchBoost(mc, 18, 60);
        setDebugState("Searching for " + mobQuery + " to obtain " + itemTarget + " (" + currentCount + "/" + targetCount + ")...");

        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone != null && !baritone.getPathingBehavior().isPathing() && !baritone.getCustomGoalProcess().isActive()) {
            double angle = (player.tickCount / 120.0);
            BlockPos explorePos = player.blockPosition().offset((int) (Math.cos(angle) * 40), 0, (int) (Math.sin(angle) * 40));
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(explorePos, 4));
        }

        return null;
    }

    private ItemEntity findNearbyDroppedTarget(Minecraft mc, LocalPlayer player, double maxDist) {
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
                    if (name.contains(itemTarget)) {
                        best = itemEntity;
                        bestDistSq = dSq;
                    }
                }
            }
        }
        return best;
    }

    private LivingEntity findTargetMob(Minecraft mc, LocalPlayer player, double maxDist) {
        if (mc.level == null) return null;
        double maxDistSq = maxDist * maxDist;
        LivingEntity closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity living) || !living.isAlive() || living.isRemoved() || living == player) continue;
            if (living.isBaby()) continue;

            String typePath = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()).getPath().toLowerCase();
            String name = living.getName().getString().toLowerCase();

            boolean match = typePath.contains(mobQuery) || name.contains(mobQuery);
            if (match) {
                double dSq = player.distanceToSqr(living);
                if (dSq <= maxDistSq && dSq < closestDistSq) {
                    closestDistSq = dSq;
                    closest = living;
                }
            }
        }
        return closest;
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
            if (InventoryManager.countItems(mc.player, itemTarget) >= targetCount) {
                finished = true;
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof KillAndLootTask t) {
            return t.mobQuery.equals(this.mobQuery) &&
                   t.itemTarget.equals(this.itemTarget) &&
                   t.targetCount == this.targetCount;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Kill & Loot " + mobQuery + " -> " + targetCount + "x " + itemTarget;
    }
}
