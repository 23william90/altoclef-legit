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
import net.minecraft.world.level.block.Blocks;
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
    private boolean isJumpingForCrit = false;
    private long critJumpStartTime = 0;
    private long postHitBackoffTimer = 0;
    private long lastDamageReceivedTime = 0;
    private long lastFleePathTime = 0;

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
            return 82.0f;
        } else if (isRetreating) {
            boolean hasCloseHostile = false;
            if (mc.level != null) {
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e != player && e.isAlive() && !e.isRemoved() && isHostileMob(e) && canMobPhysicallyReachPlayer(mc, player, e)) {
                        if (e.distanceToSqr(player) < 1024.0) { // within 32 blocks
                            hasCloseHostile = true;
                            break;
                        }
                    }
                }
            }

            if (!hasCloseHostile || player.getHealth() >= 16.0f) {
                isRetreating = false;
            } else {
                return 82.0f;
            }
        }

        // Retaliation: Mob attacked us recently
        LivingEntity hurtBy = player.getLastHurtByMob();
        if (hurtBy != null && hurtBy == currentThreat) {
            return 78.0f;
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
        if (player == null || !player.isAlive()) {
            stopShielding();
            stopApproaching();
            stopFleeing();
            currentThreat = null;
            lockedThreat = null;
            return;
        }

        // Track incoming damage timestamp for instant retaliation retargeting
        if (player.hurtTime > 0) {
            lastDamageReceivedTime = System.currentTimeMillis();
        }

        // Low Health Strategic Retreat: flee from all threats to a safe distance (>= 38m)
        if (isRetreating) {
            handleLowHealthRetreat(mc, player, currentThreat);
            return;
        }

        if (currentThreat == null || !currentThreat.isAlive() || currentThreat.isRemoved()) {
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

        // 3. Melee Combat & Elimination (Zombies, Skeletons, Spiders, Endermen, etc.)
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
                // NO SHIELD AND SWELLING: Sprint away immediately from all threats
                stopShielding();
                fleeFromAllThreats(mc, player);
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

            tryAttack(mc, player, creeper);

            // Backstep while facing creeper to reset its fuse
            if (baritone != null) {
                if (System.currentTimeMillis() < creeperBackoffTimer || distSq < 4.0 || System.currentTimeMillis() < postHitBackoffTimer) {
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

        // Deliver a single quick knockback strike only if mob is touching us (< 2.2 blocks) and weapon is ready
        if (threat instanceof LivingEntity living && isHostileMob(living) && living.isAlive() && !living.isRemoved()) {
            if (threat.distanceToSqr(player) <= 5.0 && player.getAttackStrengthScale(0.0f) >= 0.85f) {
                equipBestWeapon(player);
                lookAtDirect(player, threat.getEyePosition());
                mc.gameMode.attack(player, threat);
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                lastAttackTime = System.currentTimeMillis();
            }
        }

        // Flee away from all nearby threats in an omni-directional safe direction
        fleeFromAllThreats(mc, player);
    }

    private void handleMeleeCombat(Minecraft mc, LocalPlayer player, Entity target, double distSq) {
        if (!(target instanceof LivingEntity living) || !target.isAlive() || target.isRemoved() || !isHostileMob(living)) {
            stopShielding();
            stopApproaching();
            currentThreat = null;
            lockedThreat = null;
            return;
        }

        // Mobs that cannot physically reach the player cannot attack and shouldn't be engaged in melee
        if (!canMobPhysicallyReachPlayer(mc, player, target)) {
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
                tryAttack(mc, player, target);
                return;
            }
        }

        // Case 2: MELEE MOB COMBAT (Zombies, Spiders, Creepers in melee, Endermen, etc.)
        equipBestWeapon(player);

        ItemStack weapon = player.getMainHandItem();
        long weaponCooldown = getWeaponAttackCooldownMs(weapon);
        boolean isAttackReady = player.getAttackStrengthScale(0.0f) >= 0.92f && (System.currentTimeMillis() - lastAttackTime >= weaponCooldown);

        if (distSq <= 16.0) { // In close melee combat (<= 4.0 blocks)
            // CANCEL Baritone pathing so Baritone NEVER fights for camera yaw/pitch!
            if (baritone != null && baritone.getPathingBehavior().isPathing()) {
                baritone.getPathingBehavior().cancelEverything();
            }
            approaching = false;

            // Direct input movement & smooth targeting
            smoothLookAt(player, target.getEyePosition(), 50.0f);

            // Spacing & Hit Avoidance:
            // Backpedal right after swinging or if mob gets too close (< 2.2m)
            boolean isBackpedaling = (System.currentTimeMillis() < postHitBackoffTimer) || (distSq < 4.84);

            if (baritone != null) {
                if (isBackpedaling) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                } else if (distSq > 7.5 && isAttackReady) {
                    // Step forward into strike reach only when weapon is recharged
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                } else {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                }
            }

            // Shielding between swings:
            // While weapon is recharging and mob is close (<= 4m), raise shield to absorb hits!
            boolean hasShield = hasShield(player);
            if (hasShield && !isAttackReady && !isJumpingForCrit && distSq <= 16.0) {
                ensureShieldEquipped(mc, player);
                startShielding();
            } else if (isAttackReady || isJumpingForCrit) {
                // Lower shield when ready to jump & attack
                stopShielding();
            }

            // Attack when within reach (<= 3.5 blocks)
            if (distSq <= 12.25) {
                tryAttack(mc, player, target);
            }
        } else {
            // Farther than 4.0 blocks: path towards mob using Baritone
            stopShielding();
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
            }
            approachTarget(target);
        }
    }

    private void fleeFromAllThreats(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return;
        fleeing = true;

        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) return;

        // Repath periodically or if not pathing
        if (System.currentTimeMillis() - lastFleePathTime < 1200 && baritone.getPathingBehavior().isPathing()) {
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
            return;
        }

        Vec3 pPos = player.position();
        double repulseX = 0;
        double repulseZ = 0;
        int threatCount = 0;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e != player && e.isAlive() && !e.isRemoved() && isHostileMob(e)) {
                double dSq = e.distanceToSqr(player);
                if (dSq < 1024.0) { // within 32 blocks
                    double dist = Math.sqrt(dSq);
                    double weight = 1.0 / Math.max(1.0, dist);
                    double dx = pPos.x - e.getX();
                    double dz = pPos.z - e.getZ();
                    double len = Math.sqrt(dx * dx + dz * dz);
                    if (len > 0.001) {
                        repulseX += (dx / len) * weight;
                        repulseZ += (dz / len) * weight;
                    }
                    threatCount++;
                }
            }
        }

        if (threatCount == 0) {
            isRetreating = false;
            stopFleeing();
            return;
        }

        double repLen = Math.sqrt(repulseX * repulseX + repulseZ * repulseZ);
        if (repLen < 0.01) {
            float yawRad = (float) Math.toRadians(player.getYRot());
            repulseX = -Math.sin(yawRad);
            repulseZ = Math.cos(yawRad);
        } else {
            repulseX /= repLen;
            repulseZ /= repLen;
        }

        // Project a safe destination 38 blocks away (beyond 32m mob aggro/follow range)
        BlockPos safeDestination = findSafeFleeDestination(mc, player, repulseX, repulseZ, 38.0);
        if (safeDestination != null) {
            lastFleePathTime = System.currentTimeMillis();
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(safeDestination, 2));
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
        }
    }

    private BlockPos findSafeFleeDestination(Minecraft mc, LocalPlayer player, double dirX, double dirZ, double distance) {
        if (mc.level == null) return null;
        Vec3 pPos = player.position();

        // Check straight away, then angular offsets (+-20, +-40, +-60, +-80 deg)
        double[] angles = {0, 0.35, -0.35, 0.70, -0.70, 1.05, -1.05, 1.40, -1.40};
        for (double angle : angles) {
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double rx = dirX * cos - dirZ * sin;
            double rz = dirX * sin + dirZ * cos;

            double targetX = pPos.x + rx * distance;
            double targetZ = pPos.z + rz * distance;
            int blockX = (int) Math.floor(targetX);
            int blockZ = (int) Math.floor(targetZ);

            int startY = (int) Math.floor(player.getY());
            for (int dy = 4; dy >= -8; dy--) {
                BlockPos feet = new BlockPos(blockX, startY + dy, blockZ);
                BlockPos ground = feet.below();
                BlockPos head = feet.above();

                if (isSafeGround(mc, ground) && isPassable(mc, feet) && isPassable(mc, head)) {
                    return feet;
                }
            }
        }

        return BlockPos.containing(pPos.x + dirX * distance, player.getY(), pPos.z + dirZ * distance);
    }

    private static boolean isSafeGround(Minecraft mc, BlockPos pos) {
        if (mc.level == null) return false;
        var state = mc.level.getBlockState(pos);
        if (!state.isSolid()) return false;
        if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.CACTUS)) {
            return false;
        }
        return true;
    }

    private static boolean isPassable(Minecraft mc, BlockPos pos) {
        if (mc.level == null) return true;
        var state = mc.level.getBlockState(pos);
        if (state.isAir()) return true;
        if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)) {
            return false;
        }
        return !state.isSolid();
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

    public static long getWeaponAttackCooldownMs(ItemStack weapon) {
        if (weapon == null || weapon.isEmpty()) return 250; // Hand: 4.0 attack speed = 250ms
        String name = InventoryManager.getItemName(weapon).toLowerCase();
        if (name.contains("axe") && !name.contains("pickaxe")) {
            if (name.contains("stone") || name.contains("wooden")) return 1250; // 0.8 attack speed = 1250ms
            if (name.contains("iron")) return 1100; // 0.9 attack speed = 1111ms
            return 1000; // Diamond / Netherite axe: 1.0 attack speed = 1000ms
        }
        if (name.contains("pickaxe")) {
            return 833; // 1.2 attack speed = 833ms
        }
        if (name.contains("shovel")) {
            return 1000; // 1.0 attack speed = 1000ms
        }
        if (name.contains("sword")) {
            return 625; // 1.6 attack speed = 625ms
        }
        return 400;
    }

    private void tryAttack(Minecraft mc, LocalPlayer player, Entity target) {
        if (!(target instanceof LivingEntity living) || !target.isAlive() || target.isRemoved()) {
            return;
        }
        if (target instanceof ItemEntity || target instanceof ExperienceOrb || target instanceof ArmorStand) {
            return;
        }
        if (!canMobPhysicallyReachPlayer(mc, player, target)) {
            return;
        }

        ItemStack weapon = player.getMainHandItem();
        long cooldownMs = getWeaponAttackCooldownMs(weapon);
        boolean cooldownReady = player.getAttackStrengthScale(0.0f) >= 0.92f && (System.currentTimeMillis() - lastAttackTime >= cooldownMs);

        smoothLookAt(player, target.getEyePosition(), 55.0f);

        // Critical Hit Jump State Machine (Hit strictly on descent: vy < -0.04):
        if (isJumpingForCrit) {
            Vec3 vel = player.getDeltaMovement();
            boolean isDescent = !player.onGround() && vel.y < -0.04;
            boolean timedOut = System.currentTimeMillis() - critJumpStartTime > 650;
            boolean landed = player.onGround() && (System.currentTimeMillis() - critJumpStartTime > 200);

            if (isDescent || timedOut || landed) {
                if (player.distanceToSqr(target) <= 14.5) {
                    mc.gameMode.attack(player, target);
                    player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                    lastAttackTime = System.currentTimeMillis();
                    postHitBackoffTimer = System.currentTimeMillis() + 250;
                    if (target instanceof Creeper) {
                        creeperBackoffTimer = System.currentTimeMillis() + 450;
                    }
                }
                isJumpingForCrit = false;
            }
            return;
        }

        // Ready to initiate attack:
        if (cooldownReady) {
            double distSq = player.distanceToSqr(target);
            if (distSq <= 12.25) { // Within 3.5 blocks
                BlockPos pPos = player.blockPosition();
                boolean clearCeiling = mc.level != null && !mc.level.getBlockState(pPos.above(2)).isSolid();

                if (player.onGround() && !player.isInWater() && clearCeiling) {
                    if (shielding) {
                        stopShielding();
                    }
                    player.jumpFromGround();
                    isJumpingForCrit = true;
                    critJumpStartTime = System.currentTimeMillis();
                } else {
                    if (shielding) {
                        stopShielding();
                    }
                    mc.gameMode.attack(player, target);
                    player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                    lastAttackTime = System.currentTimeMillis();
                    postHitBackoffTimer = System.currentTimeMillis() + 250;
                    if (target instanceof Creeper) {
                        creeperBackoffTimer = System.currentTimeMillis() + 450;
                    }
                }
            }
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
     * Intelligent multi-mob threat selection with physical reachability filtering,
     * immediate retaliation on damage, point-blank retargeting, and sniper scanning.
     */
    private Entity findPriorityThreat(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;

        // 1. TOP EMERGENCY: Swelling or ignited creeper within 10 blocks that can reach player
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Creeper creeper && creeper.isAlive() && !creeper.isRemoved()) {
                if (creeper.distanceToSqr(player) < 100.0 && (creeper.getSwellDir() > 0 || creeper.isIgnited())) {
                    if (canMobPhysicallyReachPlayer(mc, player, creeper)) {
                        lockedThreat = creeper;
                        return creeper;
                    }
                }
            }
        }

        // 2. EMERGENCY: Incoming projectile headed straight for player within 24 blocks
        Projectile incoming = findIncomingProjectile(mc, player);
        if (incoming != null) {
            return incoming;
        }

        // 3. IMMEDIATE RETALIATION RETARGETING:
        // If damaged recently by a hostile mob that can reach us, switch target to it instantly!
        LivingEntity hurtBy = player.getLastHurtByMob();
        if (hurtBy != null && hurtBy.isAlive() && hurtBy != player && !hurtBy.isRemoved() && isHostileMob(hurtBy)) {
            if (player.hurtTime > 0 || (System.currentTimeMillis() - lastDamageReceivedTime < 1500)) {
                if (canMobPhysicallyReachPlayer(mc, player, hurtBy) && hurtBy.distanceToSqr(player) < 256.0) {
                    lockedThreat = hurtBy;
                    return lockedThreat;
                }
            }
        }

        // 4. POINT-BLANK PROXIMITY RETARGETING:
        // Any reachable hostile mob right in our face (<= 2.4 blocks) is an immediate threat!
        Entity closestPointBlank = null;
        double closestPointBlankDistSq = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity != player && entity.isAlive() && !entity.isRemoved() && isHostileMob(entity)) {
                if (canMobPhysicallyReachPlayer(mc, player, entity)) {
                    double dSq = entity.distanceToSqr(player);
                    if (dSq < 5.76) { // <= 2.4 blocks
                        if (dSq < closestPointBlankDistSq) {
                            closestPointBlankDistSq = dSq;
                            closestPointBlank = entity;
                        }
                    }
                }
            }
        }

        // If there's a point-blank hostile, switch to it immediately if:
        // - No locked threat, or locked threat is dead/unreachable
        // - Or locked threat is farther away by > 1.2 blocks
        // - Or locked threat is outside strike reach (> 6.25 distSq)
        if (closestPointBlank != null) {
            if (lockedThreat == null || !lockedThreat.isAlive() || lockedThreat.isRemoved()
                    || !canMobPhysicallyReachPlayer(mc, player, lockedThreat)
                    || lockedThreat.distanceToSqr(player) > closestPointBlankDistSq + 1.44
                    || lockedThreat.distanceToSqr(player) > 6.25) {
                lockedThreat = closestPointBlank;
                return lockedThreat;
            }
        }

        // 5. TARGET STICKINESS:
        // Retain current locked threat only if still valid and physically reachable
        if (lockedThreat != null && lockedThreat.isAlive() && !lockedThreat.isRemoved()
                && (isHostileMob(lockedThreat) || lockedThreat instanceof Projectile)) {
            if (canMobPhysicallyReachPlayer(mc, player, lockedThreat)) {
                double currentDistSq = lockedThreat.distanceToSqr(player);
                double maxTrackDistSq = isRangedMob(lockedThreat) ? 576.0 : 196.0;
                if (currentDistSq < maxTrackDistSq) {
                    return lockedThreat;
                }
            }
        }

        lockedThreat = null;

        // 6. Intelligent Multi-Mob Scoring (Filtered by physical reachability!)
        Entity bestThreat = null;
        double bestScore = 0.0;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!entity.isAlive() || entity == player || entity.isRemoved()) continue;
            // STRICT FILTER: NEVER target dropped items, XP orbs, animals, or non-hostiles!
            if (!isHostileMob(entity)) continue;

            // Physical reachability check: ignore mobs trapped behind walls, in caves, or pits!
            if (!canMobPhysicallyReachPlayer(mc, player, entity)) continue;

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

    public static boolean canMobPhysicallyReachPlayer(Minecraft mc, LocalPlayer player, Entity entity) {
        if (mc == null || mc.level == null || player == null || entity == null) return false;
        if (!entity.isAlive() || entity.isRemoved()) return false;

        double dy = entity.getY() - player.getY();
        double dx = entity.getX() - player.getX();
        double dz = entity.getZ() - player.getZ();
        double horizDistSq = dx * dx + dz * dz;

        // 1. Extreme vertical separation (in caves below or roof/sky above):
        if (Math.abs(dy) > 2.5) {
            if (!player.hasLineOfSight(entity)) {
                // If there's no line of sight and vertical distance > 2.5, mob cannot reach in melee
                int startY = (int) Math.floor(Math.min(player.getY(), entity.getY()));
                int endY = (int) Math.ceil(Math.max(player.getY(), entity.getY()));
                int checkX = player.getBlockX();
                int checkZ = player.getBlockZ();
                int solidCount = 0;
                for (int y = startY; y <= endY; y++) {
                    if (mc.level.getBlockState(new BlockPos(checkX, y, checkZ)).isSolid()) {
                        solidCount++;
                    }
                }
                if (solidCount >= 1) {
                    return false;
                }
            }
        }

        // 2. Ranged mobs: require line of sight (they cannot shoot arrows through solid walls)
        if (isRangedMob(entity)) {
            return player.hasLineOfSight(entity);
        }

        // 3. Melee mobs:
        if (player.hasLineOfSight(entity)) {
            return true;
        }

        // If no line of sight, check if completely walled off:
        if (isPathBlockedBySolidBlocks(mc, player.blockPosition(), entity.blockPosition(), player.getY(), entity.getY())) {
            return false;
        }

        return true;
    }

    public static boolean isPathBlockedBySolidBlocks(Minecraft mc, BlockPos pPos, BlockPos ePos, double pY, double eY) {
        if (mc.level == null) return false;

        // If vertical difference is > 2.2 blocks with no line of sight, cannot jump or climb
        if (Math.abs(pY - eY) > 2.2) {
            return true;
        }

        double dx = ePos.getX() - pPos.getX();
        double dz = ePos.getZ() - pPos.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0) return false;

        int steps = (int) Math.ceil(dist * 2.0);
        int solidWallCount = 0;
        int checkY = Math.min(pPos.getY(), ePos.getY());

        for (int i = 1; i < steps; i++) {
            double fraction = (double) i / steps;
            int x = (int) Math.floor(pPos.getX() + dx * fraction);
            int z = (int) Math.floor(pPos.getZ() + dz * fraction);

            BlockPos feet = new BlockPos(x, checkY, z);
            BlockPos head = new BlockPos(x, checkY + 1, z);
            if (mc.level.getBlockState(feet).isSolid() && mc.level.getBlockState(head).isSolid()) {
                solidWallCount++;
                if (solidWallCount >= 2) {
                    return true;
                }
            } else {
                solidWallCount = 0;
            }
        }

        return false;
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
        isJumpingForCrit = false;
    }

    @Override
    protected void onStop() {
        stopShielding();
        stopApproaching();
        stopFleeing();
        lockedThreat = null;
        isRetreating = false;
        isJumpingForCrit = false;
    }

    @Override
    public String getName() {
        return "Mob Defense";
    }
}