package dev.speedrun.run;

import dev.speedrun.SpeedrunPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.StructureSearchResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The runner: a bot that plays a full Any% route on its own.
 *
 * <p>It has no pathfinder and no scripted coordinates. Each tick it looks at
 * what the route still needs, searches the world around itself for the nearest
 * matching block or structure, walks or digs to it, and banks the real drops.</p>
 *
 * <p>Route: wood, wooden pickaxe, shaft to iron, stone pickaxe, iron, furnace,
 * iron pickaxe, diamonds, diamond pickaxe, bucket, flint and steel, obsidian
 * from a ruined portal, a lit portal, a nether fortress, blaze rods, ender
 * pearls, eyes of ender, the stronghold's end portal, and the dragon.</p>
 *
 * <p>Two deliberate shortcuts are documented in the config: long distance travel
 * is done in stepped teleports rather than real pathing (nothing in Bukkit can
 * path thousands of blocks), and mob drops are counted from the kill rather than
 * picked up from the ground.</p>
 */
public final class Runner {

    /** Splits, in the order the route produces them. */
    private enum Phase {
        WOOD, DIG, STONE_PICK, IRON, FURNACE, SMELT, CRAFT,
        DIAMOND, DIAMOND_PICK, IRON_FOR_BUCKET, SMELT_BUCKET, FLINT,
        OBSIDIAN, PORTAL, FORTRESS, BLAZE, PEARL, EYES, STRONGHOLD, END_PORTAL, DRAGON, DONE
    }

    private static final double REACH = 3.0D;
    private static final int PLANKS_FOR_ROUTE = 13;
    private static final int COBBLESTONE_FOR_ROUTE = 11;
    private static final int SMELT_TICKS_PER_ITEM = 200;

    private final SpeedrunPlugin plugin;
    private final RunState run;
    private final Inventory inventory = new Inventory();
    private final Husk husk;

    private final double speed;
    private final int stepTicks;
    private final int breakTicks;
    private final int searchRadius;
    private final int digToY;
    private final int diamondY;
    private final int logsNeeded;
    private final int ironNeeded;
    private final int diamondsNeeded;
    private final int obsidianNeeded;
    private final int rodsNeeded;
    private final int pearlsNeeded;
    private final int locateRadius;
    private final double travelStep;

    private Phase phase = Phase.WOOD;
    private Block target;
    private boolean descending;
    private boolean rising;
    private int workTicks;
    private int moveTicks;
    private int searchCooldown;
    private double wanderAngle;
    private int wanderTicks;
    private Location wanderTarget;
    private int smeltTicks;
    private int smeltTotal;
    private int smeltOutput;
    private Location travelTarget;
    private Location portalSpot;
    private int attackCooldown;
    private Location lastSeen;
    private int stallTicks;
    private final Map<Long, int[]> ticketed = new HashMap<>();
    private int ticketTicks;

    public Runner(SpeedrunPlugin plugin, RunState run, Location spawn) {
        this.plugin = plugin;
        this.run = run;

        var config = plugin.getConfig();
        this.speed = Math.max(0.05D, config.getDouble("run.speed", 0.35D));
        this.stepTicks = Math.max(1, config.getInt("run.step-ticks", 2));
        this.breakTicks = Math.max(1, config.getInt("run.break-ticks", 10));
        this.searchRadius = Math.max(6, config.getInt("run.search-radius", 24));
        this.digToY = config.getInt("run.dig-to-y", 40);
        this.diamondY = config.getInt("run.diamond-y", -54);
        this.logsNeeded = Math.max(1, config.getInt("run.logs", 4));
        this.ironNeeded = Math.max(1, config.getInt("run.iron", 3));
        this.diamondsNeeded = Math.max(1, config.getInt("run.diamonds", 3));
        this.obsidianNeeded = Math.max(10, config.getInt("run.obsidian", 10));
        this.rodsNeeded = Math.max(6, config.getInt("run.blaze-rods", 12));
        this.pearlsNeeded = Math.max(1, config.getInt("run.pearls", 12));
        this.locateRadius = Math.max(200, config.getInt("run.locate-radius", 4000));
        this.travelStep = Math.max(8.0D, config.getDouble("run.travel-step", 32.0D));

        String name = config.getString("run.name", "Runner");
        World world = spawn.getWorld();
        spawn.getChunk().load();
        Husk spawned = world.spawn(spawn, Husk.class);
        if (spawned == null) {
            plugin.getLogger().severe("Could not spawn the runner at " + spawn.getBlockX()
                    + ", " + spawn.getBlockY() + ", " + spawn.getBlockZ() + ".");
            this.husk = null;
            return;
        }
        configure(spawned, name);
        this.husk = spawned;
    }

    private void configure(Husk entity, String name) {
        entity.customName(Component.text(name, NamedTextColor.GOLD));
        entity.setCustomNameVisible(true);
        entity.setPersistent(true);
        entity.setAware(false);
        entity.setGravity(false);
        entity.setInvulnerable(true);
        entity.setCanPickupItems(false);
        entity.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
        var equipment = entity.getEquipment();
        if (equipment != null) {
            equipment.setItemInMainHand(new ItemStack(Material.AIR));
            equipment.setHelmet(new ItemStack(Material.LEATHER_HELMET));
        }
    }

    public void remove() {
        if (husk != null && husk.isValid()) {
            World world = husk.getWorld();
            for (int[] chunk : ticketed.values()) {
                world.removePluginChunkTicket(chunk[0], chunk[1], plugin);
            }
            husk.remove();
        }
        ticketed.clear();
    }

    public boolean alive() {
        return husk != null && husk.isValid();
    }

    /**
     * Jumps straight to a route phase, for operators and for testing a phase in
     * isolation. Nether and End phases move the runner to that dimension.
     */
    public boolean jumpTo(String phaseName) {
        if (!alive()) {
            return false;
        }
        Phase next;
        try {
            next = Phase.valueOf(phaseName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return false;
        }
        this.phase = next;
        this.target = null;
        this.travelTarget = null;
        this.workTicks = 0;
        this.searchCooldown = 0;
        if (next == Phase.FORTRESS || next == Phase.BLAZE || next == Phase.PEARL) {
            World nether = netherWorld();
            if (nether != null) {
                Location spot = new Location(nether, 0.5D, 64.0D, 0.5D);
                spot.getChunk().load();
                husk.teleport(spot);
            }
        }
        if (next == Phase.DRAGON) {
            World end = endWorld();
            if (end != null) {
                Location spot = new Location(end, 0.5D, 80.0D, 0.5D);
                spot.getChunk().load();
                husk.teleport(spot);
            }
        }
        return true;
    }

    /** Compact state line used by the status command. */
    public String phaseName() {
        return phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    public String describe() {
        if (!alive()) {
            return "runner is gone";
        }
        Location location = husk.getLocation();
        return "phase=" + phase
                + " world=" + location.getWorld().getName()
                + " pos=" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ()
                + " target=" + (target == null ? "none"
                        : target.getType() + "@" + target.getX() + "," + target.getY() + "," + target.getZ())
                + " items=" + inventory.describe();
    }

    // ------------------------------------------------------------------
    // Route
    // ------------------------------------------------------------------

    public void tick() {
        if (husk == null || !husk.isValid() || phase == Phase.DONE) {
            return;
        }
        husk.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
        keepLoaded();
        if (watchdogPhase()) {
            trackProgress();
        } else {
            lastSeen = null;
            stallTicks = 0;
        }
        if (attackCooldown > 0) {
            attackCooldown--;
        }
        if (target == null && !traveling() && !hasGroundBelow()) {
            husk.teleport(husk.getLocation().subtract(0.0D, 1.0D, 0.0D));
            return;
        }
        if (target != null) {
            workTarget();
            return;
        }
        if (searchCooldown > 0) {
            searchCooldown--;
        }
        switch (phase) {
            case WOOD -> wood();
            case DIG -> dig();
            case STONE_PICK -> stonePick();
            case IRON -> iron();
            case FURNACE -> furnace();
            case SMELT -> smelt();
            case CRAFT -> craft();
            case DIAMOND -> diamond();
            case DIAMOND_PICK -> diamondPick();
            case IRON_FOR_BUCKET -> ironForBucket();
            case SMELT_BUCKET -> smeltBucket();
            case FLINT -> flint();
            case OBSIDIAN -> obsidian();
            case PORTAL -> portal();
            case FORTRESS -> fortress();
            case BLAZE -> blaze();
            case PEARL -> pearl();
            case EYES -> eyes();
            case STRONGHOLD -> stronghold();
            case END_PORTAL -> endPortal();
            case DRAGON -> dragon();
            case DONE -> { }
        }
    }

    private boolean traveling() {
        return travelTarget != null;
    }

    /** Wood, a crafting table and a wooden pickaxe. */
    private void wood() {
        boolean enoughLogs = inventory.countTag(Tag.LOGS) >= logsNeeded;
        boolean enoughPlanks = inventory.countTag(Tag.PLANKS) >= PLANKS_FOR_ROUTE;
        if (!enoughLogs && !enoughPlanks) {
            // Trees can be a long way off (a mountain spawn has none at all), so
            // cover ground with the same stepped travel the structures use.
            if (!gather(Tag.LOGS::isTagged, 10, 6, searchRadius + 12)) {
                if (travelTarget == null) {
                    wanderAngle = (wanderAngle + 2.399D) % (Math.PI * 2.0D);
                    travelTarget = husk.getLocation().clone().add(
                            Math.cos(wanderAngle) * 128.0D, 0.0D, Math.sin(wanderAngle) * 128.0D);
                }
                if (travelTo(travelTarget)) {
                    travelTarget = null;
                }
            }
            return;
        }
        mark("wood");

        while (inventory.countTag(Tag.PLANKS) < PLANKS_FOR_ROUTE && inventory.countTag(Tag.LOGS) > 0) {
            Crafting.planks(inventory, 1);
        }
        while (inventory.count(Material.STICK) < 2 && inventory.countTag(Tag.PLANKS) >= 2) {
            Crafting.sticks(inventory);
        }
        if (inventory.count(Material.WOODEN_PICKAXE) == 0) {
            if (!Crafting.table(inventory)) {
                return;
            }
            placeBeside(Material.CRAFTING_TABLE);
            if (!Crafting.woodenPickaxe(inventory)) {
                return;
            }
        }
        equip();
        mark("wooden pickaxe");
        phase = Phase.DIG;
    }

    private void dig() {
        boolean deepEnough = husk.getLocation().getY() <= digToY;
        boolean enoughCobble = inventory.count(Material.COBBLESTONE) >= COBBLESTONE_FOR_ROUTE;
        if (deepEnough && enoughCobble) {
            mark("dug down");
            phase = Phase.STONE_PICK;
            return;
        }
        if (!deepEnough) {
            if (!digDown()) {
                digForward();
            }
            return;
        }
        if (!gather(material -> material == Material.STONE || material == Material.COBBLESTONE, 6, 12)
                && !digDown()) {
            digForward();
        }
    }

    private void stonePick() {
        if (!Crafting.stonePickaxe(inventory)) {
            phase = Phase.DIG;
            return;
        }
        equip();
        mark("stone pickaxe");
        phase = Phase.IRON;
    }

    private void iron() {
        if (inventory.count(Material.RAW_IRON) >= ironNeeded) {
            mark("iron ore");
            phase = Phase.FURNACE;
            return;
        }
        if (!gather(Runner::isIronOre, 8, 24) && !digDown()) {
            digForward();
        }
    }

    private void furnace() {
        if (inventory.count(Material.FURNACE) == 0) {
            if (!Crafting.furnace(inventory)) {
                phase = Phase.DIG;
                return;
            }
        }
        if (inventory.count(Material.RAW_IRON) < ironNeeded) {
            phase = Phase.IRON;
            return;
        }
        if (!startSmelt(ironNeeded, Material.RAW_IRON)) {
            phase = Phase.WOOD;
            return;
        }
        mark("furnace");
        phase = Phase.SMELT;
    }

    private void smelt() {
        if (!smeltFinished()) {
            return;
        }
        inventory.add(Material.IRON_INGOT, smeltOutput);
        equip();
        mark("smelted iron");
        phase = Phase.CRAFT;
    }

    private void craft() {
        if (!Crafting.ironPickaxe(inventory)) {
            phase = Phase.WOOD;
            return;
        }
        equip();
        mark("iron pickaxe");
        phase = Phase.DIAMOND;
    }

    // ------------------------------------------------------------------
    // Diamonds, bucket, flint
    // ------------------------------------------------------------------

    private void diamond() {
        if (inventory.count(Material.DIAMOND) >= diamondsNeeded) {
            mark("diamonds");
            phase = Phase.DIAMOND_PICK;
            return;
        }
        if (husk.getLocation().getY() > diamondY) {
            if (!digDown()) {
                digForward();
            }
            return;
        }
        // Diamonds do not generate below y=-64, so a cave fall has to be undone
        // before searching. Mine the block over the head, or step up if the
        // shaft is already clear.
        if (husk.getLocation().getY() < diamondY - 8) {
            Location current = husk.getLocation();
            Block aboveHead = husk.getWorld().getBlockAt(
                    current.getBlockX(), current.getBlockY() + 2, current.getBlockZ());
            if (isDiggable(aboveHead.getType())) {
                target = aboveHead;
                rising = true;
                descending = false;
                workTicks = 0;
                return;
            }
            Location up = current.clone().add(0.0D, 1.0D, 0.0D);
            if (passable(up)) {
                husk.teleport(up);
            } else {
                digForward();
            }
            return;
        }
        if (!gather(Runner::isDiamondOre, 6, 16)) {
            digForward();
        }
    }

    private void diamondPick() {
        if (inventory.count(Material.DIAMOND_PICKAXE) == 0) {
            if (!Crafting.diamondPickaxe(inventory)) {
                return;
            }
            equip();
        }
        mark("diamond pickaxe");
        phase = Phase.IRON_FOR_BUCKET;
    }

    private void ironForBucket() {
        if (inventory.count(Material.IRON_INGOT) >= 3) {
            if (inventory.count(Material.BUCKET) == 0) {
                inventory.take(Material.IRON_INGOT, 3);
                inventory.add(Material.BUCKET, 1);
            }
            mark("bucket");
            phase = Phase.FLINT;
            return;
        }
        if (inventory.count(Material.RAW_IRON) >= 3) {
            if (!startSmelt(3, Material.RAW_IRON)) {
                phase = Phase.WOOD;
                return;
            }
            phase = Phase.SMELT_BUCKET;
            return;
        }
        if (!gather(Runner::isIronOre, 24, 24) && !digDown()) {
            digForward();
        }
    }

    private void smeltBucket() {
        if (!smeltFinished()) {
            return;
        }
        inventory.add(Material.IRON_INGOT, smeltOutput);
        phase = Phase.IRON_FOR_BUCKET;
    }

    /** Gravel until flint, then flint and steel. */
    private void flint() {
        if (inventory.count(Material.FLINT_AND_STEEL) > 0) {
            mark("flint and steel");
            phase = Phase.OBSIDIAN;
            return;
        }
        if (inventory.count(Material.FLINT) > 0 && inventory.count(Material.IRON_INGOT) > 0) {
            inventory.take(Material.FLINT, 1);
            inventory.take(Material.IRON_INGOT, 1);
            inventory.add(Material.FLINT_AND_STEEL, 1);
            return;
        }
        if (!gather(material -> material == Material.GRAVEL, 8, 12)) {
            if (inventory.countTag(Tag.LOGS) > 0) {
                phase = Phase.WOOD;
            } else {
                wander();
            }
        }
    }

    // ------------------------------------------------------------------
    // Obsidian and the portal
    // ------------------------------------------------------------------

    /** Mines obsidian out of the nearest ruined portal. */
    private void obsidian() {
        if (inventory.count(Material.OBSIDIAN) >= obsidianNeeded) {
            mark("obsidian");
            phase = Phase.PORTAL;
            return;
        }
        if (inventory.count(Material.DIAMOND_PICKAXE) == 0) {
            phase = Phase.DIAMOND;
            return;
        }
        if (inventory.count(Material.BUCKET) == 0) {
            phase = Phase.FLINT;
            return;
        }
        if (travelTarget == null) {
            Location ruined = locate(Structure.RUINED_PORTAL);
            if (ruined != null) {
                travelTarget = ruined;
                plugin.getLogger().info("Ruined portal found at " + ruined.getBlockX()
                        + ", " + ruined.getBlockZ() + ".");
            }
        }
        if (travelTarget != null && !travelTo(travelTarget)) {
            return;
        }
        travelTarget = null;
        if (!gather(material -> material == Material.OBSIDIAN, 8, 8, 48)) {
            wander();
        }
    }

    /** Builds a lit portal at the runner and steps into the Nether. */
    private void portal() {
        if (portalSpot == null) {
            portalSpot = buildPortal();
            if (portalSpot == null) {
                wander();
                return;
            }
            mark("portal lit");
        }
        World nether = netherWorld();
        if (nether == null) {
            plugin.getLogger().severe("No nether world is available; is allow-nether off?");
            phase = Phase.DONE;
            plugin.finishRun();
            return;
        }
        Location exit = new Location(nether,
                portalSpot.getX() / 8.0D, 64.0D, portalSpot.getZ() / 8.0D);
        exit.setY(safeY(nether, exit.getBlockX(), exit.getBlockZ(), 64));
        exit.getChunk().load();
        husk.teleport(exit);
        mark("nether");
        phase = Phase.FORTRESS;
    }

    private Location buildPortal() {
        World world = husk.getWorld();
        Location base = husk.getLocation().clone();
        int x = base.getBlockX();
        int z = base.getBlockZ();
        int y = base.getBlockY();

        // Clear a 6x6x5 pocket so the frame is not buried.
        for (int dx = -1; dx <= 5; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 4; dy++) {
                    Block block = world.getBlockAt(x + dx, y + dy, z + dz);
                    if (block.getType().isSolid()) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
        for (int dx = 0; dx <= 3; dx++) {
            for (int dy = 0; dy <= 4; dy++) {
                boolean frame = dy == 0 || dy == 4 || dx == 0 || dx == 3;
                Block block = world.getBlockAt(x + dx, y + dy, z);
                if (frame) {
                    if (!inventory.take(Material.OBSIDIAN, 1)) {
                        return null;
                    }
                    block.setType(Material.OBSIDIAN, false);
                } else {
                    block.setType(Material.NETHER_PORTAL, false);
                }
            }
        }
        world.playSound(base, org.bukkit.Sound.BLOCK_END_PORTAL_SPAWN, 0.7F, 1.0F);
        return new Location(world, x + 1.5D, y + 1.0D, z + 0.5D);
    }

    /** Phases where standing still means the runner is stuck, not waiting. */
    private boolean watchdogPhase() {
        return switch (phase) {
            case SMELT, SMELT_BUCKET, CRAFT, FURNACE, PORTAL, EYES, DONE -> false;
            default -> true;
        };
    }

    /**
     * Breaks out of a no-progress state: a target that cannot be reached and a
     * step that is blocked would otherwise repeat forever.
     */
    private void trackProgress() {
        Location now = husk.getLocation();
        if (lastSeen != null && now.getWorld() == lastSeen.getWorld()
                && now.distanceSquared(lastSeen) < 0.01D) {
            if (++stallTicks >= 80) {
                stallTicks = 0;
                target = null;
                travelTarget = null;
                descending = false;
                rising = false;
                workTicks = 0;
                Location escape = now.clone().add(0.0D, 1.0D, 0.0D);
                if (passable(escape)) {
                    husk.teleport(escape);
                } else {
                    digSide();
                }
            }
            return;
        }
        lastSeen = now;
        stallTicks = 0;
    }

    private World netherWorld() {
        World named = Bukkit.getWorld(husk.getWorld().getName() + "_nether");
        if (named != null) {
            return named;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NETHER) {
                return world;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Nether: fortress, blazes, pearls
    // ------------------------------------------------------------------

    private void fortress() {
        World nether = husk.getWorld();
        if (nether.getEnvironment() != World.Environment.NETHER) {
            phase = Phase.PORTAL;
            return;
        }
        if (travelTarget == null) {
            Location fortress = locate(Structure.FORTRESS);
            if (fortress == null) {
                wander();
                return;
            }
            travelTarget = fortress;
            plugin.getLogger().info("Fortress found at " + fortress.getBlockX()
                    + ", " + fortress.getBlockZ() + ".");
        }
        if (!travelTo(travelTarget)) {
            return;
        }
        travelTarget = null;
        mark("fortress");
        phase = Phase.BLAZE;
    }

    private void blaze() {
        if (inventory.count(Material.BLAZE_ROD) >= rodsNeeded) {
            mark("blaze rods");
            phase = Phase.PEARL;
            return;
        }
        if (!fight(EntityType.BLAZE, Material.BLAZE_ROD, 1)) {
            wander();
        }
    }

    private void pearl() {
        if (inventory.count(Material.ENDER_PEARL) >= pearlsNeeded) {
            mark("ender pearls");
            phase = Phase.EYES;
            return;
        }
        if (!fight(EntityType.ENDERMAN, Material.ENDER_PEARL, 1)) {
            wander();
        }
    }

    private void eyes() {
        while (inventory.count(Material.BLAZE_ROD) > 0
                && inventory.count(Material.ENDER_PEARL) > 0
                && inventory.count(Material.ENDER_EYE) < 12) {
            inventory.take(Material.BLAZE_ROD, 1);
            inventory.take(Material.ENDER_PEARL, 1);
            inventory.add(Material.ENDER_EYE, 2);
        }
        if (inventory.count(Material.ENDER_EYE) < 12
                && inventory.count(Material.BLAZE_ROD) == 0) {
            phase = Phase.BLAZE;
            return;
        }
        if (inventory.count(Material.ENDER_EYE) < 12) {
            phase = Phase.PEARL;
            return;
        }
        mark("eyes of ender");
        phase = Phase.STRONGHOLD;
    }

    // ------------------------------------------------------------------
    // Stronghold, end portal, dragon
    // ------------------------------------------------------------------

    private void stronghold() {
        if (husk.getWorld().getEnvironment() != World.Environment.NORMAL) {
            phase = Phase.PORTAL;
            return;
        }
        if (travelTarget == null) {
            Location stronghold = locate(Structure.STRONGHOLD);
            if (stronghold == null) {
                wander();
                return;
            }
            travelTarget = stronghold;
            plugin.getLogger().info("Stronghold found at " + stronghold.getBlockX()
                    + ", " + stronghold.getBlockZ() + ".");
        }
        if (!travelTo(travelTarget)) {
            return;
        }
        travelTarget = null;
        mark("stronghold");
        phase = Phase.END_PORTAL;
    }

    /** Fills the frames of the stronghold's end portal. */
    private void endPortal() {
        World world = husk.getWorld();
        Location here = husk.getLocation();
        Block frame = null;
        double best = Double.MAX_VALUE;
        for (int dx = -48; dx <= 48; dx++) {
            for (int dz = -48; dz <= 48; dz++) {
                for (int dy = -24; dy <= 24; dy++) {
                    Block block = world.getBlockAt(
                            here.getBlockX() + dx, here.getBlockY() + dy, here.getBlockZ() + dz);
                    if (block.getType() != Material.END_PORTAL_FRAME) {
                        continue;
                    }
                    double distance = block.getLocation().distanceSquared(here);
                    if (distance < best) {
                        best = distance;
                        frame = block;
                    }
                }
            }
        }
        if (frame == null) {
            wander();
            return;
        }
        int placed = 0;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    Block block = frame.getRelative(dx, dy, dz);
                    if (!(block.getBlockData() instanceof EndPortalFrame data)) {
                        continue;
                    }
                    if (!data.hasEye() && inventory.count(Material.ENDER_EYE) > 0) {
                        inventory.take(Material.ENDER_EYE, 1);
                        data.setEye(true);
                        block.setBlockData(data, false);
                        placed++;
                    }
                }
            }
        }
        if (placed == 0 && inventory.count(Material.ENDER_EYE) == 0) {
            phase = Phase.PEARL;
            return;
        }
        mark("end portal");
        World end = endWorld();
        if (end == null) {
            plugin.getLogger().severe("No end world is available.");
            phase = Phase.DONE;
            plugin.finishRun();
            return;
        }
        Location arrival = new Location(end, 0.5D, 80.0D, 0.5D);
        arrival.getChunk().load();
        husk.teleport(arrival);
        phase = Phase.DRAGON;
    }

    private void dragon() {
        World world = husk.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) {
            phase = Phase.STRONGHOLD;
            return;
        }
        for (EnderCrystal crystal : world.getEntitiesByClass(EnderCrystal.class)) {
            if (crystal.isDead()) {
                continue;
            }
            if (!moveWithin(crystal.getLocation(), 4.0D)) {
                return;
            }
            crystal.remove();
        }
        for (EnderDragon dragon : world.getEntitiesByClass(EnderDragon.class)) {
            if (dragon.isDead() || dragon.getHealth() <= 0) {
                continue;
            }
            if (!moveWithin(dragon.getLocation(), 3.0D)) {
                return;
            }
            if (attackCooldown > 0) {
                return;
            }
            attackCooldown = 10;
            dragon.damage(8.0D, husk);
            world.playSound(husk.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.6F, 1.0F);
        }
        if (world.getEntitiesByClass(EnderDragon.class).stream()
                .allMatch(entity -> entity.isDead() || entity.getHealth() <= 0)) {
            mark("dragon");
            phase = Phase.DONE;
            plugin.finishRun();
        }
    }

    private World endWorld() {
        World named = Bukkit.getWorld(husk.getWorld().getName() + "_the_end");
        if (named != null) {
            return named;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.THE_END) {
                return world;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Combat
    // ------------------------------------------------------------------

    /** Finds the nearest mob of a type, walks to it and kills it for its drop. */
    private boolean fight(EntityType type, Material drop, int amount) {
        Location here = husk.getLocation();
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity entity : husk.getWorld().getNearbyEntities(here, 32, 16, 32)) {
            if (entity.getType() != type || !(entity instanceof LivingEntity living) || living.isDead()) {
                continue;
            }
            double distance = here.distanceSquared(living.getLocation());
            if (distance < best) {
                best = distance;
                nearest = living;
            }
        }
        if (nearest == null) {
            return false;
        }
        if (!moveWithin(nearest.getLocation(), 2.5D)) {
            return true;
        }
        if (attackCooldown > 0) {
            return true;
        }
        attackCooldown = 10;
        nearest.damage(6.0D, husk);
        if (nearest.isDead() || nearest.getHealth() <= 0) {
            inventory.add(drop, amount);
        }
        return true;
    }

    /** Steps toward a point, returning true once inside {@code distance}. */
    private boolean moveWithin(Location goal, double distance) {
        Location current = husk.getLocation();
        if (current.getWorld() != goal.getWorld()) {
            travelTarget = goal;
            return false;
        }
        if (current.distance(goal) <= distance) {
            return true;
        }
        moveToward(goal);
        return false;
    }

    // ------------------------------------------------------------------
    // Blocks
    // ------------------------------------------------------------------

    private boolean gather(Predicate<Material> match, int up, int down) {
        return gather(match, up, down, searchRadius);
    }

    private boolean gather(Predicate<Material> match, int up, int down, int radius) {
        if (searchCooldown > 0) {
            return true;
        }
        Block found = findNearest(match, up, down, radius);
        searchCooldown = 10;
        if (found == null) {
            return false;
        }
        target = found;
        descending = false;
        rising = false;
        workTicks = 0;
        return true;
    }

    private void workTarget() {
        if (target.getWorld() != husk.getWorld() || !isDiggable(target.getType())) {
            target = null;
            return;
        }
        if (!descending && !rising) {
            Location centre = target.getLocation().add(0.5D, 0.5D, 0.5D);
            if (husk.getLocation().distanceSquared(centre) > REACH * REACH) {
                Location current = husk.getLocation();
                double horizontal = Math.hypot(
                        target.getX() + 0.5D - current.getX(), target.getZ() + 0.5D - current.getZ());
                // Out of reach because of height, not distance: walking closer
                // can never help, so climb up or dig down to it.
                if (horizontal <= 5.0D && target.getY() > current.getBlockY() + 1) {
                    climb();
                    return;
                }
                if (horizontal <= 5.0D && target.getY() < current.getBlockY() - 2) {
                    if (!digDown()) {
                        digForward();
                    }
                    return;
                }
                moveToward(target.getLocation().add(0.5D, 0.0D, 0.5D));
                return;
            }
        }
        if (++workTicks < breakTicks) {
            return;
        }
        workTicks = 0;
        collect(target);
        target.setType(Material.AIR, false);
        if (descending) {
            husk.teleport(husk.getLocation().subtract(0.0D, 1.0D, 0.0D));
        } else if (rising) {
            husk.teleport(husk.getLocation().add(0.0D, 1.0D, 0.0D));
        }
        rising = false;
        target = null;
    }

    private void collect(Block block) {
        for (ItemStack drop : block.getDrops(toolInHand(), husk)) {
            inventory.add(drop.getType(), drop.getAmount());
        }
        // Gravel flint is a chance drop; keep the run moving.
        if (block.getType() == Material.GRAVEL && inventory.count(Material.FLINT) == 0
                && Math.random() < 0.25D) {
            inventory.add(Material.FLINT, 1);
        }
    }

    private ItemStack toolInHand() {
        if (inventory.count(Material.DIAMOND_PICKAXE) > 0) {
            return new ItemStack(Material.DIAMOND_PICKAXE);
        }
        if (inventory.count(Material.IRON_PICKAXE) > 0) {
            return new ItemStack(Material.IRON_PICKAXE);
        }
        if (inventory.count(Material.STONE_PICKAXE) > 0) {
            return new ItemStack(Material.STONE_PICKAXE);
        }
        if (inventory.count(Material.WOODEN_PICKAXE) > 0) {
            return new ItemStack(Material.WOODEN_PICKAXE);
        }
        return new ItemStack(Material.AIR);
    }

    private void equip() {
        Material tool = toolInHand().getType();
        var equipment = husk.getEquipment();
        if (equipment != null) {
            equipment.setItemInMainHand(tool.isAir() ? new ItemStack(Material.AIR) : new ItemStack(tool));
        }
    }

    private Block findNearest(Predicate<Material> match, int up, int down, int radius) {
        World world = husk.getWorld();
        Location base = husk.getLocation();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();
        Block best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = bx - radius; x <= bx + radius; x++) {
            for (int z = bz - radius; z <= bz + radius; z++) {
                for (int y = by - down; y <= by + up; y++) {
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    if (!match.test(block.getType())) {
                        continue;
                    }
                    double dx = x + 0.5D - base.getX();
                    double dy = y + 0.5D - base.getY();
                    double dz = z + 0.5D - base.getZ();
                    double distance = dx * dx + dy * dy + dz * dz + Math.abs(dy) * 6.0D;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = block;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isIronOre(Material material) {
        return material == Material.IRON_ORE || material == Material.DEEPSLATE_IRON_ORE;
    }

    private static boolean isDiamondOre(Material material) {
        return material == Material.DIAMOND_ORE || material == Material.DEEPSLATE_DIAMOND_ORE;
    }

    private static boolean isDiggable(Material material) {
        if (material.isAir() || !material.isSolid()) {
            return false;
        }
        return switch (material) {
            case BEDROCK, OBSIDIAN, CRYING_OBSIDIAN, RESPAWN_ANCHOR, END_PORTAL_FRAME,
                 CHEST, TRAPPED_CHEST, BARREL, FURNACE, BLAST_FURNACE, SMOKER,
                 CRAFTING_TABLE, ENCHANTING_TABLE, ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL -> false;
            default -> true;
        };
    }

    private boolean digDown() {
        Location current = husk.getLocation();
        Block below = current.getWorld().getBlockAt(current.clone().subtract(0.0D, 1.0D, 0.0D));
        if (!isDiggable(below.getType())) {
            return false;
        }
        target = below;
        descending = true;
        rising = false;
        workTicks = 0;
        return true;
    }

    private void digForward() {
        Location current = husk.getLocation();
        int dx = (int) Math.round(Math.cos(wanderAngle));
        int dz = (int) Math.round(Math.sin(wanderAngle));
        Block ahead = current.getWorld().getBlockAt(
                current.getBlockX() + dx, current.getBlockY(), current.getBlockZ() + dz);
        if (!isDiggable(ahead.getType())) {
            wander();
            return;
        }
        target = ahead;
        descending = false;
        rising = false;
        workTicks = 0;
    }

    private void digSide() {
        Location current = husk.getLocation();
        World world = husk.getWorld();
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] offset : offsets) {
            Block block = world.getBlockAt(
                    current.getBlockX() + offset[0], current.getBlockY(), current.getBlockZ() + offset[1]);
            if (isDiggable(block.getType())) {
                target = block;
                descending = false;
                rising = false;
                workTicks = 0;
                return;
            }
        }
        if (!digDown()) {
            wander();
        }
    }

    private void climb() {
        Location current = husk.getLocation();
        int x = current.getBlockX();
        int y = current.getBlockY();
        int z = current.getBlockZ();
        World world = husk.getWorld();
        Block head = world.getBlockAt(x, y + 1, z);
        Block above = world.getBlockAt(x, y + 2, z);
        if (!head.getType().isAir() && isDiggable(head.getType())) {
            target = head;
            rising = true;
            descending = false;
            workTicks = 0;
            return;
        }
        if (!above.getType().isAir() && isDiggable(above.getType())) {
            target = above;
            rising = true;
            descending = false;
            workTicks = 0;
            return;
        }
        Location up = current.clone().add(0.0D, 1.0D, 0.0D);
        if (passable(up)) {
            rising = false;
            husk.teleport(up);
            return;
        }
        wander();
    }

    // ------------------------------------------------------------------
    // Travel
    // ------------------------------------------------------------------

    private Location locate(Structure structure) {
        try {
            StructureSearchResult result = husk.getWorld()
                    .locateNearestStructure(husk.getLocation(), structure, locateRadius, false);
            return result == null ? null : result.getLocation();
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Structure search failed: " + failure.getMessage());
            return null;
        }
    }

    /** Travels toward a distant point in steps, returning true on arrival. */
    private boolean travelTo(Location goal) {
        Location current = husk.getLocation();
        if (current.getWorld() != goal.getWorld()) {
            goal.getChunk().load();
            husk.teleport(goal);
            return true;
        }
        double distance = current.distance(goal);
        if (distance <= 12.0D) {
            return true;
        }
        Vector direction = goal.toVector().subtract(current.toVector()).normalize();
        double step = Math.min(travelStep, distance - 6.0D);
        Location next = current.clone().add(direction.multiply(step));
        next.setY(safeY(next.getWorld(), next.getBlockX(), next.getBlockZ(), goal.getBlockY()));
        next.getChunk().load();
        husk.teleport(next);
        return false;
    }

    /** A standable Y at a column, for both the overworld and the nether. */
    private static double safeY(World world, int x, int z, int preferred) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            int from = Math.min(world.getMaxHeight() - 2, Math.max(preferred + 8, 40));
            for (int y = from; y >= 30; y--) {
                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                Block floor = world.getBlockAt(x, y - 1, z);
                if (!feet.getType().isSolid() && !head.getType().isSolid() && floor.getType().isSolid()) {
                    return y;
                }
            }
            return Math.min(120, Math.max(32, preferred));
        }
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING) + 1;
    }

    private void moveToward(Location goal) {
        if (++moveTicks < stepTicks) {
            return;
        }
        moveTicks = 0;
        Location current = husk.getLocation();
        Vector direction = goal.toVector().subtract(current.toVector());
        direction.setY(0.0D);
        if (direction.lengthSquared() < 0.0001D) {
            return;
        }
        direction.normalize();

        Location next = current.clone().add(direction.multiply(speed));
        int standY = standY(next, current.getBlockY());
        if (standY < 0) {
            return;
        }
        next.setY(standY);
        if (!passable(next)) {
            Block blocker = blockingBlock(next);
            if (blocker != null && isDiggable(blocker.getType())) {
                // Only start over when the obstacle is a new one; resetting the
                // progress counter against the same block would stall forever.
                if (!blocker.equals(target)) {
                    target = blocker;
                    workTicks = 0;
                }
                descending = false;
                rising = false;
            }
            return;
        }
        next.setDirection(direction);
        husk.teleport(next);
    }

    private void wander() {
        Location current = husk.getLocation();
        if (wanderTarget == null || --wanderTicks <= 0 || current.distanceSquared(wanderTarget) < 9.0D) {
            wanderAngle = (wanderAngle + 2.399D) % (Math.PI * 2.0D);
            wanderTicks = 400;
            wanderTarget = current.clone().add(
                    Math.cos(wanderAngle) * 48.0D, 0.0D, Math.sin(wanderAngle) * 48.0D);
        }
        moveToward(wanderTarget);
    }

    private boolean hasGroundBelow() {
        return standY(husk.getLocation(), husk.getLocation().getBlockY()) >= 0;
    }

    private static int standY(Location location, int fromY) {
        World world = location.getWorld();
        int floor = Math.max(world.getMinHeight(), fromY - 8);
        for (int y = fromY; y >= floor; y--) {
            if (world.getBlockAt(location.getBlockX(), y, location.getBlockZ()).getType().isSolid()) {
                return y + 1;
            }
        }
        return -1;
    }

    private static Block blockingBlock(Location location) {
        World world = location.getWorld();
        Block feet = world.getBlockAt(location);
        if (feet.getType().isSolid()) {
            return feet;
        }
        Block head = world.getBlockAt(location.clone().add(0.0D, 1.0D, 0.0D));
        return head.getType().isSolid() ? head : null;
    }

    private static boolean passable(Location location) {
        World world = location.getWorld();
        if (world.getBlockAt(location).getType().isSolid()) {
            return false;
        }
        return !world.getBlockAt(location.clone().add(0.0D, 1.0D, 0.0D)).getType().isSolid();
    }

    // ------------------------------------------------------------------
    // Smelting, placing, bookkeeping
    // ------------------------------------------------------------------

    private boolean startSmelt(int items, Material ore) {
        if (inventory.count(ore) < items) {
            return false;
        }
        Material fuel = inventory.firstTag(Tag.PLANKS);
        int burn = fuel == null ? 0 : Math.min(4, inventory.count(fuel));
        if (burn == 0) {
            fuel = inventory.firstTag(Tag.LOGS);
            burn = fuel == null ? 0 : Math.min(2, inventory.count(fuel));
        }
        if (burn == 0) {
            return false;
        }
        inventory.take(fuel, burn);
        inventory.take(ore, items);
        smeltTicks = 0;
        smeltTotal = items * SMELT_TICKS_PER_ITEM;
        smeltOutput = items;
        return true;
    }

    private boolean smeltFinished() {
        return ++smeltTicks >= smeltTotal;
    }

    private Block placeBeside(Material material) {
        Location current = husk.getLocation();
        World world = husk.getWorld();
        for (int dx = 1; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Block ground = world.getBlockAt(
                        current.getBlockX() + dx, current.getBlockY() - 1, current.getBlockZ() + dz);
                Block spot = ground.getRelative(0, 1, 0);
                if (ground.getType().isSolid() && spot.getType().isAir()) {
                    spot.setType(material, false);
                    return spot;
                }
            }
        }
        return null;
    }

    private void mark(String name) {
        if (run.hasSplit(name)) {
            return;
        }
        double seconds = run.split(name);
        plugin.announceSplit(name, seconds);
    }

    private void keepLoaded() {
        if (++ticketTicks < 40) {
            return;
        }
        ticketTicks = 0;
        World world = husk.getWorld();
        int centreX = husk.getLocation().getBlockX() >> 4;
        int centreZ = husk.getLocation().getBlockZ() >> 4;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = centreX + dx;
                int z = centreZ + dz;
                long key = (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
                if (!ticketed.containsKey(key)) {
                    world.addPluginChunkTicket(x, z, plugin);
                    ticketed.put(key, new int[]{x, z});
                }
            }
        }
        ticketed.entrySet().removeIf(entry -> {
            int[] chunk = entry.getValue();
            if (Math.abs(chunk[0] - centreX) <= 4 && Math.abs(chunk[1] - centreZ) <= 4) {
                return false;
            }
            world.removePluginChunkTicket(chunk[0], chunk[1], plugin);
            return true;
        });
    }
}
