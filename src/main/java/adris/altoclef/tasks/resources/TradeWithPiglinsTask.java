package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.tasks.construction.MineBlockTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * Modern Reactive Piglin Bartering Engine:
 * Equips gold armor for safety, locates adult Piglins, tosses gold ingots,
 * and vacuums up bartered drops (Ender Pearls, Fire Resistance, Obsidian).
 */
public class TradeWithPiglinsTask extends Task {

    private final AltoClef mod;
    private final String targetItem;
    private final int targetCount;
    private int barterCooldown = 0;
    private Task subTask = null;
    private Piglin targetPiglin = null;

    public TradeWithPiglinsTask(AltoClef mod, String targetItem, int targetCount) {
        this.mod = mod;
        this.targetItem = targetItem;
        this.targetCount = targetCount;
    }

    public TradeWithPiglinsTask(AltoClef mod, int enderPearls) {
        this(mod, "ender_pearl", enderPearls);
    }

    @Override
    protected void onStart() {
        barterCooldown = 0;
        subTask = null;
        targetPiglin = null;
        setDebugState("Initializing Piglin Bartering Engine for " + targetItem + " (" + targetCount + ")...");
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) return null;

        // 1. Completion Check
        int currentCount = InventoryManager.countItems(player, targetItem);
        if (currentCount >= targetCount) {
            setDebugState("Acquired " + currentCount + "/" + targetCount + " " + targetItem + " from Piglin Bartering!");
            return null;
        }

        // 2. Ensure Gold Armor is equipped (prevents Piglin aggression)
        if (!hasGoldArmorEquipped(player)) {
            // Check inventory for any golden armor piece
            int goldArmorSlot = findGoldArmorInInventory(player);
            if (goldArmorSlot != -1) {
                // Equip it
                equipArmorFromSlot(mc, player, goldArmorSlot);
                setDebugState("Equipping Golden Armor piece...");
                return null;
            } else if (InventoryManager.countItems(player, "gold_ingot") >= 5) {
                setDebugState("Crafting Golden Helmet for Piglin pacification...");
                return new GetItemTask(mod, "golden_helmet", 1);
            }
        }

        // 3. Priority: Vacuum up any nearby bartered ground items (pearls, drops)
        ItemEntity droppedTarget = findNearbyTargetDrop(mc, player, 16.0);
        if (droppedTarget != null) {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(droppedTarget.blockPosition(), 0));
            }
            setDebugState("Picking up bartered " + targetItem + " drop!");
            return null;
        }

        // Also vacuum other nearby item entities near piglins if close
        ItemEntity anyDrop = findNearbyDrop(mc, player, 5.0);
        if (anyDrop != null && player.distanceToSqr(anyDrop) > 1.5) {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(anyDrop.blockPosition(), 0));
            }
            setDebugState("Scooping up bartered drops...");
            return null;
        }

        // 4. Ensure player has Gold Ingots to barter
        int goldIngots = InventoryManager.countItems(player, "gold_ingot");
        if (goldIngots < 1) {
            // Check for gold nuggets
            int nuggets = InventoryManager.countItems(player, "gold_nugget");
            if (nuggets >= 9) {
                setDebugState("Crafting Gold Ingot from nuggets...");
                return new CraftInTableTask("gold_ingot");
            }
            // Acquire gold ingots (mine nether gold ore or smelt)
            setDebugState("Need Gold Ingots to barter with Piglins (Mining Nether Gold)...");
            return new GetItemTask(mod, "gold_ingot", Math.max(8, (targetCount - currentCount) * 2));
        }

        // 5. Cooldown between tossing gold
        if (barterCooldown-- > 0) {
            setDebugState("Waiting for Piglin bartering roll (" + barterCooldown + " ticks)...");
            return null;
        }

        // 6. Find nearest adult Piglin
        if (targetPiglin == null || !targetPiglin.isAlive() || targetPiglin.isRemoved() || targetPiglin.isBaby()) {
            targetPiglin = findNearestAdultPiglin(mc, player, 32.0);
        }

        if (targetPiglin == null) {
            // Wander / explore in Nether to find Piglins
            setDebugState("Searching for Piglins in Nether (Crimson Forest / Nether Wastes)...");
            return new MineBlockTask(mod, "nether gold or crimson terrain", "nether_gold_ore crimson_nylium netherrack", 1);
        }

        double distSq = player.distanceToSqr(targetPiglin);
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

        // 7. Approach Piglin within 3.5 blocks
        if (distSq > 12.0) {
            if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(targetPiglin.blockPosition(), 3));
            }
            setDebugState("Approaching Piglin at " + targetPiglin.blockPosition().toShortString() + " (" + (int) Math.sqrt(distSq) + "m)...");
            return null;
        }

        // 8. Close enough: Look at Piglin's feet and toss 1 Gold Ingot!
        int goldSlot = ensureHeldGoldIngot(player);
        if (goldSlot == -1) {
            setDebugState("Equipping Gold Ingot to hotbar...");
            return null;
        }

        lookAt(player, targetPiglin.position());
        // Drop 1 single gold ingot
        mc.gameMode.dropItem(player, false);
        AltoClef.getInstance().log("Tossed Gold Ingot to Piglin at " + targetPiglin.blockPosition().toShortString());

        barterCooldown = 120; // Piglin takes ~6-8 seconds to inspect gold and drop item
        setDebugState("Tossed Gold Ingot! Awaiting bartered drop...");
        return null;
    }

    private boolean hasGoldArmorEquipped(LocalPlayer player) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                String name = InventoryManager.getItemName(stack);
                if (name.contains("golden_") || name.contains("gold_")) {
                    return true;
                }
            }
        }
        return false;
    }

    private int findGoldArmorInInventory(LocalPlayer player) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                String name = InventoryManager.getItemName(stack);
                if (name.contains("golden_helmet") || name.contains("golden_chestplate")
                        || name.contains("golden_leggings") || name.contains("golden_boots")) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void equipArmorFromSlot(Minecraft mc, LocalPlayer player, int invSlot) {
        if (invSlot < 9) {
            player.getInventory().setSelectedSlot(invSlot);
            mc.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND);
        } else {
            // Swap to hotbar slot 0 then use
            int emptyHotbar = 0;
            player.getInventory().setSelectedSlot(emptyHotbar);
            InventoryManager.swapToHotbarSlot(mc, player, invSlot, emptyHotbar);
            mc.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    private int ensureHeldGoldIngot(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && InventoryManager.getItemName(stack).equals("gold_ingot")) {
                player.getInventory().setSelectedSlot(i);
                return i;
            }
        }
        // Check main inventory and swap to hotbar
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && InventoryManager.getItemName(stack).equals("gold_ingot")) {
                InventoryManager.swapToHotbarSlot(Minecraft.getInstance(), player, i, 0);
                player.getInventory().setSelectedSlot(0);
                return 0;
            }
        }
        return -1;
    }

    private Piglin findNearestAdultPiglin(Minecraft mc, LocalPlayer player, double maxDist) {
        if (mc.level == null) return null;
        double maxDistSq = maxDist * maxDist;
        Piglin nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof Piglin piglin && piglin.isAlive() && !piglin.isBaby()) {
                double dSq = player.distanceToSqr(piglin);
                if (dSq <= maxDistSq && dSq < nearestDistSq) {
                    nearest = piglin;
                    nearestDistSq = dSq;
                }
            }
        }
        return nearest;
    }

    private ItemEntity findNearbyTargetDrop(Minecraft mc, LocalPlayer player, double radius) {
        if (mc.level == null) return null;
        double rSq = radius * radius;
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ItemEntity item && item.isAlive()) {
                String name = InventoryManager.getItemName(item.getItem());
                if (name.contains(targetItem)) {
                    if (player.distanceToSqr(item) <= rSq) {
                        return item;
                    }
                }
            }
        }
        return null;
    }

    private ItemEntity findNearbyDrop(Minecraft mc, LocalPlayer player, double radius) {
        if (mc.level == null) return null;
        double rSq = radius * radius;
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ItemEntity item && item.isAlive()) {
                if (player.distanceToSqr(item) <= rSq) {
                    return item;
                }
            }
        }
        return null;
    }

    private void lookAt(LocalPlayer player, Vec3 target) {
        Vec3 diff = target.subtract(player.getEyePosition());
        double distXZ = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(diff.z, diff.x))) - 90.0F;
        float pitch = (float) (-Math.toDegrees(Math.atan2(diff.y, distXZ)));
        player.setYRot(yaw);
        player.setXRot(pitch);
    }

    @Override
    protected void onStop(Task interruptTask) {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone != null && baritone.getCustomGoalProcess().isActive()) {
            baritone.getCustomGoalProcess().path();
        }
    }

    @Override
    public boolean isFinished() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            return InventoryManager.countItems(mc.player, targetItem) >= targetCount;
        }
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof TradeWithPiglinsTask task) {
            return task.targetItem.equals(targetItem) && task.targetCount == targetCount;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Trade with Piglins for " + targetItem + " (" + targetCount + ")";
    }
}
