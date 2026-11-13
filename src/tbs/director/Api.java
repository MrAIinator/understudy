package tbs.director;

import net.minecraft.server.level.ServerPlayer;

/** Узкий публичный вход для сюжетного модуля «Протокол ЭХО» (он живёт в другом пакете). */
public final class Api {
    private Api() {}

    /** Фигура встаёт за спиной и исчезает, когда на неё посмотрят. */
    public static boolean watch(ServerPlayer p) {
        return Scream.watch(p.server, p);
    }

    /** Фигура на мгновение появляется перед лицом. */
    public static boolean flash(ServerPlayer p) {
        return Scream.flash(p.server, p);
    }

    /** Сообщение от самого режиссёра: не считать его репликой The Broken Script. */
    public static void self(Runnable send) {
        Hooks.SELF.set(true);
        try {
            send.run();
        } finally {
            Hooks.SELF.set(false);
        }
    }

    /** Игрок исключён из запугивания админом (/tag ... tbs_nofear). */
    public static boolean exempt(ServerPlayer p) {
        return p.getTags().contains("tbs_nofear");
    }
}
