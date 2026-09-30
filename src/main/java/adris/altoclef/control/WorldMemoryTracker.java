package adris.altoclef.control;

import adris.altoclef.tasks.construction.MineBlockTask;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Intelligent World Memory & Resource Decision Engine:
 * Tracks death drop locations, remembered dropped items, and discovered chests.
 * Weights the effort of obtaining items manually vs retrieving existing dropped items.
 */
public class WorldMemoryTracker {

    private static final WorldMemoryTracker INSTANCE = new WorldMemoryTracker();

    public static WorldMemoryTracker getInstance() {
        return INSTANCE;
    }

    public static class DeathDropMemory {
        public final BlockPos pos;
        public final String dimension;
        public final long timestamp;
        public boolean recovered = false;

        public DeathDropMemory(BlockPos pos, String dimension, long timestamp) {
            this.pos = pos;
            this.dimension = dimension;
            this.timestamp = timestamp;
        }

        public boolean isExpired() {
            // Vanilla items despawn in 5 minutes (300,000 ms) in active chunks
            return System.currentTimeMillis() - timestamp > 300_000L;
        }
    }

    private DeathDropMemory lastDeathDrop = null;
    private final Set<BlockPos> knownChests = new HashSet<>();

    private WorldMemoryTracker() {
    }

    /**
     * Records death location and dimension when player dies.
     */
    public void recordDeath(BlockPos pos, String dimension) {
        this.lastDeathDrop = new DeathDropMemory(pos, dimension, System.currentTimeMillis());
    }

    /**
     * Checks if there is a recoverable death drop within reasonable distance.
     */
    public BlockPos getRecoverableDeathDrop(Minecraft mc, LocalPlayer player, double maxDistance) {
        if (lastDeathDrop == null || lastDeathDrop.recovered || lastDeathDrop.isExpired()) {
            return null;
        }
        if (mc.level == null || player == null) return null;

        String currentDim = mc.level.dimension().toString().toLowerCase();
        if (!currentDim.contains(lastDeathDrop.dimension.toLowerCase())) {
            return null;
        }

        double dist = player.position().distanceTo(Vec3.atCenterOf(lastDeathDrop.pos));
        if (dist <= 3.0) {
            lastDeathDrop.recovered = true;
            return null;
        }

        if (dist <= maxDistance) {
            return lastDeathDrop.pos;
        }

        return null;
    }

    /**
     * Records a discovered chest in the world.
     */
    public void recordChest(BlockPos pos) {
        if (pos != null) {
            knownChests.add(pos.immutable());
        }
    }

    public boolean isChestKnown(BlockPos pos) {
        return pos != null && knownChests.contains(pos);
    }

    public BlockPos getClosestKnownChest(LocalPlayer player) {
        if (player == null || knownChests.isEmpty()) return null;
        BlockPos closest = null;
        double closestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : knownChests) {
            double dSq = player.distanceToSqr(Vec3.atCenterOf(pos));
            if (dSq < closestDistSq) {
                closestDistSq = dSq;
                closest = pos;
            }
        }
        return closest;
    }

    public Set<BlockPos> getKnownChests() {
        return Collections.unmodifiableSet(knownChests);
    }

    /**
     * Finds the best dropped item matching the requested resource within weighted retrieval distance.
     */
    public ItemEntity findBestDroppedItem(Minecraft mc, LocalPlayer player, String resourceName, String... aliases) {
        if (mc.level == null || player == null) return null;

        ItemEntity bestItem = null;
        double closestDist = Double.MAX_VALUE;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity itemEntity) || !itemEntity.isAlive() || itemEntity.isRemoved()) {
                continue;
            }

            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty()) continue;

            String itemName = InventoryManager.getItemName(stack);
            if (!matchesResource(itemName, resourceName, aliases)) continue;

            double dist = player.distanceTo(itemEntity);
            if (!shouldRetrieveDropVsMine(itemName, dist, stack.getCount())) {
                continue;
            }

            if (dist < closestDist) {
                closestDist = dist;
                bestItem = itemEntity;
            }
        }

        return bestItem;
    }

    /**
     * Weights the difficulty of obtaining an item manually against the travel distance.
     * Prevents the bot from running 1000 blocks for a piece of wood when it could just mine one,
     * while prioritizing running up to 150-450 blocks to recover lost iron, diamonds, or valuable tools.
     */
    public boolean shouldRetrieveDropVsMine(String itemName, double distance, int count) {
        double maxDistance = getMaxRetrieveDistance(itemName, count);
        return distance <= maxDistance;
    }

    public static double getMaxRetrieveDistance(String itemName, int count) {
        itemName = itemName.toLowerCase();

        // Common materials (wood, dirt, stone, cobblestone, sand)
        if (itemName.contains("log") || itemName.contains("wood") || itemName.contains("plank") ||
                itemName.contains("dirt") || itemName.contains("cobblestone") || itemName.contains("stone") ||
                itemName.contains("sand")) {
            // Large stack (>= 8) is worth traveling further for
            if (count >= 8) return 60.0;
            return 38.0;
        }

        // Medium materials (coal, copper, leather, food)
        if (itemName.contains("coal") || itemName.contains("copper") || itemName.contains("beef") ||
                itemName.contains("pork") || itemName.contains("bread") || itemName.contains("leather") ||
                itemName.contains("mutton") || itemName.contains("chicken")) {
            return 80.0;
        }

        // High value materials (iron ore, raw iron, iron ingot, tools, buckets, shield)
        if (itemName.contains("iron") || itemName.contains("shield") || itemName.contains("bucket") ||
                itemName.contains("furnace") || itemName.contains("shears")) {
            return 160.0;
        }

        // Rare / Critical speedrun materials (diamonds, obsidian, golden helmet, blaze rods, pearls, ender eyes, netherite)
        if (itemName.contains("diamond") || itemName.contains("obsidian") || itemName.contains("gold") ||
                itemName.contains("pearl") || itemName.contains("blaze") || itemName.contains("eye") ||
                itemName.contains("netherite")) {
            return 450.0;
        }

        return 50.0;
    }

    private boolean matchesResource(String itemName, String resourceName, String... aliases) {
        return MineBlockTask.matchesResource(itemName, resourceName, aliases);
    }
}
