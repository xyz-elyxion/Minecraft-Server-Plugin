package dev.bedwars.command;

import dev.bedwars.ai.AiManager;
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

    private static final List<String> ACTIONS =
            List.of("join", "leave", "shop", "ai", "start", "reset", "reload");
    private static final List<String> AI_ACTIONS = List.of("on", "off", "spawn", "clear");

    private final Arena arena;
    private final BedwarsMessages messages;
    private final AiManager ai;
    private final Runnable reloadAction;

    public BedwarsCommand(Arena arena, BedwarsMessages messages, AiManager ai, Runnable reloadAction) {
        this.arena = arena;
        this.messages = messages;
        this.ai = ai;
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
            case "ai" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                handleAi(sender, args);
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

    private void handleAi(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "on" -> {
                ai.setEnabled(true);
                messages.send(sender, "ai-on");
            }
            case "off" -> {
                ai.setEnabled(false);
                messages.send(sender, "ai-off");
            }
            case "spawn" -> {
                if (!ai.enabled()) {
                    ai.setEnabled(true);
                }
                int spawned = ai.spawnTestBots();
                messages.send(sender, "ai-spawned",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                                "count", String.valueOf(spawned)));
            }
            case "clear" -> {
                ai.shutdown();
                messages.send(sender, "ai-cleared");
            }
            default -> messages.send(sender, "ai-status",
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                            "state", ai.enabled() ? "on" : "off"),
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                            "count", String.valueOf(ai.botCount())));
        }
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
        boolean admin = sender.hasPermission("bedwars.admin");
        if (args.length == 2 && args[0].equalsIgnoreCase("ai")) {
            if (!admin) {
                return List.of();
            }
            String typed = args[1].toLowerCase(Locale.ROOT);
            return AI_ACTIONS.stream().filter(action -> action.startsWith(typed)).toList();
        }
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String action : ACTIONS) {
            if (!action.startsWith(prefix)) {
                continue;
            }
            boolean adminOnly = action.equals("start") || action.equals("reset")
                    || action.equals("reload") || action.equals("ai");
            if (!adminOnly || admin) {
                matches.add(action);
            }
        }
        return matches;
    }
}
