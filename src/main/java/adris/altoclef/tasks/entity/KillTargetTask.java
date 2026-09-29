package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.control.RenderDistanceManager;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;

public class KillTargetTask extends Task {

    private final String targetQuery;
    private LivingEntity currentTarget = null;
    private boolean finished = false;
    private long lastAttackTime = 0;
    private boolean shielding = false;
    private boolean approaching = false;
    private boolean isJumpingForCrit = false;
    private long critJumpStartTime = 0;
    private long postHitBackoffTimer = 0;

    public KillTargetTask(String targetQuery) {
        this.targetQuery = targetQuery.trim().toLowerCase();
    }

    @Override
    protected void onStart() {
        finished = false;
        currentTarget = null;
        shielding = false;
        approaching = false;
        setDebugState("Hunting target: " + targetQuery + "...");
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) return null;

        // If target is dead or despawned, finish or find next
        if (currentTarget != null && (!currentTarget.isAlive() || currentTarget.isRemoved())) {
            AltoClef.getInstance().log("Target defeated: " + targetQuery);
            RenderDistanceManager.revert(mc);
            stopShielding();
            finished = true;
            return null;
        }

        // Search for target if not currently tracking
        if (currentTarget == null || !currentTarget.isAlive()) {
            currentTarget = findTarget(mc, player);
            if (currentTarget == null) {
                // Boost render distance while searching for target
                RenderDistanceManager.requestSearchBoost(mc, 20, 60);
                setDebugState("Searching for target: " + targetQuery + "...");
                return null;
            }
        }

        RenderDistanceManager.revert(mc);
        double distSq = player.distanceToSqr(currentTarget);
        double dist = Math.sqrt(distSq);

        setDebugState("Attacking: " + currentTarget.getName().getString() + " (" + String.format("%.1f", dist) + "m)");

        // 1. Equip best weapon
        equipBestWeapon(player);

        // 2. Shield against ranged or heavy counter attacks if we have shield and cooldown allows
        if (dist > 4.0 && dist < 16.0 && hasShield(player)) {
            ItemStack targetItem = currentTarget.getMainHandItem();
            String heldName = InventoryManager.getItemName(targetItem);
            if (heldName.contains("bow") || heldName.contains("crossbow") || heldName.contains("trident")) {
                smoothLookAt(player, currentTarget.getEyePosition(), 45.0f);
                startShielding();
                approachTarget(currentTarget);
                return null;
            }
        }

        stopShielding();

        // 3. Close the distance using Baritone with hysteresis (approach if > 3.6m, stop if <= 3.0m)
        if (approaching) {
            if (dist <= 3.0) {
                stopApproaching();
            }
        } else {
            if (dist > 3.6) {
                approachTarget(currentTarget);
            }
        }

        // 4. Strike target with timed attacks, weapon cooldowns, spacing, and critical descent jumps
        if (dist <= 3.8) {
            smoothLookAt(player, currentTarget.getEyePosition(), 50.0f);
            ItemStack weapon = player.getMainHandItem();
            long cooldownMs = MobDefenseChain.getWeaponAttackCooldownMs(weapon);
            boolean cooldownReady = player.getAttackStrengthScale(0.0f) >= 0.92f && (System.currentTimeMillis() - lastAttackTime >= cooldownMs);

            // Spacing & hit avoidance: step back after hit or if point-blank
            boolean isBackpedaling = (System.currentTimeMillis() < postHitBackoffTimer) || (dist < 2.0);
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                if (isBackpedaling) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                } else if (dist > 2.8 && cooldownReady) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                } else {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                }
            }

            // Crit jump descent state machine
            if (isJumpingForCrit) {
                Vec3 vel = player.getDeltaMovement();
                boolean isDescent = !player.onGround() && vel.y < -0.04;
                boolean timedOut = System.currentTimeMillis() - critJumpStartTime > 650;
                boolean landed = player.onGround() && (System.currentTimeMillis() - critJumpStartTime > 200);

                if (isDescent || timedOut || landed) {
                    if (dist <= 3.8) {
                        mc.gameMode.attack(player, currentTarget);
                        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                        lastAttackTime = System.currentTimeMillis();
                        postHitBackoffTimer = System.currentTimeMillis() + 250;
                    }
                    isJumpingForCrit = false;
                }
            } else if (cooldownReady) {
                if (dist <= 3.5) {
                    var pPos = player.blockPosition();
                    boolean clearCeiling = mc.level != null && !mc.level.getBlockState(pPos.above(2)).isSolid();

                    if (player.onGround() && !player.isInWater() && clearCeiling) {
                        player.jumpFromGround();
                        isJumpingForCrit = true;
                        critJumpStartTime = System.currentTimeMillis();
                    } else {
                        mc.gameMode.attack(player, currentTarget);
                        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                        lastAttackTime = System.currentTimeMillis();
                        postHitBackoffTimer = System.currentTimeMillis() + 250;
                    }
                }
            }
        }

        return null;
    }

    private LivingEntity findTarget(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;

        // 1. Check for exact or partial player name match
        for (Player p : mc.level.players()) {
            if (p == player || !p.isAlive()) continue;
            String name = p.getName().getString().toLowerCase();
            if (targetQuery.equals("@p") || name.equals(targetQuery) || name.contains(targetQuery)) {
                return p;
            }
        }

        // 2. Check for mob type / entity name match
        LivingEntity closestMob = null;
        double closestDistSq = Double.MAX_VALUE;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity living) || !living.isAlive() || living == player) continue;

            String typePath = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()).getPath().toLowerCase();
            String displayName = living.getName().getString().toLowerCase();

            boolean match = false;
            if (targetQuery.equals("hostile") || targetQuery.equals("monster")) {
                match = living instanceof Monster;
            } else if (typePath.contains(targetQuery) || displayName.contains(targetQuery)) {
                match = true;
            }

            if (match) {
                // Must be physically reachable (not trapped behind solid walls or in deep caves)
                if (living instanceof Monster && !MobDefenseChain.canMobPhysicallyReachPlayer(mc, player, living)) {
                    continue;
                }

                double dSq = player.distanceToSqr(living);
                if (dSq < closestDistSq) {
                    closestDistSq = dSq;
                    closestMob = living;
                }
            }
        }

        return closestMob;
    }

    private void approachTarget(Entity target) {
        if (approaching) return;
        approaching = true;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(target.blockPosition(), 2));
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopApproaching() {
        if (!approaching) return;
        approaching = false;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null && baritone.getCustomGoalProcess().isActive()) {
                baritone.getCustomGoalProcess().path();
            }
        } catch (Throwable ignored) {
        }
    }

    private void equipBestWeapon(LocalPlayer player) {
        int bestSlot = -1;
        float bestScore = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String name = InventoryManager.getItemName(stack);
            float score = 0;
            if (name.contains("netherite_sword")) score = 10;
            else if (name.contains("diamond_sword")) score = 9;
            else if (name.contains("iron_sword")) score = 7;
            else if (name.contains("golden_sword")) score = 5;
            else if (name.contains("stone_sword")) score = 5;
            else if (name.contains("wooden_sword")) score = 4;
            else if (name.contains("axe")) score = 6;

            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        if (bestSlot != -1 && player.getInventory().getSelectedSlot() != bestSlot) {
            player.getInventory().setSelectedSlot(bestSlot);
        }
    }

    private boolean hasShield(LocalPlayer player) {
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty() && (off.is(Items.SHIELD) || InventoryManager.getItemName(off).contains("shield"))) return true;
        ItemStack main = player.getMainHandItem();
        return !main.isEmpty() && (main.is(Items.SHIELD) || InventoryManager.getItemName(main).contains("shield"));
    }

    private void startShielding() {
        shielding = true;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopShielding() {
        if (!shielding) return;
        shielding = false;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            }
        } catch (Throwable ignored) {
        }
    }

    private void smoothLookAt(LocalPlayer player, Vec3 targetPos, float maxTurnPerTick) {
        Vec3 eyes = player.getEyePosition();
        Vec3 diff = targetPos.subtract(eyes);
        double diffX = diff.x;
        double diffY = diff.y;
        double diffZ = diff.z;
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float targetYaw = (float) Math.toDegrees(Math.atan2(-diffX, diffZ));
        float targetPitch = (float) Math.toDegrees(-Math.atan2(diffY, diffXZ));

        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        float deltaYaw = wrapDegrees(targetYaw - currentYaw);
        float deltaPitch = targetPitch - currentPitch;

        float absYaw = Math.abs(deltaYaw);
        float absPitch = Math.abs(deltaPitch);

        float stepYaw = Math.min(absYaw * 0.40f + 2.5f, maxTurnPerTick);
        float stepPitch = Math.min(absPitch * 0.40f + 1.8f, maxTurnPerTick * 0.75f);

        float newYaw = absYaw <= stepYaw ? targetYaw : currentYaw + Math.signum(deltaYaw) * stepYaw;
        float newPitch = absPitch <= stepPitch ? targetPitch : currentPitch + Math.signum(deltaPitch) * stepPitch;
        newPitch = Math.max(-90.0f, Math.min(90.0f, newPitch));

        player.setYRot(newYaw);
        player.setXRot(newPitch);
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    @Override
    protected void onStop(Task interruptTask) {
        RenderDistanceManager.revert(Minecraft.getInstance());
        stopShielding();
        stopApproaching();
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof KillTargetTask task) {
            return task.targetQuery.equals(this.targetQuery);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Kill Target: " + targetQuery;
    }
}
