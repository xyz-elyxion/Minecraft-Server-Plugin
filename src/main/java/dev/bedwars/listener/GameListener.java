package dev.bedwars.listener;

import dev.bedwars.game.Arena;
import dev.bedwars.game.Kit;
import dev.bedwars.game.ShopMenu;
import dev.bedwars.game.Team;
import dev.bedwars.util.BedwarsMessages;
import org.bukkit.Location;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Wires Bukkit events into the arena and the shop. */
public final class GameListener implements Listener {

    private final Arena arena;
    private final BedwarsMessages messages;

    public GameListener(Arena arena, BedwarsMessages messages) {
        this.arena = arena;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        arena.leave(event.getPlayer(), true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!arena.isPlaying(player)) {
            return;
        }
        event.setKeepInventory(false);
        event.getDrops().clear();
        event.setDroppedExp(0);
        arena.handleDeath(player);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!arena.isPlaying(event.getPlayer())) {
            return;
        }
        Location target = arena.respawnLocation(event.getPlayer());
        if (target != null) {
            event.setRespawnLocation(target);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBedBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getBlockData() instanceof Bed)) {
            return;
        }
        if (arena.handleBedBreak(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (Kit.isShopItem(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (Kit.isShopItem(event.getItem())) {
            event.setCancelled(true);
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                if (arena.isPlaying(player)) {
                    player.openInventory(new ShopMenu().getInventory());
                }
            }
            return;
        }
        // Beds are decorative here: no sleeping through the night.
        if (event.getClickedBlock() != null && event.getClickedBlock().getBlockData() instanceof Bed) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopMenu shop)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            shop.purchase(player, event.getSlot(), messages);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ShopMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!arena.isPlaying(attacker) || !arena.isPlaying(victim)) {
            return;
        }
        if (!arena.isRunning()) {
            event.setCancelled(true);
            return;
        }
        Team attackerTeam = arena.teamOf(attacker);
        Team victimTeam = arena.teamOf(victim);
        if (attackerTeam != null && attackerTeam == victimTeam) {
            event.setCancelled(true);
        }
    }
}
