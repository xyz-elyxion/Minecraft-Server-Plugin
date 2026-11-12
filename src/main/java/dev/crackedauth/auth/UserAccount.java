package dev.crackedauth.auth;

import java.util.Locale;
import java.util.UUID;

/**
 * A stored account. Names are looked up case-insensitively, which is what
 * offline-mode servers need because the login name is the player name.
 */
public final class UserAccount {

    private final String key;
    private final long registeredAt;
    private String name;
    private UUID uniqueId;
    private String passwordHash;
    private String lastIp;
    private long lastLogin;

    public UserAccount(String name, UUID uniqueId, String passwordHash, long registeredAt) {
        this.key = key(name);
        this.name = name;
        this.uniqueId = uniqueId;
        this.passwordHash = passwordHash;
        this.registeredAt = registeredAt;
    }

    public static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    public String key() {
        return key;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public UUID uniqueId() {
        return uniqueId;
    }

    public void uniqueId(UUID uniqueId) {
        this.uniqueId = uniqueId;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public void passwordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String lastIp() {
        return lastIp;
    }

    public void lastIp(String lastIp) {
        this.lastIp = lastIp;
    }

    public long lastLogin() {
        return lastLogin;
    }

    public void lastLogin(long lastLogin) {
        this.lastLogin = lastLogin;
    }

    public long registeredAt() {
        return registeredAt;
    }
}
