package dev.bedwars.command;

import dev.bedwars.game.Arena;
import dev.bedwars.game.ShopMenu;
import dev.bedwars.util.BedwarsMessages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BedwarsCommand implements TabExecutor {

    private static final List<String> ACTIONS = List.of("join", "leave", "shop", "start", "reset", "reload");

    private final Arena arena;
    private final BedwarsMessages messages;
    private final Runnable reloadAction;

    public BedwarsCommand(Arena arena, BedwarsMessages messages, Runnable reloadAction) {
        this.arena = arena;
        this.messages = messages;
        this.reloadAction = reloadAction;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            messages.send(sender, "usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "join" -> {
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "player-only");
                    return true;
                }
                if (!player.hasPermission("bedwars.play")) {
                    messages.send(player, "no-permission");
                    return true;
                }
                arena.join(player);
            }
            case "leave" -> {
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "player-only");
                    return true;
                }
                arena.leave(player, false);
            }
            case "shop" -> {
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "player-only");
                    return true;
                }
                if (!arena.isPlaying(player)) {
                    messages.send(player, "not-playing");
                    return true;
                }
                player.openInventory(new ShopMenu().getInventory());
            }
            case "start" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (arena.isRunning()) {
                    messages.send(sender, "in-progress");
                    return true;
                }
                arena.startGame();
                messages.send(sender, "started-command");
            }
            case "reset" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                arena.reset();
                messages.send(sender, "reloaded");
            }
            case "reload" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                reloadAction.run();
                messages.send(sender, "reloaded");
            }
            default -> messages.send(sender, "usage");
        }
        return true;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("bedwars.admin")) {
            messages.send(sender, "no-permission");
            return false;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("bedwars.admin");
        List<String> matches = new ArrayList<>();
        for (String action : ACTIONS) {
            if (!action.startsWith(prefix)) {
                continue;
            }
            boolean adminOnly = action.equals("start") || action.equals("reset") || action.equals("reload");
            if (!adminOnly || admin) {
                matches.add(action);
            }
        }
        return matches;
    }
}
