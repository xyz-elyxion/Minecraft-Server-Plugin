package dev.crackedauth.auth;

import dev.crackedauth.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Owns every auth decision: which players are logged in, how passwords are
 * hashed and verified, when to lock a player out and when to kick them.
 *
 * <p>Password hashing runs on a worker thread because PBKDF2 is deliberately
 * slow; the result is applied back on the main thread, where all Bukkit calls
 * happen.</p>
 */
public final class AuthManager {

    private final JavaPlugin plugin;
    private final AccountStore store;
    private final Messages messages;
    private final Map<UUID, AuthSession> sessions = new ConcurrentHashMap<>();

    private int minPasswordLength;
    private int maxPasswordLength;
    private int loginTimeoutSeconds;
    private int maxAttempts;
    private int lockoutSeconds;
    private int sessionMinutes;
    private boolean sessionEnabled;
    private boolean sessionRequireSameIp;
    private boolean kickOnDisable;

    public AuthManager(JavaPlugin plugin, AccountStore store, Messages messages) {
        this.plugin = plugin;
        this.store = store;
        this.messages = messages;
        reloadSettings();
    }

    public void reloadSettings() {
        var config = plugin.getConfig();
        this.minPasswordLength = Math.max(1, config.getInt("settings.min-password-length", 8));
        this.maxPasswordLength = Math.max(minPasswordLength, config.getInt("settings.max-password-length", 64));
        this.loginTimeoutSeconds = Math.max(0, config.getInt("settings.login-timeout-seconds", 90));
        this.maxAttempts = Math.max(1, config.getInt("settings.max-attempts", 5));
        this.lockoutSeconds = Math.max(0, config.getInt("settings.lockout-seconds", 300));
        this.sessionEnabled = config.getBoolean("settings.session.enabled", true);
        this.sessionMinutes = Math.max(0, config.getInt("settings.session.minutes", 30));
        this.sessionRequireSameIp = config.getBoolean("settings.session.require-same-ip", true);
        this.kickOnDisable = config.getBoolean("settings.kick-on-disable", true);
    }

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    public void handleJoin(Player player) {
        AuthSession session = new AuthSession(player.getUniqueId(), player.getName(), player.getLocation().clone());
        sessions.put(player.getUniqueId(), session);

        UserAccount account = store.get(player.getName());
        if (account != null && resumeSession(player, session, account)) {
            return;
        }

        if (account == null) {
            messages.send(player, "prompt-register");
            showTitle(player, "prompt-register-title", "prompt-register-subtitle");
        } else {
            messages.send(player, "prompt-login");
            showTitle(player, "prompt-login-title", "prompt-login-subtitle");
        }
        startTimeout(player, session);
    }

    public void handleQuit(Player player) {
        AuthSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.cancelTimeout();
        }
    }

    private boolean resumeSession(Player player, AuthSession session, UserAccount account) {
        if (!sessionEnabled || sessionMinutes <= 0 || account.lastLogin() <= 0L) {
            return false;
        }
        long age = System.currentTimeMillis() - account.lastLogin();
        if (age < 0 || age > TimeUnit.MINUTES.toMillis(sessionMinutes)) {
            return false;
        }
        if (sessionRequireSameIp && !addressOf(player).equals(account.lastIp())) {
            return false;
        }
        authenticate(player, session, account, "session-resumed");
        return true;
    }

    private void startTimeout(Player player, AuthSession session) {
        session.cancelTimeout();
        if (loginTimeoutSeconds <= 0) {
            return;
        }
        session.timeoutTask(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (session.authenticated() || !player.isOnline()) {
                return;
            }
            player.kick(messages.get("timeout-kick", "seconds", String.valueOf(loginTimeoutSeconds)));
        }, loginTimeoutSeconds * 20L));
    }

    // ------------------------------------------------------------------
    // Queries used by listeners and commands
    // ------------------------------------------------------------------

    /**
     * Players without a tracked session are treated as authenticated so that
     * a hot reload can never lock out an online admin mid-command.
     */
    public boolean isAuthenticated(Player player) {
        return isAuthenticated(player.getUniqueId());
    }

    public boolean isAuthenticated(UUID uniqueId) {
        AuthSession session = sessions.get(uniqueId);
        return session == null || session.authenticated();
    }

    public Location holdLocation(Player player) {
        AuthSession session = sessions.get(player.getUniqueId());
        return session == null ? null : session.holdLocation();
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    public void register(Player player, String password, String confirmation) {
        AuthSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.authenticated()) {
            messages.send(player, "already-logged-in");
            return;
        }
        if (store.isRegistered(player.getName())) {
            messages.send(player, "register-already-registered");
            return;
        }
        if (!password.equals(confirmation)) {
            messages.send(player, "register-password-mismatch");
            return;
        }
        if (password.length() < minPasswordLength) {
            messages.send(player, "register-password-too-short", "min", String.valueOf(minPasswordLength));
            return;
        }
        if (password.length() > maxPasswordLength) {
            messages.send(player, "register-password-too-long", "max", String.valueOf(maxPasswordLength));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String hash = PasswordHasher.hash(password);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || session.authenticated()) {
                    return;
                }
                if (store.isRegistered(player.getName())) {
                    messages.send(player, "register-already-registered");
                    return;
                }
                UserAccount account = new UserAccount(player.getName(), player.getUniqueId(), hash, System.currentTimeMillis());
                store.put(account);
                authenticate(player, session, account, "register-success");
            });
        });
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    public void login(Player player, String password) {
        AuthSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.authenticated()) {
            messages.send(player, "already-logged-in");
            return;
        }
        long now = System.currentTimeMillis();
        if (session.lockedUntil() > now) {
            messages.send(player, "login-locked", "seconds", String.valueOf(remainingSeconds(session.lockedUntil() - now)));
            return;
        }
        UserAccount account = store.get(player.getName());
        if (account == null) {
            messages.send(player, "login-not-registered");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean matches = PasswordHasher.verify(password, account.passwordHash());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || session.authenticated()) {
                    return;
                }
                if (matches) {
                    session.failedAttempts(0);
                    authenticate(player, session, account, "login-success");
                } else {
                    handleFailedLogin(player, session);
                }
            });
        });
    }

    private void handleFailedLogin(Player player, AuthSession session) {
        int attempts = session.failedAttempts() + 1;
        session.failedAttempts(attempts);
        if (attempts >= maxAttempts) {
            session.failedAttempts(0);
            session.lockedUntil(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(lockoutSeconds));
            player.kick(messages.get("too-many-attempts", "seconds", String.valueOf(lockoutSeconds)));
            return;
        }
        messages.send(player, "login-wrong-password",
                "attempts", String.valueOf(maxAttempts - attempts),
                "max", String.valueOf(maxAttempts));
    }

    // ------------------------------------------------------------------
    // Password changes and logout
    // ------------------------------------------------------------------

    public void changePassword(Player player, String currentPassword, String newPassword) {
        AuthSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.authenticated()) {
            messages.send(player, "login-required");
            return;
        }
        UserAccount account = store.get(player.getName());
        if (account == null) {
            messages.send(player, "login-not-registered");
            return;
        }
        if (newPassword.length() < minPasswordLength) {
            messages.send(player, "register-password-too-short", "min", String.valueOf(minPasswordLength));
            return;
        }
        if (newPassword.length() > maxPasswordLength) {
            messages.send(player, "register-password-too-long", "max", String.valueOf(maxPasswordLength));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean matches = PasswordHasher.verify(currentPassword, account.passwordHash());
            String newHash = matches ? PasswordHasher.hash(newPassword) : null;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!matches) {
                    messages.send(player, "changepassword-wrong");
                    return;
                }
                account.passwordHash(newHash);
                store.put(account);
                messages.send(player, "changepassword-success");
            });
        });
    }

    public void logout(Player player) {
        AuthSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.authenticated()) {
            messages.send(player, "logout-not-logged-in");
            return;
        }
        session.authenticated(false);
        session.holdLocation(player.getLocation().clone());
        UserAccount account = store.get(player.getName());
        if (account != null) {
            account.lastLogin(0L);
            store.saveAsync();
        }
        messages.send(player, "logout-success");
        showTitle(player, "prompt-login-title", "prompt-login-subtitle");
        startTimeout(player, session);
    }

    // ------------------------------------------------------------------
    // Admin helpers
    // ------------------------------------------------------------------

    /**
     * Marks an online player as authenticated without a password.
     *
     * @return false when the player is already authenticated or has no session
     */
    public boolean forceLogin(Player player) {
        AuthSession session = sessions.get(player.getUniqueId());
        if (session == null || session.authenticated()) {
            return false;
        }
        UserAccount account = store.get(player.getName());
        if (account != null) {
            authenticate(player, session, account, "admin-forcelogin-target");
            return true;
        }
        session.authenticated(true);
        session.cancelTimeout();
        player.sendActionBar(Component.empty());
        messages.send(player, "admin-forcelogin-target");
        return true;
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private void authenticate(Player player, AuthSession session, UserAccount account, String messageKey) {
        session.authenticated(true);
        session.cancelTimeout();
        session.failedAttempts(0);
        player.sendActionBar(Component.empty());
        account.lastLogin(System.currentTimeMillis());
        account.lastIp(addressOf(player));
        store.saveAsync();
        messages.send(player, messageKey);
        showTitle(player, "auth-success-title", "auth-success-subtitle");
    }

    /**
     * Sends the action-bar countdown while a player is still unauthenticated.
     * Called every two seconds by {@link dev.crackedauth.CrackedAuthPlugin}.
     */
    public void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            AuthSession session = sessions.get(player.getUniqueId());
            if (session == null || session.authenticated()) {
                continue;
            }
            boolean registered = store.isRegistered(player.getName());
            int remaining = loginTimeoutSeconds <= 0
                    ? 0
                    : Math.max(0, loginTimeoutSeconds - (int) ((System.currentTimeMillis() - session.joinedAt()) / 1000L));
            player.sendActionBar(messages.plain(
                    registered ? "prompt-login-actionbar" : "prompt-register-actionbar",
                    "seconds", String.valueOf(remaining)));
        }
    }

    private void showTitle(Player player, String titleKey, String subtitleKey) {
        player.showTitle(Title.title(
                messages.plain(titleKey),
                messages.plain(subtitleKey),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(500))));
    }

    public void shutdown() {
        for (AuthSession session : sessions.values()) {
            session.cancelTimeout();
        }
        if (kickOnDisable) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                AuthSession session = sessions.get(player.getUniqueId());
                if (session != null && !session.authenticated()) {
                    player.kick(messages.get("plugin-disabled-kick"));
                }
            }
        }
        sessions.clear();
        store.save();
    }

    private static long remainingSeconds(long milliseconds) {
        return Math.max(0L, (milliseconds + 999L) / 1000L);
    }

    private static String addressOf(Player player) {
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) {
            return "";
        }
        return address.getAddress().getHostAddress();
    }
}
