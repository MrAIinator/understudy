package tbs.director;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Язык игрока. Русский — для языков из RU_LOCALES; иначе английский.
 * Язык берётся из настроек клиента (ServerPlayer.clientInformation().language()).
 */
final class L {
    private L() {}

    /**
     * Языки, чьи носители в большинстве читают по-русски: русский (и дореформенный), украинский, белорусский,
     * казахский, узбекский, киргизский, таджикский, туркменский, армянский, азербайджанский, грузинский,
     * татарский, башкирский, чувашский, якутский, удмуртский. Всем остальным — английский.
     */
    static final java.util.Set<String> RU_LOCALES = java.util.Set.of("ru_ru", "rpr", "uk_ua", "be_by", "be_latn", "kk_kz", "uz_uz", "ky_kg",
            "tg_tj", "tk_tm", "hy_am", "az_az", "ka_ge", "tt_ru", "ba_ru", "cv_ru", "sah_sah", "udm_ru", "os_ru", "ce_ru");

    static boolean ru(String lang) {
        if (lang == null) {
            return false;
        }
        String l = lang.trim().toLowerCase(Locale.ROOT);
        return RU_LOCALES.contains(l) || l.startsWith("ru_") || l.startsWith("rpr");
    }

    static boolean ru(ServerPlayer p) {
        try {
            return p != null && ru(p.clientInformation().language());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Команды: игрок — по его языку; консоль/RCON — по-русски (как логи сервера). */
    static boolean ru(CommandSourceStack src) {
        ServerPlayer p = src == null ? null : src.getPlayer();
        return p == null || ru(p);
    }

    static String tr(boolean ru, String r, String e) {
        return ru ? r : e;
    }

    static String tr(ServerPlayer p, String r, String e) {
        return ru(p) ? r : e;
    }

    static String tr(CommandSourceStack src, String r, String e) {
        return ru(src) ? r : e;
    }

    /** Одинаковый случайный индекс в русском и английском списке (списки одной длины). */
    static String pick(boolean ru, List<String> r, List<String> e) {
        int i = ThreadLocalRandom.current().nextInt(r.size());
        return ru || i >= e.size() ? r.get(i) : e.get(i);
    }

    /** Имя голоса в чате: внутри — как у ИИ («Лихорадка»), английскому игроку — как в английском TBS. */
    static String speaker(String sp, boolean ru) {
        if (ru || sp == null) {
            return sp;
        }
        return "Лихорадка".equals(sp) ? "Fever" : sp;
    }

    /** Сообщение всем, каждому — на его языке. */
    static void each(MinecraftServer server, Function<ServerPlayer, Component> msg) {
        Hooks.SELF.set(true);
        try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(msg.apply(p));
            }
        } finally {
            Hooks.SELF.set(false);
        }
    }
}
