package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.tasksystem.TaskRunner;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class FoodChain extends SingleTaskChain {

    private boolean eating = false;
    private long eatStartTime = 0;
    private boolean hasSavedAim = false;
    private float savedYaw = 0.0f;
    private float savedPitch = 0.0f;

    public FoodChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    public float getPriority() {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !player.isAlive()) return Float.NEGATIVE_INFINITY;

        int foodLevel = player.getFoodData().getFoodLevel();
        float health = player.getHealth();

        // Check for nearby hostile threats
        boolean hostileNearby = false;
        double closestHostileDistSq = Double.MAX_VALUE;
        if (mc.level != null) {
            for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                if (e != player && e.isAlive() && !e.isRemoved() && MobDefenseChain.isHostileMob(e)) {
                    double d = e.distanceToSqr(player);
                    if (d < 256.0) { // within 16 blocks
                        hostileNearby = true;
                        if (d < closestHostileDistSq) {
                            closestHostileDistSq = d;
                        }
                    }
                }
            }
        }

        // If hostile is nearby, DO NOT eat unless critical health (<= 6.0 hp) AND at reasonable spacing (> 5m)
        if (hostileNearby) {
            if (health > 6.0f) {
                if (eating) stopEating();
                return Float.NEGATIVE_INFINITY;
            }
            if (closestHostileDistSq <= 25.0) { // enemy within 5 blocks
                if (eating) stopEating();
                return Float.NEGATIVE_INFINITY;
            }
        }

        // Don't interrupt active crafting or smelting containers unless critical health or starving!
        if (player.containerMenu instanceof CraftingMenu || player.containerMenu instanceof AbstractFurnaceMenu) {
            if (health > 6.0f && foodLevel > 3) {
                if (eating) stopEating();
                return Float.NEGATIVE_INFINITY;
            }
        }

        boolean needsFood = foodLevel <= 15 || (health < 16.0f && foodLevel < 20);
        if (!needsFood && !eating) return Float.NEGATIVE_INFINITY;

        int foodSlot = findFoodSlot(player);
        if (foodSlot == -1) {
            if (eating) stopEating();
            return Float.NEGATIVE_INFINITY;
        }

        // Hysteresis: If currently in the middle of eating, hold priority to finish the bite!
        if (eating) {
            return 76.0f; // Above normal defense (66-72) to finish current bite without stuttering!
        }

        if (health <= 8.0f) {
            return 62.0f; // Critical heal
        }
        if (foodLevel <= 12) {
            return 56.0f; // Starving
        }
        return 53.0f; // Normal hunger top-up
    }

    @Override
    protected void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        // 1. If an unexpected container screen is open, close it immediately and turn away!
        boolean screenOpen = mc.gui != null && mc.gui.screen() != null;
        if (player.containerMenu != player.inventoryMenu || screenOpen) {
            stopEating();
            player.closeContainer();
            if (mc.gui != null) {
                mc.gui.setScreen(null);
            }
            ensureSafeEatingAim(mc, player);
            return;
        }

        int foodLevel = player.getFoodData().getFoodLevel();
        float health = player.getHealth();

        if (foodLevel >= 20 && health >= 20.0f) {
            stopEating();
            return;
        }

        int foodSlot = findFoodSlot(player);
        if (foodSlot == -1) {
            stopEating();
            return;
        }

        // Select food in hotbar
        if (player.getInventory().getSelectedSlot() != foodSlot) {
            player.getInventory().setSelectedSlot(foodSlot);
        }

        // 2. Ensure crosshair is NOT aiming at an interactable block/entity before right-clicking
        if (!ensureSafeEatingAim(mc, player)) {
            // Camera was just rotated to safe angle, wait 1 tick for raycast to register
            return;
        }

        // 3. Aim is safe! Start/continue eating
        startEating(mc, player);

        // 4. Safety timeout (eating takes ~1.6 seconds, max 4s)
        if (eating && System.currentTimeMillis() - eatStartTime > 4000) {
            stopEating();
        }
    }

    private void startEating(Minecraft mc, LocalPlayer player) {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
            if (mc.gameMode != null && !player.isUsingItem()) {
                mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            }
            if (!eating) {
                eating = true;
                eatStartTime = System.currentTimeMillis();
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopEating() {
        if (!eating && !hasSavedAim) return;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            }
        } catch (Throwable ignored) {
        }
        eating = false;
        eatStartTime = 0;

        // Restore saved orientation if we looked away to eat
        Minecraft mc = Minecraft.getInstance();
        if (hasSavedAim && mc.player != null) {
            mc.player.setYRot(savedYaw);
            mc.player.setXRot(savedPitch);
            try {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null) {
                    baritone.getLookBehavior().updateTarget(new Rotation(savedYaw, savedPitch), false);
                }
            } catch (Throwable ignored) {
            }
            hasSavedAim = false;
        }
    }

    private boolean isCrosshairInteractable(Minecraft mc, LocalPlayer player) {
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            if (mc.level != null) {
                BlockState state = mc.level.getBlockState(bhr.getBlockPos());
                if (isInteractableBlock(state)) {
                    return true;
                }
            }
        } else if (hit instanceof EntityHitResult ehr && hit.getType() == HitResult.Type.ENTITY) {
            if (isInteractableEntity(ehr.getEntity())) {
                return true;
            }
        }
        return false;
    }

    private boolean ensureSafeEatingAim(Minecraft mc, LocalPlayer player) {
        if (!isCrosshairInteractable(mc, player)) {
            return true;
        }

        // Release right click immediately while adjusting aim to prevent interacting
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            }
        } catch (Throwable ignored) {
        }

        if (!hasSavedAim) {
            savedYaw = player.getYRot();
            savedPitch = player.getXRot();
            hasSavedAim = true;
        }

        float currentYaw = player.getYRot();

        // 1. Try looking up (-85 degrees towards sky/ceiling)
        if (isLookSafe(mc, player, currentYaw, -85.0f)) {
            setLook(player, currentYaw, -85.0f);
            return false;
        }

        // 2. Try looking down (85 degrees towards ground)
        if (isLookSafe(mc, player, currentYaw, 85.0f)) {
            setLook(player, currentYaw, 85.0f);
            return false;
        }

        // 3. Try horizontal offsets at pitch 0
        for (float offset : new float[]{90.0f, -90.0f, 180.0f}) {
            if (isLookSafe(mc, player, currentYaw + offset, 0.0f)) {
                setLook(player, currentYaw + offset, 0.0f);
                return false;
            }
        }

        // Fallback: look straight up
        setLook(player, currentYaw, -85.0f);
        return false;
    }

    private void setLook(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setXRot(pitch);
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getLookBehavior().updateTarget(new Rotation(yaw, pitch), true);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isLookSafe(Minecraft mc, LocalPlayer player, float yaw, float pitch) {
        if (mc.level == null || player == null) return true;
        Vec3 eyePos = player.getEyePosition();
        float f = pitch * ((float)Math.PI / 180F);
        float f1 = -yaw * ((float)Math.PI / 180F);
        float f2 = Mth.cos(f1);
        float f3 = Mth.sin(f1);
        float f4 = Mth.cos(f);
        float f5 = Mth.sin(f);
        Vec3 lookVec = new Vec3(f3 * f4, -f5, f2 * f4);
        Vec3 reachVec = eyePos.add(lookVec.scale(4.5));

        ClipContext ctx = new ClipContext(eyePos, reachVec, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
        BlockHitResult bhr = mc.level.clip(ctx);
        if (bhr.getType() == HitResult.Type.BLOCK) {
            BlockState state = mc.level.getBlockState(bhr.getBlockPos());
            return !isInteractableBlock(state);
        }
        return true; // MISS is completely safe (air)
    }

    public static boolean isInteractableBlock(BlockState state) {
        if (state == null || state.isAir()) return false;
        Block block = state.getBlock();
        return block instanceof CraftingTableBlock
                || block instanceof ChestBlock
                || block instanceof EnderChestBlock
                || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof AbstractFurnaceBlock
                || block instanceof HopperBlock
                || block instanceof DispenserBlock
                || block instanceof DropperBlock
                || block instanceof AnvilBlock
                || block instanceof EnchantingTableBlock
                || block instanceof BrewingStandBlock
                || block instanceof BeaconBlock
                || block instanceof LoomBlock
                || block instanceof CartographyTableBlock
                || block instanceof GrindstoneBlock
                || block instanceof SmithingTableBlock
                || block instanceof StonecutterBlock
                || block instanceof BedBlock
                || block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock
                || block instanceof LeverBlock
                || block instanceof ButtonBlock
                || block instanceof RepeaterBlock
                || block instanceof ComparatorBlock
                || block instanceof BellBlock
                || block instanceof RespawnAnchorBlock
                || block instanceof NoteBlock
                || block instanceof JukeboxBlock
                || block instanceof LecternBlock;
    }

    public static boolean isInteractableEntity(Entity entity) {
        if (entity == null || !entity.isAlive()) return false;
        if (entity instanceof ArmorStand || entity instanceof ItemFrame) return true;
        try {
            String typeName = entity.getType().getDescriptionId().toLowerCase();
            return typeName.contains("villager")
                    || typeName.contains("boat")
                    || typeName.contains("minecart")
                    || typeName.contains("merchant")
                    || typeName.contains("horse")
                    || typeName.contains("donkey")
                    || typeName.contains("mule")
                    || typeName.contains("llama");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int findFoodSlot(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && isEdible(stack)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isEdible(ItemStack stack) {
        try {
            if (stack.has(DataComponents.FOOD)) {
                // Avoid dangerous foods like rotten flesh, spider eyes, poisonous potato unless starving
                String name = InventoryManager.getItemName(stack);
                if (name.contains("rotten_flesh") || name.contains("spider_eye") || name.contains("poisonous_potato") || name.contains("pufferfish")) {
                    return false;
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public boolean isTryingToEat() {
        return eating;
    }

    public boolean needsToEat() {
        if (!AltoClef.inGame()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        int foodLevel = mc.player.getFoodData().getFoodLevel();
        float health = mc.player.getHealth();
        return foodLevel <= 15 || (health < 16.0f && foodLevel < 20);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        stopEating();
    }

    @Override
    protected void onStop() {
        stopEating();
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String getName() {
        return "Auto Eat & Hunger";
    }
}
