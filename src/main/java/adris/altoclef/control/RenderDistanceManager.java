package adris.altoclef.control;

import net.minecraft.client.Minecraft;

/**
 * Manages dynamic render distance scaling:
 * Boosts render distance temporarily when searching for blocks (wood, ores), dropped items, mobs, or players,
 * and automatically reverts to lower chunk values to preserve CPU/memory during normal gameplay.
 */
public class RenderDistanceManager {

    private static int baseRenderDistance = -1;
    private static boolean isBoosted = false;
    private static long boostExpireTimestamp = 0;
    private static long lastBoostTime = 0;

    /**
     * Temporarily boosts render distance while searching for blocks (wood, ores), dropped items, mobs, or players.
     */
    public static void requestSearchBoost(Minecraft mc, int boostedDistance, int durationTicks) {
        if (mc == null || mc.options == null) return;
        try {
            if (baseRenderDistance == -1) {
                baseRenderDistance = mc.options.renderDistance().get();
                if (baseRenderDistance <= 0 || baseRenderDistance > 16) {
                    baseRenderDistance = 10;
                }
            }
            int targetBoost = Math.max(16, Math.min(boostedDistance, 32));
            if (mc.options.renderDistance().get() != targetBoost) {
                mc.options.renderDistance().set(targetBoost);
            }
            isBoosted = true;
            lastBoostTime = System.currentTimeMillis();
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
            forceRevert(mc);
        }
    }

    /**
     * Reverts render distance back to base value when target is found, with anti-jitter protection.
     */
    public static void revert(Minecraft mc) {
        if (mc == null || mc.options == null) return;
        // Anti-jitter: Don't revert if boost was requested less than 3.5 seconds ago
        if (System.currentTimeMillis() - lastBoostTime < 3500) return;
        forceRevert(mc);
    }

    /**
     * Forces immediate reversion of render distance back to base value (e.g. task finished/stopped).
     */
    public static void forceRevert(Minecraft mc) {
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
