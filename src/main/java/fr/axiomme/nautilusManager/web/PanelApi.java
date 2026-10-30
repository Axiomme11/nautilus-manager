package fr.axiomme.nautilusManager.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Logique Bukkit de l'API. Toutes les méthodes publiques DOIVENT être appelées sur le thread principal
 * (PanelServer s'en charge via sync()).
 */
public class PanelApi {

    public static class ApiException extends RuntimeException {
        public final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /**
     * MiniMessage restreint : couleurs, styles, dégradés, arc-en-ciel, reset et retour à la ligne.
     * Les balises interactives (click, hover, insertion, keybind, font...) sont volontairement exclues.
     */
    private static final MiniMessage MM = MiniMessage.builder()
            .tags(TagResolver.resolver(
                    StandardTags.color(),
                    StandardTags.decorations(),
                    StandardTags.gradient(),
                    StandardTags.rainbow(),
                    StandardTags.reset(),
                    StandardTags.newline()))
            .build();

    private static Component format(String text) {
        if (text.length() > 1000) throw new ApiException(400, "Message trop long (1000 caractères maximum)");
        return MM.deserialize(text);
    }

    // ---------- Serveur ----------

    public Map<String, Object> server() {
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", Bukkit.getMinecraftVersion());
        m.put("online", Bukkit.getOnlinePlayers().size());
        m.put("max", Bukkit.getMaxPlayers());
        m.put("tps", Math.min(20.0, round(Bukkit.getTPS()[0])));
        m.put("memUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1_048_576);
        m.put("memMaxMb", rt.maxMemory() / 1_048_576);
        return m;
    }

    public Map<String, Object> broadcast(JsonObject b) {
        Bukkit.broadcast(format(str(b, "message")));
        return Map.of("ok", true, "recipients", Bukkit.getOnlinePlayers().size());
    }

    public Map<String, Object> console(JsonObject b) {
        String command = str(b, "command").trim();
        if (command.startsWith("/")) command = command.substring(1);
        boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        return Map.of("ok", ok);
    }

    // ---------- Joueurs ----------

    public List<Map<String, Object>> players() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) list.add(summary(p));
        return list;
    }

    public Map<String, Object> player(String uuid) {
        return summary(find(uuid));
    }

    private Map<String, Object> summary(Player p) {
        Location l = p.getLocation();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid", p.getUniqueId().toString());
        m.put("name", p.getName());
        m.put("op", p.isOp());
        m.put("gamemode", p.getGameMode().name());
        m.put("health", round(p.getHealth()));
        m.put("maxHealth", round(maxHealth(p)));
        m.put("food", p.getFoodLevel());
        m.put("level", p.getLevel());
        m.put("ping", p.getPing());
        m.put("world", l.getWorld().getName());
        m.put("x", round(l.getX()));
        m.put("y", round(l.getY()));
        m.put("z", round(l.getZ()));
        return m;
    }

    public Map<String, Object> action(String uuid, JsonObject b) {
        Player p = find(uuid);
        String action = str(b, "action");

        switch (action) {
            case "kick" -> p.kick(Component.text(opt(b, "reason", "Expulsé par un administrateur")));
            case "ban" -> {
                String reason = opt(b, "reason", "Banni par un administrateur").replaceAll("[\\r\\n]+", " ");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + p.getName() + " " + reason);
            }
            case "op" -> p.setOp(true);
            case "deop" -> p.setOp(false);
            case "gamemode" -> p.setGameMode(parseEnum(GameMode.class, str(b, "value")));
            case "heal" -> {
                p.setHealth(maxHealth(p));
                p.setFireTicks(0);
            }
            case "feed" -> {
                p.setFoodLevel(20);
                p.setSaturation(20f);
            }
            case "kill" -> p.setHealth(0);
            case "message" -> p.sendMessage(format(str(b, "value")));
            case "teleport" -> {
                String worldName = opt(b, "world", "");
                World w = worldName.isBlank() ? p.getWorld() : Bukkit.getWorld(worldName);
                if (w == null) throw new ApiException(404, "Monde introuvable : " + worldName);
                Location cur = p.getLocation();
                p.teleportAsync(new Location(w, num(b, "x"), num(b, "y"), num(b, "z"), cur.getYaw(), cur.getPitch()));
            }
            case "teleport-to" -> p.teleportAsync(find(str(b, "target")).getLocation());
            default -> throw new ApiException(400, "Action inconnue : " + action);
        }
        return Map.of("ok", true);
    }

    // ---------- Inventaire ----------
    // Slots : 0-8 barre rapide, 9-35 sac, 36 bottes, 37 jambières, 38 plastron, 39 casque, 40 main secondaire.

    public Map<String, Object> inventory(String uuid) {
        Player p = find(uuid);
        PlayerInventory inv = p.getInventory();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int slot = 0; slot <= 40; slot++) {
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType().isAir()) continue;
            items.add(item(slot, it));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("heldSlot", inv.getHeldItemSlot());
        return m;
    }

    public Map<String, Object> inventoryOp(String uuid, JsonObject b) {
        Player p = find(uuid);
        PlayerInventory inv = p.getInventory();
        String op = str(b, "op");
        int notAdded = 0;

        switch (op) {
            case "remove" -> {
                int slot = (int) num(b, "slot");
                if (slot < 0 || slot > 40) throw new ApiException(400, "Slot invalide");
                inv.setItem(slot, null);
            }
            case "clear" -> inv.clear();
            case "give" -> {
                Material mat = Material.matchMaterial(str(b, "material"));
                if (mat == null || mat.isAir() || !mat.isItem()) throw new ApiException(400, "Item inconnu");
                int amount = (int) Math.max(1, Math.min(2304, numOr(b, "amount", 1)));
                for (ItemStack left : inv.addItem(new ItemStack(mat, amount)).values()) {
                    notAdded += left.getAmount();
                }
            }
            default -> throw new ApiException(400, "Opération inconnue : " + op);
        }
        return Map.of("ok", true, "notAdded", notAdded);
    }

    private Map<String, Object> item(int slot, ItemStack it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slot", slot);
        m.put("type", it.getType().name());
        m.put("amount", it.getAmount());
        ItemMeta meta = it.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            m.put("name", PlainTextComponentSerializer.plainText().serialize(meta.displayName()));
        }
        if (!it.getEnchantments().isEmpty()) m.put("enchanted", true);
        return m;
    }

    // ---------- Helpers ----------

    private Player find(String uuid) {
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "UUID invalide");
        }
        Player p = Bukkit.getPlayer(id);
        if (p == null) throw new ApiException(404, "Joueur hors ligne");
        return p;
    }

    private static double maxHealth(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        return a != null ? a.getValue() : 20.0;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String str(JsonObject b, String key) {
        JsonElement e = b.get(key);
        if (e == null || e.isJsonNull()) throw new ApiException(400, "Champ manquant : " + key);
        return e.getAsString();
    }

    private static String opt(JsonObject b, String key, String def) {
        JsonElement e = b.get(key);
        if (e == null || e.isJsonNull() || e.getAsString().isBlank()) return def;
        return e.getAsString();
    }

    private static double num(JsonObject b, String key) {
        try {
            return Double.parseDouble(str(b, key));
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Nombre invalide : " + key);
        }
    }

    private static double numOr(JsonObject b, String key, double def) {
        return b.has(key) && !b.get(key).isJsonNull() ? num(b, key) : def;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Valeur invalide : " + value);
        }
    }
}
