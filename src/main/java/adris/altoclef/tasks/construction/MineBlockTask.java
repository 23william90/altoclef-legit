package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.control.RenderDistanceManager;
import adris.altoclef.control.WorldMemoryTracker;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class MineBlockTask extends Task {

    public enum ToolTier {
        HAND(0),
        WOOD(1),
        STONE(2),
        IRON(3),
        DIAMOND(4),
        NETHERITE(5);

        private final int level;

        ToolTier(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }
    }

    private final AltoClef mod;
    private final String resourceName;
    private final String blockTarget;
    private final int targetCount;
    private final Block[] resolvedBlocks;
    private final String[] blockNames;
    private final BlockOptionalMetaLookup bomLookup;
    private boolean finished = false;
    private int cooldown = 0;

    // Block Blacklist & Timeout Tracking
    private BlockPos currentBreakingPos = null;
    private long breakingStartTime = 0;
    private Vec3 lastPathingPos = null;
    private int pathingStuckTicks = 0;
    private boolean wasBaritoneMining = false;

    public MineBlockTask(AltoClef mod, String resourceName, String blockTarget, int targetCount) {
        this.mod = mod;
        this.resourceName = resourceName;
        this.blockTarget = blockTarget;
        this.targetCount = targetCount;

        List<Block> blocks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String[] tokens = blockTarget.split("[,\\s]+");
        for (String token : tokens) {
            token = token.trim();
            if (token.isEmpty()) continue;
            names.add(token);
            try {
                Identifier id = Identifier.tryParse(token.contains(":") ? token : "minecraft:" + token);
                if (id != null) {
                    Block b = BuiltInRegistries.BLOCK.getValue(id);
                    if (b != null && b != Blocks.AIR) {
                        blocks.add(b);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        this.resolvedBlocks = blocks.toArray(new Block[0]);
        this.blockNames = names.toArray(new String[0]);

        BlockOptionalMetaLookup lookup = null;
        try {
            if (this.resolvedBlocks.length > 0) {
                lookup = new BlockOptionalMetaLookup(this.resolvedBlocks);
            } else if (this.blockNames.length > 0) {
                lookup = new BlockOptionalMetaLookup(this.blockNames);
            }
        } catch (Throwable ignored) {
        }
        this.bomLookup = lookup;
    }

    public ToolTier getRequiredTier() {
        ToolTier highest = ToolTier.HAND;
        for (Block b : resolvedBlocks) {
            ToolTier tier = getRequiredToolTier(b);
            if (tier.getLevel() > highest.getLevel()) {
                highest = tier;
            }
        }
        if (resolvedBlocks.length == 0) {
            for (String name : blockNames) {
                ToolTier tier = getRequiredToolTier(name);
                if (tier.getLevel() > highest.getLevel()) {
                    highest = tier;
                }
            }
        }
        return highest;
    }

    public static ToolTier getRequiredToolTier(Block block) {
        if (block == null) return ToolTier.HAND;
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) return ToolTier.HAND;
        return getRequiredToolTier(id.getPath());
    }

    public static ToolTier getRequiredToolTier(String name) {
        name = name.toLowerCase();
        if (name.contains("obsidian") || name.contains("ancient_debris") || name.contains("respawn_anchor")) {
            return ToolTier.DIAMOND;
        }
        if (name.contains("diamond") || name.contains("gold_ore") || name.contains("emerald") || name.contains("redstone")) {
            return ToolTier.IRON;
        }
        if (name.contains("iron_ore") || name.contains("raw_iron_block") || name.contains("copper_ore") || name.contains("lapis")) {
            return ToolTier.STONE;
        }
        if (name.contains("stone") || name.contains("cobble") || name.contains("deepslate") || name.contains("coal_ore") ||
                name.contains("andesite") || name.contains("diorite") || name.contains("granite") || name.contains("sandstone") ||
                name.contains("tuff") || name.contains("calcite")) {
            return ToolTier.WOOD;
        }
        return ToolTier.HAND;
    }

    public static ToolTier getPlayerPickaxeTier(LocalPlayer player) {
        if (player == null) return ToolTier.HAND;
        ToolTier highest = ToolTier.HAND;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            
            // Check direct Items references first
            if (stack.is(Items.NETHERITE_PICKAXE)) return ToolTier.NETHERITE;
            if (stack.is(Items.DIAMOND_PICKAXE)) {
                if (highest.getLevel() < ToolTier.DIAMOND.getLevel()) highest = ToolTier.DIAMOND;
            } else if (stack.is(Items.IRON_PICKAXE)) {
                if (highest.getLevel() < ToolTier.IRON.getLevel()) highest = ToolTier.IRON;
            } else if (stack.is(Items.STONE_PICKAXE)) {
                if (highest.getLevel() < ToolTier.STONE.getLevel()) highest = ToolTier.STONE;
            } else if (stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.GOLDEN_PICKAXE)) {
                if (highest.getLevel() < ToolTier.WOOD.getLevel()) highest = ToolTier.WOOD;
            } else {
                // Registry name path fallback
                String name = InventoryManager.getItemName(stack);
                if (name.contains("netherite_pickaxe")) return ToolTier.NETHERITE;
                if (name.contains("diamond_pickaxe")) {
                    if (highest.getLevel() < ToolTier.DIAMOND.getLevel()) highest = ToolTier.DIAMOND;
                } else if (name.contains("iron_pickaxe")) {
                    if (highest.getLevel() < ToolTier.IRON.getLevel()) highest = ToolTier.IRON;
                } else if (name.contains("stone_pickaxe")) {
                    if (highest.getLevel() < ToolTier.STONE.getLevel()) highest = ToolTier.STONE;
                } else if (name.contains("wooden_pickaxe") || name.contains("copper_pickaxe") || name.contains("golden_pickaxe")) {
                    if (highest.getLevel() < ToolTier.WOOD.getLevel()) highest = ToolTier.WOOD;
                }
            }
        }
        return highest;
    }

    public static int getPickaxeHotbarSlot(LocalPlayer player) {
        if (player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                if (stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.STONE_PICKAXE) ||
                        stack.is(Items.IRON_PICKAXE) || stack.is(Items.DIAMOND_PICKAXE) ||
                        stack.is(Items.NETHERITE_PICKAXE) || stack.is(Items.GOLDEN_PICKAXE) ||
                        InventoryManager.getItemName(stack).contains("pickaxe")) {
                    return i;
                }
            }
        }
        return -1;
    }

    @Override
    protected void onStart() {
        finished = false;
        cooldown = 0;
        currentBreakingPos = null;
        breakingStartTime = 0;
        lastPathingPos = null;
        pathingStuckTicks = 0;
        wasBaritoneMining = false;
        setDebugState("Target: " + targetCount + "x " + resourceName);

        try {
            BaritoneAPI.getSettings().blacklistClosestOnFailure.value = true;
        } catch (Throwable ignored) {
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            ToolTier required = getRequiredTier();
            ToolTier playerTier = getPlayerPickaxeTier(mc.player);
            if (playerTier.getLevel() < required.getLevel()) {
                setDebugState("CANNOT MINE " + resourceName + ": Requires " + required + " pickaxe (Have " + playerTier + ")");
                return;
            }
        }

        startMining();
    }

    private void startMining() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            ToolTier required = getRequiredTier();
            ToolTier playerTier = getPlayerPickaxeTier(mc.player);
            if (playerTier.getLevel() < required.getLevel()) {
                return; // Forbidden from mining without proper tool
            }
        }

        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null) {
                if (resolvedBlocks.length > 0) {
                    primary.getMineProcess().mine(targetCount, resolvedBlocks);
                } else if (blockNames.length > 0) {
                    primary.getMineProcess().mineByName(targetCount, blockNames);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;

        // Tool tier enforcement check
        ToolTier required = getRequiredTier();
        ToolTier playerTier = getPlayerPickaxeTier(mc.player);
        if (playerTier.getLevel() < required.getLevel()) {
            setDebugState("CANNOT MINE " + resourceName + ": Requires " + required + " pickaxe (Have " + playerTier + ")");
            try {
                IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (primary != null && primary.getMineProcess().isActive()) {
                    primary.getMineProcess().cancel();
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        // Equip the pickaxe in hand if required
        if (required.getLevel() >= ToolTier.WOOD.getLevel()) {
            int pickSlot = getPickaxeHotbarSlot(mc.player);
            if (pickSlot != -1 && mc.player.getInventory().getSelectedSlot() != pickSlot) {
                mc.player.getInventory().setSelectedSlot(pickSlot);
            }
        }

        int current = getCurrentCount();
        setDebugState(current + " / " + targetCount + " " + resourceName);

        if (current >= targetCount) {
            finished = true;
            RenderDistanceManager.revert(mc);
            onStop(null);
            return null;
        }

        IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
        boolean isBaritoneMining = primary != null && primary.getMineProcess().isActive();
        boolean isBaritonePathing = primary != null && primary.getPathingBehavior().isPathing();

        // If Baritone finished receiving all requested items:
        if (wasBaritoneMining && !isBaritoneMining && current >= targetCount) {
            finished = true;
            RenderDistanceManager.revert(mc);
            onStop(null);
            return null;
        }
        wasBaritoneMining = isBaritoneMining;

        // 1. ACTIVE BLOCK BREAKING TIMEOUT & BLACKLIST (Estimated Normal Break Time)
        boolean isDestroying = (mc.gameMode != null && mc.gameMode.isDestroying()) ||
                (primary != null && primary.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT));
        BlockPos targetedBlock = getCurrentlyTargetedBlock(mc);

        if (isDestroying && targetedBlock != null) {
            if (currentBreakingPos == null || !currentBreakingPos.equals(targetedBlock)) {
                currentBreakingPos = targetedBlock.immutable();
                breakingStartTime = System.currentTimeMillis();
            } else {
                BlockState state = mc.level != null ? mc.level.getBlockState(currentBreakingPos) : null;
                if (state != null && !state.isAir()) {
                    double elapsed = (System.currentTimeMillis() - breakingStartTime) / 1000.0;
                    double maxAllowed = getMaxAllowedBreakTime(mc.player, state, currentBreakingPos);

                    if (elapsed > maxAllowed) {
                        Debug.logMessage("Mining TIMEOUT on block at " + currentBreakingPos.toShortString() +
                                " (" + String.format("%.2f", elapsed) + "s > max " + String.format("%.2f", maxAllowed) + "s). Blacklisting block!");
                        WorldMemoryTracker.getInstance().blacklistBlock(currentBreakingPos, 120_000L);
                        if (primary != null) {
                            primary.getMineProcess().cancel();
                            primary.getInputOverrideHandler().clearAllKeys();
                        }
                        if (mc.gameMode != null) {
                            mc.gameMode.stopDestroyBlock();
                        }
                        currentBreakingPos = null;
                        breakingStartTime = 0;
                        setDebugState("Blacklisted slow block (" + String.format("%.1f", elapsed) + "s). Mining another...");
                        startMining();
                        return null;
                    }
                } else {
                    currentBreakingPos = null;
                    breakingStartTime = 0;
                }
            }
        } else {
            currentBreakingPos = null;
            breakingStartTime = 0;
        }

        // 2. UNREACHABLE / PATHING STUCK BLACKLIST
        if (isBaritonePathing && mc.player != null) {
            Vec3 currentPos = mc.player.position();
            if (lastPathingPos == null || currentPos.distanceToSqr(lastPathingPos) > 0.6) {
                lastPathingPos = currentPos;
                pathingStuckTicks = 0;
            } else {
                pathingStuckTicks++;
                if (pathingStuckTicks > 120) { // 6 seconds without progress while pathing to a block
                    pathingStuckTicks = 0;
                    BlockPos unreachable = findClosestTargetBlock(mc, mc.player, 32);
                    if (unreachable != null) {
                        Debug.logMessage("Pathing STUCK near " + unreachable.toShortString() + ". Blacklisting unreachable block!");
                        WorldMemoryTracker.getInstance().blacklistBlock(unreachable, 120_000L);
                        if (primary != null) {
                            primary.getPathingBehavior().forceCancel();
                            primary.getMineProcess().cancel();
                            primary.getInputOverrideHandler().clearAllKeys();
                        }
                        setDebugState("Blacklisted unreachable block. Re-routing...");
                        startMining();
                        return null;
                    }
                }
            }
        } else {
            lastPathingPos = null;
            pathingStuckTicks = 0;
        }

        // 3. Chest memory scanning: record nearby chests
        if (mc.level != null && mc.player.tickCount % 40 == 0) {
            BlockPos pPos = mc.player.blockPosition();
            for (int x = -5; x <= 5; x++) {
                for (int y = -2; y <= 3; y++) {
                    for (int z = -5; z <= 5; z++) {
                        BlockPos checkPos = pPos.offset(x, y, z);
                        if (mc.level.getBlockState(checkPos).is(Blocks.CHEST)) {
                            WorldMemoryTracker.getInstance().recordChest(checkPos);
                        }
                    }
                }
            }
        }

        // 4. RECOVER DEATH DROPS: Check if player recently died and has recoverable dropped items
        BlockPos deathDrop = WorldMemoryTracker.getInstance().getRecoverableDeathDrop(mc, mc.player, 160.0);
        if (deathDrop != null) {
            if (primary != null) {
                if (primary.getMineProcess().isActive()) {
                    primary.getMineProcess().cancel();
                }
                primary.getCustomGoalProcess().setGoalAndPath(new GoalNear(deathDrop, 1));
            }
            setDebugState("Recovering death drops at " + deathDrop.toShortString());
            RenderDistanceManager.revert(mc);
            return null;
        }

        // 5. CONTINUOUS VEIN MINING & GROUND DROPS
        boolean blocksNearby = isTargetBlockWithinReach(mc, mc.player);

        if (isDestroying || blocksNearby) {
            // Actively destroying a block, or adjacent vein block within reach:
            // CONTINUOUS MINING: Do NOT interrupt or cancel mining for dropped items!
            if (!isBaritoneMining && !isBaritonePathing) {
                startMining();
            }
        } else {
            // No target blocks within immediate reach and not destroying.
            // Check for nearby dropped items (e.g. from the vein just mined, or dropped on ground).
            ItemEntity bestDrop = WorldMemoryTracker.getInstance().findBestDroppedItem(mc, mc.player, resourceName, blockNames);
            if (bestDrop != null) {
                // If a target block is reachable from the dropped item's location:
                // Mine that block so we path there and scoop the drop simultaneously!
                if (isTargetBlockNearPos(mc, bestDrop.position(), 4.5)) {
                    if (!isBaritoneMining) {
                        startMining();
                    }
                } else if (mc.player.distanceTo(bestDrop) < 16.0f || !isBaritoneMining) {
                    // Isolated drop: path to collect it before traveling far away
                    if (primary != null) {
                        if (primary.getMineProcess().isActive()) {
                            primary.getMineProcess().cancel();
                        }
                        primary.getCustomGoalProcess().setGoalAndPath(new GoalNear(bestDrop.blockPosition(), 0));
                    }
                    setDebugState("Collecting dropped " + resourceName + " (" + (int) mc.player.distanceTo(bestDrop) + "m away)");
                    RenderDistanceManager.revert(mc);
                    return null;
                }
            } else if (!isBaritoneMining && !isBaritonePathing && (primary == null || !primary.getCustomGoalProcess().isActive())) {
                startMining();
            }
        }

        // 6. MINING BLOCKS & DYNAMIC RENDER DISTANCE
        boolean isBusy = primary != null && (primary.getMineProcess().isActive() || primary.getPathingBehavior().isPathing());
        if (isBusy) {
            RenderDistanceManager.revert(mc);
        } else {
            RenderDistanceManager.requestSearchBoost(mc, 26, 300);
        }

        if (cooldown-- <= 0) {
            cooldown = 20; // Check every 1 second
            try {
                if (primary != null && !primary.getMineProcess().isActive() && !primary.getPathingBehavior().isPathing() && !primary.getCustomGoalProcess().isActive()) {
                    startMining();
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        Minecraft mc = Minecraft.getInstance();
        RenderDistanceManager.forceRevert(mc);
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null) {
                primary.getMineProcess().cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean isTargetBlockWithinReach(Minecraft mc, LocalPlayer player) {
        if (player == null) return false;
        return isTargetBlockNearPos(mc, player.position(), 4.5);
    }

    public boolean isTargetBlockNearPos(Minecraft mc, Vec3 pos, double radius) {
        if (mc.level == null || pos == null) return false;
        BlockPos center = BlockPos.containing(pos);
        int r = (int) Math.ceil(radius);
        for (int x = -r; x <= r; x++) {
            for (int y = -2; y <= 3; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos p = center.offset(x, y, z);
                    if (WorldMemoryTracker.getInstance().isBlockBlacklisted(p)) continue;
                    if (p.closerToCenterThan(pos, radius)) {
                        Block block = mc.level.getBlockState(p).getBlock();
                        if (isTargetBlock(block)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public BlockPos getCurrentlyTargetedBlock(Minecraft mc) {
        if (mc.hitResult instanceof BlockHitResult bhr) {
            BlockPos p = bhr.getBlockPos();
            if (mc.level != null && isTargetBlock(mc.level.getBlockState(p).getBlock())) {
                return p;
            }
        }
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null && primary.getPlayerContext() != null && primary.getPlayerContext().getSelectedBlock().isPresent()) {
                BlockPos p = primary.getPlayerContext().getSelectedBlock().get();
                if (mc.level != null && isTargetBlock(mc.level.getBlockState(p).getBlock())) {
                    return p;
                }
            }
        } catch (Throwable ignored) {
        }
        if (mc.hitResult instanceof BlockHitResult bhr) {
            return bhr.getBlockPos();
        }
        return null;
    }

    public BlockPos findClosestTargetBlock(Minecraft mc, LocalPlayer player, int radius) {
        if (mc.level == null || player == null) return null;
        BlockPos center = player.blockPosition();
        BlockPos closest = null;
        double closestDistSq = Double.POSITIVE_INFINITY;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -8; y <= 8; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos p = center.offset(x, y, z);
                    if (WorldMemoryTracker.getInstance().isBlockBlacklisted(p)) continue;
                    Block block = mc.level.getBlockState(p).getBlock();
                    if (isTargetBlock(block)) {
                        double dSq = player.distanceToSqr(Vec3.atCenterOf(p));
                        if (dSq < closestDistSq) {
                            closestDistSq = dSq;
                            closest = p;
                        }
                    }
                }
            }
        }
        return closest;
    }

    public static double calculateNormalBreakTimeSeconds(LocalPlayer player, BlockState state, BlockPos pos) {
        if (player == null || state == null || state.isAir()) return 0.05;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 1.0;
        try {
            float progress = state.getDestroyProgress(player, mc.level, pos);
            if (progress <= 0.0f) {
                return -1.0; // Unbreakable or zero progress
            }
            float ticks = (float) Math.ceil(1.0f / progress);
            return ticks / 20.0;
        } catch (Throwable t) {
            return 2.0;
        }
    }

    public static double getMaxAllowedBreakTime(LocalPlayer player, BlockState state, BlockPos pos) {
        double normal = calculateNormalBreakTimeSeconds(player, state, pos);
        if (normal < 0) return 1.5; // Unbreakable / impossible -> fast blacklist
        if (normal > 35.0) return 2.0; // Inappropriate tool (would take > 35s) -> fast blacklist
        return Math.max(3.0, (normal * 2.5) + 1.5);
    }

    public boolean isTargetBlock(Block block) {
        if (block == null || block == Blocks.AIR) return false;
        for (Block b : resolvedBlocks) {
            if (b == block) return true;
        }
        try {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (id != null) {
                String path = id.getPath().toLowerCase();
                for (String name : blockNames) {
                    if (path.contains(name.toLowerCase()) || name.toLowerCase().contains(path)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public int getCurrentCount() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        Inventory inv = mc.player.getInventory();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && matchesStack(stack)) {
                count += stack.getCount();
            }
        }
        if (mc.player.containerMenu != null) {
            ItemStack carried = mc.player.containerMenu.getCarried();
            if (carried != null && !carried.isEmpty() && matchesStack(carried)) {
                count += carried.getCount();
            }
        }
        return count;
    }

    public boolean matchesStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            if (bomLookup != null && bomLookup.has(stack)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        String itemName = InventoryManager.getItemName(stack);
        return matchesResource(itemName, resourceName, blockNames);
    }

    public static boolean matchesResource(String itemName, String resourceName, String... aliases) {
        if (itemName == null || itemName.isEmpty()) return false;
        itemName = itemName.toLowerCase();

        String cleanResource = (resourceName != null ? resourceName.toLowerCase().replace(" ", "_") : "");

        // Exact or direct substring match
        if (!cleanResource.isEmpty()) {
            if (itemName.equals(cleanResource) || itemName.contains(cleanResource) || cleanResource.contains(itemName)) {
                return true;
            }
            String strippedItem = itemName.replace("_", "");
            String strippedResource = cleanResource.replace("_", "");
            if (strippedItem.contains(strippedResource) || strippedResource.contains(strippedItem)) {
                return true;
            }
        }

        // Check against aliases / target block names directly
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias == null || alias.isEmpty()) continue;
                String cleanAlias = alias.toLowerCase().replace("minecraft:", "").trim();
                if (itemName.equals(cleanAlias) || itemName.contains(cleanAlias) || cleanAlias.contains(itemName)) {
                    return true;
                }
            }
        }

        // Semantic Ore & Material Drop Mappings (Essential for modern Minecraft 1.17+)
        boolean isIron = cleanResource.contains("iron") || containsAny(aliases, "iron");
        if (isIron) {
            if (itemName.contains("raw_iron") || itemName.contains("iron_ingot") || itemName.contains("iron_ore") || itemName.equals("iron_block") || itemName.equals("raw_iron_block")) {
                return true;
            }
        }

        boolean isCopper = cleanResource.contains("copper") || containsAny(aliases, "copper");
        if (isCopper) {
            if (itemName.contains("raw_copper") || itemName.contains("copper_ingot") || itemName.contains("copper_ore") || itemName.contains("copper_block") || itemName.contains("raw_copper_block")) {
                return true;
            }
        }

        boolean isGold = cleanResource.contains("gold") || containsAny(aliases, "gold");
        if (isGold) {
            if (itemName.contains("raw_gold") || itemName.contains("gold_ingot") || itemName.contains("gold_ore") || itemName.contains("gold_nugget") || itemName.contains("gold_block") || itemName.contains("raw_gold_block")) {
                return true;
            }
        }

        boolean isDiamond = cleanResource.contains("diamond") || containsAny(aliases, "diamond");
        if (isDiamond) {
            if (itemName.contains("diamond")) {
                return true;
            }
        }

        boolean isCoal = cleanResource.contains("coal") || cleanResource.contains("fuel") || containsAny(aliases, "coal");
        if (isCoal) {
            if (itemName.equals("coal") || itemName.equals("charcoal") || itemName.contains("coal_ore") || itemName.equals("coal_block")) {
                return true;
            }
        }

        boolean isLapis = cleanResource.contains("lapis") || containsAny(aliases, "lapis");
        if (isLapis) {
            if (itemName.contains("lapis")) {
                return true;
            }
        }

        boolean isRedstone = cleanResource.contains("redstone") || containsAny(aliases, "redstone");
        if (isRedstone) {
            if (itemName.contains("redstone")) {
                return true;
            }
        }

        boolean isEmerald = cleanResource.contains("emerald") || containsAny(aliases, "emerald");
        if (isEmerald) {
            if (itemName.contains("emerald")) {
                return true;
            }
        }

        boolean isStone = cleanResource.contains("stone") || cleanResource.contains("cobble") || containsAny(aliases, "stone", "cobble", "deepslate");
        if (isStone) {
            if (itemName.contains("cobble") || itemName.contains("stone") || itemName.contains("deepslate") || itemName.contains("blackstone") || itemName.contains("tuff") || itemName.contains("andesite") || itemName.contains("diorite") || itemName.contains("granite")) {
                return true;
            }
        }

        boolean isWood = cleanResource.contains("wood") || cleanResource.contains("log") || containsAny(aliases, "log", "wood", "stem", "hyphae");
        if (isWood) {
            if (itemName.endsWith("_log") || itemName.endsWith("_wood") || itemName.endsWith("_stem") || itemName.endsWith("_hyphae") || itemName.equals("log") || itemName.equals("wood")) {
                return true;
            }
        }

        boolean isPlank = cleanResource.contains("plank") || containsAny(aliases, "plank");
        if (isPlank) {
            if (itemName.endsWith("_planks") || itemName.equals("planks")) {
                return true;
            }
        }

        return false;
    }

    private static boolean containsAny(String[] array, String... targets) {
        if (array == null) return false;
        for (String item : array) {
            if (item == null) continue;
            String lower = item.toLowerCase();
            for (String target : targets) {
                if (lower.contains(target.toLowerCase())) return true;
            }
        }
        return false;
    }

    @Override
    public boolean isFinished() {
        return finished || getCurrentCount() >= targetCount;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof MineBlockTask task) {
            return task.blockTarget.equals(this.blockTarget) && task.targetCount == this.targetCount;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Mining " + resourceName + " (" + blockTarget + ")";
    }
}
