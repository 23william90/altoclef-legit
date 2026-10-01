package adris.altoclef.control;

import net.minecraft.client.Minecraft;

/**
 * Ensures a healthy, stable render distance for searching resources (e.g. at least 14 chunks)
 * WITHOUT constantly flapping or resetting render distance at runtime, which triggers Minecraft chunk reloads
 * and cancels Baritone pathfinding.
 */
public class RenderDistanceManager {

    private static boolean initialized = false;

    /**
     * Ensures render distance is at least the target distance once to allow detecting distant ores and trees.
     */
    public static void requestSearchBoost(Minecraft mc, int boostedDistance, int durationTicks) {
        if (mc == null || mc.options == null) return;
        try {
            int current = mc.options.renderDistance().get();
            int minDistance = Math.max(14, Math.min(boostedDistance, 20));
            if (!initialized && current < minDistance) {
                mc.options.renderDistance().set(minDistance);
                initialized = true;
            }
        } catch (Throwable ignored) {
        }
    }

    public static void tick(Minecraft mc) {
        // Kept for API compatibility; no-op to prevent chunk rebuild stutter
    }

    public static void revert(Minecraft mc) {
        // No-op to prevent flapping chunk reloads and Baritone path cancellations
    }

    public static void forceRevert(Minecraft mc) {
        // No-op
    }

    public static boolean isBoosted() {
        return false;
    }
}
