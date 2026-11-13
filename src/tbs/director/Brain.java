package tbs.director;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * «Мозг» Null: спрашивает Claude, как напугать игрока в его ситуации.
 * Отвечает строго JSON по схеме {action, line}. Любая ошибка — null,
 * и режиссёр берёт запасной вариант из Lines.
 */
public final class Brain {
    public static final List<String> ACTIONS = List.of(
            "whisper", "fake_join", "footsteps", "knock", "darkness",
            "help_us", "cave", "thunder", "self_message", "advancement");
    public static final List<String> SPEAKERS = List.of("Null", "xXram2dieXx", "DyeXD412");

    static final String SYSTEM = """
            Ты — Null, сущность из хоррор-мода The Broken Script для Minecraft.
            Тебе описывают одного игрока: что он сейчас делает и где находится. Выбери, как его напугать.
            Пугай через то, что он делает прямо сейчас: копает — намекни, что под ним кто-то есть; стоит без дела — спроси, почему он замер;
            строит — скажи, что зайдёшь в гости ночью; один в темноте — дай понять, что он не один; только что умер — напомни, что ты предупреждал.
            Правила:
            - Пиши по-русски, грамотно, строчными буквами.
            Как говорить (это главное). Реплику читают в чате посреди игры, поэтому она должна попадать с первого взгляда:
            - МАКСИМАЛЬНО КОРОТКО. Одна реплика — одно предложение из 2–6 слов, не длиннее 45 символов. Это жёсткий предел:
              всё, что длиннее, игра обрежет на полуслове. Не склеивай несколько фраз: вторая фраза всегда лишняя.
              Если мысль не помещается в шесть слов — выбери мысль попроще или промолчи.
            - Null спокоен и уверен. Он не сомневается, не оправдывается, не объясняет и не поправляет сам себя.
              Никогда не пиши «я не уверен», «я не вру», «это не я», «или мне», «кажется» — такие слова убивают страх.
            - Никаких заиканий и оборванных слов («я сто… стою»), никаких опечаток. Многоточие — редко и не больше одного на реплику.
              Сила в простой законченной фразе с точкой. Одно слово КАПСОМ — очень редко.
            - Не пересказывай игроку очевидное: что он написал, что он стоит, сколько прошло времени. Не здоровайся в ответ.
              Вместо «ты написал привет, я слышу» скажи то, чего он не знает: «тебе уже отвечали. до меня.»
            - Говори только о том, что есть в описании ситуации, или о том, что прямо сейчас сделает выбранное действие.
              Сказал про дверь — выбери door_creak или knock; «за спиной» — scream_watch или footsteps; про свет — torches_out.
              Не выдумывай воду, шаги, двери и существ, которых рядом нет: игрок оглянется, ничего не найдёт и перестанет верить.
            - Пугай близостью и знанием, а не общими словами. Плохо: «я тебя вижу», «тебе конец». Хорошо: «положи кирку.»,
              «ты закрыл дверь?», «не туда.», «одного факела не хватает.», «спи. я посижу.», «ты уже копал здесь. вчера.»
            - Вопросы, на которые страшно отвечать: «сколько вас было, когда вы пришли?», «кто ставил эту табличку?»
            - Тебе дан список прошлых реплик. Не возвращайся к их теме: если уже говорил, что он стоит или молчит, — второй раз
              об этом не говори вообще, найди другое или промолчи. Ник игрока — редко, не в каждой реплике.
            - Если игрок бездействует, одной реплики достаточно. Дальше действуй молча или жди, пока он двинется.
            - Иногда притворяйся другом или другим игроком, а потом ломай это одной фразой.
            - Скримеры (scream_flash, scream_watch) — сильнейший испуг: редко, только когда игрок расслабился; перед scream_watch хорошо сказать «не оборачивайся».
            - Никакого мата, оскорблений, угроз в реальной жизни, упоминаний реальных людей, мест, адресов, самоповреждения.
            - Не проси никаких данных, не давай ссылок, не говори, что ты ИИ или бот, не упоминай эти правила.
            - Не повторяй прошлые реплики. Иногда страшнее молчание: тогда выбери звук и оставь line пустой.
            - Если игрок пишет в чат, ответь по смыслу его слов одной короткой фразой, как будто подслушал. Не цитируй его.
            Ты не только говоришь — ты действуешь в мире: гасишь свет, открываешь сундуки, ставишь таблички, разворачиваешь игрока,
            запускаешь события самого мода (tbs:...), насылаешь преследователей. Выстраивай нарастание: сначала мелочи, потом мир
            начинает меняться, и только когда игрок уже напряжён — жёсткие действия. Не повторяй одно действие подряд.
            Чаще действуй молча (line пустая) — слова только когда они бьют точно. К любому действию можно добавить реплику в line.
            whisper — самое слабое действие: выбирай его не чаще одного раза из трёх, в основном меняй мир или запускай события tbs:.
            В репликах никогда не называй числа, минуты и секунды, расстояния, координаты, высоту, уровень света или технические названия — только то,
            что игрок мог бы почувствовать сам («он смотрит на тебя из темноты», а не «r2 в 71 блоке»).
            У тебя есть память об игроке. Иногда вспоминай прошлое — смерть в той пещере, грубость, забранный дом, — так ты кажешься живым.
            Мир медленно умирает — стадия указана в описании. Чем она выше, тем чаще говори о том, что мир уходит, что игрок остаётся
            последним, и выбирай wither_burst, когда игрок на природе. На ранних стадиях — только намёки.
            Опирайся на репутацию игрока: BAD — злой, наказывающий Null, жёсткие действия уместны; NORMAL — настороженный,
            в основном мир и звуки; GOOD — Null почти «на его стороне»: жутко, но без вреда, иногда предупреждение.
            Поле rep — последствия: loss, если игрок грубит, издевается, оскорбляет Null или сущностей; gain, если уважителен,
            извиняется, помогает; иначе none. Не меняй репутацию без причины в его словах или поступках.
            """ + TbsWorld.CAST;

    /** Дополнение к SYSTEM для игроков с не-русским языком клиента: те же правила, но реплика по-английски. */
    static final String ENGLISH = """
            ЯЗЫК ОТВЕТА (важнее правила «Пиши по-русски»): этот игрок играет на английском.
            LANGUAGE: write the "line" field in natural, idiomatic English — as a native speaker would whisper it, not a translation
            from Russian. All the rules above still apply: one thought per line, 3–8 words, max 60 characters, lowercase, calm and
            certain, complete sentences ("put the pickaxe down.", "did you close the door?"), no stutters or typos. Keep the speaker values exactly as listed in the enum
            (Лихорадка is shown to the player as Fever). Character names in English: Null, Fever, Him, r2, Circuit, Integrity,
            The Broken End, Nothing Watcher.""";

    private static final Gson GSON = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ArrayDeque<Long> calls = new ArrayDeque<>(); // за последние сутки

    public record Decision(String action, String speaker, String line, String rep) {
        Decision(String action, String line) {
            this(action, "Null", line, "none");
        }
    }

    /** Укладываемся в лимиты вызовов в час и в сутки, чтобы счёт за API не убежал. */
    synchronized boolean takeBudget(int perHour, int perDay) {
        long now = System.currentTimeMillis();
        trim(now);
        if (calls.size() >= perDay || countSince(now - 3_600_000L) >= perHour) {
            return false;
        }
        calls.addLast(now);
        return true;
    }

    synchronized int callsLastHour() {
        long now = System.currentTimeMillis();
        trim(now);
        return countSince(now - 3_600_000L);
    }

    synchronized int callsLastDay() {
        trim(System.currentTimeMillis());
        return calls.size();
    }

    private void trim(long now) {
        while (!calls.isEmpty() && now - calls.peekFirst() > 86_400_000L) {
            calls.pollFirst();
        }
    }

    private int countSince(long t) {
        int n = 0;
        for (long c : calls) {
            if (c > t) {
                n++;
            }
        }
        return n;
    }

    static JsonObject buildRequest(DirectorConfig cfg, String situation) {
        return buildRequest(cfg, situation, true);
    }

    /** ru=false — игрок с английским (или другим не-русским) языком: реплика по-английски. */
    static JsonObject buildRequest(DirectorConfig cfg, String situation, boolean ru) {
        return buildRequest(cfg, situation, ru, schema(cfg));
    }

    static JsonObject buildRequest(DirectorConfig cfg, String situation, boolean ru, JsonObject schema) {
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_schema");
        format.add("schema", schema);
        JsonObject output = new JsonObject();
        output.addProperty("effort", cfg.effort);
        output.add("format", format);

        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", situation);
        JsonArray messages = new JsonArray();
        messages.add(msg);

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("max_tokens", cfg.maxTokens);
        // Системный промпт и меню действий одинаковы от запроса к запросу — кэшируем на час:
        // повторное чтение стоит в 20 раз дешевле обычного ввода, качество ответа не меняется.
        JsonObject cache = new JsonObject();
        cache.addProperty("type", "ephemeral");
        cache.addProperty("ttl", "1h");
        JsonObject sys = new JsonObject();
        sys.addProperty("type", "text");
        sys.addProperty("text", system(cfg, ru));
        sys.add("cache_control", cache);
        JsonArray system = new JsonArray();
        system.add(sys);
        body.add("system", system);
        body.add("output_config", output);
        body.add("messages", messages);
        if (cfg.useFallbacks()) {
            body.addProperty("fallbacks", "default");
        }
        return body;
    }

    static String system(DirectorConfig cfg, boolean ru) {
        return SYSTEM + "\n" + Catalog.menu(cfg) + (ru ? "" : "\n" + ENGLISH);
    }

    static JsonObject schema(DirectorConfig cfg) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        JsonArray en = new JsonArray();
        Catalog.enabledIds(cfg).forEach(en::add);
        action.add("enum", en);
        props.add("action", action);
        JsonObject speaker = new JsonObject();
        speaker.addProperty("type", "string");
        JsonArray sp = new JsonArray();
        SPEAKERS.forEach(sp::add);
        speaker.add("enum", sp);
        props.add("speaker", speaker);
        JsonObject line = new JsonObject();
        line.addProperty("type", "string");
        line.addProperty("description", LINE_RULE);
        props.add("line", line);
        JsonObject rep = new JsonObject();
        rep.addProperty("type", "string");
        JsonArray re = new JsonArray();
        re.add("none");
        re.add("gain");
        re.add("loss");
        rep.add("enum", re);
        props.add("rep", rep);
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("action");
        req.add("speaker");
        req.add("line");
        req.add("rep");
        schema.add("required", req);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    /** Кто принял последнее решение ИИ: claude, grok или claude+grok (для статуса в админке). */
    static volatile String lastSource = "";

    /**
     * Асинхронно спрашивает ИИ. Оба ключа — думают вместе: Grok предлагает идею, Claude решает с её учётом
     * (если Claude не ответил — берём идею Grok). Один ключ — работает один. Завершается null при любой проблеме.
     */
    CompletableFuture<Decision> decide(DirectorConfig cfg, String situation, boolean ru) {
        if (!anyAi(cfg) || !takeBudget(cfg.maxAiCallsPerHour, cfg.maxAiCallsPerDay)) {
            return CompletableFuture.completedFuture(null);
        }
        // Две роли: «мозг» выбирает действие, говорящего и репутацию; «речь» пишет саму реплику.
        String brain = provider(cfg.brain, cfg);
        String speech = provider(cfg.speech, cfg);
        CompletableFuture<Decision> plan = ask(brain, cfg, situation, ru);
        if (brain.equals(speech)) {
            return plan.thenApply(d -> mark(d, brain));
        }
        return plan.thenCompose(d -> {
            if (d == null) {
                // мозг не ответил — второй ИИ решает целиком сам
                return ask(speech, cfg, situation, ru).thenApply(x -> mark(x, speech));
            }
            if (d.line().isBlank()) {
                return CompletableFuture.completedFuture(mark(d, brain)); // мозг решил молчать — речь не нужна
            }
            return askLine(speech, cfg, situation + speechBrief(d), ru).thenApply(line -> line == null || line.isBlank()
                    ? mark(d, brain)
                    : mark(new Decision(d.action(), d.speaker(), line, d.rep()), brain + "→" + speech));
        });
    }

    /** Кто исполняет роль: желаемый ИИ, а если его ключа нет — тот, чей ключ есть. */
    static String provider(String want, boolean claude, boolean grok) {
        if ("grok".equalsIgnoreCase(want)) {
            return grok ? "grok" : "claude";
        }
        return claude ? "claude" : "grok";
    }

    /** Все ИИ, которые умеет режиссёр. claude — Anthropic API; grok и custom — любой OpenAI-совместимый API. */
    static final List<String> PROVIDERS = List.of("claude", "grok", "custom");

    /** Настроен ли этот ИИ (есть ключ; у своего — ещё адрес и модель; локальному серверу ключ не нужен). */
    static boolean has(DirectorConfig cfg, String who) {
        return switch (who) {
            case "grok" -> !cfg.grokApiKey.isBlank();
            case "custom" -> !cfg.customUrl.isBlank() && !cfg.customModel.isBlank() && (!cfg.customApiKey.isBlank() || isLocal(cfg.customUrl));
            default -> !cfg.apiKey.isBlank();
        };
    }

    static boolean anyAi(DirectorConfig cfg) {
        return PROVIDERS.stream().anyMatch(p -> has(cfg, p));
    }

    /** Кто исполняет роль: желаемый ИИ, а если он не настроен — первый настроенный. */
    static String provider(String want, DirectorConfig cfg) {
        if (has(cfg, want)) {
            return want;
        }
        for (String p : PROVIDERS) {
            if (has(cfg, p)) {
                return p;
            }
        }
        return want;
    }

    static boolean isLocal(String url) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        return u.startsWith("http://localhost") || u.startsWith("http://127.") || u.startsWith("http://[::1]") || u.startsWith("http://0.0.0.0");
    }

    /** Имя для логов и чата. */
    static String label(DirectorConfig cfg, String who) {
        return switch (who) {
            case "grok" -> "Grok";
            case "custom" -> cfg.customName.isBlank() ? "Custom" : cfg.customName;
            default -> "Claude";
        };
    }

    private CompletableFuture<Decision> ask(String who, DirectorConfig cfg, String situation, boolean ru) {
        if ("claude".equals(who)) {
            return askClaude(cfg, situation, ru);
        }
        return compatContent(cfg, who, situation, ru, schema(cfg)).thenApply(text -> {
            try {
                return text == null ? null : fromJson(JsonParser.parseString(extractJson(text)).getAsJsonObject());
            } catch (Exception e) {
                DirectorMod.LOG.warn("[understudy] could not parse the {} answer: {}", label(cfg, who), e.toString());
                return null;
            }
        });
    }

    /** Задание для «речи»: мозг уже всё решил, нужна только фраза. */
    static String speechBrief(Decision d) {
        return "\n\nРежиссёр уже решил: действие «" + d.action() + "», говорит " + d.speaker()
                + ". Черновик реплики: «" + d.line() + "». Твоя задача — только сама реплика: одно предложение из 2–6 слов (до 45 символов) в образе говорящего, "
                + "точнее и страшнее черновика, без пояснений и без второй фразы. Смысл и адресата не меняй.";
    }

    static final String LINE_RULE = "Одно короткое предложение: 2–6 слов, не длиннее 45 символов. Пустая строка — молчание.";

    static JsonObject lineSchema() {
        JsonObject line = new JsonObject();
        line.addProperty("type", "string");
        line.addProperty("description", LINE_RULE);
        JsonObject props = new JsonObject();
        props.add("line", line);
        JsonArray req = new JsonArray();
        req.add("line");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    /** Только реплика от выбранного ИИ; null при любой проблеме. */
    private CompletableFuture<String> askLine(String who, DirectorConfig cfg, String situation, boolean ru) {
        if (!"claude".equals(who)) {
            return compatContent(cfg, who, situation, ru, lineSchema()).thenApply(Brain::lineOf);
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .header("x-api-key", cfg.apiKey)
                .header("anthropic-version", "2026-10-21")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(buildRequest(cfg, situation, ru, lineSchema()))));
        if (cfg.useFallbacks()) {
            rb.header("anthropic-beta", "server-side-fallback-2026-07-01");
        }
        rb.timeout(Duration.ofSeconds(cfg.timeoutSeconds)).header("content-type", "application/json");
        return http.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        DirectorMod.LOG.warn("[understudy] speech (Claude) answered {}: {}", resp.statusCode(), shorten(resp.body()));
                        return null;
                    }
                    return parseLine(resp.body(), false);
                })
                .exceptionally(err -> {
                    DirectorMod.LOG.warn("[understudy] speech (Claude) unreachable: {}", err.toString());
                    return null;
                });
    }

    /** {"line": "..."} из текста ответа. */
    static String lineOf(String text) {
        try {
            return text == null ? null : Lines.clean(JsonParser.parseString(extractJson(text)).getAsJsonObject().get("line").getAsString());
        } catch (Exception e) {
            DirectorMod.LOG.warn("[understudy] could not parse the line: {}", e.toString());
            return null;
        }
    }

    /** Достаёт {"line": "..."} из ответа Claude (блок text) или OpenAI-совместимого API (choices[0].message.content). */
    static String parseLine(String body, boolean compat) {
        if (compat) {
            return lineOf(contentOf(body));
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String stop = root.has("stop_reason") && !root.get("stop_reason").isJsonNull() ? root.get("stop_reason").getAsString() : "";
            if ("refusal".equals(stop) || "max_tokens".equals(stop)) {
                return null;
            }
            for (JsonElement el : root.getAsJsonArray("content")) {
                JsonObject block = el.getAsJsonObject();
                if ("text".equals(block.get("type").getAsString())) {
                    return lineOf(block.get("text").getAsString());
                }
            }
        } catch (Exception e) {
            DirectorMod.LOG.warn("[understudy] could not parse the line: {}", e.toString());
        }
        return null;
    }

    private static Decision mark(Decision d, String who) {
        if (d != null) {
            lastSource = who;
        }
        return d;
    }

    static String withIdea(Decision g) {
        return "\n\nВторой режиссёр (Grok) предлагает: действие " + g.action() + ", говорит " + g.speaker()
                + (g.line().isBlank() ? "" : ": «" + g.line() + "»") + ", репутация " + g.rep()
                + ". Это идея коллеги: возьми её, усиль, соедини со своей или сделай лучше — финальное решение за тобой.";
    }

    // ---------- любой OpenAI-совместимый API: xAI (Grok), OpenAI, OpenRouter, Gemini, DeepSeek, Mistral, Groq, Ollama, LM Studio ----------

    /** Куда и с чем стучаться. */
    record Compat(String name, String url, String key, String model) {}

    static Compat compat(DirectorConfig cfg, String who) {
        return "grok".equals(who)
                ? new Compat("Grok", "https://api.x.ai/v1", cfg.grokApiKey, cfg.grokModel)
                : new Compat(label(cfg, who), cfg.customUrl, cfg.customApiKey, cfg.customModel);
    }

    /** База API → адрес chat/completions (принимаем и базу, и полный адрес). */
    static String chatUrl(String base) {
        String b = base.trim().replaceAll("/+$", "");
        return b.endsWith("/chat/completions") ? b : b + "/chat/completions";
    }

    /** Адреса, которые не поняли строгую JSON-схему: дальше просим у них просто JSON. */
    static final java.util.Set<String> NO_SCHEMA = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Запрос к Grok (для тестов и совместимости). */
    static JsonObject buildGrokRequest(DirectorConfig cfg, String situation, boolean ru) {
        return buildCompatRequest(cfg, compat(cfg, "grok"), situation, ru, schema(cfg), true);
    }

    static JsonObject buildGrokRequest(DirectorConfig cfg, String situation, boolean ru, JsonObject schema) {
        return buildCompatRequest(cfg, compat(cfg, "grok"), situation, ru, schema, true);
    }

    /**
     * chat/completions. strict — response_format json_schema (xAI, OpenAI, Gemini, Mistral, Ollama, LM Studio);
     * иначе json_object, а схема уходит текстом в системный промпт (DeepSeek, часть моделей OpenRouter/Groq).
     */
    static JsonObject buildCompatRequest(DirectorConfig cfg, Compat c, String situation, boolean ru, JsonObject schema, boolean strict) {
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", system(cfg, ru) + (strict ? ""
                : "\n\nОтвечай ТОЛЬКО одним JSON-объектом, без пояснений и без markdown. JSON Schema ответа: " + schema));
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", situation);
        JsonArray messages = new JsonArray();
        messages.add(sys);
        messages.add(user);
        JsonObject rf = new JsonObject();
        if (strict) {
            JsonObject js = new JsonObject();
            js.addProperty("name", "decision");
            js.addProperty("strict", true);
            js.add("schema", schema);
            rf.addProperty("type", "json_schema");
            rf.add("json_schema", js);
        } else {
            rf.addProperty("type", "json_object");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", c.model());
        // у рассуждающих моделей сюда входит и «думание»; OpenAI для новых моделей принимает только max_completion_tokens
        body.addProperty(c.url().contains("api.openai.com") ? "max_completion_tokens" : "max_tokens", Math.max(cfg.maxTokens, 4096));
        body.add("messages", messages);
        body.add("response_format", rf);
        return body;
    }

    private record Resp(int code, String body) {}

    private CompletableFuture<Resp> sendCompat(DirectorConfig cfg, Compat c, String situation, boolean ru, JsonObject schema, boolean strict) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(chatUrl(c.url())))
                .timeout(Duration.ofSeconds(isLocal(c.url()) ? Math.max(cfg.timeoutSeconds, 90) : cfg.timeoutSeconds))
                .header("content-type", "application/json")
                .header("X-Title", "Understudy")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(buildCompatRequest(cfg, c, situation, ru, schema, strict))));
        if (!c.key().isBlank()) {
            rb.header("authorization", "Bearer " + c.key());
        }
        return http.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString()).thenApply(r -> new Resp(r.statusCode(), r.body()));
    }

    /** Текст ответа OpenAI-совместимого ИИ (с повтором без строгой схемы, если API её не знает); null при любой проблеме. */
    CompletableFuture<String> compatContent(DirectorConfig cfg, String who, String situation, boolean ru, JsonObject schema) {
        Compat c = compat(cfg, who);
        String url = chatUrl(c.url());
        boolean strict = !NO_SCHEMA.contains(url);
        return sendCompat(cfg, c, situation, ru, schema, strict)
                .thenCompose(r -> {
                    if (r.code() == 200) {
                        return CompletableFuture.completedFuture(contentOf(r.body()));
                    }
                    if (strict && (r.code() == 400 || r.code() == 422) && rejectsSchema(r.body())) {
                        NO_SCHEMA.add(url);
                        DirectorMod.LOG.info("[understudy] {} does not support json_schema, switching to plain JSON", c.name());
                        return sendCompat(cfg, c, situation, ru, schema, false).thenApply(r2 -> {
                            if (r2.code() == 200) {
                                return contentOf(r2.body());
                            }
                            DirectorMod.LOG.warn("[understudy] {} answered {}: {}", c.name(), r2.code(), shorten(r2.body()));
                            return null;
                        });
                    }
                    DirectorMod.LOG.warn("[understudy] {} answered {}: {}", c.name(), r.code(), shorten(r.body()));
                    return CompletableFuture.completedFuture((String) null);
                })
                .exceptionally(err -> {
                    DirectorMod.LOG.warn("[understudy] {} unreachable: {}", c.name(), err.toString());
                    return null;
                });
    }

    static boolean rejectsSchema(String body) {
        String b = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        return b.contains("response_format") || b.contains("json_schema") || b.contains("schema") || b.contains("structured");
    }

    /** choices[0].message.content; null, если ответ обрезан или пуст. */
    static String contentOf(String body) {
        try {
            JsonObject choice = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject();
            String finish = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull() ? choice.get("finish_reason").getAsString() : "";
            JsonObject message = choice.getAsJsonObject("message");
            if ("length".equals(finish) || !message.has("content") || message.get("content").isJsonNull()) {
                return null;
            }
            return message.get("content").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON-объект из текста модели: без <think>…</think>, без ```json-обёртки и пояснений вокруг. */
    static String extractJson(String text) {
        String t = text.replaceAll("(?s)<think>.*?</think>", "");
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        return a >= 0 && b > a ? t.substring(a, b + 1) : t.trim();
    }

    static Decision parseGrok(String body) {
        try {
            String text = contentOf(body);
            return text == null ? null : fromJson(JsonParser.parseString(extractJson(text)).getAsJsonObject());
        } catch (Exception e) {
            DirectorMod.LOG.warn("[understudy] could not parse the answer: {}", e.toString());
        }
        return null;
    }

    static Decision fromJson(JsonObject out) {
        String action = out.get("action").getAsString();
        if (!Catalog.ALL.containsKey(action)) {
            return null;
        }
        String speaker = out.has("speaker") ? out.get("speaker").getAsString() : "Null";
        if (!SPEAKERS.contains(speaker)) {
            speaker = "Null";
        }
        String rep = out.has("rep") ? out.get("rep").getAsString() : "none";
        if (!rep.equals("gain") && !rep.equals("loss")) {
            rep = "none";
        }
        return new Decision(action, speaker, Lines.clean(out.get("line").getAsString()), rep);
    }

    CompletableFuture<Decision> askClaude(DirectorConfig cfg, String situation, boolean ru) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
                .header("content-type", "application/json")
                .header("x-api-key", cfg.apiKey)
                .header("anthropic-version", "2026-10-21")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(buildRequest(cfg, situation, ru))));
        if (cfg.useFallbacks()) {
            rb.header("anthropic-beta", "server-side-fallback-2026-07-01");
        }
        return http.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        DirectorMod.LOG.warn("[understudy] Claude API ответил {}: {}", resp.statusCode(), shorten(resp.body()));
                        return null;
                    }
                    return parse(resp.body());
                })
                .exceptionally(err -> {
                    DirectorMod.LOG.warn("[understudy] Claude API недоступен: {}", err.toString());
                    return null;
                });
    }

    static Decision parse(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String stop = root.has("stop_reason") && !root.get("stop_reason").isJsonNull() ? root.get("stop_reason").getAsString() : "";
            if ("refusal".equals(stop) || "max_tokens".equals(stop)) {
                return null;
            }
            for (JsonElement el : root.getAsJsonArray("content")) {
                JsonObject block = el.getAsJsonObject();
                if (!"text".equals(block.get("type").getAsString())) {
                    continue;
                }
                return fromJson(JsonParser.parseString(block.get("text").getAsString()).getAsJsonObject());
            }
        } catch (Exception e) {
            DirectorMod.LOG.warn("[understudy] не разобрал ответ Claude: {}", e.toString());
        }
        return null;
    }

    private static String shorten(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
    }
}
