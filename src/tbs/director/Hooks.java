package tbs.director;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;

/** Связь миксина с режиссёром: когда TBS последний раз писал игроку в чат. */
public final class Hooks {
    static final Map<UUID, Long> TBS_SPOKE = new ConcurrentHashMap<>();
    /** Пока режиссёр сам отправляет сообщение — не считаем его за TBS. */
    static final ThreadLocal<Boolean> SELF = ThreadLocal.withInitial(() -> false);

    private Hooks() {}

    /** true — сообщение надо не отправлять (Null никогда не «выходит»). */
    public static boolean outgoing(ServerPlayer p, Component msg) {
        if (p == null || msg == null || SELF.get()) {
            return false;
        }
        if (isTbs(msg)) {
            TBS_SPOKE.put(p.getUUID(), System.currentTimeMillis());
        }
        return false;
    }

    static boolean isNullLeave(Component msg) {
        if (msg.getContents() instanceof TranslatableContents t && "multiplayer.player.left".equals(t.getKey()) && t.getArgs().length > 0) {
            Object a = t.getArgs()[0];
            String who = a instanceof Component c ? c.getString() : String.valueOf(a);
            return who.trim().equalsIgnoreCase("null");
        }
        String s = msg.getString().trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("null left the game") || s.equals("null покинул игру");
    }

    static boolean isTbs(Component msg) {
        if (keyOf(msg)) {
            return true;
        }
        for (Component sib : msg.getSiblings()) {
            if (keyOf(sib)) {
                return true;
            }
        }
        String s = msg.getString();
        return s.startsWith("<Null>") || s.startsWith("<Нулл>") || s.startsWith("<null>") || s.startsWith("<Лихорадка>")
                || s.startsWith("<Fever>") || s.contains("xXram2dieXx") || s.contains("DyeXD412") || s.startsWith("err.");
    }

    private static boolean keyOf(Component c) {
        if (c.getContents() instanceof TranslatableContents t) {
            String k = t.getKey();
            if (k.contains("thebrokenscript")) {
                return true;
            }
            for (Object arg : t.getArgs()) {
                if (arg instanceof Component a && keyOf(a)) {
                    return true;
                }
            }
        }
        return false;
    }

    static long lastTbsSpoke(UUID id) {
        return TBS_SPOKE.getOrDefault(id, 0L);
    }
}
