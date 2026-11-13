package tbs.director;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Все действия режиссёра. Уровни: SOFT — звуки и слова; WORLD — трогает мир, но обратимо
 * или по мелочи; HARD — урон, охотники, вещи в сундуке; EXTREME — разрушения (по умолчанию выкл).
 * «tbs:имя» — родные события мода The Broken Script, вызываются через его реестр.
 */
public final class Catalog {
    enum Tier { SOFT, WORLD, HARD, EXTREME }

    record Act(String id, Tier tier, String desc) {}

    static final Map<String, Act> ALL = new LinkedHashMap<>();

    private static void add(String id, Tier t, String desc) {
        ALL.put(id, new Act(id, t, desc));
    }

    static {
        // слова и звуки (датапак tbs_fear)
        add("whisper", Tier.SOFT, "только реплика");
        add("fake_join", Tier.SOFT, "Null заходит и выходит из игры");
        add("footsteps", Tier.SOFT, "шаги сзади, всё ближе");
        add("knock", Tier.SOFT, "стук за спиной");
        add("darkness", Tier.SOFT, "темнота и сердцебиение");
        add("help_us", Tier.SOFT, "надпись HELP US на экран");
        add("cave", Tier.SOFT, "звук пещеры");
        add("thunder", Tier.SOFT, "далёкий гром");
        add("self_message", Tier.SOFT, "сообщение в чат от имени самого игрока");
        add("advancement", Tier.SOFT, "жуткое «достижение»");
        // мир (свои)
        add("torches_out", Tier.WORLD, "гаснут факелы вокруг игрока");
        add("chest_open", Tier.WORLD, "ближайший сундук сам открывается и закрывается");
        add("look_behind", Tier.WORLD, "игрока разворачивает лицом назад");
        add("sign", Tier.WORLD, "за спиной появляется табличка с твоей репликой (line)");
        add("glass_break", Tier.WORLD, "рядом лопается стекло");
        add("private_vision", Tier.WORLD, "странное видит только этот игрок (гаснут факелы, открывается дверь, мелькает фигура) — друзья рядом ничего не замечают");
        add("door_creak", Tier.WORLD, "дверь рядом сама приоткрывается со скрипом");
        add("echo_build", Tier.WORLD, "мир забирает копию постройки игрока и уносит её далеко (или в измерение TBS); скажи об этом в line");
        add("wither_burst", Tier.WORLD, "природа вокруг игрока умирает у него на глазах: трава чернеет, листья осыпаются, пепел");
        // мир (события TBS)
        add("tbs:behind_you", Tier.WORLD, "событие TBS «Behind You»");
        add("tbs:door", Tier.WORLD, "дверь рядом ведёт себя странно");
        add("tbs:doors", Tier.WORLD, "двери вокруг хлопают");
        add("tbs:push", Tier.WORLD, "что-то толкает игрока");
        add("tbs:stare_at_player", Tier.WORLD, "кто-то стоит и смотрит на игрока");
        add("tbs:null_is_near", Tier.WORLD, "Null рядом");
        add("tbs:null_scare", Tier.WORLD, "Null выскакивает");
        add("tbs:null_title", Tier.WORLD, "надпись Null на экране");
        add("tbs:psst_event", Tier.WORLD, "«псс» из ниоткуда");
        add("tbs:eyes", Tier.WORLD, "глаза в темноте");
        add("tbs:hallucination", Tier.WORLD, "галлюцинация");
        add("tbs:strike_lightning", Tier.WORLD, "молния рядом");
        add("tbs:place_redstone_torch", Tier.WORLD, "рядом появляется красный факел");
        add("tbs:obfuscated_sign", Tier.WORLD, "табличка с нечитаемым текстом");
        add("tbs:place_hello", Tier.WORLD, "надпись «hello» в мире");
        add("tbs:cursed_book", Tier.WORLD, "проклятая книга");
        add("tbs:null_invade_base", Tier.WORLD, "Null вторгается на базу");
        add("tbs:bsod", Tier.WORLD, "фальшивый синий экран");
        add("tbs:close_menu", Tier.WORLD, "закрывает игроку меню");
        add("tbs:aberration", Tier.WORLD, "искажение цвета экрана");
        add("tbs:gamma", Tier.WORLD, "сбой яркости");
        add("tbs:heartbeat", Tier.WORLD, "сердцебиение TBS");
        add("tbs:breathe", Tier.WORLD, "дыхание рядом");
        add("tbs:reset_rotation", Tier.WORLD, "сбрасывает взгляд игрока");
        add("tbs:run", Tier.WORLD, "«беги»");
        add("tbs:shadow_bug", Tier.WORLD, "баг с тенями");
        add("tbs:false_villager", Tier.WORLD, "фальшивый житель");
        add("tbs:screen_dupe", Tier.WORLD, "экран двоится");
        // жёстко
        add("hurt", Tier.HARD, "невидимый удар: немного урона (не убивает)");
        add("chest_shuffle", Tier.HARD, "в ближайшем сундуке вещи переложены по-другому (ничего не пропадает)");
        add("summon_hunter", Tier.HARD, "в 25 блоках позади появляется преследователь TBS");
        add("mimic_raid", Tier.HARD, "заходят игроки, которых нет в сети (мимики с их скином и ником): сначала ведут себя как обычные игроки — ходят, копают, пишут в чат, — потом нападают");
        add("summon_watcher", Tier.HARD, "вдали появляется наблюдатель TBS");
        add("scream_flash", Tier.WORLD, "скример: фигура TBS на миг возникает прямо перед лицом и исчезает со звуком");
        add("scream_watch", Tier.WORLD, "за спиной стоит фигура и смотрит; обернётся — исчезнет со страшным звуком (хорошо с line «не оборачивайся»)");
        add("tbs:damage", Tier.HARD, "событие TBS «Damage»");
        add("tbs:look_and_damage", Tier.HARD, "посмотрел — получил урон");
        add("tbs:set_on_fire", Tier.HARD, "игрок вспыхивает");
        add("tbs:hungry", Tier.HARD, "резкий голод");
        add("tbs:place_flowing_water", Tier.HARD, "вода из ниоткуда");
        add("tbs:place_netherrack", Tier.HARD, "незерак появляется в мире");
        // экстрим
        add("tbs:explode_base", Tier.EXTREME, "взрыв на базе игрока");
        add("tbs:inventory_corruption", Tier.EXTREME, "порча инвентаря");
        add("tbs:lava_cast", Tier.EXTREME, "лава");
        add("tbs:place_lava", Tier.EXTREME, "лава рядом");
    }

    static boolean allowed(Act a, DirectorConfig c) {
        return switch (a.tier()) {
            case SOFT -> true;
            case WORLD -> c.allowWorld;
            case HARD -> c.allowHard;
            case EXTREME -> c.allowExtreme;
        };
    }

    static List<String> enabledIds(DirectorConfig c) {
        List<String> out = new ArrayList<>();
        ALL.values().stream().filter(a -> allowed(a, c)).forEach(a -> out.add(a.id()));
        return out;
    }

    static String menu(DirectorConfig c) {
        StringBuilder b = new StringBuilder("Доступные действия (action):\n");
        for (Act a : ALL.values()) {
            if (allowed(a, c)) {
                b.append("- ").append(a.id()).append(" — ").append(a.desc());
                if (a.tier() == Tier.HARD || a.tier() == Tier.EXTREME) {
                    b.append(" [жёсткое: не чаще раза в несколько минут, когда игрок уже напуган]");
                }
                b.append('\n');
            }
        }
        return b.toString();
    }
}
