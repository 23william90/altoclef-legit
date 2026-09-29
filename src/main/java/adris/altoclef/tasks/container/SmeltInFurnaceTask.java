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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
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
            finished = true;
            cleanUpPlacedFurnace(mc);
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
                    // No more ingredient and no more result, we're done!
                    player.closeContainer();
                    cleanUpPlacedFurnace(mc);
                    finished = true;
                    return null;
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

        // 2. Furnace is not open: check nearby
        BlockPos nearbyFurnace = findNearbyFurnace(mc, player);
        if (nearbyFurnace != null) {
            setDebugState("Opening nearby Furnace at " + nearbyFurnace.toShortString());
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(nearbyFurnace), Direction.UP, nearbyFurnace, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            stepTimer = 5;
            return null;
        }

        // 3. Need to place a furnace
        int furnaceItemCount = InventoryManager.countItems(player, "furnace");
        if (furnaceItemCount == 0) {
            setDebugState("Need to craft a Furnace first!");
            return new CraftInTableTask("furnace");
        }

        // Place furnace
        BlockPos placePos = findPlacingSpot(mc, player);
        if (placePos != null) {
            int hotbarFurnaceSlot = findHotbarItem(player, "furnace");
            if (hotbarFurnaceSlot != -1) {
                player.getInventory().setSelectedSlot(hotbarFurnaceSlot);
                BlockPos support = placePos.below();
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
                mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                placedFurnacePos = placePos;
                stepTimer = 3;
            }
        }

        return null;
    }

    private void cleanUpPlacedFurnace(Minecraft mc) {
        if (placedFurnacePos != null && mc.gameMode != null && mc.level != null) {
            if (mc.level.getBlockState(placedFurnacePos).is(Blocks.FURNACE)) {
                mc.gameMode.destroyBlock(placedFurnacePos);
            }
            placedFurnacePos = null;
        }
    }

    private BlockPos findNearbyFurnace(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;
        BlockPos center = player.blockPosition();
        for (int x = -3; x <= 3; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -3; z <= 3; z++) {
                    BlockPos p = center.offset(x, y, z);
                    if (mc.level.getBlockState(p).is(Blocks.FURNACE)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    private BlockPos findPlacingSpot(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;
        BlockPos center = player.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos target = center.relative(dir);
            BlockPos support = target.below();
            if (mc.level.getBlockState(target).isAir() && mc.level.getBlockState(support).isSolid()) {
                return target;
            }
        }
        return null;
    }

    private int findHotbarItem(LocalPlayer player, String keyword) {
        keyword = keyword.toLowerCase();
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem().toString().toLowerCase().contains(keyword)) {
                return i;
            }
        }
        return -1;
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
        Minecraft mc = Minecraft.getInstance();
        cleanUpPlacedFurnace(mc);
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
