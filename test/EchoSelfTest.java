import dev.mrainator.tbsecho.Archive;
import dev.mrainator.tbsecho.EchoData;
import dev.mrainator.tbsecho.Story;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import tbs.stats.StatsServer;

/** Самопроверка сюжетного модуля и веб-архива без запуска Minecraft. */
public class EchoSelfTest {
    static int fails;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        // шифры
        check(Story.caesar(Story.caesar("колодец", 3), -3).equals(Story.code(3)), "caesar round-trip = code(3)");
        StringBuilder ac = new StringBuilder();
        String[] ls = Story.journal(1)[0].split("\n");
        for (int i = 2; i < ls.length; i++) ac.append(ls[i].charAt(0));
        check(ac.toString().toLowerCase().equals(Story.code(1)), "acrostic = code(1)");
        check(Story.reverseNumber(-350).equals("-053") && Story.reverseNumber(1274).equals("4721"), "reversed coordinates");
        int max = 0;
        for (int j = 0; j < 7; j++) for (String p : Story.journal(j)) max = Math.max(max, p.length());
        check(max <= 256, "journal pages fit a book page (max " + max + ")");
        check(Story.OPERATORS.length == 3, "three operator logs");

        // английские версии загадок
        check(Story.caesar(Story.caesar("well", 3), -3).equals(Story.code(3, false))
            && Story.journal(5, false)[1].contains(Story.caesar("well", 3)), "EN caesar round-trip = code(3,en)");
        StringBuilder acEn = new StringBuilder();
        String[] lsEn = Story.journal(1, false)[0].split("\n");
        for (int i = 2; i < lsEn.length; i++) acEn.append(lsEn[i].charAt(0));
        check(acEn.toString().toLowerCase().equals(Story.code(1, false)), "EN acrostic = code(1,en): " + acEn);
        check(String.join(" ", Story.morse(false)).equals(". -.-. .... ---") && Story.code(2, false).equals("echo"), "EN morse = echo");
        check(String.join(" ", Story.morse(true)).equals("..-.. .... ---"), "RU morse unchanged");
        check(Story.isCode(1, "помни") && Story.isCode(1, "remember") && Story.isCode(2, "эхо") && Story.isCode(2, "echo")
            && Story.isCode(3, "колодец") && Story.isCode(3, "well") && !Story.isCode(3, "помни"), "codes accepted in both languages");
        int maxEn = 0;
        for (int j = 0; j < 7; j++) for (String p : Story.journal(j, false)) maxEn = Math.max(maxEn, p.length());
        check(maxEn <= 256, "EN journal pages fit a book page (max " + maxEn + ")");
        boolean sameBeats = true;
        for (int act = 0; act <= 7; act++) {
            Story.Beat[] r = Story.beats(act, true), e = Story.beats(act, false);
            if (r.length != e.length) sameBeats = false;
            else for (int i = 0; i < r.length; i++) {
                if (!r[i].kind().equals(e[i].kind()) || r[i].delaySec() != e[i].delaySec()) sameBeats = false;
            }
        }
        check(sameBeats, "EN beats mirror RU beats");
        check(Story.WHISPERS_EN.length == Story.WHISPERS.length && Story.SIGNS_EN.length == Story.SIGNS.length
            && Story.FAKE_LOGS_EN.length == Story.FAKE_LOGS.length && Story.VESNA_LINES_EN.length == Story.VESNA_LINES.length
            && Story.REWRITER_LINES_EN.length == Story.REWRITER_LINES.length && Story.RENAMES_EN.length == Story.RENAMES.length
            && Story.TRIAL_START_EN.length == 3 && Story.TRIAL_PASS_EN.length == 3 && Story.OPERATORS_EN.length == 3, "EN arrays match RU sizes");

        // архив: рендер
        EchoData data = new EchoData();
        EchoData.State s = data.get(UUID.randomUUID());
        s.name = "Tester<b>";
        s.token = "abc2345";
        s.act = 4;
        s.journals = 6;
        s.hasGrave = true;
        s.gx = -350;
        s.gz = 1274;
        String index = Archive.render(data, "/echo/ru");
        check(index.contains("Tester&lt;b&gt;") && !index.contains("Tester<b>") && index.contains("Vesna_04") && index.contains("ОПЕРАТОРЫ"), "index lists operators, escapes names");
        check(!index.contains("abc2345"), "index does not leak tokens");
        String indexEn = Archive.render(data, "/echo");
        check(indexEn.contains("OPERATORS") && indexEn.contains("Tester&lt;b&gt;") && !indexEn.contains("ОПЕРАТОРЫ") && !indexEn.contains("abc2345"), "index defaults to English");
        check(Archive.render(data, "/echo?lang=ru").contains("ОПЕРАТОРЫ"), "?lang=ru selects Russian");
        String page = Archive.render(data, "/echo/abc2345/ru");
        check(s.webSeen == 1, "opening personal page is counted");
        check(page.contains("Запись 6") && page.contains("dimon4ik") && page.contains("X: -053") && page.contains("Z: 4721"), "personal page shows journals, logs, coordinates");
        String b64 = Base64.getEncoder().encodeToString(Story.FRAGMENT.getBytes(StandardCharsets.UTF_8));
        check(page.contains(b64), "fragment is base64 on the page");
        check(Archive.render(data, "/echo/zzzzzzz/ru").contains("запись не найдена") && s.webSeen == 1, "wrong token: no access, no count");
        check(Archive.render(data, "/echo/zzzzzzz").contains("record not found") && s.webSeen == 1, "wrong token (EN): no access, no count");
        String pageEn = Archive.render(data, "/echo/abc2345");
        String b64En = Base64.getEncoder().encodeToString(Story.FRAGMENT_EN.getBytes(StandardCharsets.UTF_8));
        check(s.webSeen == 2 && pageEn.contains("Entry 6") && pageEn.contains("PERSONAL RECORD") && pageEn.contains("X: -053")
            && pageEn.contains(b64En) && pageEn.contains("Tester&lt;b&gt;") && !pageEn.contains("Tester<b>"), "EN personal page (owner language unknown -> English)");
        s.lang = 1;
        check(Archive.render(data, "/echo/abc2345").contains("ЛИЧНАЯ ЗАПИСЬ"), "owner language picks Russian");
        check(Archive.render(data, "/echo/abc2345/en").contains("PERSONAL RECORD"), "explicit /en overrides owner language");
        check(!Archive.render(data, "/echo/<script>").contains("<script>"), "request input is not echoed");
        s.act = 1;
        check(Archive.render(data, "/echo/abc2345/ru").contains("оператор 2: [доступ закрыт"), "logs unlock by act");
        check(Archive.render(data, "/echo/abc2345/en").contains("operator 2: [access denied"), "EN logs unlock by act");

        // архив: настоящий HTTP через сервер статистики
        Path dir = Files.createTempDirectory("tbsstats-test");
        StatsServer st = StatsServer.start(dir, "127.0.0.1", 25842, true);
        st.addPublic("/echo", path -> Archive.render(data, path));
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + st.port();
        HttpResponse<String> r1 = http.send(HttpRequest.newBuilder(URI.create(base + "/echo/ru")).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(r1.statusCode() == 200 && r1.body().contains("ЭХО-БИЛД") && r1.headers().firstValue("content-type").orElse("").contains("utf-8"), "HTTP GET /echo/ru → 200 utf-8");
        HttpResponse<String> r1e = http.send(HttpRequest.newBuilder(URI.create(base + "/echo")).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(r1e.statusCode() == 200 && r1e.body().contains("ECHO BUILD"), "HTTP GET /echo → English");
        HttpResponse<String> r2 = http.send(HttpRequest.newBuilder(URI.create(base + "/echo/abc2345/ru")).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(r2.statusCode() == 200 && r2.body().contains("ЛИЧНАЯ ЗАПИСЬ"), "HTTP GET /echo/<token>/ru → personal page");
        HttpResponse<String> r3 = http.send(HttpRequest.newBuilder(URI.create(base + "/echo")).POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
        check(r3.statusCode() == 404, "POST is rejected");
        HttpResponse<String> r4 = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/director/status")).build(), HttpResponse.BodyHandlers.ofString());
        check(r4.statusCode() != 200, "admin routes are not opened by the public page");

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
