package dev.bedwars.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** One Bedwars team: spawn, bed, colour and its current members. */
public final class Team {

    private final String id;
    private final NamedTextColor color;
    private final Location spawn;
    private final Location bed;
    private final Material bedMaterial;
    private final Material woolMaterial;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Set<UUID> eliminated = new LinkedHashSet<>();
    private boolean bedAlive = true;

    public Team(String id, String colorName, Location spawn, Location bed) {
        this.id = id;
        NamedTextColor named = NamedTextColor.NAMES.value(colorName.toLowerCase(Locale.ROOT));
        this.color = named == null ? NamedTextColor.WHITE : named;
        this.spawn = spawn;
        this.bed = bed;
        this.bedMaterial = materialOr(colorName.toUpperCase(Locale.ROOT) + "_BED", Material.RED_BED);
        this.woolMaterial = materialOr(colorName.toUpperCase(Locale.ROOT) + "_WOOL", Material.WHITE_WOOL);
    }

    private static Material materialOr(String name, Material fallback) {
        Material material = Material.matchMaterial(name);
        return material == null ? fallback : material;
    }

    public String id() {
        return id;
    }

    public NamedTextColor color() {
        return color;
    }

    public Component component() {
        String label = id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
        return Component.text(label, color);
    }

    public Location spawn() {
        return spawn;
    }

    public Location bed() {
        return bed;
    }

    public Material bedMaterial() {
        return bedMaterial;
    }

    public Material woolMaterial() {
        return woolMaterial;
    }

    public Set<UUID> members() {
        return members;
    }

    public Set<UUID> eliminated() {
        return eliminated;
    }

    public boolean bedAlive() {
        return bedAlive;
    }

    public void bedAlive(boolean bedAlive) {
        this.bedAlive = bedAlive;
    }

    public boolean isMember(UUID playerId) {
        return members.contains(playerId);
    }

    public void addMember(UUID playerId) {
        members.add(playerId);
        eliminated.remove(playerId);
    }

    public void removeMember(UUID playerId) {
        members.remove(playerId);
        eliminated.remove(playerId);
    }

    public boolean isEliminated(UUID playerId) {
        return eliminated.contains(playerId);
    }

    public void eliminate(UUID playerId) {
        eliminated.add(playerId);
    }

    /** Members still in the game (uneliminated and online). */
    public int aliveCount() {
        int alive = 0;
        for (UUID member : members) {
            if (!eliminated.contains(member)) {
                alive++;
            }
        }
        return alive;
    }

    public void reset() {
        members.clear();
        eliminated.clear();
        bedAlive = true;
    }
}
