package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class CraftInTableTask extends Task {

    private final String itemTarget;
    private final int targetCount;
    private boolean finished = false;
    private int stepTimer = 0;
    private BlockPos placedTablePos = null;

    private static final Map<String, RecipeDef> RECIPES = new HashMap<>();

    public static class RecipeDef {
        public final Map<Integer, String> gridSlots; // Slot 1..9 -> item keyword
        public final Map<String, Integer> requiredCounts; // keyword -> min count

        public RecipeDef(Map<Integer, String> gridSlots, Map<String, Integer> requiredCounts) {
            this.gridSlots = gridSlots;
            this.requiredCounts = requiredCounts;
        }
    }

    static {
        // Wooden Pickaxe: 3 planks (1,2,3), 2 sticks (5,8)
        Map<Integer, String> wPick = Map.of(1, "plank", 2, "plank", 3, "plank", 5, "stick", 8, "stick");
        RECIPES.put("wooden_pickaxe", new RecipeDef(wPick, Map.of("plank", 3, "stick", 2)));

        // Stone Pickaxe: 3 cobble (1,2,3), 2 sticks (5,8)
        Map<Integer, String> sPick = Map.of(1, "cobble", 2, "cobble", 3, "cobble", 5, "stick", 8, "stick");
        RECIPES.put("stone_pickaxe", new RecipeDef(sPick, Map.of("cobble", 3, "stick", 2)));

        // Stone Sword: 2 cobble (2,5), 1 stick (8)
        Map<Integer, String> sSword = Map.of(2, "cobble", 5, "cobble", 8, "stick");
        RECIPES.put("stone_sword", new RecipeDef(sSword, Map.of("cobble", 2, "stick", 1)));

        // Furnace: 8 cobble (1,2,3,4,6,7,8,9)
        Map<Integer, String> furnace = Map.of(1, "cobble", 2, "cobble", 3, "cobble", 4, "cobble", 6, "cobble", 7, "cobble", 8, "cobble", 9, "cobble");
        RECIPES.put("furnace", new RecipeDef(furnace, Map.of("cobble", 8)));

        // Iron Pickaxe: 3 iron ingots (1,2,3), 2 sticks (5,8)
        Map<Integer, String> iPick = Map.of(1, "iron_ingot", 2, "iron_ingot", 3, "iron_ingot", 5, "stick", 8, "stick");
        RECIPES.put("iron_pickaxe", new RecipeDef(iPick, Map.of("iron_ingot", 3, "stick", 2)));

        // Shield: 6 planks (1,3,4,5,6,8), 1 iron ingot (2)
        Map<Integer, String> shield = Map.of(1, "plank", 2, "iron_ingot", 3, "plank", 4, "plank", 5, "plank", 6, "plank", 8, "plank");
        RECIPES.put("shield", new RecipeDef(shield, Map.of("plank", 6, "iron_ingot", 1)));

        // Bucket: 3 iron ingots (4,6,8)
        Map<Integer, String> bucket = Map.of(4, "iron_ingot", 6, "iron_ingot", 8, "iron_ingot");
        RECIPES.put("bucket", new RecipeDef(bucket, Map.of("iron_ingot", 3)));

        // Iron Chestplate: 8 iron ingots (1,3,4,5,6,7,8,9)
        Map<Integer, String> iChest = Map.of(1, "iron_ingot", 3, "iron_ingot", 4, "iron_ingot", 5, "iron_ingot", 6, "iron_ingot", 7, "iron_ingot", 8, "iron_ingot", 9, "iron_ingot");
        RECIPES.put("iron_chestplate", new RecipeDef(iChest, Map.of("iron_ingot", 8)));

        // Diamond Pickaxe: 3 diamonds (1,2,3), 2 sticks (5,8)
        Map<Integer, String> dPick = Map.of(1, "diamond", 2, "diamond", 3, "diamond", 5, "stick", 8, "stick");
        RECIPES.put("diamond_pickaxe", new RecipeDef(dPick, Map.of("diamond", 3, "stick", 2)));

        // Golden Helmet: 5 gold ingots (1,2,3,4,6)
        Map<Integer, String> gHelm = Map.of(1, "gold_ingot", 2, "gold_ingot", 3, "gold_ingot", 4, "gold_ingot", 6, "gold_ingot");
        RECIPES.put("golden_helmet", new RecipeDef(gHelm, Map.of("gold_ingot", 5)));

        // Bed: 3 wool (4,5,6), 3 planks (7,8,9)
        Map<Integer, String> bed = Map.of(4, "wool", 5, "wool", 6, "wool", 7, "plank", 8, "plank", 9, "plank");
        RECIPES.put("bed", new RecipeDef(bed, Map.of("wool", 3, "plank", 3)));
    }

    public CraftInTableTask(String itemTarget, int targetCount) {
        this.itemTarget = itemTarget.toLowerCase();
        this.targetCount = targetCount;
    }

    public CraftInTableTask(String itemTarget) {
        this(itemTarget, 1);
    }

    @Override
    protected void onStart() {
        finished = false;
        stepTimer = 0;
        placedTablePos = null;
        setDebugState("Crafting " + itemTarget + " in Crafting Table...");
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) return null;

        if (stepTimer-- > 0) return null;

        // 1. Success check: target items already in player inventory?
        if (InventoryManager.countItems(player, itemTarget) >= targetCount) {
            if (player.containerMenu instanceof CraftingMenu) {
                player.closeContainer();
            }
            finished = true;
            return null;
        }

        RecipeDef recipe = RECIPES.get(itemTarget);
        if (recipe == null) {
            setDebugState("Unknown recipe target: " + itemTarget);
            finished = true;
            return null;
        }

        // 2. Verify all ingredients exist in player inventory
        for (Map.Entry<String, Integer> req : recipe.requiredCounts.entrySet()) {
            if (InventoryManager.countItems(player, req.getKey()) < req.getValue()) {
                setDebugState("Missing ingredient: need " + req.getValue() + "x " + req.getKey());
                return null;
            }
        }

        // 3. Crafting table menu is currently open!
        if (player.containerMenu instanceof CraftingMenu menu) {
            executeRecipeCraft(mc, player, menu, recipe);
            return null;
        }

        // 4. Find nearby reachable Crafting Table in world
        BlockPos existingTable = findNearbyTable(mc, player);
        if (existingTable != null) {
            setDebugState("Opening Crafting Table at " + existingTable.toShortString());
            Vec3 hitVec = Vec3.atCenterOf(existingTable).add(0, 0.5, 0);
            lookAt(player, hitVec);
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, existingTable, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            stepTimer = 4;
            return null;
        }

        // 5. Check if we need to craft a Crafting Table first
        int tableItemCount = InventoryManager.countItems(player, "crafting_table");
        if (tableItemCount == 0) {
            int planks = InventoryManager.countItems(player, "plank");
            if (planks >= 4) {
                setDebugState("Auto 2x2 crafting Crafting Table...");
                int plankSlot = findPlankSlotInInventory(player);
                if (plankSlot != -1) {
                    AltoClef.getInstance().getInventoryManager().craft2x2CraftingTable(mc, player, plankSlot);
                    stepTimer = 4;
                    return null;
                }
            } else {
                int logs = InventoryManager.countItems(player, "log");
                if (logs > 0) {
                    setDebugState("Auto 2x2 crafting planks for Crafting Table...");
                    int logSlot = findLogSlotInInventory(player);
                    if (logSlot != -1) {
                        AltoClef.getInstance().getInventoryManager().craft2x2Planks(mc, player, logSlot);
                        stepTimer = 4;
                        return null;
                    }
                }
                setDebugState("Need 4 planks to craft Crafting Table!");
                return null;
            }
        }

        // 6. Place Crafting Table
        BlockPos placePos = findPlacingSpot(mc, player);
        if (placePos == null) {
            setDebugState("Looking for clear spot to place Crafting Table...");
            return null;
        }

        int hotbarSlot = ensureHeldItem(mc, player, "crafting_table");
        if (hotbarSlot == -1) {
            setDebugState("Unable to swap Crafting Table to hotbar!");
            return null;
        }

        BlockPos support = placePos.below();
        Vec3 hitVec = Vec3.atCenterOf(support).add(0, 0.5, 0);
        lookAt(player, hitVec);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, support, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        placedTablePos = placePos;
        stepTimer = 3;
        setDebugState("Placed Crafting Table at " + placePos.toShortString() + ", interacting next tick...");

        return null;
    }

    private void executeRecipeCraft(Minecraft mc, LocalPlayer player, CraftingMenu menu, RecipeDef recipe) {
        int containerId = menu.containerId;

        // 1. If result slot already has crafted item, take it!
        ItemStack resultStack = menu.getSlot(0).getItem();
        if (!resultStack.isEmpty() && resultStack.getItem().toString().toLowerCase().contains(itemTarget)) {
            mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);

            // Clear any leftovers from 3x3 grid back into inventory
            for (int s = 1; s <= 9; s++) {
                if (!menu.getSlot(s).getItem().isEmpty()) {
                    mc.gameMode.handleContainerInput(containerId, s, 0, ContainerInput.QUICK_MOVE, player);
                }
            }

            // Return carried item if cursor is not empty
            if (!menu.getCarried().isEmpty()) {
                int emptySlot = findEmptyPlayerSlotInContainer(menu);
                if (emptySlot != -1) {
                    mc.gameMode.handleContainerInput(containerId, emptySlot, 0, ContainerInput.PICKUP, player);
                }
            }

            player.closeContainer();
            setDebugState("Successfully crafted " + itemTarget + "!");
            finished = true;
            return;
        }

        // 2. Group required slots by ingredient keyword
        Map<String, List<Integer>> keywordToSlots = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : recipe.gridSlots.entrySet()) {
            keywordToSlots.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        for (Map.Entry<String, List<Integer>> group : keywordToSlots.entrySet()) {
            String keyword = group.getKey();
            List<Integer> slotsToFill = group.getValue();

            boolean allFilled = true;
            for (int slot : slotsToFill) {
                ItemStack inSlot = menu.getSlot(slot).getItem();
                if (inSlot.isEmpty() || !inSlot.getItem().toString().toLowerCase().contains(keyword)) {
                    allFilled = false;
                    break;
                }
            }
            if (allFilled) continue;

            int invSlot = findBestSlotInContainer(menu, keyword);
            if (invSlot == -1) {
                setDebugState("Missing ingredient stack for " + keyword);
                return;
            }

            // Pick up ingredient stack
            mc.gameMode.handleContainerInput(containerId, invSlot, 0, ContainerInput.PICKUP, player);

            // Right click each slot to place 1 item
            for (int gridSlot : slotsToFill) {
                ItemStack current = menu.getSlot(gridSlot).getItem();
                if (current.isEmpty() || !current.getItem().toString().toLowerCase().contains(keyword)) {
                    mc.gameMode.handleContainerInput(containerId, gridSlot, 1, ContainerInput.PICKUP, player);
                }
            }

            // Return remaining items to original inventory slot
            mc.gameMode.handleContainerInput(containerId, invSlot, 0, ContainerInput.PICKUP, player);
        }

        // 3. Immediately check slot 0 after placing
        ItemStack immediateResult = menu.getSlot(0).getItem();
        if (!immediateResult.isEmpty() && immediateResult.getItem().toString().toLowerCase().contains(itemTarget)) {
            mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
            for (int s = 1; s <= 9; s++) {
                if (!menu.getSlot(s).getItem().isEmpty()) {
                    mc.gameMode.handleContainerInput(containerId, s, 0, ContainerInput.QUICK_MOVE, player);
                }
            }
            player.closeContainer();
            setDebugState("Successfully crafted " + itemTarget + "!");
            finished = true;
        } else {
            stepTimer = 2; // Wait 2 ticks for recipe update
        }
    }

    private BlockPos findNearbyTable(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;
        Vec3 eyePos = player.getEyePosition();

        if (placedTablePos != null && mc.level.getBlockState(placedTablePos).is(Blocks.CRAFTING_TABLE)) {
            if (eyePos.distanceTo(Vec3.atCenterOf(placedTablePos)) <= 4.2) {
                return placedTablePos;
            }
        }

        BlockPos center = player.blockPosition();
        BlockPos bestTable = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int x = -3; x <= 3; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -3; z <= 3; z++) {
                    BlockPos p = center.offset(x, y, z);
                    if (mc.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
                        double d = eyePos.distanceToSqr(Vec3.atCenterOf(p));
                        if (d <= 18.0 && d < bestDistSq) {
                            bestDistSq = d;
                            bestTable = p;
                        }
                    }
                }
            }
        }
        return bestTable;
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

    private int findBestSlotInContainer(CraftingMenu menu, String keyword) {
        keyword = keyword.toLowerCase();
        int bestSlot = -1;
        int maxCount = 0;
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && stack.getItem().toString().toLowerCase().contains(keyword)) {
                if (stack.getCount() > maxCount) {
                    maxCount = stack.getCount();
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private int findEmptyPlayerSlotInContainer(CraftingMenu menu) {
        for (int i = 10; i < menu.slots.size(); i++) {
            if (menu.getSlot(i).getItem().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private int findPlankSlotInInventory(LocalPlayer player) {
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = player.inventoryMenu.getSlot(i).getItem();
            if (!stack.isEmpty() && stack.getItem().toString().toLowerCase().contains("plank") && stack.getCount() >= 4) {
                return i;
            }
        }
        return -1;
    }

    private int findLogSlotInInventory(LocalPlayer player) {
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = player.inventoryMenu.getSlot(i).getItem();
            if (!stack.isEmpty() && stack.getItem().toString().toLowerCase().contains("log")) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onStop(Task interruptTask) {
        // Keep placed table in world so nearby subtasks can reuse it
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CraftInTableTask t) {
            return t.itemTarget.equals(this.itemTarget) && t.targetCount == this.targetCount;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Crafting " + targetCount + "x " + itemTarget + " in Table";
    }
}
