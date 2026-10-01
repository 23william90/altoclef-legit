package adris.altoclef.tasks.construction;

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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Modern Reactive Nether Portal Construction Engine:
 * Places a 10-block obsidian portal frame, ignites with flint & steel,
 * and walks directly into the portal to transition dimensions.
 */
public class ConstructNetherPortalTask extends Task {

    // 10 Obsidian Portal Frame Offsets (X-aligned frame, standard economical layout):
    // Width = 4 (columns 0, 1, 2, 3), Height = 5 (rows 0, 1, 2, 3, 4)
    // Bottom: (1, 0, 0), (2, 0, 0)
    // Left Pillar: (0, 1, 0), (0, 2, 0), (0, 3, 0)
    // Right Pillar: (3, 1, 0), (3, 2, 0), (3, 3, 0)
    // Top: (1, 4, 0), (2, 4, 0)
    private static final int[][] FRAME_OFFSETS = new int[][]{
            {1, 0, 0}, {2, 0, 0}, // Bottom
            {0, 1, 0}, {0, 2, 0}, {0, 3, 0}, // Left
            {3, 1, 0}, {3, 2, 0}, {3, 3, 0}, // Right
            {1, 4, 0}, {2, 4, 0}  // Top
    };

    private BlockPos portalOrigin = null;
    private int stepTimer = 0;
    private boolean finished = false;

    @Override
    protected void onStart() {
        portalOrigin = null;
        stepTimer = 0;
        finished = false;
        setDebugState("Constructing Nether Portal...");
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) return null;

        if (stepTimer-- > 0) return null;

        // 1. If portal block is already nearby in world, step into it!
        BlockPos existingPortal = findNearbyPortalBlock(mc, player, 16);
        if (existingPortal != null) {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(existingPortal, 0));
            }
            setDebugState("Walking into Nether Portal at " + existingPortal.toShortString() + "...");
            stepTimer = 4;
            return null;
        }

        // 2. Ensure player has required materials: 10 obsidian + flint & steel
        int obsidian = InventoryManager.countItems(player, "obsidian");
        int flintSteel = InventoryManager.countItems(player, "flint_and_steel", "fire_charge");
        if (obsidian < 10 && portalOrigin == null) {
            setDebugState("Need 10 Obsidian to construct Nether Portal (Have " + obsidian + "/10)");
            return null;
        }
        if (flintSteel < 1) {
            setDebugState("Need Flint & Steel to ignite Nether Portal!");
            return null;
        }

        // 3. Find a flat, clear site for portal if not yet established
        if (portalOrigin == null) {
            portalOrigin = findPortalOrigin(mc, player);
            if (portalOrigin == null) {
                setDebugState("Searching for clear ground to build Nether Portal...");
                return null;
            }
        }

        // 4. Place missing frame blocks
        for (int[] offset : FRAME_OFFSETS) {
            BlockPos targetPos = portalOrigin.offset(offset[0], offset[1], offset[2]);
            BlockState state = mc.level.getBlockState(targetPos);
            if (!state.is(Blocks.OBSIDIAN)) {
                // If obstructed by non-air block, clear it
                if (!state.isAir() && !state.canBeReplaced()) {
                    setDebugState("Clearing obstruction at " + targetPos.toShortString());
                    int pick = MineBlockTask.getPickaxeHotbarSlot(player);
                    if (pick != -1) player.getInventory().setSelectedSlot(pick);
                    mc.gameMode.startDestroyBlock(targetPos, Direction.UP);
                    stepTimer = 4;
                    return null;
                }

                // If player is too far from placement spot, path closer
                if (player.distanceToSqr(Vec3.atCenterOf(targetPos)) > 16.0) {
                    IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(targetPos, 2));
                    }
                    setDebugState("Moving to place obsidian at " + targetPos.toShortString());
                    stepTimer = 4;
                    return null;
                }

                // Equip obsidian in hotbar
                int obsSlot = ensureHeldItem(player, "obsidian");
                if (obsSlot == -1) {
                    setDebugState("Unable to hold obsidian!");
                    return null;
                }

                // Find a neighbor block to place against
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = targetPos.relative(dir);
                    BlockState nState = mc.level.getBlockState(neighbor);
                    if (nState.isSolid() && !nState.canBeReplaced()) {
                        Vec3 hitVec = Vec3.atCenterOf(neighbor).add(dir.getOpposite().getStepX() * 0.5,
                                dir.getOpposite().getStepY() * 0.5, dir.getOpposite().getStepZ() * 0.5);
                        lookAt(player, hitVec);
                        BlockHitResult hit = new BlockHitResult(hitVec, dir.getOpposite(), neighbor, false);
                        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                        setDebugState("Placed obsidian at " + targetPos.toShortString());
                        stepTimer = 3;
                        return null;
                    }
                }

                // If no neighbor solid block, place a temporary support block below
                BlockPos below = targetPos.below();
                if (mc.level.getBlockState(below).isAir()) {
                    int throwSlot = ensureHeldThrowaway(player);
                    if (throwSlot != -1) {
                        BlockPos support = below.below();
                        if (mc.level.getBlockState(support).isSolid()) {
                            Vec3 hitVec = Vec3.atCenterOf(support).add(0, 0.5, 0);
                            lookAt(player, hitVec);
                            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, support, false);
                            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                            stepTimer = 3;
                            return null;
                        }
                    }
                }
            }
        }

        // 5. Clear interior portal space (ensure air blocks)
        for (int y = 1; y <= 3; y++) {
            for (int x = 1; x <= 2; x++) {
                BlockPos interiorPos = portalOrigin.offset(x, y, 0);
                BlockState inState = mc.level.getBlockState(interiorPos);
                if (inState.is(Blocks.NETHER_PORTAL)) {
                    // Portal is already ignited!
                    IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (baritone != null) {
                        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(interiorPos, 0));
                    }
                    setDebugState("Stepping into Nether Portal...");
                    stepTimer = 5;
                    return null;
                }
                if (!inState.isAir() && !inState.is(Blocks.FIRE)) {
                    mc.gameMode.startDestroyBlock(interiorPos, Direction.UP);
                    stepTimer = 4;
                    return null;
                }
            }
        }

        // 6. All 10 frame blocks placed! Ignite bottom interior with Flint & Steel
        BlockPos bottomInterior = portalOrigin.offset(1, 1, 0);
        BlockPos igniteSupport = portalOrigin.offset(1, 0, 0);

        int flintSlot = ensureHeldItem(player, "flint_and_steel", "fire_charge");
        if (flintSlot == -1) {
            setDebugState("Holding Flint & Steel to ignite portal...");
            return null;
        }

        Vec3 hitVec = Vec3.atCenterOf(igniteSupport).add(0, 0.5, 0);
        lookAt(player, hitVec);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, igniteSupport, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        setDebugState("Ignited Nether Portal!");
        stepTimer = 5;

        return null;
    }

    private BlockPos findNearbyPortalBlock(Minecraft mc, LocalPlayer player, int radius) {
        if (mc.level == null) return null;
        BlockPos center = player.blockPosition();
        for (int y = -4; y <= 4; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos p = center.offset(x, y, z);
                    if (mc.level.getBlockState(p).is(Blocks.NETHER_PORTAL)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    private BlockPos findPortalOrigin(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;
        BlockPos pPos = player.blockPosition();

        for (int dx = 2; dx <= 6; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos origin = pPos.offset(dx, 0, dz);
                // Check if ground beneath bottom 4 blocks is solid
                boolean groundSolid = true;
                for (int x = 0; x < 4; x++) {
                    if (!mc.level.getBlockState(origin.offset(x, -1, 0)).isSolid()) {
                        groundSolid = false;
                        break;
                    }
                }
                if (groundSolid) {
                    return origin;
                }
            }
        }
        return pPos.offset(3, 0, 0);
    }

    private int ensureHeldItem(LocalPlayer player, String... keywords) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                String name = InventoryManager.getItemName(stack);
                for (String kw : keywords) {
                    if (name.contains(kw)) {
                        player.getInventory().setSelectedSlot(i);
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private int ensureHeldThrowaway(LocalPlayer player) {
        return ensureHeldItem(player, "cobblestone", "dirt", "netherrack", "stone", "deepslate");
    }

    private void lookAt(LocalPlayer player, Vec3 target) {
        Vec3 diff = target.subtract(player.getEyePosition());
        double distXZ = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(diff.z, diff.x))) - 90.0F;
        float pitch = (float) (-Math.toDegrees(Math.atan2(diff.y, distXZ)));
        player.setYRot(yaw);
        player.setXRot(pitch);
    }

    @Override
    protected void onStop(Task interruptTask) {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone != null && baritone.getCustomGoalProcess().isActive()) {
            baritone.getCustomGoalProcess().path();
        }
    }

    @Override
    public boolean isFinished() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.dimension().toString().toLowerCase().contains("nether")) {
            return true;
        }
        return finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ConstructNetherPortalTask;
    }

    @Override
    protected String toDebugString() {
        return "Construct Nether Portal";
    }
}
