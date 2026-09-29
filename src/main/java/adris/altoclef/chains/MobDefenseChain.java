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
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
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
    private long lastZigZagTime = 0;
    private boolean zigZagLeft = false;

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
            return 88.0f;
        }

        // Emergency 3: Ranged mob aiming at us or attacking us: cannot be ignored even while traveling!
        if (isRangedMob(currentThreat) && (isRangedMobAiming(currentThreat) || isMobTargetingPlayer(currentThreat, player) || distSq < 144.0)) {
            return 80.0f;
        }

        // Emergency 4: Low health with hostile nearby -> Retreat to heal!
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
            return 75.0f;
        }

        // Check if user has an active task or bot is navigating/traveling
        AltoClef mod = AltoClef.getInstance();
        boolean hasUserTask = (mod != null && mod.getUserTaskChain() != null && mod.getUserTaskChain().isActive());
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        boolean isTraveling = (baritone != null && (baritone.getPathingBehavior().isPathing() || baritone.getCustomGoalProcess().isActive()));

        // If the bot needs to move far or has an active user task:
        // Prioritize running away / sprinting past mobs that won't bother it over stopping to fight them!
        if (hasUserTask || isTraveling) {
            // Only interrupt for melee if mob is point-blank in our face (<= 3.5m)
            if (distSq <= 12.25) {
                if (currentThreat instanceof Creeper) {
                    return 75.0f;
                }
                return 66.0f;
            }

            // Distant melee mob (> 3.5m) while traveling: ignore and sprint past!
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

        // Ranged mob idle
        if (isRangedMob(currentThreat)) {
            return 72.0f;
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
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

        if (isSwelling) {
            if (hasShield) {
                // Shield blocks 100% of creeper blast: stand ground and block
                stopFleeing();
                stopApproaching();
                if (baritone != null && baritone.getPathingBehavior().isPathing()) {
                    baritone.getPathingBehavior().cancelEverything();
                }
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

        if (distSq <= 16.0) { // Within 4 blocks of creeper
            if (baritone != null && baritone.getPathingBehavior().isPathing()) {
                baritone.getPathingBehavior().cancelEverything();
            }
            approaching = false;

            smoothLookAt(player, creeper.getEyePosition(), 45.0f);
            equipBestWeapon(player);

            // Hit creeper on attack cooldown
            if (player.getAttackStrengthScale(0.0f) >= 0.85f && System.currentTimeMillis() - lastAttackTime > 450) {
                mc.gameMode.attack(player, creeper);
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                lastAttackTime = System.currentTimeMillis();
                creeperBackoffTimer = System.currentTimeMillis() + 400;
            }

            // Backstep while facing creeper to reset its fuse
            if (baritone != null) {
                if (System.currentTimeMillis() < creeperBackoffTimer || distSq < 4.0) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                    baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
                } else if (distSq > 9.0) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                } else {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                }
            }
        } else {
            // Farther than 4 blocks: approach with Baritone without fighting camera
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
            }
            approachTarget(creeper);
        }
    }

    private void handleProjectileDefense(Minecraft mc, LocalPlayer player, Projectile projectile) {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone != null && baritone.getPathingBehavior().isPathing()) {
            baritone.getPathingBehavior().cancelEverything();
        }

        if (hasShield(player)) {
            stopFleeing();
            stopApproaching();
            ensureShieldEquipped(mc, player);
            lookAtDirect(player, projectile.position());
            startShielding();
        } else {
            // Unshielded: Calculate perpendicular vector to dodge arrow trajectory
            stopShielding();
            try {
                if (baritone != null) {
                    Vec3 vel = projectile.getDeltaMovement();
                    Vec3 toArrow = projectile.position().subtract(player.position());
                    // 2D cross product in XZ plane: (vel.x * toArrow.z - vel.z * toArrow.x)
                    double cross = vel.x * toArrow.z - vel.z * toArrow.x;
                    boolean dodgeRight = cross > 0;

                    // Check if block on chosen side is solid; if so, flip dodge direction
                    BlockPos pPos = player.blockPosition();
                    if (dodgeRight && mc.level != null && mc.level.getBlockState(pPos.east()).isSolid()) {
                        dodgeRight = false;
                    } else if (!dodgeRight && mc.level != null && mc.level.getBlockState(pPos.west()).isSolid()) {
                        dodgeRight = true;
                    }

                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, !dodgeRight);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, dodgeRight);
                    baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
                    if (player.onGround()) {
                        player.jumpFromGround();
                    }
                    dodgeTimer = System.currentTimeMillis() + 350;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void handleLowHealthRetreat(Minecraft mc, LocalPlayer player, Entity threat) {
        stopShielding();

        // Deliver a single quick knockback strike only if mob is touching us (< 2.2 blocks)
        if (threat instanceof LivingEntity living && isHostileMob(living) && living.isAlive() && !living.isRemoved()) {
            if (threat.distanceToSqr(player) <= 5.0 && player.getAttackStrengthScale(0.0f) >= 0.85f) {
                equipBestWeapon(player);
                lookAtDirect(player, threat.getEyePosition());
                mc.gameMode.attack(player, threat);
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            }
        }

        // Flee away without calling smoothLookAt every tick so Baritone faces the escape route!
        fleeFromEntity(mc, player, threat, 20.0);
    }

    private void handleMeleeCombat(Minecraft mc, LocalPlayer player, Entity target, double distSq) {
        if (!(target instanceof LivingEntity living) || !target.isAlive() || target.isRemoved() || !isHostileMob(living)) {
            stopShielding();
            stopApproaching();
            currentThreat = null;
            lockedThreat = null;
            return;
        }

        stopFleeing();
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

        boolean ranged = isRangedMob(target);

        // Case 1: RANGED MOB COMBAT (Skeletons, Pillagers, Bow/Crossbow/Trident wielders)
        if (ranged) {
            boolean aiming = isRangedMobAiming(target);
            boolean hasShield = hasShield(player);

            if (distSq > 12.25) { // Farther than melee reach (> 3.5m)
                if (hasShield) {
                    if (aiming) {
                        // Skeleton drawing bow: raise shield and march forward!
                        ensureShieldEquipped(mc, player);
                        if (baritone != null && baritone.getPathingBehavior().isPathing()) {
                            baritone.getPathingBehavior().cancelEverything();
                        }
                        smoothLookAt(player, target.getEyePosition(), 55.0f);
                        startShielding();
                        // Advance towards skeleton with shield raised
                        if (baritone != null) {
                            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                        }
                        return;
                    } else {
                        // Skeleton not aiming: lower shield and sprint towards it to close distance fast
                        stopShielding();
                        equipBestWeapon(player);
                        approachTarget(target);
                        return;
                    }
                } else {
                    // UNSHIELDED RANGED COMBAT: Zigzag approach to dodge incoming arrows!
                    stopShielding();
                    equipBestWeapon(player);

                    if (baritone != null && baritone.getPathingBehavior().isPathing()) {
                        baritone.getPathingBehavior().cancelEverything();
                    }
                    smoothLookAt(player, target.getEyePosition(), 45.0f);

                    if (System.currentTimeMillis() - lastZigZagTime > 300) {
                        lastZigZagTime = System.currentTimeMillis();
                        zigZagLeft = !zigZagLeft;
                    }

                    if (baritone != null) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
                        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, zigZagLeft);
                        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, !zigZagLeft);
                    }

                    if (aiming && player.onGround()) {
                        player.jumpFromGround();
                    }
                    return;
                }
            } else {
                // WITHIN MELEE STRIKE RANGE (<= 3.5 blocks) against ranged mob
                stopShielding();
                if (baritone != null) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
                    if (baritone.getPathingBehavior().isPathing()) {
                        baritone.getPathingBehavior().cancelEverything();
                    }
                }
                equipBestWeapon(player);
                smoothLookAt(player, target.getEyePosition(), 50.0f);
                tryAttack(mc, player, target);
                return;
            }
        }

        // Case 2: MELEE MOB COMBAT (Zombies, Spiders, Creepers in melee, Endermen, etc.)
        stopShielding();
        equipBestWeapon(player);

        if (distSq <= 16.0) { // In close melee combat (<= 4.0 blocks)
            // CANCEL Baritone pathing so Baritone NEVER fights for camera yaw/pitch!
            if (baritone != null && baritone.getPathingBehavior().isPathing()) {
                baritone.getPathingBehavior().cancelEverything();
            }
            approaching = false;

            // Direct input movement & smooth targeting
            smoothLookAt(player, target.getEyePosition(), 45.0f);

            if (baritone != null) {
                if (distSq > 8.0) { // 2.8m - 4.0m: walk forward into strike reach
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                } else if (distSq < 3.5) { // < 1.85m: back up slightly for spacing
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                } else { // 1.85m - 2.8m: optimal melee strike distance
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                }
            }

            // Attack when within reach (<= 3.8 blocks)
            if (distSq <= 14.5) {
                tryAttack(mc, player, target);
            }
        } else {
            // Farther than 4.0 blocks: path towards mob using Baritone
            // DO NOT call smoothLookAt while Baritone is pathing from distance (prevents camera jitter)!
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
            }
            approachTarget(target);
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
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
                baritone.getPathingBehavior().cancelEverything();
            }
        } catch (Throwable ignored) {
        }
    }

    private void lookAtDirect(LocalPlayer player, Vec3 targetPos) {
        Vec3 diff = targetPos.subtract(player.getEyePosition());
        double diffX = diff.x;
        double diffY = diff.y;
        double diffZ = diff.z;
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float targetYaw = (float) Math.toDegrees(Math.atan2(-diffX, diffZ));
        float targetPitch = (float) Math.toDegrees(-Math.atan2(diffY, diffXZ));

        player.setYRot(targetYaw);
        player.setXRot(Math.max(-90.0f, Math.min(90.0f, targetPitch)));
    }

    private void tryAttack(Minecraft mc, LocalPlayer player, Entity target) {
        if (!(target instanceof LivingEntity living) || !target.isAlive() || target.isRemoved()) {
            return;
        }
        if (target instanceof ItemEntity || target instanceof ExperienceOrb || target instanceof ArmorStand) {
            return;
        }
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

    public static boolean isHostileMob(Entity entity) {
        if (entity == null || !entity.isAlive() || entity.isRemoved()) return false;
        if (!(entity instanceof LivingEntity)) return false;
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb || entity instanceof ArmorStand) return false;

        if (entity instanceof Creeper) return true;
        if (entity instanceof AbstractSkeleton) return true;
        if (entity instanceof Enderman enderman) {
            return enderman.isCreepy() || enderman.hasBeenStaredAt();
        }
        if (entity instanceof Monster) return true;
        if (entity instanceof Slime) return true;
        if (entity instanceof Ghast) return true;
        if (entity instanceof Hoglin) return true;
        if (entity instanceof Zoglin) return true;
        if (entity instanceof Shulker) return true;
        if (entity instanceof EnderDragon) return true;
        if (entity instanceof WitherBoss) return true;

        if (entity instanceof Mob mob) {
            Entity target = mob.getTarget();
            if (target instanceof LocalPlayer) return true;
        }

        return false;
    }

    public static boolean isRangedMob(Entity entity) {
        if (entity == null || !entity.isAlive() || entity.isRemoved()) return false;
        if (entity instanceof AbstractSkeleton) return true;
        if (entity instanceof Ghast || entity instanceof Shulker) return true;
        if (entity instanceof LivingEntity living) {
            ItemStack main = living.getMainHandItem();
            if (!main.isEmpty()) {
                if (main.is(Items.BOW) || main.is(Items.CROSSBOW) || main.is(Items.TRIDENT)) return true;
                String name = InventoryManager.getItemName(main);
                if (name.contains("bow") || name.contains("crossbow") || name.contains("trident")) return true;
            }
        }
        String typeName = entity.getType().getDescription().getString().toLowerCase();
        return typeName.contains("skeleton") || typeName.contains("blaze") || typeName.contains("witch") ||
                typeName.contains("pillager") || typeName.contains("stray") || typeName.contains("bogged") ||
                typeName.contains("ghast");
    }

    public static boolean isRangedMobAiming(Entity entity) {
        if (!isRangedMob(entity)) return false;
        if (entity instanceof LivingEntity living && living.isUsingItem()) {
            return true;
        }
        if (entity instanceof Mob mob && mob.isAggressive()) {
            return true;
        }
        return false;
    }

    public static boolean isMobTargetingPlayer(Entity entity, LocalPlayer player) {
        if (entity instanceof Mob mob) {
            return mob.getTarget() == player;
        }
        return false;
    }

    /**
     * Intelligent multi-mob threat selection with hysteresis, point-blank retargeting,
     * ranged sniper scanning (up to 24 blocks), and trajectory-based projectile detection.
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
        if (lockedThreat != null && lockedThreat.isAlive() && !lockedThreat.isRemoved() && (isHostileMob(lockedThreat) || lockedThreat instanceof Projectile)) {
            double currentDistSq = lockedThreat.distanceToSqr(player);
            double maxTrackDistSq = isRangedMob(lockedThreat) ? 576.0 : 196.0;
            if (currentDistSq < maxTrackDistSq) {
                // If another hostile is point-blank (< 2.8 blocks) and current target is > 3.8 blocks away:
                // Retarget immediately to eliminate the point-blank attacker!
                Entity pointBlankThreat = null;
                double closestPointBlankDistSq = Double.MAX_VALUE;

                for (Entity entity : mc.level.entitiesForRendering()) {
                    if (entity != lockedThreat && isHostileMob(entity)) {
                        double dSq = entity.distanceToSqr(player);
                        if (dSq < 8.0 && dSq < closestPointBlankDistSq) {
                            closestPointBlankDistSq = dSq;
                            pointBlankThreat = entity;
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

        // 4. Retaliation: if damaged recently by a hostile mob within 16 blocks, target them
        LivingEntity hurtBy = player.getLastHurtByMob();
        if (hurtBy != null && hurtBy.isAlive() && hurtBy != player && !hurtBy.isRemoved() && isHostileMob(hurtBy)) {
            if (hurtBy.distanceToSqr(player) < 256.0) {
                lockedThreat = hurtBy;
                return lockedThreat;
            }
        }

        // 5. Intelligent Multi-Mob Scoring
        Entity bestThreat = null;
        double bestScore = 0.0; // Threat score MUST be positive (> 0.0)!

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!entity.isAlive() || entity == player || entity.isRemoved()) continue;
            // STRICT FILTER: NEVER target dropped items, XP orbs, animals, or non-hostiles!
            if (!isHostileMob(entity)) continue;

            double distSq = entity.distanceToSqr(player);
            boolean ranged = isRangedMob(entity);
            double maxDistSq = ranged ? 576.0 : 196.0; // 24m for ranged, 14m for melee
            if (distSq > maxDistSq) continue;

            double dist = Math.sqrt(distSq);
            double score;

            if (entity instanceof Creeper) {
                score = 90.0 - dist * 3.5;
            } else if (ranged) {
                if (isRangedMobAiming(entity) || isMobTargetingPlayer(entity, player)) {
                    score = 92.0 - dist * 2.0; // Prioritize dangerous ranged snipers up to 24m!
                } else {
                    score = 72.0 - dist * 2.5;
                }
            } else if (entity instanceof Enderman enderman) {
                score = 85.0 - dist * 3.0;
            } else if (entity instanceof AbstractSkeleton) {
                score = 75.0 - dist * 2.5;
            } else if (entity instanceof Monster) {
                score = 70.0 - dist * 3.0;
            } else {
                score = 60.0 - dist * 3.0;
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