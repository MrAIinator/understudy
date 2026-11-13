package tbs.director;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;

/** Что режиссёр знает о сущностях мода The Broken Script. */
public final class TbsWorld {
    static final String NS = "thebrokenscript";
    private static final Properties NAMES = new Properties();

    // снаряды, служебные и «невидимые» сущности — не события для игрока
    private static final Set<String> IGNORE = Set.of(
            "chord_projectile", "integ_fireball", "name_tag", "rock", "murderfur_spawner",
            "chunk_remover", "null_cod", "command_block_giver", "tether");

    /** Сущности, которые сами разговаривают с игроком в чате: не перебиваем их. */
    static final Set<String> TALKERS = Set.of("fever", "fever_stalk", "xxram_2die", "curved");

    static {
        try (InputStream in = TbsWorld.class.getResourceAsStream("/tbs_names.properties")) {
            if (in != null) {
                NAMES.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // без таблицы будут английские id
        }
    }

    /** Чьим голосом может говорить режиссёр, когда эта сущность рядом. null — сущность немая. */
    static String voiceOf(String id) {
        return switch (id) {
            case "xxram_2die" -> "xXram2dieXx";
            case "curved" -> "DyeXD412";
            default -> null;
        };
    }

    static boolean relevant(String id) {
        return id != null && !IGNORE.contains(id);
    }

    static String name(String id) {
        return NAMES.getProperty(id, id);
    }

    /** Краткая справка о персонажах для ИИ — своими словами, без текста мода. */
    static final String CAST = """
            Персонажи этого мира (мод The Broken Script), их можно упоминать и на них намекать:
            - Null (Нулл) — сломанная сущность-ошибка; говорит обрывками, сбоями, иногда как системное сообщение; следит издалека.
            - Лихорадка (Fever) — вежливая, спокойная, называет себя Спасителем, говорит возвышенно и пугающе-ласково, задаёт странные вопросы.
            - Он (Him), r2/Siluet, Схема (Circuit), Integrity, Сломанный Конец, Наблюдатель Ничто — молчаливые преследователи и аномалии.
            - «Пропавшие игроки» xXram2dieXx и DyeXD412 — фальшивые игроки: внезапно заходят и выходят из игры, пишут в чат как обычные люди, но что-то с ними не так.
            Голос (speaker): Null — шёпот; xXram2dieXx, DyeXD412 — реплика в чат от их имени в формате мода. За Лихорадку не говори никогда: она говорит сама, её можно только упоминать.
            Если в описании есть недавнее появление сущности — продолжай эту историю (например, спроси, видел ли он её), а не начинай новую.""";
}
