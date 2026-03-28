package lat.invictus.sync;

import lat.invictus.sync.filter.WordFilter;
import lat.invictus.sync.listeners.LookupListener;
import lat.invictus.sync.listeners.PlayerConnectionListener;
import lat.invictus.sync.listeners.RyzenStaffListener;
import lat.invictus.sync.listeners.SpamListener;
import lat.invictus.sync.listeners.TicketListener;
import lat.invictus.sync.tasks.AlertTask;
import lat.invictus.sync.tasks.StatusTask;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public class InvictusSync extends JavaPlugin {

    private static InvictusSync instance;
    private WorkerClient workerClient;
    private StatusTask   statusTask;
    private AlertTask    alertTask;
    private WordFilter   wordFilter;
    private Handler      consoleHandler;
    private final CopyOnWriteArrayList<LogRecord> pendingLogs = new CopyOnWriteArrayList<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        workerClient = new WorkerClient(this);
        wordFilter   = new WordFilter(this);

        // Listeners
        getServer().getPluginManager().registerEvents(new RyzenStaffListener(this), this);
        getServer().getPluginManager().registerEvents(new SpamListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new TicketListener(this), this);
        getServer().getPluginManager().registerEvents(new LookupListener(this), this);

        // Status task
        if (getConfig().getBoolean("sync.status", true)) {
            int interval = getConfig().getInt("status-interval", 30) * 20;
            statusTask = new StatusTask(this);
            statusTask.runTaskTimerAsynchronously(this, 100L, interval);
        }

        // Alert task
        int alertInterval = getConfig().getInt("alerts.check-interval", 100);
        alertTask = new AlertTask(this);
        alertTask.runTaskTimerAsynchronously(this, 200L, alertInterval);

        // Sync plugins al arrancar
        if (getConfig().getBoolean("sync.plugins", true))
            getServer().getScheduler().runTaskLaterAsynchronously(this, this::syncPlugins, 100L);

        // Console handler
        setupConsoleHandler();
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::flushLogs, 600L, 600L);

        // Sync word filter del portal
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::syncWordFilter, 200L);

        getLogger().info("InvictusSync habilitado. Conectado a: " + getConfig().getString("worker-url"));
    }

    @Override
    public void onDisable() {
        if (statusTask != null)    statusTask.cancel();
        if (alertTask != null)     alertTask.cancel();
        if (workerClient != null)  workerClient.shutdown();
        if (consoleHandler != null) Bukkit.getLogger().removeHandler(consoleHandler);
        getLogger().info("InvictusSync deshabilitado.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // /invictussync reload
        if (command.getName().equalsIgnoreCase("invictussync")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("invictussync.admin")) { sender.sendMessage(getMsg("no-permission")); return true; }
                reloadConfig();
                workerClient = new WorkerClient(this);
                wordFilter.reload();
                getServer().getScheduler().runTaskAsynchronously(this, this::syncWordFilter);
                sender.sendMessage(getMsg("reload-done"));
                return true;
            }
            sender.sendMessage("§eUso: /invictussync reload");
            return true;
        }

        // /syncplugins
        if (command.getName().equalsIgnoreCase("syncplugins")) {
            if (!sender.hasPermission("invictussync.admin")) { sender.sendMessage(getMsg("no-permission")); return true; }
            sender.sendMessage(getMsg("syncplugins-start"));
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                syncPlugins();
                sender.sendMessage(getMsg("syncplugins-done"));
            });
            return true;
        }

        // /link
        if (command.getName().equalsIgnoreCase("link")) {
            if (!(sender instanceof Player)) { sender.sendMessage(getMsg("player-only")); return true; }
            Player player = (Player) sender;
            if (!player.hasPermission("invictussync.link")) { player.sendMessage(getMsg("no-permission")); return true; }
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    String check = workerClient.getAndRead("/mc/link/check?uuid=" + player.getUniqueId());
                    if (check != null && check.contains("\"linked\":true")) {
                        String discord = check.replaceAll(".*\"discordUsername\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                        player.sendMessage(getMsg("link-already-linked").replace("{discord}", discord));
                        player.sendMessage(getMsg("link-already-linked-hint"));
                        return;
                    }
                    String json = String.format("{\"nick\":\"%s\",\"uuid\":\"%s\"}",
                        WorkerClient.esc(player.getName()), player.getUniqueId());
                    String resp = workerClient.postAndRead("/mc/link/generate", json);
                    if (resp != null && resp.contains("\"code\"")) {
                        String code = resp.replaceAll(".*\"code\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                        player.sendMessage(getMsg("link-header"));
                        player.sendMessage(getMsg("link-code-label"));
                        player.sendMessage("§e§l  " + code);
                        player.sendMessage(getMsg("link-code-hint"));
                    } else { player.sendMessage(getMsg("link-error")); }
                } catch (Exception e) { player.sendMessage(getMsg("error-contact")); getLogger().warning("Error /link: " + e.getMessage()); }
            });
            return true;
        }

        // /postular
        if (command.getName().equalsIgnoreCase("postular")) {
            if (!(sender instanceof Player)) { sender.sendMessage(getMsg("player-only")); return true; }
            Player player = (Player) sender;
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    String json = String.format("{\"nick\":\"%s\",\"uuid\":\"%s\"}",
                        WorkerClient.esc(player.getName()), player.getUniqueId());
                    String resp = workerClient.postAndRead("/mc/apply/generate", json);
                    if (resp != null && resp.contains("\"code\"")) {
                        String code = resp.replaceAll(".*\"code\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                        player.sendMessage(getMsg("postular-header"));
                        player.sendMessage(getMsg("postular-url"));
                        player.sendMessage("§e§l  " + code);
                        player.sendMessage(getMsg("postular-hint"));
                    } else { player.sendMessage(getMsg("postular-error")); }
                } catch (Exception e) { player.sendMessage(getMsg("error-contact")); getLogger().warning("Error /postular: " + e.getMessage()); }
            });
            return true;
        }

        // /staff
        if (command.getName().equalsIgnoreCase("staff")) {
            List<String> online = new ArrayList<>();
            for (Player p : getServer().getOnlinePlayers()) {
                if (!p.hasPermission("invictussync.link")) continue;
                if (p.hasMetadata("vanished") && p.getMetadata("vanished").stream().anyMatch(m -> m.asBoolean())) continue;
                online.add(p.getName());
            }
            if (online.isEmpty()) {
                sender.sendMessage(getMsg("staff-empty"));
            } else {
                sender.sendMessage(getMsg("staff-header").replace("{count}", String.valueOf(online.size())));
                for (String name : online) sender.sendMessage(getMsg("staff-entry").replace("{name}", name));
            }
            return true;
        }

        return false;
    }

    // ── MÉTODOS INTERNOS ─────────────────────────────────────

    private void syncPlugins() {
        try {
            Plugin[] plugins = getServer().getPluginManager().getPlugins();
            StringBuilder sb = new StringBuilder("{\"plugins\":[");
            for (int i = 0; i < plugins.length; i++) {
                Plugin p = plugins[i];
                if (i > 0) sb.append(",");
                sb.append("{\"name\":\"").append(WorkerClient.esc(p.getName()))
                  .append("\",\"version\":\"").append(WorkerClient.esc(p.getDescription().getVersion()))
                  .append("\",\"enabled\":").append(p.isEnabled()).append("}");
            }
            sb.append("]}");
            workerClient.post("/mc/plugins/sync", sb.toString());
        } catch (Exception e) { getLogger().warning("Error syncPlugins: " + e.getMessage()); }
    }

    private void syncWordFilter() {
        try {
            String resp = workerClient.getAndRead("/auth/word-filter");
            if (resp != null && resp.contains("\"words\"")) {
                int s = resp.indexOf("\"words\":[") + 9;
                int e = resp.indexOf("]", s);
                if (e > s) {
                    for (String w : resp.substring(s, e).split(",")) {
                        String word = w.trim().replace("\"", "");
                        if (!word.isEmpty()) wordFilter.addWord(word);
                    }
                }
            }
        } catch (Exception e) { getLogger().warning("Error syncWordFilter: " + e.getMessage()); }
    }

    private void setupConsoleHandler() {
        consoleHandler = new Handler() {
            @Override public void publish(LogRecord r) {
                String name = r.getLoggerName() != null ? r.getLoggerName() : "";
                if (r.getLevel().intValue() >= Level.WARNING.intValue() || name.contains("InvictusSync"))
                    pendingLogs.add(r);
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Bukkit.getLogger().addHandler(consoleHandler);
    }

    private void flushLogs() {
        if (pendingLogs.isEmpty()) return;
        List<LogRecord> toSend = new ArrayList<>(pendingLogs);
        pendingLogs.clear();
        StringBuilder sb = new StringBuilder("{\"logs\":[");
        for (int i = 0; i < toSend.size(); i++) {
            LogRecord r = toSend.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"level\":\"").append(r.getLevel().getName())
              .append("\",\"message\":\"").append(WorkerClient.esc(r.getMessage()))
              .append("\",\"logger\":\"").append(WorkerClient.esc(r.getLoggerName() != null ? r.getLoggerName() : "Server"))
              .append("\",\"ts\":").append(r.getMillis()).append("}");
        }
        sb.append("]}");
        workerClient.post("/mc/console/log", sb.toString());
    }

    public String getMsg(String key) {
        String prefix = getConfig().getString("messages.prefix", "§6§l⚔ INVICTUS §r");
        String msg    = getConfig().getString("messages." + key, "§cMensaje no configurado: " + key);
        return msg.replace("{prefix}", prefix).replace("&", "§");
    }

    public static InvictusSync getInstance()   { return instance; }
    public WorkerClient getWorkerClient()      { return workerClient; }
    public WordFilter   getWordFilter()        { return wordFilter; }
}
