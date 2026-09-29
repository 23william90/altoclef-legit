package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InventoryManager;
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

    // Watchdog and fallback counters
    private int noSpotTicks = 0;
    private int placedTableWaitTicks = 0;
    private int craftingGuiTicks = 0;

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
        noSpotTicks = 0;
        placedTableWaitTicks = 0;
        craftingGuiTicks = 0;
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
            cancelBaritonePathing();
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
            cancelBaritonePathing();
            noSpotTicks = 0;
            placedTableWaitTicks = 0;
            executeRecipeCraft(mc, player, menu, recipe);
            return null;
        }

        craftingGuiTicks = 0;

        // 4. Find nearby reachable Crafting Table in world
        BlockPos existingTable = findNearbyTable(mc, player);
        if (existingTable != null) {
            cancelBaritonePathing();
            noSpotTicks = 0;
            placedTableWaitTicks++;

            setDebugState("Opening Crafting Table at " + existingTable.toShortString());
            Vec3 hitVec = Vec3.atCenterOf(existingTable).add(0, 0.5, 0);
            lookAt(player, hitVec);
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, existingTable, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);

            // If placed table isn't opening after multiple attempts, step closer
            if (placedTableWaitTicks > 12) {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(existingTable, 1));
                }
            }

            stepTimer = 3;
            return null;
        }

        placedTableWaitTicks = 0;

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

        // 6. Find placing spot or execute backup escape plan if trapped in a 1x1 hole / confined area
        BlockPos placePos = findPlacingSpot(mc, player);
        if (placePos == null) {
            noSpotTicks++;

            // Backup Plan: Trapped in a 1x1 hole or confined tunnel
            if (noSpotTicks > 6) {
                BlockPos playerPos = player.blockPosition();

                // Count solid horizontal walls around feet
                int solidWalls = 0;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    if (mc.level.getBlockState(playerPos.relative(dir)).isSolid()) {
                        solidWalls++;
                    }
                }

                // If in a 1x1 hole (3 or 4 solid walls), try jump / mine out
                if (solidWalls >= 3) {
                    setDebugState("In 1x1 hole (" + solidWalls + "/4 walls solid). Escaping to clear space...");

                    // If ceiling has air, jump out
                    if (player.onGround() && mc.level.getBlockState(playerPos.above(2)).isAir()) {
                        player.jumpFromGround();
                    }

                    // Mine wall in front to clear space for placing
                    Direction facing = player.getDirection();
                    BlockPos wallInFront = playerPos.relative(facing);
                    if (mc.level.getBlockState(wallInFront).isSolid()) {
                        mc.gameMode.startDestroyBlock(wallInFront, Direction.UP);
                    }
                }

                // Command Baritone to find and navigate to the nearest open, flat ground
                BlockPos openGround = findNearestOpenGround(mc, player, 16);
                if (openGround != null) {
                    IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(openGround, 1));
                        setDebugState("Navigating out of confined space to open ground at " + openGround.toShortString());
                    }
                }
            } else {
                setDebugState("Looking for clear spot to place Crafting Table...");
            }
            return null;
        }

        // We found a valid placing spot! Stop any navigation pathing
        cancelBaritonePathing();
        noSpotTicks = 0;

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
        craftingGuiTicks++;

        // 1. Success check: Does player inventory ALREADY have the crafted item?
        if (InventoryManager.countItems(player, itemTarget) >= targetCount) {
            clearCraftingGrid(mc, player, menu, containerId);
            player.closeContainer();
            setDebugState("Successfully crafted " + itemTarget + "! Closed Crafting Table.");
            finished = true;
            return;
        }

        // 2. Check slot 0 (result slot): if populated, take it!
        ItemStack resultStack = menu.getSlot(0).getItem();
        if (!resultStack.isEmpty()) {
            // Quick move crafted item into inventory
            mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);

            // Pickup fallback if still in slot
            if (!menu.getSlot(0).getItem().isEmpty()) {
                mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.PICKUP, player);
            }

            if (!menu.getCarried().isEmpty()) {
                int emptySlot = findEmptyPlayerSlotInContainer(menu);
                if (emptySlot != -1) {
                    mc.gameMode.handleContainerInput(containerId, emptySlot, 0, ContainerInput.PICKUP, player);
                }
            }

            clearCraftingGrid(mc, player, menu, containerId);
            player.closeContainer();
            setDebugState("Took crafted " + itemTarget + " from table!");
            finished = true;
            return;
        }

        // 3. Fill 3x3 crafting grid according to recipe
        Map<String, List<Integer>> keywordToSlots = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : recipe.gridSlots.entrySet()) {
            keywordToSlots.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        boolean allSlotsPopulated = true;
        for (Map.Entry<String, List<Integer>> group : keywordToSlots.entrySet()) {
            String keyword = group.getKey();
            List<Integer> slotsToFill = group.getValue();

            boolean groupFilled = true;
            for (int slot : slotsToFill) {
                ItemStack inSlot = menu.getSlot(slot).getItem();
                if (inSlot.isEmpty() || !getItemName(inSlot).contains(keyword)) {
                    groupFilled = false;
                    allSlotsPopulated = false;
                    break;
                }
            }
            if (groupFilled) continue;

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
                if (current.isEmpty() || !getItemName(current).contains(keyword)) {
                    mc.gameMode.handleContainerInput(containerId, gridSlot, 1, ContainerInput.PICKUP, player);
                }
            }

            // Return remaining items to original inventory slot
            mc.gameMode.handleContainerInput(containerId, invSlot, 0, ContainerInput.PICKUP, player);
        }

        // 4. If all grid slots are populated, click slot 0 unconditionally!
        if (allSlotsPopulated) {
            mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
            mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.PICKUP, player);

            if (!menu.getCarried().isEmpty()) {
                int emptySlot = findEmptyPlayerSlotInContainer(menu);
                if (emptySlot != -1) {
                    mc.gameMode.handleContainerInput(containerId, emptySlot, 0, ContainerInput.PICKUP, player);
                }
            }

            if (InventoryManager.countItems(player, itemTarget) >= targetCount) {
                clearCraftingGrid(mc, player, menu, containerId);
                player.closeContainer();
                setDebugState("Crafted " + itemTarget + "! Closed Crafting Table.");
                finished = true;
                return;
            }
        }

        // 5. Watchdog Plan A: Stuck for > 20 ticks (~1s) with items in table
        if (craftingGuiTicks > 20) {
            setDebugState("Watchdog: Extracting slot 0 craft output...");
            mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
            mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.PICKUP, player);

            if (!menu.getCarried().isEmpty()) {
                int emptySlot = findEmptyPlayerSlotInContainer(menu);
                if (emptySlot != -1) {
                    mc.gameMode.handleContainerInput(containerId, emptySlot, 0, ContainerInput.PICKUP, player);
                }
            }

            if (InventoryManager.countItems(player, itemTarget) >= targetCount) {
                clearCraftingGrid(mc, player, menu, containerId);
                player.closeContainer();
                finished = true;
                return;
            }
        }

        // 6. Watchdog Plan B: Stuck for > 45 ticks (~2.25s) -> Reset and clear container
        if (craftingGuiTicks > 45) {
            setDebugState("Watchdog: Crafting GUI unresponsive, returning items and resetting container...");
            clearCraftingGrid(mc, player, menu, containerId);
            if (!menu.getCarried().isEmpty()) {
                int emptySlot = findEmptyPlayerSlotInContainer(menu);
                if (emptySlot != -1) {
                    mc.gameMode.handleContainerInput(containerId, emptySlot, 0, ContainerInput.PICKUP, player);
                }
            }
            player.closeContainer();
            craftingGuiTicks = 0;
            stepTimer = 4;
            return;
        }

        stepTimer = 2; // Wait 2 ticks for recipe synchronization
    }

    private void clearCraftingGrid(Minecraft mc, LocalPlayer player, CraftingMenu menu, int containerId) {
        for (int s = 1; s <= 9; s++) {
            if (!menu.getSlot(s).getItem().isEmpty()) {
                mc.gameMode.handleContainerInput(containerId, s, 0, ContainerInput.QUICK_MOVE, player);
            }
        }
        if (!menu.getCarried().isEmpty()) {
            int emptySlot = findEmptyPlayerSlotInContainer(menu);
            if (emptySlot != -1) {
                mc.gameMode.handleContainerInput(containerId, emptySlot, 0, ContainerInput.PICKUP, player);
            }
        }
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

                    // Ensure at least 2 horizontal neighbors are clear (open area!)
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

    private int findBestSlotInContainer(CraftingMenu menu, String keyword) {
        keyword = keyword.toLowerCase();
        int bestSlot = -1;
        int maxCount = 0;
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && getItemName(stack).contains(keyword)) {
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
            if (!stack.isEmpty() && getItemName(stack).contains("plank") && stack.getCount() >= 4) {
                return i;
            }
        }
        return -1;
    }

    private int findLogSlotInInventory(LocalPlayer player) {
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = player.inventoryMenu.getSlot(i).getItem();
            if (!stack.isEmpty() && getItemName(stack).contains("log")) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onStop(Task interruptTask) {
        cancelBaritonePathing();
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
