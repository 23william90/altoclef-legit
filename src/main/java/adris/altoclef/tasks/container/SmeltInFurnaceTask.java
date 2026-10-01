package adris.altoclef.tasks.container;

import adris.altoclef.control.InventoryManager;
import adris.altoclef.tasks.construction.MineBlockTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class SmeltInFurnaceTask extends Task {

    private final String ingredientKeyword;
    private final int targetOutputCount;
    private boolean finished = false;
    private int stepTimer = 0;
    private BlockPos placedFurnacePos = null;

    // Watchdogs
    private int noSpotTicks = 0;
    private int placedFurnaceWaitTicks = 0;
    private int furnaceGuiTicks = 0;

    public SmeltInFurnaceTask(String ingredientKeyword, int targetOutputCount) {
        this.ingredientKeyword = ingredientKeyword.toLowerCase();
        this.targetOutputCount = targetOutputCount;
    }

    @Override
    protected void onStart() {
        finished = false;
        stepTimer = 0;
        placedFurnacePos = null;
        noSpotTicks = 0;
        placedFurnaceWaitTicks = 0;
        furnaceGuiTicks = 0;
        setDebugState("Smelting " + targetOutputCount + "x " + ingredientKeyword + " in Furnace...");
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) return null;

        if (stepTimer-- > 0) return null;

        // Check if output is already satisfied (e.g. iron ingots)
        String outputKeyword = getOutputKeyword();
        if (InventoryManager.countItems(player, outputKeyword) >= targetOutputCount) {
            if (player.containerMenu instanceof FurnaceMenu) {
                player.closeContainer();
            }
            finished = true;
            cancelBaritonePathing();
            return null;
        }

        // 1. Furnace menu is open!
        if (player.containerMenu instanceof FurnaceMenu menu) {
            cancelBaritonePathing();
            noSpotTicks = 0;
            placedFurnaceWaitTicks = 0;
            furnaceGuiTicks++;
            int containerId = menu.containerId;

            // Take any finished output from slot 2 (RESULT_SLOT)
            ItemStack resultStack = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
            if (!resultStack.isEmpty()) {
                mc.gameMode.handleContainerInput(containerId, AbstractFurnaceMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, player);
            }

            // Supply ingredient if slot 0 is empty
            ItemStack inStack = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem();
            if (inStack.isEmpty()) {
                int invIngSlot = findSlotInFurnace(menu, ingredientKeyword);
                if (invIngSlot != -1) {
                    mc.gameMode.handleContainerInput(containerId, invIngSlot, 0, ContainerInput.QUICK_MOVE, player);
                } else if (resultStack.isEmpty()) {
                    // No more ingredient and no more result, check if done
                    if (InventoryManager.countItems(player, outputKeyword) >= targetOutputCount) {
                        player.closeContainer();
                        finished = true;
                        return null;
                    }
                }
            }

            // Supply fuel if slot 1 is empty
            ItemStack fuelStack = menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem();
            if (fuelStack.isEmpty()) {
                int fuelSlot = findSlotInFurnace(menu, "coal", "charcoal", "plank", "log");
                if (fuelSlot != -1) {
                    mc.gameMode.handleContainerInput(containerId, fuelSlot, 0, ContainerInput.QUICK_MOVE, player);
                }
            }

            // Watchdog: If stuck in furnace for > 60 ticks without items smelting, close container
            if (furnaceGuiTicks > 60 && inStack.isEmpty() && resultStack.isEmpty()) {
                player.closeContainer();
                furnaceGuiTicks = 0;
                stepTimer = 5;
                return null;
            }

            setDebugState("Smelting in furnace: " + InventoryManager.countItems(player, outputKeyword) + " / " + targetOutputCount);
            stepTimer = 10; // Check every half second
            return null;
        }

        furnaceGuiTicks = 0;

        // 2. Furnace is not open: check nearby in world
        BlockPos nearbyFurnace = findNearbyFurnace(mc, player);
        if (nearbyFurnace != null) {
            cancelBaritonePathing();
            noSpotTicks = 0;
            placedFurnaceWaitTicks++;

            // If any non-air block is directly above the furnace obstructing it, break it!
            BlockPos above = nearbyFurnace.above();
            if (!mc.level.getBlockState(above).isAir() && !mc.level.getBlockState(above).canBeReplaced()) {
                setDebugState("Clearing block obstructing Furnace: " + above.toShortString());
                int pickSlot = MineBlockTask.getPickaxeHotbarSlot(player);
                if (pickSlot != -1) {
                    player.getInventory().setSelectedSlot(pickSlot);
                }
                mc.gameMode.startDestroyBlock(above, Direction.UP);
                stepTimer = 4;
                return null;
            }

            // Find an open face exposed to air/replaceable block
            Direction hitFace = Direction.UP;
            Vec3 hitVec = Vec3.atCenterOf(nearbyFurnace).add(0, 0.5, 0);
            if (!mc.level.getBlockState(above).isAir() && !mc.level.getBlockState(above).canBeReplaced()) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbor = nearbyFurnace.relative(dir);
                    if (mc.level.getBlockState(neighbor).isAir() || mc.level.getBlockState(neighbor).canBeReplaced()) {
                        hitFace = dir;
                        hitVec = Vec3.atCenterOf(nearbyFurnace).add(dir.getStepX() * 0.5, 0, dir.getStepZ() * 0.5);
                        break;
                    }
                }
            }

            setDebugState("Opening nearby Furnace at " + nearbyFurnace.toShortString());
            lookAt(player, hitVec);
            if (player.isShiftKeyDown()) {
                player.setShiftKeyDown(false);
            }
            BlockHitResult hit = new BlockHitResult(hitVec, hitFace, nearbyFurnace, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);

            // Watchdog: If placed/existing furnace isn't opening after multiple attempts (obstructed or blocked):
            if (placedFurnaceWaitTicks > 14) {
                setDebugState("Furnace obstructed/unresponsive. Mining furnace to relocate...");
                int pickSlot = MineBlockTask.getPickaxeHotbarSlot(player);
                if (pickSlot != -1) {
                    player.getInventory().setSelectedSlot(pickSlot);
                }
                mc.gameMode.startDestroyBlock(nearbyFurnace, Direction.UP);
                if (!mc.level.getBlockState(nearbyFurnace).is(Blocks.FURNACE)) {
                    placedFurnacePos = null;
                    placedFurnaceWaitTicks = 0;
                    noSpotTicks = 0;
                }
                stepTimer = 4;
                return null;
            }

            // If placed furnace isn't opening after a few attempts, step closer
            if (placedFurnaceWaitTicks > 6) {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(nearbyFurnace, 1));
                }
            }

            stepTimer = 4;
            return null;
        }

        placedFurnaceWaitTicks = 0;

        // 3. Need to place a furnace
        int furnaceItemCount = InventoryManager.countItems(player, "furnace");
        if (furnaceItemCount == 0) {
            BlockPos distantFurnace = findDistantFurnace(mc, player, 16);
            if (distantFurnace != null) {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(distantFurnace, 2));
                    setDebugState("Navigating to existing Furnace at " + distantFurnace.toShortString());
                }
                stepTimer = 4;
                return null;
            }
            setDebugState("Need to craft a Furnace first!");
            return new CraftInTableTask("furnace");
        }

        // 4. Place furnace or escape 1x1 hole / confined area
        BlockPos placePos = findPlacingSpot(mc, player);
        if (placePos == null) {
            noSpotTicks++;

            if (noSpotTicks > 6) {
                BlockPos playerPos = player.blockPosition();
                int solidWalls = 0;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    if (mc.level.getBlockState(playerPos.relative(dir)).isSolid()) {
                        solidWalls++;
                    }
                }

                if (solidWalls >= 3) {
                    setDebugState("In 1x1 hole (" + solidWalls + "/4 walls solid). Escaping to clear space...");
                    if (player.onGround() && mc.level.getBlockState(playerPos.above(2)).isAir()) {
                        player.jumpFromGround();
                    }
                    Direction facing = player.getDirection();
                    BlockPos wallInFront = playerPos.relative(facing);
                    if (mc.level.getBlockState(wallInFront).isSolid()) {
                        mc.gameMode.startDestroyBlock(wallInFront, Direction.UP);
                    }
                }

                BlockPos openGround = findNearestOpenGround(mc, player, 16);
                if (openGround != null) {
                    IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(openGround, 1));
                        setDebugState("Navigating out of confined space to open ground at " + openGround.toShortString());
                    }
                }
            } else {
                setDebugState("Looking for clear spot to place Furnace...");
            }
            return null;
        }

        cancelBaritonePathing();
        noSpotTicks = 0;

        int hotbarSlot = ensureHeldItem(mc, player, "furnace");
        if (hotbarSlot == -1) {
            setDebugState("Unable to swap Furnace to hotbar!");
            return null;
        }

        BlockPos support = placePos.below();
        Vec3 hitVec = Vec3.atCenterOf(support).add(0, 0.5, 0);
        lookAt(player, hitVec);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, support, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        placedFurnacePos = placePos;
        stepTimer = 3;
        setDebugState("Placed Furnace at " + placePos.toShortString() + ", interacting next tick...");

        return null;
    }

    private BlockPos findNearestOpenGround(Minecraft mc, LocalPlayer player, int radius) {
        if (mc.level == null) return null;
        BlockPos center = player.blockPosition();
        BlockPos bestPos = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dy = -2; dy <= 4; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    BlockState ground = mc.level.getBlockState(p);
                    if (!ground.isSolid() || ground.canBeReplaced()) continue;

                    BlockState above1 = mc.level.getBlockState(p.above());
                    BlockState above2 = mc.level.getBlockState(p.above(2));
                    if (!above1.isAir() && !above1.canBeReplaced()) continue;
                    if (!above2.isAir() && !above2.canBeReplaced()) continue;

                    int clearNeighbors = 0;
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockState nState = mc.level.getBlockState(p.above().relative(dir));
                        if (nState.isAir() || nState.canBeReplaced()) {
                            clearNeighbors++;
                        }
                    }
                    if (clearNeighbors < 2) continue;

                    double d = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(p.above()));
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        bestPos = p.above();
                    }
                }
            }
        }
        return bestPos;
    }

    private void cancelBaritonePathing() {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null && baritone.getPathingBehavior().isPathing()) {
                baritone.getPathingBehavior().forceCancel();
            }
        } catch (Throwable ignored) {
        }
    }

    private BlockPos findNearbyFurnace(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;
        Vec3 eyePos = player.getEyePosition();

        if (placedFurnacePos != null && mc.level.getBlockState(placedFurnacePos).is(Blocks.FURNACE)) {
            if (eyePos.distanceTo(Vec3.atCenterOf(placedFurnacePos)) <= 4.2) {
                return placedFurnacePos;
            }
        }

        BlockPos center = player.blockPosition();
        BlockPos bestFurnace = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int x = -3; x <= 3; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -3; z <= 3; z++) {
                    BlockPos p = center.offset(x, y, z);
                    if (mc.level.getBlockState(p).is(Blocks.FURNACE)) {
                        double d = eyePos.distanceToSqr(Vec3.atCenterOf(p));
                        if (d <= 18.0 && d < bestDistSq) {
                            bestDistSq = d;
                            bestFurnace = p;
                        }
                    }
                }
            }
        }
        return bestFurnace;
    }

    private BlockPos findDistantFurnace(Minecraft mc, LocalPlayer player, int radius) {
        if (mc.level == null) return null;
        Vec3 eyePos = player.getEyePosition();
        BlockPos center = player.blockPosition();
        BlockPos bestFurnace = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos p = center.offset(x, y, z);
                    if (mc.level.getBlockState(p).is(Blocks.FURNACE)) {
                        double d = eyePos.distanceToSqr(Vec3.atCenterOf(p));
                        if (d < bestDistSq) {
                            bestDistSq = d;
                            bestFurnace = p;
                        }
                    }
                }
            }
        }
        return bestFurnace;
    }

    private BlockPos findPlacingSpot(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;
        BlockPos playerPos = player.blockPosition();
        Vec3 eyePos = player.getEyePosition();
        AABB playerBox = player.getBoundingBox();

        BlockPos bestSpot = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dy = 0; dy >= -1; dy--) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0 && dy == 0) continue;

                    BlockPos target = playerPos.offset(dx, dy, dz);
                    BlockPos support = target.below();

                    BlockState targetState = mc.level.getBlockState(target);
                    if (!targetState.isAir() && !targetState.canBeReplaced()) {
                        continue;
                    }

                    // Ensure space above target is clear so furnace is never obstructed
                    BlockState aboveState = mc.level.getBlockState(target.above());
                    if (!aboveState.isAir() && !aboveState.canBeReplaced()) {
                        continue;
                    }

                    AABB targetBox = new AABB(target);
                    if (playerBox.intersects(targetBox)) {
                        continue;
                    }

                    BlockState supportState = mc.level.getBlockState(support);
                    if (!supportState.isSolid() || supportState.canBeReplaced()) {
                        continue;
                    }

                    Vec3 supportTop = Vec3.atCenterOf(support).add(0, 0.5, 0);
                    double distSq = eyePos.distanceToSqr(supportTop);
                    if (distSq > 16.0) {
                        continue;
                    }

                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        bestSpot = target;
                    }
                }
            }
        }
        return bestSpot;
    }

    private int ensureHeldItem(Minecraft mc, LocalPlayer player, String keyword) {
        keyword = keyword.toLowerCase();

        // 1. Already selected in hotbar?
        ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty() && getItemName(mainHand).contains(keyword)) {
            return player.getInventory().getSelectedSlot();
        }

        // 2. In any hotbar slot (0..8)?
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && getItemName(stack).contains(keyword)) {
                player.getInventory().setSelectedSlot(i);
                return i;
            }
        }

        // 3. In main inventory (slots 9..35)? Swap to hotbar!
        int targetHotbar = 3;
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).isEmpty()) {
                targetHotbar = i;
                break;
            }
        }

        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.INV_SLOT_END; i++) {
            ItemStack stack = player.inventoryMenu.getSlot(i).getItem();
            if (!stack.isEmpty() && getItemName(stack).contains(keyword)) {
                mc.gameMode.handleContainerInput(
                        InventoryMenu.CONTAINER_ID,
                        i,
                        targetHotbar,
                        ContainerInput.SWAP,
                        player
                );
                player.getInventory().setSelectedSlot(targetHotbar);
                return targetHotbar;
            }
        }

        return -1;
    }

    private void lookAt(LocalPlayer player, Vec3 target) {
        Vec3 diff = target.subtract(player.getEyePosition());
        double distXZ = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(diff.z, diff.x))) - 90.0F;
        float pitch = (float) (-Math.toDegrees(Math.atan2(diff.y, distXZ)));
        player.setYRot(yaw);
        player.setXRot(pitch);
    }

    public static String getItemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        try {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase();
        } catch (Throwable t) {
            return stack.getItem().toString().toLowerCase();
        }
    }

    private int findSlotInFurnace(FurnaceMenu menu, String... keywords) {
        // Slots 3..38 are player inventory in FurnaceMenu
        for (int i = 3; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                String name = getItemName(stack);
                for (String kw : keywords) {
                    if (name.contains(kw.toLowerCase())) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private String getOutputKeyword() {
        if (ingredientKeyword.contains("iron")) return "iron_ingot";
        if (ingredientKeyword.contains("gold")) return "gold_ingot";
        if (ingredientKeyword.contains("copper")) return "copper_ingot";
        if (ingredientKeyword.contains("beef")) return "cooked_beef";
        if (ingredientKeyword.contains("porkchop")) return "cooked_porkchop";
        if (ingredientKeyword.contains("chicken")) return "cooked_chicken";
        if (ingredientKeyword.contains("mutton")) return "cooked_mutton";
        if (ingredientKeyword.contains("cod")) return "cooked_cod";
        if (ingredientKeyword.contains("salmon")) return "cooked_salmon";
        if (ingredientKeyword.contains("potato")) return "baked_potato";
        if (ingredientKeyword.contains("sand")) return "glass";
        if (ingredientKeyword.contains("cobble")) return "stone";
        if (ingredientKeyword.contains("clay")) return "brick";
        return ingredientKeyword;
    }

    @Override
    protected void onStop(Task interruptTask) {
        cancelBaritonePathing();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu instanceof FurnaceMenu) {
            mc.player.closeContainer();
        }
        if (mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        if (mc.options != null && mc.options.keyAttack != null) {
            mc.options.keyAttack.setDown(false);
        }
    }

    @Override
    public boolean isFinished() {
        if (finished) return true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            String outputKeyword = getOutputKeyword();
            if (InventoryManager.countItems(mc.player, outputKeyword) >= targetOutputCount) {
                finished = true;
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof SmeltInFurnaceTask t) {
            return t.ingredientKeyword.equals(this.ingredientKeyword) && t.targetOutputCount == this.targetOutputCount;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Smelting " + targetOutputCount + "x " + ingredientKeyword;
    }
}
