package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public class MineBlockTask extends Task {

    public enum ToolTier {
        HAND(0),
        WOOD(1),
        STONE(2),
        IRON(3),
        DIAMOND(4),
        NETHERITE(5);

        private final int level;

        ToolTier(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }
    }

    private final AltoClef mod;
    private final String resourceName;
    private final String blockTarget;
    private final int targetCount;
    private final Block[] resolvedBlocks;
    private final String[] blockNames;
    private boolean finished = false;
    private int cooldown = 0;

    public MineBlockTask(AltoClef mod, String resourceName, String blockTarget, int targetCount) {
        this.mod = mod;
        this.resourceName = resourceName;
        this.blockTarget = blockTarget;
        this.targetCount = targetCount;

        List<Block> blocks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String[] tokens = blockTarget.split("[,\\s]+");
        for (String token : tokens) {
            token = token.trim();
            if (token.isEmpty()) continue;
            names.add(token);
            try {
                Identifier id = Identifier.tryParse(token.contains(":") ? token : "minecraft:" + token);
                if (id != null) {
                    Block b = BuiltInRegistries.BLOCK.getValue(id);
                    if (b != null && b != Blocks.AIR) {
                        blocks.add(b);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        this.resolvedBlocks = blocks.toArray(new Block[0]);
        this.blockNames = names.toArray(new String[0]);
    }

    public ToolTier getRequiredTier() {
        ToolTier highest = ToolTier.HAND;
        for (Block b : resolvedBlocks) {
            ToolTier tier = getRequiredToolTier(b);
            if (tier.getLevel() > highest.getLevel()) {
                highest = tier;
            }
        }
        if (resolvedBlocks.length == 0) {
            for (String name : blockNames) {
                ToolTier tier = getRequiredToolTier(name);
                if (tier.getLevel() > highest.getLevel()) {
                    highest = tier;
                }
            }
        }
        return highest;
    }

    public static ToolTier getRequiredToolTier(Block block) {
        if (block == null) return ToolTier.HAND;
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) return ToolTier.HAND;
        return getRequiredToolTier(id.getPath());
    }

    public static ToolTier getRequiredToolTier(String name) {
        name = name.toLowerCase();
        if (name.contains("obsidian") || name.contains("ancient_debris") || name.contains("respawn_anchor")) {
            return ToolTier.DIAMOND;
        }
        if (name.contains("diamond") || name.contains("gold_ore") || name.contains("emerald") || name.contains("redstone")) {
            return ToolTier.IRON;
        }
        if (name.contains("iron_ore") || name.contains("raw_iron_block") || name.contains("copper_ore") || name.contains("lapis")) {
            return ToolTier.STONE;
        }
        if (name.contains("stone") || name.contains("cobble") || name.contains("deepslate") || name.contains("coal_ore") ||
                name.contains("andesite") || name.contains("diorite") || name.contains("granite") || name.contains("sandstone") ||
                name.contains("tuff") || name.contains("calcite")) {
            return ToolTier.WOOD;
        }
        return ToolTier.HAND;
    }

    public static ToolTier getPlayerPickaxeTier(LocalPlayer player) {
        if (player == null) return ToolTier.HAND;
        ToolTier highest = ToolTier.HAND;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String name = stack.getItem().toString().toLowerCase();
            if (name.contains("netherite_pickaxe")) return ToolTier.NETHERITE;
            if (name.contains("diamond_pickaxe")) {
                if (highest.getLevel() < ToolTier.DIAMOND.getLevel()) highest = ToolTier.DIAMOND;
            } else if (name.contains("iron_pickaxe")) {
                if (highest.getLevel() < ToolTier.IRON.getLevel()) highest = ToolTier.IRON;
            } else if (name.contains("stone_pickaxe")) {
                if (highest.getLevel() < ToolTier.STONE.getLevel()) highest = ToolTier.STONE;
            } else if (name.contains("wooden_pickaxe") || name.contains("copper_pickaxe") || name.contains("golden_pickaxe")) {
                if (highest.getLevel() < ToolTier.WOOD.getLevel()) highest = ToolTier.WOOD;
            }
        }
        return highest;
    }

    public static int getPickaxeHotbarSlot(LocalPlayer player) {
        if (player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem().toString().toLowerCase().contains("pickaxe")) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onStart() {
        finished = false;
        cooldown = 0;
        setDebugState("Target: " + targetCount + "x " + resourceName);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            ToolTier required = getRequiredTier();
            ToolTier playerTier = getPlayerPickaxeTier(mc.player);
            if (playerTier.getLevel() < required.getLevel()) {
                setDebugState("CANNOT MINE " + resourceName + ": Requires " + required + " pickaxe (Have " + playerTier + ")");
                return;
            }
        }

        startMining();
    }

    private void startMining() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            ToolTier required = getRequiredTier();
            ToolTier playerTier = getPlayerPickaxeTier(mc.player);
            if (playerTier.getLevel() < required.getLevel()) {
                return; // Forbidden from mining without proper tool
            }
        }

        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null) {
                if (resolvedBlocks.length > 0) {
                    primary.getMineProcess().mine(targetCount, resolvedBlocks);
                } else if (blockNames.length > 0) {
                    primary.getMineProcess().mineByName(targetCount, blockNames);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;

        // Tool tier enforcement check
        ToolTier required = getRequiredTier();
        ToolTier playerTier = getPlayerPickaxeTier(mc.player);
        if (playerTier.getLevel() < required.getLevel()) {
            setDebugState("CANNOT MINE " + resourceName + ": Requires " + required + " pickaxe (Have " + playerTier + ")");
            try {
                IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (primary != null && primary.getMineProcess().isActive()) {
                    primary.getMineProcess().cancel();
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        // Equip the pickaxe in hand if required
        if (required.getLevel() >= ToolTier.WOOD.getLevel()) {
            int pickSlot = getPickaxeHotbarSlot(mc.player);
            if (pickSlot != -1 && mc.player.getInventory().getSelectedSlot() != pickSlot) {
                mc.player.getInventory().setSelectedSlot(pickSlot);
            }
        }

        int current = getCurrentCount();
        setDebugState(current + " / " + targetCount + " " + resourceName);

        if (current >= targetCount) {
            finished = true;
            onStop(null);
            return null;
        }

        if (cooldown-- <= 0) {
            cooldown = 20; // Check every 1 second
            try {
                IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (primary != null && !primary.getMineProcess().isActive() && !primary.getPathingBehavior().isPathing()) {
                    startMining();
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary != null) {
                primary.getMineProcess().cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    private int getCurrentCount() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        Inventory inv = mc.player.getInventory();
        int count = 0;
        String lowerTarget = resourceName.toLowerCase();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                String itemName = stack.getItem().toString().toLowerCase();
                if (itemName.contains(lowerTarget) || lowerTarget.contains(itemName)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof MineBlockTask task) {
            return task.blockTarget.equals(this.blockTarget) && task.targetCount == this.targetCount;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Mining " + resourceName + " (" + blockTarget + ")";
    }
}
