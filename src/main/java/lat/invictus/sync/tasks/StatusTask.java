package lat.invictus.sync.tasks;

import lat.invictus.sync.InvictusSync;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.scheduler.BukkitRunnable;

public class StatusTask extends BukkitRunnable {

    private final InvictusSync plugin;

    public StatusTask(InvictusSync plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        try {
            int online = plugin.getServer().getOnlinePlayers().size();
            int max    = plugin.getServer().getMaxPlayers();
            double tps = Math.min(20.0, Math.round(plugin.getServer().getTPS()[0] * 10.0) / 10.0);
            String json = String.format(
                "{\"online\":%d,\"max\":%d,\"tps\":%.1f}",
                online, max, tps
            );
            plugin.getWorkerClient().post("/mc/status", json);
        } catch (Exception e) {
            plugin.getLogger().warning("[StatusTask] Error: " + e.getMessage());
        }
    }
}
