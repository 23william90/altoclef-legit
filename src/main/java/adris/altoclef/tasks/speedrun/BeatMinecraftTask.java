package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.MineBlockTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class BeatMinecraftTask extends Task {

    private final AltoClef mod;
    private Task currentSubTask;
    private SpeedrunPhase currentPhase = SpeedrunPhase.GATHER_WOOD;
    private long enteredEndTimestamp = 0;

    public enum SpeedrunPhase {
        GATHER_WOOD("Phase 1: Gathering Wood & Crafting Tools"),
        MINE_STONE("Phase 2: Mining Stone & Upgrading Tools"),
        MINE_IRON("Phase 3: Mining Iron Ore & Smelting"),
        MINE_DIAMONDS("Phase 4: Mining Diamonds & Obsidian"),
        ENTER_NETHER("Phase 5: Constructing Portal & Entering Nether"),
        GATHER_BLAZE_RODS("Phase 6: Nether Fortress & Blaze Rods"),
        GATHER_ENDER_PEARLS("Phase 7: Bartering & Ender Pearls"),
        LOCATE_STRONGHOLD("Phase 8: Locating Stronghold & End Portal"),
        WAIT_FOR_END_CHUNKS("Phase 9: Waiting For End Chunks To Load"),
        SLAY_DRAGON("Phase 10: Slaying The Ender Dragon");

        private final String description;

        SpeedrunPhase(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public BeatMinecraftTask(AltoClef mod) {
        this.mod = mod;
    }

    @Override
    protected void onStart() {
        setDebugState("Initializing Gamer Speedrun Task (Marvion Optimized)...");
    }

    @Override
    protected Task onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            setDebugState("Waiting for player to load into world...");
            return null;
        }

        // Determine current phase based on player inventory and dimension
        determinePhase(mc);
        setDebugState(currentPhase.getDescription());

        // In The End: wait for chunks to fully load before acting
        if (currentPhase == SpeedrunPhase.WAIT_FOR_END_CHUNKS) {
            if (enteredEndTimestamp == 0) {
                enteredEndTimestamp = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() - enteredEndTimestamp < 2500) {
                setDebugState("Waiting for End terrain & chunks to stabilize...");
                return null;
            }
            currentPhase = SpeedrunPhase.SLAY_DRAGON;
        }

        // Check if current subtask is finished or needs updating
        if (currentSubTask == null || currentSubTask.isFinished()) {
            currentSubTask = createSubTaskForPhase(currentPhase);
        }

        return currentSubTask;
    }

    private void determinePhase(Minecraft mc) {
        int logs = countItemInInventory(mc, "log");
        int stone = countItemInInventory(mc, "stone", "cobble");
        int iron = countItemInInventory(mc, "iron");
        int diamonds = countItemInInventory(mc, "diamond");

        // Dimension checks
        String dimension = mc.level != null ? mc.level.dimension().toString().toLowerCase() : "overworld";

        if (dimension.contains("the_end")) {
            if (enteredEndTimestamp == 0 || System.currentTimeMillis() - enteredEndTimestamp < 2500) {
                currentPhase = SpeedrunPhase.WAIT_FOR_END_CHUNKS;
            } else {
                currentPhase = SpeedrunPhase.SLAY_DRAGON;
            }
        } else if (dimension.contains("nether")) {
            int blazeRods = countItemInInventory(mc, "blaze_rod");
            int pearls = countItemInInventory(mc, "ender_pearl");
            if (blazeRods < 7) {
                currentPhase = SpeedrunPhase.GATHER_BLAZE_RODS;
            } else if (pearls < 12) {
                currentPhase = SpeedrunPhase.GATHER_ENDER_PEARLS;
            } else {
                currentPhase = SpeedrunPhase.LOCATE_STRONGHOLD;
            }
        } else {
            // Overworld progression
            if (logs < 16 && stone < 10) {
                currentPhase = SpeedrunPhase.GATHER_WOOD;
            } else if (stone < 20 && iron < 5) {
                currentPhase = SpeedrunPhase.MINE_STONE;
            } else if (iron < 15 && diamonds < 3) {
                currentPhase = SpeedrunPhase.MINE_IRON;
            } else if (diamonds < 3) {
                currentPhase = SpeedrunPhase.MINE_DIAMONDS;
            } else {
                currentPhase = SpeedrunPhase.ENTER_NETHER;
            }
        }
    }

    private Task createSubTaskForPhase(SpeedrunPhase phase) {
        return switch (phase) {
            case GATHER_WOOD -> new MineBlockTask(
                    mod, "wood",
                    "oak_log,birch_log,spruce_log,jungle_log,acacia_log,dark_oak_log,mangrove_log,cherry_log,pale_oak_log",
                    16
            );
            case MINE_STONE -> new MineBlockTask(
                    mod, "stone",
                    "cobblestone,stone,deepslate,cobbled_deepslate",
                    24
            );
            case MINE_IRON -> new MineBlockTask(
                    mod, "iron ore",
                    "iron_ore,deepslate_iron_ore,raw_iron_block",
                    16
            );
            case MINE_DIAMONDS -> new MineBlockTask(
                    mod, "diamond ore",
                    "diamond_ore,deepslate_diamond_ore",
                    5
            );
            case ENTER_NETHER -> new MineBlockTask(
                    mod, "obsidian",
                    "obsidian",
                    10
            );
            case GATHER_BLAZE_RODS -> new MineBlockTask(
                    mod, "blaze rod / spawner",
                    "spawner,nether_bricks",
                    7
            );
            case GATHER_ENDER_PEARLS -> new MineBlockTask(
                    mod, "gold ore (for bartering)",
                    "nether_gold_ore,gold_block",
                    32
            );
            case LOCATE_STRONGHOLD -> new MineBlockTask(
                    mod, "stronghold stone",
                    "stone_bricks,cracked_stone_bricks,mossy_stone_bricks",
                    1
            );
            case WAIT_FOR_END_CHUNKS -> null;
            case SLAY_DRAGON -> new MineBlockTask(
                    mod, "end stone / pillars",
                    "end_stone,obsidian",
                    64
            );
        };
    }

    private int countItemInInventory(Minecraft mc, String... keywords) {
        if (mc.player == null) return 0;
        Inventory inv = mc.player.getInventory();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                String name = stack.getItem().toString().toLowerCase();
                for (String kw : keywords) {
                    if (name.contains(kw.toLowerCase())) {
                        count += stack.getCount();
                        break;
                    }
                }
            }
        }
        return count;
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (currentSubTask != null) {
            currentSubTask.stop(interruptTask);
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BeatMinecraftTask;
    }

    @Override
    protected String toDebugString() {
        return "Beat Minecraft (Marvion Speedrun)";
    }
}
