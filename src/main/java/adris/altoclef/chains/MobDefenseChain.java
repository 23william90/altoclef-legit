package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.tasksystem.TaskChain;
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
    private boolean shielding = false;
    private boolean approaching = false;
    private boolean fleeing = false;
    private long lastAttackTime = 0;

    public MobDefenseChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    public boolean isActive() {
        // Continuously active to evaluate threats in the world
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

        // Emergency 3: Low health (< 10 HP / 5 hearts) with hostile nearby -> Must retreat to heal!
        if (player.getHealth() <= 10.0f && distSq < 144.0) {
            return 78.0f;
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
        if (player.getHealth() <= 10.0f && distSq < 144.0) {
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
                lookAt(player, creeper.getEyePosition());
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

        if (distSq > 16.0) { // > 4 blocks away
            if (player.getHealth() > 10.0f) {
                stopFleeing();
                equipBestWeapon(player);
                approachTarget(creeper);
            } else {
                fleeFromEntity(mc, player, creeper, 14.0);
            }
        } else { // In melee strike reach (<= 4 blocks)
            stopFleeing();
            equipBestWeapon(player);
            lookAt(player, creeper.getEyePosition());

            // Hit creeper on attack cooldown
            if (player.getAttackStrengthScale(0.0f) >= 0.85f && System.currentTimeMillis() - lastAttackTime > 450) {
                mc.gameMode.attack(player, creeper);
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                lastAttackTime = System.currentTimeMillis();
            }

            // Immediately backstep / strafe away to keep the fuse from detonating
            fleeFromEntity(mc, player, creeper, 6.0);
        }
    }

    private void handleProjectileDefense(Minecraft mc, LocalPlayer player, Projectile projectile) {
        if (hasShield(player)) {
            ensureShieldEquipped(mc, player);
            Entity owner = projectile.getOwner();
            if (owner != null && owner.isAlive()) {
                lookAt(player, owner.getEyePosition());
            } else {
                lookAt(player, projectile.position());
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
        fleeFromEntity(mc, player, threat, 20.0);
    }

    private void handleMeleeCombat(Minecraft mc, LocalPlayer player, Entity target, double distSq) {
        stopFleeing();

        // Skeleton drawing bow at distance: shield up while advancing
        if (target instanceof AbstractSkeleton skeleton && distSq < 144.0 && distSq > 16.0) {
            if (hasShield(player)) {
                ensureShieldEquipped(mc, player);
                lookAt(player, skeleton.getEyePosition());
                startShielding();
                approachTarget(target);
                return;
            }
        }

        stopShielding();
        equipBestWeapon(player);
        lookAt(player, target.getEyePosition());

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

                // Sprint forward away from threat
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);

                lookAt(player, Vec3.atCenterOf(fleeTarget));
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
            if (baritone != null && baritone.getCustomGoalProcess().isActive()) {
                baritone.getCustomGoalProcess().path();
            }
        } catch (Throwable ignored) {
        }
    }

    private void tryAttack(Minecraft mc, LocalPlayer player, Entity target) {
        if (player.getAttackStrengthScale(0.0f) >= 0.85f && System.currentTimeMillis() - lastAttackTime > 400) {
            lookAt(player, target.getEyePosition());

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

    private Entity findPriorityThreat(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;

        // 0. Did an entity hurt us recently? Retaliate immediately!
        LivingEntity hurtBy = player.getLastHurtByMob();
        if (hurtBy != null && hurtBy.isAlive() && hurtBy != player && !hurtBy.isRemoved()) {
            double d = hurtBy.distanceToSqr(player);
            if (d < 144.0) {
                return hurtBy;
            }
        }

        Entity bestThreat = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!entity.isAlive() || entity == player || entity.isRemoved()) continue;

            double distSq = entity.distanceToSqr(player);
            if (distSq > 196.0) continue; // max 14 blocks

            // 1. Creeper check (high priority)
            if (entity instanceof Creeper creeper) {
                if (creeper.getSwellDir() > 0 || creeper.isIgnited()) {
                    return creeper; // immediate emergency!
                }
                if (distSq < 64.0 && distSq < bestDistSq) {
                    bestThreat = creeper;
                    bestDistSq = distSq;
                }
                continue;
            }

            // 2. Incoming projectile check
            if (entity instanceof Projectile projectile) {
                Vec3 velocity = projectile.getDeltaMovement();
                Vec3 toPlayer = player.position().subtract(projectile.position()).normalize();
                if (velocity.dot(toPlayer) > 0.4 && distSq < 100.0) {
                    return projectile;
                }
                continue;
            }

            // 3. Aggressive Enderman check
            if (entity instanceof Enderman enderman) {
                if ((enderman.isCreepy() || enderman.hasBeenStaredAt()) && distSq < 144.0) {
                    if (distSq < bestDistSq) {
                        bestThreat = enderman;
                        bestDistSq = distSq;
                    }
                }
                continue;
            }

            // 4. Hostile Monsters (Zombies, Skeletons, Spiders, Slimes, Phantoms, Piglins, etc.)
            if (entity instanceof Monster monster) {
                if (distSq < 100.0 && distSq < bestDistSq) {
                    bestThreat = monster;
                    bestDistSq = distSq;
                }
            }
        }

        return bestThreat;
    }

    private void lookAt(LocalPlayer player, Vec3 target) {
        Vec3 diff = target.subtract(player.getEyePosition());
        double diffX = diff.x;
        double diffY = diff.y;
        double diffZ = diff.z;
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float yaw = (float) Math.toDegrees(Math.atan2(-diffX, diffZ));
        float pitch = (float) Math.toDegrees(-Math.atan2(diffY, diffXZ));

        player.setYRot(yaw);
        player.setXRot(pitch);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        stopShielding();
        stopApproaching();
        stopFleeing();
    }

    @Override
    protected void onStop() {
        stopShielding();
        stopApproaching();
        stopFleeing();
    }

    @Override
    public String getName() {
        return "Mob Defense";
    }
}