package dev.crackedauth.command;

import dev.crackedauth.auth.AuthManager;
import dev.crackedauth.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public final class LogoutCommand implements TabExecutor {

    private final AuthManager authManager;
    private final Messages messages;

    public LogoutCommand(AuthManager authManager, Messages messages) {
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
        authManager.logout(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
