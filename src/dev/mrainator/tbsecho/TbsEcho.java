package dev.mrainator.tbsecho;

import net.neoforged.neoforge.common.NeoForge;

/** Настройки сюжета «Протокол ЭХО». Значения задаёт TBS Director из своего config.properties. */
public final class TbsEcho {
    private TbsEcho() {}

    public static final class Val<T> {
        private volatile T v;

        Val(T v) {
            this.v = v;
        }

        public T get() {
            return v;
        }

        public void set(T v) {
            this.v = v;
        }
    }

    public static final Val<Boolean> ENABLED = new Val<>(true);
    public static final Val<Integer> INTENSITY = new Val<>(3);
    public static final Val<Double> PACE = new Val<>(1.5);
    public static final Val<Boolean> WRITE_FILES = new Val<>(false);
    public static final Val<Boolean> SPAWN_ENTITIES = new Val<>(true);
    public static final Val<Boolean> TAMPER_INVENTORY = new Val<>(true);
    public static final Val<Boolean> OLD_COMBAT = new Val<>(true);
    public static final Val<Boolean> NO_SWEEP = new Val<>(true);
    public static final Val<Boolean> TAB = new Val<>(true);
    public static final Val<String> PUBLIC_URL = new Val<>("");

    private static boolean registered;

    /** Вызывается один раз из конструктора TBS Director. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.register(Director.class);
        NeoForge.EVENT_BUS.register(OldMechanics.class);
    }
}
