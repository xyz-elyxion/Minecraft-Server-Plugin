package dev.speedrun.command;

import dev.speedrun.SpeedrunPlugin;
import dev.speedrun.run.RunState;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /speedrun start|stop|status|reload */
public final class SpeedrunCommand implements TabExecutor {

    private static final List<String> ACTIONS = List.of("start", "stop", "status", "jump", "reload");

    private final SpeedrunPlugin plugin;

    public SpeedrunCommand(SpeedrunPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("speedrun.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().send(sender, "usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (plugin.run() != null) {
                    plugin.messages().send(sender, "already-running");
                    return true;
                }
                if (!plugin.startRun()) {
                    plugin.messages().send(sender, "no-world");
                    return true;
                }
                plugin.messages().send(sender, "started");
            }
            case "stop" -> {
                if (plugin.run() == null) {
                    plugin.messages().send(sender, "not-running");
                    return true;
                }
                plugin.stopRun(true);
            }
            case "status" -> status(sender);
            case "jump" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "usage");
                    return true;
                }
                if (plugin.run() == null) {
                    plugin.messages().send(sender, "not-running");
                    return true;
                }
                plugin.messages().send(sender,
                        plugin.jumpRunner(args[1]) ? "jumped" : "no-such-phase",
                        Placeholder.unparsed("phase", args[1]));
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.messages().reload();
                plugin.messages().send(sender, "reloaded");
            }
            default -> plugin.messages().send(sender, "usage");
        }
        return true;
    }

    private void status(CommandSender sender) {
        RunState run = plugin.run();
        if (run == null) {
            plugin.messages().send(sender, "not-running");
            return;
        }
        plugin.messages().send(sender, run.finished() ? "status-finished" : "status-running",
                Placeholder.unparsed("time", run.clock()));
        plugin.messages().send(sender, "status-line",
                Placeholder.unparsed("name", "state"),
                Placeholder.unparsed("time", plugin.describeRunner()));
        if (run.splits().isEmpty()) {
            plugin.messages().send(sender, "status-none");
            return;
        }
        for (Map.Entry<String, Double> split : run.splits().entrySet()) {
            plugin.messages().send(sender, "status-line",
                    Placeholder.unparsed("name", split.getKey()),
                    Placeholder.unparsed("time", RunState.format(split.getValue())));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("speedrun.use")) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String action : ACTIONS) {
            if (action.startsWith(prefix)) {
                matches.add(action);
            }
        }
        return matches;
    }
}
