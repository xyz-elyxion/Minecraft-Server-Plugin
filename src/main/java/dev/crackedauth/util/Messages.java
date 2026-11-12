package dev.crackedauth.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads MiniMessage strings from config.yml and turns them into components.
 * The {@code get} methods prepend the configured prefix, {@code plain} does not
 * (titles and action bars read better without a chat prefix).
 */
public final class Messages {

    private final JavaPlugin plugin;
    private FileConfiguration config;
    private Component prefix;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.config = plugin.getConfig();
        this.prefix = MiniMessage.miniMessage().deserialize(config.getString("messages.prefix", ""));
    }

    public Component get(String key, String... placeholders) {
        return Component.empty().append(prefix).append(plain(key, placeholders));
    }

    public Component plain(String key, String... placeholders) {
        String raw = config.getString("messages." + key);
        if (raw == null) {
            return Component.text("[CrackedAuth] Missing message: " + key);
        }
        List<TagResolver> resolvers = new ArrayList<>(placeholders.length / 2);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            resolvers.add(Placeholder.unparsed(placeholders[i], placeholders[i + 1]));
        }
        return MiniMessage.miniMessage().deserialize(raw, resolvers.toArray(new TagResolver[0]));
    }

    public void send(CommandSender sender, String key, String... placeholders) {
        sender.sendMessage(get(key, placeholders));
    }
}
