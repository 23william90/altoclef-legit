package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Modern Reactive Stronghold Triangulation & Navigation Engine:
 * Throws Eyes of Ender, calculates mathematical ray intersection (X, Z),
 * paths directly to the Stronghold, and locates the End Portal Frame.
 */
public class LocateStrongholdTask extends Task {

    private Vec3 throw1Pos = null;
    private Vec3 throw1Dir = null;
    private Vec3 throw2Pos = null;
    private Vec3 throw2Dir = null;
    private BlockPos strongholdTarget = null;
    private EyeOfEnder activeEye = null;
    private int throwCooldown = 0;
    private boolean finished = false;

    @Override
    protected void onStart() {
        throw1Pos = null;
        throw1Dir = null;
        throw2Pos = null;
        throw2Dir = null;
        strongholdTarget = null;
        activeEye = null;
        throwCooldown = 0;
        finished = false;
        setDebugState("Initializing Stronghold Triangulation Engine...");
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return null;

        // 1. Completion Check: Near End Portal Frame in world
        BlockPos portalFrame = findNearbyPortalFrame(mc, player, 24);
        if (portalFrame != null) {
            setDebugState("Located End Portal Frame at " + portalFrame.toShortString() + "!");
            finished = true;
            return null;
        }

        // 2. Scoop up dropped Eyes of Ender on the ground
        ItemEntity droppedEye = findNearbyDroppedEye(mc, player, 16.0);
        if (droppedEye != null) {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(droppedEye.blockPosition(), 1));
            }
            setDebugState("Recovering thrown Eye of Ender...");
            return null;
        }

        // 3. Track active in-flight Eye of Ender trajectory
        if (activeEye != null && activeEye.isAlive() && !activeEye.isRemoved()) {
            Vec3 vel = activeEye.getDeltaMovement();
            if (vel.lengthSqr() > 0.01) {
                Vec3 dir = new Vec3(vel.x, 0, vel.z).normalize();
                if (throw1Dir == null) {
                    throw1Dir = dir;
                    AltoClef.getInstance().log("Recorded Eye Throw #1 trajectory: " + dir);
                } else if (throw2Dir == null && throw2Pos != null) {
                    throw2Dir = dir;
                    AltoClef.getInstance().log("Recorded Eye Throw #2 trajectory: " + dir);

                    // Compute mathematical intersection
                    strongholdTarget = calculateIntersection(throw1Pos, throw1Dir, throw2Pos, throw2Dir);
                    if (strongholdTarget != null) {
                        AltoClef.getInstance().log("Triangulated Stronghold coordinates: X=" + strongholdTarget.getX() + ", Z=" + strongholdTarget.getZ());
                    }
                }
            }
            setDebugState("Tracking in-flight Eye of Ender trajectory...");
            return null;
        }
        activeEye = null;

        // 4. If Stronghold Target is already triangulated: Path directly there!
        if (strongholdTarget != null) {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                double distSq = player.distanceToSqr(Vec3.atCenterOf(strongholdTarget));
                if (distSq > 100.0) {
                    if (!baritone.getPathingBehavior().isPathing()) {
                        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(strongholdTarget, 8));
                    }
                    setDebugState("Pathing to Stronghold at X=" + strongholdTarget.getX() + ", Z=" + strongholdTarget.getZ() + " (" + (int) Math.sqrt(distSq) + "m away)...");
                    return null;
                } else {
                    // Arrived at stronghold coordinates: spiral dig down to locate portal room!
                    setDebugState("At Stronghold coordinates! Locating portal room...");
                    BlockPos pPos = player.blockPosition();
                    for (int y = -30; y <= 10; y++) {
                        BlockPos check = pPos.above(y);
                        if (mc.level.getBlockState(check).is(Blocks.END_PORTAL_FRAME) || mc.level.getBlockState(check).is(Blocks.STONE_BRICKS)) {
                            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(check, 1));
                            return null;
                        }
                    }
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(pPos.atY(32), 1));
                    return null;
                }
            }
        }

        // 5. THROW #1: Initial throw from current location
        if (throw1Pos == null || throw1Dir == null) {
            if (throwCooldown-- > 0) return null;

            int eyeSlot = ensureHeldEye(player);
            if (eyeSlot == -1) {
                setDebugState("Need Eye of Ender to triangulate Stronghold!");
                return null;
            }

            throw1Pos = player.position();
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            activeEye = findNewlySpawnedEye(mc, player);
            throwCooldown = 40;
            setDebugState("Threw Eye of Ender #1. Recording trajectory...");
            return null;
        }

        // 6. OFFSET TRAVEL: Travel 40 blocks perpendicular to Throw #1 trajectory before Throw #2
        if (throw2Pos == null) {
            Vec3 perp = new Vec3(-throw1Dir.z, 0, throw1Dir.x).normalize();
            BlockPos offsetGoal = BlockPos.containing(throw1Pos.add(perp.scale(40.0)));
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                double dSq = player.distanceToSqr(Vec3.atCenterOf(offsetGoal));
                if (dSq > 16.0) {
                    if (!baritone.getPathingBehavior().isPathing()) {
                        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(offsetGoal, 2));
                    }
                    setDebugState("Traveling 40m offset for triangulation angle (" + (int) Math.sqrt(dSq) + "m remaining)...");
                    return null;
                } else {
                    // Arrived at offset location
                    throw2Pos = player.position();
                    throwCooldown = 20;
                }
            }
            return null;
        }

        // 7. THROW #2: Secondary throw from offset location
        if (throw2Dir == null) {
            if (throwCooldown-- > 0) return null;

            int eyeSlot = ensureHeldEye(player);
            if (eyeSlot == -1) {
                setDebugState("Need Eye of Ender for Throw #2!");
                return null;
            }

            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            activeEye = findNewlySpawnedEye(mc, player);
            throwCooldown = 40;
            setDebugState("Threw Eye of Ender #2. Computing intersection...");
            return null;
        }

        return null;
    }

    public static BlockPos calculateIntersection(Vec3 start1, Vec3 dir1, Vec3 start2, Vec3 dir2) {
        if (start1 == null || dir1 == null || start2 == null || dir2 == null) return null;
        double d1x = dir1.x, d1z = dir1.z;
        double d2x = dir2.x, d2z = dir2.z;
        double s1x = start1.x, s1z = start1.z;
        double s2x = start2.x, s2z = start2.z;

        double det = (d1x * d2z) - (d1z * d2x);
        if (Math.abs(det) < 1e-4) return null; // parallel lines

        double t2 = ((d1z * s2x) - (d1z * s1x) - (d1x * s2z) + (d1x * s1z)) / det;
        double targetX = s2x + d2x * t2;
        double targetZ = s2z + d2z * t2;

        return BlockPos.containing(targetX, 0, targetZ);
    }

    private EyeOfEnder findNewlySpawnedEye(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof EyeOfEnder eye && eye.isAlive() && !eye.isRemoved()) {
                if (player.distanceToSqr(eye) < 36.0) {
                    return eye;
                }
            }
        }
        return null;
    }

    private ItemEntity findNearbyDroppedEye(Minecraft mc, LocalPlayer player, double maxDist) {
        if (mc.level == null) return null;
        double maxDistSq = maxDist * maxDist;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ItemEntity item && item.isAlive() && !item.isRemoved()) {
                if (player.distanceToSqr(item) <= maxDistSq) {
                    if (item.getItem().is(Items.ENDER_EYE)) {
                        return item;
                    }
                }
            }
        }
        return null;
    }

    private BlockPos findNearbyPortalFrame(Minecraft mc, LocalPlayer player, int radius) {
        if (mc.level == null) return null;
        BlockPos center = player.blockPosition();
        for (int y = -8; y <= 8; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos p = center.offset(x, y, z);
                    if (mc.level.getBlockState(p).is(Blocks.END_PORTAL_FRAME)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    private int ensureHeldEye(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.ENDER_EYE)) {
                player.getInventory().setSelectedSlot(i);
                return i;
            }
        }
        return -1;
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
        return finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof LocateStrongholdTask;
    }

    @Override
    protected String toDebugString() {
        return "Locate Stronghold (Ray Triangulation)";
    }
}
