package dev.crackedauth.auth;

import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * Login state for one online player. Sessions live only in memory; durability
 * comes from {@link AccountStore#save()}.
 */
public final class AuthSession {

    private final UUID uniqueId;
    private final String name;
    private final long joinedAt = System.currentTimeMillis();
    private Location holdLocation;
    private Location returnLocation;
    private boolean inLimbo;
    private boolean authenticated;
    private int failedAttempts;
    private long lockedUntil;
    private BukkitTask timeoutTask;

    public AuthSession(UUID uniqueId, String name, Location holdLocation) {
        this.uniqueId = uniqueId;
        this.name = name;
        this.holdLocation = holdLocation;
    }

    public UUID uniqueId() {
        return uniqueId;
    }

    public String name() {
        return name;
    }

    public long joinedAt() {
        return joinedAt;
    }

    /** Where the player is pinned while unauthenticated. */
    public Location holdLocation() {
        return holdLocation;
    }

    public void holdLocation(Location holdLocation) {
        this.holdLocation = holdLocation;
    }

    /** Where the player goes back to once they authenticate. */
    public Location returnLocation() {
        return returnLocation;
    }

    public void returnLocation(Location returnLocation) {
        this.returnLocation = returnLocation;
    }

    public boolean inLimbo() {
        return inLimbo;
    }

    public void inLimbo(boolean inLimbo) {
        this.inLimbo = inLimbo;
    }

    public boolean authenticated() {
        return authenticated;
    }

    public void authenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public int failedAttempts() {
        return failedAttempts;
    }

    public void failedAttempts(int failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public long lockedUntil() {
        return lockedUntil;
    }

    public void lockedUntil(long lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public void timeoutTask(BukkitTask timeoutTask) {
        this.timeoutTask = timeoutTask;
    }

    public void cancelTimeout() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
    }
}
