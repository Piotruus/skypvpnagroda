package pl.nagroda;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NagrodaPlugin extends JavaPlugin implements Listener {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    static class Platform {
        String id, name, display, url, mode;
        Material material;
        int slot, autoSeconds;
        List<String> rewards, commands;
    }

    static class GuiHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final Map<String, Platform> platforms = new LinkedHashMap<>();
    private final Set<String> linked = new HashSet<>();
    private final Set<String> claimed = new HashSet<>();
    private final Map<String, Long> pending = new HashMap<>();
    private File dataFile;
    private JDA jda;
    private final Map<String, String> discordOwners = new HashMap<>(); // discordId -> uuid
    private long guildId, channelId;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPlatforms();
        loadData();
        getServer().getPluginManager().registerEvents(this, this);

        // tykanie co sekundę: potwierdza platformy w trybie AUTO
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);

        startBot();
    }

    @Override
    public void onDisable() {
        if (jda != null) jda.shutdownNow();
        saveData();
    }

    // ---------- config / dane ----------

    private void loadPlatforms() {
        platforms.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("platforms");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            Platform p = new Platform();
            p.id = id.toLowerCase();
            p.name = s.getString("name", id);
            p.display = s.getString("display", id);
            p.url = s.getString("url", "");
            p.mode = s.getString("verification", "AUTO").toUpperCase();
            p.slot = s.getInt("slot", 13);
            p.autoSeconds = s.getInt("auto-seconds", 90);
            Material m = Material.matchMaterial(s.getString("material", "PAPER"));
            p.material = m == null ? Material.PAPER : m;
            p.rewards = s.getStringList("rewards");
            p.commands = s.getStringList("commands");
            platforms.put(p.id, p);
        }
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        linked.addAll(y.getStringList("linked"));
        claimed.addAll(y.getStringList("claimed"));
        ConfigurationSection dc = y.getConfigurationSection("discord-owners");
        if (dc != null) for (String k : dc.getKeys(false)) discordOwners.put(k, dc.getString(k));
        ConfigurationSection pend = y.getConfigurationSection("pending");
        if (pend != null) for (String k : pend.getKeys(false)) pending.put(k.replace('|', ':'), pend.getLong(k));
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("linked", new ArrayList<>(linked));
        y.set("claimed", new ArrayList<>(claimed));
        for (Map.Entry<String, String> e : discordOwners.entrySet()) y.set("discord-owners." + e.getKey(), e.getValue());
        for (Map.Entry<String, Long> e : pending.entrySet()) y.set("pending." + e.getKey().replace(':', '|'), e.getValue());
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Nie udało się zapisać data.yml: " + ex.getMessage());
        }
    }

    private String key(UUID u, Platform p) { return u + ":" + p.id; }

    // ---------- kolory / formatowanie ----------

    private String c(String s) {
        Matcher m = HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder r = new StringBuilder("§x");
            for (char ch : m.group(1).toCharArray()) r.append('§').append(ch);
            m.appendReplacement(sb, r.toString());
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    private String msg(String path) { return c(getConfig().getString("messages." + path, path)); }

    private String fmtTime(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append(" godz. ");
        if (m > 0) sb.append(m).append(" min ");
        if (h == 0) sb.append(s).append(" s");
        return sb.toString().trim();
    }

    // ---------- czas gry ----------

    private static Statistic playStat() {
        try { return Statistic.valueOf("PLAY_ONE_MINUTE"); }
        catch (IllegalArgumentException e) { return Statistic.valueOf("PLAY_TIME"); }
    }

    private long playedSeconds(Player p) { return p.getStatistic(playStat()) / 20L; }

    // ---------- GUI ----------

    private void openGui(Player player) {
        GuiHolder holder = new GuiHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, c("&8Nagrody"));
        holder.inv = inv;
        fill(inv, player);
        player.openInventory(inv);
    }

    private void fill(Inventory inv, Player player) {
        inv.clear();
        for (Platform p : platforms.values()) inv.setItem(p.slot, buildItem(p, player));
    }

    private ItemStack buildItem(Platform p, Player player) {
        String k = key(player.getUniqueId(), p);
        boolean isLinked = linked.contains(k);
        boolean isClaimed = claimed.contains(k);

        ItemStack it = new ItemStack(p.material);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(c(p.name));

        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(c("&8» &7Połączono: " + (isLinked ? "&a✔" : "&c✘")));
        lore.add("");
        lore.add(c("&8» &7Nagrody:"));
        for (String r : p.rewards) lore.add(c("&8  - " + r));
        lore.add("");
        if (isClaimed) {
            lore.add(c("&a✔ Nagroda odebrana"));
        } else if (isLinked) {
            lore.add(c("&aKliknij, aby odebrać nagrodę!"));
        } else if (pending.containsKey(k)) {
            long left = Math.max(0, (pending.get(k) - System.currentTimeMillis()) / 1000);
            lore.add(c("&eWeryfikacja... (" + fmtTime(left) + ")"));
        } else {
            lore.add(c("&eKliknij, aby połączyć konto!"));
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private void refreshIfOpen(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder) {
            fill(player.getOpenInventory().getTopInventory(), player);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof GuiHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        Platform clicked = null;
        for (Platform p : platforms.values()) if (p.slot == e.getRawSlot()) clicked = p;
        if (clicked == null) return;
        handleClick(player, clicked);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof GuiHolder) e.setCancelled(true);
    }

    private void handleClick(Player player, Platform p) {
        String k = key(player.getUniqueId(), p);

        if (claimed.contains(k)) {
            player.sendMessage(msg("already-claimed"));
            return;
        }

        if (linked.contains(k)) {
            tryClaim(player, p);
            return;
        }

        if (pending.containsKey(k)) {
            long left = Math.max(0, (pending.get(k) - System.currentTimeMillis()) / 1000);
            player.sendMessage(msg("pending").replace("%time%", fmtTime(left)));
            return;
        }

        // pierwszy klik: wyślij link na czat
        Component link = LegacyComponentSerializer.legacySection()
                .deserialize(msg("link-sent").replace("%url%", p.url))
                .clickEvent(ClickEvent.openUrl(p.url));
        player.sendMessage(link);

        if (p.mode.equals("AUTO")) {
            pending.put(k, System.currentTimeMillis() + p.autoSeconds * 1000L);
            saveData();
        } else {
            player.sendMessage(msg("waiting-api"));
        }
        player.closeInventory();
    }

    private void tryClaim(Player player, Platform p) {
        long need = getConfig().getLong("required-playtime-minutes", 60) * 60L;
        long played = playedSeconds(player);
        if (played < need) {
            String text = msg("need-time").replace("%time%", fmtTime(need - played));
            player.sendTitle(" ", text, 5, 60, 10);
            return;
        }

        claimed.add(key(player.getUniqueId(), p));
        saveData();
        for (String cmd : p.commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
        }
        player.sendTitle(" ", msg("success").replace("%platform%", p.display), 5, 60, 10);
        refreshIfOpen(player);
    }

    // ---------- łączenie kont ----------

    private void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Map.Entry<String, Long>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> en = it.next();
            if (now < en.getValue()) continue;
            String[] parts = en.getKey().split(":");
            it.remove();
            changed = true;
            Platform p = platforms.get(parts[1]);
            if (p != null) setLinked(UUID.fromString(parts[0]), p);
        }
        if (changed) saveData();

        // odśwież otwarte GUI (odliczanie)
        for (Player pl : Bukkit.getOnlinePlayers()) refreshIfOpen(pl);
    }

    private void setLinked(UUID uuid, Platform p) {
        linked.add(key(uuid, p));
        pending.remove(key(uuid, p));
        saveData();
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            online.sendMessage(msg("linked-notify").replace("%platform%", p.display));
            refreshIfOpen(online);
        }
    }

    // ---------- bot Discord (JDA) ----------

    private void startBot() {
        String token = System.getenv("NAGRODA_DISCORD_TOKEN");
        if (token == null || token.isBlank()) token = getConfig().getString("discord.token", "");
        if (token == null || token.isBlank() || token.startsWith("WKLEJ")) {
            getLogger().warning("Brak tokena bota Discord (discord.token w config.yml) - weryfikacja Discorda nie dziala.");
            return;
        }
        try {
            guildId = Long.parseLong(getConfig().getString("discord.guild-id", "0"));
            channelId = Long.parseLong(getConfig().getString("discord.channel-id", "0"));
        } catch (NumberFormatException e) {
            getLogger().warning("Nieprawidlowe guild-id / channel-id w config.yml");
            return;
        }
        final String t = token;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                jda = JDABuilder.createLight(t, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                        .addEventListeners(new DiscordListener())
                        .build();
                jda.awaitReady();
                getLogger().info("Bot Discord polaczony jako " + jda.getSelfUser().getName());
            } catch (Exception e) {
                getLogger().severe("Nie udalo sie uruchomic bota Discord: " + e.getMessage());
            }
        });
    }

    private class DiscordListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent e) {
            if (e.getAuthor().isBot() || !e.isFromGuild()) return;
            if (e.getGuild().getIdLong() != guildId || e.getChannel().getIdLong() != channelId) return;

            String nick = e.getMessage().getContentRaw().trim();
            String discordId = e.getAuthor().getId();
            String mention = e.getAuthor().getAsMention();

            if (!nick.matches("[A-Za-z0-9_]{3,16}")) {
                e.getMessage().reply("❌ " + mention + " wpisz **tylko swój nick z Minecrafta** (3-16 znaków).").queue();
                return;
            }
            Bukkit.getScheduler().runTask(NagrodaPlugin.this, () -> {
                String result = processDiscordLink(nick, discordId);
                String text = switch (result) {
                    case "OK" -> "✅ " + mention + " konto **" + nick + "** zostało połączone! Wejdź na serwer i wpisz **/nagroda**.";
                    case "NOT_FOUND" -> "❌ " + mention + " gracz **" + nick + "** nigdy nie był na serwerze. Wejdź na serwer i spróbuj ponownie.";
                    case "ALREADY_NICK" -> "⚠️ " + mention + " nick **" + nick + "** jest już połączony z Discordem.";
                    case "ALREADY_DISCORD" -> "⚠️ " + mention + " to konto Discord jest już połączone z innym nickiem.";
                    default -> "❌ " + mention + " wystąpił błąd, zgłoś to administracji.";
                };
                e.getMessage().reply(text).queue();
            });
        }
    }

    /** Wywoływane na głównym wątku serwera. */
    private String processDiscordLink(String nick, String discordId) {
        Platform p = platforms.get("discord");
        if (p == null) return "ERROR";
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(nick);
        if (op == null) op = Bukkit.getOfflinePlayer(nick);
        if (!op.hasPlayedBefore()) return "NOT_FOUND";
        UUID u = op.getUniqueId();
        if (linked.contains(key(u, p))) return "ALREADY_NICK";
        if (discordOwners.containsKey(discordId)) return "ALREADY_DISCORD";
        discordOwners.put(discordId, u.toString());
        setLinked(u, p);
        return "OK";
    }

    // ---------- komendy ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            if (!sender.hasPermission("nagroda.admin")) {
                sender.sendMessage(c("&cBrak uprawnień."));
                return true;
            }
            // /nagroda admin link|reset <nick> <platforma>   |   /nagroda admin reload
            if (args.length == 2 && args[1].equalsIgnoreCase("reload")) {
                reloadConfig();
                loadPlatforms();
                sender.sendMessage(c("&aPrzeładowano konfigurację."));
                return true;
            }
            if (args.length == 4) {
                Platform p = platforms.get(args[3].toLowerCase());
                if (p == null) { sender.sendMessage(c("&cNieznana platforma.")); return true; }
                UUID u = Bukkit.getOfflinePlayer(args[2]).getUniqueId();
                if (args[1].equalsIgnoreCase("link")) {
                    setLinked(u, p);
                    sender.sendMessage(c("&aPołączono " + args[2] + " z " + p.display));
                    return true;
                }
                if (args[1].equalsIgnoreCase("reset")) {
                    linked.remove(key(u, p)); claimed.remove(key(u, p)); pending.remove(key(u, p));
                    discordOwners.values().removeIf(v -> v.equals(u.toString()));
                    saveData();
                    sender.sendMessage(c("&aZresetowano " + args[2] + " dla " + p.display));
                    return true;
                }
            }
            sender.sendMessage(c("&e/nagroda admin <link|reset> <nick> <discord|tiktok|youtube> &7| &e/nagroda admin reload"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tylko dla graczy.");
            return true;
        }
        openGui(player);
        return true;
    }
}
