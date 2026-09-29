package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.tasksystem.TaskRunner;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;

public class MobDefenseChain extends SingleTaskChain {

    private Entity currentThreat = null;
    private Entity lockedThreat = null;
    private boolean shielding = false;
    private boolean approaching = false;
    private boolean fleeing = false;
    private boolean isRetreating = false;
    private long lastAttackTime = 0;
    private long creeperBackoffTimer = 0;

    public MobDefenseChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public float getPriority() {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !player.isAlive()) return Float.NEGATIVE_INFINITY;

        currentThreat = findPriorityThreat(mc, player);
        if (currentThreat == null) {
            stopShielding();
            stopFleeing();
            stopApproaching();
            isRetreating = false;
            return Float.NEGATIVE_INFINITY;
        }

        double distSq = currentThreat.distanceToSqr(player);

        // Emergency 1: Creeper swelling or ignited (will explode in seconds!)
        if (currentThreat instanceof Creeper creeper) {
            if (creeper.getSwellDir() > 0 || creeper.isIgnited()) {
                return 92.0f;
            }
            if (distSq < 49.0) { // < 7 blocks
                return 75.0f;
            }
            return 68.0f;
        }

        // Emergency 2: Incoming projectile heading straight for us
        if (currentThreat instanceof Projectile) {
            return 82.0f;
        }

        // Emergency 3: Low health with hostile nearby -> Retreat to heal!
        if (player.getHealth() <= 10.0f && distSq < 144.0) {
            isRetreating = true;
            return 78.0f;
        } else if (isRetreating) {
            if (player.getHealth() >= 14.0f || distSq > 225.0) {
                isRetreating = false;
            } else {
                return 78.0f;
            }
        }

        // Retaliation: Mob attacked us recently
        LivingEntity hurtBy = player.getLastHurtByMob();
        if (hurtBy != null && hurtBy == currentThreat) {
            return 70.0f;
        }

        // Close melee threat (< 4 blocks)
        if (distSq < 16.0) {
            return 66.0f;
        }

        // Hostile closing in (< 10 blocks)
        if (distSq < 100.0) {
            return 58.0f;
        }

        return 52.0f;
    }

    @Override
    protected void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || currentThreat == null || !currentThreat.isAlive() || currentThreat.isRemoved()) {
            stopShielding();
            stopApproaching();
            stopFleeing();
            currentThreat = null;
            lockedThreat = null;
            return;
        }

        double distSq = currentThreat.distanceToSqr(player);

        // 1. Creeper Defense & Smart Evasion
        if (currentThreat instanceof Creeper creeper) {
            handleCreeperDefense(mc, player, creeper, distSq);
            return;
        }

        // 2. Projectile Defense (Arrows, Fireballs)
        if (currentThreat instanceof Projectile projectile) {
            handleProjectileDefense(mc, player, projectile);
            return;
        }

        // 3. Low Health Strategic Retreat (Give space for FoodChain to eat & heal)
        if (isRetreating && distSq < 144.0) {
            handleLowHealthRetreat(mc, player, currentThreat);
            return;
        }

        // 4. Melee Combat & Elimination (Zombies, Skeletons, Spiders, Endermen, etc.)
        handleMeleeCombat(mc, player, currentThreat, distSq);
    }

    private void handleCreeperDefense(Minecraft mc, LocalPlayer player, Creeper creeper, double distSq) {
        boolean isSwelling = creeper.getSwellDir() > 0 || creeper.isIgnited();
        boolean hasShield = hasShield(player);

        if (isSwelling) {
            if (hasShield) {
                // If we have a shield, face the creeper and block the explosion!
                stopFleeing();
                stopApproaching();
                ensureShieldEquipped(mc, player);
                smoothLookAt(player, creeper.getEyePosition(), 45.0f);
                startShielding();
                return;
            } else {
                // NO SHIELD AND SWELLING: SPRINT AWAY IMMEDIATELY!
                stopShielding();
                fleeFromEntity(mc, player, creeper, 16.0);
                return;
            }
        }

        // Creeper not swelling yet
        stopShielding();

        // Always face the creeper smoothly (never snap camera 180 degrees away)
        smoothLookAt(player, creeper.getEyePosition(), 35.0f);

        if (distSq > 11.0) { // > 3.3 blocks away
            stopFleeing();
            equipBestWeapon(player);
            approachTarget(creeper);
        } else { // In melee strike reach (<= 3.3 blocks)
            stopApproaching();
            equipBestWeapon(player);

            // Hit creeper on attack cooldown
            if (player.getAttackStrengthScale(0.0f) >= 0.85f && System.currentTimeMillis() - lastAttackTime > 450) {
                mc.gameMode.attack(player, creeper);
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                lastAttackTime = System.currentTimeMillis();
                creeperBackoffTimer = System.currentTimeMillis() + 350;
            }

            // Immediately backstep while keeping eyes locked on creeper to keep fuse reset
            if (System.currentTimeMillis() < creeperBackoffTimer) {
                try {
                    IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (baritone != null) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                    }
                } catch (Throwable ignored) {
                }
            } else {
                try {
                    IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (baritone != null) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void handleProjectileDefense(Minecraft mc, LocalPlayer player, Projectile projectile) {
        if (hasShield(player)) {
            ensureShieldEquipped(mc, player);
            Entity owner = projectile.getOwner();
            if (owner != null && owner.isAlive()) {
                smoothLookAt(player, owner.getEyePosition(), 45.0f);
            } else {
                smoothLookAt(player, projectile.position(), 45.0f);
            }
            startShielding();
        } else {
            // Dodge: strafe sideways relative to projectile flight path
            Vec3 vel = projectile.getDeltaMovement().normalize();
            Vec3 side = new Vec3(-vel.z, 0, vel.x).normalize().scale(3.5);
            BlockPos dodgePos = BlockPos.containing(player.position().add(side));
            try {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(dodgePos, 1));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void handleLowHealthRetreat(Minecraft mc, LocalPlayer player, Entity threat) {
        stopShielding();

        // If hostile is in immediate face (< 2.2 blocks) while retreating, defensive strike to knock them back
        if (threat.distanceToSqr(player) <= 5.5 && player.getAttackStrengthScale(0.0f) >= 0.75f) {
            equipBestWeapon(player);
            smoothLookAt(player, threat.getEyePosition(), 45.0f);
            mc.gameMode.attack(player, threat);
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        }

        fleeFromEntity(mc, player, threat, 20.0);
    }

    private void handleMeleeCombat(Minecraft mc, LocalPlayer player, Entity target, double distSq) {
        stopFleeing();

        // Skeleton drawing bow at distance: shield up while advancing
        if (target instanceof AbstractSkeleton skeleton && distSq < 144.0 && distSq > 16.0) {
            if (hasShield(player)) {
                ensureShieldEquipped(mc, player);
                smoothLookAt(player, skeleton.getEyePosition(), 35.0f);
                startShielding();
                approachTarget(target);
                return;
            }
        }

        stopShielding();
        equipBestWeapon(player);
        smoothLookAt(player, target.getEyePosition(), 35.0f);

        // Pursue enemy
        if (distSq > 9.0) {
            approachTarget(target);
        } else {
            stopApproaching();
        }

        // Strike when in reach (< 3.8 blocks)
        if (distSq <= 16.0) {
            tryAttack(mc, player, target);
        }
    }

    private void fleeFromEntity(Minecraft mc, LocalPlayer player, Entity threat, double targetDistance) {
        fleeing = true;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                Vec3 threatPos = threat.position();
                Vec3 playerPos = player.position();
                Vec3 awayDir = playerPos.subtract(threatPos).normalize();
                if (awayDir.lengthSqr() < 0.001) {
                    awayDir = new Vec3(1, 0, 0);
                }

                BlockPos fleeTarget = BlockPos.containing(playerPos.add(awayDir.scale(targetDistance)));
                if (mc.level != null && mc.level.getBlockState(fleeTarget).isSolid()) {
                    fleeTarget = fleeTarget.above();
                }

                if (!baritone.getCustomGoalProcess().isActive() || !baritone.getPathingBehavior().isPathing()) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(fleeTarget, 2));
                }

                // Sprint forward away from threat via Baritone pathing
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopFleeing() {
        if (!fleeing) return;
        fleeing = false;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            }
        } catch (Throwable ignored) {
        }
    }

    private void approachTarget(Entity target) {
        approaching = true;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                if (!baritone.getCustomGoalProcess().isActive() || !baritone.getPathingBehavior().isPathing()) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(target.blockPosition(), 1));
                }
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopApproaching() {
        if (!approaching) return;
        approaching = false;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
                if (baritone.getCustomGoalProcess().isActive()) {
                    baritone.getCustomGoalProcess().path();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void tryAttack(Minecraft mc, LocalPlayer player, Entity target) {
        if (player.getAttackStrengthScale(0.0f) >= 0.85f && System.currentTimeMillis() - lastAttackTime > 400) {
            smoothLookAt(player, target.getEyePosition(), 45.0f);

            // Jump critical if moving forward on ground
            if (player.onGround() && !player.isInWater()) {
                player.jumpFromGround();
            }

            mc.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            lastAttackTime = System.currentTimeMillis();
        }
    }

    private void startShielding() {
        shielding = true;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                if (baritone.getPathingBehavior().isPathing()) {
                    baritone.getPathingBehavior().forceCancel();
                }
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

    private void ensureShieldEquipped(Minecraft mc, LocalPlayer player) {
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty() && (off.is(Items.SHIELD) || InventoryManager.getItemName(off).contains("shield"))) {
            return;
        }

        // Swap shield into offhand slot (40 in inventoryMenu)
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && (stack.is(Items.SHIELD) || InventoryManager.getItemName(stack).contains("shield"))) {
                int slotId = i < 9 ? i + 36 : i;
                if (mc.gameMode != null) {
                    mc.gameMode.handleContainerInput(0, slotId, InventoryMenu.SHIELD_SLOT, ContainerInput.SWAP, player);
                }
                return;
            }
        }
    }

    private boolean hasShield(LocalPlayer player) {
        if (player == null) return false;
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty() && (off.is(Items.SHIELD) || InventoryManager.getItemName(off).contains("shield"))) return true;
        ItemStack main = player.getMainHandItem();
        if (!main.isEmpty() && (main.is(Items.SHIELD) || InventoryManager.getItemName(main).contains("shield"))) return true;
        return InventoryManager.countItems(player, "shield") > 0;
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

    /**
     * Intelligent multi-mob threat selection with hysteresis and emergency overrides.
     * Prevents rapid camera oscillation when multiple hostiles surround the player.
     */
    private Entity findPriorityThreat(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;

        // 1. TOP EMERGENCY: Swelling or ignited creeper within 10 blocks (takes priority over everything!)
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Creeper creeper && creeper.isAlive() && !creeper.isRemoved()) {
                if (creeper.distanceToSqr(player) < 100.0 && (creeper.getSwellDir() > 0 || creeper.isIgnited())) {
                    lockedThreat = creeper;
                    return creeper;
                }
            }
        }

        // 2. EMERGENCY: Incoming projectile headed straight for us within 8 blocks
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Projectile projectile && projectile.isAlive() && !projectile.isRemoved()) {
                double distSq = projectile.distanceToSqr(player);
                if (distSq < 64.0) {
                    Vec3 velocity = projectile.getDeltaMovement();
                    Vec3 toPlayer = player.position().subtract(projectile.position()).normalize();
                    if (velocity.dot(toPlayer) > 0.4) {
                        return projectile;
                    }
                }
            }
        }

        // 3. TARGET STICKINESS: Check if current locked threat is still valid
        if (lockedThreat != null && lockedThreat.isAlive() && !lockedThreat.isRemoved()) {
            double currentDistSq = lockedThreat.distanceToSqr(player);
            // If target is within 14 blocks, stick to it unless another mob is in point-blank melee (< 2.2 blocks)
            if (currentDistSq < 196.0) {
                Entity pointBlankThreat = null;
                for (Entity entity : mc.level.entitiesForRendering()) {
                    if (entity != lockedThreat && entity instanceof Monster monster && monster.isAlive() && !monster.isRemoved()) {
                        double dSq = monster.distanceToSqr(player);
                        if (dSq < 5.0 && currentDistSq > 16.0) { // < 2.2 blocks vs > 4.0 blocks
                            pointBlankThreat = monster;
                            break;
                        }
                    }
                }

                if (pointBlankThreat != null) {
                    lockedThreat = pointBlankThreat;
                    return lockedThreat;
                }

                return lockedThreat;
            }
        }

        // Current target is invalid, dead, or out of range. Pick the best new threat!
        lockedThreat = null;

        // 4. Retaliation: if damaged recently by a mob within 12 blocks, target them
        LivingEntity hurtBy = player.getLastHurtByMob();
        if (hurtBy != null && hurtBy.isAlive() && hurtBy != player && !hurtBy.isRemoved()) {
            if (hurtBy.distanceToSqr(player) < 144.0) {
                lockedThreat = hurtBy;
                return lockedThreat;
            }
        }

        // 5. Intelligent Multi-Mob Scoring
        Entity bestThreat = null;
        double bestScore = -1000.0;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!entity.isAlive() || entity == player || entity.isRemoved()) continue;

            double distSq = entity.distanceToSqr(player);
            if (distSq > 196.0) continue; // max 14 blocks

            double dist = Math.sqrt(distSq);
            double score = 0;

            if (entity instanceof Creeper creeper) {
                score = 90.0 - dist * 3.5;
            } else if (entity instanceof Enderman enderman) {
                if (enderman.isCreepy() || enderman.hasBeenStaredAt()) {
                    score = 80.0 - dist * 3.0;
                }
            } else if (entity instanceof AbstractSkeleton) {
                score = 75.0 - dist * 2.5;
            } else if (entity instanceof Monster) {
                score = 70.0 - dist * 3.0;
            }

            if (score > bestScore) {
                bestScore = score;
                bestThreat = entity;
            }
        }

        lockedThreat = bestThreat;
        return lockedThreat;
    }

    /**
     * Smoothly rotates the camera towards the target position without instantaneous snapping or jitter.
     * Synchronizes with Baritone's LookBehavior to prevent rotational conflicts.
     */
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

        // Smooth ease-out rotational curve
        float stepYaw = Math.min(absYaw * 0.40f + 2.5f, maxTurnPerTick);
        float stepPitch = Math.min(absPitch * 0.40f + 1.8f, maxTurnPerTick * 0.75f);

        float newYaw = absYaw <= stepYaw ? targetYaw : currentYaw + Math.signum(deltaYaw) * stepYaw;
        float newPitch = absPitch <= stepPitch ? targetPitch : currentPitch + Math.signum(deltaPitch) * stepPitch;
        newPitch = Math.max(-90.0f, Math.min(90.0f, newPitch));

        player.setYRot(newYaw);
        player.setXRot(newPitch);

        // Synchronize with Baritone's LookBehavior so Baritone does not fight player rotation
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getLookBehavior().updateTarget(new Rotation(newYaw, newPitch), true);
            }
        } catch (Throwable ignored) {
        }
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        stopShielding();
        stopApproaching();
        stopFleeing();
        lockedThreat = null;
        isRetreating = false;
    }

    @Override
    protected void onStop() {
        stopShielding();
        stopApproaching();
        stopFleeing();
        lockedThreat = null;
        isRetreating = false;
    }

    @Override
    public String getName() {
        return "Mob Defense";
    }
}