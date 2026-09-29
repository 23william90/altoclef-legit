package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.control.RenderDistanceManager;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.item.component.SwingAnimation;

public class HeroTask extends Task {

    private final AltoClef mod;
    private long lastAttackTime = 0;

    public HeroTask(AltoClef mod) {
        this.mod = mod;
    }

    @Override
    protected void onStart() {
        setDebugState("Initializing Hero Mode (Hunting all hostile mobs)...");
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;

        // 1. Collect nearby experience orbs
        ExperienceOrb nearestOrb = null;
        double nearestOrbDist = Double.MAX_VALUE;
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ExperienceOrb orb && orb.isAlive()) {
                double dist = mc.player.distanceTo(orb);
                if (dist < nearestOrbDist) {
                    nearestOrbDist = dist;
                    nearestOrb = orb;
                }
            }
        }
        if (nearestOrb != null && nearestOrbDist < 8.0) {
            setDebugState("Collecting nearby XP orb...");
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null && !primary.getPathingBehavior().isPathing()) {
                primary.getCustomGoalProcess().setGoalAndPath(new GoalNear(nearestOrb.blockPosition(), 1));
            }
            return null;
        }

        // 2. Find closest hostile mob
        net.minecraft.world.entity.LivingEntity targetMob = null;
        double nearestMobDist = Double.MAX_VALUE;
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if ((e instanceof Monster || e instanceof Slime) && e.isAlive()) {
                double dist = mc.player.distanceTo(e);
                if (dist < nearestMobDist && dist < 48.0) {
                    nearestMobDist = dist;
                    targetMob = (net.minecraft.world.entity.LivingEntity) e;
                }
            }
        }

        if (targetMob != null) {
            RenderDistanceManager.revert(mc);
            setDebugState("Hunting: " + targetMob.getType().getDescription().getString() + " (" + String.format("%.1f", nearestMobDist) + "m)");

            // Move towards mob
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null && (!primary.getPathingBehavior().isPathing() || nearestMobDist > 3.5)) {
                primary.getCustomGoalProcess().setGoalAndPath(new GoalNear(targetMob.blockPosition(), 2));
            }

            // Attack if in melee range
            if (nearestMobDist <= 3.8) {
                var mobEye = targetMob.getEyePosition();
                var playerEye = mc.player.getEyePosition();
                double dx = mobEye.x - playerEye.x;
                double dy = mobEye.y - playerEye.y;
                double dz = mobEye.z - playerEye.z;
                double distXZ = Math.sqrt(dx * dx + dz * dz);
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(Math.atan2(-dy, distXZ));
                mc.player.setYRot(yaw);
                mc.player.setXRot(pitch);

                if (mc.player.getAttackStrengthScale(0.0f) >= 0.9f && System.currentTimeMillis() - lastAttackTime > 500) {
                    if (mc.gameMode != null) {
                        mc.gameMode.attack(mc.player, targetMob);
                        mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                        lastAttackTime = System.currentTimeMillis();
                    }
                }
            }
            return null;
        }

        // Boost render distance while searching for hostile mobs
        RenderDistanceManager.requestSearchBoost(mc, 18, 60);
        setDebugState("Searching for hostile mobs in area...");
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        RenderDistanceManager.revert(Minecraft.getInstance());
        IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (primary != null) {
            primary.getCustomGoalProcess().path();
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof HeroTask;
    }

    @Override
    protected String toDebugString() {
        return "Hero (Kill All Hostiles)";
    }
}
