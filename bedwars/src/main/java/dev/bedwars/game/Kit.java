package dev.bedwars.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;

/** What a player receives when a game starts or they respawn. */
public final class Kit {

    public static final Component SHOP_NAME = Component.text("Item Shop", NamedTextColor.GOLD);

    private Kit() {
    }

    public static ItemStack shopItem() {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(SHOP_NAME);
        meta.lore(List.of(Component.text("Right-click to browse", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isShopItem(ItemStack item) {
        if (item == null || item.getType() != Material.CHEST) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.displayName() != null && meta.displayName().equals(SHOP_NAME);
    }

    public static void give(Player player, Team team) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        player.setGameMode(GameMode.SURVIVAL);
        player.setHealth(20.0D);
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setFireTicks(0);

        player.getInventory().setItem(0, new ItemStack(Material.IRON_SWORD));
        player.getInventory().setItem(1, new ItemStack(team.woolMaterial(), 16));
        player.getInventory().setItem(2, new ItemStack(Material.IRON_PICKAXE));
        player.getInventory().setItem(8, shopItem());
    }
}
