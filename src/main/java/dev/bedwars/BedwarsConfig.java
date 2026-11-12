package dev.bedwars;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * Loads {@code plugins/CrackedAuth/bedwars.yml}.
 *
 * <p>Bedwars keeps its own file so its {@code settings:} and {@code messages:}
 * blocks never collide with the auth plugin's config.</p>
 */
public final class BedwarsConfig {

    private static final String RESOURCE = "bedwars.yml";

    private final JavaPlugin plugin;
    private final File file;
    private FileConfiguration configuration;

    public BedwarsConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), RESOURCE);
        reload();
    }

    public void reload() {
        if (!file.exists()) {
            plugin.saveResource(RESOURCE, false);
        }
        this.configuration = YamlConfiguration.loadConfiguration(file);
        try (InputStream defaults = plugin.getResource(RESOURCE)) {
            if (defaults != null) {
                this.configuration.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defaults, StandardCharsets.UTF_8)));
            }
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not read the bundled " + RESOURCE + " defaults", failure);
        }
    }

    public FileConfiguration config() {
        return configuration;
    }
}
