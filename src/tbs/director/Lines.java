package tbs.director;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** Запасные реплики и выбор действия без ИИ — по ситуации игрока. Реплики — на языке игрока (s.ru). */
public final class Lines {
    private static final Pattern URL = Pattern.compile("(?i)(https?://|www\\.)\\S+");

    static String clean(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace('§', ' ').replaceAll("\\p{Cntrl}", " ");
        s = URL.matcher(s).replaceAll("");
        s = s.trim().replaceAll("\\s+", " ");
        return shorten(s, MAX_LINE);
    }

    /** Предел длины реплики в чате. Длиннее — режем по границе предложения, а не на полуслове. */
    static final int MAX_LINE = 60;

    static String shorten(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int cut = -1;
        for (int i = max - 1; i >= 8; i--) {
            if (".?!…".indexOf(s.charAt(i)) >= 0) {
                cut = i + 1; // последняя законченная фраза, которая помещается
                break;
            }
        }
        if (cut < 0) {
            cut = s.lastIndexOf(' ', max); // нет законченной фразы — хотя бы по целому слову
            if (cut < 8) {
                cut = max;
            }
            return s.substring(0, cut).replaceAll("[\\s,;:—–-]+$", "") + "…";
        }
        return s.substring(0, cut).trim();
    }

    private static String pick(List<String> list) {
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    private static String pick(boolean ru, List<String> r, List<String> e) {
        return L.pick(ru, r, e);
    }

    static String echoLine(boolean ru) {
        return pick(ru,
                List.of("я забрал кусочек твоего дома. он теперь у меня", "твой дом стоит и в другом месте. там холоднее",
                        "мир забывает тебя по частям", "я сохранил то, что ты построил. там, где тебя нет"),
                List.of("i took a piece of your home. it's mine now", "your house stands somewhere else too. it's colder there",
                        "the world is forgetting you piece by piece", "i kept what you built. somewhere you're not"));
    }

    /** Действие в мире под ситуацию — когда шёпота уже было достаточно. */
    static String worldPick(Situation s) {
        if (s.dark || s.underground) {
            return pick(List.of("torches_out", "look_behind", "tbs:eyes", "tbs:breathe", "tbs:behind_you", "summon_watcher", "scream_watch", "scream_flash"));
        }
        if (s.placed >= 10) {
            return pick(List.of("chest_open", "sign", "tbs:door", "tbs:doors", "glass_break"));
        }
        if ("BAD".equals(s.repTier())) {
            return pick(List.of("hurt", "summon_hunter", "tbs:look_and_damage", "tbs:push"));
        }
        return pick(List.of("look_behind", "tbs:behind_you", "tbs:null_is_near", "tbs:psst_event", "tbs:stare_at_player", "summon_watcher", "chest_open", "wither_burst", "wither_burst", "door_creak", "echo_build", "scream_watch", "scream_flash"));
    }

    static Brain.Decision fallback(Situation s) {
        Brain.Decision d = fallbackRaw(s);
        for (int i = 0; i < 4 && !d.line().isEmpty() && s.pastLines.contains(d.line()); i++) {
            d = fallbackRaw(s);
        }
        return s.pastLines.contains(d.line()) ? new Brain.Decision(d.action(), "") : d;
    }

    private static Brain.Decision fallbackRaw(Situation s) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        boolean ru = s.ru;
        if (!s.tbsEvents.isEmpty()) {
            return new Brain.Decision("whisper", pick(ru,
                    List.of("ты видел его?", "он ещё здесь", "не смотри туда снова", "он запомнил тебя", "это был не я"),
                    List.of("did you see him?", "he's still here", "don't look over there again", "he remembers you now", "that wasn't me")));
        }
        if (s.chat != null) {
            return new Brain.Decision("whisper", pick(ru,
                    List.of("я слышал тебя", "не зови меня", "я уже здесь", "тише. он услышит", "ты сам меня позвал"),
                    List.of("i heard you", "don't call me", "i'm already here", "quiet. he'll hear you", "you called me yourself")));
        }
        if (s.recentlyDied) {
            return new Brain.Decision("whisper", pick(ru,
                    List.of("я предупреждал", "ещё раз?", "ты оставил там вещи. я их видел"),
                    List.of("i warned you", "again?", "you left your things back there. i saw them")));
        }
        if ("BAD".equals(s.repTier()) && r.nextInt(3) == 0) {
            return new Brain.Decision(pick(List.of("hurt", "summon_watcher", "tbs:look_and_damage", "torches_out")),
                    pick(ru, List.of("ты сам виноват", "я помню, что ты сказал", ""),
                            List.of("you brought this on yourself", "i remember what you said", "")));
        }
        if ((s.dark || s.underground) && r.nextInt(3) == 0) {
            return new Brain.Decision(pick(List.of("torches_out", "look_behind", "tbs:eyes", "tbs:breathe")), "");
        }
        if (s.placed >= 15 && r.nextBoolean()) {
            return new Brain.Decision(pick(List.of("chest_open", "sign", "tbs:door")), pick(ru,
                    List.of("красивый дом", "я зайду ночью", "оставь дверь открытой"),
                    List.of("nice house", "i'll come by tonight", "leave the door open")));
        }
        if (s.afkSeconds >= 120) {
            return new Brain.Decision(r.nextBoolean() ? "footsteps" : "whisper", pick(ru,
                    List.of("почему ты не двигаешься?", "ты ещё здесь?", "я подожду", "не оборачивайся… я сказал НЕ",
                            "мне нравится, когда ты так стоишь. удобно", "ты замер. правильно. он ищет по движению"),
                    List.of("why aren't you moving?", "are you still there?", "i can wait", "don't turn around… i said DON'T",
                            "i like it when you stand still like that. easier", "you froze. good. he hunts by movement")));
        }
        if (s.underground || s.dark) {
            String a = pick(List.of("footsteps", "cave", "darkness", "knock"));
            return new Brain.Decision(a, r.nextInt(3) == 0 ? "" : pick(ru,
                    List.of("ты слишком глубоко", "здесь кто-то копал до тебя", "не гаси факел", "я под тобой",
                            "копай. я как раз с той стороны", "слышишь? это не эхо. это я стучу в ответ"),
                    List.of("you're too deep", "someone dug here before you", "don't let the torch go out", "i'm under you",
                            "keep digging. i'm right on the other side", "hear that? not an echo. it's me knocking back")));
        }
        if (s.mined >= 25) {
            return new Brain.Decision("whisper", pick(ru,
                    List.of("хватит копать", "ты разбудишь его", "под тобой пусто"),
                    List.of("stop digging", "you'll wake him", "there's nothing under you")));
        }
        if (s.placed >= 25) {
            return new Brain.Decision("knock", pick(ru,
                    List.of("красивый дом. я зайду ночью", "оставь дверь открытой", "", "я уже был внутри. ты переставил сундук", "кровать у окна… плохая идея"),
                    List.of("nice house. i'll come in tonight", "leave the door open", "", "i've been inside already. you moved the chest", "bed by the window… bad idea")));
        }
        if (s.night) {
            return new Brain.Decision(pick(List.of("help_us", "thunder", "fake_join", "self_message", "advancement")), "");
        }
        return new Brain.Decision(pick(Brain.ACTIONS), "");
    }
}
