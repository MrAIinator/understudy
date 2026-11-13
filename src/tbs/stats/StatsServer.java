package tbs.stats;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Маленький HTTP-сервер статистики TBS Launcher.
 *
 * GET  /v1/count        → {"users": N}                     (публично)
 * POST /v1/hello        → регистрирует игрока и его железо   (публично)
 * GET  /v1/admin/users  → весь список, нужен X-Admin-Key     (только админ)
 *
 * Хранит данные в users.json рядом с config.properties и admin-key.txt.
 * Ник и IP не хранятся: id — это SHA-256 от UUID аккаунта, его считает лаунчер.
 */
public final class StatsServer {
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{64}$");
    private static final int MAX_USERS = 50_000;
    private static final int MAX_BODY = 16 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path dir;
    private final Path usersFile;
    private final byte[] adminKey;
    private final Map<String, Map<String, Object>> users = new ConcurrentHashMap<>();
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "tbs-stats-save"));
    private volatile boolean dirty;
    private HttpServer http;
    private final int port;
    private boolean launcher;

    /** Для других модов (TBS Director): запущенный сервер статистики. */
    public static volatile StatsServer INSTANCE;

    /**
     * Другой мод может повесить свой админский обработчик на этот же порт.
     * handler(метод, тело) → JSON-ответ. Доступ только с X-Admin-Key.
     */
    public void addAdmin(String path, java.util.function.BiFunction<String, String, String> handler) {
        if (!launcher || adminKey.length == 0) {
            return; // админ-эндпоинты только для лаунчера автора и только с ключом
        }
        http.createContext(path, ex -> {
            String given = ex.getRequestHeaders().getFirst("X-Admin-Key");
            if (given == null || !MessageDigest.isEqual(adminKey, given.trim().getBytes(StandardCharsets.UTF_8))) {
                send(ex, 401, "{\"error\":\"key\"}");
                return;
            }
            String body = "POST".equals(ex.getRequestMethod()) ? readBody(ex) : "";
            String out;
            try {
                out = handler.apply(ex.getRequestMethod(), body);
            } catch (Exception e) {
                out = "{\"error\":\"" + e.toString().replace('\"', '\'') + "\"}";
            }
            send(ex, 200, out == null ? "{}" : out);
        });
    }

    /**
     * Публичная HTML-страница без ключа (веб-архив сюжета). handler(путь запроса) → HTML или null (404).
     * Только GET; тело запроса не читается, в ответ ничего из запроса не подставляется.
     */
    public void addPublic(String path, java.util.function.Function<String, String> handler) {
        http.createContext(path, ex -> {
            String out = null;
            if ("GET".equals(ex.getRequestMethod())) {
                try {
                    out = handler.apply(ex.getRequestURI().getPath());
                } catch (Exception e) {
                    out = null;
                }
            }
            byte[] body = (out == null ? "<!doctype html><meta charset=\"utf-8\"><p>404</p>" : out).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(out == null ? 404 : 200, body.length);
            try (java.io.OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
    }

    private StatsServer(Path dir, int port, String adminKey) {
        this.dir = dir;
        this.usersFile = dir.resolve("users.json");
        this.port = port;
        this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
    }

    public int port() {
        return port;
    }

    /**
     * Поднимает веб-сервер. launcher=false — только публичные страницы (веб-архив), без статистики,
     * без админ-ключа и без файлов. launcher=true — ещё и эндпоинты лаунчера автора (/v1/...).
     */
    public static StatsServer start(Path dir, String bind, int port, boolean launcher) throws IOException {
        Files.createDirectories(dir);
        String key = "";
        if (launcher) {
            Path keyFile = dir.resolve("admin-key.txt");
            if (!Files.exists(keyFile)) {
                byte[] raw = new byte[24];
                new SecureRandom().nextBytes(raw);
                Files.writeString(keyFile, HexFormat.of().formatHex(raw) + System.lineSeparator());
            }
            key = Files.readString(keyFile).trim();
        }
        StatsServer s = new StatsServer(dir, port, key);
        s.launcher = launcher;
        if (launcher) {
            s.load();
        }
        s.http = HttpServer.create(new InetSocketAddress(bind, port), 32);
        s.http.setExecutor(Executors.newFixedThreadPool(4, r -> daemon(r, "understudy-web")));
        if (launcher) {
            s.http.createContext("/v1/count", s::count);
            s.http.createContext("/v1/hello", s::hello);
            s.http.createContext("/v1/admin/users", s::adminUsers);
        }
        s.http.start();
        INSTANCE = s;
        s.saver.scheduleWithFixedDelay(s::saveIfDirty, 30, 30, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(s::saveIfDirty, "tbs-stats-final-save"));
        return s;
    }

    public void stop() {
        if (INSTANCE == this) {
            INSTANCE = null;
        }
        if (http != null) {
            http.stop(0);
        }
        saveIfDirty();
        saver.shutdownNow();
    }

    // ---------- handlers ----------

    private void count(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"method\"}");
            return;
        }
        send(ex, 200, "{\"users\":" + users.size() + "}");
    }

    private void hello(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"method\"}");
            return;
        }
        JsonObject body;
        try {
            body = JsonParser.parseString(readBody(ex)).getAsJsonObject();
        } catch (Exception e) {
            send(ex, 400, "{\"error\":\"bad json\"}");
            return;
        }
        String id = str(body, "id", 64);
        if (id == null || !ID.matcher(id).matches()) {
            send(ex, 400, "{\"error\":\"bad id\"}");
            return;
        }
        if (!users.containsKey(id) && users.size() >= MAX_USERS) {
            send(ex, 503, "{\"error\":\"full\"}");
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> rec = users.computeIfAbsent(id, k -> {
            Map<String, Object> m = new ConcurrentHashMap<>();
            m.put("id", k);
            m.put("firstSeen", now);
            return m;
        });
        rec.put("lastSeen", now);
        putStr(rec, "launcher", str(body, "launcher", 20));
        JsonElement hwEl = body.get("hw");
        if (hwEl != null && hwEl.isJsonObject()) {
            JsonObject hw = hwEl.getAsJsonObject();
            putStr(rec, "cpu", str(hw, "cpu", 120));
            putStr(rec, "gpu", str(hw, "gpu", 160));
            putStr(rec, "os", str(hw, "os", 80));
            putStr(rec, "screen", str(hw, "screen", 20));
            putInt(rec, "cores", num(hw, "cores", 1, 512));
            putInt(rec, "ramGb", num(hw, "ramGb", 1, 2048));
            putInt(rec, "gameRamGb", num(hw, "gameRamGb", 1, 2048));
        }
        dirty = true;
        send(ex, 200, "{\"users\":" + users.size() + "}");
    }

    private void adminUsers(HttpExchange ex) throws IOException {
        String given = ex.getRequestHeaders().getFirst("X-Admin-Key");
        if (given == null || !MessageDigest.isEqual(adminKey, given.trim().getBytes(StandardCharsets.UTF_8))) {
            send(ex, 401, "{\"error\":\"key\"}");
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>(users.values());
        send(ex, 200, GSON.toJson(Map.of("users", list)));
    }

    // ---------- storage ----------

    private void load() {
        if (!Files.exists(usersFile)) {
            return;
        }
        try {
            Map<String, Map<String, Object>> m = new Gson().fromJson(Files.readString(usersFile),
                    new TypeToken<Map<String, Map<String, Object>>>() {}.getType());
            if (m != null) {
                m.forEach((k, v) -> users.put(k, new ConcurrentHashMap<>(v)));
            }
        } catch (Exception e) {
            System.err.println("[tbs-stats] users.json не прочитался: " + e);
        }
    }

    private synchronized void saveIfDirty() {
        if (!launcher) {
            return;
        }
        if (!dirty) {
            return;
        }
        dirty = false;
        try {
            Path tmp = dir.resolve("users.json.tmp");
            Files.writeString(tmp, GSON.toJson(users));
            Files.move(tmp, usersFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            dirty = true;
            System.err.println("[tbs-stats] не сохранил users.json: " + e);
        }
    }

    // ---------- helpers ----------

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) {
                throw new IOException("too big");
            }
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private static String str(JsonObject o, String key, int max) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        String s = e.getAsString().replaceAll("[\\p{Cntrl}]", " ").trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static Integer num(JsonObject o, String key, int min, int max) {
        try {
            JsonElement e = o.get(key);
            if (e == null || !e.isJsonPrimitive()) {
                return null;
            }
            int v = (int) Math.round(e.getAsDouble());
            return v < min || v > max ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private static void putStr(Map<String, Object> m, String k, String v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    private static void putInt(Map<String, Object> m, String k, Integer v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** Для проверки без майн-сервера: java -cp ... tbs.stats.StatsServer <папка> */
    public static void main(String[] args) throws Exception {
        StatsServer s = start(Path.of(args.length > 0 ? args[0] : "tbsstats"), "0.0.0.0", 25842, true);
        System.out.println("tbs-stats on port " + s.port());
        Thread.currentThread().join();
    }
}
