package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.tasks.construction.MineBlockTask;
import adris.altoclef.tasks.construction.MineBlockTask.ToolTier;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

public class GetItemTask extends Task {

    private final AltoClef mod;
    private final String item;
    private final int targetCount;
    private Task treeSubTask;

    public GetItemTask(AltoClef mod, String item, int targetCount) {
        this.mod = mod;
        this.item = (item != null ? item.toLowerCase().replace(" ", "_").trim() : "");
        this.targetCount = Math.max(1, targetCount);
    }

    public GetItemTask(String item) {
        this(AltoClef.getInstance(), item, 1);
    }

    public GetItemTask(String item, int count) {
        this(AltoClef.getInstance(), item, count);
    }

    @Override
    protected void onStart() {
        setDebugState("Acquiring " + targetCount + "x " + item + " (Task Tree)");
        treeSubTask = null;
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return null;

        // Completion check for requested item
        if (isItemAcquired(mc, player, item, targetCount)) {
            setDebugState("Acquired " + targetCount + "x " + item + "!");
            return null;
        }

        // If actively crafting in table or smelting in furnace, let subtask complete!
        // When items are moved from player inventory into crafting/furnace grids,
        // player inventory counts temporarily drop. Re-evaluating the tree here would falsely conclude
        // ingredients are missing, close the container, and abort the craft.
        if (treeSubTask instanceof CraftInTableTask craftTask && !craftTask.isFinished()) {
            if (player.containerMenu instanceof CraftingMenu || craftTask.hasRequiredIngredients(player)) {
                return treeSubTask;
            }
        }
        if (treeSubTask instanceof SmeltInFurnaceTask smeltTask && !smeltTask.isFinished()) {
            if (player.containerMenu instanceof FurnaceMenu) {
                return treeSubTask;
            }
        }

        // Dynamically evaluate the full task tree every tick
        Task nextAction = evaluateTree(mc, player, item, targetCount);
        if (nextAction != null && !nextAction.equals(treeSubTask)) {
            if (treeSubTask != null && !treeSubTask.isFinished()) {
                treeSubTask.stop(nextAction);
            }
            treeSubTask = nextAction;
        }

        return treeSubTask;
    }

    private boolean isItemAcquired(Minecraft mc, LocalPlayer player, String itemKey, int count) {
        if (player == null) return false;
        String clean = cleanKey(itemKey);

        if (clean.equals("diamond_armor") || clean.equals("diamond_gear")) {
            return hasEquippedOrInventory(player, "diamond_helmet") &&
                   hasEquippedOrInventory(player, "diamond_chestplate") &&
                   hasEquippedOrInventory(player, "diamond_leggings") &&
                   hasEquippedOrInventory(player, "diamond_boots");
        }
        if (clean.equals("iron_armor") || clean.equals("iron_gear")) {
            return hasEquippedOrInventory(player, "iron_helmet") &&
                   hasEquippedOrInventory(player, "iron_chestplate") &&
                   hasEquippedOrInventory(player, "iron_leggings") &&
                   hasEquippedOrInventory(player, "iron_boots");
        }
        if (clean.equals("golden_armor") || clean.equals("gold_armor")) {
            return hasEquippedOrInventory(player, "golden_helmet") &&
                   hasEquippedOrInventory(player, "golden_chestplate") &&
                   hasEquippedOrInventory(player, "golden_leggings") &&
                   hasEquippedOrInventory(player, "golden_boots");
        }

        return InventoryManager.countItems(player, clean) >= count;
    }

    private boolean hasEquippedOrInventory(LocalPlayer player, String armorPiece) {
        if (InventoryManager.countItems(player, armorPiece) > 0) return true;
        if (player.inventoryMenu != null) {
            for (int i = 0; i < 4; i++) {
                ItemStack stack = player.inventoryMenu.getSlot(net.minecraft.world.inventory.InventoryMenu.ARMOR_SLOT_START + i).getItem();
                if (!stack.isEmpty() && InventoryManager.getItemName(stack).contains(armorPiece)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Pure reactive Task Tree:
     * Recursively traverses from the goal down to the root prerequisite,
     * skipping ahead immediately if intermediate items are already found/mined!
     */
    private Task evaluateTree(Minecraft mc, LocalPlayer player, String targetItem, int count) {
        String clean = cleanKey(targetItem);

        // 1. COMPOUND SETS
        if (clean.equals("diamond_armor") || clean.equals("diamond_gear")) {
            if (!hasEquippedOrInventory(player, "diamond_helmet")) return evaluateTree(mc, player, "diamond_helmet", 1);
            if (!hasEquippedOrInventory(player, "diamond_chestplate")) return evaluateTree(mc, player, "diamond_chestplate", 1);
            if (!hasEquippedOrInventory(player, "diamond_leggings")) return evaluateTree(mc, player, "diamond_leggings", 1);
            if (!hasEquippedOrInventory(player, "diamond_boots")) return evaluateTree(mc, player, "diamond_boots", 1);
            return null;
        }
        if (clean.equals("iron_armor") || clean.equals("iron_gear")) {
            if (!hasEquippedOrInventory(player, "iron_helmet")) return evaluateTree(mc, player, "iron_helmet", 1);
            if (!hasEquippedOrInventory(player, "iron_chestplate")) return evaluateTree(mc, player, "iron_chestplate", 1);
            if (!hasEquippedOrInventory(player, "iron_leggings")) return evaluateTree(mc, player, "iron_leggings", 1);
            if (!hasEquippedOrInventory(player, "iron_boots")) return evaluateTree(mc, player, "iron_boots", 1);
            return null;
        }
        if (clean.equals("golden_armor") || clean.equals("gold_armor")) {
            if (!hasEquippedOrInventory(player, "golden_helmet")) return evaluateTree(mc, player, "golden_helmet", 1);
            if (!hasEquippedOrInventory(player, "golden_chestplate")) return evaluateTree(mc, player, "golden_chestplate", 1);
            if (!hasEquippedOrInventory(player, "golden_leggings")) return evaluateTree(mc, player, "golden_leggings", 1);
            if (!hasEquippedOrInventory(player, "golden_boots")) return evaluateTree(mc, player, "golden_boots", 1);
            return null;
        }

        // 2. DIAMOND ARMOR PIECES
        if (clean.equals("diamond_helmet")) return evaluateArmorPiece(mc, player, "diamond_helmet", "diamond", 5);
        if (clean.equals("diamond_chestplate")) return evaluateArmorPiece(mc, player, "diamond_chestplate", "diamond", 8);
        if (clean.equals("diamond_leggings")) return evaluateArmorPiece(mc, player, "diamond_leggings", "diamond", 7);
        if (clean.equals("diamond_boots")) return evaluateArmorPiece(mc, player, "diamond_boots", "diamond", 4);

        // 3. IRON ARMOR PIECES
        if (clean.equals("iron_helmet")) return evaluateArmorPiece(mc, player, "iron_helmet", "iron_ingot", 5);
        if (clean.equals("iron_chestplate")) return evaluateArmorPiece(mc, player, "iron_chestplate", "iron_ingot", 8);
        if (clean.equals("iron_leggings")) return evaluateArmorPiece(mc, player, "iron_leggings", "iron_ingot", 7);
        if (clean.equals("iron_boots")) return evaluateArmorPiece(mc, player, "iron_boots", "iron_ingot", 4);

        // 4. GOLDEN ARMOR PIECES
        if (clean.equals("golden_helmet")) return evaluateArmorPiece(mc, player, "golden_helmet", "gold_ingot", 5);
        if (clean.equals("golden_chestplate")) return evaluateArmorPiece(mc, player, "golden_chestplate", "gold_ingot", 8);
        if (clean.equals("golden_leggings")) return evaluateArmorPiece(mc, player, "golden_leggings", "gold_ingot", 7);
        if (clean.equals("golden_boots")) return evaluateArmorPiece(mc, player, "golden_boots", "gold_ingot", 4);

        // 5. DIAMOND TOOLS
        if (clean.equals("diamond_pickaxe")) return evaluateCraftTool(mc, player, "diamond_pickaxe", "diamond", 3, 2);
        if (clean.equals("diamond_sword")) return evaluateCraftTool(mc, player, "diamond_sword", "diamond", 2, 1);
        if (clean.equals("diamond_axe")) return evaluateCraftTool(mc, player, "diamond_axe", "diamond", 3, 2);
        if (clean.equals("diamond_shovel")) return evaluateCraftTool(mc, player, "diamond_shovel", "diamond", 1, 2);
        if (clean.equals("diamond_hoe")) return evaluateCraftTool(mc, player, "diamond_hoe", "diamond", 2, 2);

        // 6. IRON TOOLS
        if (clean.equals("iron_pickaxe")) return evaluateCraftTool(mc, player, "iron_pickaxe", "iron_ingot", 3, 2);
        if (clean.equals("iron_sword")) return evaluateCraftTool(mc, player, "iron_sword", "iron_ingot", 2, 1);
        if (clean.equals("iron_axe")) return evaluateCraftTool(mc, player, "iron_axe", "iron_ingot", 3, 2);
        if (clean.equals("iron_shovel")) return evaluateCraftTool(mc, player, "iron_shovel", "iron_ingot", 1, 2);
        if (clean.equals("iron_hoe")) return evaluateCraftTool(mc, player, "iron_hoe", "iron_ingot", 2, 2);

        // 7. STONE TOOLS
        if (clean.equals("stone_pickaxe")) return evaluateCraftTool(mc, player, "stone_pickaxe", "cobblestone", 3, 2);
        if (clean.equals("stone_sword")) return evaluateCraftTool(mc, player, "stone_sword", "cobblestone", 2, 1);
        if (clean.equals("stone_axe")) return evaluateCraftTool(mc, player, "stone_axe", "cobblestone", 3, 2);
        if (clean.equals("stone_shovel")) return evaluateCraftTool(mc, player, "stone_shovel", "cobblestone", 1, 2);
        if (clean.equals("stone_hoe")) return evaluateCraftTool(mc, player, "stone_hoe", "cobblestone", 2, 2);

        // 8. WOODEN TOOLS
        if (clean.equals("wooden_pickaxe") || clean.equals("wood_pickaxe")) return evaluateCraftWoodenTool(mc, player, "wooden_pickaxe", 3, 2);
        if (clean.equals("wooden_sword") || clean.equals("wood_sword")) return evaluateCraftWoodenTool(mc, player, "wooden_sword", 2, 1);
        if (clean.equals("wooden_axe") || clean.equals("wood_axe")) return evaluateCraftWoodenTool(mc, player, "wooden_axe", 3, 2);
        if (clean.equals("wooden_shovel") || clean.equals("wood_shovel")) return evaluateCraftWoodenTool(mc, player, "wooden_shovel", 1, 2);
        if (clean.equals("wooden_hoe") || clean.equals("wood_hoe")) return evaluateCraftWoodenTool(mc, player, "wooden_hoe", 2, 2);

        // 9. UTILITIES & GEAR
        if (clean.equals("shield")) {
            if (InventoryManager.countItems(player, "shield") > 0) return null;
            if (InventoryManager.countItems(player, "iron_ingot") < 1) return evaluateTree(mc, player, "iron_ingot", 1);
            int totalPlanks = InventoryManager.countItems(player, "log") * 4 + InventoryManager.countItems(player, "plank");
            if (totalPlanks < 6) return evaluateTree(mc, player, "log", 2);
            if (!ensureCraftingTable(mc, player)) return evaluateTree(mc, player, "crafting_table", 1);
            return new CraftInTableTask("shield");
        }

        if (clean.equals("bucket") || clean.equals("water_bucket")) {
            if (InventoryManager.countItems(player, clean) >= count) return null;
            if (InventoryManager.countItems(player, "iron_ingot") < 3) return evaluateTree(mc, player, "iron_ingot", 3);
            if (!ensureCraftingTable(mc, player)) return evaluateTree(mc, player, "crafting_table", 1);
            return new CraftInTableTask("bucket");
        }

        if (clean.equals("flint_and_steel")) {
            if (InventoryManager.countItems(player, "flint_and_steel") > 0) return null;
            if (InventoryManager.countItems(player, "iron_ingot") < 1) return evaluateTree(mc, player, "iron_ingot", 1);
            if (InventoryManager.countItems(player, "flint") < 1) return new MineBlockTask(mod, "flint", "gravel", 1);
            return new CraftInTableTask("flint_and_steel");
        }

        if (clean.equals("crafting_table")) {
            if (InventoryManager.countItems(player, "crafting_table") > 0 || isCraftingTableNearby(mc, player, 16)) return null;
            int totalPlanks = InventoryManager.countItems(player, "log") * 4 + InventoryManager.countItems(player, "plank");
            if (totalPlanks < 4) return evaluateTree(mc, player, "log", 2);
            return new CraftInTableTask("crafting_table");
        }

        if (clean.equals("furnace")) {
            if (InventoryManager.countItems(player, "furnace") > 0 || isFurnaceNearby(mc, player, 16)) return null;
            int normalCobble = InventoryManager.countItems(player, "cobblestone");
            int deepslateCobble = InventoryManager.countItems(player, "cobbled_deepslate");
            int blackstone = InventoryManager.countItems(player, "blackstone");
            if (normalCobble < 8 && deepslateCobble < 8 && blackstone < 8) {
                if (deepslateCobble > normalCobble) {
                    return evaluateTree(mc, player, "cobbled_deepslate", 8);
                } else {
                    return evaluateTree(mc, player, "cobblestone", 8);
                }
            }
            if (!ensureCraftingTable(mc, player)) return evaluateTree(mc, player, "crafting_table", 1);
            return new CraftInTableTask("furnace");
        }

        if (clean.equals("stick")) {
            if (InventoryManager.countItems(player, "stick") >= count) return null;
            int totalPlanks = InventoryManager.countItems(player, "log") * 4 + InventoryManager.countItems(player, "plank");
            if (totalPlanks < 2) return evaluateTree(mc, player, "log", 1);
            return new CraftInTableTask("stick");
        }

        if (clean.equals("plank") || clean.equals("planks")) {
            if (InventoryManager.countItems(player, "plank") >= count) return null;
            if (InventoryManager.countItems(player, "log") < 1) return evaluateTree(mc, player, "log", Math.max(1, count / 4));
            return new CraftInTableTask("planks");
        }

        // 10. SMELTED MATERIALS
        if (clean.equals("iron_ingot") || clean.equals("iron")) {
            int currentIngots = InventoryManager.countItems(player, "iron_ingot");
            if (currentIngots >= count) return null;
            int needed = count - currentIngots;

            // Check furnace
            if (!isFurnaceNearby(mc, player, 16) && InventoryManager.countItems(player, "furnace") == 0) {
                return evaluateTree(mc, player, "furnace", 1);
            }
            // Check fuel
            int fuel = InventoryManager.countItems(player, "coal", "charcoal", "log");
            if (fuel < Math.max(1, needed / 8)) {
                return evaluateTree(mc, player, "coal", 4);
            }
            // Check raw material
            int raw = InventoryManager.countItems(player, "raw_iron", "iron_ore", "deepslate_iron_ore");
            if (raw < needed) {
                return evaluateTree(mc, player, "raw_iron", needed);
            }
            return new SmeltInFurnaceTask("raw_iron", needed);
        }

        if (clean.equals("gold_ingot") || clean.equals("gold")) {
            int currentIngots = InventoryManager.countItems(player, "gold_ingot");
            if (currentIngots >= count) return null;
            int needed = count - currentIngots;

            if (!isFurnaceNearby(mc, player, 16) && InventoryManager.countItems(player, "furnace") == 0) {
                return evaluateTree(mc, player, "furnace", 1);
            }
            int fuel = InventoryManager.countItems(player, "coal", "charcoal", "log");
            if (fuel < Math.max(1, needed / 8)) {
                return evaluateTree(mc, player, "coal", 4);
            }
            int raw = InventoryManager.countItems(player, "raw_gold", "gold_ore", "nether_gold_ore");
            if (raw < needed) {
                return evaluateTree(mc, player, "raw_gold", needed);
            }
            return new SmeltInFurnaceTask("raw_gold", needed);
        }

        if (clean.equals("copper_ingot") || clean.equals("copper")) {
            int currentIngots = InventoryManager.countItems(player, "copper_ingot");
            if (currentIngots >= count) return null;
            int needed = count - currentIngots;

            if (!isFurnaceNearby(mc, player, 16) && InventoryManager.countItems(player, "furnace") == 0) {
                return evaluateTree(mc, player, "furnace", 1);
            }
            int raw = InventoryManager.countItems(player, "raw_copper", "copper_ore");
            if (raw < needed) {
                return evaluateTree(mc, player, "raw_copper", needed);
            }
            return new SmeltInFurnaceTask("raw_copper", needed);
        }

        // 11. RAW / MINED MATERIALS
        if (clean.equals("diamond")) {
            int current = InventoryManager.countItems(player, "diamond");
            if (current >= count) return null;
            ToolTier tier = MineBlockTask.getPlayerPickaxeTier(player);
            if (tier.getLevel() < ToolTier.IRON.getLevel()) {
                setDebugState("Tree: Requires Iron Pickaxe to mine Diamonds");
                return evaluateTree(mc, player, "iron_pickaxe", 1);
            }
            return new MineBlockTask(mod, "diamond ore", "diamond_ore deepslate_diamond_ore", count);
        }

        if (clean.equals("raw_iron") || clean.equals("iron_ore")) {
            int current = InventoryManager.countItems(player, "raw_iron", "iron_ore", "deepslate_iron_ore", "iron_ingot");
            if (current >= count) return null;
            ToolTier tier = MineBlockTask.getPlayerPickaxeTier(player);
            if (tier.getLevel() < ToolTier.STONE.getLevel()) {
                setDebugState("Tree: Requires Stone Pickaxe to mine Iron Ore");
                return evaluateTree(mc, player, "stone_pickaxe", 1);
            }
            return new MineBlockTask(mod, "iron ore", "iron_ore deepslate_iron_ore raw_iron_block", count);
        }

        if (clean.equals("raw_gold") || clean.equals("gold_ore")) {
            int current = InventoryManager.countItems(player, "raw_gold", "gold_ore", "gold_ingot");
            if (current >= count) return null;
            ToolTier tier = MineBlockTask.getPlayerPickaxeTier(player);
            if (tier.getLevel() < ToolTier.IRON.getLevel()) {
                setDebugState("Tree: Requires Iron Pickaxe to mine Gold Ore");
                return evaluateTree(mc, player, "iron_pickaxe", 1);
            }
            return new MineBlockTask(mod, "gold ore", "gold_ore deepslate_gold_ore nether_gold_ore", count);
        }

        if (clean.equals("raw_copper") || clean.equals("copper_ore")) {
            int current = InventoryManager.countItems(player, "raw_copper", "copper_ore", "copper_ingot");
            if (current >= count) return null;
            ToolTier tier = MineBlockTask.getPlayerPickaxeTier(player);
            if (tier.getLevel() < ToolTier.STONE.getLevel()) {
                return evaluateTree(mc, player, "stone_pickaxe", 1);
            }
            return new MineBlockTask(mod, "copper ore", "copper_ore deepslate_copper_ore", count);
        }

        if (clean.equals("coal")) {
            int current = InventoryManager.countItems(player, "coal", "charcoal");
            if (current >= count) return null;
            ToolTier tier = MineBlockTask.getPlayerPickaxeTier(player);
            if (tier.getLevel() < ToolTier.WOOD.getLevel()) {
                return evaluateTree(mc, player, "wooden_pickaxe", 1);
            }
            return new MineBlockTask(mod, "coal", "coal_ore deepslate_coal_ore", count);
        }

        if (clean.equals("obsidian")) {
            int current = InventoryManager.countItems(player, "obsidian");
            if (current >= count) return null;
            ToolTier tier = MineBlockTask.getPlayerPickaxeTier(player);
            if (tier.getLevel() < ToolTier.DIAMOND.getLevel()) {
                setDebugState("Tree: Requires Diamond Pickaxe to mine Obsidian");
                return evaluateTree(mc, player, "diamond_pickaxe", 1);
            }
            return new MineBlockTask(mod, "obsidian", "obsidian", count);
        }

        if (clean.equals("cobbled_deepslate")) {
            int current = InventoryManager.countItems(player, "cobbled_deepslate");
            if (current >= count) return null;
            ToolTier tier = MineBlockTask.getPlayerPickaxeTier(player);
            if (tier.getLevel() < ToolTier.WOOD.getLevel()) {
                setDebugState("Tree: Requires Wooden Pickaxe to mine Cobbled Deepslate");
                return evaluateTree(mc, player, "wooden_pickaxe", 1);
            }
            return new MineBlockTask(mod, "cobbled deepslate", "deepslate cobbled_deepslate", count);
        }

        if (clean.equals("cobblestone")) {
            int current = InventoryManager.countItems(player, "cobblestone");
            if (current >= count) return null;
            ToolTier tier = MineBlockTask.getPlayerPickaxeTier(player);
            if (tier.getLevel() < ToolTier.WOOD.getLevel()) {
                setDebugState("Tree: Requires Wooden Pickaxe to mine Cobblestone");
                return evaluateTree(mc, player, "wooden_pickaxe", 1);
            }
            return new MineBlockTask(mod, "cobblestone", "stone cobblestone", count);
        }

        if (clean.equals("cobble") || clean.equals("stone") || clean.equals("deepslate")) {
            int current = InventoryManager.countItems(player, "cobble", "cobblestone", "stone", "deepslate", "cobbled_deepslate");
            if (current >= count) return null;
            ToolTier tier = MineBlockTask.getPlayerPickaxeTier(player);
            if (tier.getLevel() < ToolTier.WOOD.getLevel()) {
                setDebugState("Tree: Requires Wooden Pickaxe to mine Stone");
                return evaluateTree(mc, player, "wooden_pickaxe", 1);
            }
            return new MineBlockTask(mod, "cobblestone", "stone cobblestone deepslate cobbled_deepslate", count);
        }

        if (clean.equals("bed") || clean.endsWith("_bed")) {
            int current = InventoryManager.countItems(player, "bed");
            if (current >= count) return null;
            return new MineBlockTask(
                    mod, "bed",
                    "white_bed red_bed yellow_bed orange_bed light_blue_bed green_bed pink_bed gray_bed blue_bed",
                    count
            );
        }

        if (clean.equals("log") || clean.equals("wood") || clean.endsWith("_log") || clean.endsWith("_wood")) {
            int current = InventoryManager.countItems(player, "log", "wood");
            if (current >= count) return null;
            return new MineBlockTask(
                    mod, "wood logs",
                    "oak_log birch_log spruce_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log pale_oak_log",
                    count
            );
        }

        // 12. GENERIC FALLBACK FOR ANY MINABLE BLOCK
        String resolvedBlocks = resolveBlocksForItem(clean);
        MineBlockTask fallbackMine = new MineBlockTask(mod, targetItem, resolvedBlocks, count);
        ToolTier required = fallbackMine.getRequiredTier();
        ToolTier playerTier = MineBlockTask.getPlayerPickaxeTier(player);
        if (playerTier.getLevel() < required.getLevel()) {
            String requiredPick = switch (required) {
                case DIAMOND, NETHERITE -> "diamond_pickaxe";
                case IRON -> "iron_pickaxe";
                case STONE -> "stone_pickaxe";
                default -> "wooden_pickaxe";
            };
            setDebugState("Tree: Requires " + requiredPick + " to mine " + targetItem);
            return evaluateTree(mc, player, requiredPick, 1);
        }

        return fallbackMine;
    }

    private Task evaluateArmorPiece(Minecraft mc, LocalPlayer player, String armorPiece, String material, int materialCount) {
        if (hasEquippedOrInventory(player, armorPiece)) return null;

        int mat = InventoryManager.countItems(player, material);
        if (mat < materialCount) {
            setDebugState("Tree: Need " + (materialCount - mat) + " more " + material + " for " + armorPiece);
            return evaluateTree(mc, player, material, materialCount);
        }

        if (!ensureCraftingTable(mc, player)) {
            return evaluateTree(mc, player, "crafting_table", 1);
        }

        return new CraftInTableTask(armorPiece);
    }

    private Task evaluateCraftTool(Minecraft mc, LocalPlayer player, String toolName, String material, int matCount, int stickCount) {
        if (InventoryManager.countItems(player, toolName) > 0) return null;

        int mat = InventoryManager.countItems(player, material);
        if (mat < matCount) {
            setDebugState("Tree: Need " + (matCount - mat) + " more " + material + " for " + toolName);
            return evaluateTree(mc, player, material, matCount);
        }

        int sticks = InventoryManager.countItems(player, "stick");
        if (sticks < stickCount) {
            return evaluateTree(mc, player, "stick", stickCount);
        }

        if (!ensureCraftingTable(mc, player)) {
            return evaluateTree(mc, player, "crafting_table", 1);
        }

        return new CraftInTableTask(toolName);
    }

    private Task evaluateCraftWoodenTool(Minecraft mc, LocalPlayer player, String toolName, int plankCount, int stickCount) {
        if (InventoryManager.countItems(player, toolName) > 0) return null;

        int totalPlanks = InventoryManager.countItems(player, "log") * 4 + InventoryManager.countItems(player, "plank");
        int needed = plankCount + (stickCount * 2);
        if (totalPlanks < needed) {
            return evaluateTree(mc, player, "log", Math.max(2, needed / 4));
        }

        if (!ensureCraftingTable(mc, player)) {
            return evaluateTree(mc, player, "crafting_table", 1);
        }

        return new CraftInTableTask(toolName);
    }

    private boolean ensureCraftingTable(Minecraft mc, LocalPlayer player) {
        if (player == null) return false;
        if (player.containerMenu instanceof CraftingMenu) return true;
        return InventoryManager.countItems(player, "crafting_table") > 0 || isCraftingTableNearby(mc, player, 16);
    }

    private boolean isCraftingTableNearby(Minecraft mc, LocalPlayer player, int radius) {
        if (mc.level == null || player == null) return false;
        if (player.containerMenu instanceof CraftingMenu) return true;
        BlockPos center = player.blockPosition();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (mc.level.getBlockState(center.offset(x, y, z)).is(Blocks.CRAFTING_TABLE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isFurnaceNearby(Minecraft mc, LocalPlayer player, int radius) {
        if (mc.level == null || player == null) return false;
        if (player.containerMenu instanceof FurnaceMenu) return true;
        BlockPos center = player.blockPosition();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (mc.level.getBlockState(center.offset(x, y, z)).is(Blocks.FURNACE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String cleanKey(String key) {
        if (key == null) return "";
        String s = key.toLowerCase().replace(" ", "_").trim();
        return s.contains(":") ? s.substring(s.indexOf(':') + 1) : s;
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (treeSubTask != null) {
            treeSubTask.stop(interruptTask);
        }
    }

    @Override
    public boolean isFinished() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && isItemAcquired(mc, mc.player, item, targetCount);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetItemTask task) {
            return task.item.equalsIgnoreCase(this.item) && task.targetCount == this.targetCount;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Get Item Tree: " + targetCount + "x " + item;
    }

    private static String resolveBlocksForItem(String item) {
        String lower = item.toLowerCase().replace(" ", "_");
        if (lower.contains("log") || lower.equals("wood")) {
            return "oak_log birch_log spruce_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log pale_oak_log";
        }
        if (lower.contains("plank")) {
            return "oak_planks birch_planks spruce_planks jungle_planks acacia_planks dark_oak_planks mangrove_planks cherry_planks";
        }
        if (lower.contains("diamond")) {
            return "diamond_ore deepslate_diamond_ore";
        }
        if (lower.contains("iron")) {
            return "iron_ore deepslate_iron_ore raw_iron_block";
        }
        if (lower.contains("coal")) {
            return "coal_ore deepslate_coal_ore";
        }
        if (lower.contains("gold")) {
            return "gold_ore deepslate_gold_ore nether_gold_ore";
        }
        if (lower.contains("copper")) {
            return "copper_ore deepslate_copper_ore";
        }
        if (lower.contains("emerald")) {
            return "emerald_ore deepslate_emerald_ore";
        }
        if (lower.contains("lapis")) {
            return "lapis_ore deepslate_lapis_ore";
        }
        if (lower.contains("redstone")) {
            return "redstone_ore deepslate_redstone_ore";
        }
        if (lower.contains("stone") || lower.contains("cobble")) {
            return "stone cobblestone deepslate cobbled_deepslate";
        }
        if (lower.contains("sand")) {
            return "sand red_sand";
        }
        if (lower.contains("dirt")) {
            return "dirt grass_block";
        }
        return lower;
    }
}
