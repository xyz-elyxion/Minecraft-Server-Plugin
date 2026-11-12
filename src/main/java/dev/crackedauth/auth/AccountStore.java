package dev.crackedauth.auth;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Stores accounts in {@code plugins/CrackedAuth/users.yml}.
 *
 * <p>Writes go to a temporary file that is then moved over the real one, so a
 * crash mid-save cannot leave a half-written account database. The map itself
 * is only ever mutated on the main thread; saving runs off-thread.</p>
 */
public final class AccountStore {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, UserAccount> accounts = new ConcurrentHashMap<>();
    private final AtomicBoolean saveQueued = new AtomicBoolean();

    public AccountStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "users.yml");
    }

    public void load() {
        accounts.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection users = yaml.getConfigurationSection("users");
        if (users == null) {
            return;
        }
        for (String key : users.getKeys(false)) {
            ConfigurationSection section = users.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            String hash = section.getString("password-hash");
            if (hash == null || hash.isBlank()) {
                plugin.getLogger().warning("Skipping account '" + key + "' because it has no password hash.");
                continue;
            }
            String rawUniqueId = section.getString("uuid");
            UUID uniqueId = null;
            if (rawUniqueId != null) {
                try {
                    uniqueId = UUID.fromString(rawUniqueId);
                } catch (IllegalArgumentException malformed) {
                    plugin.getLogger().warning("Account '" + key + "' has an invalid uuid; ignoring it.");
                }
            }
            UserAccount account = new UserAccount(
                    section.getString("name", key),
                    uniqueId,
                    hash,
                    section.getLong("registered-at", 0L));
            account.lastIp(section.getString("last-ip"));
            account.lastLogin(section.getLong("last-logged-in", 0L));
            accounts.put(UserAccount.key(key), account);
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (UserAccount account : accounts.values()) {
            String path = "users." + account.key() + ".";
            yaml.set(path + "name", account.name());
            yaml.set(path + "uuid", account.uniqueId() == null ? null : account.uniqueId().toString());
            yaml.set(path + "password-hash", account.passwordHash());
            yaml.set(path + "last-ip", account.lastIp());
            yaml.set(path + "last-logged-in", account.lastLogin());
            yaml.set(path + "registered-at", account.registeredAt());
        }
        try {
            File folder = plugin.getDataFolder();
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException("Could not create " + folder);
            }
            Path target = file.toPath();
            Path temporary = file.toPath().resolveSibling("users.yml.tmp");
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save users.yml", exception);
        }
    }

    public void saveAsync() {
        if (!plugin.isEnabled()) {
            save();
            return;
        }
        if (!saveQueued.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                save();
            } finally {
                saveQueued.set(false);
            }
        });
    }

    public UserAccount get(String name) {
        return accounts.get(UserAccount.key(name));
    }

    public boolean isRegistered(String name) {
        return get(name) != null;
    }

    public void put(UserAccount account) {
        accounts.put(account.key(), account);
        saveAsync();
    }

    public UserAccount remove(String name) {
        UserAccount removed = accounts.remove(UserAccount.key(name));
        if (removed != null) {
            saveAsync();
        }
        return removed;
    }

    public Collection<UserAccount> all() {
        return accounts.values();
    }

    public int size() {
        return accounts.size();
    }
}
