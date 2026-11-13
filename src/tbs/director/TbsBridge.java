package tbs.director;

import java.lang.reflect.Method;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Запуск родных событий The Broken Script через его реестр BCRegistries.EVENT (рефлексией). */
public final class TbsBridge {
    private static Registry<?> events;
    private static boolean tried;

    private static synchronized Registry<?> registry() {
        if (!tried) {
            tried = true;
            try {
                Class<?> c = Class.forName("net.thebrokenscript.brokencore.api.registry.BCRegistries");
                events = (Registry<?>) c.getField("EVENT").get(null);
            } catch (Throwable t) {
                DirectorMod.LOG.warn("[understudy] события The Broken Script недоступны: {}", t.toString());
            }
        }
        return events;
    }

    private static long arrivedCheckedAt;
    private static boolean arrivedCache;

    /** Пришёл ли Null на сервер (переменные мира TBS: isNullHere или hasNullSpawned). Кэш 10 с. Без TBS — true. */
    static boolean nullArrived(net.minecraft.server.MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - arrivedCheckedAt < 10_000L) {
            return arrivedCache;
        }
        arrivedCheckedAt = now;
        try {
            Class<?> ext = Class.forName("net.thebrokenscript.api.ext.LevelExt");
            Object inst = ext.getField("INSTANCE").get(null);
            Object vars = ext.getMethod("getVars", net.minecraft.world.level.LevelAccessor.class).invoke(inst, server.overworld());
            boolean here = (boolean) vars.getClass().getMethod("isNullHere").invoke(vars);
            boolean spawned = (boolean) vars.getClass().getMethod("getHasNullSpawned").invoke(vars);
            arrivedCache = here || spawned;
        } catch (Throwable t) {
            arrivedCache = true; // не смогли прочитать — не блокируем режиссёра
        }
        return arrivedCache;
    }

    /** Возвращает флаг «Null на сервере» через LevelExt.updateVars (TBS сам сохранит и разошлёт игрокам). */
    private static void keepNullHere(Class<?> ext, Object inst, net.minecraft.server.MinecraftServer server) {
        try {
            Class<?> fn = Class.forName("kotlin.jvm.functions.Function1");
            Object unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
            Object setter = java.lang.reflect.Proxy.newProxyInstance(fn.getClassLoader(), new Class<?>[] {fn}, (proxy, m, args) -> {
                if ("invoke".equals(m.getName()) && args != null && args.length == 1) {
                    args[0].getClass().getMethod("setNullHere", boolean.class).invoke(args[0], true);
                    return unit;
                }
                return m.getName().equals("hashCode") ? 0 : m.getName().equals("equals") ? (Object) false : "keepNullHere";
            });
            ext.getMethod("updateVars", net.minecraft.world.level.LevelAccessor.class, fn).invoke(inst, server.overworld(), setter);
            DirectorMod.LOG.info("[understudy] Null хотел уйти — остаётся на сервере");
        } catch (Throwable t) {
            DirectorMod.LOG.warn("[understudy] не удержал Null: {}", t.toString());
        }
    }

    /** Репутация игрока у сущностей TBS (0–100; ≤25 BAD, ≤75 NORMAL, >75 GOOD) или -1, если не прочиталась. */
    static int reputation(ServerPlayer p) {
        try {
            Class<?> ext = Class.forName("net.thebrokenscript.api.ext.PlayerExt");
            Object inst = ext.getField("INSTANCE").get(null);
            Object vars = ext.getMethod("getVars", net.minecraft.world.entity.player.Player.class).invoke(inst, p);
            return (int) vars.getClass().getMethod("getEntityReputation").invoke(vars);
        } catch (Throwable t) {
            return -1;
        }
    }

    static String repTier(int rep) {
        return rep < 0 ? "?" : rep <= 25 ? "BAD" : rep <= 75 ? "NORMAL" : "GOOD";
    }

    /** Меняет репутацию родным механизмом TBS: gain=true — GAIN_MINOR, иначе LOSS_MINOR. */
    static boolean changeRep(ServerPlayer p, boolean gain) {
        try {
            Class<?> tier = Class.forName("net.thebrokenscript.util.RepTier");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object t = Enum.valueOf((Class) tier, gain ? "GAIN_MINOR" : "LOSS_MINOR");
            Class.forName("net.thebrokenscript.util.RepUtilKt")
                    .getMethod("applyRep", net.minecraft.world.entity.player.Player.class, tier).invoke(null, p, t);
            return true;
        } catch (Throwable t) {
            DirectorMod.LOG.warn("[understudy] репутация не изменилась: {}", t.toString());
            return false;
        }
    }

    /** true, если событие нашлось, разрешено самим TBS сейчас и запущено. */
    static boolean run(String name, ServerPlayer p) {
        Registry<?> reg = registry();
        if (reg == null) {
            return false;
        }
        try {
            Object ev = reg.get(ResourceLocation.fromNamespaceAndPath(TbsWorld.NS, name));
            if (ev == null) {
                return false;
            }
            ServerLevel level = p.serverLevel();
            Vec3 pos = p.position();
            Method can = ev.getClass().getMethod("canExecute", ServerLevel.class, ServerPlayer.class, Vec3.class);
            if (!(boolean) can.invoke(ev, level, p, pos)) {
                return false;
            }
            Method run = ev.getClass().getMethod("run", ServerLevel.class, ServerPlayer.class, Vec3.class);
            run.invoke(ev, level, p, pos);
            return true;
        } catch (Throwable t) {
            DirectorMod.LOG.warn("[understudy] событие TBS {} упало: {}", name, t.toString());
            return false;
        }
    }
}
