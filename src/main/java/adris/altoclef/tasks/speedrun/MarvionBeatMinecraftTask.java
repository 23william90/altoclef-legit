package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.MineBlockTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        START_WOOD("Marvion Phase 1: Fast Wood Gathering & Tool Rush"),
        FAST_LOOT_STRUCTURES("Marvion Phase 2: Scanning Temples & Ruined Portals"),
        MINE_DIAMOND_GEAR("Marvion Phase 3: Diamond Rush & Shield Setup"),
        PRUNE_INVENTORY("Marvion Phase 4: Pruning Inventory Clutter"),
        ENTER_NETHER("Marvion Phase 5: Portal Building & Entering Nether"),
        NETHER_FORTRESS_AND_BLAZES("Marvion Phase 6: Fortress Hunting & Blaze Rods (32 RD Boost)"),
        PIGLIN_BARTER_PEARLS("Marvion Phase 7: Piglin Bartering & Ender Pearls"),
        LOCATE_STRONGHOLD("Marvion Phase 8: Eye Crafting & Stronghold Infiltration"),
        WAIT_FOR_END_CHUNKS("Marvion Phase 9: Stabilizing End Platform & Chunks"),
        SLAY_DRAGON_BEDS("Marvion Phase 10: Bed-Explosion Dragon One-Cycle & Anti-Enderman"),
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
        setDebugState("Initializing Marvion Speedrun Engine...");
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

        // Determine phase
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

        // Check subtask completion
        if (currentSubTask == null || currentSubTask.isFinished()) {
            currentSubTask = createSubTaskForPhase(currentPhase);
        }

        return currentSubTask;
    }

    private void determinePhase(Minecraft mc) {
        String dimension = mc.level != null ? mc.level.dimension().toString().toLowerCase() : "overworld";

        int logs = countItemInInventory(mc, "log");
        int iron = countItemInInventory(mc, "iron_ingot", "raw_iron");
        int diamonds = countItemInInventory(mc, "diamond");
        int obsidian = countItemInInventory(mc, "obsidian");
        int blazeRods = countItemInInventory(mc, "blaze_rod");
        int pearls = countItemInInventory(mc, "ender_pearl");

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
            // Overworld Progression
            if (logs < 12 && iron < 3) {
                currentPhase = SpeedrunPhase.START_WOOD;
            } else if (iron < 3 && diamonds < 3 && config.searchDesertTemples) {
                currentPhase = SpeedrunPhase.FAST_LOOT_STRUCTURES;
            } else if (diamonds < 3 && obsidian < 10) {
                currentPhase = SpeedrunPhase.MINE_DIAMOND_GEAR;
            } else if (obsidian < 10) {
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
                case START_WOOD, MINE_DIAMOND_GEAR, PRUNE_INVENTORY -> {
                    // Minimize render distance during mining/gathering for max tick speed
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
                            // Look at enderman and strike
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
            // Check if dragon is dying -> look up!
            if (dragon.dragonDeathTime > 0 ||
                    (dragon.getPhaseManager() != null && dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING)) {
                setDebugState("Dragon dying! Looking up to the skies.");
                mc.player.setXRot(-90.0f);
                return null;
            }

            // Dragon Bed Explosions when perching
            boolean isPerching = dragon.getPhaseManager() != null &&
                    (dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.SITTING_FLAMING ||
                     dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.SITTING_SCANNING ||
                     dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.LANDING);

            if (isPerching && bedDetonationCooldown-- <= 0) {
                // Select bed in hotbar
                int bedSlot = findItemSlot(mc, "bed");
                if (bedSlot != -1) {
                    mc.player.getInventory().setSelectedSlot(bedSlot);
                    // Right click towards bedrock exit portal center (0, y, 0)
                    BlockPos portalCenter = new BlockPos(0, 65, 0);
                    if (mc.gameMode != null) {
                        BlockHitResult hit = new BlockHitResult(new Vec3(0.5, 65.5, 0.5), Direction.UP, portalCenter, false);
                        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                        bedDetonationCooldown = 15; // Cooldown before next bed detonation
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
                    mod, "wood",
                    "oak_log birch_log spruce_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log pale_oak_log",
                    16
            );
            case FAST_LOOT_STRUCTURES, MINE_DIAMOND_GEAR -> new MineBlockTask(
                    mod, "diamond and iron ores",
                    "diamond_ore deepslate_diamond_ore iron_ore deepslate_iron_ore",
                    8
            );
            case PRUNE_INVENTORY -> null;
            case ENTER_NETHER -> new MineBlockTask(
                    mod, "obsidian for nether portal",
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

    private int findItemSlot(Minecraft mc, String keyword) {
        if (mc.player == null) return -1;
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem().toString().toLowerCase().contains(keyword.toLowerCase())) {
                return i;
            }
        }
        return -1;
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
        return "Beating the game (Marvion version - Ultra Fast Speedrun)";
    }
}
