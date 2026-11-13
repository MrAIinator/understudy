package dev.mrainator.tbsecho;

import dev.mrainator.tbsecho.EchoData.State;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import tbs.stats.StatsServer;

/**
 * Веб-архив «ЭХО-билда»: настоящая страница в браузере на порту сервера статистики.
 * /echo — общий список операторов; /echo/<пропуск> — личная страница игрока с найденными записями
 * и журналами прошлых операторов. Когда личную страницу открывают, игра это замечает.
 * Язык: суффикс /ru или /en (или ?lang=, если сервер передаст строку запроса) → язык владельца пропуска → английский.
 */
public final class Archive {
    private Archive() {}

    private static final SecureRandom RND = new SecureRandom();
    private static final String ABC = "abcdefghijkmnpqrstuvwxyz23456789";

    static String newToken() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 7; i++) b.append(ABC.charAt(RND.nextInt(ABC.length())));
        return b.toString();
    }

    /** Вешает страницы на сервер статистики; без него архив просто недоступен. */
    public static boolean register(MinecraftServer server) {
        StatsServer stats = StatsServer.INSTANCE;
        if (stats == null) return false;
        stats.addPublic("/echo", path -> {
            try {
                return server.submit(() -> render(server, path)).get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                return null;
            }
        });
        return true;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Переключатель языка; base — путь страницы из наших данных (не из запроса). */
    private static String langLinks(String base) {
        return "<p class=\"d\"><a href=\"" + esc(base) + "/ru\">ru</a> · <a href=\"" + esc(base) + "/en\">en</a></p>";
    }

    private static String page(boolean ru, String title, String body) {
        return "<!doctype html><html lang=\"" + (ru ? "ru" : "en") + "\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<meta name=\"robots\" content=\"noindex\"><title>" + esc(title) + "</title><style>"
            + "body{background:#050805;color:#7fbf7f;font:15px/1.5 Consolas,'Courier New',monospace;margin:0;padding:24px 16px}"
            + "main{max-width:720px;margin:0 auto}h1{font-size:17px;color:#b6e6b6;letter-spacing:2px}h2{font-size:15px;color:#b6e6b6;margin-top:28px}"
            + "pre{white-space:pre-wrap;border-left:2px solid #1f3a1f;padding:6px 12px;margin:10px 0;color:#9fd09f}"
            + ".d{color:#3f6a3f}.r{color:#b33}table{border-collapse:collapse}td{padding:2px 18px 2px 0}a{color:#3f6a3f}"
            + "</style></head><body><main>" + body + "</main></body></html>";
    }

    static String render(MinecraftServer server, String path) {
        return render(EchoData.of(server), path);
    }

    /** path: /echo[/пропуск][/ru|/en][?lang=ru|en]. В ответ из запроса ничего не подставляется. */
    public static String render(EchoData data, String path) {
        String p = path == null ? "" : path;
        Boolean lang = null;
        int q = p.indexOf('?');
        if (q >= 0) {
            String query = p.substring(q + 1).toLowerCase(Locale.ROOT);
            if (query.contains("lang=ru")) lang = true;
            else if (query.contains("lang=en")) lang = false;
            p = p.substring(0, q);
        }
        StringBuilder rest = new StringBuilder();
        for (String seg : (p.length() > 5 ? p.substring(5) : "").split("/")) {
            String t = seg.trim();
            if (t.isEmpty()) continue;
            if (t.equals("ru")) lang = true;
            else if (t.equals("en")) lang = false;
            else rest.append(t);
        }
        if (rest.length() == 0) return index(data, lang != null && lang);
        String token = rest.toString();
        for (State s : data.all()) {
            if (!s.token.isEmpty() && s.token.equals(token)) {
                s.webSeen++;
                data.setDirty();
                return personal(s, lang != null ? lang : s.lang == 1);
            }
        }
        return lang != null && lang
            ? page(true, "ЭХО // нет записи", "<h1>ЭХО-БИЛД // АРХИВ</h1><p class=\"r\">запись не найдена. или уже стёрта.</p>")
            : page(false, "ECHO // no record", "<h1>ECHO BUILD // ARCHIVE</h1><p class=\"r\">record not found. or already erased.</p>");
    }

    private static String index(EchoData data, boolean ru) {
        StringBuilder b = new StringBuilder(ru
            ? "<h1>ЭХО-БИЛД // АРХИВ СЕРВЕРА // 2012</h1>"
                + "<p class=\"d\">сервер выключен 17.04.2012. процесс не завершился.</p><h2>ОПЕРАТОРЫ</h2><table>"
                + "<tr><td>1</td><td>kostya_</td><td class=\"r\">стёрт</td></tr>"
                + "<tr><td>2</td><td>Lena.S</td><td class=\"r\">стёрта</td></tr>"
                + "<tr><td>3</td><td>dimon4ik</td><td class=\"r\">стёрт</td></tr>"
                + "<tr><td>4</td><td>" + Story.VESNA + "</td><td>дописана</td></tr>"
            : "<h1>ECHO BUILD // SERVER ARCHIVE // 2012</h1>"
                + "<p class=\"d\">server shut down 17.04.2012. the process never exited.</p><h2>OPERATORS</h2><table>"
                + "<tr><td>1</td><td>kostya_</td><td class=\"r\">erased</td></tr>"
                + "<tr><td>2</td><td>Lena.S</td><td class=\"r\">erased</td></tr>"
                + "<tr><td>3</td><td>dimon4ik</td><td class=\"r\">erased</td></tr>"
                + "<tr><td>4</td><td>" + Story.VESNA + "</td><td>written in</td></tr>");
        for (State s : data.all()) {
            if (s.act >= 1 && !s.name.isEmpty()) {
                b.append("<tr><td>5</td><td>").append(esc(s.name)).append("</td><td>").append(Director.status(s, ru)).append("</td></tr>");
            }
        }
        b.append(ru ? "</table><p class=\"d\">вход — по личному пропуску. пропуск лежит под красным светом.</p>"
            : "</table><p class=\"d\">entry by personal pass only. the pass lies under red light.</p>");
        b.append(langLinks("/echo"));
        return page(ru, ru ? "ЭХО // архив" : "ECHO // archive", b.toString());
    }

    private static String personal(State s, boolean ru) {
        StringBuilder b = new StringBuilder(ru
            ? "<h1>ЭХО-БИЛД // ЛИЧНАЯ ЗАПИСЬ</h1><p>оператор 5: " + esc(s.name) + " · статус: " + Director.status(s, true)
                + " · сессий: " + s.sessions + " · смертей: " + s.deaths + "</p>"
            : "<h1>ECHO BUILD // PERSONAL RECORD</h1><p>operator 5: " + esc(s.name) + " · status: " + Director.status(s, false)
                + " · sessions: " + s.sessions + " · deaths: " + s.deaths + "</p>");
        b.append(ru ? "<h2>ЗАПИСИ ОПЕРАТОРА 4</h2>" : "<h2>OPERATOR 4 ENTRIES</h2>");
        int found = Math.min(s.journals, 6);
        if (found == 0) b.append(ru ? "<p class=\"d\">пока пусто. ищи красный свет.</p>" : "<p class=\"d\">empty for now. look for red light.</p>");
        for (int i = 0; i < found; i++) journal(b, i, ru);
        if (s.graveVisited) journal(b, 6, ru);
        b.append(ru ? "<h2>ЖУРНАЛЫ ПРЕДЫДУЩИХ</h2>" : "<h2>LOGS OF THOSE BEFORE</h2>");
        String[] ops = Story.operators(ru);
        for (int i = 0; i < ops.length; i++) {
            if (s.act > i) b.append("<pre>").append(esc(ops[i])).append("</pre>");
            else if (ru) b.append("<p class=\"d\">оператор ").append(i + 1).append(": [доступ закрыт — мало записей]</p>");
            else b.append("<p class=\"d\">operator ").append(i + 1).append(": [access denied — not enough entries]</p>");
        }
        if (s.act >= 3) {
            b.append(ru ? "<h2>ПОВРЕЖДЁННЫЙ ФРАГМЕНТ</h2><pre>" : "<h2>DAMAGED FRAGMENT</h2><pre>")
                .append(Base64.getEncoder().encodeToString(Story.fragment(ru).getBytes(StandardCharsets.UTF_8)))
                .append(ru ? "</pre><p class=\"d\">кодировка не повреждена. повреждён тот, кто писал.</p>"
                    : "</pre><p class=\"d\">the encoding isn't damaged. whoever wrote it is.</p>");
        }
        if (s.act >= 4 && s.hasGrave && !s.graveVisited) {
            b.append(ru ? "<h2>ПОСЛЕДНЕЕ ПОЛОЖЕНИЕ ОПЕРАТОРА 4</h2><pre>X: " : "<h2>OPERATOR 4 LAST KNOWN POSITION</h2><pre>X: ")
                .append(Story.reverseNumber(s.gx)).append("\nZ: ").append(Story.reverseNumber(s.gz))
                .append(ru ? "\n\nон читает слева направо. ты — читай наоборот.</pre>" : "\n\nit reads left to right. you — read it backwards.</pre>");
        }
        b.append("<p class=\"r\">").append(ru ? "он видит, что ты здесь. входов: " : "it sees you're here. visits: ").append(s.webSeen).append("</p>");
        b.append(langLinks("/echo/" + s.token));
        return page(ru, (ru ? "ЭХО // " : "ECHO // ") + s.name, b.toString());
    }

    private static void journal(StringBuilder b, int i, boolean ru) {
        b.append("<pre>").append(esc(Story.journalTitle(i, ru) + "\n\n" + String.join("\n\n", Story.journal(i, ru)))).append("</pre>");
    }
}
