package tbs.director;

import java.util.ArrayList;
import java.util.List;

/**
 * Настоящая отложенная задача «через N тиков». (TickTask из ванилы не ждёт: сервер выполняет
 * его почти сразу — из-за этого мимики исчезали в момент появления.) Крутится в серверном тике.
 */
public final class Sched {
    record Later(int delayTicks, Runnable task) {}

    private record Job(long due, Runnable task) {}

    private static final List<Job> JOBS = new ArrayList<>();
    private static long now;

    private Sched() {}

    /** Вызывать только из серверного потока. */
    static synchronized void tell(Later l) {
        JOBS.add(new Job(now + Math.max(1, l.delayTicks()), l.task()));
    }

    /** Каждый серверный тик. */
    static void tick() {
        List<Runnable> due = new ArrayList<>();
        synchronized (Sched.class) {
            now++;
            JOBS.removeIf(j -> {
                if (j.due() <= now) {
                    due.add(j.task());
                    return true;
                }
                return false;
            });
        }
        for (Runnable r : due) {
            try {
                r.run();
            } catch (Throwable t) {
                DirectorMod.LOG.warn("[understudy] отложенная задача упала: {}", t.toString());
            }
        }
    }

    static synchronized int pending() {
        return JOBS.size();
    }
}
