package dev.speedrun.run;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** The timer, the splits and the live boss bar for one run. */
public final class RunState {

    /** Splits the route is expected to produce, used for the boss bar progress. */
    private static final int EXPECTED_SPLITS = 8;

    private final long startedAt = System.currentTimeMillis();
    private final Map<String, Double> splits = new LinkedHashMap<>();
    private final BossBar bar;

    private boolean finished;
    private double totalSeconds;

    public RunState(boolean bossBar) {
        this.bar = bossBar
                ? BossBar.bossBar(Component.text("Speedrun", NamedTextColor.RED), 0.0F,
                        BossBar.Color.RED, BossBar.Overlay.PROGRESS)
                : null;
        update();
    }

    public double elapsed() {
        return finished ? totalSeconds : (System.currentTimeMillis() - startedAt) / 1000.0D;
    }

    public String clock() {
        return format(elapsed());
    }

    public static String format(double seconds) {
        int minutes = (int) (seconds / 60.0D);
        double rest = seconds - minutes * 60.0D;
        return String.format(Locale.ROOT, "%d:%04.1f", minutes, rest);
    }

    /** Records a split and returns the time it landed at. */
    public double split(String name) {
        double seconds = elapsed();
        splits.put(name, seconds);
        return seconds;
    }

    public boolean hasSplit(String name) {
        return splits.containsKey(name);
    }

    public Map<String, Double> splits() {
        return splits;
    }

    public void finish() {
        if (finished) {
            return;
        }
        totalSeconds = (System.currentTimeMillis() - startedAt) / 1000.0D;
        finished = true;
        if (bar != null) {
            bar.name(Component.text("Finished " + clock(), NamedTextColor.GREEN));
            bar.progress(1.0F);
        }
    }

    public boolean finished() {
        return finished;
    }

    public void update() {
        if (bar == null) {
            return;
        }
        bar.name(Component.text("Speedrun " + clock() + "  " + splits.size() + "/" + EXPECTED_SPLITS,
                finished ? NamedTextColor.GREEN : NamedTextColor.RED));
        bar.progress(Math.min(1.0F, splits.size() / (float) EXPECTED_SPLITS));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(bar);
        }
    }

    public void hide() {
        if (bar == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(bar);
        }
    }
}
