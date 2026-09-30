package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.tasks.construction.MineBlockTask;
import adris.altoclef.tasks.construction.MineBlockTask.ToolTier;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

public class MarvionBeatMinecraftTask extends Task {

    private final AltoClef mod;
    private final BeatMinecraftConfig config = new BeatMinecraftConfig();
    private SpeedrunPhase currentPhase = SpeedrunPhase.START_WOOD;
    private Task currentSubTask;
    private long enteredEndTimestamp = 0;
    private int originalRenderDistance = 12;
    private double originalEntityScaling = 1.0;
    private int bedDetonationCooldown = 0;
    private boolean isFinished = false;
    private final Set<BlockPos> lootedChests = new HashSet<>();
    private int clutterTimer = 0;

    public enum SpeedrunPhase {
        START_WOOD("Marvion Phase 1: Fast Wood Gathering"),
        CRAFT_BASIC_MATERIALS("Marvion Phase 2: Rapid 2x2 Crafting (Planks/Sticks/Table)"),
        CRAFT_WOODEN_PICKAXE("Marvion Phase 3: Crafting Wooden Pickaxe"),
        RECOVER_CRAFTING_TABLE("Marvion: Retrieving Nearby Crafting Table"),
        MINE_COBBLESTONE("Marvion Phase 4: Mining Cobblestone (Holding Wooden Pickaxe)"),
        CRAFT_STONE_TOOLS("Marvion Phase 5: Crafting Stone Pickaxe, Sword & Furnace"),
        MINE_FUEL("Marvion Phase 6: Mining Furnace Fuel"),
        MINE_IRON_ORE("Marvion Phase 7: Mining Iron Ore (Holding Stone Pickaxe)"),
        SMELT_IRON("Marvion Phase 8: Smelting Raw Iron in Furnace"),
        CRAFT_IRON_GEAR("Marvion Phase 9: Crafting Iron Pickaxe, Shield & Bucket"),
        CRAFT_GOLDEN_HELMET("Marvion Phase 10: Crafting Golden Helmet (Piglin Pacification)"),
        MINE_DIAMONDS("Marvion Phase 11: Mining Diamonds (Holding Iron Pickaxe)"),
        CRAFT_DIAMOND_PICKAXE("Marvion Phase 12: Crafting Diamond Pickaxe"),
        COLLECT_BEDS("Marvion Phase 13: Collecting Beds (For Dragon One-Cycle)"),
        ENTER_NETHER("Marvion Phase 14: Portal Building & Entering Nether"),
        NETHER_FORTRESS_AND_BLAZES("Marvion Phase 15: Fortress Hunting & Blaze Rods (32 RD Boost)"),
        PIGLIN_BARTER_PEARLS("Marvion Phase 16: Piglin Bartering & Ender Pearls"),
        LOCATE_STRONGHOLD("Marvion Phase 17: Eye Crafting & Stronghold Infiltration"),
        WAIT_FOR_END_CHUNKS("Marvion Phase 18: Stabilizing End Platform & Chunks"),
        SLAY_DRAGON_BEDS("Marvion Phase 19: Bed-Explosion Dragon One-Cycle & Anti-Enderman"),
        VICTORY("Marvion Complete: Beating Minecraft (Marvion Optimized)");

        private final String description;

        SpeedrunPhase(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public MarvionBeatMinecraftTask(AltoClef mod) {
        this.mod = mod;
    }

    @Override
    protected void onStart() {
        setDebugState("Initializing Marvion Speedrun Engine (Complete Error-Safe Suite)...");
        currentPhase = SpeedrunPhase.START_WOOD;
        currentSubTask = null;
        isFinished = false;
        enteredEndTimestamp = 0;
        lootedChests.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            try {
                originalRenderDistance = mc.options.renderDistance().get();
                originalEntityScaling = mc.options.entityDistanceScaling().get();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            setDebugState("Waiting for player and world to load...");
            return null;
        }

        // Win check
        if (mc.gui != null && mc.gui.screen() instanceof WinScreen) {
            setDebugState("Game Finished! Win Screen detected.");
            isFinished = true;
            restoreOriginalSettings(mc);
            return null;
        }

        // Marvion Error-Safety Routine 1: Structure Fast-Looting (Ruined Portals & Desert Temples)
        scanAndLootChests(mc, player);

        // Marvion Error-Safety Routine 2: Inventory Clutter Cleaning
        if (clutterTimer-- <= 0) {
            clutterTimer = 40; // Every 2 seconds
            pruneInventoryClutter(mc, player);
        }

        SpeedrunPhase oldPhase = currentPhase;
        boolean isCraftingActive = (currentSubTask instanceof CraftInTableTask craftTask && !craftTask.isFinished());
        boolean isSmeltingActive = (currentSubTask instanceof SmeltInFurnaceTask smeltTask && !smeltTask.isFinished());
        if (!isCraftingActive && !isSmeltingActive) {
            determinePhase(mc);
        }
        setDebugState(currentPhase.getDescription());

        // Apply Marvion Dynamic Render Distance & Entity Distance Manipulations
        applyPerformanceOptimizations(mc);

        // Special Phase Handling: End Chunks Stabilization
        if (currentPhase == SpeedrunPhase.WAIT_FOR_END_CHUNKS) {
            if (enteredEndTimestamp == 0) {
                enteredEndTimestamp = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() - enteredEndTimestamp < 2500) {
                setDebugState("Stabilizing End platform chunks (preventing void fall)...");
                return null;
            }
            currentPhase = SpeedrunPhase.SLAY_DRAGON_BEDS;
        }

        // Special Phase Handling: Dragon Fight with Angry Enderman & Breath Avoidance
        if (currentPhase == SpeedrunPhase.SLAY_DRAGON_BEDS) {
            // Check dragon breath avoidance first
            if (handleDragonBreathAvoidance(mc, player)) {
                return null;
            }
            // Check angry enderman priority next
            Task combatTask = handleDragonFight(mc);
            if (combatTask != null) {
                return combatTask;
            }
        }

        // Check subtask completion or phase shift
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

        String dimension = mc.level != null ? mc.level.dimension().toString().toLowerCase() : "overworld";

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
        int blazeRods = countItemInInventory(mc, "blaze_rod");
        int pearls = countItemInInventory(mc, "ender_pearl");
        int beds = countItemInInventory(mc, "bed");
        boolean hasShield = countItemInInventory(mc, "shield") > 0 || isShieldEquipped(player);
        boolean hasBucket = countItemInInventory(mc, "bucket") > 0;
        boolean hasGoldenHelmet = countItemInInventory(mc, "golden_helmet") > 0 || isGoldenHelmetEquipped(player);

        int totalWoodPlanks = logs * 4 + planks;
        boolean hasTable = tables > 0 || isCraftingTableNearby(mc, player, 16);
        boolean canMakeTable = hasTable || totalWoodPlanks >= 4;
        boolean hasFurnace = furnaces > 0 || isFurnaceNearby(mc, player, 16);

        if (dimension.contains("the_end")) {
            if (enteredEndTimestamp == 0 || System.currentTimeMillis() - enteredEndTimestamp < 2500) {
                currentPhase = SpeedrunPhase.WAIT_FOR_END_CHUNKS;
            } else {
                currentPhase = SpeedrunPhase.SLAY_DRAGON_BEDS;
            }
        } else if (dimension.contains("nether")) {
            if (blazeRods < 7) {
                currentPhase = SpeedrunPhase.NETHER_FORTRESS_AND_BLAZES;
            } else if (pearls < 12) {
                currentPhase = SpeedrunPhase.PIGLIN_BARTER_PEARLS;
            } else {
                currentPhase = SpeedrunPhase.LOCATE_STRONGHOLD;
            }
        } else {
            // Overworld Strict Ordered Progression with Prerequisite Fallback: NO DEAD ENDS!

            // Prerequisite Fallbacks:
            // Fallback 1: No Pickaxe and no wood to make one
            if (!hasPickaxe && totalWoodPlanks < 4) {
                currentPhase = SpeedrunPhase.START_WOOD;
            }
            // Fallback 2: No Crafting Table and cannot make one
            else if (!canMakeTable) {
                currentPhase = SpeedrunPhase.START_WOOD;
            }
            // Fallback 3: No sticks and no wood when needing to craft tools
            else if (sticks < 2 && totalWoodPlanks < 1 && (pickTier.getLevel() < ToolTier.STONE.getLevel() || rawIron >= 3 || diamonds >= 3)) {
                currentPhase = SpeedrunPhase.START_WOOD;
            }
            // Step 1: No Pickaxe at all (or tool broken)
            else if (!hasPickaxe) {
                if (totalWoodPlanks < 8) {
                    currentPhase = SpeedrunPhase.START_WOOD;
                } else if (planks < 3 || sticks < 2 || !hasTable) {
                    currentPhase = SpeedrunPhase.CRAFT_BASIC_MATERIALS;
                } else {
                    currentPhase = SpeedrunPhase.CRAFT_WOODEN_PICKAXE;
                }
            }
            // Step 2: Portable Crafting Table Recovery (pick up nearby table so bot carries it)
            else if (tables < 1 && isCraftingTableNearby(mc, player, 10)) {
                currentPhase = SpeedrunPhase.RECOVER_CRAFTING_TABLE;
            }
            // Step 3: Cobblestone (Must have Wooden Pickaxe!)
            else if (cobble < 14 && pickTier.getLevel() < ToolTier.STONE.getLevel()) {
                currentPhase = SpeedrunPhase.MINE_COBBLESTONE;
            }
            // Step 4: Craft Stone Pickaxe
            else if (pickTier.getLevel() < ToolTier.STONE.getLevel()) {
                currentPhase = SpeedrunPhase.CRAFT_STONE_TOOLS;
            }
            // Step 5: Furnace (Requires 8 Cobblestone)
            else if (!hasFurnace && ironIngots < 3) {
                if (cobble < 8) {
                    currentPhase = SpeedrunPhase.MINE_COBBLESTONE;
                } else {
                    currentPhase = SpeedrunPhase.CRAFT_STONE_TOOLS;
                }
            }
            // Step 6: Mine Fuel (Coal/Wood) if needed for furnace
            else if (coal < 4 && totalWoodPlanks < 4 && rawIron > 0 && ironIngots < 3) {
                currentPhase = SpeedrunPhase.MINE_FUEL;
            }
            // Step 7: Mine Iron Ore (Requires Stone Pickaxe!)
            else if ((rawIron + ironIngots < 15) && pickTier.getLevel() < ToolTier.IRON.getLevel()) {
                currentPhase = SpeedrunPhase.MINE_IRON_ORE;
            }
            // Step 8: Smelt Raw Iron in Furnace
            else if (ironIngots < 3 && rawIron >= 3) {
                currentPhase = SpeedrunPhase.SMELT_IRON;
            }
            // Step 9: Craft Iron Gear (Iron Pickaxe, Shield, Bucket)
            else if (pickTier.getLevel() < ToolTier.IRON.getLevel() || !hasShield || !hasBucket) {
                if (totalWoodPlanks < 6 && planks < 6) {
                    currentPhase = SpeedrunPhase.START_WOOD;
                } else if (ironIngots < 3 && rawIron >= 3) {
                    currentPhase = SpeedrunPhase.SMELT_IRON;
                } else if (ironIngots < 3 && (rawIron + ironIngots < 3)) {
                    currentPhase = SpeedrunPhase.MINE_IRON_ORE;
                } else {
                    currentPhase = SpeedrunPhase.CRAFT_IRON_GEAR;
                }
            }
            // Step 10: Golden Helmet for Nether Piglin Pacification
            else if (config.goldenHelmet && !hasGoldenHelmet && countItemInInventory(mc, "gold_ingot") >= 5) {
                currentPhase = SpeedrunPhase.CRAFT_GOLDEN_HELMET;
            }
            // Step 11: Mine Diamonds (Must have Iron Pickaxe!)
            else if (diamonds < 3 && pickTier.getLevel() < ToolTier.DIAMOND.getLevel()) {
                currentPhase = SpeedrunPhase.MINE_DIAMONDS;
            }
            // Step 12: Craft Diamond Pickaxe
            else if (pickTier.getLevel() < ToolTier.DIAMOND.getLevel()) {
                if (sticks < 2 && totalWoodPlanks < 1) {
                    currentPhase = SpeedrunPhase.START_WOOD;
                } else {
                    currentPhase = SpeedrunPhase.CRAFT_DIAMOND_PICKAXE;
                }
            }
            // Step 13: Collect Beds for Dragon 1-Cycle
            else if (config.dragonBedStrats && beds < config.requiredBeds && obsidian >= 10) {
                currentPhase = SpeedrunPhase.COLLECT_BEDS;
            }
            // Step 14: Enter Nether
            else if (obsidian < 10 && !hasBucket) {
                currentPhase = SpeedrunPhase.ENTER_NETHER;
            } else if (pearls >= 12 && blazeRods >= 7) {
                currentPhase = SpeedrunPhase.LOCATE_STRONGHOLD;
            } else {
                currentPhase = SpeedrunPhase.ENTER_NETHER;
            }
        }
    }

    private void applyPerformanceOptimizations(Minecraft mc) {
        if (!config.renderDistanceManipulation || mc.options == null) return;

        try {
            switch (currentPhase) {
                case START_WOOD, MINE_COBBLESTONE, MINE_IRON_ORE, MINE_DIAMONDS, SMELT_IRON -> {
                    // Minimize render distance during underground mining/gathering for max tick speed
                    if (mc.options.renderDistance().get() != 2) {
                        mc.options.renderDistance().set(2);
                        mc.options.entityDistanceScaling().set(0.5);
                    }
                }
                case NETHER_FORTRESS_AND_BLAZES -> {
                    // Maximize view distance and entity distance to spot blaze spawners & fortresses
                    if (mc.options.renderDistance().get() != 32) {
                        mc.options.renderDistance().set(32);
                        mc.options.entityDistanceScaling().set(5.0);
                    }
                }
                default -> {
                    // Balanced default
                    if (mc.options.renderDistance().get() != 12) {
                        mc.options.renderDistance().set(12);
                        mc.options.entityDistanceScaling().set(1.0);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void scanAndLootChests(Minecraft mc, LocalPlayer player) {
        if (!config.searchRuinedPortals && !config.searchDesertTemples) return;
        if (mc.level == null) return;

        // If chest container is currently open, extract valuable loot!
        if (player.containerMenu instanceof ChestMenu menu) {
            int containerSize = menu.getContainer().getContainerSize();
            for (int i = 0; i < containerSize; i++) {
                ItemStack stack = menu.getSlot(i).getItem();
                if (!stack.isEmpty()) {
                    String name = InventoryManager.getItemName(stack);
                    if (isValuableSpeedrunItem(name)) {
                        mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, player);
                    }
                }
            }
            player.closeContainer();
            return;
        }

        // Search for nearby unopened chests
        BlockPos pPos = player.blockPosition();
        for (int x = -10; x <= 10; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -10; z <= 10; z++) {
                    BlockPos pos = pPos.offset(x, y, z);
                    if (mc.level.getBlockState(pos).is(Blocks.CHEST) && !lootedChests.contains(pos)) {
                        double distSq = player.distanceToSqr(Vec3.atCenterOf(pos));
                        if (distSq < 16.0) {
                            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
                            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                            lootedChests.add(pos);
                            return;
                        }
                    }
                }
            }
        }
    }

    private boolean isValuableSpeedrunItem(String name) {
        return name.contains("diamond") || name.contains("iron_ingot") || name.contains("golden_apple") ||
                name.contains("obsidian") || name.contains("flint_and_steel") || name.contains("gold_ingot") ||
                name.contains("golden_helmet") || name.contains("fire_charge") || name.contains("ender_pearl");
    }

    private void pruneInventoryClutter(Minecraft mc, LocalPlayer player) {
        if (!config.cleanClutter || player.inventoryMenu == null) return;
        if (!player.inventoryMenu.getCarried().isEmpty()) return;

        ToolTier tier = MineBlockTask.getPlayerPickaxeTier(player);

        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = player.inventoryMenu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            String name = InventoryManager.getItemName(stack);

            boolean shouldDiscard = false;

            // Discard wooden pickaxe if we have iron or diamond
            if (tier.getLevel() >= ToolTier.IRON.getLevel() && name.contains("wooden_pickaxe")) {
                shouldDiscard = true;
            }
            // Discard stone tools if we have diamond pickaxe
            if (tier.getLevel() >= ToolTier.DIAMOND.getLevel() && (name.contains("stone_pickaxe") || name.contains("stone_sword"))) {
                shouldDiscard = true;
            }
            // Discard excess lighters (> 1)
            if (name.contains("flint_and_steel") && countItemInInventory(mc, "flint_and_steel") > 1) {
                shouldDiscard = true;
            }
            // Discard poisonous potatoes
            if (name.contains("poisonous_potato")) {
                shouldDiscard = true;
            }

            if (shouldDiscard) {
                mc.gameMode.handleContainerInput(0, i, 1, ContainerInput.THROW, player);
                break;
            }
        }
    }

    private boolean handleDragonBreathAvoidance(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return false;
        for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof AreaEffectCloud cloud && cloud.isAlive()) {
                double dist = player.distanceTo(cloud);
                if (dist < cloud.getRadius() + 2.5) {
                    setDebugState("DODGING Dragon's Breath cloud!");
                    Vec3 away = player.position().subtract(cloud.position()).normalize().scale(5.0);
                    BlockPos escapePos = BlockPos.containing(player.position().add(away));
                    try {
                        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                        if (baritone != null) {
                            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(escapePos, 1));
                        }
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private Task handleDragonFight(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;

        // 1. Angry Enderman elimination priority in The End
        if (config.eliminateAngryEndermen) {
            for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof Enderman enderman && enderman.isAlive()) {
                    if (enderman.isCreepy() || enderman.hasBeenStaredAt()) {
                        double dist = mc.player.distanceTo(enderman);
                        if (dist < 6.0) {
                            setDebugState("Eliminating angry Enderman threatening dragon fight!");
                            Vec3 target = enderman.getEyePosition();
                            Vec3 playerEye = mc.player.getEyePosition();
                            double dx = target.x - playerEye.x;
                            double dy = target.y - playerEye.y;
                            double dz = target.z - playerEye.z;
                            double distXZ = Math.sqrt(dx * dx + dz * dz);
                            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                            float pitch = (float) Math.toDegrees(Math.atan2(-dy, distXZ));
                            mc.player.setYRot(yaw);
                            mc.player.setXRot(pitch);

                            if (dist < 3.8 && mc.player.getAttackStrengthScale(0.0f) >= 0.95f) {
                                if (mc.gameMode != null) {
                                    mc.gameMode.attack(mc.player, enderman);
                                    mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                                }
                            }
                            return null;
                        }
                    }
                }
            }
        }

        // 2. Dragon Bed Strat & Death Animation
        EnderDragon dragon = null;
        for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof EnderDragon ed && ed.isAlive()) {
                dragon = ed;
                break;
            }
        }

        if (dragon != null) {
            if (dragon.getHealth() <= 0.0f || dragon.dragonDeathTime > 0) {
                setDebugState("Dragon Defeated! Victory looking-up animation.");
                mc.player.setXRot(-85.0f);
                return null;
            }

            if (config.dragonBedStrats && dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.LANDING) {
                if (bedDetonationCooldown-- <= 0) {
                    BlockPos headPos = dragon.blockPosition().above(1);
                    double distToHead = mc.player.distanceToSqr(Vec3.atCenterOf(headPos));
                    if (distToHead < 25.0) {
                        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(headPos), Direction.UP, headPos, false);
                        if (mc.gameMode != null) {
                            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                        }
                        bedDetonationCooldown = 15;
                        setDebugState("Detonating bed against perching dragon head!");
                    }
                }
            }
        }

        return null;
    }

    private Task createSubTaskForPhase(SpeedrunPhase phase) {
        return switch (phase) {
            case START_WOOD -> new MineBlockTask(
                    mod, "wood logs",
                    "oak_log birch_log spruce_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log pale_oak_log",
                    12
            );
            case CRAFT_BASIC_MATERIALS -> null; // Handled directly in tick via InventoryManager auto-craft
            case CRAFT_WOODEN_PICKAXE -> new CraftInTableTask("wooden_pickaxe");
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
            case CRAFT_GOLDEN_HELMET -> new CraftInTableTask("golden_helmet");
            case MINE_DIAMONDS -> new MineBlockTask(
                    mod, "diamond ore",
                    "diamond_ore deepslate_diamond_ore",
                    3
            );
            case CRAFT_DIAMOND_PICKAXE -> new CraftInTableTask("diamond_pickaxe");
            case COLLECT_BEDS -> new MineBlockTask(
                    mod, "beds",
                    "white_bed orange_bed magenta_bed light_blue_bed yellow_bed lime_bed pink_bed gray_bed light_gray_bed cyan_bed purple_bed blue_bed brown_bed green_bed red_bed black_bed",
                    config.requiredBeds
            );
            case ENTER_NETHER -> new MineBlockTask(
                    mod, "obsidian",
                    "obsidian",
                    10
            );
            case NETHER_FORTRESS_AND_BLAZES -> new MineBlockTask(
                    mod, "blaze spawners and nether bricks",
                    "spawner nether_bricks",
                    7
            );
            case PIGLIN_BARTER_PEARLS -> new MineBlockTask(
                    mod, "gold ore for bartering",
                    "nether_gold_ore gold_block",
                    32
            );
            case LOCATE_STRONGHOLD -> new MineBlockTask(
                    mod, "stronghold portal frame and stone bricks",
                    "end_portal_frame stone_bricks",
                    1
            );
            case WAIT_FOR_END_CHUNKS -> null;
            case SLAY_DRAGON_BEDS -> new MineBlockTask(
                    mod, "bedrock and obsidian pillars",
                    "bedrock obsidian",
                    1
            );
            case VICTORY -> null;
        };
    }

    private boolean isShieldEquipped(LocalPlayer player) {
        if (player == null) return false;
        ItemStack offhand = player.getOffhandItem();
        return offhand != null && !offhand.isEmpty() &&
                (offhand.is(Items.SHIELD) || InventoryManager.getItemName(offhand).contains("shield"));
    }

    private boolean isGoldenHelmetEquipped(LocalPlayer player) {
        if (player == null) return false;
        ItemStack helm = player.inventoryMenu.getSlot(InventoryMenu.ARMOR_SLOT_START).getItem();
        return !helm.isEmpty() &&
                (helm.is(Items.GOLDEN_HELMET) || InventoryManager.getItemName(helm).contains("golden_helmet"));
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

    private void restoreOriginalSettings(Minecraft mc) {
        if (config.renderDistanceManipulation && mc.options != null) {
            try {
                mc.options.renderDistance().set(originalRenderDistance);
                mc.options.entityDistanceScaling().set(originalEntityScaling);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (stopped()) {
            if (currentSubTask != null) {
                currentSubTask.stop(interruptTask);
            }
            Minecraft mc = Minecraft.getInstance();
            restoreOriginalSettings(mc);
        }
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

    @Override
    public boolean isFinished() {
        return isFinished;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof MarvionBeatMinecraftTask;
    }

    @Override
    protected String toDebugString() {
        return "Marvion Speedrun (Complete Error-Safe Suite)";
    }
}
