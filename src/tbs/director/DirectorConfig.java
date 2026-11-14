package tbs.director;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * config/understudy/config.properties.
 * The file is generated on first start with an English comment above every important option.
 * Options added by later versions are appended to an existing file, nothing the owner wrote is rewritten.
 * No API key is ever shipped with the mod: the owner pastes their own, or the mod runs without AI.
 */
public final class DirectorConfig {
    public boolean enabled = true;
    public String apiKey = "";
    public String model = "claude-sonnet-5-5";
    public String effort = "low";
    public int minGapSeconds = 240;
    public int maxGapSeconds = 900;
    public int maxAiCallsPerHour = 20;
    public int maxAiCallsPerDay = 300;
    public int aiChancePercent = 50;
    // Sonnet-class models always "think" and thinking counts towards max_tokens — 1024 would cut the answer off.
    public int maxTokens = 4096;
    public boolean allowWorld = true;
    public boolean allowHard = true;
    public boolean allowExtreme = false;
    public int hardCooldownSeconds = 420;
    public boolean decayEnabled = true;
    public int decayMinutesPerStage = 360;
    public boolean echoEnabled = true;
    public int echoMinutes = 40;
    public boolean ambientEnabled = true;
    public double aiActivity = 1.5;
    public int timeoutSeconds = 25;
    public boolean replyToChat = true;
    public String grokApiKey = "";
    public String grokModel = "grok-4";
    public String customName = "";
    public String customUrl = "";
    public String customApiKey = "";
    public String customModel = "";
    public String brain = "claude";
    public String speech = "grok";
    public boolean mimicSelfFallback = true;

    // Story module "Protocol ECHO" and optional old combat
    public boolean protocolEnabled = true;
    public int protocolIntensity = 3;
    public double protocolPace = 1.5;
    public boolean protocolTab = true;
    public boolean oldCombat = false;
    public boolean noSweepAttack = false;

    // Optional built-in web server (story archive pages). Off unless the owner turns it on.
    public boolean webArchiveEnabled = false;
    public int webPort = 25842;
    public String webBind = "0.0.0.0";
    public String publicUrl = "";
    public boolean launcherApi = false;

    /** One documented option: section title (optional), English and Russian comment, key and default value. */
    record Opt(String section, String en, String ru, String key, String def) {}

    static final List<Opt> OPTS = List.of(
            new Opt("GENERAL / ОБЩЕЕ", """
                    Master switch for the AI director (whispers, scares, world events).
                    The story, world decay and mimics have their own switches below.""", """
                    Главный выключатель режиссёра (шёпот, испуги, события в мире).
                    У сюжета, умирания мира и мимиков свои переключатели ниже.""", "enabled", "true"),
            new Opt(null, """
                    Seconds between two scares for the same player (min..max). At night, in the dark and alone the pause is halved.""", """
                    Пауза между испугами одного игрока, секунды (мин..макс). Ночью, в темноте и в одиночку — вдвое короче.""", "minGapSeconds", "240"),
            new Opt(null, null, null, "maxGapSeconds", "900"),
            new Opt(null, """
                    Small causal details between scares (a door creaks, a torch goes out, an animal freezes).
                    Every 1-3 minutes, never uses AI. Sounds always have a visible source.""", """
                    Мелочи между испугами (скрипнула дверь, погас факел, замер зверь). Раз в 1-3 минуты, без ИИ.
                    У каждого звука есть видимая причина.""", "ambientEnabled", "true"),
            new Opt(null, """
                    Null answers in chat when a player writes "who's there", "null", "help" and similar.""", """
                    Null отвечает в чате, когда игрок пишет «кто тут», «null», «помогите» и т.п.""", "replyToChat", "true"),

            new Opt("AI (OPTIONAL) / ИИ (НЕОБЯЗАТЕЛЬНО)", """
                    The mod is fully playable WITHOUT any key: Null then uses built-in lines and picks actions by itself.
                    Anthropic API key (console.anthropic.com -> API Keys). You pay for your own usage.
                    You can also set keys in game without opening this file: /nullAI key claude <key>, /nullAI key grok <key> (server owner only).
                    Never share a config file that has a key in it.""", """
                    Мод полностью работает БЕЗ ключа: Null берёт готовые реплики и сам выбирает действия.
                    Ключ Anthropic API (console.anthropic.com -> API Keys). Запросы оплачиваешь ты сам.
                    Ключ можно вставить и прямо в игре: /nullAI key claude <ключ>, /nullAI key grok <ключ> (только владелец сервера).
                    Не передавай никому конфиг, в котором вписан ключ.""", "apiKey", ""),
            new Opt(null, """
                    Claude model and reasoning effort (low / medium / high).
                    claude-sonnet-5-5 is the default; claude-haiku-5-5 is far cheaper; claude-opus-5-5 is smarter and costs more.""", """
                    Модель Claude и «усилие» (low / medium / high).
                    claude-sonnet-5-5 — по умолчанию; claude-haiku-5-5 — намного дешевле; claude-opus-5-5 — умнее и дороже.""", "model", "claude-sonnet-5-5"),
            new Opt(null, null, null, "effort", "low"),
            new Opt(null, """
                    Second, optional AI: Grok (console.x.ai -> API Keys). Works alone or together with Claude.""", """
                    Второй, необязательный ИИ: Grok (console.x.ai -> API Keys). Работает один или вместе с Claude.""", "grokApiKey", ""),
            new Opt(null, null, null, "grokModel", "grok-4"),
            new Opt(null, """
                    Any other AI with an OpenAI-compatible API: OpenAI, OpenRouter (hundreds of models), Google Gemini, DeepSeek,
                    Mistral, Groq, or a local model in Ollama / LM Studio (no key needed). Easiest way, in game:
                      /nullAI custom <openai|openrouter|gemini|deepseek|mistral|groq|ollama|lmstudio>   then   /nullAI key custom <key>
                    Or fill these by hand. customUrl is the API base (…/v1); customModel is the model id from the provider's docs.
                    Model names change over time - if requests fail, check the model id first.""", """
                    Любой другой ИИ с OpenAI-совместимым API: OpenAI, OpenRouter (сотни моделей), Google Gemini, DeepSeek,
                    Mistral, Groq или локальная модель в Ollama / LM Studio (без ключа). Проще всего в игре:
                      /nullAI custom <openai|openrouter|gemini|deepseek|mistral|groq|ollama|lmstudio>   потом   /nullAI key custom <ключ>
                    Или заполни вручную. customUrl — база API (…/v1); customModel — id модели из документации сервиса.
                    Названия моделей со временем меняются — если запросы не проходят, сначала проверь id модели.""", "customName", ""),
            new Opt(null, null, null, "customUrl", ""),
            new Opt(null, null, null, "customApiKey", ""),
            new Opt(null, null, null, "customModel", ""),
            new Opt(null, """
                    Who does what (claude, grok or custom). In game: /nullAI
                    brain  - decides how to scare, who speaks, what happens to reputation
                    speech - writes the actual line for the brain's decision
                    Same value twice = one AI does everything. If the chosen AI is not set up, the first one that is takes its role.""", """
                    Кто за что отвечает (claude, grok или custom). В игре: /nullAI
                    brain  — «мозг»: решает, чем пугать, кто говорит и что с репутацией
                    speech — «речь»: пишет саму реплику по решению мозга
                    Одинаковые значения — один ИИ делает всё. Выбранный не настроен — его роль берёт первый настроенный.""", "brain", "claude"),
            new Opt(null, null, null, "speech", "grok"),
            new Opt(null, """
                    Cost control: hard limits on AI requests for the whole server. Above the limit built-in lines are used (free).""", """
                    Контроль расходов: лимит запросов к ИИ на весь сервер. Сверх лимита — готовые фразы (бесплатно).""", "maxAiCallsPerHour", "20"),
            new Opt(null, null, null, "maxAiCallsPerDay", "300"),
            new Opt(null, """
                    Percent of scheduled scares written by AI (chat replies always use AI when a key is set).""", """
                    Сколько процентов плановых испугов придумывает ИИ (ответы в чате — всегда ИИ, если есть ключ).""", "aiChancePercent", "50"),
            new Opt(null, """
                    How much more often AI scares happen (1.0 = exactly the pauses above, 1.5 = one and a half times more often).""", """
                    Во сколько раз чаще испуги от ИИ (1.0 — ровно по паузам выше, 1.5 — в полтора раза чаще).""", "aiActivity", "1.5"),
            new Opt(null, """
                    Max tokens per AI answer and request timeout in seconds. Leave as is unless you know why.""", """
                    Максимум токенов в ответе ИИ и таймаут запроса в секундах. Без причины не трогай.""", "maxTokens", "4096"),
            new Opt(null, null, null, "timeoutSeconds", "25"),

            new Opt("HOW FAR NULL MAY GO / НАСКОЛЬКО ДАЛЕКО ЗАХОДИТ NULL", """
                    allowWorld   - torches going out, chests opening, signs, camera turns, The Broken Script's own events
                    allowHard    - damage (never lethal), hunters, mimic raids, shuffled chest contents, fire
                    allowExtreme - base explosions, ruined inventory, lava. IRREVERSIBLE, off by default.""", """
                    allowWorld   — гаснущие факелы, открытые сундуки, таблички, разворот камеры, события самого TBS
                    allowHard    — урон (не смертельный), охотники, набеги мимиков, перестановка вещей в сундуке, огонь
                    allowExtreme — взрыв базы, порча инвентаря, лава. НЕОБРАТИМО, по умолчанию выключено.""", "allowWorld", "true"),
            new Opt(null, null, null, "allowHard", "true"),
            new Opt(null, null, null, "allowExtreme", "false"),
            new Opt(null, """
                    A hard action hits the same player at most once per this many seconds.""", """
                    Жёсткое действие одному игроку — не чаще раза в столько секунд.""", "hardCooldownSeconds", "420"),
            new Opt(null, """
                    Mimics are "players" who join while the real ones are offline (names come from the world's usercache).
                    In single player nobody else is known - with this on, the mimic wears YOUR name and skin.""", """
                    Мимики — «игроки», которые заходят, пока настоящих нет в сети (ники берутся из usercache).
                    В одиночной игре других игроков нет — если включено, мимик приходит с ТВОИМ ником и скином.""", "mimicSelfFallback", "true"),

            new Opt("LIVING WORLD / ЖИВОЙ МИР", """
                    The world slowly dies in 5 stages: grass dries, trees go bare, ash, animals freeze, broken blocks.
                    Player builds are never touched. A stage takes this many minutes of play with players online (360 = 6 hours).""", """
                    Мир медленно умирает в 5 стадий: трава сохнет, деревья лысеют, пепел, звери замирают, битые блоки.
                    Постройки игроков не трогаются. Стадия — столько минут игры с игроками онлайн (360 = 6 часов).""", "decayEnabled", "true"),
            new Opt(null, null, null, "decayMinutesPerStage", "360"),
            new Opt(null, """
                    Echo: sometimes the world takes a copy of a player's build and places it far away.
                    Only blocks placed by players (tracked since Understudy 3.1.2 was installed) are copied - never villages,
                    ruins or The Broken Script's own structures. At most once per this many minutes for the whole server.
                    Saved copies: /null echoes (count), /null echoes clear (delete all).""", """
                    Эхо: иногда мир забирает копию постройки игрока и ставит её где-то далеко.
                    Копируются только блоки, поставленные игроками (учёт ведётся с установки Understudy 3.1.2), — не деревни,
                    не руины и не постройки самого The Broken Script. Не чаще раза в столько минут на весь сервер.
                    Сохранённые копии: /null echoes (сколько), /null echoes clear (удалить все).""", "echoEnabled", "true"),
            new Opt(null, null, null, "echoMinutes", "40"),

            new Opt("STORY: PROTOCOL ECHO / СЮЖЕТ «ПРОТОКОЛ ЭХО»", """
                    A long ARG-style story: logs of previous "operators" in hidden caches, ciphers, code words typed in chat,
                    trials and three endings. Russian and English, chosen by each player's game language. Admin command: /tbsecho""", """
                    Длинный сюжет в духе ARG: записи прошлых «операторов» в тайниках, шифры, кодовые слова в чат,
                    испытания и три концовки. На русском и английском — по языку игры каждого игрока. Команда админа: /tbsecho""", "protocolEnabled", "true"),
            new Opt(null, """
                    How harsh story events are: 1 soft, 2 normal, 3 harsh.""", """
                    Жёсткость сюжетных событий: 1 мягко, 2 нормально, 3 жёстко.""", "protocolIntensity", "3"),
            new Opt(null, """
                    Story length: 1.0 is about 15 in-game days, 1.5 is one and a half times longer and rarer.
                    While a player stands still for 2 minutes the story clock runs at half speed.""", """
                    Длина сюжета: 1.0 — примерно 15 игровых дней, 1.5 — в полтора раза дольше и реже.
                    Пока игрок 2 минуты стоит на месте, сюжетные часы идут вдвое медленнее.""", "protocolPace", "1.5"),
            new Opt(null, """
                    Status line at the bottom of the player list (Tab).""", """
                    Строка статуса внизу списка игроков (Tab).""", "protocolTab", "true"),

            new Opt("OLD COMBAT (OPTIONAL) / СТАРЫЙ БОЙ (НЕОБЯЗАТЕЛЬНО)", """
                    oldCombat     - pre-1.9 feel: no attack cooldown
                    noSweepAttack - swords do not hit in an area
                    Both change combat for every player, so they are off by default.""", """
                    oldCombat     — как до 1.9: удар без перезарядки
                    noSweepAttack — меч не бьёт по площади
                    Меняют бой для всех игроков, поэтому по умолчанию выключены.""", "oldCombat", "false"),
            new Opt(null, null, null, "noSweepAttack", "false"),

            new Opt("WEB ARCHIVE (OPTIONAL) / ВЕБ-АРХИВ (НЕОБЯЗАТЕЛЬНО)", """
                    A tiny built-in web server that shows story archive pages in a browser.
                    When false the mod opens NO network port at all.
                    Hosting: the port must be opened/allocated in your hosting panel.""", """
                    Маленький встроенный веб-сервер со страницами сюжетного архива для браузера.
                    false — мод вообще не открывает сетевых портов.
                    На хостинге порт нужно открыть (выделить) в панели хостинга.""", "webArchiveEnabled", "false"),
            new Opt(null, null, null, "webPort", "25842"),
            new Opt(null, """
                    Address to listen on. 0.0.0.0 = all interfaces, 127.0.0.1 = this computer only.""", """
                    На каком адресе слушать. 0.0.0.0 — все сети, 127.0.0.1 — только этот компьютер.""", "webBind", "0.0.0.0"),
            new Opt(null, """
                    The address players type in their browser, e.g. http://your.server.ip:25842
                    Empty = the story never sends players to the web archive.""", """
                    Адрес, который игроки откроют в браузере, например http://ip.твоего.сервера:25842
                    Пусто — сюжет не отправляет игроков в веб-архив.""", "publicUrl", ""),
            new Opt(null, """
                    Statistics and admin endpoints for the author's own launcher. Nobody else needs this - keep it false.""", """
                    Статистика и админ-доступ для лаунчера автора. Больше никому не нужно — оставь false.""", "launcherApi", "false"));

    private static String role(String v) {
        String r = v.trim().toLowerCase(java.util.Locale.ROOT);
        return Brain.PROVIDERS.contains(r) ? r : "claude";
    }

    /** Hands the story settings over to the story module. */
    void applyProtocol() {
        dev.mrainator.tbsecho.TbsEcho.ENABLED.set(protocolEnabled);
        dev.mrainator.tbsecho.TbsEcho.INTENSITY.set(protocolIntensity);
        dev.mrainator.tbsecho.TbsEcho.PACE.set(protocolPace);
        dev.mrainator.tbsecho.TbsEcho.TAB.set(protocolTab);
        dev.mrainator.tbsecho.TbsEcho.PUBLIC_URL.set(webArchiveEnabled ? publicUrl : "");
        dev.mrainator.tbsecho.TbsEcho.OLD_COMBAT.set(oldCombat);
        dev.mrainator.tbsecho.TbsEcho.NO_SWEEP.set(noSweepAttack);
        dev.mrainator.tbsecho.TbsEcho.SPAWN_ENTITIES.set(allowHard);
        dev.mrainator.tbsecho.TbsEcho.TAMPER_INVENTORY.set(allowHard);
    }

    boolean useFallbacks() {
        // Server-side fallbacks exist for Opus / Sonnet / Fable class models, not for Haiku.
        return !model.contains("haiku");
    }

    /** The whole documented file, with the given values (missing keys take their defaults). */
    static String template(Properties values) {
        StringBuilder b = new StringBuilder("""
                # Understudy - an unofficial director add-on for The Broken Script.
                # Edit, then run /null reload in game (or restart). Lines starting with # are comments.
                # Works on a dedicated server / hosting, on a LAN host and in single player.
                # How to connect an AI (Claude, Grok, OpenAI, Gemini, DeepSeek, local models...): see AI-SETUP.txt next to this file.
                #
                # Understudy — неофициальный режиссёр-дополнение к The Broken Script.
                # Поменяй значения и введи /null reload в игре (или перезапусти). Строки с # — комментарии.
                # Работает на выделенном сервере / хостинге, при игре по сети и в одиночной игре.
                # Как подключить ИИ (Claude, Grok, OpenAI, Gemini, DeepSeek, локальные модели...): файл AI-SETUP.txt рядом.
                """);
        for (Opt o : OPTS) {
            block(b, o, values.getProperty(o.key(), o.def()));
        }
        return b.toString();
    }

    private static void block(StringBuilder b, Opt o, String value) {
        if (o.section() != null) {
            b.append("\n# ==================== ").append(o.section()).append(" ====================\n");
        }
        if (o.en() != null) {
            b.append('\n');
            for (String line : (o.en().strip() + "\n" + o.ru().strip()).split("\n")) {
                b.append("# ").append(line.strip()).append('\n');
            }
        }
        b.append(o.key()).append('=').append(value).append('\n');
    }

    private static boolean bool(Properties p, String k, boolean def) {
        return Boolean.parseBoolean(p.getProperty(k, String.valueOf(def)).trim());
    }

    private static double dbl(Properties p, String k, double def, double min, double max) {
        try {
            return Math.max(min, Math.min(max, Double.parseDouble(p.getProperty(k, String.valueOf(def)).trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static DirectorConfig load(Path dir) {
        DirectorConfig c = new DirectorConfig();
        try {
            Files.createDirectories(dir);
            writeGuide(dir);
            Path file = dir.resolve("config.properties");
            Properties p = new Properties();
            boolean existed = Files.exists(file);
            if (existed) {
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                }
                // A config written before 3.1.0 (the author's own server): keep its web features on.
                if (!p.containsKey("webArchiveEnabled") && !p.getProperty("publicUrl", "").isBlank()) {
                    p.setProperty("webArchiveEnabled", "true");
                    p.setProperty("launcherApi", "true");
                    appendMissing(file, p, true);
                } else {
                    appendMissing(file, p, false);
                }
            }
            c.enabled = bool(p, "enabled", true);
            c.apiKey = orBundled(p, "apiKey");
            c.model = p.getProperty("model", c.model).trim();
            c.effort = p.getProperty("effort", c.effort).trim();
            c.minGapSeconds = Math.max(30, intOf(p, "minGapSeconds", c.minGapSeconds));
            c.maxGapSeconds = Math.max(c.minGapSeconds, intOf(p, "maxGapSeconds", c.maxGapSeconds));
            c.maxAiCallsPerHour = Math.max(0, intOf(p, "maxAiCallsPerHour", c.maxAiCallsPerHour));
            c.maxAiCallsPerDay = Math.max(0, intOf(p, "maxAiCallsPerDay", c.maxAiCallsPerDay));
            c.aiChancePercent = Math.min(100, Math.max(0, intOf(p, "aiChancePercent", c.aiChancePercent)));
            c.maxTokens = Math.min(8000, Math.max(256, intOf(p, "maxTokens", c.maxTokens)));
            if (!c.model.contains("haiku")) {
                c.maxTokens = Math.max(c.maxTokens, 4096); // thinking counts towards max_tokens
            }
            c.timeoutSeconds = Math.max(5, intOf(p, "timeoutSeconds", c.timeoutSeconds));
            c.replyToChat = bool(p, "replyToChat", true);
            c.allowWorld = bool(p, "allowWorld", true);
            c.allowHard = bool(p, "allowHard", true);
            c.allowExtreme = bool(p, "allowExtreme", false);
            c.hardCooldownSeconds = Math.max(60, intOf(p, "hardCooldownSeconds", c.hardCooldownSeconds));
            c.mimicSelfFallback = bool(p, "mimicSelfFallback", true);
            c.decayEnabled = bool(p, "decayEnabled", true);
            c.decayMinutesPerStage = Math.max(5, intOf(p, "decayMinutesPerStage", c.decayMinutesPerStage));
            c.echoEnabled = bool(p, "echoEnabled", true);
            c.echoMinutes = Math.max(5, intOf(p, "echoMinutes", c.echoMinutes));
            c.ambientEnabled = bool(p, "ambientEnabled", true);
            c.aiActivity = dbl(p, "aiActivity", 1.5, 0.3, 5.0);
            c.grokApiKey = orBundled(p, "grokApiKey");
            c.grokModel = p.getProperty("grokModel", c.grokModel).trim();
            c.customName = p.getProperty("customName", "").trim();
            c.customUrl = p.getProperty("customUrl", "").trim();
            c.customApiKey = orBundled(p, "customApiKey");
            c.customModel = p.getProperty("customModel", "").trim();
            c.brain = role(p.getProperty("brain", c.brain));
            c.speech = role(p.getProperty("speech", c.speech));
            c.protocolEnabled = bool(p, "protocolEnabled", true);
            c.protocolIntensity = Math.min(3, Math.max(1, intOf(p, "protocolIntensity", c.protocolIntensity)));
            c.protocolPace = dbl(p, "protocolPace", 1.5, 0.1, 10.0);
            c.protocolTab = bool(p, "protocolTab", true);
            c.oldCombat = bool(p, "oldCombat", false);
            c.noSweepAttack = bool(p, "noSweepAttack", false);
            c.webArchiveEnabled = bool(p, "webArchiveEnabled", false);
            c.webPort = Math.min(65535, Math.max(1, intOf(p, "webPort", c.webPort)));
            c.webBind = p.getProperty("webBind", c.webBind).trim();
            c.publicUrl = p.getProperty("publicUrl", "").trim().replaceAll("/+$", "");
            c.launcherApi = bool(p, "launcherApi", false);
            c.applyProtocol();
            if (!existed) {
                Files.writeString(file, template(new Properties()), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            DirectorMod.LOG.error("[understudy] could not read config.properties", e);
        }
        return c;
    }

    /**
     * Appends options the file does not have yet, each with its comment. Existing lines are left alone.
     * useValues: write the values from p (used once, to carry settings over from a pre-3.1.0 config).
     */
    private static void appendMissing(Path file, Properties p, boolean useValues) {
        List<Opt> missing = new ArrayList<>();
        Properties onDisk = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            onDisk.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        for (Opt o : OPTS) {
            if (!onDisk.containsKey(o.key())) {
                missing.add(o);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        StringBuilder b = new StringBuilder("\n# ---------- options added by Understudy 3.1.0 ----------\n");
        for (Opt o : missing) {
            block(b, new Opt(null, o.en(), o.ru(), o.key(), o.def()), useValues ? p.getProperty(o.key(), o.def()) : o.def());
        }
        try {
            Files.writeString(file, b.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            DirectorMod.LOG.warn("[understudy] could not append new options: {}", e.toString());
        }
    }

    /**
     * Private builds only: keys placed in the jar at /understudy/bundled-keys.properties at build time.
     * Public builds and the git repository never contain this file. A key in the config always wins;
     * "/nullAI key <ai> clear" writes an empty value, which also falls back to the bundled key.
     */
    static final Properties BUNDLED = new Properties();

    static {
        try (InputStream in = DirectorConfig.class.getResourceAsStream("/understudy/bundled-keys.properties")) {
            if (in != null) {
                BUNDLED.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // no bundled keys
        }
    }

    private static String orBundled(Properties p, String key) {
        String v = p.getProperty(key, "").trim();
        return v.isEmpty() ? BUNDLED.getProperty(key, "").trim() : v;
    }

    /** AI-SETUP.txt next to the config: how to connect an AI (EN + RU). Refreshed on every start. */
    static void writeGuide(Path dir) {
        try (InputStream in = DirectorConfig.class.getResourceAsStream("/understudy/AI-SETUP.txt")) {
            if (in != null) {
                byte[] guide = in.readAllBytes();
                Path to = dir.resolve("AI-SETUP.txt");
                if (!Files.exists(to) || !java.util.Arrays.equals(Files.readAllBytes(to), guide)) {
                    Files.write(to, guide);
                }
            }
        } catch (Exception e) {
            DirectorMod.LOG.warn("[understudy] could not write AI-SETUP.txt: {}", e.toString());
        }
    }

    /** Changes a single "key=value" line in config.properties and touches nothing else (API keys, comments). */
    static void setKey(Path dir, String key, String value) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve("config.properties");
        List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (!t.startsWith("#") && t.matches(java.util.regex.Pattern.quote(key) + "\\s*[=:].*")) {
                lines.set(i, key + "=" + value);
                found = true;
            }
        }
        if (!found) {
            lines.add(key + "=" + value);
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static int intOf(Properties p, String k, int def) {
        try {
            return Integer.parseInt(p.getProperty(k, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
