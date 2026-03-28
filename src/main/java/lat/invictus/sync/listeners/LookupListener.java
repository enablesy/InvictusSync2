package lat.invictus.sync.listeners;

import lat.invictus.sync.InvictusSync;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LookupListener implements Listener {

    private final InvictusSync plugin;
    private final Map<UUID, String>   openMenus = new ConcurrentHashMap<>();
    private final Map<UUID, MenuData> menuData  = new ConcurrentHashMap<>();
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("dd/MM/yy HH:mm");

    public LookupListener(InvictusSync plugin) { this.plugin = plugin; }

    public static class MenuData {
        public String targetName, targetUuid, firstSeen, lastSeen;
        public List<Map<String, String>> sanctions = new ArrayList<>();
        public List<Map<String, String>> alts      = new ArrayList<>();
    }

    // LOWEST = antes de Essentials, ignoreCancelled=false = aunque esté cancelado
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw   = event.getMessage();
        String lower = raw.toLowerCase().trim();
        if (!lower.startsWith("/lookup")) return;

        // Solo procesar si es exactamente /lookup o /lookup <args>
        if (lower.length() > 7 && raw.charAt(7) != ' ') return;

        Player player = event.getPlayer();
        if (!player.hasPermission("invictussync.link")) return;

        // Cancelar para que Essentials no lo maneje
        event.setCancelled(true);

        String[] parts = raw.trim().split("\\s+", 2);
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            player.sendMessage(plugin.getMsg("lookup-usage"));
            return;
        }

        String targetName = parts[1].trim();
        player.sendMessage(plugin.getMsg("lookup-loading").replace("{player}", targetName));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> fetchAndOpen(player, targetName));
    }

    public void fetchAndOpen(Player viewer, String targetName) {
        try {
            String sanctionsResp = plugin.getWorkerClient().getAndRead(
                "/mc/sanctions?search=" + java.net.URLEncoder.encode(targetName, "UTF-8") + "&page=1");

            String uuidResp = plugin.getWorkerClient().getAndRead(
                "/mc/player/uuid?nick=" + java.net.URLEncoder.encode(targetName, "UTF-8"));

            String targetUuid = null;
            if (uuidResp != null && uuidResp.contains("\"uuid\":\"")) {
                int s = uuidResp.indexOf("\"uuid\":\"") + 8;
                int e = uuidResp.indexOf("\"", s);
                if (e > s) targetUuid = uuidResp.substring(s, e);
            }

            String ipsResp = targetUuid != null
                ? plugin.getWorkerClient().getAndRead("/auth/player/ips?uuid=" + targetUuid)
                : plugin.getWorkerClient().getAndRead("/auth/player/ips?nick=" + java.net.URLEncoder.encode(targetName, "UTF-8"));

            MenuData data    = new MenuData();
            data.targetName  = targetName;
            data.targetUuid  = targetUuid;
            if (sanctionsResp != null) data.sanctions = parseSanctions(sanctionsResp, targetName);
            if (ipsResp != null)       data.alts       = parseAlts(ipsResp);
            if (ipsResp != null)       parseDates(ipsResp, data);

            openFor(viewer, data);
        } catch (Exception e) {
            plugin.getLogger().warning("[Lookup] Error: " + e.getMessage());
            plugin.getServer().getScheduler().runTask(plugin, () ->
                viewer.sendMessage(plugin.getMsg("error-contact")));
        }
    }

    private void openFor(Player viewer, MenuData data) {
        openMenus.put(viewer.getUniqueId(), data.targetName);
        menuData.put(viewer.getUniqueId(), data);
        plugin.getServer().getScheduler().runTask(plugin, () ->
            viewer.openInventory(buildMenu(data)));
    }

    private Inventory buildMenu(MenuData data) {
        String title = ChatColor.DARK_GRAY + "» " + ChatColor.GOLD + data.targetName + ChatColor.DARK_GRAY + " — Lookup";
        Inventory inv = Bukkit.createInventory(null, 54, title);

        ItemStack bg     = glass(Material.BLACK_STAINED_GLASS_PANE);
        ItemStack border = glass(Material.GRAY_STAINED_GLASS_PANE);
        ItemStack sep    = glass(Material.WHITE_STAINED_GLASS_PANE);
        for (int i = 0; i < 54; i++) inv.setItem(i, bg);
        for (int i : new int[]{0,1,2,3,4,5,6,7,8,45,46,47,48,49,50,51,52,53}) inv.setItem(i, border);

        // Cabeza
        ItemStack head = skull(data.targetName);
        ItemMeta hm = head.getItemMeta();
        hm.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + data.targetName);
        hm.setLore(Arrays.asList(
            ChatColor.DARK_GRAY + "UUID: "             + ChatColor.GRAY + nvl(data.targetUuid, "Desconocido"),
            ChatColor.DARK_GRAY + "Primera conexión: " + ChatColor.GRAY + nvl(data.firstSeen, "—"),
            ChatColor.DARK_GRAY + "Última conexión: "  + ChatColor.GRAY + nvl(data.lastSeen, "—")
        ));
        head.setItemMeta(hm);
        inv.setItem(4, head);

        // Header sanciones
        inv.setItem(9, item(
    Material.BOOK,
    ChatColor.RED + "" + ChatColor.BOLD + "Sanciones recientes",
    Collections.singletonList(ChatColor.GRAY + "" + data.sanctions.size() + " registradas")
));

        // Sanciones
        int slot = 10;
        for (Map<String, String> s : data.sanctions) {
            if (slot > 21) break;
            String type = nvl(s.get("type"), "warn");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.DARK_GRAY + "Razón: "    + ChatColor.GRAY   + trunc(nvl(s.get("reason"), "—"), 40));
            lore.add(ChatColor.DARK_GRAY + "Staff: "    + ChatColor.YELLOW + nvl(s.get("staff"), "?"));
            lore.add(ChatColor.DARK_GRAY + "Fecha: "    + ChatColor.GRAY   + nvl(s.get("date"), "?"));
            if (s.get("duration") != null) lore.add(ChatColor.DARK_GRAY + "Duración: " + ChatColor.GRAY + s.get("duration"));
            inv.setItem(slot++, item(sanctionMat(type),
                sanctionColor(type) + typeName(type) + ChatColor.DARK_GRAY + " → " + ChatColor.WHITE + nvl(s.get("target"), "?"),
                lore));
        }
        if (data.sanctions.isEmpty())
            inv.setItem(10, item(Material.LIME_DYE, ChatColor.GREEN + "Sin sanciones",
                Collections.singletonList(ChatColor.GRAY + "Este jugador no tiene historial.")));

        inv.setItem(22, sep);

        // Header alts
        inv.setItem(23, item(
    Material.COMPASS,
    ChatColor.YELLOW + "" + ChatColor.BOLD + "Posibles alts",
    Collections.singletonList(ChatColor.GRAY + "" + data.alts.size() + " detectados")
));

        // Alts
        int altSlot = 24;
        for (Map<String, String> alt : data.alts) {
            if (altSlot > 34) break;
            String nick = nvl(alt.get("nick"), "?");
            ItemStack altHead = skull(nick);
            ItemMeta am = altHead.getItemMeta();
            am.setDisplayName(ChatColor.YELLOW + nick);
            am.setLore(Arrays.asList(
                ChatColor.DARK_GRAY + "IP compartida: " + ChatColor.GRAY + nvl(alt.get("sharedIp"), "?"),
                ChatColor.YELLOW + "» Clic para ver su lookup"
            ));
            altHead.setItemMeta(am);
            inv.setItem(altSlot++, altHead);
        }
        if (data.alts.isEmpty())
            inv.setItem(24, item(Material.LIME_DYE, ChatColor.GREEN + "Sin alts detectados",
                Collections.singletonList(ChatColor.GRAY + "No se encontraron IPs compartidas.")));

        inv.setItem(35, sep);
        inv.setItem(49, item(Material.BARRIER, ChatColor.RED + "Cerrar", null));
        return inv;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player viewer = (Player) event.getWhoClicked();
        if (!openMenus.containsKey(viewer.getUniqueId())) return;
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (clicked.getType() == Material.BARRIER) { viewer.closeInventory(); return; }
        int slot = event.getSlot();
        if (slot >= 24 && slot <= 34 && clicked.getType() == Material.PLAYER_HEAD) {
            ItemMeta meta = clicked.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String altName = ChatColor.stripColor(meta.getDisplayName());
                viewer.closeInventory();
                viewer.sendMessage(plugin.getMsg("lookup-loading").replace("{player}", altName));
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> fetchAndOpen(viewer, altName));
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        openMenus.remove(event.getPlayer().getUniqueId());
        menuData.remove(event.getPlayer().getUniqueId());
    }

    // ── PARSERS ───────────────────────────────────────────────

    private List<Map<String, String>> parseSanctions(String json, String target) {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            int idx = json.indexOf("\"sanctions\":[");
            if (idx == -1) return result;
            int start = json.indexOf("[", idx) + 1;
            for (String obj : splitObjects(cutArray(json, start))) {
                if (result.size() >= 15) break;
                String t = extractStr(obj, "target");
                if (t == null || !t.equalsIgnoreCase(target)) continue;
                Map<String, String> s = new HashMap<>();
                s.put("type",   extractStr(obj, "type"));
                s.put("target", t);
                s.put("staff",  extractStr(obj, "staff"));
                s.put("reason", extractStr(obj, "reason"));
                s.put("duration", extractStr(obj, "duration"));
                String ts = extractStr(obj, "timestamp");
                if (ts != null) try { s.put("date", DATE_FMT.format(new Date(Long.parseLong(ts)))); } catch (Exception ignored) {}
                result.add(s);
            }
        } catch (Exception e) { plugin.getLogger().warning("[Lookup] parseSanctions: " + e.getMessage()); }
        return result;
    }

    private List<Map<String, String>> parseAlts(String json) {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            int idx = json.indexOf("\"alts\":[");
            if (idx == -1) return result;
            int start = json.indexOf("[", idx) + 1;
            for (String obj : splitObjects(cutArray(json, start))) {
                if (result.size() >= 10) break;
                Map<String, String> alt = new HashMap<>();
                alt.put("nick",     extractStr(obj, "nick"));
                alt.put("sharedIp", extractStr(obj, "sharedIp"));
                if (alt.get("nick") != null) result.add(alt);
            }
        } catch (Exception e) { plugin.getLogger().warning("[Lookup] parseAlts: " + e.getMessage()); }
        return result;
    }

    private void parseDates(String json, MenuData data) {
        try {
            int idx = json.indexOf("\"history\":[");
            if (idx == -1) return;
            int start = json.indexOf("[", idx) + 1;
            List<String> objs = splitObjects(cutArray(json, start));
            if (!objs.isEmpty()) {
                String ts = extractStr(objs.get(0), "ts");
                if (ts != null) try { data.lastSeen  = DATE_FMT.format(new Date(Long.parseLong(ts))); } catch (Exception ignored) {}
                ts = extractStr(objs.get(objs.size() - 1), "ts");
                if (ts != null) try { data.firstSeen = DATE_FMT.format(new Date(Long.parseLong(ts))); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private String cutArray(String json, int start) {
        int depth = 1, i = start;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i++);
            if (c == '[') depth++; else if (c == ']') depth--;
        }
        return json.substring(start, Math.max(0, i - 1));
    }

    private List<String> splitObjects(String arr) {
        List<String> list = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') { if (--depth == 0 && start != -1) { list.add(arr.substring(start, i + 1)); start = -1; } }
        }
        return list;
    }

    private String extractStr(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx == -1) return null;
        int vs = idx + search.length();
        while (vs < json.length() && json.charAt(vs) == ' ') vs++;
        if (vs >= json.length()) return null;
        char first = json.charAt(vs);
        if (first == '"') {
            int end = json.indexOf('"', vs + 1);
            return end == -1 ? null : json.substring(vs + 1, end);
        }
        if (first == 'n') return null;
        int end = vs;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(vs, end).trim();
    }

    // ── ITEM HELPERS ──────────────────────────────────────────

    private ItemStack glass(Material mat) {
        ItemStack i = new ItemStack(mat); ItemMeta m = i.getItemMeta();
        if (m != null) { m.setDisplayName(" "); i.setItemMeta(m); } return i;
    }

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack i = new ItemStack(mat); ItemMeta m = i.getItemMeta();
        if (m == null) return i;
        m.setDisplayName(name); if (lore != null) m.setLore(lore); i.setItemMeta(m); return i;
    }

    private ItemStack skull(String name) {
        ItemStack s = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta m = (SkullMeta) s.getItemMeta();
        if (m != null) { m.setOwningPlayer(Bukkit.getOfflinePlayer(name)); s.setItemMeta(m); }
        return s;
    }

    private Material sanctionMat(String t) {
        switch (t.toLowerCase()) {
            case "ban":  return Material.BARRIER;
            case "kick": return Material.LEATHER_BOOTS;
            case "mute": return Material.PAPER;
            case "warn": return Material.YELLOW_DYE;
            case "jail": return Material.IRON_BARS;
            default:     return Material.GRAY_DYE;
        }
    }

private String sanctionColor(String t) {
    switch (t.toLowerCase()) {
        case "ban":  return ChatColor.RED.toString();
        case "kick": return ChatColor.GOLD.toString();
        case "mute": return ChatColor.YELLOW.toString();
        case "warn": return ChatColor.AQUA.toString();
        case "jail": return ChatColor.DARK_GRAY.toString();
        default:     return ChatColor.GRAY.toString();
    }
}

    private String typeName(String t) {
        switch (t.toLowerCase()) {
            case "ban":    return "BAN";
            case "kick":   return "KICK";
            case "mute":   return "MUTE";
            case "warn":   return "WARN";
            case "jail":   return "JAIL";
            case "unban":  return "UNBAN";
            case "unmute": return "UNMUTE";
            case "unjail": return "UNJAIL";
            default:       return t.toUpperCase();
        }
    }

    private String nvl(String s, String def) { return s != null ? s : def; }
    private String trunc(String s, int max)  { return s != null && s.length() > max ? s.substring(0, max) + "..." : s; }
}
