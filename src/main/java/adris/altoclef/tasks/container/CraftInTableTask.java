package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
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
    private int ticksSinceLastCraftAction = 0;
    private int extractAttempts = 0;
    private int lastIngredientSourceSlot = -1;
    private String currentIngredientKeyword = null;
    private int craftingMenuOpenCloseLoops = 0;
    private boolean wasMenuOpen = false;
    private int missingIngredientTicks = 0;

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
        // Wooden Pickaxe: 3 planks (1,2,3) FIRST, then 2 sticks (5,8)
        Map<Integer, String> wPick = new LinkedHashMap<>();
        wPick.put(1, "plank");
        wPick.put(2, "plank");
        wPick.put(3, "plank");
        wPick.put(5, "stick");
        wPick.put(8, "stick");
        RECIPES.put("wooden_pickaxe", new RecipeDef(wPick, Map.of("plank", 3, "stick", 2)));

        // Stone Pickaxe: 3 cobble (1,2,3) FIRST, then 2 sticks (5,8)
        Map<Integer, String> sPick = new LinkedHashMap<>();
        sPick.put(1, "cobble");
        sPick.put(2, "cobble");
        sPick.put(3, "cobble");
        sPick.put(5, "stick");
        sPick.put(8, "stick");
        RECIPES.put("stone_pickaxe", new RecipeDef(sPick, Map.of("cobble", 3, "stick", 2)));

        // Stone Sword: 2 cobble (2,5), 1 stick (8)
        Map<Integer, String> sSword = new LinkedHashMap<>();
        sSword.put(2, "cobble");
        sSword.put(5, "cobble");
        sSword.put(8, "stick");
        RECIPES.put("stone_sword", new RecipeDef(sSword, Map.of("cobble", 2, "stick", 1)));

        // Furnace: 8 cobble (1,2,3,4,6,7,8,9)
        Map<Integer, String> furnace = new LinkedHashMap<>();
        furnace.put(1, "cobble");
        furnace.put(2, "cobble");
        furnace.put(3, "cobble");
        furnace.put(4, "cobble");
        furnace.put(6, "cobble");
        furnace.put(7, "cobble");
        furnace.put(8, "cobble");
        furnace.put(9, "cobble");
        RECIPES.put("furnace", new RecipeDef(furnace, Map.of("cobble", 8)));

        // Iron Pickaxe: 3 iron ingots (1,2,3) FIRST, then 2 sticks (5,8)
        Map<Integer, String> iPick = new LinkedHashMap<>();
        iPick.put(1, "iron_ingot");
        iPick.put(2, "iron_ingot");
        iPick.put(3, "iron_ingot");
        iPick.put(5, "stick");
        iPick.put(8, "stick");
        RECIPES.put("iron_pickaxe", new RecipeDef(iPick, Map.of("iron_ingot", 3, "stick", 2)));

        // Iron Sword: 2 iron ingots (2,5), 1 stick (8)
        Map<Integer, String> iSword = new LinkedHashMap<>();
        iSword.put(2, "iron_ingot");
        iSword.put(5, "iron_ingot");
        iSword.put(8, "stick");
        RECIPES.put("iron_sword", new RecipeDef(iSword, Map.of("iron_ingot", 2, "stick", 1)));

        // Shield: 6 planks (1,3,4,5,6,8), 1 iron ingot (2)
        Map<Integer, String> shield = new LinkedHashMap<>();
        shield.put(1, "plank");
        shield.put(3, "plank");
        shield.put(4, "plank");
        shield.put(5, "plank");
        shield.put(6, "plank");
        shield.put(8, "plank");
        shield.put(2, "iron_ingot");
        RECIPES.put("shield", new RecipeDef(shield, Map.of("plank", 6, "iron_ingot", 1)));

        // Bucket: 3 iron ingots (4,6,8)
        Map<Integer, String> bucket = new LinkedHashMap<>();
        bucket.put(4, "iron_ingot");
        bucket.put(6, "iron_ingot");
        bucket.put(8, "iron_ingot");
        RECIPES.put("bucket", new RecipeDef(bucket, Map.of("iron_ingot", 3)));

        // Iron Chestplate: 8 iron ingots (1,3,4,5,6,7,8,9)
        Map<Integer, String> iChest = new LinkedHashMap<>();
        iChest.put(1, "iron_ingot");
        iChest.put(3, "iron_ingot");
        iChest.put(4, "iron_ingot");
        iChest.put(5, "iron_ingot");
        iChest.put(6, "iron_ingot");
        iChest.put(7, "iron_ingot");
        iChest.put(8, "iron_ingot");
        iChest.put(9, "iron_ingot");
        RECIPES.put("iron_chestplate", new RecipeDef(iChest, Map.of("iron_ingot", 8)));

        // Diamond Pickaxe: 3 diamonds (1,2,3) FIRST, then 2 sticks (5,8)
        Map<Integer, String> dPick = new LinkedHashMap<>();
        dPick.put(1, "diamond");
        dPick.put(2, "diamond");
        dPick.put(3, "diamond");
        dPick.put(5, "stick");
        dPick.put(8, "stick");
        RECIPES.put("diamond_pickaxe", new RecipeDef(dPick, Map.of("diamond", 3, "stick", 2)));

        // Diamond Sword: 2 diamonds (2,5), 1 stick (8)
        Map<Integer, String> dSword = new LinkedHashMap<>();
        dSword.put(2, "diamond");
        dSword.put(5, "diamond");
        dSword.put(8, "stick");
        RECIPES.put("diamond_sword", new RecipeDef(dSword, Map.of("diamond", 2, "stick", 1)));

        // Golden Helmet: 5 gold ingots (1,2,3,4,6)
        Map<Integer, String> gHelm = new LinkedHashMap<>();
        gHelm.put(1, "gold_ingot");
        gHelm.put(2, "gold_ingot");
        gHelm.put(3, "gold_ingot");
        gHelm.put(4, "gold_ingot");
        gHelm.put(6, "gold_ingot");
        RECIPES.put("golden_helmet", new RecipeDef(gHelm, Map.of("gold_ingot", 5)));

        // Bed: 3 wool (4,5,6), 3 planks (7,8,9)
        Map<Integer, String> bed = new LinkedHashMap<>();
        bed.put(4, "wool");
        bed.put(5, "wool");
        bed.put(6, "wool");
        bed.put(7, "plank");
        bed.put(8, "plank");
        bed.put(9, "plank");
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
        ticksSinceLastCraftAction = 0;
        extractAttempts = 0;
        lastIngredientSourceSlot = -1;
        currentIngredientKeyword = null;
        craftingMenuOpenCloseLoops = 0;
        wasMenuOpen = false;
        missingIngredientTicks = 0;
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

        // 2. Crafting table menu is currently open!
        if (player.containerMenu instanceof CraftingMenu menu) {
            cancelBaritonePathing();
            noSpotTicks = 0;
            placedTableWaitTicks = 0;
            if (!wasMenuOpen) {
                wasMenuOpen = true;
                craftingMenuOpenCloseLoops++;
                craftingGuiTicks = 0;
                ticksSinceLastCraftAction = 0;
                extractAttempts = 0;
                lastIngredientSourceSlot = -1;
                currentIngredientKeyword = null;
            }
            executeRecipeCraft(mc, player, menu, recipe);
            return null;
        }

        wasMenuOpen = false;
        craftingGuiTicks = 0;
        ticksSinceLastCraftAction = 0;
        lastIngredientSourceSlot = -1;
        currentIngredientKeyword = null;

        // 3. Safety Watchdog: If stuck in repeated open-close crafting loops (>= 6 times):
        // Mine the Crafting Table to break the loop, pick it up, and relocate!
        if (craftingMenuOpenCloseLoops >= 6) {
            BlockPos nearby = findNearbyTable(mc, player);
            if (nearby != null) {
                setDebugState("Safety Watchdog: Stuck in crafting loop (" + craftingMenuOpenCloseLoops + "x)! Mining table to reset...");
                int pickSlot = MineBlockTask.getPickaxeHotbarSlot(player);
                if (pickSlot != -1) {
                    player.getInventory().setSelectedSlot(pickSlot);
                }
                mc.gameMode.startDestroyBlock(nearby, Direction.UP);
                if (!mc.level.getBlockState(nearby).is(Blocks.CRAFTING_TABLE)) {
                    craftingMenuOpenCloseLoops = 0;
                    placedTablePos = null;
                    placedTableWaitTicks = 0;
                    noSpotTicks = 0;
                }
                stepTimer = 4;
                return null;
            } else {
                craftingMenuOpenCloseLoops = 0;
            }
        }

        // 4. Verify all ingredients exist in player inventory (when table is NOT open)
        for (Map.Entry<String, Integer> req : recipe.requiredCounts.entrySet()) {
            if (InventoryManager.countItems(player, req.getKey()) < req.getValue()) {
                // If missing ingredient is sticks, auto-craft them if we have wood/planks
                if (req.getKey().equals("stick")) {
                    int planks = InventoryManager.countItems(player, "plank");
                    if (planks >= 2) {
                        int plankSlot = findPlankSlotInInventory(player, 2);
                        if (plankSlot != -1) {
                            setDebugState("Auto 2x2 crafting sticks...");
                            AltoClef.getInstance().getInventoryManager().craft2x2Sticks(mc, player, plankSlot);
                            stepTimer = 3;
                            return null;
                        }
                    } else {
                        int logs = InventoryManager.countItems(player, "log");
                        if (logs > 0) {
                            int logSlot = findLogSlotInInventory(player);
                            if (logSlot != -1) {
                                setDebugState("Auto 2x2 crafting planks for sticks...");
                                AltoClef.getInstance().getInventoryManager().craft2x2Planks(mc, player, logSlot);
                                stepTimer = 3;
                                return null;
                            }
                        }
                    }
                }
                // If missing ingredient is planks, auto-craft them from logs
                else if (req.getKey().equals("plank")) {
                    int logs = InventoryManager.countItems(player, "log");
                    if (logs > 0) {
                        int logSlot = findLogSlotInInventory(player);
                        if (logSlot != -1) {
                            setDebugState("Auto 2x2 crafting planks from logs...");
                            AltoClef.getInstance().getInventoryManager().craft2x2Planks(mc, player, logSlot);
                            stepTimer = 3;
                            return null;
                        }
                    }
                }

                missingIngredientTicks++;
                if (missingIngredientTicks > 12) {
                    setDebugState("Missing ingredient (" + req.getKey() + "). Aborting craft to re-gather...");
                    finished = true;
                    if (player.containerMenu instanceof CraftingMenu) {
                        player.closeContainer();
                    }
                    cancelBaritonePathing();
                    return null;
                }
                setDebugState("Missing ingredient: need " + req.getValue() + "x " + req.getKey());
                return null;
            }
        }
        missingIngredientTicks = 0;

        // 4. Find nearby reachable Crafting Table in world
        BlockPos existingTable = findNearbyTable(mc, player);
        if (existingTable != null) {
            cancelBaritonePathing();
            noSpotTicks = 0;
            placedTableWaitTicks++;

            // If any non-air block is directly above the table obstructing it, break it!
            BlockPos above = existingTable.above();
            if (!mc.level.getBlockState(above).isAir() && !mc.level.getBlockState(above).canBeReplaced()) {
                setDebugState("Clearing block obstructing Crafting Table: " + above.toShortString());
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
            Vec3 hitVec = Vec3.atCenterOf(existingTable).add(0, 0.5, 0);
            if (!mc.level.getBlockState(above).isAir() && !mc.level.getBlockState(above).canBeReplaced()) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbor = existingTable.relative(dir);
                    if (mc.level.getBlockState(neighbor).isAir() || mc.level.getBlockState(neighbor).canBeReplaced()) {
                        hitFace = dir;
                        hitVec = Vec3.atCenterOf(existingTable).add(dir.getStepX() * 0.5, 0, dir.getStepZ() * 0.5);
                        break;
                    }
                }
            }

            setDebugState("Opening Crafting Table at " + existingTable.toShortString());
            lookAt(player, hitVec);
            if (player.isShiftKeyDown()) {
                player.setShiftKeyDown(false);
            }
            BlockHitResult hit = new BlockHitResult(hitVec, hitFace, existingTable, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);

            // Watchdog: If placed/existing table isn't opening after multiple attempts (obstructed, in a hole, or blocked):
            if (placedTableWaitTicks > 14) {
                setDebugState("Crafting Table obstructed/unresponsive. Mining table to relocate...");
                int pickSlot = MineBlockTask.getPickaxeHotbarSlot(player);
                if (pickSlot != -1) {
                    player.getInventory().setSelectedSlot(pickSlot);
                }
                mc.gameMode.startDestroyBlock(existingTable, Direction.UP);
                if (!mc.level.getBlockState(existingTable).is(Blocks.CRAFTING_TABLE)) {
                    placedTablePos = null;
                    placedTableWaitTicks = 0;
                    noSpotTicks = 0;
                }
                stepTimer = 4;
                return null;
            }

            // If placed table isn't opening after a few attempts, step closer
            if (placedTableWaitTicks > 6) {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(existingTable, 1));
                }
            }

            stepTimer = 3;
            return null;
        }

        placedTableWaitTicks = 0;

        // 4b. If we have no Crafting Table in inventory, check if one is located further away (up to 16 blocks)
        int tableItemCount = InventoryManager.countItems(player, "crafting_table");
        if (tableItemCount == 0) {
            BlockPos distantTable = findDistantTable(mc, player, 16);
            if (distantTable != null) {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(distantTable, 2));
                    setDebugState("Navigating to existing Crafting Table at " + distantTable.toShortString());
                }
                stepTimer = 4;
                return null;
            }
        }

        // 5. Check if we need to craft a Crafting Table first
        if (tableItemCount == 0) {
            int planks = InventoryManager.countItems(player, "plank");
            if (planks >= 4) {
                setDebugState("Auto 2x2 crafting Crafting Table...");
                int plankSlot = findPlankSlotInInventory(player, 4);
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

    private int countTargetInMenuOrInventory(CraftingMenu menu, LocalPlayer player, String target) {
        int count = InventoryManager.countItems(player, target);
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty() && getItemName(carried).contains(target)) {
            count += carried.getCount();
        }
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && getItemName(stack).contains(target)) {
                // If container slot already accounted for by player inventory, ensure max is reported
                count = Math.max(count, stack.getCount());
            }
        }
        return count;
    }

    private void executeRecipeCraft(Minecraft mc, LocalPlayer player, CraftingMenu menu, RecipeDef recipe) {
        int containerId = menu.containerId;
        craftingGuiTicks++;
        ticksSinceLastCraftAction++;

        // 1. Success check: Does player inventory already have targetCount items?
        // First, if cursor is holding the target item, deposit it into an inventory slot!
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty() && getItemName(carried).contains(itemTarget)) {
            int targetSlot = findSlotToDepositTarget(menu, itemTarget);
            if (targetSlot != -1) {
                setDebugState("Depositing crafted " + itemTarget + " into inventory...");
                mc.gameMode.handleContainerInput(containerId, targetSlot, 0, ContainerInput.PICKUP, player);
                ticksSinceLastCraftAction = 0;
                stepTimer = 2;
                return;
            }
        }

        // Count how many of target item we have in player inventory
        int currentCount = InventoryManager.countItems(player, itemTarget);
        if (currentCount >= targetCount) {
            // Deposit carried item if still holding anything
            if (!menu.getCarried().isEmpty()) {
                int targetSlot = findDisposableSlotInContainer(menu);
                if (targetSlot != -1) {
                    mc.gameMode.handleContainerInput(containerId, targetSlot, 0, ContainerInput.PICKUP, player);
                    stepTimer = 2;
                    return;
                }
            }
            player.closeContainer();
            setDebugState("Successfully crafted " + itemTarget + "! Closed Crafting Table.");
            finished = true;
            craftingMenuOpenCloseLoops = 0;
            craftingGuiTicks = 0;
            ticksSinceLastCraftAction = 0;
            return;
        }

        // 2. Result Slot (Slot 0) Extraction:
        // STRICT TARGET MATCH: Only extract if slot 0 matches the intended recipe target!
        // This prevents extracting accidental intermediate items like a wooden hoe while placing planks for a pickaxe!
        ItemStack resultStack = menu.getSlot(0).getItem();
        if (!resultStack.isEmpty() && matchesKeyword(getItemName(resultStack), itemTarget)) {
            // Before extracting, ensure cursor is empty
            if (!menu.getCarried().isEmpty()) {
                int targetSlot = (lastIngredientSourceSlot >= 10 && lastIngredientSourceSlot < menu.slots.size())
                        ? lastIngredientSourceSlot 
                        : findDisposableSlotInContainer(menu);
                if (targetSlot != -1) {
                    mc.gameMode.handleContainerInput(containerId, targetSlot, 0, ContainerInput.PICKUP, player);
                    lastIngredientSourceSlot = -1;
                    currentIngredientKeyword = null;
                    ticksSinceLastCraftAction = 0;
                    stepTimer = 2;
                    return;
                }
            }

            extractAttempts++;
            ticksSinceLastCraftAction = 0;
            if (extractAttempts <= 3) {
                setDebugState("Extracting " + getItemName(resultStack) + " from craft result slot 0 (Shift-Click)...");
                mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
            } else {
                // Fallback: Pick up to cursor, then deposit next tick
                setDebugState("Extracting " + getItemName(resultStack) + " from craft result slot 0 (Pickup)...");
                mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.PICKUP, player);
            }
            stepTimer = 2;
            return;
        } else {
            extractAttempts = 0;
        }

        // 3. Handle Carried Item in Cursor:
        if (!menu.getCarried().isEmpty()) {
            ItemStack carriedStack = menu.getCarried();
            String carriedName = getItemName(carriedStack);

            if (currentIngredientKeyword == null) {
                for (String kw : recipe.requiredCounts.keySet()) {
                    if (matchesKeyword(carriedName, kw)) {
                        currentIngredientKeyword = kw;
                        break;
                    }
                }
            }

            // Is the carried item an ingredient we need to place into an unpopulated grid slot?
            if (currentIngredientKeyword != null && matchesKeyword(carriedName, currentIngredientKeyword)) {
                // Find next slot that needs this ingredient
                int targetGridSlot = -1;
                for (Map.Entry<Integer, String> entry : recipe.gridSlots.entrySet()) {
                    if (entry.getValue().equals(currentIngredientKeyword)) {
                        ItemStack inSlot = menu.getSlot(entry.getKey()).getItem();
                        if (inSlot.isEmpty() || !matchesKeyword(getItemName(inSlot), currentIngredientKeyword)) {
                            targetGridSlot = entry.getKey();
                            break;
                        }
                    }
                }

                if (targetGridSlot != -1) {
                    // Right-click grid slot to place 1 item
                    setDebugState("Placing " + currentIngredientKeyword + " into grid slot " + targetGridSlot);
                    mc.gameMode.handleContainerInput(containerId, targetGridSlot, 1, ContainerInput.PICKUP, player);
                    ticksSinceLastCraftAction = 0;
                    stepTimer = 2;
                    return;
                }
            }

            // If carried item is no longer needed in the grid, return it to inventory
            int returnSlot = (lastIngredientSourceSlot >= 10 && lastIngredientSourceSlot < menu.slots.size()) 
                    ? lastIngredientSourceSlot 
                    : findDisposableSlotInContainer(menu);
            if (returnSlot != -1) {
                setDebugState("Returning leftover ingredient to inventory slot " + returnSlot);
                mc.gameMode.handleContainerInput(containerId, returnSlot, 0, ContainerInput.PICKUP, player);
                lastIngredientSourceSlot = -1;
                currentIngredientKeyword = null;
                ticksSinceLastCraftAction = 0;
                stepTimer = 2;
                return;
            }
        }

        // 4. Clear any stray/wrong items in the 3x3 crafting grid (slots 1..9)
        for (int s = 1; s <= 9; s++) {
            ItemStack inSlot = menu.getSlot(s).getItem();
            if (!inSlot.isEmpty()) {
                String expectedKeyword = recipe.gridSlots.get(s);
                if (expectedKeyword == null || !matchesKeyword(getItemName(inSlot), expectedKeyword)) {
                    setDebugState("Clearing stray item " + getItemName(inSlot) + " from grid slot " + s);
                    mc.gameMode.handleContainerInput(containerId, s, 0, ContainerInput.QUICK_MOVE, player);
                    ticksSinceLastCraftAction = 0;
                    stepTimer = 2;
                    return;
                }
            }
        }

        // 5. Check if all recipe grid slots are satisfied
        boolean allSlotsSatisfied = true;
        for (Map.Entry<Integer, String> entry : recipe.gridSlots.entrySet()) {
            ItemStack inSlot = menu.getSlot(entry.getKey()).getItem();
            if (inSlot.isEmpty() || !matchesKeyword(getItemName(inSlot), entry.getValue())) {
                allSlotsSatisfied = false;
                break;
            }
        }

        if (allSlotsSatisfied) {
            // Recipe is complete in grid! Wait for server to sync slot 0.
            // If waited > 8 ticks with all slots populated and slot 0 still empty, click slot 0 to nudge server
            if (ticksSinceLastCraftAction > 8 && ticksSinceLastCraftAction % 6 == 0) {
                setDebugState("Nudging server craft evaluation on slot 0...");
                mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
            }
            stepTimer = 2;
            return;
        }

        // 6. Grid is not satisfied: pick up the next required ingredient
        for (Map.Entry<Integer, String> entry : recipe.gridSlots.entrySet()) {
            int slot = entry.getKey();
            String keyword = entry.getValue();
            ItemStack inSlot = menu.getSlot(slot).getItem();
            if (inSlot.isEmpty() || !matchesKeyword(getItemName(inSlot), keyword)) {
                int invSlot = findBestSlotInContainer(menu, keyword);
                if (invSlot == -1) {
                    setDebugState("Missing ingredient stack for " + keyword + " in inventory!");
                    player.closeContainer();
                    finished = true;
                    stepTimer = 4;
                    return;
                }

                setDebugState("Picking up " + keyword + " from inventory slot " + invSlot);
                lastIngredientSourceSlot = invSlot;
                currentIngredientKeyword = keyword;
                mc.gameMode.handleContainerInput(containerId, invSlot, 0, ContainerInput.PICKUP, player);
                ticksSinceLastCraftAction = 0;
                stepTimer = 2;
                return;
            }
        }

        // 7. Watchdog inside GUI:
        // If inactive for > 70 ticks (~3.5s) or GUI open for > 140 ticks (~7.0s) without completing:
        if (ticksSinceLastCraftAction > 70 || craftingGuiTicks > 140) {
            setDebugState("Watchdog: Crafting GUI unresponsive (" + ticksSinceLastCraftAction + " idle ticks). Resetting...");
            if (!menu.getCarried().isEmpty()) {
                int targetSlot = findDisposableSlotInContainer(menu);
                if (targetSlot != -1) {
                    mc.gameMode.handleContainerInput(containerId, targetSlot, 0, ContainerInput.PICKUP, player);
                }
            }
            player.closeContainer();
            craftingGuiTicks = 0;
            ticksSinceLastCraftAction = 0;
            lastIngredientSourceSlot = -1;
            currentIngredientKeyword = null;
            stepTimer = 4;
            return;
        }

        stepTimer = 2;
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

    private BlockPos findDistantTable(Minecraft mc, LocalPlayer player, int radius) {
        if (mc.level == null) return null;
        Vec3 eyePos = player.getEyePosition();
        BlockPos center = player.blockPosition();
        BlockPos bestTable = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos p = center.offset(x, y, z);
                    if (mc.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
                        double d = eyePos.distanceToSqr(Vec3.atCenterOf(p));
                        if (d < bestDistSq) {
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

                    // Ensure space above target is clear so table is never obstructed
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

    public static boolean matchesKeyword(String itemName, String keyword) {
        if (itemName == null || keyword == null) return false;
        itemName = itemName.toLowerCase();
        keyword = keyword.toLowerCase();
        if (itemName.equals(keyword) || itemName.contains(keyword)) {
            return true;
        }
        if (keyword.equals("cobble")) {
            return itemName.contains("cobblestone") || itemName.contains("cobbled_deepslate") || itemName.contains("blackstone");
        }
        if (keyword.equals("plank")) {
            return itemName.endsWith("_planks") || itemName.contains("plank");
        }
        if (keyword.equals("log")) {
            return itemName.endsWith("_log") || itemName.endsWith("_wood") || itemName.endsWith("_stem") || itemName.contains("log");
        }
        if (keyword.equals("wool")) {
            return itemName.endsWith("_wool") || itemName.contains("wool");
        }
        return false;
    }

    private int findBestSlotInContainer(CraftingMenu menu, String keyword) {
        int bestSlot = -1;
        int maxCount = 0;
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && matchesKeyword(getItemName(stack), keyword)) {
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

    private int findDisposableSlotInContainer(CraftingMenu menu) {
        int empty = findEmptyPlayerSlotInContainer(menu);
        if (empty != -1) return empty;
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                String name = getItemName(stack);
                if (name.contains("rotten_flesh") || name.contains("seeds") || name.contains("poisonous_potato")) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int findSlotToDepositTarget(CraftingMenu menu, String target) {
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && getItemName(stack).contains(target) && stack.getCount() < stack.getMaxStackSize()) {
                return i;
            }
        }
        return findDisposableSlotInContainer(menu);
    }

    private int findPlankSlotInInventory(LocalPlayer player, int minCount) {
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = player.inventoryMenu.getSlot(i).getItem();
            if (!stack.isEmpty() && matchesKeyword(getItemName(stack), "plank") && stack.getCount() >= minCount) {
                return i;
            }
        }
        return -1;
    }

    private int findLogSlotInInventory(LocalPlayer player) {
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = player.inventoryMenu.getSlot(i).getItem();
            if (!stack.isEmpty() && matchesKeyword(getItemName(stack), "log")) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onStop(Task interruptTask) {
        cancelBaritonePathing();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu instanceof CraftingMenu) {
            mc.player.closeContainer();
        }
    }

    @Override
    public boolean isFinished() {
        if (finished) return true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            int count = InventoryManager.countItems(mc.player, itemTarget);
            if (mc.player.containerMenu instanceof CraftingMenu menu) {
                count = Math.max(count, countTargetInMenuOrInventory(menu, mc.player, itemTarget));
            }
            if (count >= targetCount) {
                finished = true;
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CraftInTableTask t) {
            return t.itemTarget.equals(this.itemTarget) && t.targetCount == this.targetCount;
        }
        return false;
    }

    public boolean hasRequiredIngredients(LocalPlayer player) {
        if (player == null) return false;
        RecipeDef recipe = RECIPES.get(itemTarget);
        if (recipe == null) return false;
        CraftingMenu menu = (player.containerMenu instanceof CraftingMenu cm) ? cm : null;
        for (Map.Entry<String, Integer> req : recipe.requiredCounts.entrySet()) {
            int count = InventoryManager.countItems(player, req.getKey());
            if (menu != null) {
                ItemStack carried = menu.getCarried();
                if (!carried.isEmpty() && matchesKeyword(getItemName(carried), req.getKey())) {
                    count += carried.getCount();
                }
                for (int s = 1; s <= 9; s++) {
                    ItemStack inSlot = menu.getSlot(s).getItem();
                    if (!inSlot.isEmpty() && matchesKeyword(getItemName(inSlot), req.getKey())) {
                        count += inSlot.getCount();
                    }
                }
            }
            if (req.getKey().equals("stick")) {
                if (count < req.getValue()) {
                    int planks = InventoryManager.countItems(player, "plank");
                    int logs = InventoryManager.countItems(player, "log");
                    if (planks < 2 && logs < 1) return false;
                }
            } else if (req.getKey().equals("plank")) {
                if (count < req.getValue()) {
                    int logs = InventoryManager.countItems(player, "log");
                    if (logs < 1) return false;
                }
            } else {
                if (count < req.getValue()) return false;
            }
        }
        return true;
    }

    @Override
    protected String toDebugString() {
        return "Crafting " + targetCount + "x " + itemTarget + " in Table";
    }
}
