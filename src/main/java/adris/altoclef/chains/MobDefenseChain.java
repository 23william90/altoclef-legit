package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.TaskRunner;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.projectile.Projectile;
import adris.altoclef.control.InventoryManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;

public class MobDefenseChain extends SingleTaskChain {

    private Entity currentThreat = null;
    private boolean shielding = false;
    private boolean approaching = false;
    private long lastAttackTime = 0;

    public MobDefenseChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    public float getPriority() {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !player.isAlive()) return Float.NEGATIVE_INFINITY;

        currentThreat = findPriorityThreat(mc, player);
        if (currentThreat == null) return Float.NEGATIVE_INFINITY;

        double distSq = currentThreat.distanceToSqr(player);

        // Emergency Creeper explosion or projectile incoming
        if (currentThreat instanceof Creeper creeper) {
            if (creeper.getSwellDir() > 0 || creeper.isIgnited() || distSq < 16.0) {
                return 85.0f;
            }
            return 70.0f;
        }

        if (currentThreat instanceof Projectile) {
            return 80.0f;
        }

        // Close melee threat
        if (distSq < 16.0) { // < 4 blocks
            return 65.0f;
        }

        return 58.0f;
    }

    @Override
    protected void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || currentThreat == null || !currentThreat.isAlive()) {
            stopShielding();
            stopApproaching();
            currentThreat = null;
            return;
        }

        double distSq = currentThreat.distanceToSqr(player);

        // 1. Creeper defense
        if (currentThreat instanceof Creeper creeper) {
            handleCreeperDefense(mc, player, creeper, distSq);
            return;
        }

        // 2. Projectile defense
        if (currentThreat instanceof Projectile projectile) {
            handleProjectileDefense(mc, player, projectile);
            return;
        }

        // 3. Melee combat (Zombies, Skeletons, Spiders, aggressive Endermen)
        handleMeleeCombat(mc, player, currentThreat, distSq);
    }

    private void handleCreeperDefense(Minecraft mc, LocalPlayer player, Creeper creeper, double distSq) {
        lookAt(player, creeper.position().add(0, creeper.getEyeHeight(), 0));

        // If swelling close by, raise shield to absorb explosion
        if ((creeper.getSwellDir() > 0 || creeper.isIgnited()) && distSq < 25.0) {
            if (hasShield(player)) {
                startShielding(player);
                return;
            }
        }

        stopShielding();

        // Evade: back away
        if (distSq < 36.0) { // < 6 blocks
            try {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                    baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
                }
            } catch (Throwable ignored) {
            }
        }

        // If safe distance and armed, hit creeper
        if (distSq <= 16.0 && creeper.getSwellDir() <= 0) {
            equipBestWeapon(player);
            tryAttack(mc, player, creeper);
        }
    }

    private void handleProjectileDefense(Minecraft mc, LocalPlayer player, Projectile projectile) {
        if (hasShield(player)) {
            lookAt(player, projectile.position());
            startShielding(player);
        }
    }

    private void handleMeleeCombat(Minecraft mc, LocalPlayer player, Entity target, double distSq) {
        // If Skeleton is drawing bow from range, raise shield briefly
        if (target instanceof AbstractSkeleton && distSq < 100.0 && distSq > 16.0) {
            if (hasShield(player) && System.currentTimeMillis() - lastAttackTime > 600) {
                lookAt(player, target.position().add(0, target.getEyeHeight(), 0));
                startShielding(player);
                approachTarget(target);
                return;
            }
        }

        stopShielding();
        equipBestWeapon(player);
        lookAt(player, target.position().add(0, target.getEyeHeight() * 0.75, 0));

        // Actively pursue and close the distance towards the enemy!
        if (distSq > 9.0) {
            approachTarget(target);
        } else {
            stopApproaching();
        }

        if (distSq <= 16.0) { // in reach ~4 blocks
            tryAttack(mc, player, target);
        }
    }

    private void approachTarget(Entity target) {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                approaching = true;
                if (!baritone.getCustomGoalProcess().isActive() || !baritone.getPathingBehavior().isPathing()) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(target.blockPosition(), 1));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopApproaching() {
        if (!approaching) return;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null && baritone.getCustomGoalProcess().isActive()) {
                baritone.getCustomGoalProcess().path();
            }
            approaching = false;
        } catch (Throwable ignored) {
        }
    }

    private void tryAttack(Minecraft mc, LocalPlayer player, Entity target) {
        if (player.getAttackStrengthScale(0.0f) >= 0.85f) {
            lookAt(player, target.position().add(0, target.getEyeHeight() * 0.75, 0));
            mc.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            lastAttackTime = System.currentTimeMillis();
        }
    }

    private void startShielding(LocalPlayer player) {
        if (shielding) return;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                shielding = true;
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopShielding() {
        if (!shielding) return;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                shielding = false;
            }
        } catch (Throwable ignored) {
        }
    }

    private Entity findPriorityThreat(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;

        Entity bestThreat = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!entity.isAlive() || entity == player) continue;

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
                if (velocity.dot(toPlayer) > 0.5 && distSq < 100.0) {
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

            // 4. General hostile monster (Zombies, Skeletons, Spiders, Slimes, etc.) within 10 blocks
            if (entity instanceof Monster monster) {
                if (distSq < 100.0 && distSq < bestDistSq) {
                    bestThreat = monster;
                    bestDistSq = distSq;
                }
            }
        }

        return bestThreat;
    }

    private boolean hasShield(LocalPlayer player) {
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty() && (off.is(Items.SHIELD) || InventoryManager.getItemName(off).contains("shield"))) {
            return true;
        }
        ItemStack main = player.getMainHandItem();
        return !main.isEmpty() && (main.is(Items.SHIELD) || InventoryManager.getItemName(main).contains("shield"));
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

    private void lookAt(LocalPlayer player, Vec3 target) {
        Vec3 diff = target.subtract(player.getEyePosition());
        double diffX = diff.x;
        double diffY = diff.y;
        double diffZ = diff.z;
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float yaw = (float) Math.toDegrees(Math.atan2(-diffX, diffZ));
        float pitch = (float) Math.toDegrees(-Math.atan2(diffY, diffXZ));

        AltoClef mod = AltoClef.getInstance();
        if (mod != null && mod.getModSettings().isLegitMovement()) {
            float speed = mod.getModSettings().getLegitRotationSpeed();
            player.setYRot(interpolateAngle(player.getYRot(), yaw, speed));
            player.setXRot(interpolateAngle(player.getXRot(), pitch, speed));
        } else {
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
    }

    private float interpolateAngle(float current, float target, float maxDelta) {
        float delta = (target - current) % 360;
        if (delta > 180) delta -= 360;
        if (delta < -180) delta += 360;
        if (Math.abs(delta) <= maxDelta) return target;
        return current + Math.signum(delta) * maxDelta;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        stopShielding();
        stopApproaching();
    }

    @Override
    protected void onStop() {
        stopShielding();
        stopApproaching();
    }

    @Override
    public boolean isActive() {
        return currentThreat != null && currentThreat.isAlive();
    }

    @Override
    public String getName() {
        return "Mob Defense";
    }
}