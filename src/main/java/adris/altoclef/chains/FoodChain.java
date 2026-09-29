package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.TaskRunner;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import adris.altoclef.control.InventoryManager;
import net.minecraft.world.item.ItemStack;

public class FoodChain extends SingleTaskChain {

    private boolean eating = false;
    private long eatStartTime = 0;

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

        boolean needsFood = foodLevel <= 15 || (health < 16.0f && foodLevel < 20);
        if (!needsFood && !eating) return Float.NEGATIVE_INFINITY;

        int foodSlot = findFoodSlot(player);
        if (foodSlot == -1) return Float.NEGATIVE_INFINITY;

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
        if (player == null) return;

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

        startEating();

        // Safety timeout (eating takes ~1.6 seconds, max 4s)
        if (eating && System.currentTimeMillis() - eatStartTime > 4000) {
            stopEating();
        }
    }

    private void startEating() {
        if (eating) return;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                eating = true;
                eatStartTime = System.currentTimeMillis();
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopEating() {
        if (!eating) return;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                eating = false;
            }
        } catch (Throwable ignored) {
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
