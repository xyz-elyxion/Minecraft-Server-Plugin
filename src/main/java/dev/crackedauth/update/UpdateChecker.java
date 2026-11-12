package dev.crackedauth.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.crackedauth.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the project's GitHub releases for a newer jar and stages it for install.
 *
 * <p>Nothing is swapped while the server is running: a newer jar is downloaded to
 * {@code plugins/CrackedAuth/update/}, then copied over the loaded plugin jar
 * during shutdown so the next start runs the new version. If the copy fails
 * (a locked jar on Windows, for example) the staged file is left in place and
 * the path is logged for a manual swap.</p>
 */
public final class UpdateChecker {

    private static final Pattern ASSET_VERSION = Pattern.compile("CrackedAuth-(\\d+\\.\\d+(?:\\.\\d+)?)\\.jar");
    private static final String USER_AGENT = "CrackedAuth-Updater";
    private static final long START_DELAY_TICKS = 100L;

    private final JavaPlugin plugin;
    private final Messages messages;
    private final File pluginJar;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private boolean enabled;
    private boolean autoDownload;
    private String repository;
    private String apiBase = "https://api.github.com";
    private int intervalHours;
    private BukkitTask task;
    private volatile boolean checking;
    private String notifiedVersion;
    private Path stagedJar;
    private String stagedVersion;

    public UpdateChecker(JavaPlugin plugin, Messages messages, File pluginJar) {
        this.plugin = plugin;
        this.messages = messages;
        this.pluginJar = pluginJar;
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        this.enabled = config.getBoolean("update.enabled", true);
        this.repository = config.getString("update.repository", "");
        this.autoDownload = config.getBoolean("update.auto-download", true);
        this.intervalHours = Math.max(0, config.getInt("update.check-interval-hours", 6));
        this.apiBase = normalizeBase(config.getString("update.api-base", "https://api.github.com"));
    }

    private static String normalizeBase(String raw) {
        String base = raw == null || raw.isBlank() ? "https://api.github.com" : raw.trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public String currentVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** Schedules the startup check and the repeating interval check. */
    public void start() {
        stop();
        adoptStagedFromDisk();
        if (!enabled || repository == null || repository.isBlank()) {
            return;
        }
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> check(null), START_DELAY_TICKS);
        if (intervalHours > 0) {
            long ticks = intervalHours * 60L * 60L * 20L;
            task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> check(null), ticks, ticks);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Manual check, used by {@code /authadmin update}. */
    public void checkAsync(CommandSender feedback) {
        if (!enabled) {
            messages.send(feedback, "update-disabled");
            return;
        }
        if (repository == null || repository.isBlank()) {
            messages.send(feedback, "update-failed", "error", "update.repository is empty");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> check(feedback));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String stagedVersion() {
        return stagedVersion;
    }

    // ------------------------------------------------------------------
    // Checking
    // ------------------------------------------------------------------

    private void check(CommandSender feedback) {
        if (!enabled || checking) {
            return;
        }
        checking = true;
        try {
            Release release = fetchLatestRelease();
            if (release == null) {
                reply(feedback, "update-none");
                return;
            }
            if (Version.compare(release.version(), currentVersion()) <= 0) {
                reply(feedback, "update-none");
                return;
            }

            announce(release);
            if (!autoDownload) {
                return;
            }
            try {
                Path staged = download(release);
                this.stagedJar = staged;
                this.stagedVersion = release.version();
                plugin.getLogger().info("Downloaded CrackedAuth " + release.version()
                        + "; it will be installed when the server stops.");
                reply(feedback, "update-downloaded", "version", release.version(),
                        "file", staged.getFileName().toString());
            } catch (IOException | InterruptedException downloadFailure) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not download CrackedAuth " + release.version(), downloadFailure);
                reply(feedback, "update-download-failed",
                        "version", release.version(), "error", describe(downloadFailure));
            }
        } catch (IOException | InterruptedException failure) {
            plugin.getLogger().log(Level.WARNING, "Update check failed", failure);
            reply(feedback, "update-failed", "error", describe(failure));
        } finally {
            checking = false;
        }
    }

    private void announce(Release release) {
        if (release.version().equals(notifiedVersion)) {
            return;
        }
        notifiedVersion = release.version();
        plugin.getLogger().info("A newer CrackedAuth is available: " + release.version()
                + " (running " + currentVersion() + ").");
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("crackedauth.admin")) {
                    messages.send(player, "update-available",
                            "version", release.version(), "current", currentVersion());
                }
            }
        });
    }

    private Release fetchLatestRelease() throws IOException, InterruptedException {
        String latest = get(apiBase + "/repos/" + repository + "/releases/latest");
        JsonElement latestJson = parse(latest);
        if (latestJson != null && latestJson.isJsonObject()) {
            Release release = toRelease(latestJson.getAsJsonObject());
            if (release != null) {
                return release;
            }
        }

        // Fall back to the release list: /releases/latest ignores pre-releases,
        // and the rolling "latest" build used to be published as one.
        String list = get(apiBase + "/repos/" + repository + "/releases?per_page=10");
        JsonElement parsed = parse(list);
        if (parsed == null || !parsed.isJsonArray()) {
            return null;
        }
        JsonArray releases = parsed.getAsJsonArray();
        for (JsonElement element : releases) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            if (object.has("draft") && object.get("draft").getAsBoolean()) {
                continue;
            }
            Release candidate = toRelease(object);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    static Release toRelease(JsonObject object) {
        if (object == null || !object.has("assets") || !object.get("assets").isJsonArray()) {
            return null;
        }
        String tag = object.has("tag_name") ? object.get("tag_name").getAsString() : "";
        JsonArray assets = object.getAsJsonArray("assets");
        for (JsonElement element : assets) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject asset = element.getAsJsonObject();
            String name = asset.has("name") ? asset.get("name").getAsString() : "";
            String url = asset.has("browser_download_url") ? asset.get("browser_download_url").getAsString() : "";
            if (url.isEmpty() || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            Matcher matcher = ASSET_VERSION.matcher(name);
            String version = matcher.find() ? matcher.group(1) : tag.replaceFirst("^v", "");
            if (Version.parse(version) == null) {
                continue;
            }
            return new Release(version, tag, name, url);
        }
        return null;
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT + "/" + currentVersion())
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return "";
        }
        if (response.statusCode() == 403) {
            throw new IOException("GitHub rejected the request (HTTP 403), usually the anonymous API rate limit for this server's IP; try again later");
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub returned HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    private static JsonElement parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(body);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Staging and installing
    // ------------------------------------------------------------------

    private Path download(Release release) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(release.assetUrl()))
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", USER_AGENT + "/" + currentVersion())
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("download returned HTTP " + response.statusCode());
        }
        byte[] data = response.body();
        if (data.length < 1024 || data[0] != 'P' || data[1] != 'K') {
            throw new IOException("downloaded file is not a jar archive");
        }
        Path directory = new File(plugin.getDataFolder(), "update").toPath();
        Files.createDirectories(directory);
        Path target = directory.resolve(release.assetName());
        Files.write(target, data, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return target;
    }

    /**
     * Picks up a jar staged by an earlier run that never got to install it
     * (crash, kill -9, or a failed copy).
     */
    private void adoptStagedFromDisk() {
        Path directory = new File(plugin.getDataFolder(), "update").toPath();
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .forEach(path -> {
                        Matcher matcher = ASSET_VERSION.matcher(path.getFileName().toString());
                        if (!matcher.find()) {
                            return;
                        }
                        String version = matcher.group(1);
                        if (stagedVersion == null || Version.compare(version, stagedVersion) > 0) {
                            stagedVersion = version;
                            stagedJar = path;
                        }
                    });
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not inspect the update folder", failure);
        }
    }

    /** Called from {@code onDisable()}: swaps in a staged jar for the next start. */
    public void applyOnShutdown() {
        Path staged = this.stagedJar;
        String version = this.stagedVersion;
        if (staged == null || version == null || !Files.isRegularFile(staged)) {
            return;
        }
        if (Version.compare(version, currentVersion()) <= 0) {
            return;
        }
        try {
            Files.copy(staged, pluginJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(staged);
            plugin.getLogger().info("Installed CrackedAuth " + version
                    + " - it will be loaded on the next start.");
        } catch (IOException failure) {
            plugin.getLogger().warning("Could not replace " + pluginJar.getName() + " automatically ("
                    + failure.getMessage() + "). Install " + staged + " manually.");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void reply(CommandSender sender, String key, String... placeholders) {
        if (sender == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> messages.send(sender, key, placeholders));
    }

    private static String describe(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    record Release(String version, String tag, String assetName, String assetUrl) {
    }
}
