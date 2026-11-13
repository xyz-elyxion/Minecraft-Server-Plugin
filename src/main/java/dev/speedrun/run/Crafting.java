package dev.speedrun.run;

import org.bukkit.Material;
import org.bukkit.Tag;

/** The handful of recipes the any% route needs, costed exactly like vanilla. */
public final class Crafting {

    private Crafting() {
    }

    /** Turns logs into planks, four per log, of the matching wood type. */
    public static int planks(Inventory inventory, int logs) {
        int crafted = 0;
        while (crafted < logs) {
            Material log = inventory.firstTag(Tag.LOGS);
            if (log == null) {
                break;
            }
            inventory.take(log, 1);
            inventory.add(plankOf(log), 4);
            crafted++;
        }
        return crafted;
    }

    /** Two planks become four sticks. */
    public static boolean sticks(Inventory inventory) {
        if (inventory.countTag(Tag.PLANKS) < 2) {
            return false;
        }
        inventory.takeTag(Tag.PLANKS, 2);
        inventory.add(Material.STICK, 4);
        return true;
    }

    /** Four planks for a crafting table. */
    public static boolean table(Inventory inventory) {
        return inventory.takeTag(Tag.PLANKS, 4);
    }

    public static boolean woodenPickaxe(Inventory inventory) {
        if (!ensureSticks(inventory) || !inventory.takeTag(Tag.PLANKS, 3)) {
            return false;
        }
        inventory.take(Material.STICK, 2);
        inventory.add(Material.WOODEN_PICKAXE, 1);
        return true;
    }

    public static boolean stonePickaxe(Inventory inventory) {
        if (!ensureSticks(inventory) || !inventory.take(Material.COBBLESTONE, 3)) {
            return false;
        }
        inventory.take(Material.STICK, 2);
        inventory.add(Material.STONE_PICKAXE, 1);
        return true;
    }

    /** Crafts sticks from planks when the tool recipes need them. */
    private static boolean ensureSticks(Inventory inventory) {
        while (inventory.count(Material.STICK) < 2) {
            if (!sticks(inventory)) {
                return false;
            }
        }
        return true;
    }

    public static boolean diamondPickaxe(Inventory inventory) {
        if (!ensureSticks(inventory) || !inventory.take(Material.DIAMOND, 3)) {
            return false;
        }
        inventory.take(Material.STICK, 2);
        inventory.add(Material.DIAMOND_PICKAXE, 1);
        return true;
    }

    public static boolean furnace(Inventory inventory) {
        if (!inventory.take(Material.COBBLESTONE, 8)) {
            return false;
        }
        inventory.add(Material.FURNACE, 1);
        return true;
    }

    public static boolean ironPickaxe(Inventory inventory) {
        if (!ensureSticks(inventory) || !inventory.take(Material.IRON_INGOT, 3)) {
            return false;
        }
        inventory.take(Material.STICK, 2);
        inventory.add(Material.IRON_PICKAXE, 1);
        return true;
    }

    private static Material plankOf(Material log) {
        String name = log.name();
        if (name.startsWith("STRIPPED_")) {
            name = name.substring("STRIPPED_".length());
        }
        name = name.replace("_LOG", "_PLANKS").replace("_STEM", "_PLANKS").replace("_WOOD", "_PLANKS");
        Material planks = Material.matchMaterial(name);
        return planks == null ? Material.OAK_PLANKS : planks;
    }
}
