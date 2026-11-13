package tbs.director;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.Vec3;

/** Собственные действия режиссёра в мире. Все проверяют, что им есть что делать, иначе false. */
public final class WorldActs {
    private static final List<String> HUNTERS = List.of("he_chase", "circuit_stalk", "siluet_chase", "the_broken_end_stalk", "nothingiswatchingchase");
    private static final List<String> WATCHERS = List.of("null_watching", "stare", "circuit_stare", "siluet_stare", "nothing_watcher", "faraway");

    static boolean torchesOut(ServerPlayer p) {
        return torchesOut(p, 10);
    }

    /** Дверь рядом сама приоткрывается со скрипом и через 3 секунды закрывается. */
    static boolean doorCreak(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        for (BlockPos pos : BlockPos.betweenClosed(p.blockPosition().offset(-8, -2, -8), p.blockPosition().offset(8, 3, 8))) {
            BlockState s = l.getBlockState(pos);
            if (s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door && s.hasProperty(net.minecraft.world.level.block.DoorBlock.OPEN)
                    && !s.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) {
                BlockPos dp = pos.immutable();
                door.setOpen(null, l, s, dp, true);
                Sched.tell(new Sched.Later(60, () -> {
                    BlockState now = l.getBlockState(dp);
                    if (now.getBlock() instanceof net.minecraft.world.level.block.DoorBlock d2) {
                        d2.setOpen(null, l, now, dp, false);
                    }
                }));
                return true;
            }
        }
        return false;
    }

    static boolean torchesOut(ServerPlayer p, int max) {
        ServerLevel l = p.serverLevel();
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(p.blockPosition().offset(-10, -4, -10), p.blockPosition().offset(10, 4, 10))) {
            BlockState s = l.getBlockState(pos);
            if (s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.SOUL_TORCH) || s.is(Blocks.SOUL_WALL_TORCH)) {
                l.removeBlock(pos.immutable(), false);
                l.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.8f, 0.6f); // шипит сам факел
                if (++n >= max) {
                    break;
                }
            }
        }
        return n > 0;
    }

    private static BlockPos nearestChest(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(p.blockPosition().offset(-8, -3, -8), p.blockPosition().offset(8, 3, 8))) {
            if (l.getBlockState(pos).getBlock() instanceof ChestBlock) {
                double d = pos.distSqr(p.blockPosition());
                if (d < bd) {
                    bd = d;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    static boolean chestOpen(ServerPlayer p) {
        BlockPos c = nearestChest(p);
        if (c == null) {
            return false;
        }
        ServerLevel l = p.serverLevel();
        BlockState s = l.getBlockState(c);
        l.blockEvent(c, s.getBlock(), 1, 1);
        l.playSound(null, c, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, 0.8f);
        Sched.tell(new Sched.Later(40, () -> {
            if (l.getBlockState(c).getBlock() instanceof ChestBlock) {
                l.blockEvent(c, l.getBlockState(c).getBlock(), 1, 0);
                l.playSound(null, c, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 1f, 0.8f);
            }
        }));
        return true;
    }

    /** Переставляет вещи внутри сундука. Ничего не удаляет и не добавляет. */
    static boolean chestShuffle(ServerPlayer p) {
        BlockPos c = nearestChest(p);
        if (c == null) {
            return false;
        }
        BlockEntity be = p.serverLevel().getBlockEntity(c);
        if (!(be instanceof ChestBlockEntity chest)) {
            return false;
        }
        Container inv = chest;
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            items.add(inv.getItem(i).copy());
        }
        if (items.stream().allMatch(ItemStack::isEmpty)) {
            return false;
        }
        java.util.Collections.shuffle(items);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            inv.setItem(i, items.get(i));
        }
        inv.setChanged();
        chestOpen(p);
        return true;
    }

    static boolean lookBehind(ServerPlayer p) {
        p.teleportTo(p.serverLevel(), p.getX(), p.getY(), p.getZ(), p.getYRot() + 180f, p.getXRot());
        return true;
    }

    static boolean glassBreak(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(p.blockPosition().offset(-8, -3, -8), p.blockPosition().offset(8, 4, 8))) {
            BlockState s = l.getBlockState(pos);
            String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
            if (id.equals("glass") || id.equals("glass_pane") || id.endsWith("_stained_glass") || id.endsWith("_stained_glass_pane")) {
                l.destroyBlock(pos.immutable(), false);
                if (++n >= 2) {
                    break;
                }
            }
        }
        return n > 0;
    }

    static boolean sign(ServerPlayer p, String line) {
        ServerLevel l = p.serverLevel();
        Vec3 back = p.getLookAngle().multiply(-1, 0, -1).normalize().scale(3);
        BlockPos at = BlockPos.containing(p.position().add(back));
        for (int dy = 0; dy < 3; dy++) {
            BlockPos pos = at.above(dy - 1);
            if (l.getBlockState(pos).isAir() && l.getBlockState(pos.below()).isSolid()) {
                int rot = Math.floorMod(Math.round((p.getYRot() + 180f) * 16f / 360f), 16);
                l.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION, (rot + 8) % 16), 3);
                if (l.getBlockEntity(pos) instanceof SignBlockEntity sb) {
                    String[] words = (line.isEmpty() ? L.tr(p, "я был здесь", "i was here") : line).split(" ");
                    String[] rows = new String[4];
                    int r = 0;
                    StringBuilder cur = new StringBuilder();
                    for (String w : words) {
                        if (cur.length() + w.length() + 1 > 15 && cur.length() > 0) {
                            rows[r++] = cur.toString();
                            cur.setLength(0);
                            if (r == 4) {
                                break;
                            }
                        }
                        if (cur.length() > 0) {
                            cur.append(' ');
                        }
                        cur.append(w);
                    }
                    if (r < 4 && cur.length() > 0) {
                        rows[r] = cur.toString();
                    }
                    SignText t = new SignText();
                    for (int i = 0; i < 4; i++) {
                        t = t.setMessage(i, Component.literal(rows[i] == null ? "" : rows[i]));
                    }
                    sb.setText(t, true);
                    sb.setWaxed(true);
                    sb.setChanged();
                    l.sendBlockUpdated(pos, l.getBlockState(pos), l.getBlockState(pos), 3);
                }
                return true;
            }
        }
        return false;
    }

    static boolean hurt(ServerPlayer p) {
        if (p.getHealth() <= 10f) {
            return false; // не добиваем
        }
        p.hurt(p.damageSources().magic(), 2f + ThreadLocalRandom.current().nextInt(3));
        return true;
    }

    static boolean summon(ServerPlayer p, boolean hunter) {
        List<String> pool = hunter ? HUNTERS : WATCHERS;
        String id = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath(TbsWorld.NS, id));
        if (type == null || type == EntityType.PIG) { // pig = «не найдено» по умолчанию реестра
            return false;
        }
        ServerLevel l = p.serverLevel();
        Vec3 dir = p.getLookAngle().multiply(-1, 0, -1).normalize().scale(hunter ? 25 : 40);
        BlockPos base = BlockPos.containing(p.position().add(dir));
        BlockPos spot = l.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base);
        if (Math.abs(spot.getY() - p.getBlockY()) > 12) {
            spot = base; // под землёй — ставим на уровне игрока
        }
        Entity e = type.spawn(l, spot, MobSpawnType.EVENT);
        return e != null;
    }
}
