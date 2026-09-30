package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.tasks.construction.MineBlockTask;
import adris.altoclef.tasks.construction.MineBlockTask.ToolTier;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.resources.GetItemTask;
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
    private Task currentSubTask;
    private long enteredEndTimestamp = 0;
    private int originalRenderDistance = 12;
    private double originalEntityScaling = 1.0;
    private int bedDetonationCooldown = 0;
    private boolean isFinished = false;
    private final Set<BlockPos> lootedChests = new HashSet<>();
    private int clutterTimer = 0;

    public MarvionBeatMinecraftTask(AltoClef mod) {
        this.mod = mod;
    }

    @Override
    protected void onStart() {
        setDebugState("Initializing Marvion Speedrun Engine (Reactive Task Tree Architecture)...");
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

        if (player.isDeadOrDying()) {
            if (currentSubTask != null) {
                currentSubTask.stop(null);
                currentSubTask = null;
            }
            setDebugState("Player died. Waiting for respawn...");
            return null;
        }

        // Marvion Routine 1: Structure Fast-Looting (Ruined Portals, Desert Temples, Blacksmiths)
        scanAndLootChests(mc, player);

        // Marvion Routine 2: Inventory Clutter Cleaning
        if (clutterTimer-- <= 0) {
            clutterTimer = 40; // Every 2 seconds
            pruneInventoryClutter(mc, player);
        }

        // Evaluate Speedrun Tree directly
        Task nextAction = evaluateSpeedrunTree(mc, player);

        // Apply Marvion Dynamic Render Distance & Entity Distance Manipulations based on current action
        applyPerformanceOptimizations(mc, nextAction);

        if (nextAction != null && !nextAction.equals(currentSubTask)) {
            if (currentSubTask != null && !currentSubTask.isFinished()) {
                currentSubTask.stop(nextAction);
            }
            currentSubTask = nextAction;
        }

        return currentSubTask;
    }

    /**
     * Pure Reactive Speedrun Task Tree for Marvion:
     * Evaluates preconditions dynamically from Dragon down to Root wood logs!
     */
    private Task evaluateSpeedrunTree(Minecraft mc, LocalPlayer player) {
        String dimension = mc.level != null ? mc.level.dimension().toString().toLowerCase() : "overworld";

        // 1. THE END: DRAGON FIGHT WITH BED STRATS & ANTI-ENDERMAN
        if (dimension.contains("the_end")) {
            if (enteredEndTimestamp == 0) {
                enteredEndTimestamp = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() - enteredEndTimestamp < 2500) {
                setDebugState("Tree [End]: Stabilizing End platform chunks (preventing void fall)...");
                return null;
            }

            // Dragon breath avoidance
            if (handleDragonBreathAvoidance(mc, player)) {
                return null;
            }

            // Angry Enderman defense & bed explosion against dragon
            Task combatTask = handleDragonFight(mc);
            if (combatTask != null) {
                return combatTask;
            }

            setDebugState("Tree [End]: Slaying Ender Dragon with beds & critical strikes");
            return new MineBlockTask(mod, "bedrock and obsidian pillars", "bedrock obsidian", 1);
        }

        // 2. THE NETHER: BLAZE RODS & PIGLIN BARTERING
        if (dimension.contains("nether")) {
            boolean hasGoldenHelmet = isGoldenHelmetEquipped(player) || InventoryManager.countItems(player, "golden_helmet") > 0;
            if (config.goldenHelmet && !hasGoldenHelmet && InventoryManager.countItems(player, "gold_ingot") >= 5) {
                setDebugState("Tree [Nether]: Crafting Golden Helmet (Piglin Pacification)");
                return new GetItemTask(mod, "golden_helmet", 1);
            }

            int blazeRods = InventoryManager.countItems(player, "blaze_rod");
            int pearls = InventoryManager.countItems(player, "ender_pearl");

            if (blazeRods < 7) {
                setDebugState("Tree [Nether]: Fortress Hunting & Blaze Rods (" + blazeRods + "/7)");
                return new MineBlockTask(mod, "blaze spawners and nether bricks", "spawner nether_bricks", 7);
            }

            if (pearls < 12) {
                setDebugState("Tree [Nether]: Piglin Bartering & Ender Pearls (" + pearls + "/12)");
                return new MineBlockTask(mod, "gold ore for bartering", "nether_gold_ore gold_block", 32);
            }

            setDebugState("Tree [Nether]: Returning to Overworld Portal");
            return new MineBlockTask(mod, "nether portal", "nether_portal obsidian", 1);
        }

        // 3. OVERWORLD: TREE PROGRESSION
        int blazeRods = InventoryManager.countItems(player, "blaze_rod");
        int pearls = InventoryManager.countItems(player, "ender_pearl");
        int eyeOfEnder = InventoryManager.countItems(player, "ender_eye", "eye_of_ender");

        // Step 3a: Stronghold & End Portal Navigation
        if (eyeOfEnder >= 12 || (pearls >= 12 && blazeRods >= 7)) {
            if (eyeOfEnder < 12) {
                setDebugState("Tree: Crafting Eyes of Ender");
                return new CraftInTableTask("ender_eye");
            }
            setDebugState("Tree: Locating Stronghold and End Portal Frame");
            return new MineBlockTask(
                    mod, "stronghold portal frame and stone bricks",
                    "end_portal_frame stone_bricks",
                    1
            );
        }

        // Step 3b: Collect Beds for Dragon 1-Cycle
        int beds = InventoryManager.countItems(player, "bed");
        int obsidian = InventoryManager.countItems(player, "obsidian");
        if (config.dragonBedStrats && beds < config.requiredBeds && obsidian >= 10) {
            setDebugState("Tree: Collecting Beds for Dragon One-Cycle (" + beds + "/" + config.requiredBeds + ")");
            return new GetItemTask(mod, "bed", config.requiredBeds);
        }

        // Step 3c: Nether Preparation & Portal
        boolean hasShield = isShieldEquipped(player) || InventoryManager.countItems(player, "shield") > 0;
        boolean hasBucket = InventoryManager.countItems(player, "bucket", "water_bucket") > 0;
        int flintAndSteel = InventoryManager.countItems(player, "flint_and_steel", "fire_charge");
        ToolTier pickTier = MineBlockTask.getPlayerPickaxeTier(player);

        if (!hasShield && InventoryManager.countItems(player, "iron_ingot") > 0) {
            setDebugState("Tree: Equipping Shield");
            return new GetItemTask(mod, "shield", 1);
        }

        if (!hasBucket && InventoryManager.countItems(player, "iron_ingot") >= 3) {
            setDebugState("Tree: Crafting Water Bucket");
            return new GetItemTask(mod, "bucket", 1);
        }

        if (pickTier.getLevel() < ToolTier.DIAMOND.getLevel()) {
            setDebugState("Tree: Acquiring Diamond Pickaxe (Task Tree)");
            return new GetItemTask(mod, "diamond_pickaxe", 1);
        }

        if (obsidian < 10) {
            setDebugState("Tree: Mining Obsidian for Nether Portal (" + obsidian + "/10)");
            return new MineBlockTask(mod, "obsidian", "obsidian", 10);
        }

        if (flintAndSteel < 1) {
            setDebugState("Tree: Acquiring Flint & Steel");
            return new GetItemTask(mod, "flint_and_steel", 1);
        }

        setDebugState("Tree: Constructing & Entering Nether Portal");
        return new MineBlockTask(mod, "nether portal", "nether_portal obsidian", 1);
    }

    private void applyPerformanceOptimizations(Minecraft mc, Task currentTask) {
        if (!config.renderDistanceManipulation || mc.options == null) return;

        try {
            if (currentTask instanceof MineBlockTask mine) {
                String target = mine.toString().toLowerCase();
                if (target.contains("blaze") || target.contains("spawner") || target.contains("stronghold")) {
                    // High render distance to detect fortresses and strongholds
                    if (mc.options.renderDistance().get() != 32) {
                        mc.options.renderDistance().set(32);
                        mc.options.entityDistanceScaling().set(5.0);
                    }
                    return;
                }
                if (target.contains("coal") || target.contains("iron") || target.contains("diamond") || target.contains("cobble") || target.contains("obsidian")) {
                    // Low render distance during underground mining
                    if (mc.options.renderDistance().get() != 2) {
                        mc.options.renderDistance().set(2);
                        mc.options.entityDistanceScaling().set(0.5);
                    }
                    return;
                }
            }

            // Balanced default
            if (mc.options.renderDistance().get() != 12) {
                mc.options.renderDistance().set(12);
                mc.options.entityDistanceScaling().set(1.0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void scanAndLootChests(Minecraft mc, LocalPlayer player) {
        if (!config.searchRuinedPortals && !config.searchDesertTemples) return;
        if (mc.level == null) return;

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

            if (tier.getLevel() >= ToolTier.IRON.getLevel() && name.contains("wooden_pickaxe")) {
                shouldDiscard = true;
            }
            if (tier.getLevel() >= ToolTier.DIAMOND.getLevel() && (name.contains("stone_pickaxe") || name.contains("stone_sword"))) {
                shouldDiscard = true;
            }
            if (name.contains("flint_and_steel") && InventoryManager.countItems(player, "flint_and_steel") > 1) {
                shouldDiscard = true;
            }
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

    private boolean isShieldEquipped(LocalPlayer player) {
        if (player == null) return false;
        ItemStack offhand = player.getOffhandItem();
        return offhand != null && !offhand.isEmpty() &&
                (offhand.is(Items.SHIELD) || InventoryManager.getItemName(offhand).contains("shield"));
    }

    private boolean isGoldenHelmetEquipped(LocalPlayer player) {
        if (player == null || player.inventoryMenu == null) return false;
        ItemStack helm = player.inventoryMenu.getSlot(InventoryMenu.ARMOR_SLOT_START).getItem();
        return !helm.isEmpty() &&
                (helm.is(Items.GOLDEN_HELMET) || InventoryManager.getItemName(helm).contains("golden_helmet"));
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
        return "Marvion Speedrun (Pure Task Tree Architecture)";
    }
}
