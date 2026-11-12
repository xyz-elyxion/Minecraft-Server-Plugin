package dev.bedwars.util;

import dev.bedwars.BedwarsConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/** MiniMessage rendering for Bedwars, read from bedwars.yml. */
public final class BedwarsMessages {

    private final BedwarsConfig config;
    private Component prefix;

    public BedwarsMessages(BedwarsConfig config) {
        this.config = config;
        reload();
    }

    public void reload() {
        this.prefix = MiniMessage.miniMessage().deserialize(config.config().getString("messages.prefix", ""));
    }

    public Component get(String key, TagResolver... resolvers) {
        String raw = config.config().getString("messages." + key);
        if (raw == null) {
            return Component.text("[Bedwars] Missing message: " + key);
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
