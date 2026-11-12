package dev.crackedauth.command;

import dev.crackedauth.CrackedAuthPlugin;
import dev.crackedauth.auth.AccountStore;
import dev.crackedauth.auth.AuthManager;
import dev.crackedauth.auth.PasswordHasher;
import dev.crackedauth.auth.UserAccount;
import dev.crackedauth.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AuthAdminCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("unregister", "forcelogin", "setpassword", "info", "reload");

    private final CrackedAuthPlugin plugin;
    private final AuthManager authManager;
    private final AccountStore store;
    private final Messages messages;

    public AuthAdminCommand(CrackedAuthPlugin plugin, AuthManager authManager, AccountStore store, Messages messages) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.store = store;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("crackedauth.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            messages.send(sender, "admin-usage");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "unregister" -> unregister(sender, args);
            case "forcelogin" -> forceLogin(sender, args);
            case "setpassword" -> setPassword(sender, args);
            case "info" -> info(sender, args);
            case "reload" -> {
                plugin.reloadPlugin();
                messages.send(sender, "admin-reloaded");
            }
            default -> messages.send(sender, "admin-usage");
        }
        return true;
    }

    private void unregister(CommandSender sender, String[] args) {
        if (args.length != 2) {
            messages.send(sender, "admin-usage");
            return;
        }
        UserAccount removed = store.remove(args[1]);
        if (removed == null) {
            messages.send(sender, "admin-player-not-found", "player", args[1]);
            return;
        }
        Player online = Bukkit.getPlayerExact(removed.name());
        if (online != null) {
            online.kick(messages.get("admin-unregister-target"));
        }
        messages.send(sender, "admin-unregistered", "player", removed.name());
    }

    private void forceLogin(CommandSender sender, String[] args) {
        if (args.length != 2) {
            messages.send(sender, "admin-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "admin-player-not-found", "player", args[1]);
            return;
        }
        if (!authManager.forceLogin(target)) {
            messages.send(sender, "admin-forcelogin-already", "player", target.getName());
            return;
        }
        messages.send(sender, "admin-forcelogin-done", "player", target.getName());
    }

    private void setPassword(CommandSender sender, String[] args) {
        if (args.length != 3) {
            messages.send(sender, "admin-usage");
            return;
        }
        UserAccount account = store.get(args[1]);
        if (account == null) {
            messages.send(sender, "admin-player-not-found", "player", args[1]);
            return;
        }
        int minLength = Math.max(1, plugin.getConfig().getInt("settings.min-password-length", 8));
        if (args[2].length() < minLength) {
            messages.send(sender, "register-password-too-short", "min", String.valueOf(minLength));
            return;
        }
        account.passwordHash(PasswordHasher.hash(args[2]));
        store.put(account);
        messages.send(sender, "admin-setpassword-done", "player", account.name());
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length != 2) {
            messages.send(sender, "admin-usage");
            return;
        }
        UserAccount account = store.get(args[1]);
        if (account == null) {
            messages.send(sender, "admin-player-not-found", "player", args[1]);
            return;
        }
        Player online = Bukkit.getPlayerExact(account.name());
        messages.send(sender, "admin-info",
                "player", account.name(),
                "registered", account.registeredAt() <= 0L ? "unknown" : Instant.ofEpochMilli(account.registeredAt()).toString(),
                "last-ip", account.lastIp() == null || account.lastIp().isEmpty() ? "-" : account.lastIp(),
                "last-login", account.lastLogin() <= 0L ? "never" : Instant.ofEpochMilli(account.lastLogin()).toString(),
                "online", online == null ? "no" : "yes",
                "authenticated", online == null ? "-" : (authManager.isAuthenticated(online) ? "yes" : "no"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("crackedauth.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return startingWith(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return startingWith(names, args[1]);
        }
        return List.of();
    }

    private static List<String> startingWith(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
