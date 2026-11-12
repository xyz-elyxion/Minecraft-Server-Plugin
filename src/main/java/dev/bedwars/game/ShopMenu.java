package dev.bedwars.game;

import dev.bedwars.util.BedwarsMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;

/** A 27-slot shop: the first slots hold offers, the rest is padding. */
public final class ShopMenu implements InventoryHolder {

    private static final List<Offer> OFFERS = List.of(
            new Offer(Material.WHITE_WOOL, 16, Material.IRON_INGOT, 4),
            new Offer(Material.IRON_SWORD, 1, Material.GOLD_INGOT, 7),
            new Offer(Material.IRON_PICKAXE, 1, Material.IRON_INGOT, 10),
            new Offer(Material.BOW, 1, Material.GOLD_INGOT, 12),
            new Offer(Material.ARROW, 8, Material.GOLD_INGOT, 2),
            new Offer(Material.GOLDEN_APPLE, 1, Material.GOLD_INGOT, 3),
            new Offer(Material.OBSIDIAN, 4, Material.EMERALD, 4),
            new Offer(Material.DIAMOND_SWORD, 1, Material.EMERALD, 4));

    private final Inventory inventory;

    public ShopMenu() {
        this.inventory = Bukkit.createInventory(this, 27, Component.text("Item Shop", NamedTextColor.DARK_GRAY));
        for (int index = 0; index < OFFERS.size(); index++) {
            inventory.setItem(index, describe(OFFERS.get(index)));
        }
    }

    private static ItemStack describe(Offer offer) {
        ItemStack item = new ItemStack(offer.item(), offer.amount());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(pretty(offer.item()), NamedTextColor.WHITE));
        meta.lore(List.of(Component.text(
                offer.price() + " x " + pretty(offer.currency()), NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private static String pretty(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Charges the player and hands over the item. Returns false when they cannot afford it. */
    public boolean purchase(Player player, int slot, BedwarsMessages messages) {
        if (slot < 0 || slot >= OFFERS.size()) {
            return false;
        }
        Offer offer = OFFERS.get(slot);
        if (!charge(player, offer.currency(), offer.price())) {
            messages.send(player, "not-enough");
            return false;
        }
        player.getInventory().addItem(new ItemStack(offer.item(), offer.amount()));
        messages.send(player, "bought", Placeholder.unparsed("item", pretty(offer.item())));
        return true;
    }

    private static boolean charge(Player player, Material currency, int price) {
        int available = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == currency) {
                available += stack.getAmount();
            }
        }
        if (available < price) {
            return false;
        }
        int remaining = price;
        ItemStack[] contents = player.getInventory().getContents();
        for (int index = 0; index < contents.length && remaining > 0; index++) {
            ItemStack stack = contents[index];
            if (stack == null || stack.getType() != currency) {
                continue;
            }
            int taken = Math.min(stack.getAmount(), remaining);
            stack.setAmount(stack.getAmount() - taken);
            remaining -= taken;
            if (stack.getAmount() <= 0) {
                player.getInventory().setItem(index, null);
            }
        }
        return true;
    }

    private record Offer(Material item, int amount, Material currency, int price) {
    }
}
