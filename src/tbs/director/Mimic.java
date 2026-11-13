package tbs.director;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Мимики: «заходят» игроки, которых сейчас нет в сети, — фальшивые игроки TBS с их скином и ником —
 * и нападают. Потом «выходят». Берём настоящих игроков сервера из usercache.json.
 */
public final class Mimic {
    record Known(String name, UUID id) {}

    /** Активные погони: мимик бежит к жертве и, если надо, копает к ней. */
    static final class Chase {
        final Mob mob;
        final UUID target;
        double lastDist = 1e9;
        int stuck;
        int attackCd;
        int lastHitTick = -100;
        Known who;
        // «Игра в игрока»: сначала мимик ведёт себя как обычный игрок и только потом нападает
        Phase phase = Phase.ACT;
        int actLeft;          // сколько ещё притворяться (в шагах по 5 тиков)
        int tellLeft;         // пауза-взгляд перед нападением
        Task task = Task.WATCH;
        int taskLeft;
        int taskLen;
        BlockPos work;        // блок, который он «копает»
        int said;             // сколько раз уже писал в чат
        int lastSaidStep = -100;
        int step;
        int lastPathStep = -100;
        Vec3 lastPos;         // где был 5 тиков назад — чтобы понять, что он правда упёрся
        // Куда смотрит голова. Держим цель и доворачиваем каждый тик — иначе голова дёргается
        // (LookControl сам возвращает её к телу, если цель не обновлять).
        Entity lookEnt;
        Vec3 lookPos;

        Chase(Mob mob, UUID target) {
            this.mob = mob;
            this.target = target;
        }
    }

    enum Phase { ACT, TELL, HUNT }

    /** Дальность удара между центрами (у стоящих вплотную — около 0,6). Было 1,95 — били почти с двух блоков. */
    static final double REACH = 1.5;

    /** Поворот головы за тик, градусы: плавно, как у игрока с мышью, а не рывком. */
    static final float HEAD_YAW_SPEED = 9f;
    // Наклон LookControl каждый тик сбрасывает в 0 и доворачивает заново, поэтому «скорость» наклона —
    // это по сути предел угла: меньше 40° — и он не сможет посмотреть вниз/вверх на игрока.
    static final float HEAD_PITCH_SPEED = 40f;

    static void lookAt(Chase c, Entity e) {
        c.lookEnt = e;
        c.lookPos = null;
    }

    static void lookAt(Chase c, Vec3 p) {
        c.lookEnt = null;
        c.lookPos = p;
    }

    /** Каждый тик: доворачиваем голову к цели. Без цели голова идёт за телом (по ходу движения). */
    private static void look(Chase c) {
        Mob m = c.mob;
        if (m.isInWater() && m.getFluidHeight(net.minecraft.tags.FluidTags.WATER) > m.getFluidJumpThreshold()) {
            m.getJumpControl().jump(); // держится на воде, как игрок (своих целей у него нет — мы их сняли)
        }
        if (c.lookEnt != null && c.lookEnt.isAlive() && c.lookEnt.level() == m.level()) {
            m.getLookControl().setLookAt(c.lookEnt, HEAD_YAW_SPEED, HEAD_PITCH_SPEED);
        } else if (c.lookPos != null) {
            m.getLookControl().setLookAt(c.lookPos.x, c.lookPos.y, c.lookPos.z, HEAD_YAW_SPEED, HEAD_PITCH_SPEED);
        }
    }

    /*
     * Скорость. У моба на земле за секунду ≈ 44 * 0,98 * s², где s = MOVEMENT_SPEED * модификатор навигации
     * (Mob.setSpeed ещё и ставит zza = s). Игрок: шаг 4,317 блока/с, бег 5,612.
     * Шаг: s = 0,316. Бег: s = 0,361 — только модификатором навигации. Флаг спринта мобу НЕ ставим:
     * с ним каждый прыжок на ступеньку даёт рывок вперёд (+0,2 блока/тик), из-за чего мимик дёргался и обгонял игрока.
     */
    static final double WALK_ATTR = 0.316;
    static final double WALK_MOD = 1.0;
    static final double RUN_MOD = 0.361 / WALK_ATTR;
    /** Как часто пересчитывать путь к движущейся цели (в шагах по 5 тиков): чаще — моб топчется и дёргается. */
    static final int REPATH_STEPS = 4;

    /** Блоков в секунду у моба при данном атрибуте/модификаторе/спринте. */
    static double blocksPerSecond(double attr, double mod, boolean sprint) {
        double sp = attr * (sprint ? 1.3 : 1.0) * mod;
        return 44.05 * 0.98 * sp * sp;
    }

    /** Идти к движущейся цели: путь пересчитываем раз в секунду, между пересчётами только подправляем скорость. */
    private static void run(Chase c, net.minecraft.world.entity.Entity to, boolean sprint) {
        Mob m = c.mob;
        double mod = sprint ? RUN_MOD : WALK_MOD;
        if (m.getNavigation().isDone() || c.step - c.lastPathStep >= REPATH_STEPS) {
            m.getNavigation().moveTo(to, mod);
            c.lastPathStep = c.step;
        } else {
            m.getNavigation().setSpeedModifier(mod);
        }
    }

    private static void run(Mob m, double x, double y, double z, boolean sprint) {
        m.getNavigation().moveTo(x, y, z, sprint ? RUN_MOD : WALK_MOD);
    }

    enum Task { WANDER, WATCH, FOLLOW, MINE, TORCH }

    /** Сколько мимик притворяется игроком: 45–100 секунд (в шагах по 5 тиков). */
    static int actSteps(java.util.Random r) {
        return 4 * (45 + r.nextInt(56));
    }

    /** Что делает «игрок» дальше. dark — вокруг темно (тогда может поставить факел). */
    static Task pickTask(int roll, boolean dark) {
        if (roll < 30) {
            return Task.WANDER;
        }
        if (roll < 50) {
            return Task.WATCH;
        }
        if (roll < 70) {
            return Task.FOLLOW;
        }
        if (roll < 92) {
            return Task.MINE;
        }
        return dark ? Task.TORCH : Task.WANDER;
    }

    static final List<Chase> CHASES = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Set<Entity> MIMICS = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    /** Мимики, которых уже убили (сообщение «вышел» отправлено) — чтобы не писать его второй раз. */
    static final Set<UUID> LEFT = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Каждый тик сервера. */
    static void tick(MinecraftServer server) {
        for (Chase c : CHASES) {
            if (!c.mob.isAlive()) {
                CHASES.remove(c);
                MIMICS.remove(c.mob);
                continue;
            }
            look(c);
            if (c.mob.tickCount % 5 != 0) {
                continue;
            }
            ServerPlayer t = server.getPlayerList().getPlayer(c.target);
            if (t == null || !t.isAlive() || t.level() != c.mob.level()) {
                continue;
            }
            double d = c.mob.distanceTo(t);
            c.step++;
            if (c.phase == Phase.ACT) {
                act(server, c, t, d);
                continue;
            }
            if (c.phase == Phase.TELL) {
                // замер и смотрит в упор — секунда-две, потом срывается
                c.mob.getNavigation().stop();
                lookAt(c, t);
                if (--c.tellLeft <= 0) {
                    c.phase = Phase.HUNT;
                }
                continue;
            }
            c.mob.setTarget(t);
            lookAt(c, t);
            if (c.attackCd > 0) {
                c.attackCd--;
            }
            if (d <= REACH && Math.abs(t.getY() - c.mob.getY()) < 1.5) {
                // удар: замах, 1,25 сердца, отбрасывание — раз в секунду
                c.mob.getNavigation().stop();
                if (c.attackCd == 0) {
                    c.mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                    t.hurt(c.mob.damageSources().mobAttack(c.mob), 2.5f);
                    t.knockback(0.4, c.mob.getX() - t.getX(), c.mob.getZ() - t.getZ());
                    c.attackCd = 4;
                }
                c.lastDist = d;
                c.stuck = 0;
                continue;
            }
            run(c, t, true);
            // «застрял» — это когда сам почти не сдвинулся, а не когда игрок просто убегает
            Vec3 now = c.mob.position();
            boolean still = c.lastPos != null && now.distanceToSqr(c.lastPos) < 0.2 * 0.2;
            c.lastPos = now;
            c.stuck = d > 2.1 && still ? c.stuck + 1 : 0;
            c.lastDist = d;
            boolean underground = !t.serverLevel().canSeeSky(t.blockPosition()) && t.getY() < c.mob.getY() - 2;
            if (d > 2 && (underground || c.stuck >= 6)) {
                dig(c.mob, t);
            }
        }
    }

    private static final List<String> CHAT = List.of("привет", "о, ты тут", "что строишь?", "есть еда?", "давно играешь?", "я ща", "го в шахту",
            "у тебя есть железо?", "норм место", "лагает что-то");
    private static final List<String> CHAT_EN = List.of("hi", "oh, you're here", "what are you building?", "got food?", "been on long?", "brb", "wanna go mining?",
            "got any iron?", "nice spot", "kinda laggy");
    private static final List<String> REPLY = List.of("да", "ага", "ща", "погоди", "?", "не", "потом", "я рядом");
    private static final List<String> REPLY_EN = List.of("yeah", "yep", "sec", "wait", "?", "nah", "later", "i'm near");

    private static void say(MinecraftServer server, Chase c, List<String> ru, List<String> en) {
        int li = ThreadLocalRandom.current().nextInt(ru.size());
        String name = c.who == null ? "?" : c.who.name();
        Hooks.SELF.set(true);
        try {
            L.each(server, pl -> Component.literal("<" + name + "> " + (L.ru(pl) ? ru : en).get(li)));
        } finally {
            Hooks.SELF.set(false);
        }
        c.said++;
        c.lastSaidStep = c.step;
    }

    /** Мимик раскрыт или ему надоело притворяться: замирает, смотрит, говорит — и нападает. */
    static void turn(MinecraftServer server, Chase c, boolean speak) {
        if (c.phase != Phase.ACT) {
            return;
        }
        c.phase = Phase.TELL;
        c.tellLeft = 6 + ThreadLocalRandom.current().nextInt(5);
        c.mob.getNavigation().stop();
        c.mob.setShiftKeyDown(false);
        c.mob.setPose(net.minecraft.world.entity.Pose.STANDING);
        if (c.work != null) {
            c.mob.level().destroyBlockProgress(c.mob.getId(), c.work, -1);
            c.work = null;
        }
        if (speak && server != null) {
            say(server, c, LINES, LINES_EN);
        }
    }

    /** Игрок написал в чат — притворяющийся мимик рядом иногда отвечает, как живой. */
    static void onChat(MinecraftServer server, ServerPlayer p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Chase c : CHASES) {
            if (c.phase == Phase.ACT && c.target.equals(p.getUUID()) && c.mob.isAlive() && c.said < 4 && r.nextInt(100) < 60) {
                Sched.tell(new Sched.Later(25 + r.nextInt(50), () -> {
                    if (c.mob.isAlive() && c.phase == Phase.ACT) {
                        say(server, c, REPLY, REPLY_EN);
                    }
                }));
                return;
            }
        }
    }

    /** Шаг «игры в игрока» (раз в 5 тиков). */
    private static void act(MinecraftServer server, Chase c, ServerPlayer t, double d) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Mob m = c.mob;
        ServerLevel l = (ServerLevel) m.level();
        m.setTarget(null);
        c.actLeft -= d < 3.0 ? 3 : 1;                       // игрок подошёл вплотную — терпения меньше
        if (Math.abs(t.getY() - m.getY()) > 6) {
            c.actLeft = Math.min(c.actLeft, 40);            // жертва под землёй — долго наверху не играем
        }
        if (c.actLeft <= 0) {
            turn(server, c, true);
            return;
        }
        // изредка пишет в чат что-нибудь обычное
        if (c.said < 2 && c.step - c.lastSaidStep > 60 && c.step > 16 && r.nextInt(70) == 0) {
            say(server, c, CHAT, CHAT_EN);
        }
        if (--c.taskLeft <= 0) {
            if (c.work != null) {
                l.destroyBlockProgress(m.getId(), c.work, -1);
                c.work = null;
            }
            m.setShiftKeyDown(false);
            m.setPose(net.minecraft.world.entity.Pose.STANDING);
            boolean dark = l.getMaxLocalRawBrightness(m.blockPosition()) < 6;
            c.task = pickTask(r.nextInt(100), dark);
            lookAt(c, (Entity) null); // новое дело — голова снова смотрит по ходу
            switch (c.task) {
                case WANDER -> {
                    // точка в 7–14 блоках от игрока: ходит вокруг, но в упор не лезет
                    int x = m.getBlockX(), z = m.getBlockZ(), y = m.getBlockY();
                    for (int i = 0; i < 8; i++) { // гулять идёт по суше, а не в озеро
                        double ang = r.nextDouble() * Math.PI * 2, rad = 7 + r.nextDouble() * 7;
                        x = (int) Math.floor(t.getX() + Math.cos(ang) * rad);
                        z = (int) Math.floor(t.getZ() + Math.sin(ang) * rad);
                        y = l.canSeeSky(m.blockPosition()) ? l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : m.getBlockY();
                        if (dry(l, new BlockPos(x, y, z))) {
                            break;
                        }
                    }
                    boolean run = r.nextInt(3) == 0;
                    run(m, x + 0.5, y, z + 0.5, run);
                    c.taskLen = 16 + r.nextInt(16);
                }
                case WATCH -> {
                    m.getNavigation().stop();
                    c.taskLen = 8 + r.nextInt(10);
                }
                case FOLLOW -> c.taskLen = 16 + r.nextInt(20);
                case MINE -> {
                    c.work = findBlock(l, m);
                    if (c.work == null) {
                        c.task = Task.WATCH;
                        c.taskLen = 6;
                    } else {
                        run(m, c.work.getX() + 0.5, c.work.getY(), c.work.getZ() + 0.5, false);
                        c.taskLen = 14 + r.nextInt(8);
                    }
                }
                case TORCH -> {
                    m.getNavigation().stop();
                    c.taskLen = 4;
                }
            }
            c.taskLeft = c.taskLen;
        }
        switch (c.task) {
            case WANDER -> {
                if (m.getNavigation().isDone()) {
                    c.taskLeft = Math.min(c.taskLeft, 2);
                }
            }
            case WATCH -> {
                lookAt(c, t);
                // «приветствие» приседанием — пару раз в начале
                boolean crouch = c.taskLen - c.taskLeft < 6 && (c.taskLen - c.taskLeft) % 2 == 0 && d < 16;
                m.setShiftKeyDown(crouch);
                m.setPose(crouch ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING);
            }
            case FOLLOW -> {
                lookAt(c, t);
                if (d > 7) {
                    run(c, t, d > 14);
                } else {
                    m.getNavigation().stop();
                }
            }
            case MINE -> {
                BlockPos w = c.work;
                if (w == null || l.getBlockState(w).isAir()) {
                    c.taskLeft = 0;
                } else if (m.distanceToSqr(w.getX() + 0.5, w.getY() + 0.5, w.getZ() + 0.5) <= 12) {
                    m.getNavigation().stop();
                    lookAt(c, new Vec3(w.getX() + 0.5, w.getY() + 0.5, w.getZ() + 0.5));
                    m.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                    net.minecraft.world.level.block.state.BlockState s = l.getBlockState(w);
                    l.playSound(null, w, s.getSoundType().getHitSound(), net.minecraft.sounds.SoundSource.BLOCKS, 0.5f, 0.8f);
                    int done = c.taskLen - c.taskLeft;
                    l.destroyBlockProgress(m.getId(), w, Math.min(9, done * 10 / Math.max(1, c.taskLen)));
                    if (c.taskLeft <= 1) {
                        l.destroyBlockProgress(m.getId(), w, -1);
                        l.destroyBlock(w, false, m); // «подобрал» — дропа нет
                        c.work = null;
                    }
                }
            }
            case TORCH -> {
                if (c.taskLeft == 2) {
                    BlockPos at = m.blockPosition();
                    if (l.getBlockState(at).isAir() && l.getBlockState(at.below()).isFaceSturdy(l, at.below(), net.minecraft.core.Direction.UP)) {
                        m.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                        l.setBlockAndUpdate(at, net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState());
                        l.playSound(null, at, net.minecraft.sounds.SoundEvents.WOOD_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
                    }
                }
            }
        }
    }

    /** «Природный» блок рядом, который игрок мог бы копать: бревно, земля, камень, песок. Постройки не трогаем. */
    static boolean minable(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.DIRT)
                || s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || s.is(net.minecraft.tags.BlockTags.SAND)
                || s.is(net.minecraft.tags.BlockTags.COAL_ORES) || s.is(net.minecraft.tags.BlockTags.IRON_ORES);
    }

    private static BlockPos findBlock(ServerLevel l, Mob m) {
        BlockPos o = m.blockPosition();
        BlockPos best = null;
        double bestD = 1e9;
        for (BlockPos p : BlockPos.betweenClosed(o.offset(-4, 0, -4), o.offset(4, 1, 4))) {
            // на уровне ног и головы, не под собой, и чтобы к блоку был открытый подход
            if ((p.getX() == o.getX() && p.getZ() == o.getZ()) || !minable(l.getBlockState(p)) || l.getBlockEntity(p) != null) {
                continue;
            }
            boolean open = false;
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                open |= l.getBlockState(p.relative(dir)).isAir();
            }
            double dd = p.distSqr(o);
            if (open && dd >= 2 && dd < bestD) {
                bestD = dd;
                best = p.immutable();
            }
        }
        return best;
    }

    /** Прорывает проход 1x2 в сторону игрока (лестницей вниз/вверх) и протискивается в него. */
    private static void dig(Mob m, ServerPlayer t) {
        Vec3 dir = t.position().subtract(m.position());
        double len = dir.length();
        if (len < 1.5) {
            return;
        }
        dir = dir.scale(1 / len);
        ServerLevel l = (ServerLevel) m.level();
        double dy = dir.y < -0.35 ? -1 : dir.y > 0.35 ? 1 : 0;
        BlockPos feet = BlockPos.containing(m.getX() + dir.x * 0.9, m.getY() + dy * 0.9 + 0.1, m.getZ() + dir.z * 0.9);
        BlockPos[] cells = dy > 0 ? new BlockPos[] {feet, feet.above(), m.blockPosition().above(2)} : new BlockPos[] {feet, feet.above()};
        boolean broke = false;
        for (BlockPos pos : cells) {
            net.minecraft.world.level.block.state.BlockState s = l.getBlockState(pos);
            float hard = s.getDestroySpeed(l, pos);
            if (!s.isAir() && hard >= 0 && hard < 50 && l.getBlockEntity(pos) == null && s.getFluidState().isEmpty()) {
                l.destroyBlock(pos, false, m);
                broke = true;
            }
        }
        if (broke || m.getNavigation().isDone()) {
            m.moveTo(m.getX() + dir.x * 0.6, m.getY() + dy * 0.55, m.getZ() + dir.z * 0.6, m.getYRot(), m.getXRot());
        }
    }

    private static final List<String> LINES = List.of("привет", "я тут", "почему ты один?", "иди сюда", "это я", "не убегай",
            "ты меня не узнал?", "я вернулся", "мы же друзья", "открой");
    /** Те же реплики по-английски (по индексу). */
    private static final List<String> LINES_EN = List.of("hi", "i'm here", "why are you alone?", "come here", "it's me", "don't run",
            "don't you recognize me?", "i'm back", "we're friends, right?", "open up");

    /** Игроки сервера, которых сейчас нет онлайн. */
    static List<Known> offline(MinecraftServer server) {
        List<Known> out = new ArrayList<>();
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            online.add(p.getUUID());
        }
        try {
            Path cache = server.getServerDirectory().resolve("usercache.json");
            if (Files.exists(cache)) {
                for (JsonElement e : JsonParser.parseString(Files.readString(cache)).getAsJsonArray()) {
                    String name = e.getAsJsonObject().get("name").getAsString();
                    UUID id = UUID.fromString(e.getAsJsonObject().get("uuid").getAsString());
                    // «null» — не мимик: его легко спутать с настоящим Null из сюжета TBS
                    if (!online.contains(id) && !name.equalsIgnoreCase("null")) {
                        out.add(new Known(name, id));
                    }
                }
            }
        } catch (Exception ex) {
            DirectorMod.LOG.warn("[understudy] usercache.json не прочитался: {}", ex.toString());
        }
        return out;
    }

    private static void broadcast(MinecraftServer server, Component msg) {
        Hooks.SELF.set(true);
        try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(msg);
            }
        } finally {
            Hooks.SELF.set(false);
        }
    }

    /** Набег: 1–3 мимика за спиной игрока. Возвращает, сколько появилось. */
    static int raid(MinecraftServer server, ServerPlayer target, int count, boolean selfFallback) {
        List<Known> pool = offline(server);
        if (pool.isEmpty() && selfFallback) {
            pool = new ArrayList<>(List.of(new Known(target.getGameProfile().getName(), target.getUUID())));
            count = 1;
        }
        if (pool.isEmpty()) {
            return 0;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath(TbsWorld.NS, "fake_player"));
        if (type == EntityType.PIG) {
            return 0;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        java.util.Collections.shuffle(pool);
        ServerLevel l = target.serverLevel();
        int spawned = 0;
        for (int i = 0; i < Math.min(count, pool.size()); i++) {
            Known k = pool.get(i);
            int delay = 20 + i * (40 + r.nextInt(80)); // заходят по одному
            Sched.tell(new Sched.Later(delay, () -> spawnOne(server, l, target, type, k)));
            spawned++;
        }
        return spawned;
    }

    private static void spawnOne(MinecraftServer server, ServerLevel l, ServerPlayer target, EntityType<?> type, Known k) {
        if (!target.isAlive() || target.serverLevel() != l) {
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        broadcast(server, Component.translatable("multiplayer.player.joined", k.name()).withStyle(ChatFormatting.YELLOW));
        // в ~25 блоках, чаще со спины; всегда на поверхности — под землю он прокопается сам.
        // Ищем сушу: сначала за спиной, потом по кругу и ближе; посреди моря — как получится.
        double back = Math.atan2(-target.getLookAngle().z, -target.getLookAngle().x);
        BlockPos spot = null;
        for (int i = 0; i < 24; i++) {
            double ang = back + (r.nextDouble() - 0.5) * Math.PI * (i < 8 ? 1 : 2);
            double rad = 25 - (i / 8) * 6;
            int bx = target.getBlockX() + (int) Math.round(Math.cos(ang) * rad);
            int bz = target.getBlockZ() + (int) Math.round(Math.sin(ang) * rad);
            l.getChunk(bx >> 4, bz >> 4);
            BlockPos at = l.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(bx, 0, bz));
            if (spot == null) {
                spot = at;
            }
            if (dry(l, at)) {
                spot = at;
                break;
            }
        }
        // без finalizeSpawn: иначе TBS сам пишет «присоединился» второй раз и выбирает своего игрока
        Entity e = type.create(l);
        if (e == null) {
            return;
        }
        try {
            e.getClass().getMethod("setSelectedPlayer", UUID.class).invoke(e, k.id());
            e.getClass().getMethod("setEvilDuration", int.class).invoke(e, 20 * 180);
        } catch (Throwable t) {
            DirectorMod.LOG.warn("[understudy] мимик без скина: {}", t.toString());
        }
        e.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, r.nextFloat() * 360f, 0f);
        if (!l.addFreshEntity(e)) {
            return;
        }
        e.setCustomName(Component.literal(k.name()));
        e.setCustomNameVisible(true);
        if (e instanceof Mob m) {
            // здоровье как у игрока: 20 (10 сердец), у TBS-шного фейка его почти нет
            var maxHp = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (maxHp != null) {
                maxHp.setBaseValue(20.0);
            }
            m.setHealth(20f);
            // скорость как у игрока: шаг ≈ 4,3 блока/с, бег ≈ 5,6 блока/с (у фейка TBS она своя, заметно выше)
            var spd = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            if (spd != null) {
                spd.setBaseValue(WALK_ATTR);
            }
            // у TBS дальность поиска пути 1816 блоков — каждый пересчёт пути вешал сервер; игроку хватит 48
            var range = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
            if (range != null) {
                range.setBaseValue(48.0);
            }
            m.getNavigation().setCanFloat(true); // через воду — вплавь
            MIMICS.add(m);
            stripGoals(m); // своё «передразнивание» TBS мешает нападать — управляем сами
            m.setPersistenceRequired();
            Chase ch = new Chase(m, target.getUUID());
            ch.who = k;
            ch.actLeft = actSteps(r); // сначала притворяется игроком
            CHASES.add(ch);
            if (r.nextBoolean()) {
                turn(server, ch, r.nextBoolean()); // половина мимиков не притворяется — нападает сразу
            }
        }
        DirectorMod.LOG.info("[understudy] мимик {} зашёл к {}", k.name(), target.getGameProfile().getName());
        // притворство до 100 с + охота — уходит через 4–5 минут
        Sched.tell(new Sched.Later(20 * (240 + r.nextInt(60)), () -> leave(server, e, k)));
    }

    /** Это мимик режиссёра (а не обычный фейк TBS)? Вызывается из миксина на каждом шаге движения — без блокировок. */
    public static boolean isMimic(Entity e) {
        return e != null && MIMICS.contains(e);
    }

    /** Суша: под ногами не жидкость и сама клетка не залита. */
    static boolean dry(ServerLevel l, BlockPos feet) {
        return l.getFluidState(feet).isEmpty() && l.getFluidState(feet.below()).isEmpty();
    }

    static Chase chaseOf(Entity e) {
        for (Chase c : CHASES) {
            if (c.mob == e) {
                return c;
            }
        }
        return null;
    }

    /**
     * Удар игрока по мимику. TBS-шный FakePlayerEntity.hurt при ударе игроком
     * превращается в «Схему» (Circuit) и умирает — у наших мимиков урон считаем сами.
     * true — событие удара надо отменить.
     */
    public static boolean onHit(MinecraftServer server, Entity target, Entity attacker, float base) {
        Chase c = chaseOf(target);
        if (c == null || !(target instanceof Mob m)) {
            return false;
        }
        if (m.tickCount - c.lastHitTick < 10) {
            return true; // неуязвимость полсекунды, как у обычных мобов
        }
        c.lastHitTick = m.tickCount;
        // ударили притворщика — маска слетает у него и у всех, кто пришёл с ним
        for (Chase o : CHASES) {
            if (o.target.equals(c.target)) {
                turn(server, o, o == c);
            }
        }
        m.setHealth(m.getHealth() - Math.max(1f, base));
        m.level().broadcastDamageEvent(m, attacker instanceof net.minecraft.world.entity.player.Player pl ? m.damageSources().playerAttack(pl) : m.damageSources().generic());
        m.playSound(net.minecraft.sounds.SoundEvents.PLAYER_HURT, 1f, 0.8f + ThreadLocalRandom.current().nextFloat() * 0.3f);
        if (attacker != null) {
            m.knockback(0.4, attacker.getX() - m.getX(), attacker.getZ() - m.getZ());
        }
        if (m.getHealth() <= 0) {
            ((ServerLevel) m.level()).sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, m.getX(), m.getY() + 1, m.getZ(), 20, 0.3, 0.6, 0.3, 0.02);
            CHASES.remove(c);
            MIMICS.remove(m);
            LEFT.add(m.getUUID());
            m.discard();
            if (c.who != null) {
                broadcast(server, Component.translatable("multiplayer.player.left", c.who.name()).withStyle(ChatFormatting.YELLOW));
            }
        }
        return true;
    }

    /** Рядом с мимиком появилась «Схема» TBS (удар мечом по площади и т.п.) — не пускаем. */
    static boolean isCircuitFromMimic(Entity e) {
        if (!"circuit".equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath())) {
            return false;
        }
        for (Chase c : CHASES) {
            if (c.mob.level() == e.level() && c.mob.distanceToSqr(e) < 2.5) {
                return true;
            }
        }
        return false;
    }

    /** Снимает все цели ИИ сущности (TBS-шные), чтобы ею управлял режиссёр. */
    private static void stripGoals(Mob m) {
        for (String field : new String[] {"goalSelector", "targetSelector"}) {
            try {
                java.lang.reflect.Field f = Mob.class.getDeclaredField(field);
                f.setAccessible(true);
                ((net.minecraft.world.entity.ai.goal.GoalSelector) f.get(m)).removeAllGoals(g -> true);
            } catch (Throwable t) {
                DirectorMod.LOG.warn("[understudy] не снял цели мимика: {}", t.toString());
            }
        }
    }

    private static void leave(MinecraftServer server, Entity e, Known k) {
        if (LEFT.remove(e.getUUID())) {
            return;
        }
        boolean wasAlive = e.isAlive();
        if (wasAlive) {
            e.discard();
        }
        broadcast(server, Component.translatable("multiplayer.player.left", k.name()).withStyle(ChatFormatting.YELLOW));
    }
}
