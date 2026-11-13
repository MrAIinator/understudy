package dev.mrainator.tbsecho;

import java.util.Locale;
import net.minecraft.server.level.ServerPlayer;

/** Язык игрока для текстов сюжета: русский для ru_*, uk_ua, be_by, kk_kz; остальным — английский. */
final class Lang {
    private Lang() {}

    static boolean ru(String lang) {
        if (lang == null) return false;
        String l = lang.trim().toLowerCase(Locale.ROOT);
        return l.startsWith("ru") || l.equals("uk_ua") || l.equals("be_by") || l.equals("kk_kz");
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
