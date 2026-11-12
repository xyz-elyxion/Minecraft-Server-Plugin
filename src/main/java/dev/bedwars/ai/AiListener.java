package dev.bedwars.ai;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/** Reports bot deaths to the AI manager; player deaths stay in GameListener. */
public final class AiListener implements Listener {

    private final AiManager ai;

    public AiListener(AiManager ai) {
        this.ai = ai;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player || !ai.isBot(event.getEntity())) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        ai.handleDeath(event.getEntity());
    }
}
