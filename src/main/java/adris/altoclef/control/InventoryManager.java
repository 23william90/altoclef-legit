package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.MineBlockTask;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.input.Input;
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
            "deepslate", "tuff", "andesite", "diorite", "granite"
    );

    private int tickCooldown = 0;

    public void tick(AltoClef mod) {
        if (!AltoClef.inGame()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !player.isAlive()) return;

        // Don't modify inventory while an external screen or container is open
        if (player.containerMenu != player.inventoryMenu) return;
        if (mc.gui != null && mc.gui.screen() != null) return;
        if (mc.gameMode == null) return;

        // CRITICAL GUARD: Do NOT touch inventory or hotbar while breaking blocks, eating, or in combat!
        // Touching inventory while breaking cancels destruction progress and resets block break!
        if (mc.gameMode.isDestroying()) return;
        if (mc.options.keyAttack.isDown()) return;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) return;
                if (baritone.getMineProcess().isActive()) return;
            }
        } catch (Throwable ignored) {
        }
        if (mod.getFoodChain() != null && mod.getFoodChain().isTryingToEat()) return;
        if (mod.getMobDefenseChain() != null && mod.getMobDefenseChain().isUnderAttack()) return;

        if (tickCooldown-- > 0) return;
        tickCooldown = 8; // Run every 8 ticks (~0.4s)

        try {
            autoEquipArmor(mc, player);
            autoEquipShield(mc, player);
            ensureThrowawaysOnHotbar(mc, player);
            ensureWeaponOnHotbar(mc, player);
            ensurePickaxeOnHotbar(mc, player);
            ensureFoodOnHotbar(mc, player);
            ensureCraftingTableOnHotbar(mc, player);

            // Automated 2x2 Crafting for basic materials (wood -> planks -> sticks & crafting table)
            autoCraftBasicMaterials(mc, player);
        } catch (Throwable ignored) {
        }
    }

    private void autoCraftBasicMaterials(Minecraft mc, LocalPlayer player) {
        InventoryMenu menu = player.inventoryMenu;
        if (menu == null || !menu.getCarried().isEmpty()) return;
        if (mc.gameMode != null && mc.gameMode.isDestroying()) return;

        int planks = countItems(player, "plank");
        int logs = countItems(player, "log");
        int sticks = countItems(player, "stick");
        int tables = countItems(player, "crafting_table");

        // 1. If we have logs and < 8 planks, craft planks!
        if (planks < 8 && logs > 0) {
            int logSlot = findSlot(menu, "log");
            if (logSlot != -1) {
                craft2x2Planks(mc, player, logSlot);
                return;
            }
        }

        // 2. If we have planks and 0 crafting tables, craft a crafting table FIRST!
        if (tables < 1 && planks >= 4) {
            int plankSlot = findSlot(menu, "plank");
            if (plankSlot != -1 && menu.getSlot(plankSlot).getItem().getCount() >= 4) {
                craft2x2CraftingTable(mc, player, plankSlot);
                return;
            }
        }

        // 3. If we have planks and < 4 sticks, craft sticks!
        if (sticks < 4 && planks >= 2) {
            int plankSlot = findSlot(menu, "plank");
            if (plankSlot != -1) {
                craft2x2Sticks(mc, player, plankSlot);
                return;
            }
        }
    }

    public void craft2x2Planks(Minecraft mc, LocalPlayer player, int logSlot) {
        mc.gameMode.handleContainerInput(0, logSlot, 0, ContainerInput.PICKUP, player);
        mc.gameMode.handleContainerInput(0, 1, 1, ContainerInput.PICKUP, player); // 1 log in slot 1
        mc.gameMode.handleContainerInput(0, logSlot, 0, ContainerInput.PICKUP, player); // return remainder
        mc.gameMode.handleContainerInput(0, 0, 0, ContainerInput.QUICK_MOVE, player); // take output planks
        // Clean up
        if (!player.inventoryMenu.getSlot(1).getItem().isEmpty()) {
            mc.gameMode.handleContainerInput(0, 1, 0, ContainerInput.QUICK_MOVE, player);
        }
    }

    public void craft2x2Sticks(Minecraft mc, LocalPlayer player, int plankSlot) {
        mc.gameMode.handleContainerInput(0, plankSlot, 0, ContainerInput.PICKUP, player);
        mc.gameMode.handleContainerInput(0, 1, 1, ContainerInput.PICKUP, player); // 1 plank in slot 1
        mc.gameMode.handleContainerInput(0, 3, 1, ContainerInput.PICKUP, player); // 1 plank in slot 3
        mc.gameMode.handleContainerInput(0, plankSlot, 0, ContainerInput.PICKUP, player); // return remainder
        mc.gameMode.handleContainerInput(0, 0, 0, ContainerInput.QUICK_MOVE, player); // take output sticks
        // Clean up
        if (!player.inventoryMenu.getSlot(1).getItem().isEmpty()) {
            mc.gameMode.handleContainerInput(0, 1, 0, ContainerInput.QUICK_MOVE, player);
        }
        if (!player.inventoryMenu.getSlot(3).getItem().isEmpty()) {
            mc.gameMode.handleContainerInput(0, 3, 0, ContainerInput.QUICK_MOVE, player);
        }
    }

    public void craft2x2CraftingTable(Minecraft mc, LocalPlayer player, int plankSlot) {
        mc.gameMode.handleContainerInput(0, plankSlot, 0, ContainerInput.PICKUP, player);
        mc.gameMode.handleContainerInput(0, 1, 1, ContainerInput.PICKUP, player);
        mc.gameMode.handleContainerInput(0, 2, 1, ContainerInput.PICKUP, player);
        mc.gameMode.handleContainerInput(0, 3, 1, ContainerInput.PICKUP, player);
        mc.gameMode.handleContainerInput(0, 4, 1, ContainerInput.PICKUP, player);
        mc.gameMode.handleContainerInput(0, plankSlot, 0, ContainerInput.PICKUP, player); // return remainder
        mc.gameMode.handleContainerInput(0, 0, 0, ContainerInput.QUICK_MOVE, player); // take table
        // Clean up
        for (int s = 1; s <= 4; s++) {
            if (!player.inventoryMenu.getSlot(s).getItem().isEmpty()) {
                mc.gameMode.handleContainerInput(0, s, 0, ContainerInput.QUICK_MOVE, player);
            }
        }
    }

    private int findSlot(InventoryMenu menu, String keyword) {
        keyword = keyword.toLowerCase();
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && getItemName(stack).contains(keyword)) {
                return i;
            }
        }
        return -1;
    }

    public static String getItemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase();
        } catch (Throwable t) {
            return stack.getItem().toString().toLowerCase();
        }
    }

    public static int countItems(LocalPlayer player, String... keywords) {
        if (player == null) return 0;
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                String name = getItemName(stack);
                for (String kw : keywords) {
                    if (MineBlockTask.matchesResource(name, kw, (String[]) null)) {
                        count += stack.getCount();
                        break;
                    }
                }
            }
        }
        if (player.containerMenu != null) {
            ItemStack carried = player.containerMenu.getCarried();
            if (carried != null && !carried.isEmpty()) {
                String name = getItemName(carried);
                for (String kw : keywords) {
                    if (MineBlockTask.matchesResource(name, kw, (String[]) null)) {
                        count += carried.getCount();
                        break;
                    }
                }
            }
        }
        return count;
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
            String name = getItemName(stack);

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
            if (!stack.isEmpty() && getItemName(stack).contains("shield")) {
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
        if (!slot1.isEmpty() && getItemName(slot1).contains("pickaxe")) return;

        // Find best pickaxe in inventory and swap to slot 1
        int bestSlot = -1;
        float bestScore = -1;
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            String name = getItemName(stack);
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

    private void ensureCraftingTableOnHotbar(Minecraft mc, LocalPlayer player) {
        InventoryMenu menu = player.inventoryMenu;
        if (menu == null) return;

        // Check if hotbar already has a crafting table
        for (int i = InventoryMenu.USE_ROW_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && getItemName(stack).contains("crafting_table")) {
                return;
            }
        }

        // Find crafting table in main inventory and swap to hotbar slot 2
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.INV_SLOT_END; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && getItemName(stack).contains("crafting_table")) {
                swapToHotbar(mc, player, i, 2);
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
        if (mc.gameMode != null && mc.gameMode.isDestroying()) return;
        mc.gameMode.handleContainerInput(
                InventoryMenu.CONTAINER_ID,
                slotNum,
                hotbarSlot,
                ContainerInput.SWAP,
                player
        );
    }

    private boolean isThrowaway(ItemStack stack) {
        String name = getItemName(stack);
        if (name.contains("plank") || name.contains("log") || name.contains("wood")) return false;
        for (String target : THROWAWAY_NAMES) {
            if (name.contains(target)) return true;
        }
        return false;
    }

    private boolean isWeapon(ItemStack stack) {
        String name = getItemName(stack);
        return name.contains("sword") || name.contains("axe");
    }

    private float getWeaponScore(ItemStack stack) {
        String name = getItemName(stack);
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
        String name = getItemName(stack);
        if (name.contains("rotten_flesh") || name.contains("spider_eye") ||
                name.contains("pufferfish") || name.contains("poisonous_potato")) {
            return false;
        }
        return stack.has(DataComponents.FOOD);
    }
}
