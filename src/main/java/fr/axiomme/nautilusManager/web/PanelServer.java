package fr.axiomme.nautilusManager.web;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.axiomme.nautilusManager.NautilusManager;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Serveur HTTP embarqué (JDK, aucune dépendance) : sert la page et expose l'API JSON.
 * Les handlers tournent hors du thread principal : tout accès à l'API Bukkit passe par sync().
 */
public class PanelServer {

    private static final Gson GSON = new Gson();
    private static final String CSP = "default-src 'self'; img-src 'self' https://mc-heads.net data:; "
            + "style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none'";
    private static final int MAX_BODY = 64 * 1024;
    private static final int MAX_FAILS = 10;
    private static final long WINDOW_MS = 10 * 60_000L;

    private final NautilusManager plugin;
    private final PanelApi api = new PanelApi();
    private final Map<String, long[]> failures = new ConcurrentHashMap<>(); // ip -> {nb échecs, début fenêtre}

    private HttpServer http;
    private ExecutorService executor;

    public PanelServer(NautilusManager plugin) {
        this.plugin = plugin;
    }

    public void start() throws IOException {
        String host = plugin.getConfig().getString("bind-address", "127.0.0.1");
        int port = plugin.getConfig().getInt("port", 8080);

        http = HttpServer.create(new InetSocketAddress(host, port), 0);
        executor = Executors.newFixedThreadPool(4);
        http.setExecutor(executor);
        http.createContext("/api/", this::handleApi);
        http.createContext("/", this::handleStatic);
        http.start();

        plugin.getLogger().info("Panel web démarré sur http://" + host + ":" + port);
    }

    public void stop() {
        if (http != null) http.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    // ---------- Page ----------

    private void handleStatic(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            boolean ok = ex.getRequestMethod().equals("GET") && (path.equals("/") || path.equals("/web/index.html"));
            if (!ok) {
                send(ex, 404, "text/plain; charset=utf-8", "Introuvable".getBytes(UTF_8));
                return;
            }
            try (InputStream in = plugin.getResource("web/index.html")) {
                if (in == null) {
                    send(ex, 404, "text/plain; charset=utf-8", "index.html absent du jar".getBytes(UTF_8));
                    return;
                }
                send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
            }
        } finally {
            ex.close();
        }
    }

    // ---------- API ----------

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            String ip = clientIp(ex);
            if (isBlocked(ip)) {
                sendJson(ex, 429, Map.of("error", "Trop d'essais, réessaie dans quelques minutes"));
                return;
            }
            if (!authorized(ex)) {
                recordFailure(ip);
                sendJson(ex, 401, Map.of("error", "Jeton invalide"));
                return;
            }
            failures.remove(ip);

            String method = ex.getRequestMethod();
            String[] parts = ex.getRequestURI().getPath().substring("/api/".length()).split("/");
            JsonObject body = method.equals("POST") ? readBody(ex) : new JsonObject();

            sendJson(ex, 200, route(method, parts, body));
        } catch (PanelApi.ApiException e) {
            sendJson(ex, e.status, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            plugin.getLogger().warning("Erreur API : " + e);
            sendJson(ex, 500, Map.of("error", "Erreur interne"));
        } finally {
            ex.close();
        }
    }

    private Object route(String method, String[] p, JsonObject body) throws Exception {
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");

        switch (p[0]) {
            case "server" -> {
                if (get && p.length == 1) return sync(api::server);
            }
            case "players" -> {
                if (get && p.length == 1) return sync(api::players);
                if (get && p.length == 2) return sync(() -> api.player(p[1]));
                if (p.length == 3 && p[2].equals("inventory")) {
                    if (get) return sync(() -> api.inventory(p[1]));
                    if (post) return sync(() -> api.inventoryOp(p[1], body));
                }
                if (post && p.length == 3 && p[2].equals("action")) return sync(() -> api.action(p[1], body));
            }
            case "broadcast" -> {
                if (post && p.length == 1) return sync(() -> api.broadcast(body));
            }
            case "console" -> {
                if (post && p.length == 1) {
                    if (!plugin.getConfig().getBoolean("allow-console", false)) {
                        throw new PanelApi.ApiException(403, "Console désactivée (allow-console dans config.yml)");
                    }
                    return sync(() -> api.console(body));
                }
            }
            default -> { }
        }
        throw new PanelApi.ApiException(404, "Route inconnue");
    }

    /** Exécute la tâche sur le thread principal du serveur et attend le résultat. */
    private <T> T sync(Callable<T> task) throws Exception {
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    // ---------- Sécurité ----------

    private boolean authorized(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        byte[] given = header.substring(7).trim().getBytes(UTF_8);
        byte[] expected = plugin.getConfig().getString("token", "").getBytes(UTF_8);
        return MessageDigest.isEqual(given, expected); // comparaison en temps constant
    }

    private String clientIp(HttpExchange ex) {
        if (plugin.getConfig().getBoolean("trust-proxy", false)) {
            String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.substring(xff.lastIndexOf(',') + 1).trim(); // dernière entrée = ajoutée par le proxy
            }
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private boolean isBlocked(String ip) {
        long[] f = failures.get(ip);
        if (f == null) return false;
        if (System.currentTimeMillis() - f[1] > WINDOW_MS) {
            failures.remove(ip);
            return false;
        }
        return f[0] >= MAX_FAILS;
    }

    private void recordFailure(String ip) {
        long now = System.currentTimeMillis();
        failures.compute(ip, (k, f) -> {
            if (f == null || now - f[1] > WINDOW_MS) return new long[]{1, now};
            f[0]++;
            return f;
        });
    }

    // ---------- Utilitaires HTTP ----------

    private JsonObject readBody(HttpExchange ex) {
        try {
            byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (raw.length > MAX_BODY) throw new PanelApi.ApiException(413, "Corps trop grand");
            if (raw.length == 0) return new JsonObject();
            JsonElement el = JsonParser.parseString(new String(raw, UTF_8));
            if (!el.isJsonObject()) throw new PanelApi.ApiException(400, "Objet JSON attendu");
            return el.getAsJsonObject();
        } catch (IOException | JsonParseException e) {
            throw new PanelApi.ApiException(400, "JSON invalide");
        }
    }

    private void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        send(ex, status, "application/json; charset=utf-8", GSON.toJson(payload).getBytes(UTF_8));
    }

    private void send(HttpExchange ex, int status, String type, byte[] data) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Content-Security-Policy", CSP);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }
}