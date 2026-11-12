package dev.bedwars.world;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Owns the arena world and builds the lobby, the team islands and the middle
 * platforms so a fresh install is playable without any manual world editing.
 */
public final class ArenaWorld {

    private final JavaPlugin plugin;
    private String worldName;
    private double lobbyX;
    private double lobbyY;
    private double lobbyZ;
    private World world;
    private boolean built;

    public ArenaWorld(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        this.worldName = config.getString("settings.world", "bedwars_world");
        this.lobbyX = config.getDouble("settings.lobby.x", 0.5D);
        this.lobbyY = config.getDouble("settings.lobby.y", 70.0D);
        this.lobbyZ = config.getDouble("settings.lobby.z", 0.5D);
        this.built = false;
        this.world = null;
    }

    public World world() {
        if (world != null) {
            return world;
        }
        World existing = Bukkit.getWorld(worldName);
        if (existing == null) {
            existing = new WorldCreator(worldName)
                    .environment(World.Environment.NORMAL)
                    .generator(new VoidWorldGenerator())
                    .createWorld();
        }
        if (existing == null) {
            plugin.getLogger().severe("Could not create the Bedwars world '" + worldName + "'.");
            return null;
        }
        existing.setAutoSave(false);
        existing.setDifficulty(Difficulty.NORMAL);
        existing.setGameRule(GameRules.PVP, true);
        existing.setGameRule(GameRules.SPAWN_MOBS, false);
        existing.setGameRule(GameRules.ADVANCE_WEATHER, false);
        existing.setGameRule(GameRules.ADVANCE_TIME, false);
        existing.setSpawnLocation((int) Math.floor(lobbyX), (int) Math.floor(lobbyY), (int) Math.floor(lobbyZ));
        this.world = existing;
        return existing;
    }

    public Location lobby() {
        World current = world();
        return current == null ? null : new Location(current, lobbyX, lobbyY, lobbyZ, 0.0F, 0.0F);
    }

    public Location location(ConfigurationSection section, double fallbackY) {
        World current = world();
        if (current == null || section == null) {
            return null;
        }
        return new Location(current,
                section.getDouble("x", 0.5D),
                section.getDouble("y", fallbackY),
                section.getDouble("z", 0.5D));
    }

    /** Builds (or rebuilds) the arena geometry. Safe to call repeatedly. */
    public void build(java.util.List<TeamRef> teams) {
        World current = world();
        if (current == null) {
            return;
        }

        // Lobby island.
        fill(current, lobbyX - 4, lobbyY - 1, lobbyZ - 4, lobbyX + 4, lobbyY - 1, lobbyZ + 4, Material.SMOOTH_QUARTZ);

        for (TeamRef team : teams) {
            if (team.spawn() == null) {
                continue;
            }
            double sx = team.spawn().getX();
            double sy = team.spawn().getY();
            double sz = team.spawn().getZ();
            fill(current, sx - 2, sy - 1, sz - 2, sx + 2, sy - 1, sz + 2, team.wool());
        }

        // Middle islands for the diamond generators, plus the centre emerald island.
        fill(current, -3, 64, 11, 3, 64, 17, Material.STONE_BRICKS);
        fill(current, -3, 64, -17, 3, 64, -11, Material.STONE_BRICKS);
        fill(current, -2, 64, -2, 2, 64, 2, Material.STONE_BRICKS);

        this.built = true;
    }

    /** Places a bed-of-two-blocks at the given location. */
    public void placeBed(org.bukkit.Location location, Material material) {
        World current = world();
        if (current == null || location == null) {
            return;
        }
        Block foot = current.getBlockAt(location);
        Block head = foot.getRelative(BlockFace.NORTH);
        if (!(material.createBlockData() instanceof Bed footData)
                || !(material.createBlockData() instanceof Bed headData)) {
            return;
        }
        footData.setPart(Bed.Part.FOOT);
        footData.setFacing(BlockFace.NORTH);
        foot.setBlockData(footData, false);

        headData.setPart(Bed.Part.HEAD);
        headData.setFacing(BlockFace.NORTH);
        head.setBlockData(headData, false);
    }

    /** Removes both halves of a bed. Returns true when a bed was actually removed. */
    public boolean removeBed(org.bukkit.Location location) {
        World current = world();
        if (current == null || location == null) {
            return false;
        }
        boolean removed = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block block = current.getBlockAt(
                        location.getBlockX() + dx, location.getBlockY(), location.getBlockZ() + dz);
                if (block.getBlockData() instanceof Bed) {
                    block.setType(Material.AIR, false);
                    removed = true;
                }
            }
        }
        return removed;
    }

    private static void fill(World world, double x1, double y1, double z1, double x2, double y2, double z2, Material material) {
        int minX = (int) Math.floor(Math.min(x1, x2));
        int maxX = (int) Math.floor(Math.max(x1, x2));
        int minY = (int) Math.floor(Math.min(y1, y2));
        int maxY = (int) Math.floor(Math.max(y1, y2));
        int minZ = (int) Math.floor(Math.min(z1, z2));
        int maxZ = (int) Math.floor(Math.max(z1, z2));
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    world.getBlockAt(x, y, z).setType(material, false);
                }
            }
        }
    }

    public void unload() {
        World current = world;
        if (current == null) {
            return;
        }
        World fallback = null;
        for (World candidate : Bukkit.getWorlds()) {
            if (!candidate.getName().equals(worldName)) {
                fallback = candidate;
                break;
            }
        }
        if (fallback != null) {
            Location spawn = fallback.getSpawnLocation();
            for (org.bukkit.entity.Player player : current.getPlayers()) {
                player.teleport(spawn);
            }
        }
        Bukkit.unloadWorld(current, false);
        world = null;
    }

    /** Plain data holder used while building, so the world code need not know about teams. */
    public record TeamRef(Location spawn, Material wool) {
        public TeamRef {
            wool = wool == null ? Material.WHITE_WOOL : wool;
        }

        public String name() {
            return wool.name().toLowerCase(Locale.ROOT);
        }
    }
}
