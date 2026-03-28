package lat.invictus.sync.listeners;

import lat.invictus.sync.InvictusSync;
import lat.invictus.sync.http.WorkerClient;

import dev.imshadow.RyzenStaff;
import dev.imshadow.API.RyzenStaffApi;
import dev.imshadow.StaffSystem.StaffSystem;

import me.leoko.advancedban.bukkit.event.PunishmentEvent;
import me.leoko.advancedban.bukkit.event.RevokePunishmentEvent;
import me.leoko.advancedban.utils.Punishment;
import me.leoko.advancedban.utils.PunishmentType;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RyzenStaffListener implements Listener {

    private final InvictusSync plugin;
    private final WorkerClient client;
    private RyzenStaff ryzen;
    private final Map<UUID, Boolean> staffModeState = new HashMap<>();
    private final Map<UUID, Boolean> freezeState    = new HashMap<>();
    private final Map<UUID, Boolean> adminChatState = new HashMap<>();
    private final Map<UUID, Boolean> staffChatState = new HashMap<>();

    // Debug: un tick = 2s (40L). 45 ticks = ~90s
    private int debugTick = 0;

    public RyzenStaffListener(InvictusSync plugin) {
        this.plugin = plugin;
        this.client = plugin.getWorkerClient();
        try {
            ryzen = (RyzenStaff) Bukkit.getPluginManager().getPlugin("RyzenStaff");
            if (ryzen == null) plugin.getLogger().warning("RyzenStaff no encontrado.");
            else               plugin.getLogger().info("RyzenStaff encontrado. Sincronizacion activa.");
        } catch (Exception e) {
            plugin.getLogger().warning("Error al cargar RyzenStaff: " + e.getMessage());
        }
        startPollingTask();
    }

    // ── POLLING (staff mode + admin chat) ────────────────────────────────────

    private void startPollingTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (ryzen == null || !plugin.getConfig().getBoolean("sync.activity", true)) return;
            debugTick++;
            boolean doDebug = (debugTick % 45 == 0);
            StringBuilder debugSummary = doDebug ? new StringBuilder("[InvictusSync] DEBUG estados:") : null;
            try {
                RyzenStaffApi api = new RyzenStaffApi(ryzen);
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();

                    // STAFF MODE
                    boolean inStaffMode;
                    try {
                        inStaffMode = StaffSystem.isInStaffMode(player);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[InvictusSync] Error isInStaffMode(" + player.getName() + "): " + e.getMessage());
                        continue;
                    }
                    boolean wasInStaffMode = staffModeState.getOrDefault(uuid, false);
                    if (inStaffMode && !wasInStaffMode) {
                        staffModeState.put(uuid, true);
                        plugin.getLogger().info("[InvictusSync] " + player.getName() + " entro al modo staff.");
                        client.post("/mc/activity", String.format(
                            "{\"type\":\"staffmode_on\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"Entro al modo staff\"}",
                            WorkerClient.esc(player.getName()), uuid));
                    } else if (!inStaffMode && wasInStaffMode) {
                        staffModeState.put(uuid, false);
                        plugin.getLogger().info("[InvictusSync] " + player.getName() + " salio del modo staff.");
                        client.post("/mc/activity", String.format(
                            "{\"type\":\"staffmode_off\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"Salio del modo staff\"}",
                            WorkerClient.esc(player.getName()), uuid));
                    }

                    // ADMIN CHAT
                    boolean inAdminChat;
                    try {
                        inAdminChat = api.isAdminChatMode(player);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[InvictusSync] Error isAdminChatMode(" + player.getName() + "): " + e.getMessage());
                        continue;
                    }
                    boolean wasInAdminChat = adminChatState.getOrDefault(uuid, false);

                    if (doDebug) {
                        debugSummary.append(" | ").append(player.getName())
                            .append("[sm=").append(inStaffMode ? "1" : "0")
                            .append(",ac=").append(inAdminChat ? "1" : "0")
                            .append("]");
                    }

                    if (inAdminChat && !wasInAdminChat) {
                        adminChatState.put(uuid, true);
                        plugin.getLogger().info("[InvictusSync] " + player.getName() + " activo el admin chat.");
                        client.post("/mc/activity", String.format(
                            "{\"type\":\"adminchat\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"Activo el admin chat\"}",
                            WorkerClient.esc(player.getName()), uuid));
                    } else if (!inAdminChat && wasInAdminChat) {
                        adminChatState.put(uuid, false);
                        plugin.getLogger().info("[InvictusSync] " + player.getName() + " desactivo el admin chat.");
                        client.post("/mc/activity", String.format(
                            "{\"type\":\"adminchat\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"Desactivo el admin chat\"}",
                            WorkerClient.esc(player.getName()), uuid));
                    }
                }
                if (doDebug && debugSummary.length() > 28) plugin.getLogger().info(debugSummary.toString());
            } catch (Exception e) {
                plugin.getLogger().warning("[InvictusSync] Error en polling: " + e.getClass().getName() + ": " + e.getMessage());
                for (StackTraceElement el : e.getStackTrace()) {
                    plugin.getLogger().warning("  at " + el.toString());
                }
            }
        }, 40L, 40L);
    }

    // ── SANCIONES via AdvancedBan ─────────────────────────────────────────────
    // Solo se dispara si AdvancedBan aplico la sancion correctamente

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPunishment(PunishmentEvent event) {
        if (!plugin.getConfig().getBoolean("sync.sanctions", true)) return;
        try {
            Punishment p = event.getPunishment();
            String type = mapType(p.getType());
            if (type == null) return;

            String duration = "";
            try { duration = p.getType().isTemp() ? p.getDuration(false) : ""; } catch (Exception ignored) {}

            plugin.getLogger().info("[InvictusSync] Sancion aplicada: " + type + " a " + p.getName() + " por " + p.getOperator());
            client.post("/mc/sanction", String.format(
                "{\"type\":\"%s\",\"target\":\"%s\",\"staff\":\"%s\",\"reason\":\"%s\",\"duration\":\"%s\"}",
                type,
                WorkerClient.esc(p.getName()),
                WorkerClient.esc(p.getOperator()),
                WorkerClient.esc(p.getReason()),
                WorkerClient.esc(duration)));
        } catch (Exception e) {
            plugin.getLogger().warning("[InvictusSync] Error en PunishmentEvent: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRevoke(RevokePunishmentEvent event) {
        if (!plugin.getConfig().getBoolean("sync.sanctions", true)) return;
        try {
            Punishment p = event.getPunishment();
            String revokeType = mapRevokeType(p.getType());
            if (revokeType == null) return;

            plugin.getLogger().info("[InvictusSync] Sancion revocada: " + revokeType + " a " + p.getName());
            client.post("/mc/sanction", String.format(
                "{\"type\":\"%s\",\"target\":\"%s\",\"staff\":\"%s\",\"reason\":\"Revocado\",\"duration\":\"\"}",
                revokeType,
                WorkerClient.esc(p.getName()),
                WorkerClient.esc(p.getOperator())));
        } catch (Exception e) {
            plugin.getLogger().warning("[InvictusSync] Error en RevokePunishmentEvent: " + e.getMessage());
        }
    }

    private String mapType(PunishmentType type) {
        switch (type) {
            case BAN: case TEMP_BAN: case IP_BAN: case TEMP_IP_BAN: return "ban";
            case MUTE: case TEMP_MUTE:                              return "mute";
            case WARNING: case TEMP_WARNING:                        return "warn";
            case KICK:                                              return "kick";
            default: return null;
        }
    }

    private String mapRevokeType(PunishmentType type) {
        switch (type) {
            case BAN: case TEMP_BAN: case IP_BAN: case TEMP_IP_BAN: return "unban";
            case MUTE: case TEMP_MUTE:                              return "unmute";
            default: return null;
        }
    }

    // ── COMANDOS (freeze, staffchat, reports, jail — fuera de AdvancedBan) ───

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().toLowerCase();
        Player player = event.getPlayer();
        String[] parts = msg.split(" ");
        String cmd = parts[0].replace("/", "");

        switch (cmd) {

            // FREEZE (toggle)
            case "freeze": case "fr":
                if (!plugin.getConfig().getBoolean("sync.activity", true) || parts.length < 2) return;
                String freezeTarget = parts[1];
                Player targetPlayer = Bukkit.getPlayer(freezeTarget);
                UUID targetUuid = targetPlayer != null
                    ? targetPlayer.getUniqueId()
                    : UUID.nameUUIDFromBytes(freezeTarget.getBytes());
                boolean wasFrozen = freezeState.getOrDefault(targetUuid, false);
                if (wasFrozen) {
                    freezeState.put(targetUuid, false);
                    client.post("/mc/activity", String.format(
                        "{\"type\":\"unfreeze\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"target\":\"%s\",\"detail\":\"Descongelo a %s\"}",
                        WorkerClient.esc(player.getName()), player.getUniqueId(),
                        WorkerClient.esc(freezeTarget), WorkerClient.esc(freezeTarget)));
                } else {
                    freezeState.put(targetUuid, true);
                    client.post("/mc/activity", String.format(
                        "{\"type\":\"freeze\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"target\":\"%s\",\"detail\":\"Congelo a %s\"}",
                        WorkerClient.esc(player.getName()), player.getUniqueId(),
                        WorkerClient.esc(freezeTarget), WorkerClient.esc(freezeTarget)));
                }
                break;

            // STAFF CHAT (toggle)
            case "sc": case "staffchat":
                if (!plugin.getConfig().getBoolean("sync.activity", true)) return;
                UUID scUuid = player.getUniqueId();
                boolean wasInStaffChat = staffChatState.getOrDefault(scUuid, false);
                if (wasInStaffChat) {
                    staffChatState.put(scUuid, false);
                    client.post("/mc/activity", String.format(
                        "{\"type\":\"staffchat\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"Desactivo el staff chat\"}",
                        WorkerClient.esc(player.getName()), scUuid));
                } else {
                    staffChatState.put(scUuid, true);
                    client.post("/mc/activity", String.format(
                        "{\"type\":\"staffchat\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"Activo el staff chat\"}",
                        WorkerClient.esc(player.getName()), scUuid));
                }
                break;

            // REPORTS
            case "report":
                if (!plugin.getConfig().getBoolean("sync.reports", true) || parts.length < 3) return;
                Player reportedPlayer = Bukkit.getPlayer(parts[1]);
                String reportedUuid = reportedPlayer != null ? reportedPlayer.getUniqueId().toString() : "";
                client.post("/mc/report", String.format(
                    "{\"reporter\":\"%s\",\"reporterUuid\":\"%s\",\"reported\":\"%s\",\"reportedUuid\":\"%s\",\"reason\":\"%s\"}",
                    WorkerClient.esc(player.getName()), player.getUniqueId(),
                    WorkerClient.esc(parts[1]), reportedUuid,
                    WorkerClient.esc(joinFrom(parts, 2))));
                break;

            // JAIL (RyzenStaff, no cubierto por AdvancedBan)
            case "jail":
                if (!plugin.getConfig().getBoolean("sync.sanctions", true) || parts.length < 2) return;
                client.post("/mc/sanction", String.format(
                    "{\"type\":\"jail\",\"target\":\"%s\",\"staff\":\"%s\",\"reason\":\"%s\",\"duration\":\"\"}",
                    WorkerClient.esc(parts[1]), WorkerClient.esc(player.getName()),
                    WorkerClient.esc(parts.length >= 3 ? joinFrom(parts, 2) : "Sin razon")));
                break;
            case "unjail":
                if (!plugin.getConfig().getBoolean("sync.sanctions", true) || parts.length < 2) return;
                client.post("/mc/sanction", String.format(
                    "{\"type\":\"unjail\",\"target\":\"%s\",\"staff\":\"%s\",\"reason\":\"Liberado de jail\",\"duration\":\"\"}",
                    WorkerClient.esc(parts[1]), WorkerClient.esc(player.getName())));
                break;
        }
    }

    // ── QUIT ──────────────────────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        staffModeState.remove(uuid);
        freezeState.remove(uuid);
        adminChatState.remove(uuid);
        staffChatState.remove(uuid);
    }

    // ── UTILS ──────────────────────────────────────────────────────────────────

    private String joinFrom(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (i > from) sb.append(" ");
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
