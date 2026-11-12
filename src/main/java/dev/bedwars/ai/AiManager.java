package dev.bedwars.ai;

import dev.bedwars.BedwarsConfig;
import dev.bedwars.game.Arena;
import dev.bedwars.game.Team;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Bedwars opponents that are not players.
 *
 * <p>Each bot is a husk wearing its team's colour, added to a team as a normal
 * member so the existing bed/elimination/win rules apply to it unchanged. The
 * bots run on their own small controller instead of vanilla mob AI: every step
 * they pick the nearest enemy (player or bot), otherwise the nearest enemy bed,
 * otherwise the enemy base, then walk, bridge and swing.</p>
 *
 * <p>Movement is a controlled teleport of a fraction of a block per step with
 * gravity off, and every step places team wool under the bot. That is what lets
 * them cross the void the same way a player would bridge, without a pathfinder
 * that cannot see across a gap.</p>
 */
public final class AiManager {

    /** The controller runs one step every {@code STEP_TICKS} ticks. */
    private static final int STEP_TICKS = 5;

    private final JavaPlugin plugin;
    private final Arena arena;
    private final BedwarsConfig config;
    private final Map<UUID, Bot> bots = new LinkedHashMap<>();
    private final Map<Location, Material> bridges = new LinkedHashMap<>();
    private final Map<String, Integer> nameCounters = new LinkedHashMap<>();

    private boolean enabled;
    private int perTeam;
    private double damage;
    private int cooldownSteps;
    private double speed;
    private double attackRange;
    private double bedRange;
    private double aggroRange;
    private boolean bridging;
    private int respawnSeconds;

    public AiManager(JavaPlugin plugin, Arena arena, BedwarsConfig config) {
        this.plugin = plugin;
        this.arena = arena;
        this.config = config;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    public void load() {
        despawnAll();
        var cfg = config.config();
        this.enabled = cfg.getBoolean("ai.enabled", true);
        this.perTeam = Math.max(0, cfg.getInt("ai.per-team", 2));

        String difficulty = cfg.getString("ai.difficulty", "NORMAL").toUpperCase(Locale.ROOT);
        double defaultDamage = switch (difficulty) {
            case "EASY" -> 2.0D;
            case "HARD" -> 4.0D;
            default -> 3.0D;
        };
        int defaultCooldown = switch (difficulty) {
            case "EASY" -> 25;
            case "HARD" -> 12;
            default -> 18;
        };

        this.damage = Math.max(0.5D, cfg.getDouble("ai.damage", defaultDamage));
        this.cooldownSteps = Math.max(1, cfg.getInt("ai.attack-cooldown-ticks", defaultCooldown) / STEP_TICKS);
        this.speed = Math.max(0.05D, Math.min(1.5D, cfg.getDouble("ai.speed", 0.45D)));
        this.attackRange = Math.max(1.0D, cfg.getDouble("ai.attack-range", 2.8D));
        this.bedRange = Math.max(1.0D, cfg.getDouble("ai.bed-range", 3.2D));
        this.aggroRange = Math.max(2.0D, cfg.getDouble("ai.aggro-range", 16.0D));
        this.bridging = cfg.getBoolean("ai.bridge", true);
        this.respawnSeconds = Math.max(1, cfg.getInt("settings.respawn-seconds", 4));
        this.nameCounters.clear();
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
        if (!value) {
            despawnAll();
        }
    }

    public int botCount() {
        return bots.size();
    }

    public boolean isBot(Entity entity) {
        return entity != null && bots.containsKey(entity.getUniqueId());
    }

    // ------------------------------------------------------------------
    // Spawning
    // ------------------------------------------------------------------

    /** Fills every team with bots; used when a real game starts. */
    public void spawnForGame() {
        if (!enabled || perTeam <= 0) {
            return;
        }
        despawnAll();
        for (Team team : arena.teams()) {
            for (int i = 0; i < perTeam; i++) {
                spawnBot(team, nextName(team));
            }
        }
    }

    /** Admin helper: populate the arena even when no game is running. */
    public int spawnTestBots() {
        despawnAll();
        int count = Math.max(1, perTeam);
        int spawned = 0;
        for (Team team : arena.teams()) {
            for (int i = 0; i < count; i++) {
                if (spawnBot(team, nextName(team))) {
                    spawned++;
                }
            }
        }
        return spawned;
    }

    private String nextName(Team team) {
        int number = nameCounters.merge(team.id(), 1, Integer::sum);
        String label = team.id().substring(0, 1).toUpperCase(Locale.ROOT) + team.id().substring(1);
        return label + " Bot " + number;
    }

    private boolean spawnBot(Team team, String name) {
        Location spawn = team.spawn();
        if (spawn == null || spawn.getWorld() == null) {
            return false;
        }
        World world = spawn.getWorld();
        int slot = bots.size();
        double angle = slot * 1.9D;
        Location location = spawn.clone().add(Math.cos(angle) * 1.2D, 0.0D, Math.sin(angle) * 1.2D);
        location.setDirection(new Vector(-Math.cos(angle), 0.0D, -Math.sin(angle)));

        Husk husk = world.spawn(location, Husk.class, entity -> configure(entity, team, name));
        if (husk == null) {
            return false;
        }
        team.addMember(husk.getUniqueId());
        bots.put(husk.getUniqueId(), new Bot(husk.getUniqueId(), team, name));
        return true;
    }

    private void configure(Husk husk, Team team, String name) {
        husk.customName(Component.text(name, team.color()));
        husk.setCustomNameVisible(true);
        husk.setPersistent(true);
        husk.setRemoveWhenFarAway(false);
        husk.setAware(false);
        husk.setGravity(false);
        husk.setCanPickupItems(false);
        husk.setVelocity(new Vector(0.0D, 0.0D, 0.0D));

        EntityEquipment equipment = husk.getEquipment();
        if (equipment == null) {
            return;
        }
        equipment.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
        Color color = teamColor(team);
        equipment.setHelmet(leather(Material.LEATHER_HELMET, color));
        equipment.setChestplate(leather(Material.LEATHER_CHESTPLATE, color));
        equipment.setLeggings(leather(Material.LEATHER_LEGGINGS, color));
        equipment.setBoots(leather(Material.LEATHER_BOOTS, color));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            equipment.setDropChance(slot, 0.0F);
        }
    }

    private static ItemStack leather(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Color teamColor(Team team) {
        String wool = team.woolMaterial().name();
        String dye = wool.endsWith("_WOOL") ? wool.substring(0, wool.length() - 5) : "WHITE";
        try {
            return DyeColor.valueOf(dye).getColor();
        } catch (IllegalArgumentException unknown) {
            return Color.WHITE;
        }
    }

    // ------------------------------------------------------------------
    // Behaviour
    // ------------------------------------------------------------------

    public void tick() {
        if (!enabled) {
            if (!bots.isEmpty()) {
                despawnAll();
            }
            return;
        }
        if (bots.isEmpty()) {
            return;
        }
        for (Bot bot : new ArrayList<>(bots.values())) {
            Entity entity = Bukkit.getEntity(bot.id);
            if (!(entity instanceof Husk husk) || !husk.isValid()) {
                bots.remove(bot.id);
                continue;
            }
            // A team reset (game over) drops the bot from its roster: clean it up.
            if (!bot.team.members().contains(bot.id) || bot.team.isEliminated(bot.id)) {
                husk.remove();
                bots.remove(bot.id);
                continue;
            }
            step(bot, husk);
        }
    }

    private void step(Bot bot, Husk husk) {
        Location current = husk.getLocation();
        husk.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
        bot.cooldown = Math.max(0, bot.cooldown - 1);

        LivingEntity enemy = nearestEnemy(bot, current);
        if (enemy != null) {
            double distance = current.distance(enemy.getLocation());
            if (distance > attackRange) {
                walk(bot, husk, enemy.getLocation());
                return;
            }
            husk.setRotation(yawTo(current, enemy.getLocation()), current.getPitch());
            if (bot.cooldown > 0) {
                return;
            }
            bot.cooldown = cooldownSteps;
            enemy.damage(damage, husk);
            husk.getWorld().playSound(current, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.6F, 1.0F);
            return;
        }

        Block bed = nearestEnemyBed(bot, current);
        if (bed != null) {
            Location centre = bed.getLocation().add(0.5D, 0.0D, 0.5D);
            if (current.distance(centre) > bedRange) {
                walk(bot, husk, centre);
                return;
            }
            arena.handleBotBedBreak(bot.team, bot.name, bed);
            return;
        }

        Team fallback = fallbackTarget(bot);
        if (fallback != null) {
            walk(bot, husk, fallback.spawn());
        }
    }

    private LivingEntity nearestEnemy(Bot bot, Location from) {
        LivingEntity best = null;
        double bestDistance = aggroRange;
        for (Team team : arena.teams()) {
            if (team == bot.team) {
                continue;
            }
            for (UUID member : team.members()) {
                LivingEntity candidate = memberEntity(member);
                if (candidate == null || candidate.isDead()) {
                    continue;
                }
                double distance = from.distance(candidate.getLocation());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private LivingEntity memberEntity(UUID id) {
        if (bots.containsKey(id)) {
            Entity entity = Bukkit.getEntity(id);
            return entity instanceof LivingEntity living ? living : null;
        }
        Player player = Bukkit.getPlayer(id);
        if (player == null || player.getGameMode() != GameMode.SURVIVAL) {
            return null;
        }
        return player;
    }

    private Block nearestEnemyBed(Bot bot, Location from) {
        Block best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Team team : arena.teams()) {
            if (team == bot.team || !team.bedAlive()) {
                continue;
            }
            Block block = team.bed().getBlock();
            if (!(block.getBlockData() instanceof Bed)) {
                continue;
            }
            double distance = from.distance(block.getLocation().add(0.5D, 0.0D, 0.5D));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = block;
            }
        }
        return best;
    }

    private Team fallbackTarget(Bot bot) {
        Team best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Team team : arena.teams()) {
            if (team == bot.team || team.spawn() == null) {
                continue;
            }
            double distance = bot.team.spawn().distance(team.spawn());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = team;
            }
        }
        return best;
    }

    private void walk(Bot bot, Husk husk, Location goal) {
        Location current = husk.getLocation();
        Vector direction = goal.toVector().subtract(current.toVector());
        direction.setY(0.0D);
        if (direction.lengthSquared() < 0.0001D) {
            return;
        }
        direction.normalize();

        Location next = current.clone().add(direction.clone().multiply(speed));
        next.setY(current.getY());
        if (!roomAt(next)) {
            Vector side = new Vector(-direction.getZ(), 0.0D, direction.getX());
            Location strafe = current.clone().add(side.multiply(speed));
            strafe.setY(current.getY());
            if (!roomAt(strafe)) {
                strafe = current.clone().subtract(side.multiply(speed));
                strafe.setY(current.getY());
                if (!roomAt(strafe)) {
                    return;
                }
            }
            next = strafe;
        }

        if (bridging) {
            bridge(bot, next);
        }
        next.setDirection(direction);
        husk.teleport(next);
    }

    /** Keeps a wool path under the bot, so it can cross the gaps between islands. */
    private void bridge(Bot bot, Location next) {
        Location below = new Location(next.getWorld(), next.getX(), next.getY() - 1.0D, next.getZ());
        Block block = below.getBlock();
        if (block.getType() != Material.AIR) {
            return;
        }
        Material wool = bot.team.woolMaterial();
        block.setType(wool, false);
        bridges.put(block.getLocation(), wool);
    }

    private static boolean roomAt(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        Block feet = world.getBlockAt(location);
        Block head = world.getBlockAt(location.clone().add(0.0D, 1.0D, 0.0D));
        return !feet.getType().isSolid() && !head.getType().isSolid();
    }

    private static float yawTo(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    // ------------------------------------------------------------------
    // Death and cleanup
    // ------------------------------------------------------------------

    public void handleDeath(Entity entity) {
        Bot bot = bots.remove(entity.getUniqueId());
        if (bot == null) {
            return;
        }
        entity.remove();
        Team team = bot.team;
        if (!arena.isRunning()) {
            return;
        }
        if (!team.bedAlive()) {
            arena.eliminateBot(team, bot.id, bot.name);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!arena.isRunning() || !team.bedAlive() || team.isEliminated(bot.id)
                    || !team.members().contains(bot.id)) {
                return;
            }
            spawnBot(team, bot.name);
        }, respawnSeconds * 20L);
    }

    /** Ends the round: removes the bots and the wool they placed. */
    public void despawnAll() {
        for (Bot bot : bots.values()) {
            Entity entity = Bukkit.getEntity(bot.id);
            if (entity != null) {
                entity.remove();
            }
            bot.team.removeMember(bot.id);
        }
        bots.clear();
        for (Map.Entry<Location, Material> bridge : bridges.entrySet()) {
            Block block = bridge.getKey().getBlock();
            if (block.getType() == bridge.getValue()) {
                block.setType(Material.AIR, false);
            }
        }
        bridges.clear();
    }

    public void shutdown() {
        despawnAll();
    }

    private static final class Bot {
        private final UUID id;
        private final Team team;
        private final String name;
        private int cooldown;

        private Bot(UUID id, Team team, String name) {
            this.id = id;
            this.team = team;
            this.name = name;
        }
    }
}
