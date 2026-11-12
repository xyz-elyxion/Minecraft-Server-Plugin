package dev.crackedauth;

import dev.crackedauth.auth.AccountStore;
import dev.crackedauth.auth.AuthManager;
import dev.crackedauth.command.AuthAdminCommand;
import dev.crackedauth.command.ChangePasswordCommand;
import dev.crackedauth.command.LoginCommand;
import dev.crackedauth.command.LogoutCommand;
import dev.crackedauth.command.RegisterCommand;
import dev.crackedauth.listener.AuthGateListener;
import dev.crackedauth.util.Messages;
import dev.crackedauth.world.LimboWorld;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class CrackedAuthPlugin extends JavaPlugin {

    private Messages messages;
    private AccountStore accountStore;
    private LimboWorld limboWorld;
    private AuthManager authManager;
    private BukkitTask promptTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.messages = new Messages(this);
        this.accountStore = new AccountStore(this);
        this.accountStore.load();
        this.limboWorld = new LimboWorld(this);
        this.authManager = new AuthManager(this, accountStore, messages, limboWorld);

        if (Bukkit.getOnlineMode()) {
            getLogger().warning("This server runs with online-mode=true, so Mojang already authenticates players.");
            getLogger().warning("CrackedAuth is meant for offline-mode servers; it still works here, but it is redundant.");
        }

        getServer().getPluginManager().registerEvents(new AuthGateListener(this, authManager, messages), this);

        registerCommand("register", new RegisterCommand(authManager, messages));
        registerCommand("login", new LoginCommand(authManager, messages));
        registerCommand("logout", new LogoutCommand(authManager, messages));
        registerCommand("changepassword", new ChangePasswordCommand(authManager, messages));
        registerCommand("authadmin", new AuthAdminCommand(this, authManager, accountStore, messages));

        this.promptTask = Bukkit.getScheduler().runTaskTimer(this, authManager::tick, 40L, 40L);

        getLogger().info("CrackedAuth enabled with " + accountStore.size() + " stored account(s).");
        limboWorld.loadAtStartup();
    }

    @Override
    public void onDisable() {
        if (promptTask != null) {
            promptTask.cancel();
            promptTask = null;
        }
        if (authManager != null) {
            authManager.shutdown();
        }
    }

    /**
     * Re-reads config.yml and applies the security settings and messages.
     */
    public void reloadPlugin() {
        reloadConfig();
        messages.reload();
        limboWorld.reload();
        authManager.reloadSettings();
    }

    private void registerCommand(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command '" + name + "' is missing from plugin.yml - skipping it.");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
