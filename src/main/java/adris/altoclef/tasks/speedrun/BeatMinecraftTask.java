package adris.altoclef.tasks.speedrun;

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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

public class BeatMinecraftTask extends Task {

    private final AltoClef mod;
    private Task currentSubTask;
    private SpeedrunPhase currentPhase = SpeedrunPhase.GATHER_WOOD;
    private long enteredEndTimestamp = 0;

    public enum SpeedrunPhase {
        GATHER_WOOD("Phase 1: Gathering Wood Logs"),
        CRAFT_BASIC_MATERIALS("Phase 2: Crafting Planks, Sticks & Crafting Table"),
        CRAFT_WOODEN_PICKAXE("Phase 3: Crafting Wooden Pickaxe"),
        RECOVER_CRAFTING_TABLE("Phase 3b: Recovering Crafting Table"),
        MINE_COBBLESTONE("Phase 4: Mining Cobblestone (Holding Wooden Pickaxe)"),
        CRAFT_STONE_TOOLS("Phase 5: Crafting Stone Pickaxe, Sword & Furnace"),
        MINE_FUEL("Phase 6: Mining Coal / Furnace Fuel"),
        MINE_IRON_ORE("Phase 7: Mining Iron Ore (Holding Stone Pickaxe)"),
        SMELT_IRON("Phase 8: Smelting Raw Iron in Furnace"),
        CRAFT_IRON_GEAR("Phase 9: Crafting Iron Pickaxe, Shield & Bucket"),
        MINE_DIAMONDS("Phase 10: Mining Diamonds (Holding Iron Pickaxe)"),
        CRAFT_DIAMOND_PICKAXE("Phase 11: Crafting Diamond Pickaxe"),
        ENTER_NETHER("Phase 12: Constructing Portal & Entering Nether"),
        GATHER_BLAZE_RODS("Phase 13: Nether Fortress & Blaze Rods"),
        GATHER_ENDER_PEARLS("Phase 14: Bartering & Ender Pearls"),
        LOCATE_STRONGHOLD("Phase 15: Locating Stronghold & End Portal"),
        WAIT_FOR_END_CHUNKS("Phase 16: Waiting For End Chunks To Load"),
        SLAY_DRAGON("Phase 17: Slaying The Ender Dragon");

        private final String description;

        SpeedrunPhase(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public BeatMinecraftTask(AltoClef mod) {
        this.mod = mod;
    }

    @Override
    protected void onStart() {
        setDebugState("Initializing Gamer Speedrun Task (Ordered Progression)...");
        currentPhase = SpeedrunPhase.GATHER_WOOD;
        currentSubTask = null;
        enteredEndTimestamp = 0;
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            setDebugState("Waiting for player to load into world...");
            return null;
        }

        if (mc.player.isDeadOrDying()) {
            if (currentSubTask != null) {
                currentSubTask.stop(null);
                currentSubTask = null;
            }
            setDebugState("Player died. Waiting for respawn...");
            return null;
        }

        // Determine phase FIRST every tick to allow immediate progression when goals/prerequisites are met
        SpeedrunPhase oldPhase = currentPhase;
        determinePhase(mc);
        setDebugState(currentPhase.getDescription());

        // In The End: wait for chunks to fully load before acting
        if (currentPhase == SpeedrunPhase.WAIT_FOR_END_CHUNKS) {
            if (enteredEndTimestamp == 0) {
                enteredEndTimestamp = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() - enteredEndTimestamp < 2500) {
                setDebugState("Waiting for End terrain & chunks to stabilize...");
                return null;
            }
            currentPhase = SpeedrunPhase.SLAY_DRAGON;
        }

        // If phase changed or current subtask finished or null, transition to new subtask
        if (currentPhase != oldPhase || currentSubTask == null || currentSubTask.isFinished()) {
            if (currentSubTask != null && !currentSubTask.isFinished()) {
                currentSubTask.stop(null);
            }
            currentSubTask = createSubTaskForPhase(currentPhase);
        }

        return currentSubTask;
    }

    private void determinePhase(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        ToolTier pickTier = MineBlockTask.getPlayerPickaxeTier(player);
        boolean hasPickaxe = pickTier.getLevel() >= ToolTier.WOOD.getLevel() || countItemInInventory(mc, "pickaxe") > 0;

        int logs = countItemInInventory(mc, "log");
        int planks = countItemInInventory(mc, "plank");
        int sticks = countItemInInventory(mc, "stick");
        int tables = countItemInInventory(mc, "crafting_table");
        int furnaces = countItemInInventory(mc, "furnace");
        int cobble = countItemInInventory(mc, "cobble", "cobbled_deepslate");
        int rawIron = countItemInInventory(mc, "raw_iron");
        int ironIngots = countItemInInventory(mc, "iron_ingot");
        int diamonds = countItemInInventory(mc, "diamond");
        int coal = countItemInInventory(mc, "coal", "charcoal");
        int obsidian = countItemInInventory(mc, "obsidian");
        boolean hasShield = countItemInInventory(mc, "shield") > 0 || isShieldEquipped(player);
        boolean hasBucket = countItemInInventory(mc, "bucket") > 0;

        int totalWoodPlanks = logs * 4 + planks;
        boolean hasTable = tables > 0 || isCraftingTableNearby(mc, player, 16);
        boolean canMakeTable = hasTable || totalWoodPlanks >= 4;
        boolean hasFurnace = furnaces > 0 || isFurnaceNearby(mc, player, 16);

        // Dimension checks
        String dimension = mc.level != null ? mc.level.dimension().toString().toLowerCase() : "overworld";

        if (dimension.contains("the_end")) {
            if (enteredEndTimestamp == 0 || System.currentTimeMillis() - enteredEndTimestamp < 2500) {
                currentPhase = SpeedrunPhase.WAIT_FOR_END_CHUNKS;
            } else {
                currentPhase = SpeedrunPhase.SLAY_DRAGON;
            }
        } else if (dimension.contains("nether")) {
            int blazeRods = countItemInInventory(mc, "blaze_rod");
            int pearls = countItemInInventory(mc, "ender_pearl");
            if (blazeRods < 7) {
                currentPhase = SpeedrunPhase.GATHER_BLAZE_RODS;
            } else if (pearls < 12) {
                currentPhase = SpeedrunPhase.GATHER_ENDER_PEARLS;
            } else {
                currentPhase = SpeedrunPhase.LOCATE_STRONGHOLD;
            }
        } else {
            // Overworld Strict Progression with Prerequisite Fallback: NO DEAD ENDS!

            // Fallback 1: Wood Stockpile
            if (pickTier.getLevel() < ToolTier.IRON.getLevel() && logs < 4 && totalWoodPlanks < 16) {
                currentPhase = SpeedrunPhase.GATHER_WOOD;
            }
            // Fallback 2: Basic Crafting Table & Sticks Availability
            else if (!canMakeTable) {
                currentPhase = SpeedrunPhase.GATHER_WOOD;
            }
            else if (sticks < 2 && totalWoodPlanks < 1) {
                currentPhase = SpeedrunPhase.GATHER_WOOD;
            }
            // Step 1: No Pickaxe (or wooden pickaxe broken)
            else if (!hasPickaxe) {
                if (totalWoodPlanks < 8) {
                    currentPhase = SpeedrunPhase.GATHER_WOOD;
                } else {
                    currentPhase = SpeedrunPhase.CRAFT_WOODEN_PICKAXE;
                }
            }
            // Step 2: Portable Crafting Table Recovery (pick up nearby table so bot carries it)
            else if (tables < 1 && isCraftingTableNearby(mc, player, 10)) {
                currentPhase = SpeedrunPhase.RECOVER_CRAFTING_TABLE;
            }
            // Step 2: Cobblestone (Must have Wooden Pickaxe!)
            else if (cobble < 14 && pickTier.getLevel() < ToolTier.STONE.getLevel()) {
                currentPhase = SpeedrunPhase.MINE_COBBLESTONE;
            }
            // Step 3: Craft Stone Tools & Furnace
            else if (pickTier.getLevel() < ToolTier.STONE.getLevel()) {
                currentPhase = SpeedrunPhase.CRAFT_STONE_TOOLS;
            }
            // Step 4: Furnace (Requires 8 Cobblestone)
            else if (!hasFurnace && ironIngots < 3) {
                if (cobble < 8) {
                    currentPhase = SpeedrunPhase.MINE_COBBLESTONE;
                } else {
                    currentPhase = SpeedrunPhase.CRAFT_STONE_TOOLS;
                }
            }
            // Step 5: Mine Fuel (Coal/Wood) if needed for furnace
            else if (coal < 4 && totalWoodPlanks < 4 && rawIron > 0 && ironIngots < 3) {
                currentPhase = SpeedrunPhase.MINE_FUEL;
            }
            // Step 6: Mine Iron Ore (Requires Stone Pickaxe!)
            else if ((rawIron + ironIngots < 15) && pickTier.getLevel() < ToolTier.IRON.getLevel()) {
                currentPhase = SpeedrunPhase.MINE_IRON_ORE;
            }
            // Step 7: Smelt Raw Iron in Furnace
            else if (ironIngots < 3 && rawIron >= 3) {
                currentPhase = SpeedrunPhase.SMELT_IRON;
            }
            // Step 8: Craft Iron Gear (Iron Pickaxe, Shield, Bucket)
            else if (pickTier.getLevel() < ToolTier.IRON.getLevel() || !hasShield || !hasBucket) {
                if (totalWoodPlanks < 6 && planks < 6) {
                    currentPhase = SpeedrunPhase.GATHER_WOOD;
                } else if (ironIngots < 3 && rawIron >= 3) {
                    currentPhase = SpeedrunPhase.SMELT_IRON;
                } else if (ironIngots < 3 && (rawIron + ironIngots < 3)) {
                    currentPhase = SpeedrunPhase.MINE_IRON_ORE;
                } else {
                    currentPhase = SpeedrunPhase.CRAFT_IRON_GEAR;
                }
            }
            // Step 9: Mine Diamonds (Requires Iron Pickaxe!)
            else if (diamonds < 3 && pickTier.getLevel() < ToolTier.DIAMOND.getLevel()) {
                currentPhase = SpeedrunPhase.MINE_DIAMONDS;
            }
            // Step 10: Craft Diamond Pickaxe
            else if (pickTier.getLevel() < ToolTier.DIAMOND.getLevel()) {
                if (sticks < 2 && totalWoodPlanks < 1) {
                    currentPhase = SpeedrunPhase.GATHER_WOOD;
                } else {
                    currentPhase = SpeedrunPhase.CRAFT_DIAMOND_PICKAXE;
                }
            }
            // Step 11: Enter Nether
            else {
                currentPhase = SpeedrunPhase.ENTER_NETHER;
            }
        }
    }

    private Task createSubTaskForPhase(SpeedrunPhase phase) {
        return switch (phase) {
            case GATHER_WOOD -> new MineBlockTask(
                    mod, "wood logs",
                    "oak_log birch_log spruce_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log pale_oak_log",
                    12
            );
            case CRAFT_BASIC_MATERIALS, CRAFT_WOODEN_PICKAXE -> new CraftInTableTask("wooden_pickaxe");
            case RECOVER_CRAFTING_TABLE -> new MineBlockTask(mod, "crafting table", "crafting_table", 1);
            case MINE_COBBLESTONE -> new MineBlockTask(
                    mod, "cobblestone",
                    "stone cobblestone deepslate cobbled_deepslate",
                    14
            );
            case CRAFT_STONE_TOOLS -> {
                LocalPlayer player = Minecraft.getInstance().player;
                if (MineBlockTask.getPlayerPickaxeTier(player).getLevel() < ToolTier.STONE.getLevel()) {
                    yield new CraftInTableTask("stone_pickaxe");
                }
                if (countItemInInventory(Minecraft.getInstance(), "furnace") == 0 && !isFurnaceNearby(Minecraft.getInstance(), player, 16)) {
                    yield new CraftInTableTask("furnace");
                }
                if (countItemInInventory(Minecraft.getInstance(), "stone_sword") == 0) {
                    yield new CraftInTableTask("stone_sword");
                }
                yield new CraftInTableTask("furnace");
            }
            case MINE_FUEL -> new MineBlockTask(
                    mod, "coal",
                    "coal_ore deepslate_coal_ore",
                    4
            );
            case MINE_IRON_ORE -> new MineBlockTask(
                    mod, "iron ore",
                    "iron_ore deepslate_iron_ore raw_iron_block",
                    15
            );
            case SMELT_IRON -> new SmeltInFurnaceTask("raw_iron", 15);
            case CRAFT_IRON_GEAR -> {
                LocalPlayer player = Minecraft.getInstance().player;
                if (MineBlockTask.getPlayerPickaxeTier(player).getLevel() < ToolTier.IRON.getLevel()) {
                    yield new CraftInTableTask("iron_pickaxe");
                }
                if (!isShieldEquipped(player) && countItemInInventory(Minecraft.getInstance(), "shield") == 0) {
                    yield new CraftInTableTask("shield");
                }
                yield new CraftInTableTask("bucket");
            }
            case MINE_DIAMONDS -> new MineBlockTask(
                    mod, "diamond ore",
                    "diamond_ore deepslate_diamond_ore",
                    3
            );
            case CRAFT_DIAMOND_PICKAXE -> new CraftInTableTask("diamond_pickaxe");
            case ENTER_NETHER -> new MineBlockTask(
                    mod, "obsidian",
                    "obsidian",
                    10
            );
            case GATHER_BLAZE_RODS -> new MineBlockTask(
                    mod, "blaze rod / spawner",
                    "spawner nether_bricks",
                    7
            );
            case GATHER_ENDER_PEARLS -> new MineBlockTask(
                    mod, "gold ore (for bartering)",
                    "nether_gold_ore gold_block",
                    32
            );
            case LOCATE_STRONGHOLD -> new MineBlockTask(
                    mod, "stronghold stone",
                    "end_portal_frame stone_bricks cracked_stone_bricks mossy_stone_bricks",
                    1
            );
            case WAIT_FOR_END_CHUNKS -> null;
            case SLAY_DRAGON -> new MineBlockTask(
                    mod, "end stone / pillars",
                    "end_stone obsidian bedrock",
                    64
            );
        };
    }

    private boolean isShieldEquipped(LocalPlayer player) {
        if (player == null) return false;
        ItemStack offhand = player.getOffhandItem();
        return offhand != null && !offhand.isEmpty() &&
                (offhand.is(net.minecraft.world.item.Items.SHIELD) || InventoryManager.getItemName(offhand).contains("shield"));
    }

    private int countItemInInventory(Minecraft mc, String... keywords) {
        if (mc.player == null) return 0;
        int count = InventoryManager.countItems(mc.player, keywords);
        if (mc.player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu menu) {
            ItemStack carried = menu.getCarried();
            if (!carried.isEmpty()) {
                String name = InventoryManager.getItemName(carried);
                for (String kw : keywords) {
                    if (CraftInTableTask.matchesKeyword(name, kw)) {
                        count += carried.getCount();
                        break;
                    }
                }
            }
            for (int s = 1; s <= 9; s++) {
                ItemStack stack = menu.getSlot(s).getItem();
                if (!stack.isEmpty()) {
                    String name = InventoryManager.getItemName(stack);
                    for (String kw : keywords) {
                        if (CraftInTableTask.matchesKeyword(name, kw)) {
                            count += stack.getCount();
                            break;
                        }
                    }
                }
            }
        }
        return count;
    }

    private boolean isCraftingTableNearby(Minecraft mc, LocalPlayer player, int radius) {
        if (mc.level == null || player == null) return false;
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

    @Override
    protected void onStop(Task interruptTask) {
        if (stopped() && currentSubTask != null) {
            currentSubTask.stop(interruptTask);
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BeatMinecraftTask;
    }

    @Override
    protected String toDebugString() {
        return "Beat Minecraft (Ordered Speedrun)";
    }
}
