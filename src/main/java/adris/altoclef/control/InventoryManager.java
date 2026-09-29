package adris.altoclef.control;

import adris.altoclef.AltoClef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

public class InventoryManager {

    private static final Set<String> THROWAWAY_NAMES = Set.of(
            "dirt", "cobblestone", "cobbled_deepslate", "netherrack", "stone", "sandstone",
            "oak_planks", "birch_planks", "spruce_planks", "jungle_planks", "acacia_planks",
            "dark_oak_planks", "mangrove_planks", "cherry_planks", "pale_oak_planks"
    );

    private int tickCooldown = 0;

    public void tick(AltoClef mod) {
        if (!AltoClef.inGame()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !player.isAlive()) return;

        // Don't modify inventory while an external screen or container is open
        if (mc.gui != null && mc.gui.screen() != null) return;
        if (mc.gameMode == null) return;

        if (tickCooldown-- > 0) return;
        tickCooldown = 8; // Run every 8 ticks (~0.4s)

        try {
            autoEquipArmor(mc, player);
            autoEquipShield(mc, player);
            ensureThrowawaysOnHotbar(mc, player);
            ensureWeaponOnHotbar(mc, player);
            ensurePickaxeOnHotbar(mc, player);
            ensureFoodOnHotbar(mc, player);
        } catch (Throwable ignored) {
        }
    }

    private void autoEquipArmor(Minecraft mc, LocalPlayer player) {
        InventoryMenu menu = player.inventoryMenu;
        if (menu == null) return;

        // Check each armor slot: 5 = Helmet, 6 = Chestplate, 7 = Leggings, 8 = Boots
        boolean missingHelmet = menu.getSlot(InventoryMenu.ARMOR_SLOT_START).getItem().isEmpty();
        boolean missingChest = menu.getSlot(InventoryMenu.ARMOR_SLOT_START + 1).getItem().isEmpty();
        boolean missingLegs = menu.getSlot(InventoryMenu.ARMOR_SLOT_START + 2).getItem().isEmpty();
        boolean missingBoots = menu.getSlot(InventoryMenu.ARMOR_SLOT_START + 3).getItem().isEmpty();

        if (!missingHelmet && !missingChest && !missingLegs && !missingBoots) return;

        // Scan main inventory (slots 9 to 35)
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.INV_SLOT_END; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            String name = stack.getItem().toString().toLowerCase();

            if (missingHelmet && name.contains("helmet")) {
                quickMove(mc, player, i);
                missingHelmet = false;
            } else if (missingChest && (name.contains("chestplate") || name.contains("elytra"))) {
                quickMove(mc, player, i);
                missingChest = false;
            } else if (missingLegs && name.contains("leggings")) {
                quickMove(mc, player, i);
                missingLegs = false;
            } else if (missingBoots && name.contains("boots")) {
                quickMove(mc, player, i);
                missingBoots = false;
            }
        }
    }

    private void autoEquipShield(Minecraft mc, LocalPlayer player) {
        InventoryMenu menu = player.inventoryMenu;
        if (menu == null) return;

        ItemStack offhand = menu.getSlot(InventoryMenu.SHIELD_SLOT).getItem();
        if (!offhand.isEmpty()) return;

        // Find shield in main inventory and shift-click into offhand
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.INV_SLOT_END; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && stack.getItem().toString().toLowerCase().contains("shield")) {
                quickMove(mc, player, i);
                return;
            }
        }
    }

    private void ensureThrowawaysOnHotbar(Minecraft mc, LocalPlayer player) {
        InventoryMenu menu = player.inventoryMenu;
        if (menu == null) return;

        // Check if hotbar already has throwaways (slots 36 to 44)
        for (int i = InventoryMenu.USE_ROW_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && isThrowaway(stack)) {
                return; // Hotbar already has throwaway blocks!
            }
        }

        // Find throwaways in main inventory (slots 9 to 35) and swap to hotbar slot 4 (index 40 in menu)
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.INV_SLOT_END; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && isThrowaway(stack)) {
                swapToHotbar(mc, player, i, 4); // Place in hotbar slot 4
                return;
            }
        }
    }

    private void ensureWeaponOnHotbar(Minecraft mc, LocalPlayer player) {
        InventoryMenu menu = player.inventoryMenu;
        if (menu == null) return;

        // Check hotbar slot 0 (index 36 in menu)
        ItemStack slot0 = menu.getSlot(InventoryMenu.USE_ROW_SLOT_START).getItem();
        if (!slot0.isEmpty() && isWeapon(slot0)) return;

        // Find best weapon in inventory and swap to slot 0
        int bestSlot = -1;
        float bestScore = -1;
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            float score = getWeaponScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        if (bestSlot != -1 && bestSlot != InventoryMenu.USE_ROW_SLOT_START) {
            swapToHotbar(mc, player, bestSlot, 0);
        }
    }

    private void ensurePickaxeOnHotbar(Minecraft mc, LocalPlayer player) {
        InventoryMenu menu = player.inventoryMenu;
        if (menu == null) return;

        // Check hotbar slot 1 (index 37 in menu)
        ItemStack slot1 = menu.getSlot(InventoryMenu.USE_ROW_SLOT_START + 1).getItem();
        if (!slot1.isEmpty() && slot1.getItem().toString().toLowerCase().contains("pickaxe")) return;

        // Find best pickaxe in inventory and swap to slot 1
        int bestSlot = -1;
        float bestScore = -1;
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            String name = stack.getItem().toString().toLowerCase();
            if (name.contains("pickaxe")) {
                float score = 1;
                if (name.contains("netherite")) score = 10;
                else if (name.contains("diamond")) score = 9;
                else if (name.contains("iron")) score = 7;
                else if (name.contains("golden")) score = 6;
                else if (name.contains("stone")) score = 5;
                else if (name.contains("wooden")) score = 4;

                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
        }

        if (bestSlot != -1 && bestSlot != InventoryMenu.USE_ROW_SLOT_START + 1) {
            swapToHotbar(mc, player, bestSlot, 1);
        }
    }

    private void ensureFoodOnHotbar(Minecraft mc, LocalPlayer player) {
        InventoryMenu menu = player.inventoryMenu;
        if (menu == null) return;

        // Check if any hotbar slot has food
        for (int i = InventoryMenu.USE_ROW_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && isEdible(stack)) return;
        }

        // Find food in main inventory and swap to hotbar slot 8
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.INV_SLOT_END; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && isEdible(stack)) {
                swapToHotbar(mc, player, i, 8);
                return;
            }
        }
    }

    private void quickMove(Minecraft mc, LocalPlayer player, int slotNum) {
        mc.gameMode.handleContainerInput(
                InventoryMenu.CONTAINER_ID,
                slotNum,
                0,
                ContainerInput.QUICK_MOVE,
                player
        );
    }

    private void swapToHotbar(Minecraft mc, LocalPlayer player, int slotNum, int hotbarSlot) {
        mc.gameMode.handleContainerInput(
                InventoryMenu.CONTAINER_ID,
                slotNum,
                hotbarSlot,
                ContainerInput.SWAP,
                player
        );
    }

    private boolean isThrowaway(ItemStack stack) {
        String name = stack.getItem().toString().toLowerCase();
        for (String target : THROWAWAY_NAMES) {
            if (name.contains(target)) return true;
        }
        return false;
    }

    private boolean isWeapon(ItemStack stack) {
        String name = stack.getItem().toString().toLowerCase();
        return name.contains("sword") || name.contains("axe");
    }

    private float getWeaponScore(ItemStack stack) {
        String name = stack.getItem().toString().toLowerCase();
        if (name.contains("netherite_sword")) return 10;
        if (name.contains("diamond_sword")) return 9;
        if (name.contains("iron_sword")) return 7;
        if (name.contains("golden_sword")) return 5;
        if (name.contains("stone_sword")) return 5;
        if (name.contains("wooden_sword")) return 4;
        if (name.contains("axe")) return 6;
        return 0;
    }

    private boolean isEdible(ItemStack stack) {
        String name = stack.getItem().toString().toLowerCase();
        if (name.contains("rotten_flesh") || name.contains("spider_eye") ||
            name.contains("pufferfish") || name.contains("poisonous_potato")) {
            return false;
        }
        return stack.has(DataComponents.FOOD);
    }
}
