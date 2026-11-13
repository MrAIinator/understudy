package tbs.director;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Долгая память Null о каждом игроке: где умирал, кого видел, грубил ли, что у него забрали.
 * Хранится в папке мира (world/tbsdirector/memory/<uuid>.json) — новый мир начинается с чистого листа.
 */
public final class Memory {
    private static final int MAX = 30;

    record Note(long time, String text, String key) {}

    private final Path dir;
    private final Map<UUID, List<Note>> cache = new ConcurrentHashMap<>();

    Memory(Path worldDir) {
        this.dir = worldDir.resolve("memory");
    }

    private List<Note> notes(UUID id) {
        return cache.computeIfAbsent(id, k -> {
            List<Note> out = new ArrayList<>();
            try {
                Path f = dir.resolve(k + ".json");
                if (Files.exists(f)) {
                    for (JsonElement e : JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonArray()) {
                        JsonObject o = e.getAsJsonObject();
                        out.add(new Note(o.get("t").getAsLong(), o.get("x").getAsString(), o.has("k") ? o.get("k").getAsString() : ""));
                    }
                }
            } catch (Exception ex) {
                DirectorMod.LOG.warn("[understudy] память не прочиталась: {}", ex.toString());
            }
            return java.util.Collections.synchronizedList(out);
        });
    }

    /**
     * Запомнить событие. key — для повторов: одинаковый key не чаще раза в cooldownMs
     * (например, «видел Лихорадку» не записывается каждые две секунды).
     */
    void remember(UUID id, String text, String key, long cooldownMs) {
        List<Note> list = notes(id);
        long now = System.currentTimeMillis();
        synchronized (list) {
            if (key != null && !key.isEmpty()) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    Note n = list.get(i);
                    if (key.equals(n.key()) && now - n.time() < cooldownMs) {
                        return;
                    }
                }
            }
            list.add(new Note(now, text, key == null ? "" : key));
            while (list.size() > MAX) {
                list.remove(0);
            }
        }
        save(id, list);
    }

    private void save(UUID id, List<Note> list) {
        try {
            Files.createDirectories(dir);
            JsonArray arr = new JsonArray();
            synchronized (list) {
                for (Note n : list) {
                    JsonObject o = new JsonObject();
                    o.addProperty("t", n.time());
                    o.addProperty("x", n.text());
                    o.addProperty("k", n.key());
                    arr.add(o);
                }
            }
            Files.writeString(dir.resolve(id + ".json"), arr.toString(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            DirectorMod.LOG.warn("[understudy] память не сохранилась: {}", ex.toString());
        }
    }

    /** Последние воспоминания с давностью — для описания ситуации ИИ. */
    List<String> recall(UUID id, int count) {
        List<Note> list = notes(id);
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (list) {
            for (int i = Math.max(0, list.size() - count); i < list.size(); i++) {
                Note n = list.get(i);
                out.add(ago(now - n.time()) + ": " + n.text());
            }
        }
        return out;
    }

    static String ago(long ms) {
        long min = ms / 60_000L;
        if (min < 2) {
            return "только что";
        }
        if (min < 60) {
            return min + " мин назад";
        }
        long h = min / 60;
        if (h < 24) {
            return h + " ч назад";
        }
        long d = h / 24;
        return d + (d == 1 ? " день назад" : d < 5 ? " дня назад" : " дней назад");
    }
}
