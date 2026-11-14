package dev.mrainator.tbsecho;

import java.util.Locale;
import net.minecraft.server.level.ServerPlayer;

/** Язык игрока для текстов сюжета: русский для русскоязычных стран (RU_LOCALES); остальным — английский. */
final class Lang {
    private Lang() {}

    /**
     * Языки, чьи носители в большинстве читают по-русски: русский (и дореформенный), украинский, белорусский,
     * казахский, узбекский, киргизский, таджикский, туркменский, армянский, азербайджанский, грузинский,
     * татарский, башкирский, чувашский, якутский, удмуртский. Всем остальным — английский.
     */
    static final java.util.Set<String> RU_LOCALES = java.util.Set.of("ru_ru", "rpr", "uk_ua", "be_by", "be_latn", "kk_kz", "uz_uz", "ky_kg",
            "tg_tj", "tk_tm", "hy_am", "az_az", "ka_ge", "tt_ru", "ba_ru", "cv_ru", "sah_sah", "udm_ru", "os_ru", "ce_ru");

    static boolean ru(String lang) {
        if (lang == null) return false;
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

    static String tr(ServerPlayer p, String ru, String en) {
        return ru(p) ? ru : en;
    }
}
