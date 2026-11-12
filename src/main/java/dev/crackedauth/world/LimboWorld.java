package dev.crackedauth.world;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns the limbo world: an empty void world that unauthenticated players wait in.
 *
 * <p>The world is created on demand with {@link VoidChunkGenerator}, is never
 * saved, spawns no mobs and ticks nothing, so it costs a few chunks at most.</p>
 */
public final class LimboWorld {

    private final JavaPlugin plugin;
    private String worldName;
    private double spawnY;
    private boolean enabled;
    private World world;

    public LimboWorld(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        this.enabled = config.getBoolean("settings.limbo.enabled", true);
        this.worldName = config.getString("settings.limbo.world-name", "crackedauth_limbo");
        this.spawnY = config.getDouble("settings.limbo.spawn-y", 65.0D);
        this.world = enabled ? Bukkit.getWorld(worldName) : null;
    }

    /**
     * Creates the world during startup so the first player to join does not pay
     * for it. Failures are logged and fall back to holding players in place.
     */
    public void loadAtStartup() {
        if (!enabled) {
            plugin.getLogger().info("Limbo world is disabled; players will be held where they join.");
            return;
        }
        if (ensure()) {
            plugin.getLogger().info("Limbo world '" + worldName + "' is ready.");
        }
    }

    public boolean isAvailable() {
        return ensure();
    }

    private boolean ensure() {
        if (!enabled) {
            return false;
        }
        if (world != null && Bukkit.getWorld(worldName) != null) {
            return true;
        }
        World existing = Bukkit.getWorld(worldName);
        if (existing == null) {
            existing = new WorldCreator(worldName)
                    .environment(World.Environment.NORMAL)
                    .generator(new VoidChunkGenerator())
                    .createWorld();
        }
        if (existing == null) {
            plugin.getLogger().severe("Could not create the limbo world '" + worldName + "'; holding players in place instead.");
            enabled = false;
            return false;
        }
        existing.setDifficulty(Difficulty.PEACEFUL);
        existing.setAutoSave(false);
        existing.setGameRule(GameRules.SPAWN_MOBS, false);
        existing.setGameRule(GameRules.SPAWN_MONSTERS, false);
        existing.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        existing.setGameRule(GameRules.SPAWN_PATROLS, false);
        existing.setGameRule(GameRules.ADVANCE_WEATHER, false);
        existing.setGameRule(GameRules.ADVANCE_TIME, false);
        existing.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        this.world = existing;
        return true;
    }

    /** Spawn point inside limbo, or null when limbo is unavailable. */
    public Location spawn() {
        if (!ensure() || world == null) {
            return null;
        }
        return new Location(world, 0.5D, spawnY, 0.5D, 0.0F, 0.0F);
    }

    public boolean isLimboWorld(World other) {
        return other != null && other.getName().equals(worldName);
    }

    /** First non-limbo world, used to recover a return point. */
    public World mainWorld() {
        for (World candidate : Bukkit.getWorlds()) {
            if (!isLimboWorld(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
