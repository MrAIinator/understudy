package tbs.director;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Скримеры в духе TBS.
 * flash — фигура на мгновение прямо перед лицом и сразу исчезает.
 * watch — фигура стоит за спиной и смотрит; стоит обернуться — пропадает со страшным звуком.
 */
final class Scream {
    private static final List<String> FIGURES = List.of("nulll", "null_watching", "siluet_stare", "stare", "he", "circuit_stare");
    private static final List<String> STINGS = List.of("null_jumpscare_notloudanymore", "circuit_jumpscare_sound", "glitch_sound_1", "bsod");
    /** Наши фигуры — их появление не считается событием TBS. */
    static final Set<UUID> OURS = ConcurrentHashMap.newKeySet();

    private record Watcher(Entity e, UUID target, long until) {}
    private static final List<Watcher> WATCHERS = new CopyOnWriteArrayList<>();

    private Scream() {}

    private static SoundEvent sound(String id) {
        return BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.fromNamespaceAndPath(TbsWorld.NS, id));
    }

    private static void sting(ServerPlayer p) {
        SoundEvent s = sound(STINGS.get(ThreadLocalRandom.current().nextInt(STINGS.size())));
        if (s == null) {
            s = net.minecraft.sounds.SoundEvents.WARDEN_ROAR;
        }
        p.playNotifySound(s, SoundSource.HOSTILE, 1f, 0.9f + ThreadLocalRandom.current().nextFloat() * 0.2f);
    }

    /** Неподвижная фигура TBS: без ИИ, бессмертная, смотрит на игрока. */
    private static Entity figure(ServerLevel l, Vec3 at, ServerPlayer look) {
        String id = FIGURES.get(ThreadLocalRandom.current().nextInt(FIGURES.size()));
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath(TbsWorld.NS, id));
        if (type == EntityType.PIG) {
            return null;
        }
        Entity e = type.create(l);
        if (e == null) {
            return null;
        }
        Vec3 d = look.position().subtract(at);
        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        e.moveTo(at.x, at.y, at.z, yaw, 0f);
        e.setYHeadRot(yaw);
        e.setInvulnerable(true);
        e.setSilent(true);
        if (e instanceof Mob m) {
            m.setNoAi(true);
            m.setYBodyRot(yaw);
        }
        OURS.add(e.getUUID());
        if (!l.addFreshEntity(e)) {
            OURS.remove(e.getUUID());
            return null;
        }
        return e;
    }

    /** Место на земле: ищем свободные 2 блока по высоте около заданной точки. */
    private static Vec3 ground(ServerLevel l, double x, double y, double z) {
        BlockPos base = BlockPos.containing(x, y, z);
        for (int dy = 0; dy <= 4; dy++) {
            for (int s : new int[] {dy, -dy}) {
                BlockPos p = base.offset(0, s, 0);
                if (l.getBlockState(p).isAir() && l.getBlockState(p.above()).isAir() && l.getBlockState(p.below()).isSolid()) {
                    return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                }
            }
        }
        return null;
    }

    /** Мелькнул перед лицом на пару тиков. */
    static boolean flash(MinecraftServer server, ServerPlayer p) {
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
        Vec3 spot = ground(p.serverLevel(), p.getX() + flat.x * 2.3, p.getY(), p.getZ() + flat.z * 2.3);
        if (spot == null) {
            spot = new Vec3(p.getX() + flat.x * 2.3, p.getY(), p.getZ() + flat.z * 2.3);
        }
        Entity e = figure(p.serverLevel(), spot, p);
        if (e == null) {
            return false;
        }
        sting(p);
        p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 30, 0, false, false));
        Sched.tell(new Sched.Later(4, () -> {
            e.discard();
            OURS.remove(e.getUUID());
        }));
        return true;
    }

    /** Стоит за спиной и смотрит. Обернётся — исчезнет со звуком. */
    static boolean watch(MinecraftServer server, ServerPlayer p) {
        Vec3 look = p.getLookAngle();
        Vec3 back = new Vec3(-look.x, 0, -look.z).normalize();
        ServerLevel l = p.serverLevel();
        for (int dist = 9; dist <= 15; dist += 2) {
            Vec3 spot = ground(l, p.getX() + back.x * dist, p.getY(), p.getZ() + back.z * dist);
            if (spot == null) {
                continue;
            }
            Entity e = figure(l, spot, p);
            if (e == null) {
                return false;
            }
            WATCHERS.add(new Watcher(e, p.getUUID(), server.getTickCount() + 20 * 45L));
            return true;
        }
        return false;
    }

    /** Каждый тик. */
    static void tick(MinecraftServer server) {
        for (Watcher w : WATCHERS) {
            ServerPlayer p = server.getPlayerList().getPlayer(w.target());
            Entity e = w.e();
            boolean gone = !e.isAlive() || p == null || p.level() != e.level() || p.distanceTo(e) > 48
                    || server.getTickCount() > w.until();
            if (!gone) {
                Vec3 d = p.position().subtract(e.position());
                float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
                e.setYRot(yaw);
                e.setYHeadRot(yaw);
                Vec3 toFig = e.getEyePosition().subtract(p.getEyePosition()).normalize();
                if (p.getLookAngle().dot(toFig) > 0.96 && p.hasLineOfSight(e)) {
                    sting(p);
                    p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 40, 0, false, false));
                    gone = true;
                }
            }
            if (gone) {
                WATCHERS.remove(w);
                e.discard();
                OURS.remove(e.getUUID());
            }
        }
    }
}