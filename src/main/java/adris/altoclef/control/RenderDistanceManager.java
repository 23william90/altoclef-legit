package adris.altoclef.control;

import net.minecraft.client.Minecraft;

/**
 * Manages dynamic render distance scaling:
 * Boosts render distance temporarily when searching for dropped items, ores, mobs, or players,
 * and automatically reverts to lower chunk values to preserve CPU/memory during normal gameplay.
 */
public class RenderDistanceManager {

    private static int baseRenderDistance = -1;
    private static boolean isBoosted = false;
    private static long boostExpireTimestamp = 0;

    /**
     * Temporarily boosts render distance while searching for dropped items, ores, mobs, or players.
     */
    public static void requestSearchBoost(Minecraft mc, int boostedDistance, int durationTicks) {
        if (mc == null || mc.options == null) return;
        try {
            if (baseRenderDistance == -1) {
                baseRenderDistance = mc.options.renderDistance().get();
                if (baseRenderDistance <= 0 || baseRenderDistance > 12) {
                    baseRenderDistance = 10;
                }
            }
            int targetBoost = Math.max(14, Math.min(boostedDistance, 24));
            if (mc.options.renderDistance().get() != targetBoost) {
                mc.options.renderDistance().set(targetBoost);
            }
            isBoosted = true;
            boostExpireTimestamp = System.currentTimeMillis() + (durationTicks * 50L);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Ticks the manager, auto-reverting when boost expires.
     */
    public static void tick(Minecraft mc) {
        if (!isBoosted || mc == null || mc.options == null) return;
        if (System.currentTimeMillis() > boostExpireTimestamp) {
            revert(mc);
        }
    }

    /**
     * Reverts render distance immediately back to base value when target is found or task finishes.
     */
    public static void revert(Minecraft mc) {
        if (mc == null || mc.options == null) return;
        try {
            if (baseRenderDistance != -1 && mc.options.renderDistance().get() != baseRenderDistance) {
                mc.options.renderDistance().set(baseRenderDistance);
            }
            isBoosted = false;
        } catch (Throwable ignored) {
        }
    }

    public static boolean isBoosted() {
        return isBoosted;
    }
}
