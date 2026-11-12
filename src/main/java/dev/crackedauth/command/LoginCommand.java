package dev.crackedauth.command;

import dev.crackedauth.auth.AuthManager;
import dev.crackedauth.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public final class LoginCommand implements TabExecutor {

    private final AuthManager authManager;
    private final Messages messages;

    public LoginCommand(AuthManager authManager, Messages messages) {
        this.authManager = authManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("crackedauth.use")) {
            messages.send(player, "no-permission");
            return true;
        }
        if (args.length != 1) {
            messages.send(player, "login-usage");
            return true;
        }
        authManager.login(player, args[0]);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
