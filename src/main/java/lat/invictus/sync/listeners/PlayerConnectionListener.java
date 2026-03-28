package lat.invictus.sync.listeners;

import lat.invictus.sync.InvictusSync;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

public class PlayerConnectionListener implements Listener {

    private final InvictusSync plugin;

    public PlayerConnectionListener(InvictusSync plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) return;

        String playerName = event.getPlayer().getName();
        String uuid       = event.getPlayer().getUniqueId().toString();
        String ip         = event.getAddress().getHostAddress();
        long   ts         = System.currentTimeMillis();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Registrar IP
                String json = String.format(
                    "{\"nick\":\"%s\",\"uuid\":\"%s\",\"ip\":\"%s\",\"ts\":%d}",
                    WorkerClient.esc(playerName), uuid, WorkerClient.esc(ip), ts);
                plugin.getWorkerClient().post("/mc/player/login", json);

                // Anti evasión
                if (plugin.getConfig().getBoolean("anti-evasion.enabled", true)) {
                    String response = plugin.getWorkerClient().getAndRead(
                        "/mc/player/check-evasion?ip=" + java.net.URLEncoder.encode(ip, "UTF-8") + "&uuid=" + uuid);
                    if (response != null && response.contains("\"evasion\":true")) {
                        String bannedNick = response.replaceAll(".*\"bannedNick\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                        String msg = plugin.getMsg("evasion-staff-notify")
                            .replace("{player}", playerName)
                            .replace("{banned}", bannedNick.equals(response) ? "cuenta conocida" : bannedNick);
                        plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.getServer().getOnlinePlayers().stream()
                                .filter(p -> p.hasPermission("invictussync.link"))
                                .forEach(p -> p.sendMessage(msg))
                        );
                        String reportJson = String.format(
                            "{\"reporter\":\"SISTEMA\",\"reported\":\"%s\",\"reportedUuid\":\"%s\",\"reason\":\"[Auto] Posible evasion de ban. IP coincide con: %s\"}",
                            WorkerClient.esc(playerName), uuid,
                            WorkerClient.esc(bannedNick.equals(response) ? "desconocida" : bannedNick));
                        plugin.getWorkerClient().post("/mc/report", reportJson);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error en PlayerConnectionListener: " + e.getMessage());
            }
        });
    }
}
