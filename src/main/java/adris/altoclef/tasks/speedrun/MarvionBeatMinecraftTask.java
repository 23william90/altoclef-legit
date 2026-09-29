package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.MineBlockTask;
import adris.altoclef.tasks.construction.MineBlockTask.ToolTier;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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

    public enum SpeedrunPhase {
        START_WOOD("Marvion Phase 1: Fast Wood Gathering"),
        CRAFT_BASIC_MATERIALS("Marvion Phase 2: Rapid 2x2 Crafting (Planks/Sticks/Table)"),
        CRAFT_WOODEN_PICKAXE("Marvion Phase 3: Crafting Wooden Pickaxe"),
        MINE_COBBLESTONE("Marvion Phase 4: Mining Cobblestone (Holding Wooden Pickaxe)"),
        CRAFT_STONE_TOOLS("Marvion Phase 5: Crafting Stone Pickaxe, Sword & Furnace"),
        MINE_FUEL("Marvion Phase 6: Mining Furnace Fuel"),
        MINE_IRON_ORE("Marvion Phase 7: Mining Iron Ore (Holding Stone Pickaxe)"),
        SMELT_IRON("Marvion Phase 8: Smelting Raw Iron in Furnace"),
        CRAFT_IRON_GEAR("Marvion Phase 9: Crafting Iron Pickaxe, Shield & Bucket"),
        MINE_DIAMONDS("Marvion Phase 10: Mining Diamonds (Holding Iron Pickaxe)"),
        CRAFT_DIAMOND_PICKAXE("Marvion Phase 11: Crafting Diamond Pickaxe"),
        PRUNE_INVENTORY("Marvion Phase 12: Pruning Inventory Clutter"),
        ENTER_NETHER("Marvion Phase 13: Portal Building & Entering Nether"),
        NETHER_FORTRESS_AND_BLAZES("Marvion Phase 14: Fortress Hunting & Blaze Rods (32 RD Boost)"),
        PIGLIN_BARTER_PEARLS("Marvion Phase 15: Piglin Bartering & Ender Pearls"),
        LOCATE_STRONGHOLD("Marvion Phase 16: Eye Crafting & Stronghold Infiltration"),
        WAIT_FOR_END_CHUNKS("Marvion Phase 17: Stabilizing End Platform & Chunks"),
        SLAY_DRAGON_BEDS("Marvion Phase 18: Bed-Explosion Dragon One-Cycle & Anti-Enderman"),
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
        setDebugState("Initializing Marvion Speedrun Engine (Ordered Progression)...");
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
        if (mc.player == null || mc.level == null) {
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

        SpeedrunPhase oldPhase = currentPhase;
        determinePhase(mc);
        setDebugState(currentPhase.getDescription());

        // Apply Marvion Render Distance & Entity Distance Manipulations
        applyPerformanceOptimizations(mc);

        // Special Phase Handling
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

        if (currentPhase == SpeedrunPhase.SLAY_DRAGON_BEDS) {
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

        String dimension = mc.level != null ? mc.level.dimension().toString().toLowerCase() : "overworld";

        int logs = countItemInInventory(mc, "log");
        int planks = countItemInInventory(mc, "planks");
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
        boolean hasShield = countItemInInventory(mc, "shield") > 0 || isShieldEquipped(player);
        boolean hasBucket = countItemInInventory(mc, "bucket") > 0;

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
            // Overworld Strict Ordered Progression

            // 1. Wood Logs
            if (logs < 4 && planks < 8 && pickTier == ToolTier.HAND) {
                currentPhase = SpeedrunPhase.START_WOOD;
            }
            // 2. 2x2 Crafting: Planks, Sticks, Crafting Table
            else if ((planks < 8 || sticks < 4 || tables < 1) && pickTier == ToolTier.HAND) {
                currentPhase = SpeedrunPhase.CRAFT_BASIC_MATERIALS;
            }
            // 3. Wooden Pickaxe
            else if (pickTier == ToolTier.HAND) {
                currentPhase = SpeedrunPhase.CRAFT_WOODEN_PICKAXE;
            }
            // 4. Cobblestone (Must have Wooden Pickaxe!)
            else if (cobble < 11 && pickTier.getLevel() < ToolTier.STONE.getLevel()) {
                currentPhase = SpeedrunPhase.MINE_COBBLESTONE;
            }
            // 5. Stone Pickaxe & Furnace
            else if (pickTier.getLevel() < ToolTier.STONE.getLevel()) {
                currentPhase = SpeedrunPhase.CRAFT_STONE_TOOLS;
            } else if (furnaces < 1 && ironIngots < 3) {
                currentPhase = SpeedrunPhase.CRAFT_STONE_TOOLS;
            }
            // 6. Mine Fuel (Coal/Wood) if needed for furnace
            else if (coal < 4 && (planks + logs < 4) && rawIron > 0 && ironIngots < 3) {
                currentPhase = SpeedrunPhase.MINE_FUEL;
            }
            // 7. Mine Iron Ore (Must have Stone Pickaxe!)
            else if ((rawIron + ironIngots < 15) && pickTier.getLevel() < ToolTier.IRON.getLevel()) {
                currentPhase = SpeedrunPhase.MINE_IRON_ORE;
            }
            // 8. Smelt Raw Iron in Furnace
            else if (ironIngots < 3 && rawIron >= 3) {
                currentPhase = SpeedrunPhase.SMELT_IRON;
            }
            // 9. Craft Iron Gear (Iron Pickaxe, Shield, Bucket)
            else if (pickTier.getLevel() < ToolTier.IRON.getLevel() || !hasShield || !hasBucket) {
                currentPhase = SpeedrunPhase.CRAFT_IRON_GEAR;
            }
            // 10. Mine Diamonds (Must have Iron Pickaxe!)
            else if (diamonds < 3 && pickTier.getLevel() < ToolTier.DIAMOND.getLevel()) {
                currentPhase = SpeedrunPhase.MINE_DIAMONDS;
            }
            // 11. Craft Diamond Pickaxe
            else if (pickTier.getLevel() < ToolTier.DIAMOND.getLevel()) {
                currentPhase = SpeedrunPhase.CRAFT_DIAMOND_PICKAXE;
            }
            // 12. Enter Nether
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

    private Task handleDragonFight(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;

        // 1. Angry Enderman elimination priority in The End
        if (config.eliminateAngryEndermen) {
            for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof Enderman enderman && enderman.isAlive()) {
                    if (enderman.isCreepy() || enderman.hasBeenStaredAt()) {
                        double dist = mc.player.distanceTo(enderman);
                        if (dist < 5.0) {
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
                    6
            );
            case CRAFT_BASIC_MATERIALS -> null; // Handled directly in tick via InventoryManager auto-craft
            case CRAFT_WOODEN_PICKAXE -> new CraftInTableTask("wooden_pickaxe");
            case MINE_COBBLESTONE -> new MineBlockTask(
                    mod, "cobblestone",
                    "stone cobblestone deepslate cobbled_deepslate",
                    11
            );
            case CRAFT_STONE_TOOLS -> new CraftInTableTask("stone_pickaxe");
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
            case PRUNE_INVENTORY -> null;
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
        return offhand != null && !offhand.isEmpty() && offhand.getItem().toString().toLowerCase().contains("shield");
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
        if (currentSubTask != null) {
            currentSubTask.stop(interruptTask);
        }
        Minecraft mc = Minecraft.getInstance();
        restoreOriginalSettings(mc);
    }

    private int countItemInInventory(Minecraft mc, String... keywords) {
        if (mc.player == null) return 0;
        Inventory inv = mc.player.getInventory();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                String name = stack.getItem().toString().toLowerCase();
                for (String kw : keywords) {
                    if (name.contains(kw.toLowerCase())) {
                        count += stack.getCount();
                        break;
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
        return "Marvion Speedrun (Ordered Progression)";
    }
}
