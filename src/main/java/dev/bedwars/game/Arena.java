package dev.bedwars.game;

import dev.bedwars.BedwarsConfig;
import dev.bedwars.util.BedwarsMessages;
import dev.bedwars.world.ArenaWorld;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Bedwars game itself: who is on which team, the countdown, generators,
 * bed breaking, respawns and the win condition.
 *
 * <p>All methods run on the main thread (commands, events and the one-second
 * tick task), so plain collections are safe here.</p>
 */
public final class Arena {

    private enum State {
        WAITING, COUNTING, RUNNING, ENDING
    }

    private final JavaPlugin plugin;
    private final BedwarsMessages messages;
    private final ArenaWorld world;
    private final BedwarsConfig bedwarsConfig;
    private final List<Team> teams = new ArrayList<>();
    private final List<Generator> generators = new ArrayList<>();
    private final Map<UUID, Team> playerTeams = new LinkedHashMap<>();
    private final Map<UUID, BukkitTask> respawnTasks = new ConcurrentHashMap<>();

    private State state = State.WAITING;
    private int minPlayers;
    private int maxTeamSize;
    private int countdownSeconds;
    private int respawnSeconds;
    private int voidY;
    private int countdown;
    private int elapsed;

    public Arena(JavaPlugin plugin, BedwarsMessages messages, ArenaWorld world, BedwarsConfig bedwarsConfig) {
        this.plugin = plugin;
        this.messages = messages;
        this.world = world;
        this.bedwarsConfig = bedwarsConfig;
    }

    public List<Team> teams() {
        return teams;
    }

    public State state() {
        return state;
    }

    // ------------------------------------------------------------------
    // Loading and configuration
    // ------------------------------------------------------------------

    public void load() {
        cancelRespawns();
        playerTeams.clear();
        teams.clear();
        generators.clear();

        var config = bedwarsConfig.config();
        this.minPlayers = Math.max(1, config.getInt("settings.min-players", 2));
        this.maxTeamSize = Math.max(1, config.getInt("settings.max-team-size", 4));
        this.countdownSeconds = Math.max(1, config.getInt("settings.countdown-seconds", 15));
        this.respawnSeconds = Math.max(1, config.getInt("settings.respawn-seconds", 4));
        this.voidY = config.getInt("settings.void-y", 20);

        int iron = Math.max(1, config.getInt("settings.generator-seconds.iron", 2));
        int gold = Math.max(1, config.getInt("settings.generator-seconds.gold", 8));
        int diamond = Math.max(1, config.getInt("settings.generator-seconds.diamond", 25));
        int emerald = Math.max(1, config.getInt("settings.generator-seconds.emerald", 40));
        Map<String, Integer> intervals = Map.of(
                "IRON", iron, "GOLD", gold, "DIAMOND", diamond, "EMERALD", emerald);

        ConfigurationSection teamsSection = config.getConfigurationSection("settings.teams");
        if (teamsSection != null) {
            for (String id : teamsSection.getKeys(false)) {
                ConfigurationSection section = teamsSection.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                Location spawn = world.location(section.getConfigurationSection("spawn"), 66.0D);
                Location bed = world.location(section.getConfigurationSection("bed"), 65.0D);
                if (spawn == null || bed == null) {
                    continue;
                }
                teams.add(new Team(id, section.getString("color", "WHITE"), spawn, bed));
            }
        }

        world.build(teams.stream()
                .map(team -> new ArenaWorld.TeamRef(team.spawn(), team.woolMaterial()))
                .toList());
        for (Team team : teams) {
            world.placeBed(team.bed(), team.bedMaterial());
        }

        for (Map<?, ?> entry : config.getMapList("settings.generators")) {
            Object rawType = entry.get("type");
            if (rawType == null) {
                continue;
            }
            String type = String.valueOf(rawType).toUpperCase(Locale.ROOT);
            Integer interval = intervals.get(type);
            if (interval == null) {
                continue;
            }
            double x = number(entry.get("x"), 0.5D);
            double y = number(entry.get("y"), 65.0D);
            double z = number(entry.get("z"), 0.5D);
            generators.add(new Generator(type, new Location(world.world(), x, y, z), interval));
        }

        resetState();
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number numeric) {
            return numeric.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }

    private void resetState() {
        state = State.WAITING;
        countdown = countdownSeconds;
        elapsed = 0;
        for (Team team : teams) {
            team.reset();
        }
        for (Generator generator : generators) {
            generator.remaining = generator.intervalSeconds;
        }
    }

    // ------------------------------------------------------------------
    // Joining and leaving
    // ------------------------------------------------------------------

    public boolean join(Player player) {
        if (playerTeams.containsKey(player.getUniqueId())) {
            messages.send(player, "already-playing");
            return false;
        }
        if (state == State.RUNNING || state == State.ENDING) {
            messages.send(player, "in-progress");
            return false;
        }
        Team team = bestTeam();
        if (team == null) {
            messages.send(player, "game-full");
            return false;
        }

        team.addMember(player.getUniqueId());
        playerTeams.put(player.getUniqueId(), team);
        player.teleport(team.spawn());
        Kit.give(player, team);
        applyBoard(player);

        int needed = Math.max(0, minPlayers - playerTeams.size());
        if (needed > 0) {
            messages.send(player, "joined", Placeholder.unparsed("needed", String.valueOf(needed)));
        } else {
            messages.send(player, "joined-full");
        }
        if (state == State.WAITING && playerTeams.size() >= minPlayers) {
            state = State.COUNTING;
            countdown = countdownSeconds;
        }
        return true;
    }

    private Team bestTeam() {
        return teams.stream()
                .filter(team -> team.members().size() < maxTeamSize)
                .min(Comparator.comparingInt(team -> team.members().size()))
                .orElse(null);
    }

    public void leave(Player player, boolean quitting) {
        Team team = playerTeams.remove(player.getUniqueId());
        cancelRespawn(player.getUniqueId());
        if (team == null) {
            if (!quitting) {
                messages.send(player, "not-playing");
            }
            return;
        }
        team.removeMember(player.getUniqueId());
        resetPlayer(player);
        if (!quitting) {
            Location lobby = world.lobby();
            if (lobby != null) {
                player.teleport(lobby);
            }
            messages.send(player, "left");
        }
        if (state == State.RUNNING) {
            checkWin();
        } else if (playerTeams.isEmpty() && state == State.COUNTING) {
            state = State.WAITING;
            countdown = countdownSeconds;
        }
    }

    private void resetPlayer(Player player) {
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.setHealth(20.0D);
        player.setFoodLevel(20);
        player.setFireTicks(0);
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    // ------------------------------------------------------------------
    // Game flow
    // ------------------------------------------------------------------

    public void startGame() {
        if (teams.isEmpty() || playerTeams.isEmpty()) {
            return;
        }
        state = State.RUNNING;
        elapsed = 0;
        for (Generator generator : generators) {
            generator.remaining = generator.intervalSeconds;
        }
        for (Map.Entry<UUID, Team> entry : playerTeams.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            player.teleport(entry.getValue().spawn());
            Kit.give(player, entry.getValue());
            updateBoard(player);
        }
        messages.broadcast("started");
    }

    public void tick() {
        switch (state) {
            case COUNTING -> {
                countdown--;
                if (countdown <= 0) {
                    startGame();
                } else if (countdown <= 5 || countdown % 5 == 0) {
                    messages.broadcast("countdown", Placeholder.unparsed("seconds", String.valueOf(countdown)));
                }
            }
            case RUNNING -> {
                elapsed++;
                runGenerators();
                killVoidPlayers();
                for (UUID member : playerTeams.keySet()) {
                    Player player = Bukkit.getPlayer(member);
                    if (player != null) {
                        updateBoard(player);
                    }
                }
            }
            default -> {
                // Nothing to do while waiting for players or during the victory screen.
            }
        }
    }

    private void runGenerators() {
        for (Generator generator : generators) {
            generator.remaining--;
            if (generator.remaining > 0) {
                continue;
            }
            generator.remaining = generator.intervalSeconds;
            World arenaWorld = generator.location.getWorld();
            if (arenaWorld == null) {
                continue;
            }
            ItemStack drop = new ItemStack(generator.material);
            arenaWorld.dropItemNaturally(generator.location, drop);
        }
    }

    private void killVoidPlayers() {
        for (UUID member : List.copyOf(playerTeams.keySet())) {
            Player player = Bukkit.getPlayer(member);
            if (player == null || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (player.getLocation().getY() < voidY) {
                messages.send(player, "void");
                player.setHealth(0.0D);
            }
        }
    }

    // ------------------------------------------------------------------
    // Death, respawn and elimination
    // ------------------------------------------------------------------

    public void handleDeath(Player player) {
        Team team = playerTeams.get(player.getUniqueId());
        if (team == null) {
            return;
        }
        if (team.bedAlive()) {
            scheduleRespawn(player, team);
        } else {
            eliminate(player, team);
        }
    }

    private void scheduleRespawn(Player player, Team team) {
        int seconds = respawnSeconds;
        messages.send(player, "respawn", Placeholder.unparsed("seconds", String.valueOf(seconds)));
        player.setGameMode(GameMode.SPECTATOR);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.spigot().respawn();
            }
        });
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            respawnTasks.remove(player.getUniqueId());
            if (!player.isOnline() || !playerTeams.containsKey(player.getUniqueId())) {
                return;
            }
            player.teleport(team.spawn());
            Kit.give(player, team);
            updateBoard(player);
        }, seconds * 20L);
        respawnTasks.put(player.getUniqueId(), task);
    }

    private void eliminate(Player player, Team team) {
        team.eliminate(player.getUniqueId());
        messages.send(player, "eliminated-player");
        player.setGameMode(GameMode.SPECTATOR);
        Location lobby = world.lobby();
        if (lobby != null) {
            player.teleport(lobby);
        }
        if (team.aliveCount() == 0) {
            eliminateTeam(team);
        }
        checkWin();
    }

    private void eliminateTeam(Team team) {
        messages.broadcast("eliminated-team", Placeholder.component("team", team.component()));
        for (UUID member : team.members()) {
            Player player = Bukkit.getPlayer(member);
            if (player != null) {
                team.eliminate(member);
                player.setGameMode(GameMode.SPECTATOR);
            }
        }
    }

    private void checkWin() {
        if (state != State.RUNNING) {
            return;
        }
        List<Team> alive = teams.stream().filter(team -> team.aliveCount() > 0).toList();
        if (alive.size() > 1) {
            return;
        }
        if (alive.isEmpty()) {
            reset();
            return;
        }
        Team winner = alive.getFirst();
        state = State.ENDING;
        messages.broadcast("victory", Placeholder.component("team", winner.component()));
        Bukkit.getScheduler().runTaskLater(plugin, this::reset, 8 * 20L);
    }

    // ------------------------------------------------------------------
    // Beds
    // ------------------------------------------------------------------

    public boolean handleBedBreak(Player breaker, Block block) {
        Team owner = teamAt(block.getLocation());
        if (owner == null) {
            return false;
        }
        if (!owner.bedAlive()) {
            messages.send(breaker, "bed-gone");
            return true;
        }
        Team breakerTeam = playerTeams.get(breaker.getUniqueId());
        if (breakerTeam == owner) {
            messages.send(breaker, "bed-own");
            return true;
        }
        owner.bedAlive(false);
        world.removeBed(owner.bed());
        messages.broadcast("bed-destroyed",
                Placeholder.component("team", owner.component()),
                Placeholder.unparsed("player", breaker.getName()));
        if (owner.aliveCount() == 0) {
            eliminateTeam(owner);
        }
        checkWin();
        return true;
    }

    private Team teamAt(Location location) {
        for (Team team : teams) {
            Location bed = team.bed();
            if (bed.getWorld() == null || !bed.getWorld().equals(location.getWorld())) {
                continue;
            }
            if (bed.getBlockY() == location.getBlockY()
                    && Math.abs(bed.getBlockX() - location.getBlockX()) <= 1
                    && Math.abs(bed.getBlockZ() - location.getBlockZ()) <= 1) {
                return team;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Scoreboard
    // ------------------------------------------------------------------

    private void applyBoard(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective(
                "bedwars", Criteria.DUMMY,
                Component.text("BEDWARS", NamedTextColor.GOLD),
                RenderType.INTEGER);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        player.setScoreboard(board);
        updateBoard(player);
    }

    private void updateBoard(Player player) {
        Team own = playerTeams.get(player.getUniqueId());
        if (own == null) {
            return;
        }
        Scoreboard board = player.getScoreboard();
        Objective objective = board.getObjective("bedwars");
        if (objective == null) {
            return;
        }
        for (String entry : new ArrayList<>(board.getEntries())) {
            board.resetScores(entry);
        }

        List<String> lines = new ArrayList<>();
        lines.add(" ");
        lines.add("Team: " + own.id());
        for (Team team : teams) {
            lines.add(team.id() + " bed: " + (team.bedAlive() ? "alive" : "broken"));
        }
        lines.add("  ");
        lines.add(state == State.RUNNING ? "Time: " + elapsed + "s" : (state == State.ENDING ? "Game over" : "Waiting"));
        lines.add("   ");
        lines.add("Players: " + playerTeams.size());

        int score = lines.size();
        for (String line : lines) {
            objective.getScore(line).setScore(score--);
        }
    }

    // ------------------------------------------------------------------
    // Reset and shutdown
    // ------------------------------------------------------------------

    public void reset() {
        cancelRespawns();
        state = State.WAITING;
        countdown = countdownSeconds;
        elapsed = 0;
        Location lobby = world.lobby();
        for (UUID member : List.copyOf(playerTeams.keySet())) {
            Player player = Bukkit.getPlayer(member);
            if (player != null) {
                resetPlayer(player);
                if (lobby != null) {
                    player.teleport(lobby);
                }
            }
        }
        playerTeams.clear();
        for (Team team : teams) {
            team.reset();
        }
        for (Generator generator : generators) {
            generator.remaining = generator.intervalSeconds;
        }
        world.build(teams.stream()
                .map(team -> new ArenaWorld.TeamRef(team.spawn(), team.woolMaterial()))
                .toList());
        for (Team team : teams) {
            world.placeBed(team.bed(), team.bedMaterial());
        }
    }

    public void shutdown() {
        cancelRespawns();
        Location lobby = world.lobby();
        for (UUID member : List.copyOf(playerTeams.keySet())) {
            Player player = Bukkit.getPlayer(member);
            if (player != null) {
                resetPlayer(player);
                if (lobby != null) {
                    player.teleport(lobby);
                }
            }
        }
        playerTeams.clear();
        cancelRespawns();
    }

    private void cancelRespawns() {
        for (BukkitTask task : respawnTasks.values()) {
            task.cancel();
        }
        respawnTasks.clear();
    }

    private void cancelRespawn(UUID uniqueId) {
        BukkitTask task = respawnTasks.remove(uniqueId);
        if (task != null) {
            task.cancel();
        }
    }

    // ------------------------------------------------------------------
    // Helpers used by listeners
    // ------------------------------------------------------------------

    public boolean isPlaying(Player player) {
        return playerTeams.containsKey(player.getUniqueId());
    }

    public Team teamOf(Player player) {
        return playerTeams.get(player.getUniqueId());
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public int elapsedSeconds() {
        return elapsed;
    }

    public Location respawnLocation(Player player) {
        Team team = playerTeams.get(player.getUniqueId());
        if (team == null) {
            return world.lobby();
        }
        if (team.isEliminated(player.getUniqueId())) {
            return world.lobby();
        }
        return team.spawn();
    }

    private static final class Generator {
        private final Material material;
        private final Location location;
        private final int intervalSeconds;
        private int remaining;

        private Generator(String type, Location location, int intervalSeconds) {
            this.material = switch (type) {
                case "GOLD" -> Material.GOLD_INGOT;
                case "DIAMOND" -> Material.DIAMOND;
                case "EMERALD" -> Material.EMERALD;
                default -> Material.IRON_INGOT;
            };
            this.location = location;
            this.intervalSeconds = intervalSeconds;
            this.remaining = intervalSeconds;
        }
    }
}
