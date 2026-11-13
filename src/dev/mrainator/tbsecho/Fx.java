package dev.mrainator.tbsecho;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;

/** Эффекты и утилиты: звук, текст, блоки, файлы, отложенные задачи. */
public final class Fx {
    private Fx() {}

    private record Task(long tick, UUID player, Consumer<ServerPlayer> run) {}

    private static final List<Task> TASKS = new ArrayList<>();

    public static void later(ServerPlayer p, int ticks, Consumer<ServerPlayer> run) {
        TASKS.add(new Task(p.server.getTickCount() + Math.max(1, ticks), p.getUUID(), run));
    }

    public static void runTasks(MinecraftServer server) {
        if (TASKS.isEmpty()) return;
        long now = server.getTickCount();
        List<Task> due = new ArrayList<>();
        for (Iterator<Task> it = TASKS.iterator(); it.hasNext();) {
            Task t = it.next();
            if (t.tick <= now) {
                due.add(t);
                it.remove();
            }
        }
        for (Task t : due) {
            ServerPlayer p = server.getPlayerList().getPlayer(t.player);
            if (p == null) continue;
            try {
                t.run.accept(p);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public static void clearTasks() {
        TASKS.clear();
    }

    // ---------- звук ----------

    public static void sound(ServerPlayer p, String id, Vec3 at, float volume, float pitch) {
        Holder<SoundEvent> h = Holder.direct(SoundEvent.createVariableRangeEvent(ResourceLocation.parse(id)));
        p.connection.send(new ClientboundSoundPacket(h, SoundSource.AMBIENT, at.x, at.y, at.z, volume, pitch, p.getRandom().nextLong()));
    }

    public static void sound(ServerPlayer p, String id, float volume, float pitch) {
        sound(p, id, p.position(), volume, pitch);
    }

    public static Vec3 behind(ServerPlayer p, double dist) {
        Vec3 look = Vec3.directionFromRotation(0, p.getYRot());
        return p.position().subtract(look.scale(dist)).add(0, 1, 0);
    }

    // ---------- текст ----------

    public static String fmt(String s, ServerPlayer p) {
        return s.replace("%s", p.getGameProfile().getName());
    }

    public static void vesna(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal("<" + Story.VESNA + "> " + fmt(text, p)));
    }

    public static void rewriter(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal("§4" + fmt(text, p)));
    }

    public static void joined(ServerPlayer p, boolean join) {
        p.sendSystemMessage(Component.translatable(join ? "multiplayer.player.joined" : "multiplayer.player.left", Story.VESNA)
            .withStyle(ChatFormatting.YELLOW));
    }

    public static void actionbar(ServerPlayer p, String text) {
        p.displayClientMessage(Component.literal(fmt(text, p)).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC), true);
    }

    public static void title(ServerPlayer p, String title, String sub) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 30));
        p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(fmt(sub, p))));
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(fmt(title, p))));
    }

    public static void darkness(ServerPlayer p, int ticks) {
        p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, ticks, 0, false, false, false));
    }

    // ---------- предметы и блоки ----------

    public static ItemStack book(String title, String author, String[] pages) {
        List<Filterable<Component>> list = new ArrayList<>();
        for (String page : pages) list.add(Filterable.passThrough(Component.literal(page)));
        ItemStack s = new ItemStack(Items.WRITTEN_BOOK);
        s.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), author, 3, list, true));
        return s;
    }

    public static void give(ServerPlayer p, ItemStack stack) {
        if (!p.getInventory().add(stack)) p.drop(stack, false);
    }

    private static boolean free(ServerLevel l, BlockPos pos) {
        BlockState st = l.getBlockState(pos);
        return (st.isAir() || st.canBeReplaced()) && st.getFluidState().isEmpty();
    }

    private static boolean floor(ServerLevel l, BlockPos pos) {
        return l.getBlockState(pos).isSolidRender(l, pos);
    }

    /** Свободная клетка с твёрдым полом на расстоянии [min,max] от игрока; null, если не нашлось. */
    public static BlockPos findSpot(ServerPlayer p, int min, int max) {
        ServerLevel l = p.serverLevel();
        BlockPos base = p.blockPosition();
        for (int tries = 0; tries < 40; tries++) {
            double a = p.getRandom().nextDouble() * Math.PI * 2;
            int d = min + p.getRandom().nextInt(max - min + 1);
            int x = base.getX() + (int) Math.round(Math.cos(a) * d), z = base.getZ() + (int) Math.round(Math.sin(a) * d);
            for (int y = base.getY() + 5; y >= base.getY() - 5; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!l.hasChunkAt(pos)) break;
                if (free(l, pos) && free(l, pos.above()) && floor(l, pos.below())) return pos;
            }
        }
        return null;
    }

    public static boolean sign(ServerPlayer p, BlockPos pos, String[] lines) {
        ServerLevel l = p.serverLevel();
        if (pos == null || !free(l, pos)) return false;
        float yaw = (float) (Mth.atan2(p.getZ() - (pos.getZ() + 0.5), p.getX() - (pos.getX() + 0.5)) * 180 / Math.PI) - 90f;
        int rot = Mth.floor((yaw + 180f) * 16f / 360f + 0.5f) & 15;
        l.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rot), 3);
        if (l.getBlockEntity(pos) instanceof SignBlockEntity be) {
            SignText t = new SignText();
            for (int i = 0; i < 4 && i < lines.length; i++) t = t.setMessage(i, Component.literal(fmt(lines[i], p)));
            be.setText(t, true);
            be.setText(t, false);
            be.setChanged();
            l.sendBlockUpdated(pos, be.getBlockState(), be.getBlockState(), 3);
            return true;
        }
        return false;
    }

    /** Тайник: сундук с предметом, рядом булыжник с красным факелом. */
    public static boolean cache(ServerPlayer p, BlockPos pos, ItemStack content) {
        ServerLevel l = p.serverLevel();
        if (pos == null) return false;
        l.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        if (!(l.getBlockEntity(pos) instanceof ChestBlockEntity chest)) return false;
        chest.setItem(13, content);
        for (BlockPos n : new BlockPos[] {pos.north(), pos.south(), pos.east(), pos.west()}) {
            if (free(l, n) && free(l, n.above()) && floor(l, n.below())) {
                l.setBlock(n, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
                l.setBlock(n.above(), Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
                break;
            }
        }
        return true;
    }

    public static Entity spawn(ServerPlayer p, BlockPos pos, String... ids) {
        if (pos == null || !TbsEcho.SPAWN_ENTITIES.get()) return null;
        for (String id : ids) {
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse("thebrokenscript:" + id));
            if (type.isPresent()) {
                EntityType<?> t = type.get();
                try {
                    Entity e = t.spawn(p.serverLevel(), pos, MobSpawnType.EVENT);
                    if (e != null) return e;
                } catch (RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    // ---------- файлы ----------

    /** Файл в папке игры (только одиночная игра); папка, имя и текст — на языке игрока. */
    public static void file(ServerPlayer p, String nameRu, String nameEn, String textRu, String textEn, boolean append) {
        boolean ru = Lang.ru(p);
        file(p.server, ru ? "ЭХО" : "ECHO", ru ? nameRu : nameEn, ru ? textRu : textEn, append);
    }

    private static void file(MinecraftServer server, String folder, String name, String text, boolean append) {
        if (!TbsEcho.WRITE_FILES.get() || !server.isSingleplayer()) return;
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve(folder);
            Files.createDirectories(dir);
            Path f = dir.resolve(name);
            if (append) {
                Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(f, text, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
    }
}
