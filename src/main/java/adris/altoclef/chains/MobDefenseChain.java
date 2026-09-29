package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.tasksystem.TaskRunner;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
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
    private long dodgeTimer = 0;

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
        }

        // Emergency 2: Incoming projectile heading straight for us
        if (currentThreat instanceof Projectile) {
            return 85.0f;
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

        // Check if user has an active task or bot is navigating/traveling
        AltoClef mod = AltoClef.getInstance();
        boolean hasUserTask = (mod != null && mod.getUserTaskChain() != null && mod.getUserTaskChain().isActive());
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        boolean isTraveling = (baritone != null && (baritone.getPathingBehavior().isPathing() || baritone.getCustomGoalProcess().isActive()));

        // If the bot needs to move far or has an active user task:
        // Prioritize running away / sprinting past mobs that won't bother it over stopping to fight them!
        if (hasUserTask || isTraveling) {
            // Only interrupt if mob is point-blank in our face (<= 3.5m)
            if (distSq <= 12.25) {
                if (currentThreat instanceof Creeper) {
                    return 75.0f;
                }
                return 66.0f;
            }

            // Distant mob (> 3.5m) while traveling: ignore and sprint past!
            stopShielding();
            stopApproaching();
            stopFleeing();
            return Float.NEGATIVE_INFINITY;
        }

        // Idle / No User Task defense thresholds:
        if (currentThreat instanceof Creeper) {
            if (distSq < 49.0) { // < 7 blocks
                return 75.0f;
            }
            return 68.0f;
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

        // Clean up completed dodge strafe
        if (dodgeTimer > 0 && System.currentTimeMillis() > dodgeTimer) {
            dodgeTimer = 0;
            try {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
                }
            } catch (Throwable ignored) {
            }
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
                // Shield blocks 100% of creeper blast: stand ground and block
                stopFleeing();
                stopApproaching();
                ensureShieldEquipped(mc, player);
                smoothLookAt(player, creeper.getEyePosition(), 50.0f);
                startShielding();
                return;
            } else {
                // NO SHIELD AND SWELLING: Sprint away immediately
                stopShielding();
                fleeFromEntity(mc, player, creeper, 16.0);
                return;
            }
        }

        // Creeper not swelling yet
        stopShielding();

        // Movement with hysteresis: approach if > 3.6m, stop if <= 3.0m
        if (approaching) {
            if (distSq <= 9.0) {
                stopApproaching();
            }
        } else {
            if (distSq > 13.0) {
                stopFleeing();
                equipBestWeapon(player);
                approachTarget(creeper);
            }
        }

        // When in strike reach (<= 3.5 blocks)
        if (distSq <= 12.25) {
            smoothLookAt(player, creeper.getEyePosition(), 40.0f);
            equipBestWeapon(player);

            // Hit creeper on attack cooldown
            if (player.getAttackStrengthScale(0.0f) >= 0.85f && System.currentTimeMillis() - lastAttackTime > 450) {
                mc.gameMode.attack(player, creeper);
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                lastAttackTime = System.currentTimeMillis();
                creeperBackoffTimer = System.currentTimeMillis() + 350;
            }

            // Backstep while facing creeper to reset its fuse
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
            stopFleeing();
            stopApproaching();
            ensureShieldEquipped(mc, player);
            Entity owner = projectile.getOwner();
            if (owner != null && owner.isAlive()) {
                smoothLookAt(player, owner.getEyePosition(), 60.0f);
            } else {
                smoothLookAt(player, projectile.position(), 60.0f);
            }
            startShielding();
        } else {
            // Unshielded: instantly strafe sideways to dodge the arrow
            stopShielding();
            try {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, true);
                    baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
                    dodgeTimer = System.currentTimeMillis() + 350;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void handleLowHealthRetreat(Minecraft mc, LocalPlayer player, Entity threat) {
        stopShielding();

        // If hostile is point-blank (< 2.2 blocks) while retreating, defensive strike to push away
        if (threat.distanceToSqr(player) <= 5.0 && player.getAttackStrengthScale(0.0f) >= 0.75f) {
            equipBestWeapon(player);
            smoothLookAt(player, threat.getEyePosition(), 50.0f);
            mc.gameMode.attack(player, threat);
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        }

        fleeFromEntity(mc, player, threat, 20.0);
    }

    private void handleMeleeCombat(Minecraft mc, LocalPlayer player, Entity target, double distSq) {
        stopFleeing();

        // Skeleton aiming bow at distance: shield up while advancing
        if (target instanceof AbstractSkeleton skeleton && distSq < 196.0 && distSq > 9.0) {
            if (hasShield(player) && (skeleton.isAggressive() || skeleton.isUsingItem())) {
                ensureShieldEquipped(mc, player);
                smoothLookAt(player, skeleton.getEyePosition(), 45.0f);
                startShielding();
                approachTarget(target);
                return;
            }
        }

        stopShielding();
        equipBestWeapon(player);

        // Pursue with hysteresis: approach if > 3.6m, stop if <= 3.0m
        if (approaching) {
            if (distSq <= 9.0) {
                stopApproaching();
            }
        } else {
            if (distSq > 13.0) {
                approachTarget(target);
            }
        }

        // Aim and attack when within reach (<= 3.8 blocks)
        if (distSq <= 14.5) {
            smoothLookAt(player, target.getEyePosition(), 40.0f);
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
                baritone.getPathingBehavior().cancelEverything();
            }
        } catch (Throwable ignored) {
        }
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
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
                baritone.getPathingBehavior().cancelEverything();
            }
        } catch (Throwable ignored) {
        }
    }

    private void tryAttack(Minecraft mc, LocalPlayer player, Entity target) {
        if (player.getAttackStrengthScale(0.0f) >= 0.85f && System.currentTimeMillis() - lastAttackTime > 400) {
            smoothLookAt(player, target.getEyePosition(), 45.0f);

            // Jump critical if on ground
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
     * Intelligent multi-mob threat selection with hysteresis, point-blank retargeting,
     * and trajectory-based projectile detection.
     */
    private Entity findPriorityThreat(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;

        // 1. TOP EMERGENCY: Swelling or ignited creeper within 10 blocks
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Creeper creeper && creeper.isAlive() && !creeper.isRemoved()) {
                if (creeper.distanceToSqr(player) < 100.0 && (creeper.getSwellDir() > 0 || creeper.isIgnited())) {
                    lockedThreat = creeper;
                    return creeper;
                }
            }
        }

        // 2. EMERGENCY: Incoming projectile headed straight for player within 24 blocks
        Projectile incoming = findIncomingProjectile(mc, player);
        if (incoming != null) {
            return incoming;
        }

        // 3. TARGET STICKINESS with POINT-BLANK RETARGETING:
        if (lockedThreat != null && lockedThreat.isAlive() && !lockedThreat.isRemoved()) {
            double currentDistSq = lockedThreat.distanceToSqr(player);
            if (currentDistSq < 196.0) {
                // If another hostile is point-blank (< 2.8 blocks) and current target is > 3.8 blocks away:
                // Retarget immediately to eliminate the point-blank attacker!
                Entity pointBlankThreat = null;
                double closestPointBlankDistSq = Double.MAX_VALUE;

                for (Entity entity : mc.level.entitiesForRendering()) {
                    if (entity != lockedThreat && entity instanceof Monster monster && monster.isAlive() && !monster.isRemoved()) {
                        double dSq = monster.distanceToSqr(player);
                        if (dSq < 8.0 && dSq < closestPointBlankDistSq) {
                            closestPointBlankDistSq = dSq;
                            pointBlankThreat = monster;
                        }
                    }
                }

                if (pointBlankThreat != null && currentDistSq > 14.5) {
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

            if (entity instanceof Creeper) {
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

    private Projectile findIncomingProjectile(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;
        Vec3 playerEye = player.getEyePosition();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Projectile projectile && projectile.isAlive() && !projectile.isRemoved()) {
                double distSq = projectile.distanceToSqr(player);
                if (distSq > 576.0 || distSq < 0.2) continue; // max 24 blocks

                Vec3 vel = projectile.getDeltaMovement();
                if (vel.lengthSqr() < 0.05) continue; // stationary or stuck arrow

                Vec3 toPlayer = playerEye.subtract(projectile.position());
                double dot = vel.dot(toPlayer);
                if (dot <= 0) continue; // flying away

                double velSq = vel.lengthSqr();
                double t = dot / velSq;
                if (t > 0 && t < 35.0) { // arriving within 35 ticks
                    Vec3 closestPoint = projectile.position().add(vel.scale(t));
                    if (closestPoint.distanceTo(playerEye) < 1.8) {
                        return projectile;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Smoothly rotates the camera towards the target position without instantaneous snapping or jitter.
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