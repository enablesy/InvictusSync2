package lat.invictus.sync.tasks;

import lat.invictus.sync.InvictusSync;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.HashMap;
import java.util.Map;

public class AlertTask extends BukkitRunnable {

    private final InvictusSync plugin;
    private final Map<String, Long> lastAlertTime = new HashMap<>();

    public AlertTask(InvictusSync plugin) { this.plugin = plugin; }

    @Override
    public void run() {
        double tpsThreshold = plugin.getConfig().getDouble("alerts.tps-threshold", 15.0);
        int ramThreshold    = plugin.getConfig().getInt("alerts.ram-threshold", 90);
        long cooldownMs     = plugin.getConfig().getLong("alerts.cooldown-ms", 60000);

        try {
            double[] tps = plugin.getServer().getTPS();
            double current = Math.round(tps[0] * 10.0) / 10.0;
            if (current < tpsThreshold && canAlert("tps", cooldownMs)) {
                String msg = plugin.getMsg("alert-tps").replace("{tps}", String.valueOf(current));
                notifyStaff(msg);
                String json = String.format(
                    "{\"logs\":[{\"level\":\"SEVERE\",\"message\":\"[AlertTask] TPS bajo: %s\",\"logger\":\"InvictusSync\",\"ts\":%d}]}",
                    current, System.currentTimeMillis());
                plugin.getWorkerClient().post("/mc/console/log", json);
            }
        } catch (Exception ignored) {}

        try {
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            long used = mem.getHeapMemoryUsage().getUsed();
            long max  = mem.getHeapMemoryUsage().getMax();
            if (max > 0) {
                int percent = (int) ((used * 100) / max);
                if (percent >= ramThreshold && canAlert("ram", cooldownMs)) {
                    String msg = plugin.getMsg("alert-ram").replace("{percent}", String.valueOf(percent));
                    notifyStaff(msg);
                    String json = String.format(
                        "{\"logs\":[{\"level\":\"SEVERE\",\"message\":\"[AlertTask] RAM alta: %d%%\",\"logger\":\"InvictusSync\",\"ts\":%d}]}",
                        percent, System.currentTimeMillis());
                    plugin.getWorkerClient().post("/mc/console/log", json);
                }
            }
        } catch (Exception ignored) {}
    }

    private boolean canAlert(String type, long cooldownMs) {
        long now  = System.currentTimeMillis();
        Long last = lastAlertTime.get(type);
        if (last == null || now - last >= cooldownMs) { lastAlertTime.put(type, now); return true; }
        return false;
    }

    private void notifyStaff(String message) {
        plugin.getServer().getScheduler().runTask(plugin, () ->
            plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("invictussync.link"))
                .forEach(p -> p.sendMessage(message))
        );
    }
}
