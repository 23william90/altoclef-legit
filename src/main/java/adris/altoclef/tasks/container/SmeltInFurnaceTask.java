package adris.altoclef.tasks.container;

import adris.altoclef.control.InventoryManager;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

    public SmeltInFurnaceTask(String ingredientKeyword, int targetOutputCount) {
        this.ingredientKeyword = ingredientKeyword.toLowerCase();
        this.targetOutputCount = targetOutputCount;
    }

    @Override
    protected void onStart() {
        finished = false;
        stepTimer = 0;
        placedFurnacePos = null;
        setDebugState("Smelting " + targetOutputCount + "x " + ingredientKeyword + " in Furnace...");
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) return null;

        if (stepTimer-- > 0) return null;

        // Check if output is already satisfied (e.g. iron ingots)
        String outputKeyword = ingredientKeyword.contains("iron") ? "iron_ingot" : "gold_ingot";
        if (InventoryManager.countItems(player, outputKeyword) >= targetOutputCount) {
            if (player.containerMenu instanceof FurnaceMenu) {
                player.closeContainer();
            }
            finished = true;
            return null;
        }

        // 1. Furnace menu is open!
        if (player.containerMenu instanceof FurnaceMenu menu) {
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

            setDebugState("Smelting in furnace: " + InventoryManager.countItems(player, outputKeyword) + " / " + targetOutputCount);
            stepTimer = 10; // Check every half second
            return null;
        }

        // 2. Furnace is not open: check nearby in world
        BlockPos nearbyFurnace = findNearbyFurnace(mc, player);
        if (nearbyFurnace != null) {
            setDebugState("Opening nearby Furnace at " + nearbyFurnace.toShortString());
            Vec3 hitVec = Vec3.atCenterOf(nearbyFurnace).add(0, 0.5, 0);
            lookAt(player, hitVec);
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, nearbyFurnace, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            stepTimer = 4;
            return null;
        }

        // 3. Need to place a furnace
        int furnaceItemCount = InventoryManager.countItems(player, "furnace");
        if (furnaceItemCount == 0) {
            setDebugState("Need to craft a Furnace first!");
            return new CraftInTableTask("furnace");
        }

        // 4. Place furnace
        BlockPos placePos = findPlacingSpot(mc, player);
        if (placePos == null) {
            setDebugState("Looking for clear spot to place Furnace...");
            return null;
        }

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
        if (!mainHand.isEmpty() && mainHand.getItem().toString().toLowerCase().contains(keyword)) {
            return player.getInventory().getSelectedSlot();
        }

        // 2. In any hotbar slot (0..8)?
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem().toString().toLowerCase().contains(keyword)) {
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
            if (!stack.isEmpty() && stack.getItem().toString().toLowerCase().contains(keyword)) {
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

    private int findSlotInFurnace(FurnaceMenu menu, String... keywords) {
        // Slots 3..38 are player inventory in FurnaceMenu
        for (int i = 3; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                String name = stack.getItem().toString().toLowerCase();
                for (String kw : keywords) {
                    if (name.contains(kw.toLowerCase())) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    @Override
    protected void onStop(Task interruptTask) {
        // Keep placed furnace in world so smelting can continue or be collected later
    }

    @Override
    public boolean isFinished() {
        return finished;
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
