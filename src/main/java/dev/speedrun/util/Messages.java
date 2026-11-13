package dev.speedrun.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** MiniMessage messages read from config.yml. */
public final class Messages {

    private final JavaPlugin plugin;
    private Component prefix;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.prefix = MiniMessage.miniMessage().deserialize(plugin.getConfig().getString("messages.prefix", ""));
    }

    public Component get(String key, TagResolver... resolvers) {
        String raw = plugin.getConfig().getString("messages." + key);
        if (raw == null) {
            return Component.text("[SpeedrunAI] Missing message: " + key);
        }
        return Component.empty().append(prefix).append(MiniMessage.miniMessage().deserialize(raw, resolvers));
    }

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(get(key, resolvers));
    }

    public void broadcast(String key, TagResolver... resolvers) {
        Bukkit.broadcast(get(key, resolvers));
    }
}
