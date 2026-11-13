package tbs.director;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * «Разделение группы»: странное видит только один игрок. Сервер шлёт ему одному
 * фальшивое состояние блока, остальные видят мир как есть. Потом у него всё возвращается.
 */
public final class Split {
    private Split() {}

    /** Есть ли рядом с игроком другие (тогда разделение имеет смысл). */
    static boolean together(ServerPlayer p, List<ServerPlayer> all) {
        for (ServerPlayer o : all) {
            if (o != p && o.level() == p.level() && o.distanceTo(p) < 24) {
                return true;
            }
        }
        return false;
    }

    private static void fake(ServerPlayer p, BlockPos pos, BlockState shown, int ticks) {
        ServerLevel l = p.serverLevel();
        p.connection.send(new ClientboundBlockUpdatePacket(pos, shown));
        Sched.tell(new Sched.Later(ticks, () -> {
            if (!p.hasDisconnected()) {
                p.connection.send(new ClientboundBlockUpdatePacket(pos, l.getBlockState(pos)));
            }
        }));
    }

    /** Только у него гаснут факелы вокруг (на 10–20 с). */
    static boolean torches(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        int n = 0;
        int ticks = 200 + ThreadLocalRandom.current().nextInt(200);
        for (BlockPos pos : BlockPos.betweenClosed(p.blockPosition().offset(-10, -4, -10), p.blockPosition().offset(10, 4, 10))) {
            BlockState s = l.getBlockState(pos);
            if (s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.LANTERN)) {
                fake(p, pos.immutable(), Blocks.AIR.defaultBlockState(), ticks);
                if (++n >= 8) {
                    break;
                }
            }
        }
        return n > 0;
    }

    /** Только у него открывается дверь рядом (на 3–6 с). */
    static boolean door(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        for (BlockPos pos : BlockPos.betweenClosed(p.blockPosition().offset(-8, -2, -8), p.blockPosition().offset(8, 3, 8))) {
            BlockState s = l.getBlockState(pos);
            if (s.getBlock() instanceof DoorBlock && s.hasProperty(DoorBlock.OPEN) && !s.getValue(DoorBlock.OPEN)) {
                BlockPos lower = s.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? pos.immutable() : pos.below().immutable();
                int ticks = 60 + ThreadLocalRandom.current().nextInt(60);
                fake(p, lower, l.getBlockState(lower).setValue(DoorBlock.OPEN, true), ticks);
                fake(p, lower.above(), l.getBlockState(lower.above()).setValue(DoorBlock.OPEN, true), ticks);
                // звук идёт от самой двери и слышен только этому игроку
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                        net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(net.minecraft.sounds.SoundEvents.WOODEN_DOOR_OPEN),
                        net.minecraft.sounds.SoundSource.BLOCKS, lower.getX() + 0.5, lower.getY() + 0.5, lower.getZ() + 0.5, 1f, 0.8f, l.getRandom().nextLong()));
                return true;
            }
        }
        return false;
    }

    /** Только он видит «фигуру»: блок обсидиана на миг в поле зрения, где его нет. */
    static boolean glimpse(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        BlockPos at = BlockPos.containing(p.getEyePosition().add(p.getLookAngle().scale(14)));
        for (int dy = 0; dy < 4; dy++) {
            BlockPos pos = at.below(dy);
            if (l.getBlockState(pos).isAir() && l.getBlockState(pos.above()).isAir() && !l.getBlockState(pos.below()).isAir()) {
                fake(p, pos, Blocks.BLACK_CONCRETE.defaultBlockState(), 12);
                fake(p, pos.above(), Blocks.BLACK_CONCRETE.defaultBlockState(), 12);
                return true;
            }
        }
        return false;
    }

    /** Случайное «личное» событие; false — нечего показать. */
    static boolean any(ServerPlayer p) {
        int pick = ThreadLocalRandom.current().nextInt(3);
        for (int i = 0; i < 3; i++) {
            boolean ok = switch ((pick + i) % 3) {
                case 0 -> torches(p);
                case 1 -> door(p);
                default -> glimpse(p);
            };
            if (ok) {
                return true;
            }
        }
        return false;
    }
}
