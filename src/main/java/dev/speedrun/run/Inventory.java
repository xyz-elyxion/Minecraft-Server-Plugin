package dev.speedrun.run;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What the runner is carrying.
 *
 * <p>Items are counted here instead of in a Bukkit inventory: the bot never has
 * to walk over a drop or manage stacks, and the route logic stays readable. The
 * counts are still real drops from real broken blocks.</p>
 */
public final class Inventory {

    private final Map<Material, Integer> items = new EnumMap<>(Material.class);

    public void add(Material material, int amount) {
        if (material == null || material.isAir() || amount <= 0) {
            return;
        }
        items.merge(material, amount, Integer::sum);
    }

    public int count(Material material) {
        return items.getOrDefault(material, 0);
    }

    public int countTag(Tag<Material> tag) {
        int total = 0;
        for (Map.Entry<Material, Integer> entry : items.entrySet()) {
            if (tag.isTagged(entry.getKey())) {
                total += entry.getValue();
            }
        }
        return total;
    }

    public boolean take(Material material, int amount) {
        int held = count(material);
        if (held < amount) {
            return false;
        }
        if (held == amount) {
            items.remove(material);
        } else {
            items.put(material, held - amount);
        }
        return true;
    }

    public boolean takeTag(Tag<Material> tag, int amount) {
        if (countTag(tag) < amount) {
            return false;
        }
        int left = amount;
        for (Material material : List.copyOf(items.keySet())) {
            if (left <= 0) {
                break;
            }
            if (!tag.isTagged(material)) {
                continue;
            }
            int used = Math.min(count(material), left);
            take(material, used);
            left -= used;
        }
        return left == 0;
    }

    public Material firstTag(Tag<Material> tag) {
        for (Map.Entry<Material, Integer> entry : items.entrySet()) {
            if (entry.getValue() > 0 && tag.isTagged(entry.getKey())) {
                return entry.getKey();
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public String describe() {
        if (items.isEmpty()) {
            return "empty";
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Material, Integer> entry : items.entrySet()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(entry.getKey().name()).append('x').append(entry.getValue());
        }
        return text.toString();
    }
}
