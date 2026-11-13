package dev.speedrun;

import dev.speedrun.command.SpeedrunCommand;
import dev.speedrun.run.RunState;
import dev.speedrun.run.Runner;
import dev.speedrun.util.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** A bot that speedruns the early game on your server, against a live timer. */
public final class SpeedrunPlugin extends JavaPlugin {

    private Messages messages;
    private RunState run;
    private Runner runner;
    private BukkitTask task;
    private int hudTicks;
    private int logTicks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.messages = new Messages(this);

        PluginCommand command = getCommand("speedrun");
        if (command == null) {
            getLogger().severe("Command 'speedrun' is missing from plugin.yml - the plugin cannot be controlled.");
        } else {
            SpeedrunCommand executor = new SpeedrunCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        this.task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("SpeedrunAI ready - /speedrun start sends the runner.");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        stopRun(false);
    }

    public Messages messages() {
        return messages;
    }

    public RunState run() {
        return run;
    }

    /** Sends the runner out and starts the clock. False when a run is already going. */
    public boolean startRun() {
        if (run != null) {
            return false;
        }
        World world = resolveWorld();
        if (world == null) {
            return false;
        }
        Location spawn = world.getSpawnLocation().clone();
        int surface = world.getHighestBlockYAt(spawn.getBlockX(), spawn.getBlockZ(), HeightMap.MOTION_BLOCKING);
        Location start = new Location(world, spawn.getBlockX() + 0.5D, surface + 1.0D, spawn.getBlockZ() + 0.5D);

        this.run = new RunState(getConfig().getBoolean("run.boss-bar", true));
        this.runner = new Runner(this, run, start);
        if (!runner.alive()) {
            stopRun(false);
            return false;
        }
        this.hudTicks = 0;
        getLogger().info("Run started in '" + world.getName() + "' at " + start.getBlockX()
                + ", " + start.getBlockY() + ", " + start.getBlockZ() + ".");
        return true;
    }

    /** Called by the runner when the route is finished. */
    public void finishRun() {
        if (run == null) {
            return;
        }
        run.finish();
        messages.broadcast("finished", Placeholder.unparsed("time", run.clock()));
        getLogger().info("Run finished in " + run.clock() + ".");
    }

    public String describeRunner() {
        return runner == null ? "no runner" : runner.describe();
    }

    public boolean jumpRunner(String phaseName) {
        return runner != null && runner.jumpTo(phaseName);
    }

    public void announceSplit(String name, double seconds) {
        messages.broadcast("split",
                Placeholder.unparsed("split", name),
                Placeholder.unparsed("time", RunState.format(seconds)));
        getLogger().info("Split " + name + " at " + RunState.format(seconds) + ".");
    }

    public void stopRun(boolean announce) {
        if (runner != null) {
            runner.remove();
            runner = null;
        }
        if (run != null) {
            run.hide();
            run = null;
        }
        if (announce) {
            messages.broadcast("stopped");
        }
    }

    private void tick() {
        if (runner != null) {
            runner.tick();
        }
        if (run != null && !run.finished()) {
            hudTicks++;
            if (hudTicks >= 5) {
                hudTicks = 0;
                run.update();
            }
            if (++logTicks >= 400) {
                logTicks = 0;
                if (runner != null) {
                    getLogger().info(runner.describe());
                }
            }
        }
    }

    private World resolveWorld() {
        String name = getConfig().getString("run.world", "world");
        World world = name == null ? null : Bukkit.getWorld(name);
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().getFirst();
        }
        return world;
    }
}
