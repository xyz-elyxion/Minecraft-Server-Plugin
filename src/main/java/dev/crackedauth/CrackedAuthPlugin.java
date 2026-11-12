package dev.crackedauth;

import dev.bedwars.BedwarsConfig;
import dev.bedwars.ai.AiListener;
import dev.bedwars.ai.AiManager;
import dev.bedwars.command.BedwarsCommand;
import dev.bedwars.game.Arena;
import dev.bedwars.listener.GameListener;
import dev.bedwars.util.BedwarsMessages;
import dev.bedwars.world.ArenaWorld;
import dev.crackedauth.auth.AccountStore;
import dev.crackedauth.auth.AuthManager;
import dev.crackedauth.command.AuthAdminCommand;
import dev.crackedauth.command.ChangePasswordCommand;
import dev.crackedauth.command.LoginCommand;
import dev.crackedauth.command.LogoutCommand;
import dev.crackedauth.command.RegisterCommand;
import dev.crackedauth.listener.AuthGateListener;
import dev.crackedauth.update.UpdateChecker;
import dev.crackedauth.util.Messages;
import dev.crackedauth.world.LimboWorld;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * One plugin, two systems: password logins for offline-mode servers and a
 * self-building Bedwars arena. They share the jar, the data folder and the
 * release, but keep separate config files.
 */
public final class CrackedAuthPlugin extends JavaPlugin {

    private Messages messages;
    private AccountStore accountStore;
    private LimboWorld limboWorld;
    private UpdateChecker updateChecker;
    private AuthManager authManager;

    private BedwarsConfig bedwarsConfig;
    private BedwarsMessages bedwarsMessages;
    private ArenaWorld bedwarsWorld;
    private Arena bedwarsArena;
    private AiManager bedwarsAi;

    private BukkitTask promptTask;
    private BukkitTask bedwarsTask;
    private BukkitTask bedwarsAiTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Auth system.
        this.messages = new Messages(this);
        this.accountStore = new AccountStore(this);
        this.accountStore.load();
        this.limboWorld = new LimboWorld(this);
        this.authManager = new AuthManager(this, accountStore, messages, limboWorld);
        this.updateChecker = new UpdateChecker(this, messages, getFile());
        getServer().getPluginManager().registerEvents(new AuthGateListener(this, authManager, messages), this);

        registerCommand("register", new RegisterCommand(authManager, messages));
        registerCommand("login", new LoginCommand(authManager, messages));
        registerCommand("logout", new LogoutCommand(authManager, messages));
        registerCommand("changepassword", new ChangePasswordCommand(authManager, messages));
        registerCommand("authadmin", new AuthAdminCommand(this, authManager, accountStore, messages, updateChecker));

        // Bedwars.
        this.bedwarsConfig = new BedwarsConfig(this);
        this.bedwarsMessages = new BedwarsMessages(bedwarsConfig);
        this.bedwarsWorld = new ArenaWorld(this, bedwarsConfig);
        this.bedwarsArena = new Arena(this, bedwarsMessages, bedwarsWorld, bedwarsConfig);
        this.bedwarsArena.load();
        this.bedwarsAi = new AiManager(this, bedwarsArena, bedwarsConfig);
        this.bedwarsAi.load();
        this.bedwarsArena.onGameStart(bedwarsAi::spawnForGame);
        getServer().getPluginManager().registerEvents(new GameListener(bedwarsArena, bedwarsMessages), this);
        getServer().getPluginManager().registerEvents(new AiListener(bedwarsAi), this);
        registerCommand("bedwars", new BedwarsCommand(bedwarsArena, bedwarsMessages, bedwarsAi, this::reloadPlugin));

        this.promptTask = Bukkit.getScheduler().runTaskTimer(this, authManager::tick, 40L, 40L);
        this.bedwarsTask = Bukkit.getScheduler().runTaskTimer(this, bedwarsArena::tick, 20L, 20L);
        this.bedwarsAiTask = Bukkit.getScheduler().runTaskTimer(this, bedwarsAi::tick, 10L, 5L);

        if (Bukkit.getOnlineMode()) {
            getLogger().warning("This server runs with online-mode=true, so Mojang already authenticates players.");
            getLogger().warning("CrackedAuth is meant for offline-mode servers; it still works here, but it is redundant.");
        }

        getLogger().info("CrackedAuth enabled with " + accountStore.size() + " stored account(s) and "
                + bedwarsArena.teams().size() + " Bedwars team(s); AI opponents "
                + (bedwarsAi.enabled() ? "on" : "off") + ".");
        limboWorld.loadAtStartup();
        updateChecker.start();
    }

    @Override
    public void onDisable() {
        if (promptTask != null) {
            promptTask.cancel();
            promptTask = null;
        }
        if (bedwarsTask != null) {
            bedwarsTask.cancel();
            bedwarsTask = null;
        }
        if (bedwarsAiTask != null) {
            bedwarsAiTask.cancel();
            bedwarsAiTask = null;
        }
        if (authManager != null) {
            authManager.shutdown();
        }
        if (updateChecker != null) {
            updateChecker.stop();
            updateChecker.applyOnShutdown();
        }
        if (bedwarsAi != null) {
            bedwarsAi.shutdown();
        }
        if (bedwarsArena != null) {
            bedwarsArena.shutdown();
        }
        if (bedwarsWorld != null) {
            bedwarsWorld.unload();
        }
    }

    /**
     * Re-reads config.yml and bedwars.yml, and resets both systems.
     */
    public void reloadPlugin() {
        reloadConfig();
        messages.reload();
        limboWorld.reload();
        authManager.reloadSettings();
        updateChecker.reload();
        updateChecker.start();

        bedwarsConfig.reload();
        bedwarsMessages.reload();
        bedwarsWorld.reload();
        bedwarsArena.load();
        bedwarsAi.load();
        bedwarsArena.onGameStart(bedwarsAi::spawnForGame);
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
