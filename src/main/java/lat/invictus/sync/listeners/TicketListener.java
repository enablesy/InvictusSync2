package lat.invictus.sync.listeners;

import lat.invictus.sync.InvictusSync;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TicketListener implements Listener {

    private final InvictusSync plugin;
    private final Map<UUID, String> awaitingInput      = new ConcurrentHashMap<>();
    private final Map<UUID, String> awaitingReportedNick = new ConcurrentHashMap<>();
    private final Set<UUID>         openMenus          = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long>   cooldowns          = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 60000;
    private static final String MENU_TITLE = ChatColor.DARK_GRAY + "» " + ChatColor.GOLD + "Abrir Ticket";

    private static final Object[][] CATEGORIES = {
        { Material.REDSTONE,      "Reporte de jugador", "Reporta a un jugador por hacer\nalgo contra las reglas.",       ChatColor.RED,    "reporte"    },
        { Material.WRITABLE_BOOK, "Bug / Error",         "Informa de un fallo o error\nque encontraste en el servidor.", ChatColor.AQUA,   "bug"        },
        { Material.BOOK,          "Queja",               "Queja sobre el comportamiento\ndel servidor o el staff.",      ChatColor.YELLOW, "queja"      },
        { Material.COMPASS,       "Sugerencia",          "Propón una mejora o idea\npara el servidor.",                  ChatColor.GREEN,  "sugerencia" },
        { Material.PAPER,         "Otro",                "Cualquier otra consulta\nque no encaje en las anteriores.",    ChatColor.GRAY,   "otro"       },
    };

    public TicketListener(InvictusSync plugin) { this.plugin = plugin; }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().equalsIgnoreCase("/ticket")) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) { player.sendMessage(plugin.getMsg("ticket-cooldown")); return; }
        openMenu(player);
    }

    private void openMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE);
        ItemStack bg = makeItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, bg);
        int[] slots = {10, 11, 12, 13, 14};
        for (int i = 0; i < CATEGORIES.length; i++) {
            Object[] cat = CATEGORIES[i];
            List<String> lore = new ArrayList<>();
            for (String line : ((String) cat[2]).split("\n")) lore.add(ChatColor.GRAY + line);
            lore.add(""); lore.add(ChatColor.YELLOW + "» Click para seleccionar");
            inv.setItem(slots[i], makeItem((Material) cat[0], (ChatColor) cat[3] + "" + ChatColor.BOLD + (String) cat[1], lore));
        }
        inv.setItem(22, makeItem(Material.BARRIER, ChatColor.RED + "Cancelar"));
        openMenus.add(player.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> player.openInventory(inv));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!openMenus.contains(player.getUniqueId())) return;
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || clicked.getType() == Material.BLACK_STAINED_GLASS_PANE) return;
        if (clicked.getType() == Material.BARRIER) { player.closeInventory(); return; }
        int[] slots = {10, 11, 12, 13, 14};
        for (int i = 0; i < slots.length; i++) {
            if (event.getSlot() != slots[i]) continue;
            String catId = (String) CATEGORIES[i][4];
            player.closeInventory();
            if (catId.equals("reporte")) {
                awaitingReportedNick.put(player.getUniqueId(), "");
                player.sendMessage(ChatColor.GOLD + "⚔ " + ChatColor.GRAY + "Escribe el nick del jugador que quieres reportar:");
                player.sendMessage(ChatColor.DARK_GRAY + "(Escribe " + ChatColor.RED + "cancelar" + ChatColor.DARK_GRAY + " para cancelar)");
            } else {
                awaitingInput.put(player.getUniqueId(), catId);
                player.sendMessage(ChatColor.GOLD + "⚔ " + ChatColor.GRAY + "Describe tu " + ChatColor.YELLOW + (String) CATEGORIES[i][1] + ChatColor.GRAY + " en el chat:");
                player.sendMessage(ChatColor.DARK_GRAY + "(Escribe " + ChatColor.RED + "cancelar" + ChatColor.DARK_GRAY + " para cancelar)");
            }
            return;
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) { openMenus.remove(event.getPlayer().getUniqueId()); }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String msg = event.getMessage().trim();

        if (awaitingReportedNick.containsKey(uuid)) {
            event.setCancelled(true);
            awaitingReportedNick.remove(uuid);
            if (msg.equalsIgnoreCase("cancelar")) { player.sendMessage(ChatColor.GRAY + "Ticket cancelado."); return; }
            awaitingInput.put(uuid, "reporte:" + msg);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.sendMessage(ChatColor.GOLD + "⚔ " + ChatColor.GRAY + "Ahora describe el motivo contra " + ChatColor.YELLOW + msg + ChatColor.GRAY + ":");
                player.sendMessage(ChatColor.DARK_GRAY + "(Escribe " + ChatColor.RED + "cancelar" + ChatColor.DARK_GRAY + " para cancelar)");
            });
            return;
        }

        if (awaitingInput.containsKey(uuid)) {
            event.setCancelled(true);
            String catId = awaitingInput.remove(uuid);
            if (msg.equalsIgnoreCase("cancelar")) { player.sendMessage(ChatColor.GRAY + "Ticket cancelado."); return; }
            cooldowns.put(uuid, System.currentTimeMillis());
            String finalCatId = catId;
            String finalMsg   = msg;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    String catLabel, reason;
                    if (finalCatId.startsWith("reporte:")) {
                        String reported = finalCatId.substring("reporte:".length());
                        catLabel = "Reporte de jugador";
                        reason   = "[TICKET:" + catLabel + "] Jugador reportado: " + reported + " — " + finalMsg;
                    } else {
                        catLabel = getCatLabel(finalCatId);
                        reason   = "[TICKET:" + catLabel + "] " + finalMsg;
                    }
                    String json = String.format(
                        "{\"reporter\":\"%s\",\"reporterUuid\":\"%s\",\"reported\":\"SERVIDOR\",\"reason\":\"%s\"}",
                        WorkerClient.esc(player.getName()), uuid, WorkerClient.esc(reason));
                    plugin.getWorkerClient().post("/mc/report", json);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        player.sendMessage(plugin.getMsg("ticket-sent"));
                        plugin.getServer().getOnlinePlayers().stream()
                            .filter(p -> p.hasPermission("invictussync.link") && !p.equals(player))
                            .forEach(p -> p.sendMessage(ChatColor.BLUE + "" + ChatColor.BOLD + "[TICKET] "
                                + ChatColor.RESET + ChatColor.GRAY + player.getName() + " — "
                                + ChatColor.YELLOW + getCatLabel(finalCatId.startsWith("reporte:") ? "reporte" : finalCatId)
                                + ChatColor.GRAY + ": " + finalMsg));
                    });
                } catch (Exception e) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> player.sendMessage(plugin.getMsg("ticket-error")));
                }
            });
        }
    }

    private String getCatLabel(String catId) {
        for (Object[] cat : CATEGORIES) if (cat[4].equals(catId)) return (String) cat[1];
        return catId;
    }

    private ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta  = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    private ItemStack makeItem(Material mat, String name) { return makeItem(mat, name, null); }
}
