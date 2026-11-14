package tbs.director;

import java.lang.reflect.Field;
import java.util.List;
import java.util.regex.Pattern;

public final class DirectorSelfTest {
    static int fails;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "FAIL ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        // 1. разбор нормального ответа
        String ok = "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"},{\"type\":\"text\",\"text\":\"{\\\"action\\\":\\\"footsteps\\\",\\\"line\\\":\\\"я под тобой §c http://x.ru\\\"}\"}]}";
        Brain.Decision d = Brain.parse(ok);
        check(d != null && d.action().equals("footsteps") && d.line().equals("я под тобой c"), "parse + clean: " + d);
        // 2. отказ и мусор
        check(Brain.parse("{\"stop_reason\":\"refusal\",\"content\":[]}") == null, "refusal -> null");
        check(Brain.parse("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"action\\\":\\\"rm_rf\\\",\\\"line\\\":\\\"\\\"}\"}]}") == null, "unknown action -> null");
        check(Brain.parse("not json") == null, "garbage -> null");
        // 3. тело запроса
        DirectorConfig c = new DirectorConfig();
        String body = Brain.buildRequest(c, "Игрок: Test").toString();
        check(body.contains("\"model\":\"claude-sonnet-5-5\"") && body.contains("\"json_schema\"") && body.contains("\"fallbacks\":\"default\"")
                && body.contains("\"effort\":\"low\"") && body.contains("\"max_tokens\":4096")
                && body.contains("\"cache_control\":{\"type\":\"ephemeral\",\"ttl\":\"1h\"}")
                && !body.contains("\"thinking\"") && !body.contains("tool_choice") && !body.contains("temperature"),
                "request body: sonnet 5.5, low effort, cached system prompt, fallbacks");
        c.model = "claude-opus-5-5";
        check(Brain.buildRequest(c, "x").toString().contains("\"fallbacks\":\"default\""), "opus: fallbacks on");
        // 4. бюджет
        Brain b = new Brain();
        check(b.takeBudget(2, 100) && b.takeBudget(2, 100) && !b.takeBudget(2, 100), "budget per hour");
        Brain b2 = new Brain();
        check(b2.takeBudget(10, 1) && !b2.takeBudget(10, 1), "budget per day");
        // 5. реакция на чат
        Field f = DirectorMod.class.getDeclaredField("CALL");
        f.setAccessible(true);
        Pattern call = (Pattern) f.get(null);
        for (String s : List.of("кто тут?", "Null ты где", "помогите", "это хиробрин?", "кто здесь")) check(call.matcher(s).find(), "call: " + s);
        for (String s : List.of("пойду копать", "нулевой уровень", "привет всем")) check(!call.matcher(s).find(), "no call: " + s);
        // 6. запасные решения и описание
        Situation s = new Situation();
        s.name = "MrAIinator"; s.dimension = "overworld"; s.biome = "dark_forest"; s.y = 12; s.light = 2;
        s.underground = true; s.dark = true; s.night = true; s.alone = true; s.nearestPlayer = -1; s.mined = 40;
        s.health = 14; s.maxHealth = 20; s.pastLines = List.of("я под тобой");
        Brain.Decision fb = Lines.fallback(s);
        check(Catalog.ALL.containsKey(fb.action()), "fallback underground: " + fb);
        s.chat = "кто тут?";
        check(Lines.fallback(s).action().equals("whisper"), "fallback chat -> whisper");
        String desc = s.describe();
        System.out.println("--- описание для ИИ ---\n" + desc);
        check(desc.contains("Под землёй") && desc.contains("кто тут?") && desc.contains("не повторяй"), "describe");
        // 7. голоса и сущности TBS
        String fever = "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"action\\\":\\\"whisper\\\",\\\"speaker\\\":\\\"Лихорадка\\\",\\\"line\\\":\\\"Ты видел небо?\\\"}\"}]}";
        Brain.Decision fd = Brain.parse(fever);
        check(fd != null && fd.speaker().equals("Null"), "speaker Лихорадка больше не голос режиссёра → Null: " + fd);
        String bad = "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"action\\\":\\\"whisper\\\",\\\"speaker\\\":\\\"Admin\\\",\\\"line\\\":\\\"x\\\"}\"}]}";
        check(Brain.parse(bad).speaker().equals("Null"), "unknown speaker -> Null");
        check(TbsWorld.name("fever").equals("Лихорадка") && TbsWorld.name("he").equals("Он") && TbsWorld.name("xxram_2die").equals("xXram2dieXx"), "tbs names: " + TbsWorld.name("fever"));
        check(!TbsWorld.relevant("integ_fireball") && TbsWorld.relevant("fever"), "ignore projectiles");
        check(Brain.SYSTEM.contains("Лихорадка") && Brain.buildRequest(new DirectorConfig(), "x").toString().contains("\"speaker\""), "prompt + schema know speakers");
        Situation t = new Situation();
        t.name = "P"; t.dimension = "overworld"; t.biome = "forest"; t.nearestPlayer = -1;
        t.nearbyTbs = List.of("Лихорадка, 20 блоков");
        t.tbsEvents = List.of("Лихорадка появился(ась) в 20 блоках (50 с назад)");
        check(t.describe().contains("Рядом сущности The Broken Script") && t.describe().contains("Недавние события"), "describe tbs");
        check(Lines.fallback(t).action().equals("whisper"), "fallback after tbs event");
        // 8. мир, репутация, TBS-чат
        DirectorConfig dc = new DirectorConfig();
        List<String> ids = Catalog.enabledIds(dc);
        check(ids.contains("torches_out") && ids.contains("tbs:behind_you") && ids.contains("hurt") && !ids.contains("tbs:explode_base"), "catalog tiers default (extreme off)");
        String req2 = Brain.buildRequest(dc, "x").toString();
        check(req2.contains("\"rep\"") && req2.contains("torches_out") && !req2.contains("explode_base"), "schema has rep + world acts");
        String withRep = "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"action\\\":\\\"torches_out\\\",\\\"speaker\\\":\\\"Null\\\",\\\"line\\\":\\\"\\\",\\\"rep\\\":\\\"loss\\\"}\"}]}";
        Brain.Decision rd = Brain.parse(withRep);
        check(rd != null && rd.action().equals("torches_out") && rd.rep().equals("loss"), "parse rep: " + rd);
        check(TbsBridge.repTier(10).equals("BAD") && TbsBridge.repTier(50).equals("NORMAL") && TbsBridge.repTier(90).equals("GOOD") && TbsBridge.repTier(-1).equals("?"), "rep tiers");
        check(Hooks.isTbs(net.minecraft.network.chat.Component.translatable("chat.thebrokenscript.null.msg")), "tbs chat by key");
        check(Hooks.isTbs(net.minecraft.network.chat.Component.literal("<Нулл> err.type=null")), "tbs chat by text");
        check(!Hooks.isTbs(net.minecraft.network.chat.Component.literal("Null шепчет вам: привет")), "own whisper is not tbs");
        Situation rs = new Situation(); rs.name="P"; rs.dimension="o"; rs.biome="b"; rs.nearestPlayer=-1; rs.reputation=12;
        check(rs.describe().contains("BAD"), "describe rep");
        // 9. мир умирает
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("decay");
        Decay.DirectorConfigView view = new Decay.DirectorConfigView() {
            public int decayMinutesPerStage() { return 360; }
            public boolean decayEnabled() { return true; }
        };
        Decay dz = new Decay(tmp, view);
        check(dz.stage == 0 && dz.describe().contains("жив"), "decay starts alive");
        dz.stage = 3; dz.minutes = 1100; dz.save();
        Decay dz2 = new Decay(tmp, view);
        check(dz2.stage == 3 && dz2.minutes == 1100 && dz2.describe().contains("3 из 5"), "decay persists: " + dz2.describe());
        dz2.onDeath();
        check(dz2.minutes == 1115, "death speeds decay");
        DirectorConfig dd = new DirectorConfig();
        check(dd.decayEnabled && dd.decayMinutesPerStage == 360 && Catalog.ALL.containsKey("wither_burst"), "decay config + action");
        Situation ws = new Situation(); ws.name="P"; ws.dimension="o"; ws.biome="b"; ws.nearestPlayer=-1; ws.worldState = dz2.describe();
        check(ws.describe().contains("Мир умирает"), "describe world state");
        check(Brain.SYSTEM.contains("медленно умирает"), "prompt knows decay");
        DirectorConfig lc = new DirectorConfig();
        check(lc.echoEnabled && lc.ambientEnabled && lc.aiActivity == 1.5 && Catalog.ALL.containsKey("echo_build") && Catalog.ALL.containsKey("door_creak") && !Lines.echoLine(true).isEmpty(), "echo + ambient config");
        check(Echo.CLAN_VOID.location().toString().equals("thebrokenscript:clan_void") && Echo.VOID_Y == 252 && Echo.TBS_STRUCTURES.contains("clanvoidnew1"), "clan_void target");
        java.nio.file.Path ed = java.nio.file.Files.createTempDirectory("echo");
        check(Echo.load(ed, null) == null, "empty library -> null");
        check(Catalog.ALL.containsKey("mimic_raid") && Catalog.ALL.get("mimic_raid").tier() == Catalog.Tier.HARD, "mimic is hard tier");
        int[] fired = {0};
        Sched.tell(new Sched.Later(3, () -> fired[0]++));
        Sched.tick(); Sched.tick();
        boolean early = fired[0] == 0;
        Sched.tick();
        check(early && fired[0] == 1, "sched waits 3 ticks");
        check(Hooks.isNullLeave(net.minecraft.network.chat.Component.translatable("multiplayer.player.left", "Null")) && !Hooks.isNullLeave(net.minecraft.network.chat.Component.translatable("multiplayer.player.left", "Markusuni")) && Hooks.isNullLeave(net.minecraft.network.chat.Component.literal("Null left the game")), "null leave detector ok");
        java.nio.file.Path md = java.nio.file.Files.createTempDirectory("mem");
        java.util.UUID mu = java.util.UUID.randomUUID();
        Memory mem = new Memory(md);
        mem.remember(mu, "умер под землёй", "death", 0);
        mem.remember(mu, "видел Лихорадка", "saw:fever", 3_600_000L);
        mem.remember(mu, "видел Лихорадка", "saw:fever", 3_600_000L);
        java.util.List<String> rc = new Memory(md).recall(mu, 6);
        check(rc.size() == 2 && rc.get(0).contains("умер под землёй") && rc.get(0).startsWith("только что"), "memory remembers + dedupes + persists: " + rc);
        check(Memory.ago(3 * 86_400_000L).equals("3 дня назад"), "memory ago");
        // 10. язык игрока
        check(L.ru("ru_ru") && L.ru("RU_RU") && L.ru("uk_ua") && L.ru("be_by") && L.ru("kk_kz"), "lang: ru family -> ru");
        check(!L.ru("en_us") && !L.ru("de_de") && !L.ru("") && !L.ru((String) null), "lang: others -> en");
        check(L.tr(true, "да", "yes").equals("да") && L.tr(false, "да", "yes").equals("yes"), "lang: tr");
        check(L.speaker("Лихорадка", false).equals("Fever") && L.speaker("Лихорадка", true).equals("Лихорадка") && L.speaker("Null", false).equals("Null"), "lang: speaker names");
        check(!Lines.echoLine(false).matches(".*\\p{IsCyrillic}.*") && Lines.echoLine(true).matches(".*\\p{IsCyrillic}.*"), "lang: echo line per language");
        Situation en = new Situation();
        en.name = "P"; en.dimension = "overworld"; en.biome = "forest"; en.nearestPlayer = -1; en.ru = false; en.chat = "who's there?";
        Brain.Decision ed2 = Lines.fallback(en);
        check(ed2.action().equals("whisper") && !ed2.line().isEmpty() && !ed2.line().matches(".*\\p{IsCyrillic}.*"), "lang: english fallback: " + ed2);
        check(en.describe().contains("Reply language: English") && !s.describe().contains("Reply language"), "lang: describe marks english");
        String enReq = Brain.buildRequest(new DirectorConfig(), "x", false).toString();
        check(enReq.contains("natural, idiomatic English") && !Brain.buildRequest(new DirectorConfig(), "x", true).toString().contains("idiomatic English"), "lang: english system addendum only for en");
        check(call.matcher("who's there?").find() && call.matcher("who are you").find(), "call: english");
        check(new Decay(tmp, view).describe(false).startsWith("The world") && dz2.describe(true).contains("3 из 5"), "lang: decay describe en/ru");
        // Grok
        DirectorConfig gc = new DirectorConfig();
        check(gc.grokApiKey.isEmpty() && gc.grokModel.equals("grok-4"), "grok: default off");
        String greq = Brain.buildGrokRequest(gc, "Игрок: Test", true).toString();
        check(greq.contains("\"model\":\"grok-4\"") && greq.contains("\"json_schema\"") && greq.contains("\"strict\":true")
                && greq.contains("\"role\":\"system\"") && greq.contains("Лихорадка") && !greq.contains("x-api-key"), "grok: request shape");
        String gresp = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":"
                + "\"{\\\"action\\\":\\\"whisper\\\",\\\"speaker\\\":\\\"Null\\\",\\\"line\\\":\\\"я вижу тебя\\\",\\\"rep\\\":\\\"none\\\"}\"}}]}";
        Brain.Decision gd = Brain.parseGrok(gresp);
        check(gd != null && gd.action().equals("whisper") && gd.line().contains("вижу"), "grok: parse " + gd);
        check(Brain.parseGrok("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":null}}]}") == null, "grok: cut-off -> null");
        check(Brain.withIdea(gd).contains("Grok") && Brain.withIdea(gd).contains("я вижу тебя"), "grok: idea passed to claude");
        // длинная реплика режется по границе предложения, а не на полуслове
        String longLine = "MrAIinator… blackout. почему? ты сам написал это. я слышал… экран двоится, посмотри на монитор внимательно";
        String cutLine = Lines.clean(longLine);
        check(cutLine.length() <= Lines.MAX_LINE && cutLine.equals("MrAIinator… blackout. почему? ты сам написал это. я слышал…"), "line: cut at sentence end → «" + cutLine + "»");
        String noStop = Lines.clean("я стою прямо за твоей спиной и смотрю как ты копаешь эту шахту уже очень давно");
        check(noStop.length() <= Lines.MAX_LINE + 1 && noStop.endsWith("…") && !noStop.contains("  ") && noStop.startsWith("я стою прямо за твоей спиной"), "line: no sentence end → cut at a word → «" + noStop + "»");
        check(Lines.clean("положи кирку.").equals("положи кирку."), "line: short line untouched");
        // роли «мозг» и «речь»
        check(gc.brain.equals("claude") && gc.speech.equals("grok"), "roles: default brain=claude, speech=grok");
        check(Brain.provider("grok", true, true).equals("grok") && Brain.provider("claude", true, true).equals("claude"), "roles: both keys → as configured");
        check(Brain.provider("grok", true, false).equals("claude") && Brain.provider("claude", false, true).equals("grok"), "roles: missing key → the other AI takes the role");
        {
            java.nio.file.Path cfgDir = java.nio.file.Files.createTempDirectory("nullai");
            java.nio.file.Files.writeString(cfgDir.resolve("config.properties"),
                    "# мозг\napiKey=sk-secret\nbrain=claude\n# brain=grok\nspeech = grok\ngrokApiKey=xai-secret\n", java.nio.charset.StandardCharsets.UTF_8);
            DirectorConfig.setKey(cfgDir, "brain", "grok");
            DirectorConfig.setKey(cfgDir, "speech", "claude");
            DirectorConfig k = DirectorConfig.load(cfgDir);
            String txt = java.nio.file.Files.readString(cfgDir.resolve("config.properties"), java.nio.charset.StandardCharsets.UTF_8);
            check("grok".equals(k.brain) && "claude".equals(k.speech), "/nullAI: roles saved and reloaded");
            DirectorMod.Watch vw = new DirectorMod.Watch();
            long t0 = 1_000_000L;
            boolean onlyNull = DirectorMod.voices(vw, t0).equals(java.util.List.of("Null"));
            vw.voiceSeen.put(TbsWorld.voiceOf("xxram_2die"), t0);
            boolean feverOk = DirectorMod.voices(vw, t0 + 60_000L).contains("xXram2dieXx") && TbsWorld.voiceOf("fever") == null && !Brain.SPEAKERS.contains("Лихорадка") && !DirectorMod.voices(vw, t0 + 60_000L).contains("DyeXD412");
            boolean gone = !DirectorMod.voices(vw, t0 + DirectorMod.VOICE_WINDOW + 1).contains("xXram2dieXx");
            check(onlyNull && feverOk && gone, "voices: fake players speak only while near (2 min window); Fever is never voiced");
            check(TbsWorld.voiceOf("curved").equals("DyeXD412") && TbsWorld.voiceOf("xxram_2die").equals("xXram2dieXx") && TbsWorld.voiceOf("him") == null,
                    "voices: entity → speaker mapping");
            check("sk-secret".equals(k.apiKey) && "xai-secret".equals(k.grokApiKey) && txt.contains("# brain=grok") && txt.contains("# мозг"),
                    "/nullAI: keys and comments untouched");
        }
        String lreq = Brain.buildGrokRequest(gc, "Игрок: Test" + Brain.speechBrief(gd), true, Brain.lineSchema()).toString();
        check(lreq.contains("\"line\"") && !lreq.contains("\"action\":{") && lreq.contains("Черновик реплики") && lreq.contains("я вижу тебя"), "speech: line-only request with the brain's decision");
        String creq = Brain.buildRequest(gc, "x", true, Brain.lineSchema()).toString();
        check(creq.contains("\"required\":[\"line\"]") && creq.contains("cache_control"), "speech: claude line-only request keeps cached system");
        check("не оборачивайся".equals(Brain.parseLine("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"line\\\":\\\"не оборачивайся\\\"}\"}}]}", true)), "speech: parse grok line");
        check("я здесь".equals(Brain.parseLine("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"},{\"type\":\"text\",\"text\":\"{\\\"line\\\":\\\"я здесь\\\"}\"}]}", false)), "speech: parse claude line after a thinking block");
        check(Brain.parseLine("{\"stop_reason\":\"refusal\",\"content\":[]}", false) == null && Brain.parseLine("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\"}}]}", true) == null, "speech: refusal / cut-off → null");
        {
            java.util.Random mr = new java.util.Random(7);
            boolean range = true;
            for (int i = 0; i < 200; i++) {
                int st = Mimic.actSteps(mr);
                range &= st >= 4 * 45 && st <= 4 * 100;
            }
            check(range, "mimic: pretends to be a player for 45–100 s before attacking");
            Mimic.Chase mc = new Mimic.Chase(null, java.util.UUID.randomUUID());
            double walk = Mimic.blocksPerSecond(Mimic.WALK_ATTR, Mimic.WALK_MOD, false), sprint = Mimic.blocksPerSecond(Mimic.WALK_ATTR, Mimic.RUN_MOD, false);
            check(Math.abs(walk - 4.317) < 0.15 && Math.abs(sprint - 5.612) < 0.15, "mimic: player speed — walk " + String.format("%.2f", walk) + ", sprint " + String.format("%.2f", sprint) + " b/s");
            check(Mimic.REACH == 1.5 && !Mimic.isMimic(null), "mimic: melee reach 1.5 blocks (" + Mimic.REACH + ")");
            check(mc.phase == Mimic.Phase.ACT, "mimic: starts in the acting phase, not hunting");
            check(Mimic.pickTask(0, false) == Mimic.Task.WANDER && Mimic.pickTask(60, false) == Mimic.Task.FOLLOW && Mimic.pickTask(80, false) == Mimic.Task.MINE
                    && Mimic.pickTask(99, false) == Mimic.Task.WANDER && Mimic.pickTask(99, true) == Mimic.Task.TORCH, "mimic: player-like task mix, torch only in the dark");
        }
        {
            // 3.1.0: generated config documents every option in English and Russian, ships no key and no address
            java.nio.file.Path fresh = java.nio.file.Files.createTempDirectory("understudy");
            DirectorConfig fc = DirectorConfig.load(fresh);
            String txt = java.nio.file.Files.readString(fresh.resolve("config.properties"), java.nio.charset.StandardCharsets.UTF_8);
            boolean allKeys = true;
            for (DirectorConfig.Opt o : DirectorConfig.OPTS) {
                allKeys &= txt.contains("\n" + o.key() + "=");
            }
            check(allKeys && txt.contains("Master switch") && txt.contains("Главный выключатель"), "3.1.0 config: every option written, comments EN + RU");
            check(fc.apiKey.isEmpty() && fc.grokApiKey.isEmpty() && fc.publicUrl.isEmpty() && !fc.webArchiveEnabled && !fc.launcherApi
                    && !fc.oldCombat && !txt.contains("xai-"), "3.1.0 config: no keys, no address, no open port, old combat off by default");
            // an old (pre-3.1.0) config of the author's server keeps its web features
            java.nio.file.Path old = java.nio.file.Files.createTempDirectory("understudy-old");
            java.nio.file.Files.writeString(old.resolve("config.properties"), "enabled=true\npublicUrl=http://1.2.3.4:25842\noldCombat=true\n", java.nio.charset.StandardCharsets.UTF_8);
            DirectorConfig oc = DirectorConfig.load(old);
            check(oc.webArchiveEnabled && oc.launcherApi && oc.oldCombat && "http://1.2.3.4:25842".equals(oc.publicUrl), "3.1.0 config: old config migrated, web features kept");
            DirectorConfig again = DirectorConfig.load(old);
            String oldTxt = java.nio.file.Files.readString(old.resolve("config.properties"), java.nio.charset.StandardCharsets.UTF_8);
            check(again.webArchiveEnabled && oldTxt.split("options added by Understudy").length == 2, "3.1.0 config: new options appended once");
        }
        check(NullAiCommand.cleanKey("  \"sk-ant-api03-abcdefghijklmnopqrstuv\" ").equals("sk-ant-api03-abcdefghijklmnopqrstuv")
                && NullAiCommand.looksValid("claude", "sk-ant-api03-abcdefghijklmnopqrstuv") && !NullAiCommand.looksValid("claude", "xai-abcdefghijklmnopqrstuvwxyz")
                && NullAiCommand.looksValid("grok", "xai-abcdefghijklmnopqrstuvwxyz") && !NullAiCommand.looksValid("grok", "hello"), "/nullAI key: cleans and recognises keys");
        String masked = NullAiCommand.mask("sk-ant-api03-SECRETSECRETSECRET-wxyz");
        check(masked.equals("sk-ant-…wxyz") && !masked.contains("SECRET") && NullAiCommand.mask("").equals("—"), "/nullAI key: never shows the key, only " + masked);
        check(NullAiCommand.keyField("grok").equals("grokApiKey") && NullAiCommand.keyField("claude").equals("apiKey"), "/nullAI key: writes the right config field");
        {
            // любые ИИ: свой OpenAI-совместимый
            DirectorConfig ac = new DirectorConfig();
            check(!Brain.anyAi(ac) && Brain.provider("custom", ac).equals("custom"), "any AI: nothing set up → no AI");
            ac.customUrl = "http://localhost:11434/v1"; ac.customModel = "llama3.1"; ac.customName = "Ollama";
            check(Brain.has(ac, "custom") && Brain.provider("claude", ac).equals("custom") && Brain.label(ac, "custom").equals("Ollama"), "any AI: local model needs no key and takes free roles");
            ac.customUrl = "https://api.deepseek.com/v1";
            check(!Brain.has(ac, "custom"), "any AI: remote service needs a key");
            ac.customApiKey = "sk-deepseek-1234567890";
            check(Brain.has(ac, "custom") && Brain.chatUrl("https://api.deepseek.com/v1/").equals("https://api.deepseek.com/v1/chat/completions")
                    && Brain.chatUrl("https://x.y/v1/chat/completions").equals("https://x.y/v1/chat/completions"), "any AI: chat/completions address");
            String strict = Brain.buildCompatRequest(ac, Brain.compat(ac, "custom"), "s", true, Brain.lineSchema(), true).toString();
            String loose = Brain.buildCompatRequest(ac, Brain.compat(ac, "custom"), "s", true, Brain.lineSchema(), false).toString();
            check(strict.contains("json_schema") && loose.contains("json_object") && loose.contains("JSON Schema") && strict.contains("\"max_tokens\""), "any AI: strict schema, plain-JSON fallback");
            ac.customUrl = "https://api.openai.com/v1";
            check(Brain.buildCompatRequest(ac, Brain.compat(ac, "custom"), "s", true, Brain.lineSchema(), true).toString().contains("max_completion_tokens"), "any AI: OpenAI uses max_completion_tokens");
            check(Brain.extractJson("<think>hm {no}</think>Sure!\n```json\n{\"line\":\"я здесь\"}\n```").equals("{\"line\":\"я здесь\"}")
                    && "я здесь".equals(Brain.lineOf("```json\n{\"line\":\"я здесь\"}\n```")), "any AI: JSON pulled out of think tags and markdown");
            check(Brain.rejectsSchema("{\"error\":{\"message\":\"response_format json_schema is not supported\"}}") && !Brain.rejectsSchema("{\"error\":\"invalid api key\"}"), "any AI: detects missing schema support");
            check(NullAiCommand.preset("ollama").keyFrom().isEmpty() && NullAiCommand.preset("OpenRouter").url().startsWith("https://openrouter.ai") && NullAiCommand.preset("nope") == null
                    && NullAiCommand.keyField("custom").equals("customApiKey") && NullAiCommand.looksValid("custom", "abcdefgh"), "any AI: presets and custom key");
            java.nio.file.Path cd = java.nio.file.Files.createTempDirectory("understudy-custom");
            DirectorConfig.load(cd);
            DirectorConfig.setKey(cd, "brain", "custom");
            check(DirectorConfig.load(cd).brain.equals("custom"), "any AI: custom role saved");
            check(NullAiCommand.modelField("claude").equals("model") && NullAiCommand.modelField("grok").equals("grokModel") && NullAiCommand.modelField("custom").equals("customModel"), "/nullAI model: right config field for every AI");
            String guide = java.nio.file.Files.readString(cd.resolve("AI-SETUP.txt"), java.nio.charset.StandardCharsets.UTF_8);
            check(guide.contains("/nullAI key claude") && guide.contains("customUrl=") && guide.contains("КАК ПОДКЛЮЧИТЬ ИИ"), "AI-SETUP.txt written next to the config (EN + RU)");
        }
        {
            check(!Echo.copyAllowed(Echo.CLAN_VOID) && Echo.copyAllowed(net.minecraft.world.level.Level.OVERWORLD) && Echo.copyAllowed(net.minecraft.world.level.Level.NETHER),
                    "echo: never copies inside The Broken Script dimensions (clan_void etc.)");
            java.nio.file.Path echoDir = java.nio.file.Files.createTempDirectory("understudy-echo");
            java.nio.file.Files.createDirectories(echoDir.resolve("echoes"));
            java.nio.file.Files.writeString(echoDir.resolve("echoes").resolve("a1.dat"), "x");
            java.nio.file.Files.writeString(echoDir.resolve("echoes").resolve("b2.dat"), "x");
            java.nio.file.Files.writeString(echoDir.resolve("echoes").resolve("keep.txt"), "x");
            check(Echo.libraryCount(echoDir) == 2 && Echo.clearLibrary(echoDir) == 2 && Echo.libraryCount(echoDir) == 0
                    && java.nio.file.Files.exists(echoDir.resolve("echoes").resolve("keep.txt")), "echo: /null echoes clear deletes only saved copies");
        }
        {
            java.nio.file.Path wd = java.nio.file.Files.createTempDirectory("understudy-built");
            PlayerBuilt.load(wd);
            PlayerBuilt.add("minecraft:overworld", 42L);
            PlayerBuilt.add("minecraft:overworld", 43L);
            PlayerBuilt.add("thebrokenscript:clan_void", 7L);
            PlayerBuilt.remove("minecraft:overworld", 43L);
            PlayerBuilt.close();
            PlayerBuilt.load(wd);
            check(PlayerBuilt.has("minecraft:overworld", 42L) && !PlayerBuilt.has("minecraft:overworld", 43L)
                    && PlayerBuilt.has("thebrokenscript:clan_void", 7L) && !PlayerBuilt.has("minecraft:the_nether", 42L)
                    && PlayerBuilt.count("minecraft:overworld") == 1, "player-built blocks: tracked per dimension, removed on break, survive restart");
            PlayerBuilt.close();
        }
        check(L.ru("uk_ua") && L.ru("BE_BY") && L.ru("kk_kz") && L.ru("uz_uz") && L.ru("ky_kg") && L.ru("ru_ru") && L.ru("rpr")
                && !L.ru("en_us") && !L.ru("pt_br") && !L.ru("es_es") && !L.ru("pl_pl") && !L.ru("de_de") && !L.ru((String) null), "lang: Russian for the CIS languages, English for everyone else");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
