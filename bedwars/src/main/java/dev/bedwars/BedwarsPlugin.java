package dev.bedwars;

import dev.bedwars.command.BedwarsCommand;
import dev.bedwars.game.Arena;
import dev.bedwars.listener.GameListener;
import dev.bedwars.util.Messages;
import dev.bedwars.world.ArenaWorld;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class BedwarsPlugin extends JavaPlugin {

    private Messages messages;
    private ArenaWorld arenaWorld;
    private Arena arena;
    private BukkitTask tickTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.messages = new Messages(this);
        this.arenaWorld = new ArenaWorld(this);
        this.arena = new Arena(this, messages, arenaWorld);
        this.arena.load();

        getServer().getPluginManager().registerEvents(new GameListener(this, arena, messages), this);
        registerCommand("bedwars", new BedwarsCommand(this, arena, messages));

        this.tickTask = Bukkit.getScheduler().runTaskTimer(this, arena::tick, 20L, 20L);

        getLogger().info("Bedwars enabled with " + arena.teams().size() + " team(s).");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (arena != null) {
            arena.shutdown();
        }
        if (arenaWorld != null) {
            arenaWorld.unload();
        }
    }

    public void reloadPlugin() {
        reloadConfig();
        messages.reload();
        arenaWorld.reload();
        arena.load();
    }

    private void registerCommand(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command '" + name + "' is missing from plugin.yml - skipping it.");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
