package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.TaskRunner;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

public class MLGBucketFallChain extends SingleTaskChain {

    private boolean placing = false;
    private boolean placed = false;
    private long placedTime = 0;

    public MLGBucketFallChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    public float getPriority() {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !player.isAlive()) return Float.NEGATIVE_INFINITY;

        if (player.isCreative() || player.isSpectator() || player.isFallFlying()) {
            return Float.NEGATIVE_INFINITY;
        }

        // Falling fast from lethal height
        if (player.fallDistance > 3.5f && player.getDeltaMovement().y < -0.65 && !player.isInWater() && !player.onGround()) {
            if (findWaterBucketSlot(player) != -1) {
                return 95.0f;
            }
        }

        if (placing || placed) {
            return 95.0f;
        }

        return Float.NEGATIVE_INFINITY;
    }

    @Override
    protected void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        int bucketSlot = findWaterBucketSlot(player);

        if (!placed) {
            if (bucketSlot != -1) {
                player.getInventory().setSelectedSlot(bucketSlot);
            }
            player.setXRot(90.0f); // Look straight down

            try {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    placing = true;
                }
            } catch (Throwable ignored) {
            }

            if (player.onGround() || player.isInWater()) {
                placed = true;
                placedTime = System.currentTimeMillis();
                try {
                    IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (baritone != null) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                    }
                } catch (Throwable ignored) {
                }
            }
        } else {
            // Collect water back up after landing
            if (System.currentTimeMillis() - placedTime > 250) {
                int emptyBucket = findEmptyBucketSlot(player);
                if (emptyBucket != -1) {
                    player.getInventory().setSelectedSlot(emptyBucket);
                    player.setXRot(90.0f);
                    try {
                        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                        if (baritone != null) {
                            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (System.currentTimeMillis() - placedTime > 600) {
                cleanup();
            }
        }
    }

    private void cleanup() {
        placing = false;
        placed = false;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            }
        } catch (Throwable ignored) {
        }
    }

    private int findWaterBucketSlot(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem().toString().toLowerCase().contains("water_bucket")) {
                return i;
            }
        }
        return -1;
    }

    private int findEmptyBucketSlot(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                String name = stack.getItem().toString().toLowerCase();
                if (name.equals("bucket") || name.endsWith(":bucket")) {
                    return i;
                }
            }
        }
        return -1;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        cleanup();
    }

    @Override
    protected void onStop() {
        cleanup();
    }

    @Override
    public boolean isActive() {
        return placing || placed;
    }

    @Override
    public String getName() {
        return "MLG Water Bucket";
    }
}
