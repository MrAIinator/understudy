package tbs.director;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

/**
 * Вкладка «Режиссёр» в TBS-Admin: обработчики на HTTP-сервере мода tbs-stats (тот же порт и ключ админа).
 * GET /v1/director/status, GET /v1/director/feed, POST /v1/director/act.
 */
public final class AdminApi {
    private static final ArrayDeque<String[]> FEED = new ArrayDeque<>();

    private AdminApi() {}

    /** Событие в живую ленту админки. */
    static void feed(String text) {
        synchronized (FEED) {
            FEED.addLast(new String[] {String.valueOf(System.currentTimeMillis() / 1000), text});
            while (FEED.size() > 150) {
                FEED.pollFirst();
            }
        }
    }

    static String feedJson() {
        JsonArray arr = new JsonArray();
        synchronized (FEED) {
            for (String[] e : FEED) {
                JsonObject o = new JsonObject();
                o.addProperty("t", Long.parseLong(e[0]));
                o.addProperty("text", e[1]);
                arr.add(o);
            }
        }
        JsonObject root = new JsonObject();
        root.add("events", arr);
        return root.toString();
    }

    /** Подключиться к tbs-stats. status/act выполняются в серверном потоке. */
    static void register(MinecraftServer server, Supplier<String> status, Function<String, String> act) {
        try {
            Class<?> cls = Class.forName("tbs.stats.StatsServer");
            Object stats = cls.getField("INSTANCE").get(null);
            if (stats == null) {
                DirectorMod.LOG.warn("[understudy] tbs-stats не запущен — вкладка «Режиссёр» недоступна");
                return;
            }
            var add = cls.getMethod("addAdmin", String.class, BiFunction.class);
            add.invoke(stats, "/v1/director/status", (BiFunction<String, String, String>) (m, b) -> onServer(server, status));
            add.invoke(stats, "/v1/director/feed", (BiFunction<String, String, String>) (m, b) -> feedJson());
            add.invoke(stats, "/v1/director/act", (BiFunction<String, String, String>) (m, b) -> onServer(server, () -> act.apply(b)));
            DirectorMod.LOG.info("[understudy] вкладка «Режиссёр» подключена к серверу статистики");
        } catch (Throwable t) {
            DirectorMod.LOG.warn("[understudy] вкладка «Режиссёр» не подключилась: {}", t.toString());
        }
    }

    private static String onServer(MinecraftServer server, Supplier<String> job) {
        try {
            return server.submit(job::get).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "{\"error\":\"сервер занят: " + e.getClass().getSimpleName() + "\"}";
        }
    }
}
