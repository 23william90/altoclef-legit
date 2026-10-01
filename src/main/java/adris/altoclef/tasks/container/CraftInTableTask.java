package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.InventoryManager;
import adris.altoclef.tasks.construction.MineBlockTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class CraftInTableTask extends Task {

    private final String itemTarget;
    private final int targetCount;
    private boolean finished = false;
    private int stepTimer = 0;
    private BlockPos placedTablePos = null;

    // Watchdog and fallback counters
    private int noSpotTicks = 0;
    private int placedTableWaitTicks = 0;
    private int craftingGuiTicks = 0;
    private int ticksSinceLastCraftAction = 0;
    private int extractAttempts = 0;
    private int recipeBookAttempts = 0;
    private int lastIngredientSourceSlot = -1;
    private String currentIngredientKeyword = null;
    private int craftingMenuOpenCloseLoops = 0;
    private boolean wasMenuOpen = false;
    private int missingIngredientTicks = 0;
    private int lastTargetCount = -1;

    private static final Map<String, RecipeDef> RECIPES = new HashMap<>();

    public static class RecipeDef {
        public final Map<Integer, String> gridSlots; // Slot 1..9 -> item keyword
        public final Map<String, Integer> requiredCounts; // keyword -> min count

        public RecipeDef(Map<Integer, String> gridSlots, Map<String, Integer> requiredCounts) {
            this.gridSlots = gridSlots;
            this.requiredCounts = requiredCounts;
        }
    }

    static {
        // Wooden Pickaxe: 3 planks (1,2,3) FIRST, then 2 sticks (5,8)
        Map<Integer, String> wPick = new LinkedHashMap<>();
        wPick.put(1, "plank");
        wPick.put(2, "plank");
        wPick.put(3, "plank");
        wPick.put(5, "stick");
        wPick.put(8, "stick");
        RECIPES.put("wooden_pickaxe", new RecipeDef(wPick, Map.of("plank", 3, "stick", 2)));

        // Wooden Sword: 2 planks (2,5), 1 stick (8)
        Map<Integer, String> wSword = new LinkedHashMap<>();
        wSword.put(2, "plank");
        wSword.put(5, "plank");
        wSword.put(8, "stick");
        RECIPES.put("wooden_sword", new RecipeDef(wSword, Map.of("plank", 2, "stick", 1)));

        // Wooden Axe: 3 planks (1,2,4), 2 sticks (5,8)
        Map<Integer, String> wAxe = new LinkedHashMap<>();
        wAxe.put(1, "plank");
        wAxe.put(2, "plank");
        wAxe.put(4, "plank");
        wAxe.put(5, "stick");
        wAxe.put(8, "stick");
        RECIPES.put("wooden_axe", new RecipeDef(wAxe, Map.of("plank", 3, "stick", 2)));

        // Wooden Shovel: 1 plank (2), 2 sticks (5,8)
        Map<Integer, String> wShovel = new LinkedHashMap<>();
        wShovel.put(2, "plank");
        wShovel.put(5, "stick");
        wShovel.put(8, "stick");
        RECIPES.put("wooden_shovel", new RecipeDef(wShovel, Map.of("plank", 1, "stick", 2)));

        // Wooden Hoe: 2 planks (1,2), 2 sticks (5,8)
        Map<Integer, String> wHoe = new LinkedHashMap<>();
        wHoe.put(1, "plank");
        wHoe.put(2, "plank");
        wHoe.put(5, "stick");
        wHoe.put(8, "stick");
        RECIPES.put("wooden_hoe", new RecipeDef(wHoe, Map.of("plank", 2, "stick", 2)));

        // Stone Pickaxe: 3 cobble (1,2,3) FIRST, then 2 sticks (5,8)
        Map<Integer, String> sPick = new LinkedHashMap<>();
        sPick.put(1, "cobble");
        sPick.put(2, "cobble");
        sPick.put(3, "cobble");
        sPick.put(5, "stick");
        sPick.put(8, "stick");
        RECIPES.put("stone_pickaxe", new RecipeDef(sPick, Map.of("cobble", 3, "stick", 2)));

        // Stone Sword: 2 cobble (2,5), 1 stick (8)
        Map<Integer, String> sSword = new LinkedHashMap<>();
        sSword.put(2, "cobble");
        sSword.put(5, "cobble");
        sSword.put(8, "stick");
        RECIPES.put("stone_sword", new RecipeDef(sSword, Map.of("cobble", 2, "stick", 1)));

        // Stone Axe: 3 cobble (1,2,4), 2 sticks (5,8)
        Map<Integer, String> sAxe = new LinkedHashMap<>();
        sAxe.put(1, "cobble");
        sAxe.put(2, "cobble");
        sAxe.put(4, "cobble");
        sAxe.put(5, "stick");
        sAxe.put(8, "stick");
        RECIPES.put("stone_axe", new RecipeDef(sAxe, Map.of("cobble", 3, "stick", 2)));

        // Stone Shovel: 1 cobble (2), 2 sticks (5,8)
        Map<Integer, String> sShovel = new LinkedHashMap<>();
        sShovel.put(2, "cobble");
        sShovel.put(5, "stick");
        sShovel.put(8, "stick");
        RECIPES.put("stone_shovel", new RecipeDef(sShovel, Map.of("cobble", 1, "stick", 2)));

        // Stone Hoe: 2 cobble (1,2), 2 sticks (5,8)
        Map<Integer, String> sHoe = new LinkedHashMap<>();
        sHoe.put(1, "cobble");
        sHoe.put(2, "cobble");
        sHoe.put(5, "stick");
        sHoe.put(8, "stick");
        RECIPES.put("stone_hoe", new RecipeDef(sHoe, Map.of("cobble", 2, "stick", 2)));

        // Furnace: 8 cobble (1,2,3,4,6,7,8,9)
        Map<Integer, String> furnace = new LinkedHashMap<>();
        furnace.put(1, "cobble");
        furnace.put(2, "cobble");
        furnace.put(3, "cobble");
        furnace.put(4, "cobble");
        furnace.put(6, "cobble");
        furnace.put(7, "cobble");
        furnace.put(8, "cobble");
        furnace.put(9, "cobble");
        RECIPES.put("furnace", new RecipeDef(furnace, Map.of("cobble", 8)));

        // Iron Pickaxe: 3 iron ingots (1,2,3) FIRST, then 2 sticks (5,8)
        Map<Integer, String> iPick = new LinkedHashMap<>();
        iPick.put(1, "iron_ingot");
        iPick.put(2, "iron_ingot");
        iPick.put(3, "iron_ingot");
        iPick.put(5, "stick");
        iPick.put(8, "stick");
        RECIPES.put("iron_pickaxe", new RecipeDef(iPick, Map.of("iron_ingot", 3, "stick", 2)));

        // Iron Sword: 2 iron ingots (2,5), 1 stick (8)
        Map<Integer, String> iSword = new LinkedHashMap<>();
        iSword.put(2, "iron_ingot");
        iSword.put(5, "iron_ingot");
        iSword.put(8, "stick");
        RECIPES.put("iron_sword", new RecipeDef(iSword, Map.of("iron_ingot", 2, "stick", 1)));

        // Iron Axe: 3 iron ingots (1,2,4), 2 sticks (5,8)
        Map<Integer, String> iAxe = new LinkedHashMap<>();
        iAxe.put(1, "iron_ingot");
        iAxe.put(2, "iron_ingot");
        iAxe.put(4, "iron_ingot");
        iAxe.put(5, "stick");
        iAxe.put(8, "stick");
        RECIPES.put("iron_axe", new RecipeDef(iAxe, Map.of("iron_ingot", 3, "stick", 2)));

        // Iron Shovel: 1 iron ingot (2), 2 sticks (5,8)
        Map<Integer, String> iShovel = new LinkedHashMap<>();
        iShovel.put(2, "iron_ingot");
        iShovel.put(5, "stick");
        iShovel.put(8, "stick");
        RECIPES.put("iron_shovel", new RecipeDef(iShovel, Map.of("iron_ingot", 1, "stick", 2)));

        // Iron Hoe: 2 iron ingots (1,2), 2 sticks (5,8)
        Map<Integer, String> iHoe = new LinkedHashMap<>();
        iHoe.put(1, "iron_ingot");
        iHoe.put(2, "iron_ingot");
        iHoe.put(5, "stick");
        iHoe.put(8, "stick");
        RECIPES.put("iron_hoe", new RecipeDef(iHoe, Map.of("iron_ingot", 2, "stick", 2)));

        // Shield: 6 planks (1,3,4,5,6,8), 1 iron ingot (2)
        Map<Integer, String> shield = new LinkedHashMap<>();
        shield.put(1, "plank");
        shield.put(3, "plank");
        shield.put(4, "plank");
        shield.put(5, "plank");
        shield.put(6, "plank");
        shield.put(8, "plank");
        shield.put(2, "iron_ingot");
        RECIPES.put("shield", new RecipeDef(shield, Map.of("plank", 6, "iron_ingot", 1)));

        // Bucket: 3 iron ingots (4,6,8)
        Map<Integer, String> bucket = new LinkedHashMap<>();
        bucket.put(4, "iron_ingot");
        bucket.put(6, "iron_ingot");
        bucket.put(8, "iron_ingot");
        RECIPES.put("bucket", new RecipeDef(bucket, Map.of("iron_ingot", 3)));

        // Flint and Steel: 1 iron ingot (1), 1 flint (2)
        Map<Integer, String> flintSteel = new LinkedHashMap<>();
        flintSteel.put(1, "iron_ingot");
        flintSteel.put(2, "flint");
        RECIPES.put("flint_and_steel", new RecipeDef(flintSteel, Map.of("iron_ingot", 1, "flint", 1)));

        // Iron Helmet: 5 iron ingots (1,2,3,4,6)
        Map<Integer, String> iHelm = new LinkedHashMap<>();
        iHelm.put(1, "iron_ingot");
        iHelm.put(2, "iron_ingot");
        iHelm.put(3, "iron_ingot");
        iHelm.put(4, "iron_ingot");
        iHelm.put(6, "iron_ingot");
        RECIPES.put("iron_helmet", new RecipeDef(iHelm, Map.of("iron_ingot", 5)));

        // Iron Chestplate: 8 iron ingots (1,3,4,5,6,7,8,9)
        Map<Integer, String> iChest = new LinkedHashMap<>();
        iChest.put(1, "iron_ingot");
        iChest.put(3, "iron_ingot");
        iChest.put(4, "iron_ingot");
        iChest.put(5, "iron_ingot");
        iChest.put(6, "iron_ingot");
        iChest.put(7, "iron_ingot");
        iChest.put(8, "iron_ingot");
        iChest.put(9, "iron_ingot");
        RECIPES.put("iron_chestplate", new RecipeDef(iChest, Map.of("iron_ingot", 8)));

        // Iron Leggings: 7 iron ingots (1,2,3,4,6,7,9)
        Map<Integer, String> iLegs = new LinkedHashMap<>();
        iLegs.put(1, "iron_ingot");
        iLegs.put(2, "iron_ingot");
        iLegs.put(3, "iron_ingot");
        iLegs.put(4, "iron_ingot");
        iLegs.put(6, "iron_ingot");
        iLegs.put(7, "iron_ingot");
        iLegs.put(9, "iron_ingot");
        RECIPES.put("iron_leggings", new RecipeDef(iLegs, Map.of("iron_ingot", 7)));

        // Iron Boots: 4 iron ingots (4,6,7,9)
        Map<Integer, String> iBoots = new LinkedHashMap<>();
        iBoots.put(4, "iron_ingot");
        iBoots.put(6, "iron_ingot");
        iBoots.put(7, "iron_ingot");
        iBoots.put(9, "iron_ingot");
        RECIPES.put("iron_boots", new RecipeDef(iBoots, Map.of("iron_ingot", 4)));

        // Diamond Pickaxe: 3 diamonds (1,2,3) FIRST, then 2 sticks (5,8)
        Map<Integer, String> dPick = new LinkedHashMap<>();
        dPick.put(1, "diamond");
        dPick.put(2, "diamond");
        dPick.put(3, "diamond");
        dPick.put(5, "stick");
        dPick.put(8, "stick");
        RECIPES.put("diamond_pickaxe", new RecipeDef(dPick, Map.of("diamond", 3, "stick", 2)));

        // Diamond Sword: 2 diamonds (2,5), 1 stick (8)
        Map<Integer, String> dSword = new LinkedHashMap<>();
        dSword.put(2, "diamond");
        dSword.put(5, "diamond");
        dSword.put(8, "stick");
        RECIPES.put("diamond_sword", new RecipeDef(dSword, Map.of("diamond", 2, "stick", 1)));

        // Diamond Axe: 3 diamonds (1,2,4), 2 sticks (5,8)
        Map<Integer, String> dAxe = new LinkedHashMap<>();
        dAxe.put(1, "diamond");
        dAxe.put(2, "diamond");
        dAxe.put(4, "diamond");
        dAxe.put(5, "stick");
        dAxe.put(8, "stick");
        RECIPES.put("diamond_axe", new RecipeDef(dAxe, Map.of("diamond", 3, "stick", 2)));

        // Diamond Shovel: 1 diamond (2), 2 sticks (5,8)
        Map<Integer, String> dShovel = new LinkedHashMap<>();
        dShovel.put(2, "diamond");
        dShovel.put(5, "stick");
        dShovel.put(8, "stick");
        RECIPES.put("diamond_shovel", new RecipeDef(dShovel, Map.of("diamond", 1, "stick", 2)));

        // Diamond Hoe: 2 diamonds (1,2), 2 sticks (5,8)
        Map<Integer, String> dHoe = new LinkedHashMap<>();
        dHoe.put(1, "diamond");
        dHoe.put(2, "diamond");
        dHoe.put(5, "stick");
        dHoe.put(8, "stick");
        RECIPES.put("diamond_hoe", new RecipeDef(dHoe, Map.of("diamond", 2, "stick", 2)));

        // Diamond Helmet: 5 diamonds (1,2,3,4,6)
        Map<Integer, String> dHelm = new LinkedHashMap<>();
        dHelm.put(1, "diamond");
        dHelm.put(2, "diamond");
        dHelm.put(3, "diamond");
        dHelm.put(4, "diamond");
        dHelm.put(6, "diamond");
        RECIPES.put("diamond_helmet", new RecipeDef(dHelm, Map.of("diamond", 5)));

        // Diamond Chestplate: 8 diamonds (1,3,4,5,6,7,8,9)
        Map<Integer, String> dChest = new LinkedHashMap<>();
        dChest.put(1, "diamond");
        dChest.put(3, "diamond");
        dChest.put(4, "diamond");
        dChest.put(5, "diamond");
        dChest.put(6, "diamond");
        dChest.put(7, "diamond");
        dChest.put(8, "diamond");
        dChest.put(9, "diamond");
        RECIPES.put("diamond_chestplate", new RecipeDef(dChest, Map.of("diamond", 8)));

        // Diamond Leggings: 7 diamonds (1,2,3,4,6,7,9)
        Map<Integer, String> dLegs = new LinkedHashMap<>();
        dLegs.put(1, "diamond");
        dLegs.put(2, "diamond");
        dLegs.put(3, "diamond");
        dLegs.put(4, "diamond");
        dLegs.put(6, "diamond");
        dLegs.put(7, "diamond");
        dLegs.put(9, "diamond");
        RECIPES.put("diamond_leggings", new RecipeDef(dLegs, Map.of("diamond", 7)));

        // Diamond Boots: 4 diamonds (4,6,7,9)
        Map<Integer, String> dBoots = new LinkedHashMap<>();
        dBoots.put(4, "diamond");
        dBoots.put(6, "diamond");
        dBoots.put(7, "diamond");
        dBoots.put(9, "diamond");
        RECIPES.put("diamond_boots", new RecipeDef(dBoots, Map.of("diamond", 4)));

        // Golden Helmet: 5 gold ingots (1,2,3,4,6)
        Map<Integer, String> gHelm = new LinkedHashMap<>();
        gHelm.put(1, "gold_ingot");
        gHelm.put(2, "gold_ingot");
        gHelm.put(3, "gold_ingot");
        gHelm.put(4, "gold_ingot");
        gHelm.put(6, "gold_ingot");
        RECIPES.put("golden_helmet", new RecipeDef(gHelm, Map.of("gold_ingot", 5)));

        // Crafting Table: 4 planks (1,2,4,5)
        Map<Integer, String> table = new LinkedHashMap<>();
        table.put(1, "plank");
        table.put(2, "plank");
        table.put(4, "plank");
        table.put(5, "plank");
        RECIPES.put("crafting_table", new RecipeDef(table, Map.of("plank", 4)));

        // Sticks: 2 planks (1,4)
        Map<Integer, String> stick = new LinkedHashMap<>();
        stick.put(1, "plank");
        stick.put(4, "plank");
        RECIPES.put("stick", new RecipeDef(stick, Map.of("plank", 2)));

        // Planks: 1 log (1)
        Map<Integer, String> planks = new LinkedHashMap<>();
        planks.put(1, "log");
        RECIPES.put("planks", new RecipeDef(planks, Map.of("log", 1)));

        // Bed: 3 wool (4,5,6), 3 planks (7,8,9)
        Map<Integer, String> bed = new LinkedHashMap<>();
        bed.put(4, "wool");
        bed.put(5, "wool");
        bed.put(6, "wool");
        bed.put(7, "plank");
        bed.put(8, "plank");
        bed.put(9, "plank");
        RECIPES.put("bed", new RecipeDef(bed, Map.of("wool", 3, "plank", 3)));

        // Bread: 3 wheat (4,5,6)
        Map<Integer, String> bread = new LinkedHashMap<>();
        bread.put(4, "wheat");
        bread.put(5, "wheat");
        bread.put(6, "wheat");
        RECIPES.put("bread", new RecipeDef(bread, Map.of("wheat", 3)));

        // Wheat from Hay Block: 1 hay block (5)
        Map<Integer, String> wheatFromHay = new LinkedHashMap<>();
        wheatFromHay.put(5, "hay_block");
        RECIPES.put("wheat", new RecipeDef(wheatFromHay, Map.of("hay_block", 1)));

        // Golden Carrot: 8 gold nuggets, 1 carrot
        Map<Integer, String> gCarrot = new LinkedHashMap<>();
        gCarrot.put(1, "gold_nugget");
        gCarrot.put(2, "gold_nugget");
        gCarrot.put(3, "gold_nugget");
        gCarrot.put(4, "gold_nugget");
        gCarrot.put(5, "carrot");
        gCarrot.put(6, "gold_nugget");
        gCarrot.put(7, "gold_nugget");
        gCarrot.put(8, "gold_nugget");
        gCarrot.put(9, "gold_nugget");
        RECIPES.put("golden_carrot", new RecipeDef(gCarrot, Map.of("gold_nugget", 8, "carrot", 1)));

        // Golden Apple: 8 gold ingots, 1 apple
        Map<Integer, String> gApple = new LinkedHashMap<>();
        gApple.put(1, "gold_ingot");
        gApple.put(2, "gold_ingot");
        gApple.put(3, "gold_ingot");
        gApple.put(4, "gold_ingot");
        gApple.put(5, "apple");
        gApple.put(6, "gold_ingot");
        gApple.put(7, "gold_ingot");
        gApple.put(8, "gold_ingot");
        gApple.put(9, "gold_ingot");
        RECIPES.put("golden_apple", new RecipeDef(gApple, Map.of("gold_ingot", 8, "apple", 1)));

        // Arrow: 1 flint (2), 1 stick (5), 1 feather (8)
        Map<Integer, String> arrow = new LinkedHashMap<>();
        arrow.put(2, "flint");
        arrow.put(5, "stick");
        arrow.put(8, "feather");
        RECIPES.put("arrow", new RecipeDef(arrow, Map.of("flint", 1, "stick", 1, "feather", 1)));

        // Bow: 3 sticks (2, 4, 8), 3 strings (3, 6, 9)
        Map<Integer, String> bow = new LinkedHashMap<>();
        bow.put(2, "stick");
        bow.put(3, "string");
        bow.put(4, "stick");
        bow.put(6, "string");
        bow.put(8, "stick");
        bow.put(9, "string");
        RECIPES.put("bow", new RecipeDef(bow, Map.of("stick", 3, "string", 3)));

        // Blaze Powder: 1 blaze rod (5)
        Map<Integer, String> blazePowder = new LinkedHashMap<>();
        blazePowder.put(5, "blaze_rod");
        RECIPES.put("blaze_powder", new RecipeDef(blazePowder, Map.of("blaze_rod", 1)));

        // Eye of Ender: 1 blaze powder (4), 1 ender pearl (5)
        Map<Integer, String> enderEye = new LinkedHashMap<>();
        enderEye.put(4, "blaze_powder");
        enderEye.put(5, "ender_pearl");
        RECIPES.put("ender_eye", new RecipeDef(enderEye, Map.of("blaze_powder", 1, "ender_pearl", 1)));
        RECIPES.put("eye_of_ender", new RecipeDef(enderEye, Map.of("blaze_powder", 1, "ender_pearl", 1)));

        // Torch: 1 coal (2), 1 stick (5)
        Map<Integer, String> torch = new LinkedHashMap<>();
        torch.put(2, "coal");
        torch.put(5, "stick");
        RECIPES.put("torch", new RecipeDef(torch, Map.of("coal", 1, "stick", 1)));

        // Chest: 8 planks (1,2,3,4,6,7,8,9)
        Map<Integer, String> chest = new LinkedHashMap<>();
        chest.put(1, "plank");
        chest.put(2, "plank");
        chest.put(3, "plank");
        chest.put(4, "plank");
        chest.put(6, "plank");
        chest.put(7, "plank");
        chest.put(8, "plank");
        chest.put(9, "plank");
        RECIPES.put("chest", new RecipeDef(chest, Map.of("plank", 8)));

        // Netherite Ingot: 4 netherite scrap + 4 gold ingots
        Map<Integer, String> nIngot = new LinkedHashMap<>();
        nIngot.put(1, "netherite_scrap");
        nIngot.put(2, "netherite_scrap");
        nIngot.put(4, "netherite_scrap");
        nIngot.put(5, "netherite_scrap");
        nIngot.put(3, "gold_ingot");
        nIngot.put(6, "gold_ingot");
        nIngot.put(7, "gold_ingot");
        nIngot.put(8, "gold_ingot");
        RECIPES.put("netherite_ingot", new RecipeDef(nIngot, Map.of("netherite_scrap", 4, "gold_ingot", 4)));
    }

    public CraftInTableTask(String itemTarget, int targetCount) {
        this.itemTarget = itemTarget.toLowerCase();
        this.targetCount = targetCount;
    }

    public CraftInTableTask(String itemTarget) {
        this(itemTarget, 1);
    }

    @Override
    protected void onStart() {
        finished = false;
        stepTimer = 0;
        placedTablePos = null;
        noSpotTicks = 0;
        placedTableWaitTicks = 0;
        craftingGuiTicks = 0;
        ticksSinceLastCraftAction = 0;
        extractAttempts = 0;
        recipeBookAttempts = 0;
        lastIngredientSourceSlot = -1;
        currentIngredientKeyword = null;
        craftingMenuOpenCloseLoops = 0;
        wasMenuOpen = false;
        missingIngredientTicks = 0;
        lastTargetCount = -1;
        setDebugState("Crafting " + itemTarget + " in Crafting Table...");
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) return null;

        if (stepTimer-- > 0) return null;

        // 1. Success check: target items already in player inventory?
        if (InventoryManager.countItems(player, itemTarget) >= targetCount) {
            if (player.containerMenu instanceof CraftingMenu) {
                player.closeContainer();
            }
            finished = true;
            cancelBaritonePathing();
            return null;
        }

        RecipeDef recipe = RECIPES.get(itemTarget);
        if (recipe == null) {
            setDebugState("Unknown recipe target: " + itemTarget);
            finished = true;
            return null;
        }

        // 2. Crafting table menu is currently open!
        if (player.containerMenu instanceof CraftingMenu menu) {
            cancelBaritonePathing();
            noSpotTicks = 0;
            placedTableWaitTicks = 0;
            if (!wasMenuOpen) {
                wasMenuOpen = true;
                craftingMenuOpenCloseLoops++;
                craftingGuiTicks = 0;
                ticksSinceLastCraftAction = 0;
                extractAttempts = 0;
                recipeBookAttempts = 0;
                lastIngredientSourceSlot = -1;
                currentIngredientKeyword = null;
            }
            executeRecipeCraft(mc, player, menu, recipe);
            return null;
        }

        wasMenuOpen = false;
        craftingGuiTicks = 0;
        ticksSinceLastCraftAction = 0;
        lastIngredientSourceSlot = -1;
        currentIngredientKeyword = null;

        // 3. Safety Watchdog: If stuck in repeated open-close crafting loops (>= 6 times):
        // Mine the Crafting Table to break the loop, pick it up, and relocate!
        if (craftingMenuOpenCloseLoops >= 6) {
            BlockPos nearby = findNearbyTable(mc, player);
            if (nearby != null) {
                setDebugState("Safety Watchdog: Stuck in crafting loop (" + craftingMenuOpenCloseLoops + "x)! Mining table to reset...");
                int pickSlot = MineBlockTask.getPickaxeHotbarSlot(player);
                if (pickSlot != -1) {
                    player.getInventory().setSelectedSlot(pickSlot);
                }
                mc.gameMode.startDestroyBlock(nearby, Direction.UP);
                if (!mc.level.getBlockState(nearby).is(Blocks.CRAFTING_TABLE)) {
                    craftingMenuOpenCloseLoops = 0;
                    placedTablePos = null;
                    placedTableWaitTicks = 0;
                    noSpotTicks = 0;
                }
                stepTimer = 4;
                return null;
            } else {
                craftingMenuOpenCloseLoops = 0;
            }
        }

        // 4. Verify all ingredients exist in player inventory (when table is NOT open)
        if (itemTarget.equals("furnace")) {
            int normalCobble = InventoryManager.countItems(player, "cobblestone");
            int deepslateCobble = InventoryManager.countItems(player, "cobbled_deepslate");
            int blackstone = InventoryManager.countItems(player, "blackstone");
            if (normalCobble < 8 && deepslateCobble < 8 && blackstone < 8) {
                missingIngredientTicks++;
                if (missingIngredientTicks > 12) {
                    setDebugState("Missing 8x homogeneous stone for furnace. Aborting craft to re-gather...");
                    finished = true;
                    if (player.containerMenu instanceof CraftingMenu) {
                        player.closeContainer();
                    }
                    cancelBaritonePathing();
                    return null;
                }
                setDebugState("Need 8 of same stone type for furnace (have " + Math.max(normalCobble, Math.max(deepslateCobble, blackstone)) + "/8)");
                return null;
            } else {
                missingIngredientTicks = 0;
            }
        }

        for (Map.Entry<String, Integer> req : recipe.requiredCounts.entrySet()) {
            if (InventoryManager.countItems(player, req.getKey()) < req.getValue()) {
                // If missing ingredient is sticks, auto-craft them if we have wood/planks
                if (req.getKey().equals("stick")) {
                    int planks = InventoryManager.countItems(player, "plank");
                    if (planks >= 2) {
                        int plankSlot = findPlankSlotInInventory(player, 2);
                        if (plankSlot != -1) {
                            setDebugState("Auto 2x2 crafting sticks...");
                            AltoClef.getInstance().getInventoryManager().craft2x2Sticks(mc, player, plankSlot);
                            stepTimer = 3;
                            return null;
                        }
                    } else {
                        int logs = InventoryManager.countItems(player, "log");
                        if (logs > 0) {
                            int logSlot = findLogSlotInInventory(player);
                            if (logSlot != -1) {
                                setDebugState("Auto 2x2 crafting planks for sticks...");
                                AltoClef.getInstance().getInventoryManager().craft2x2Planks(mc, player, logSlot);
                                stepTimer = 3;
                                return null;
                            }
                        }
                    }
                }
                // If missing ingredient is planks, auto-craft them from logs
                else if (req.getKey().equals("plank")) {
                    int logs = InventoryManager.countItems(player, "log");
                    if (logs > 0) {
                        int logSlot = findLogSlotInInventory(player);
                        if (logSlot != -1) {
                            setDebugState("Auto 2x2 crafting planks from logs...");
                            AltoClef.getInstance().getInventoryManager().craft2x2Planks(mc, player, logSlot);
                            stepTimer = 3;
                            return null;
                        }
                    }
                }

                missingIngredientTicks++;
                if (missingIngredientTicks > 12) {
                    setDebugState("Missing ingredient (" + req.getKey() + "). Aborting craft to re-gather...");
                    finished = true;
                    if (player.containerMenu instanceof CraftingMenu) {
                        player.closeContainer();
                    }
                    cancelBaritonePathing();
                    return null;
                }
                setDebugState("Missing ingredient: need " + req.getValue() + "x " + req.getKey());
                return null;
            }
        }
        missingIngredientTicks = 0;

        // 4. Find nearby reachable Crafting Table in world
        BlockPos existingTable = findNearbyTable(mc, player);
        if (existingTable != null) {
            cancelBaritonePathing();
            noSpotTicks = 0;
            placedTableWaitTicks++;

            // If any non-air block is directly above the table obstructing it, break it!
            BlockPos above = existingTable.above();
            if (!mc.level.getBlockState(above).isAir() && !mc.level.getBlockState(above).canBeReplaced()) {
                setDebugState("Clearing block obstructing Crafting Table: " + above.toShortString());
                int pickSlot = MineBlockTask.getPickaxeHotbarSlot(player);
                if (pickSlot != -1) {
                    player.getInventory().setSelectedSlot(pickSlot);
                }
                mc.gameMode.startDestroyBlock(above, Direction.UP);
                stepTimer = 4;
                return null;
            }

            // Find an open face exposed to air/replaceable block
            Direction hitFace = Direction.UP;
            Vec3 hitVec = Vec3.atCenterOf(existingTable).add(0, 0.5, 0);
            if (!mc.level.getBlockState(above).isAir() && !mc.level.getBlockState(above).canBeReplaced()) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbor = existingTable.relative(dir);
                    if (mc.level.getBlockState(neighbor).isAir() || mc.level.getBlockState(neighbor).canBeReplaced()) {
                        hitFace = dir;
                        hitVec = Vec3.atCenterOf(existingTable).add(dir.getStepX() * 0.5, 0, dir.getStepZ() * 0.5);
                        break;
                    }
                }
            }

            setDebugState("Opening Crafting Table at " + existingTable.toShortString());
            lookAt(player, hitVec);
            if (player.isShiftKeyDown()) {
                player.setShiftKeyDown(false);
            }
            BlockHitResult hit = new BlockHitResult(hitVec, hitFace, existingTable, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);

            // Watchdog: If placed/existing table isn't opening after multiple attempts (obstructed, in a hole, or blocked):
            if (placedTableWaitTicks > 14) {
                setDebugState("Crafting Table obstructed/unresponsive. Mining table to relocate...");
                int pickSlot = MineBlockTask.getPickaxeHotbarSlot(player);
                if (pickSlot != -1) {
                    player.getInventory().setSelectedSlot(pickSlot);
                }
                mc.gameMode.startDestroyBlock(existingTable, Direction.UP);
                if (!mc.level.getBlockState(existingTable).is(Blocks.CRAFTING_TABLE)) {
                    placedTablePos = null;
                    placedTableWaitTicks = 0;
                    noSpotTicks = 0;
                }
                stepTimer = 4;
                return null;
            }

            // If placed table isn't opening after a few attempts, step closer
            if (placedTableWaitTicks > 6) {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(existingTable, 1));
                }
            }

            stepTimer = 3;
            return null;
        }

        placedTableWaitTicks = 0;

        // 4b. If we have no Crafting Table in inventory, check if one is located further away (up to 16 blocks)
        int tableItemCount = InventoryManager.countItems(player, "crafting_table");
        if (tableItemCount == 0) {
            BlockPos distantTable = findDistantTable(mc, player, 16);
            if (distantTable != null) {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(distantTable, 2));
                    setDebugState("Navigating to existing Crafting Table at " + distantTable.toShortString());
                }
                stepTimer = 4;
                return null;
            }
        }

        // 5. Check if we need to craft a Crafting Table first
        if (tableItemCount == 0) {
            int planks = InventoryManager.countItems(player, "plank");
            if (planks >= 4) {
                setDebugState("Auto 2x2 crafting Crafting Table...");
                int plankSlot = findPlankSlotInInventory(player, 4);
                if (plankSlot != -1) {
                    AltoClef.getInstance().getInventoryManager().craft2x2CraftingTable(mc, player, plankSlot);
                    stepTimer = 4;
                    return null;
                }
            } else {
                int logs = InventoryManager.countItems(player, "log");
                if (logs > 0) {
                    setDebugState("Auto 2x2 crafting planks for Crafting Table...");
                    int logSlot = findLogSlotInInventory(player);
                    if (logSlot != -1) {
                        AltoClef.getInstance().getInventoryManager().craft2x2Planks(mc, player, logSlot);
                        stepTimer = 4;
                        return null;
                    }
                }
                setDebugState("Need 4 planks to craft Crafting Table!");
                return null;
            }
        }

        // 6. Find placing spot or execute backup escape plan if trapped in a 1x1 hole / confined area
        BlockPos placePos = findPlacingSpot(mc, player);
        if (placePos == null) {
            noSpotTicks++;

            // Backup Plan: Trapped in a 1x1 hole or confined tunnel
            if (noSpotTicks > 6) {
                BlockPos playerPos = player.blockPosition();

                // Count solid horizontal walls around feet
                int solidWalls = 0;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    if (mc.level.getBlockState(playerPos.relative(dir)).isSolid()) {
                        solidWalls++;
                    }
                }

                // If in a 1x1 hole (3 or 4 solid walls), try jump / mine out
                if (solidWalls >= 3) {
                    setDebugState("In 1x1 hole (" + solidWalls + "/4 walls solid). Escaping to clear space...");

                    // If ceiling has air, jump out
                    if (player.onGround() && mc.level.getBlockState(playerPos.above(2)).isAir()) {
                        player.jumpFromGround();
                    }

                    // Mine wall in front to clear space for placing
                    Direction facing = player.getDirection();
                    BlockPos wallInFront = playerPos.relative(facing);
                    if (mc.level.getBlockState(wallInFront).isSolid()) {
                        mc.gameMode.startDestroyBlock(wallInFront, Direction.UP);
                    }
                }

                // Command Baritone to find and navigate to the nearest open, flat ground
                BlockPos openGround = findNearestOpenGround(mc, player, 16);
                if (openGround != null) {
                    IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (baritone != null && !baritone.getPathingBehavior().isPathing()) {
                        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(openGround, 1));
                        setDebugState("Navigating out of confined space to open ground at " + openGround.toShortString());
                    }
                }
            } else {
                setDebugState("Looking for clear spot to place Crafting Table...");
            }
            return null;
        }

        // We found a valid placing spot! Stop any navigation pathing
        cancelBaritonePathing();
        noSpotTicks = 0;

        int hotbarSlot = ensureHeldItem(mc, player, "crafting_table");
        if (hotbarSlot == -1) {
            setDebugState("Unable to swap Crafting Table to hotbar!");
            return null;
        }

        BlockPos support = placePos.below();
        Vec3 hitVec = Vec3.atCenterOf(support).add(0, 0.5, 0);
        lookAt(player, hitVec);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, support, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        placedTablePos = placePos;
        stepTimer = 3;
        setDebugState("Placed Crafting Table at " + placePos.toShortString() + ", interacting next tick...");

        return null;
    }

    private int countTargetInMenuOrInventory(CraftingMenu menu, LocalPlayer player, String target) {
        int count = InventoryManager.countItems(player, target);
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty() && getItemName(carried).contains(target)) {
            count += carried.getCount();
        }
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && getItemName(stack).contains(target)) {
                // If container slot already accounted for by player inventory, ensure max is reported
                count = Math.max(count, stack.getCount());
            }
        }
        return count;
    }

    private void executeRecipeCraft(Minecraft mc, LocalPlayer player, CraftingMenu menu, RecipeDef recipe) {
        int containerId = menu.containerId;
        craftingGuiTicks++;
        ticksSinceLastCraftAction++;

        // Track changes to player target count
        int currentCount = InventoryManager.countItems(player, itemTarget);
        if (currentCount > lastTargetCount) {
            lastTargetCount = currentCount;
            recipeBookAttempts = 0;
            extractAttempts = 0;
        }

        // 1. If cursor is holding the target item, deposit it into an inventory slot!
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty() && isCraftTarget(carried, itemTarget)) {
            int targetSlot = findSlotToDepositTarget(menu, itemTarget);
            if (targetSlot != -1 && targetSlot != -999) {
                setDebugState("Depositing crafted " + itemTarget + " into inventory (slot " + targetSlot + ")...");
                ItemStack dest = menu.getSlot(targetSlot).getItem();
                if (!dest.isEmpty() && !dest.is(carried.getItem())) {
                    // Throw out the disposable junk item to free the slot
                    mc.gameMode.handleContainerInput(containerId, targetSlot, 1, ContainerInput.THROW, player);
                }
                mc.gameMode.handleContainerInput(containerId, targetSlot, 0, ContainerInput.PICKUP, player);
            } else {
                int throwSlot = findDisposableSlotInContainer(menu);
                if (throwSlot != -1) {
                    setDebugState("Throwing out junk in slot " + throwSlot + " to make room for " + itemTarget);
                    mc.gameMode.handleContainerInput(containerId, throwSlot, 1, ContainerInput.THROW, player);
                    mc.gameMode.handleContainerInput(containerId, throwSlot, 0, ContainerInput.PICKUP, player);
                } else {
                    mc.gameMode.handleContainerInput(containerId, -999, 0, ContainerInput.PICKUP, player);
                }
            }
            ticksSinceLastCraftAction = 0;
            stepTimer = 2;
            return;
        }

        // 2. Success check: Does player inventory already have targetCount items?
        if (currentCount >= targetCount) {
            // Deposit carried item if still holding anything
            if (!menu.getCarried().isEmpty()) {
                clearCursor(mc, player, menu, containerId);
                stepTimer = 2;
                return;
            }
            player.closeContainer();
            setDebugState("Successfully crafted " + itemTarget + "! Closed Crafting Table.");
            finished = true;
            craftingMenuOpenCloseLoops = 0;
            craftingGuiTicks = 0;
            ticksSinceLastCraftAction = 0;
            return;
        }

        // Check if all recipe grid slots are satisfied
        String dominantStone = itemTarget.equals("furnace") ? getDominantStoneType(menu) : null;
        boolean allSlotsSatisfied = true;
        for (Map.Entry<Integer, String> entry : recipe.gridSlots.entrySet()) {
            ItemStack inSlot = menu.getSlot(entry.getKey()).getItem();
            if (inSlot.isEmpty() || !matchesKeyword(getItemName(inSlot), entry.getValue())) {
                allSlotsSatisfied = false;
                break;
            }
            if (dominantStone != null && entry.getValue().equals("cobble") && !getItemName(inSlot).contains(dominantStone)) {
                allSlotsSatisfied = false;
                break;
            }
        }

        // 3. Result Slot (Slot 0) Extraction:
        // Extract if slot 0 is populated and matches target, or if all recipe grid slots are satisfied
        ItemStack resultStack = menu.getSlot(0).getItem();
        if (!resultStack.isEmpty() && (isCraftTarget(resultStack, itemTarget) || allSlotsSatisfied)) {
            // Before extracting, ensure cursor is empty of leftovers
            if (!menu.getCarried().isEmpty()) {
                clearCursor(mc, player, menu, containerId);
                ticksSinceLastCraftAction = 0;
                stepTimer = 2;
                return;
            }

            extractAttempts++;
            ticksSinceLastCraftAction = 0;
            recipeBookAttempts = 0;
            setDebugState("Extracting " + getItemName(resultStack) + " from craft result slot 0...");
            mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
            if (extractAttempts >= 2 && !menu.getSlot(0).getItem().isEmpty()) {
                mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.PICKUP, player);
            }
            stepTimer = 2;
            return;
        } else {
            if (!allSlotsSatisfied) {
                extractAttempts = 0;
            }
        }

        // 4. If all recipe slots are satisfied but slot 0 is still empty, wait / nudge server
        if (allSlotsSatisfied) {
            if (!menu.getCarried().isEmpty()) {
                clearCursor(mc, player, menu, containerId);
                stepTimer = 2;
                return;
            }
            if (ticksSinceLastCraftAction > 4 && ticksSinceLastCraftAction % 3 == 0) {
                setDebugState("Nudging server craft evaluation on slot 0...");
                mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
            }
            stepTimer = 2;
            return;
        }

        // 5. AUTO-CRAFT FAST PATH: Modern Minecraft Native Recipe Book Packet
        if (menu.getCarried().isEmpty() && recipeBookAttempts < 3) {
            RecipeDisplayId recipeId = findRecipeInBook(player, itemTarget);
            if (recipeId != null) {
                recipeBookAttempts++;
                setDebugState("Auto-crafting " + itemTarget + " via Recipe Book...");
                mc.gameMode.handlePlaceRecipe(containerId, recipeId, true);
                mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
                ticksSinceLastCraftAction = 0;
                stepTimer = 2;
                return;
            }
        }

        // 6. Handle Carried Item in Cursor (Manual Fallback):
        if (!menu.getCarried().isEmpty()) {
            ItemStack carriedStack = menu.getCarried();
            String carriedName = getItemName(carriedStack);

            if (currentIngredientKeyword == null) {
                for (String kw : recipe.requiredCounts.keySet()) {
                    if (matchesKeyword(carriedName, kw)) {
                        currentIngredientKeyword = kw;
                        break;
                    }
                }
            }

            boolean matchesDominant = dominantStone == null || currentIngredientKeyword == null || !currentIngredientKeyword.equals("cobble") || carriedName.contains(dominantStone);

            // Is the carried item an ingredient we need to place into unpopulated grid slots?
            if (currentIngredientKeyword != null && matchesKeyword(carriedName, currentIngredientKeyword) && matchesDominant) {
                int placedCount = 0;
                for (Map.Entry<Integer, String> entry : recipe.gridSlots.entrySet()) {
                    if (entry.getValue().equals(currentIngredientKeyword)) {
                        ItemStack inSlot = menu.getSlot(entry.getKey()).getItem();
                        boolean needs = inSlot.isEmpty() || !matchesKeyword(getItemName(inSlot), currentIngredientKeyword);
                        if (!needs && dominantStone != null && entry.getValue().equals("cobble") && !getItemName(inSlot).contains(dominantStone)) {
                            needs = true;
                        }
                        if (needs) {
                            // Right-click grid slot to place 1 item
                            mc.gameMode.handleContainerInput(containerId, entry.getKey(), 1, ContainerInput.PICKUP, player);
                            placedCount++;
                            if (menu.getCarried().isEmpty()) break;
                        }
                    }
                }

                if (placedCount > 0) {
                    setDebugState("Placed " + placedCount + "x " + currentIngredientKeyword + " into crafting grid");
                    ticksSinceLastCraftAction = 0;
                    stepTimer = 2;
                    return;
                }
            }

            // If carried item is no longer needed in the grid, return it to inventory
            clearCursor(mc, player, menu, containerId);
            ticksSinceLastCraftAction = 0;
            stepTimer = 2;
            return;
        }

        // 7. Clear any stray/wrong items in the 3x3 crafting grid (slots 1..9)
        for (int s = 1; s <= 9; s++) {
            ItemStack inSlot = menu.getSlot(s).getItem();
            if (!inSlot.isEmpty()) {
                String expectedKeyword = recipe.gridSlots.get(s);
                boolean invalid = expectedKeyword == null || !matchesKeyword(getItemName(inSlot), expectedKeyword);
                if (!invalid && dominantStone != null && expectedKeyword.equals("cobble") && !getItemName(inSlot).contains(dominantStone)) {
                    invalid = true;
                }
                if (invalid) {
                    setDebugState("Clearing stray/mixed item " + getItemName(inSlot) + " from grid slot " + s);
                    mc.gameMode.handleContainerInput(containerId, s, 0, ContainerInput.QUICK_MOVE, player);
                    ticksSinceLastCraftAction = 0;
                    stepTimer = 2;
                    return;
                }
            }
        }

        // 8. Grid is not satisfied: pick up the next required ingredient
        for (Map.Entry<Integer, String> entry : recipe.gridSlots.entrySet()) {
            int slot = entry.getKey();
            String keyword = entry.getValue();
            ItemStack inSlot = menu.getSlot(slot).getItem();
            boolean needs = inSlot.isEmpty() || !matchesKeyword(getItemName(inSlot), keyword);
            if (!needs && dominantStone != null && keyword.equals("cobble") && !getItemName(inSlot).contains(dominantStone)) {
                needs = true;
            }
            if (needs) {
                String searchKeyword = (dominantStone != null && keyword.equals("cobble")) ? dominantStone : keyword;
                int invSlot = findBestSlotInContainer(menu, searchKeyword);
                if (invSlot == -1) {
                    setDebugState("Missing ingredient stack for " + searchKeyword + " in inventory!");
                    clearCursor(mc, player, menu, containerId);
                    player.closeContainer();
                    finished = true;
                    stepTimer = 4;
                    return;
                }

                setDebugState("Picking up " + searchKeyword + " from inventory slot " + invSlot);
                lastIngredientSourceSlot = invSlot;
                currentIngredientKeyword = keyword;
                mc.gameMode.handleContainerInput(containerId, invSlot, 0, ContainerInput.PICKUP, player);
                ticksSinceLastCraftAction = 0;
                stepTimer = 2;
                return;
            }
        }

        // 9. Watchdog inside GUI:
        // If inactive for > 70 ticks (~3.5s) or GUI open for > 140 ticks (~7.0s) without completing:
        if (ticksSinceLastCraftAction > 70 || craftingGuiTicks > 140) {
            setDebugState("Watchdog: Crafting GUI unresponsive (" + ticksSinceLastCraftAction + " idle ticks). Resetting...");
            clearCursor(mc, player, menu, containerId);
            player.closeContainer();
            craftingGuiTicks = 0;
            ticksSinceLastCraftAction = 0;
            lastIngredientSourceSlot = -1;
            currentIngredientKeyword = null;
            stepTimer = 4;
            return;
        }

        stepTimer = 2;
    }

    private void clearCraftingGrid(Minecraft mc, LocalPlayer player, CraftingMenu menu, int containerId) {
        for (int s = 1; s <= 9; s++) {
            if (!menu.getSlot(s).getItem().isEmpty()) {
                mc.gameMode.handleContainerInput(containerId, s, 0, ContainerInput.QUICK_MOVE, player);
            }
        }
        clearCursor(mc, player, menu, containerId);
    }

    private BlockPos findNearestOpenGround(Minecraft mc, LocalPlayer player, int radius) {
        if (mc.level == null) return null;
        BlockPos center = player.blockPosition();
        BlockPos bestPos = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dy = -2; dy <= 4; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    BlockState ground = mc.level.getBlockState(p);
                    if (!ground.isSolid() || ground.canBeReplaced()) continue;

                    BlockState above1 = mc.level.getBlockState(p.above());
                    BlockState above2 = mc.level.getBlockState(p.above(2));
                    if (!above1.isAir() && !above1.canBeReplaced()) continue;
                    if (!above2.isAir() && !above2.canBeReplaced()) continue;

                    // Ensure at least 2 horizontal neighbors are clear (open area!)
                    int clearNeighbors = 0;
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockState nState = mc.level.getBlockState(p.above().relative(dir));
                        if (nState.isAir() || nState.canBeReplaced()) {
                            clearNeighbors++;
                        }
                    }
                    if (clearNeighbors < 2) continue;

                    double d = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(p.above()));
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        bestPos = p.above();
                    }
                }
            }
        }
        return bestPos;
    }

    private void cancelBaritonePathing() {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null && baritone.getPathingBehavior().isPathing()) {
                baritone.getPathingBehavior().forceCancel();
            }
        } catch (Throwable ignored) {
        }
    }

    private BlockPos findNearbyTable(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;
        Vec3 eyePos = player.getEyePosition();

        if (placedTablePos != null && mc.level.getBlockState(placedTablePos).is(Blocks.CRAFTING_TABLE)) {
            if (eyePos.distanceTo(Vec3.atCenterOf(placedTablePos)) <= 4.2) {
                return placedTablePos;
            }
        }

        BlockPos center = player.blockPosition();
        BlockPos bestTable = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int x = -3; x <= 3; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -3; z <= 3; z++) {
                    BlockPos p = center.offset(x, y, z);
                    if (mc.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
                        double d = eyePos.distanceToSqr(Vec3.atCenterOf(p));
                        if (d <= 18.0 && d < bestDistSq) {
                            bestDistSq = d;
                            bestTable = p;
                        }
                    }
                }
            }
        }
        return bestTable;
    }

    private BlockPos findDistantTable(Minecraft mc, LocalPlayer player, int radius) {
        if (mc.level == null) return null;
        Vec3 eyePos = player.getEyePosition();
        BlockPos center = player.blockPosition();
        BlockPos bestTable = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos p = center.offset(x, y, z);
                    if (mc.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
                        double d = eyePos.distanceToSqr(Vec3.atCenterOf(p));
                        if (d < bestDistSq) {
                            bestDistSq = d;
                            bestTable = p;
                        }
                    }
                }
            }
        }
        return bestTable;
    }

    private BlockPos findPlacingSpot(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return null;
        BlockPos playerPos = player.blockPosition();
        Vec3 eyePos = player.getEyePosition();
        AABB playerBox = player.getBoundingBox();

        BlockPos bestSpot = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dy = 0; dy >= -1; dy--) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0 && dy == 0) continue;

                    BlockPos target = playerPos.offset(dx, dy, dz);
                    BlockPos support = target.below();

                    BlockState targetState = mc.level.getBlockState(target);
                    if (!targetState.isAir() && !targetState.canBeReplaced()) {
                        continue;
                    }

                    // Ensure space above target is clear so table is never obstructed
                    BlockState aboveState = mc.level.getBlockState(target.above());
                    if (!aboveState.isAir() && !aboveState.canBeReplaced()) {
                        continue;
                    }

                    AABB targetBox = new AABB(target);
                    if (playerBox.intersects(targetBox)) {
                        continue;
                    }

                    BlockState supportState = mc.level.getBlockState(support);
                    if (!supportState.isSolid() || supportState.canBeReplaced()) {
                        continue;
                    }

                    Vec3 supportTop = Vec3.atCenterOf(support).add(0, 0.5, 0);
                    double distSq = eyePos.distanceToSqr(supportTop);
                    if (distSq > 16.0) {
                        continue;
                    }

                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        bestSpot = target;
                    }
                }
            }
        }
        return bestSpot;
    }

    private int ensureHeldItem(Minecraft mc, LocalPlayer player, String keyword) {
        keyword = keyword.toLowerCase();

        // 1. Already selected in hotbar?
        ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty() && getItemName(mainHand).contains(keyword)) {
            return player.getInventory().getSelectedSlot();
        }

        // 2. In any hotbar slot (0..8)?
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && getItemName(stack).contains(keyword)) {
                player.getInventory().setSelectedSlot(i);
                return i;
            }
        }

        // 3. In main inventory (slots 9..35)? Swap to hotbar!
        int targetHotbar = 3;
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).isEmpty()) {
                targetHotbar = i;
                break;
            }
        }

        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.INV_SLOT_END; i++) {
            ItemStack stack = player.inventoryMenu.getSlot(i).getItem();
            if (!stack.isEmpty() && getItemName(stack).contains(keyword)) {
                mc.gameMode.handleContainerInput(
                        InventoryMenu.CONTAINER_ID,
                        i,
                        targetHotbar,
                        ContainerInput.SWAP,
                        player
                );
                player.getInventory().setSelectedSlot(targetHotbar);
                return targetHotbar;
            }
        }

        return -1;
    }

    private void lookAt(LocalPlayer player, Vec3 target) {
        Vec3 diff = target.subtract(player.getEyePosition());
        double distXZ = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(diff.z, diff.x))) - 90.0F;
        float pitch = (float) (-Math.toDegrees(Math.atan2(diff.y, distXZ)));
        player.setYRot(yaw);
        player.setXRot(pitch);
    }

    public static String getItemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        try {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase();
        } catch (Throwable t) {
            return stack.getItem().toString().toLowerCase();
        }
    }

    public static boolean matchesKeyword(String itemName, String keyword) {
        if (itemName == null || keyword == null) return false;
        itemName = itemName.toLowerCase();
        keyword = keyword.toLowerCase();
        if (itemName.equals(keyword) || itemName.contains(keyword)) {
            return true;
        }
        if (keyword.equals("cobble")) {
            return itemName.contains("cobblestone") || itemName.contains("cobbled_deepslate") || itemName.contains("blackstone");
        }
        if (keyword.equals("plank") || keyword.equals("planks")) {
            return itemName.endsWith("_planks") || itemName.contains("plank");
        }
        if (keyword.equals("log")) {
            return itemName.endsWith("_log") || itemName.endsWith("_wood") || itemName.endsWith("_stem") || itemName.contains("log");
        }
        if (keyword.equals("wool")) {
            return itemName.endsWith("_wool") || itemName.contains("wool");
        }
        return false;
    }

    public static boolean matchesRecipeTarget(String resultName, String target) {
        if (resultName == null || target == null) return false;
        resultName = resultName.toLowerCase().replace("minecraft:", "").trim();
        target = target.toLowerCase().replace("minecraft:", "").trim();
        if (resultName.equals(target)) return true;

        // Check material compatibility if material is specified in target
        String[] materials = {"wooden", "wood", "stone", "iron", "golden", "gold", "diamond", "netherite"};
        for (String mat : materials) {
            boolean targetHasMat = target.contains(mat);
            boolean resultHasMat = resultName.contains(mat) || (mat.equals("wood") && resultName.contains("wooden")) || (mat.equals("gold") && resultName.contains("golden"));
            if (targetHasMat && !resultHasMat) {
                return false;
            }
        }

        if (resultName.contains(target) || target.contains(resultName)) return true;
        if (target.contains("pickaxe") && resultName.contains("pickaxe")) return true;
        if (target.contains("sword") && resultName.contains("sword")) return true;
        if (target.contains("axe") && !target.contains("pickaxe") && resultName.contains("axe") && !resultName.contains("pickaxe")) return true;
        if (target.contains("shovel") && resultName.contains("shovel")) return true;
        if (target.contains("hoe") && resultName.contains("hoe")) return true;
        if (target.contains("helmet") && resultName.contains("helmet")) return true;
        if (target.contains("chestplate") && resultName.contains("chestplate")) return true;
        if (target.contains("leggings") && resultName.contains("leggings")) return true;
        if (target.contains("boots") && resultName.contains("boots")) return true;
        if (target.equals("plank") || target.equals("planks") || target.contains("plank")) {
            return resultName.endsWith("_planks") || resultName.contains("plank");
        }
        if (target.equals("bed") || target.contains("bed")) {
            return resultName.endsWith("_bed");
        }
        return false;
    }

    public static boolean isCraftTarget(ItemStack stack, String target) {
        if (stack == null || stack.isEmpty()) return false;
        String name = getItemName(stack);
        return matchesRecipeTarget(name, target) || matchesKeyword(name, target);
    }

    public static RecipeDisplayId findRecipeInBook(LocalPlayer player, String target) {
        if (player == null) return null;
        try {
            ClientRecipeBook book = player.getRecipeBook();
            if (book == null) return null;
            List<RecipeCollection> collections = book.getCollections();
            if (collections == null || collections.isEmpty()) return null;

            RecipeDisplayId craftableExact = null;
            RecipeDisplayId craftableAny = null;
            RecipeDisplayId fallbackExact = null;

            for (RecipeCollection col : collections) {
                List<RecipeDisplayEntry> recipes = col.getRecipes();
                if (recipes == null) continue;
                for (RecipeDisplayEntry entry : recipes) {
                    List<ItemStack> results = entry.resultItems(ContextMap.EMPTY);
                    if (results != null) {
                        for (ItemStack stack : results) {
                            if (!stack.isEmpty()) {
                                String name = getItemName(stack);
                                boolean isCraftable = col.isCraftable(entry.id());
                                if (matchesRecipeTarget(name, target)) {
                                    if (isCraftable) {
                                        return entry.id(); // Exact target match and craftable now!
                                    }
                                    if (fallbackExact == null) {
                                        fallbackExact = entry.id();
                                    }
                                } else if (matchesKeyword(name, target)) {
                                    if (isCraftable && craftableAny == null) {
                                        craftableAny = entry.id();
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (craftableExact != null) return craftableExact;
            if (fallbackExact != null) return fallbackExact;
            return craftableAny;
        } catch (Throwable t) {
            Debug.logWarning("Error searching recipe book: " + t.getMessage());
            return null;
        }
    }

    private String getDominantStoneType(CraftingMenu menu) {
        int normalCobble = 0;
        int deepslateCobble = 0;
        int blackstone = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (i == 0) continue; // skip result slot
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                String name = getItemName(stack);
                if (name.contains("cobbled_deepslate")) {
                    deepslateCobble += stack.getCount();
                } else if (name.contains("cobblestone")) {
                    normalCobble += stack.getCount();
                } else if (name.contains("blackstone")) {
                    blackstone += stack.getCount();
                }
            }
        }
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            String name = getItemName(carried);
            if (name.contains("cobbled_deepslate")) deepslateCobble += carried.getCount();
            else if (name.contains("cobblestone")) normalCobble += carried.getCount();
            else if (name.contains("blackstone")) blackstone += carried.getCount();
        }

        if (deepslateCobble >= 8) return "cobbled_deepslate";
        if (normalCobble >= 8) return "cobblestone";
        if (blackstone >= 8) return "blackstone";
        if (deepslateCobble >= normalCobble && deepslateCobble >= blackstone) return "cobbled_deepslate";
        if (normalCobble >= deepslateCobble && normalCobble >= blackstone) return "cobblestone";
        return "blackstone";
    }

    private int findBestSlotInContainer(CraftingMenu menu, String keyword) {
        int bestSlot = -1;
        int maxCount = 0;
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && matchesKeyword(getItemName(stack), keyword)) {
                if (keyword.equals("cobbled_deepslate") && !getItemName(stack).contains("cobbled_deepslate")) continue;
                if (keyword.equals("cobblestone") && !getItemName(stack).contains("cobblestone")) continue;
                if (keyword.equals("blackstone") && !getItemName(stack).contains("blackstone")) continue;
                if (stack.getCount() > maxCount) {
                    maxCount = stack.getCount();
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private int findEmptyPlayerSlotInContainer(CraftingMenu menu) {
        for (int i = 10; i < menu.slots.size(); i++) {
            if (menu.getSlot(i).getItem().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private int findMergeableSlot(CraftingMenu menu, ItemStack carried) {
        if (carried == null || carried.isEmpty()) return -1;
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && stack.is(carried.getItem()) && ItemStack.isSameItemSameComponents(carried, stack)) {
                if (stack.getCount() < stack.getMaxStackSize()) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean clearCursor(Minecraft mc, LocalPlayer player, CraftingMenu menu, int containerId) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) return true;

        // 1. Try returning to source slot if valid and empty or mergeable
        if (lastIngredientSourceSlot >= 10 && lastIngredientSourceSlot < menu.slots.size()) {
            ItemStack srcStack = menu.getSlot(lastIngredientSourceSlot).getItem();
            if (srcStack.isEmpty() || (srcStack.is(carried.getItem()) && ItemStack.isSameItemSameComponents(carried, srcStack) && srcStack.getCount() + carried.getCount() <= srcStack.getMaxStackSize())) {
                mc.gameMode.handleContainerInput(containerId, lastIngredientSourceSlot, 0, ContainerInput.PICKUP, player);
                lastIngredientSourceSlot = -1;
                currentIngredientKeyword = null;
                return true;
            }
        }

        // 2. Try merging with an existing stack with room
        int mergeSlot = findMergeableSlot(menu, carried);
        if (mergeSlot != -1) {
            mc.gameMode.handleContainerInput(containerId, mergeSlot, 0, ContainerInput.PICKUP, player);
            lastIngredientSourceSlot = -1;
            currentIngredientKeyword = null;
            return true;
        }

        // 3. Try finding any empty player slot
        int emptySlot = findEmptyPlayerSlotInContainer(menu);
        if (emptySlot != -1) {
            mc.gameMode.handleContainerInput(containerId, emptySlot, 0, ContainerInput.PICKUP, player);
            lastIngredientSourceSlot = -1;
            currentIngredientKeyword = null;
            return true;
        }

        // 4. Drop outside GUI
        mc.gameMode.handleContainerInput(containerId, -999, 0, ContainerInput.PICKUP, player);
        lastIngredientSourceSlot = -1;
        currentIngredientKeyword = null;
        return true;
    }

    private int findDisposableSlotInContainer(CraftingMenu menu) {
        int empty = findEmptyPlayerSlotInContainer(menu);
        if (empty != -1) return empty;
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                String name = getItemName(stack);
                if (name.contains("rotten_flesh") || name.contains("seeds") || name.contains("poisonous_potato") ||
                    name.contains("spider_eye") || name.contains("diorite") || name.contains("granite") ||
                    name.contains("andesite") || name.contains("tuff") || name.contains("gravel")) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int findSlotToDepositTarget(CraftingMenu menu, String target) {
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            int merge = findMergeableSlot(menu, carried);
            if (merge != -1) return merge;
        }
        int empty = findEmptyPlayerSlotInContainer(menu);
        if (empty != -1) return empty;
        return findDisposableSlotInContainer(menu);
    }

    private int findPlankSlotInInventory(LocalPlayer player, int minCount) {
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = player.inventoryMenu.getSlot(i).getItem();
            if (!stack.isEmpty() && matchesKeyword(getItemName(stack), "plank") && stack.getCount() >= minCount) {
                return i;
            }
        }
        return -1;
    }

    private int findLogSlotInInventory(LocalPlayer player) {
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) {
            ItemStack stack = player.inventoryMenu.getSlot(i).getItem();
            if (!stack.isEmpty() && matchesKeyword(getItemName(stack), "log")) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onStop(Task interruptTask) {
        cancelBaritonePathing();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu instanceof CraftingMenu) {
            mc.player.closeContainer();
        }
        if (mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        if (mc.options != null && mc.options.keyAttack != null) {
            mc.options.keyAttack.setDown(false);
        }
    }

    @Override
    public boolean isFinished() {
        if (finished) return true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            if (mc.player.containerMenu instanceof CraftingMenu menu) {
                if (!menu.getCarried().isEmpty()) {
                    return false;
                }
            }
            int count = InventoryManager.countItems(mc.player, itemTarget);
            if (count >= targetCount) {
                finished = true;
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CraftInTableTask t) {
            return t.itemTarget.equals(this.itemTarget) && t.targetCount == this.targetCount;
        }
        return false;
    }

    public boolean hasRequiredIngredients(LocalPlayer player) {
        if (player == null) return false;
        if (InventoryManager.countItems(player, itemTarget) >= targetCount) return true;
        RecipeDef recipe = RECIPES.get(itemTarget);
        if (recipe == null) return false;
        CraftingMenu menu = (player.containerMenu instanceof CraftingMenu cm) ? cm : null;
        if (menu != null) {
            if (isCraftTarget(menu.getCarried(), itemTarget)) return true;
            if (isCraftTarget(menu.getSlot(0).getItem(), itemTarget)) return true;
        }

        if (itemTarget.equals("furnace")) {
            int normalCobble = InventoryManager.countItems(player, "cobblestone");
            int deepslateCobble = InventoryManager.countItems(player, "cobbled_deepslate");
            int blackstone = InventoryManager.countItems(player, "blackstone");
            if (menu != null) {
                for (int s = 1; s <= 9; s++) {
                    ItemStack st = menu.getSlot(s).getItem();
                    if (!st.isEmpty()) {
                        String n = getItemName(st);
                        if (n.contains("cobbled_deepslate")) deepslateCobble += st.getCount();
                        else if (n.contains("cobblestone")) normalCobble += st.getCount();
                        else if (n.contains("blackstone")) blackstone += st.getCount();
                    }
                }
                ItemStack carried = menu.getCarried();
                if (!carried.isEmpty()) {
                    String n = getItemName(carried);
                    if (n.contains("cobbled_deepslate")) deepslateCobble += carried.getCount();
                    else if (n.contains("cobblestone")) normalCobble += carried.getCount();
                    else if (n.contains("blackstone")) blackstone += carried.getCount();
                }
            }
            if (normalCobble < 8 && deepslateCobble < 8 && blackstone < 8) {
                return false;
            }
        }

        for (Map.Entry<String, Integer> req : recipe.requiredCounts.entrySet()) {
            int count = InventoryManager.countItems(player, req.getKey());
            if (menu != null) {
                ItemStack carried = menu.getCarried();
                if (!carried.isEmpty() && matchesKeyword(getItemName(carried), req.getKey())) {
                    count += carried.getCount();
                }
                for (int s = 1; s <= 9; s++) {
                    ItemStack inSlot = menu.getSlot(s).getItem();
                    if (!inSlot.isEmpty() && matchesKeyword(getItemName(inSlot), req.getKey())) {
                        count += inSlot.getCount();
                    }
                }
            }
            if (req.getKey().equals("stick")) {
                if (count < req.getValue()) {
                    int planks = InventoryManager.countItems(player, "plank");
                    int logs = InventoryManager.countItems(player, "log");
                    if (planks < 2 && logs < 1) return false;
                }
            } else if (req.getKey().equals("plank")) {
                if (count < req.getValue()) {
                    int logs = InventoryManager.countItems(player, "log");
                    if (logs < 1) return false;
                }
            } else {
                if (count < req.getValue()) return false;
            }
        }
        return true;
    }

    @Override
    protected String toDebugString() {
        return "Crafting " + targetCount + "x " + itemTarget + " in Table";
    }
}
