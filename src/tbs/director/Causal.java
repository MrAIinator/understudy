package tbs.director;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Звук только с причиной. Каждый метод либо находит то, что игрок может увидеть
 * (дверь, фигуру, молнию, пещеру вокруг), и играет звук ОТТУДА, либо возвращает false — и тогда
 * режиссёр берёт другое действие. Звуков «из пустоты у уха» здесь нет.
 */
public final class Causal {
    private Causal() {}

    /** События TBS, которые по сути один звук: разрешены только рядом с видимой фигурой. */
    static final Set<String> SOUND_ONLY = Set.of("breathe", "psst_event", "run");

    /** Стук: только в настоящую дверь рядом; после стука она приоткрывается. */
    public static boolean knock(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        for (BlockPos pos : BlockPos.betweenClosed(p.blockPosition().offset(-8, -2, -8), p.blockPosition().offset(8, 3, 8))) {
            BlockState s = l.getBlockState(pos);
            if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER && !s.getValue(DoorBlock.OPEN)) {
                BlockPos door = pos.immutable();
                for (int i = 0; i < 3; i++) {
                    Sched.tell(new Sched.Later(1 + i * 9, () ->
                            l.playSound(null, door, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.BLOCKS, 0.9f, 0.7f)));
                }
                Sched.tell(new Sched.Later(70, () -> {
                    BlockState now = l.getBlockState(door);
                    if (now.getBlock() instanceof DoorBlock d && !now.getValue(DoorBlock.OPEN)) {
                        d.setOpen(null, l, now, door, true);
                    }
                }));
                return true;
            }
        }
        return false;
    }

    /** Шаги: только если за спиной реально встала фигура; шаги идут от неё к игроку. */
    public static boolean footsteps(MinecraftServer server, ServerPlayer p) {
        if (!Scream.watch(server, p)) {
            return false;
        }
        ServerLevel l = p.serverLevel();
        Vec3 back = Vec3.directionFromRotation(0, p.getYRot()).scale(-1);
        Vec3 from = p.position();
        for (int i = 0; i < 5; i++) {
            Vec3 at = from.add(back.scale(13 - i));
            BlockPos ground = BlockPos.containing(at).below();
            Sched.tell(new Sched.Later(20 + i * 10, () -> {
                var type = l.getBlockState(ground).getSoundType(l, ground, null);
                l.playSound(null, at.x, at.y, at.z, type.getStepSound(), SoundSource.HOSTILE, 1f, type.getPitch() * 0.8f);
            }));
        }
        return true;
    }

    /** Пещерный гул: только под землёй, где он естественен. */
    public static boolean cave(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        BlockPos pos = p.blockPosition();
        if (l.canSeeSky(pos) || pos.getY() > 50) {
            return false;
        }
        Vec3 dir = Vec3.directionFromRotation(0, ThreadLocalRandom.current().nextFloat() * 360f).scale(10);
        l.playSound(null, p.getX() + dir.x, p.getY(), p.getZ() + dir.z, SoundEvents.AMBIENT_CAVE.value(), SoundSource.AMBIENT, 1f, 0.6f);
        return true;
    }

    /** Гром: только вместе с настоящей (безвредной) молнией на горизонте, которую видно. */
    public static boolean thunder(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        if (!l.canSeeSky(p.blockPosition().above())) {
            return false;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Vec3 dir = Vec3.directionFromRotation(0, p.getYRot() + (r.nextFloat() - 0.5f) * 70f).scale(45 + r.nextInt(25));
        int x = (int) Math.floor(p.getX() + dir.x), z = (int) Math.floor(p.getZ() + dir.z);
        if (!l.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) {
            return false;
        }
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(l);
        if (bolt == null) {
            return false;
        }
        bolt.moveTo(x + 0.5, l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z + 0.5);
        bolt.setVisualOnly(true);
        return l.addFreshEntity(bolt);
    }

    /** Темнота: без звука, только эффект, и только там, где и так темно. */
    public static boolean darkness(ServerPlayer p) {
        if (p.serverLevel().getMaxLocalRawBrightness(p.blockPosition()) > 7) {
            return false;
        }
        p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 140, 0, false, false, false));
        return true;
    }
}
