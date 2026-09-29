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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
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

        // Check if item is already in inventory
        if (InventoryManager.countItems(player, itemTarget) >= targetCount) {
            finished = true;
            cleanUpPlacedTable(mc);
            return null;
        }

        RecipeDef recipe = RECIPES.get(itemTarget);
        if (recipe == null) {
            setDebugState("Unknown recipe target: " + itemTarget);
            finished = true;
            return null;
        }

        // Verify required materials exist
        for (Map.Entry<String, Integer> req : recipe.requiredCounts.entrySet()) {
            if (InventoryManager.countItems(player, req.getKey()) < req.getValue()) {
                setDebugState("Missing ingredient: need " + req.getValue() + "x " + req.getKey());
                return null;
            }
        }

        // 1. Crafting table menu is currently open!
        if (player.containerMenu instanceof CraftingMenu menu) {
            executeRecipeCraft(mc, player, menu, recipe);
            return null;
        }

        // 2. Crafting table is NOT open yet: find or place one
        BlockPos existingTable = findNearbyTable(mc, player);
        if (existingTable != null) {
            setDebugState("Opening nearby Crafting Table at " + existingTable.toShortString());
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(existingTable), Direction.UP, existingTable, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            stepTimer = 5;
            return null;
        }

        // 3. Need to place a crafting table
        int tableItemCount = InventoryManager.countItems(player, "crafting_table");
        if (tableItemCount == 0) {
            // Auto 2x2 craft crafting table if we have 4 planks
            if (InventoryManager.countItems(player, "plank") >= 4) {
                AltoClef.getInstance().getInventoryManager().tick(AltoClef.getInstance());
                stepTimer = 4;
            } else {
                setDebugState("Need 4 planks to craft Crafting Table!");
            }
            return null;
        }

        // Place table
        BlockPos placePos = findPlacingSpot(mc, player);
        if (placePos != null) {
            int hotbarTableSlot = findHotbarItem(player, "crafting_table");
            if (hotbarTableSlot != -1) {
                player.getInventory().setSelectedSlot(hotbarTableSlot);
                BlockPos support = placePos.below();
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
                mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                placedTablePos = placePos;
                stepTimer = 3;
            }
        }

        return null;
    }

    private void executeRecipeCraft(Minecraft mc, LocalPlayer player, CraftingMenu menu, RecipeDef recipe) {
        int containerId = menu.containerId;

        // Group required slots by ingredient keyword
        Map<String, List<Integer>> keywordToSlots = new HashMap<>();
        for (Map.Entry<Integer, String> entry : recipe.gridSlots.entrySet()) {
            keywordToSlots.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        // For each ingredient group, place into grid
        for (Map.Entry<String, List<Integer>> group : keywordToSlots.entrySet()) {
            String keyword = group.getKey();
            List<Integer> slotsToFill = group.getValue();

            int invSlot = findSlotInContainer(menu, keyword);
            if (invSlot == -1) return;

            // Pick up ingredient stack
            mc.gameMode.handleContainerInput(containerId, invSlot, 0, ContainerInput.PICKUP, player);

            // Right click each slot to place 1 item
            for (int gridSlot : slotsToFill) {
                mc.gameMode.handleContainerInput(containerId, gridSlot, 1, ContainerInput.PICKUP, player);
            }

            // Return remaining items to original inventory slot
            mc.gameMode.handleContainerInput(containerId, invSlot, 0, ContainerInput.PICKUP, player);
        }

        // Take crafted item from slot 0 with quick move!
        mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);

        // Clear any leftovers from 3x3 grid back into inventory
        for (int s = 1; s <= 9; s++) {
            if (!menu.getSlot(s).getItem().isEmpty()) {
                mc.gameMode.handleContainerInput(containerId, s, 0, ContainerInput.QUICK_MOVE, player);
            }
        }

        // Close screen
        player.closeContainer();
        setDebugState("Successfully crafted " + itemTarget + "!");

        cleanUpPlacedTable(mc);
        finished = true;
    }

    private void cleanUpPlacedTable(Minecraft mc) {
        if (placedTablePos != null && mc.gameMode != null && mc.level != null) {
            if (mc.level.getBlockState(placedTablePos).is(Blocks.CRAFTING_TABLE)) {
                mc.gameMode.destroyBlock(placedTablePos);
            }
            placedTablePos = null;
        }
    }

    private BlockPos findNearbyTable(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;
        BlockPos center = player.blockPosition();
        for (int x = -3; x <= 3; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -3; z <= 3; z++) {
                    BlockPos p = center.offset(x, y, z);
                    if (mc.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
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

    private int findSlotInContainer(CraftingMenu menu, String keyword) {
        keyword = keyword.toLowerCase();
        // Slots 10..45 are player inventory in CraftingMenu
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && stack.getItem().toString().toLowerCase().contains(keyword)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onStop(Task interruptTask) {
        Minecraft mc = Minecraft.getInstance();
        cleanUpPlacedTable(mc);
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
