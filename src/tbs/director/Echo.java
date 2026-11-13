package tbs.director;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * «Эхо»: мир забирает кусочки построек игроков.
 * Где появляются копии: далеко в обычном мире; в ~100 блоках перед игроком, который долго идёт
 * в одну сторону; на высоте 252 в измерении TBS clan_void (там же — родные структуры TBS).
 * Копируется только настоящая постройка. Хранилища пустые, ценные блоки заменены — дюпа нет.
 */
public final class Echo {
    static final ResourceKey<Level> CLAN_VOID = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(TbsWorld.NS, "clan_void"));
    static final int VOID_Y = 252;
    private static final int LIBRARY_MAX = 80;
    private static final Set<String> VALUABLE = Set.of("diamond", "emerald", "gold", "netherite", "ancient_debris", "beacon",
            "shulker_box", "_ore", "lapis_block", "redstone_block", "iron_block", "amethyst", "spawner", "dragon", "conduit",
            "enchanting_table", "anvil", "copper_block", "coal_block", "raw_", "end_portal", "respawn_anchor", "lodestone");
    /** Родные постройки TBS для clan_void. */
    static final List<String> TBS_STRUCTURES = List.of(
            "clanvoidnew1", "clanvoidnew2", "clanvoidnew3", "clanvoidnew4", "clanvoidnew5", "clanvoidnew6", "clanvoidnew7",
            "clanvoidnew8", "clanvoidnew9", "clanvoidnew10", "clanvoidnew11", "clanvoidnew12", "clanvoidnew13",
            "clandimensionroom1", "clandimensionroom2", "clandimensionroom3", "clandimensionroom4", "clandimensionroom5",
            "clanbuildoverhaul", "daya_house", "daya_tower_redo", "daya_observatory", "daya_blacksmith", "dirt_home",
            "baseruins", "alongwayfromhome", "afriendlost");

    /** Стадия умирания мира — влияет на то, насколько искажены копии. */
    static volatile int decayStage;

    /**
     * inv — содержимое хранилищ: {p: позиция, items: [{Slot, item}]}.
     * frames — рамки: {p, f: сторона, glow, rot, item}.
     */
    record Piece(String owner, int sx, int sy, int sz, List<BlockPos> rel, List<BlockState> states,
            List<CompoundTag> inv, List<CompoundTag> frames) {}

    private static final Set<String> VALUABLE_ITEMS = Set.of("diamond", "netherite", "emerald", "gold", "enchanted", "totem",
            "elytra", "shulker", "beacon", "nether_star", "trident", "heart_of_the_sea", "ancient_debris", "dragon", "spawn_egg",
            "mace", "heavy_core", "trial_key");

    // ---------- захват ----------

    private static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Природный блок — часть фундамента, если он ниже рукотворного в своей или соседней колонке. */
    private static boolean isFoundation(java.util.Map<Long, Integer> floor, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Integer y = floor.get(column(pos.getX() + dx, pos.getZ() + dz));
                if (y != null && pos.getY() < y) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Копирует постройку вокруг игрока. null — рядом нет настоящей постройки. */
    static Piece capture(ServerPlayer p, int decayStage) {
        ServerLevel l = p.serverLevel();
        BlockPos c = p.blockPosition();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int built = 0;
        Set<Block> kinds = new HashSet<>();
        // самый нижний рукотворный блок в каждой колонке: по нему отличаем фундамент от ландшафта вокруг
        java.util.Map<Long, Integer> floor = new java.util.HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-16, -6, -16), c.offset(16, 14, 16))) {
            BlockState s = l.getBlockState(pos);
            if (Decay.isBuilt(s)) {
                floor.merge(column(pos.getX(), pos.getZ()), pos.getY(), Math::min);
                built++;
                kinds.add(s.getBlock());
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
        }
        // постройка, а не пара блоков: много рукотворного, несколько материалов, есть объём
        if (built < 60 || kinds.size() < 3 || maxX - minX < 3 || maxY - minY < 2 || maxZ - minZ < 3) {
            return null;
        }
        minX -= 2;
        minZ -= 2;
        maxX += 2;
        maxZ += 2;
        minY -= 2;
        maxX = Math.min(maxX, minX + 31);
        maxZ = Math.min(maxZ, minZ + 31);
        maxY = Math.min(maxY + 1, minY + 27);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Block missing = missingBlock();
        List<BlockPos> rel = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            BlockState s = l.getBlockState(pos);
            if (s.isAir()) {
                continue;
            }
            // природные блоки берём только как фундамент: под постройкой и вплотную к ней.
            // Холмы, деревья и трава вокруг в копию не попадают — иначе вместо дома переносится кусок ландшафта.
            if (!Decay.isBuilt(s) && !isFoundation(floor, pos)) {
                continue;
            }
            BlockState t = sanitize(s, r, missing);
            if (t == null || t.isAir()) {
                continue;
            }
            rel.add(new BlockPos(pos.getX() - minX, pos.getY() - minY, pos.getZ() - minZ));
            states.add(t);
        }
        // вещи: хранилища и рамки копируются, ценное — сразу в name.null
        var reg = l.registryAccess();
        List<CompoundTag> inv = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (l.getBlockEntity(pos) instanceof net.minecraft.world.Container box && !(box instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity)) {
                ListTag items = new ListTag();
                for (int slot = 0; slot < box.getContainerSize(); slot++) {
                    net.minecraft.world.item.ItemStack st = cleanItem(box.getItem(slot));
                    if (!st.isEmpty()) {
                        CompoundTag it = new CompoundTag();
                        it.putInt("Slot", slot);
                        it.put("item", st.save(reg));
                        items.add(it);
                    }
                }
                if (!items.isEmpty()) {
                    CompoundTag e = new CompoundTag();
                    e.putLong("p", new BlockPos(pos.getX() - minX, pos.getY() - minY, pos.getZ() - minZ).asLong());
                    e.put("items", items);
                    inv.add(e);
                }
            }
        }
        List<CompoundTag> frames = new ArrayList<>();
        for (net.minecraft.world.entity.decoration.ItemFrame fr : l.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class,
                new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1))) {
            BlockPos fp = fr.getPos();
            CompoundTag e = new CompoundTag();
            e.putLong("p", new BlockPos(fp.getX() - minX, fp.getY() - minY, fp.getZ() - minZ).asLong());
            e.putInt("f", fr.getDirection().get3DDataValue());
            e.putBoolean("glow", fr instanceof net.minecraft.world.entity.decoration.GlowItemFrame);
            e.putInt("rot", fr.getRotation());
            net.minecraft.world.item.ItemStack st = cleanItem(fr.getItem());
            if (!st.isEmpty()) {
                e.put("item", st.save(reg));
            }
            frames.add(e);
        }
        // общая база: все, кто рядом, — совладельцы
        List<String> owners = new ArrayList<>();
        for (ServerPlayer o : l.players()) {
            if (o.distanceTo(p) <= 32) {
                owners.add(o.getGameProfile().getName());
            }
        }
        return new Piece(String.join("+", owners), maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, rel, states, inv, frames);
    }

    /** Предмет TBS по id, или пусто. */
    private static net.minecraft.world.item.ItemStack tbsItem(String id, int count) {
        net.minecraft.world.item.Item it = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(TbsWorld.NS, id));
        return it == net.minecraft.world.item.Items.AIR ? net.minecraft.world.item.ItemStack.EMPTY : new net.minecraft.world.item.ItemStack(it, count);
    }

    /** name.null, очень редко name.revuxor. */
    static net.minecraft.world.item.ItemStack brokenItem(ThreadLocalRandom r, int count) {
        net.minecraft.world.item.ItemStack s = r.nextInt(25) == 0 ? tbsItem("gore", 1) : tbsItem("n", Math.max(1, count));
        return s.isEmpty() ? new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER, 1) : s;
    }

    /** В библиотеку предмет попадает как есть; ценное режется уже при появлении копии (см. loot). */
    private static net.minecraft.world.item.ItemStack cleanItem(net.minecraft.world.item.ItemStack s) {
        return s.isEmpty() ? net.minecraft.world.item.ItemStack.EMPTY : s.copy();
    }

    static boolean valuable(net.minecraft.world.item.ItemStack s) {
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        boolean v = s.isEnchanted();
        for (String k : VALUABLE_ITEMS) {
            v |= id.contains(k);
        }
        for (String k : VALUABLE) {
            v |= id.contains(k);
        }
        return v;
    }

    /**
     * Ценное в копии: очень редко (2%) нетронуто, иначе половина — пропало,
     * половина — осталось, но в разы меньше (стак алмазов → 1–4 штуки).
     */
    static net.minecraft.world.item.ItemStack loot(net.minecraft.world.item.ItemStack s, ThreadLocalRandom r) {
        if (s.isEmpty() || !valuable(s) || r.nextInt(100) < 2) {
            return s;
        }
        if (r.nextBoolean()) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        net.minecraft.world.item.ItemStack less = s.copy();
        less.setCount(Math.max(1, 1 + r.nextInt(Math.max(1, s.getCount() / 8 + 1))));
        return less;
    }

    private static Block missingBlock() {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(TbsWorld.NS, "block_is_missing_id"));
        return b == Blocks.AIR ? null : b;
    }

    /** При захвате: убрать ценное и текущие жидкости. Остальное — как было. */
    private static BlockState sanitize(BlockState s, ThreadLocalRandom r, Block missing) {
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (!s.getFluidState().isEmpty() && !s.getFluidState().isSource()) {
            return null;
        }
        for (String v : VALUABLE) {
            if (id.contains(v)) {
                return r.nextBoolean() || missing == null ? Blocks.COBBLESTONE.defaultBlockState() : missing.defaultBlockState();
            }
        }
        return s;
    }

    /**
     * Искажение не по всему дому: 1–3 «укуса», мир будто начал есть постройку с одного края
     * и остановился. В центре укуса почти всё — блоки TBS и дыры, к краю — всё реже.
     */
    static final class Eater {
        final List<BlockPos> centers = new ArrayList<>();
        final List<Double> radii = new ArrayList<>();

        static Eater of(Piece piece, ThreadLocalRandom r, int stage) {
            Eater e = new Eater();
            if (piece.rel().isEmpty()) {
                return e;
            }
            BlockPos first = piece.rel().get(r.nextInt(piece.rel().size()));
            int bites = 1 + r.nextInt(2) + (stage >= 3 ? 1 : 0);
            for (int b = 0; b < bites; b++) {
                BlockPos c = b == 0 ? first : first.offset(r.nextInt(13) - 6, r.nextInt(7) - 3, r.nextInt(13) - 6);
                e.centers.add(c);
                e.radii.add(3.0 + stage + r.nextInt(4));
            }
            return e;
        }

        /** 0 — не тронуто, 1 — съедено. */
        double weight(BlockPos p) {
            double w = 0;
            for (int i = 0; i < centers.size(); i++) {
                double d = Math.sqrt(centers.get(i).distSqr(p));
                double rad = radii.get(i);
                if (d < rad) {
                    w = Math.max(w, 0.92 * Math.pow(1 - d / rad, 0.6));
                } else if (d < rad + 3) {
                    w = Math.max(w, 0.06); // редкие «споры» вокруг укуса
                }
            }
            return w;
        }
    }

    private static BlockState tbsBlock(String id) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(TbsWorld.NS, id));
        return b == Blocks.AIR ? null : b.defaultBlockState();
    }

    /** It, Disruption, Shadow Bug; редко Viscera и Block Is Missing Id. */
    private static BlockState eatenBlock(ThreadLocalRandom r) {
        int roll = r.nextInt(100);
        String id = roll < 7 ? "viscera" : roll < 11 ? "block_is_missing_id" : switch (r.nextInt(3)) {
            case 0 -> "it";
            case 1 -> "disruption";
            default -> "shadow_bug";
        };
        BlockState s = tbsBlock(id);
        return s != null ? s : Blocks.COBBLESTONE.defaultBlockState();
    }

    /** При появлении искажённой копии. */
    private static BlockState corrupt(BlockState s, BlockPos rel, Eater eat, ThreadLocalRandom r, Block missing) {
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (!s.getFluidState().isEmpty() && !s.getFluidState().isSource()) {
            return null;
        }
        for (String v : VALUABLE) {
            if (id.contains(v)) {
                return r.nextBoolean() || missing == null ? Blocks.COBBLESTONE.defaultBlockState() : missing.defaultBlockState();
            }
        }
        if (id.equals("torch")) {
            return Blocks.REDSTONE_TORCH.defaultBlockState();
        }
        if (id.equals("wall_torch") || id.contains("lantern") || id.equals("glowstone") || id.contains("shroomlight")) {
            return null; // в копии темно
        }
        double w = eat.weight(rel);
        if (w <= 0 || r.nextDouble() >= w) {
            return s;
        }
        if (id.endsWith("carpet")) {
            BlockState nm = tbsBlock("name_missing");
            if (nm != null && r.nextInt(100) < 75) {
                return nm.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)
                        ? nm.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, net.minecraft.core.Direction.UP)
                        : nm;
            }
            return null;
        }
        if (!s.isSolid()) {
            return r.nextInt(100) < 55 ? null : s; // двери, факелы, стекло-панели — частью исчезли
        }
        return r.nextInt(100) < 14 ? null : eatenBlock(r);
    }
    // ---------- библиотека ----------

    static void save(Path dir, Piece piece) {
        try {
            Path lib = dir.resolve("echoes");
            Files.createDirectories(lib);
            CompoundTag root = new CompoundTag();
            root.putString("owner", piece.owner());
            root.putInt("sx", piece.sx());
            root.putInt("sy", piece.sy());
            root.putInt("sz", piece.sz());
            ListTag blocks = new ListTag();
            for (int i = 0; i < piece.rel().size(); i++) {
                CompoundTag b = new CompoundTag();
                b.putLong("p", piece.rel().get(i).asLong());
                b.put("s", NbtUtils.writeBlockState(piece.states().get(i)));
                blocks.add(b);
            }
            root.put("blocks", blocks);
            ListTag inv = new ListTag();
            inv.addAll(piece.inv());
            root.put("inv", inv);
            ListTag frames = new ListTag();
            frames.addAll(piece.frames());
            root.put("frames", frames);
            String prefix = piece.owner().replaceAll("[^A-Za-z0-9_]", "_") + "_";
            // снимок той же базы чаще раза в 10 минут заменяет прошлый, а не плодит дубли
            try (Stream<Path> same = Files.list(lib)) {
                for (Path old : same.filter(f -> f.getFileName().toString().startsWith(prefix)).toList()) {
                    if (System.currentTimeMillis() - Files.getLastModifiedTime(old).toMillis() < 600_000L) {
                        Files.deleteIfExists(old);
                    }
                }
            }
            NbtIo.writeCompressed(root, lib.resolve(prefix + System.currentTimeMillis() + ".dat"));
            try (Stream<Path> files = Files.list(lib)) {
                List<Path> all = files.sorted(Comparator.comparing(Path::toString)).toList();
                for (int i = 0; i < all.size() - LIBRARY_MAX; i++) {
                    Files.deleteIfExists(all.get(i));
                }
            }
        } catch (Exception e) {
            DirectorMod.LOG.warn("[understudy] эхо не сохранилось: {}", e.toString());
        }
    }

    /** Случайная постройка из библиотеки, лучше — не этого игрока. */
    static Piece load(Path dir, String notOwner) {
        try (Stream<Path> files = Files.list(dir.resolve("echoes"))) {
            List<Path> all = files.filter(f -> f.toString().endsWith(".dat")).toList();
            if (all.isEmpty()) {
                return null;
            }
            List<Path> others = all.stream().filter(f -> notOwner == null || !f.getFileName().toString().startsWith(notOwner + "_")).toList();
            List<Path> pool = others.isEmpty() ? all : others;
            CompoundTag root = NbtIo.readCompressed(pool.get(ThreadLocalRandom.current().nextInt(pool.size())), NbtAccounter.unlimitedHeap());
            List<BlockPos> rel = new ArrayList<>();
            List<BlockState> states = new ArrayList<>();
            for (Tag t : root.getList("blocks", Tag.TAG_COMPOUND)) {
                CompoundTag b = (CompoundTag) t;
                rel.add(BlockPos.of(b.getLong("p")));
                states.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), b.getCompound("s")));
            }
            List<CompoundTag> inv = new ArrayList<>();
            for (Tag t : root.getList("inv", Tag.TAG_COMPOUND)) {
                inv.add((CompoundTag) t);
            }
            List<CompoundTag> frames = new ArrayList<>();
            for (Tag t : root.getList("frames", Tag.TAG_COMPOUND)) {
                frames.add((CompoundTag) t);
            }
            return new Piece(root.getString("owner"), root.getInt("sx"), root.getInt("sy"), root.getInt("sz"), rel, states, inv, frames);
        } catch (Exception e) {
            return null;
        }
    }

    /** Самый старый снимок постройки этого игрока (для главы «Воспоминание»). */
    static Piece loadOldest(Path dir, String owner) {
        try (Stream<Path> files = Files.list(dir.resolve("echoes"))) {
            Optional<Path> f = files.filter(x -> x.getFileName().toString().contains(owner) && x.toString().endsWith(".dat"))
                    .min(Comparator.comparing(Path::toString));
            if (f.isEmpty()) {
                return null;
            }
            Path tmp = Files.createTempDirectory("tbsecho");
            Files.createDirectories(tmp.resolve("echoes"));
            Files.copy(f.get(), tmp.resolve("echoes").resolve(f.get().getFileName()));
            Piece p = load(tmp, null);
            Files.deleteIfExists(tmp.resolve("echoes").resolve(f.get().getFileName()));
            Files.deleteIfExists(tmp.resolve("echoes"));
            Files.deleteIfExists(tmp);
            return p;
        } catch (Exception e) {
            return null;
        }
    }
    // ---------- размещение ----------

    private static int put(ServerLevel target, Piece piece, BlockPos at) {
        return put(target, piece, at, false);
    }

    /** grounded — копия ставится на землю: под нижним слоем досыпается грунт, чтобы края не висели. */
    private static int put(ServerLevel target, Piece piece, BlockPos at, boolean grounded) {
        if (grounded) {
            for (BlockPos rel : piece.rel()) {
                if (rel.getY() != 0) {
                    continue;
                }
                BlockPos.MutableBlockPos down = at.offset(rel).mutable().move(0, -1, 0);
                for (int d = 0; d < 8 && down.getY() > target.getMinBuildHeight(); d++, down.move(0, -1, 0)) {
                    BlockState under = target.getBlockState(down);
                    if (!under.isAir() && !under.canBeReplaced()) {
                        break;
                    }
                    target.setBlock(down, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
            }
        }
        int n = 0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        boolean distorted = r.nextBoolean(); // половина копий — как было, половина — искажённые
        Block missing = missingBlock();
        Eater eat = distorted ? Eater.of(piece, r, decayStage) : new Eater();
        Set<BlockPos> solid = new HashSet<>();
        for (int i = 0; i < piece.rel().size() && n < 14000; i++) {
            BlockPos rel = piece.rel().get(i);
            BlockPos pos = at.offset(rel);
            BlockState st = distorted ? corrupt(piece.states().get(i), rel, eat, r, missing) : piece.states().get(i);
            if (st != null && pos.getY() < target.getMaxBuildHeight() && pos.getY() >= target.getMinBuildHeight()) {
                target.setBlock(pos, st, 2 | 16);
                solid.add(rel);
                n++;
            }
        }
        if (distorted) {
            portal(target, piece, at, eat, solid, r);
        }
        fill(target, piece, at, eat, distorted, r);
        return n;
    }

    /** Совсем редко: «431434» в два блока высотой, стоит в комнате как проход. */
    private static void portal(ServerLevel target, Piece piece, BlockPos at, Eater eat, Set<BlockPos> solid, ThreadLocalRandom r) {
        BlockState gate = tbsBlock("all_dead");
        if (gate == null || r.nextInt(100) >= (Story.current >= 5 ? 35 : 12)) { // в главе «Библиотека» — чаще
            return;
        }
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos rel : solid) {
            BlockPos up = rel.above();
            if (eat.weight(up) > 0.2 && !solid.contains(up) && !solid.contains(up.above()) && up.getY() + 1 < piece.sy()) {
                spots.add(up);
            }
        }
        if (spots.isEmpty()) {
            return;
        }
        BlockPos p = at.offset(spots.get(r.nextInt(spots.size())));
        target.setBlock(p, gate, 2 | 16);
        target.setBlock(p.above(), gate, 2 | 16);
    }

    /** Вещи: сундуки и рамки. В искажённой копии часть предметов стала name.null. */
    private static void fill(ServerLevel target, Piece piece, BlockPos at, Eater eat, boolean distorted, ThreadLocalRandom r) {
        var reg = target.registryAccess();
        int itemRot = 8 + decayStage * 4;
        for (CompoundTag e : piece.inv()) {
            BlockPos rel = BlockPos.of(e.getLong("p"));
            if (!(target.getBlockEntity(at.offset(rel)) instanceof net.minecraft.world.Container c)) {
                continue; // сундук «съеден»
            }
            double w = distorted ? eat.weight(rel) : 0;
            for (Tag t : e.getList("items", Tag.TAG_COMPOUND)) {
                CompoundTag it = (CompoundTag) t;
                int slot = it.getInt("Slot");
                net.minecraft.world.item.ItemStack st = net.minecraft.world.item.ItemStack.parseOptional(reg, it.getCompound("item"));
                if (st.isEmpty() || slot < 0 || slot >= c.getContainerSize()) {
                    continue;
                }
                if (distorted && (r.nextDouble() < w * 0.8 || r.nextInt(100) < itemRot)) {
                    st = brokenItem(r, 1 + r.nextInt(st.getCount()));
                } else {
                    st = loot(st, r);
                }
                if (!st.isEmpty()) {
                    c.setItem(slot, st);
                }
            }
        }
        for (CompoundTag e : piece.frames()) {
            BlockPos rel = BlockPos.of(e.getLong("p"));
            BlockPos pos = at.offset(rel);
            net.minecraft.core.Direction dir = net.minecraft.core.Direction.from3DDataValue(e.getInt("f"));
            net.minecraft.world.entity.decoration.ItemFrame fr = e.getBoolean("glow")
                    ? new net.minecraft.world.entity.decoration.GlowItemFrame(target, pos, dir)
                    : new net.minecraft.world.entity.decoration.ItemFrame(target, pos, dir);
            if (!fr.survives()) {
                continue;
            }
            net.minecraft.world.item.ItemStack st = e.contains("item")
                    ? net.minecraft.world.item.ItemStack.parseOptional(reg, e.getCompound("item"))
                    : net.minecraft.world.item.ItemStack.EMPTY;
            if (distorted && !st.isEmpty() && (r.nextDouble() < eat.weight(rel) || r.nextInt(100) < itemRot * 2)) {
                st = brokenItem(r, 1);
            } else {
                st = loot(st, r);
            }
            if (!st.isEmpty()) {
                fr.setItem(st, false);
                fr.setRotation(e.getInt("rot"));
            }
            target.addFreshEntity(fr);
        }
    }
    /**
     * Куда поставить копию размером sx×sz, чтобы она стояла на земле, а не висела.
     * Высота берётся по сетке точек под всей постройкой (листва не считается), из них — нижняя четверть:
     * одинокое дерево или холмик под углом больше не поднимают дом в воздух.
     */
    private static BlockPos surface(ServerLevel l, int x, int z, int sx, int sz) {
        List<Integer> hs = new ArrayList<>();
        for (int i = 0; i <= 4; i++) {
            for (int j = 0; j <= 4; j++) {
                int px = x + (sx - 1) * i / 4, pz = z + (sz - 1) * j / 4;
                l.getChunk(px >> 4, pz >> 4); // сгенерировать/загрузить
                hs.add(l.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(px, 0, pz)).getY());
            }
        }
        java.util.Collections.sort(hs);
        return new BlockPos(x, hs.get(hs.size() / 4) - 2, z);
    }

    /** Далеко в обычном мире (500–1500 блоков от точки). */
    static String placeFar(MinecraftServer server, Piece piece, BlockPos from, Path dir) {
        ServerLevel ow = server.getLevel(Level.OVERWORLD);
        if (ow == null) {
            return null;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang = r.nextDouble() * Math.PI * 2;
        int dist = 500 + r.nextInt(1000);
        BlockPos at = surface(ow, from.getX() + (int) (Math.cos(ang) * dist), from.getZ() + (int) (Math.sin(ang) * dist), piece.sx(), piece.sz());
        return log(dir, piece.owner(), ow, at, put(ow, piece, at, true), "далеко");
    }

    /** Перед игроком, в ~100 блоках по направлению движения. */
    static String placeAhead(Piece piece, ServerPlayer p, double dirX, double dirZ, Path dir) {
        ServerLevel l = p.serverLevel();
        int x = p.getBlockX() + (int) (dirX * 100) - piece.sx() / 2;
        int z = p.getBlockZ() + (int) (dirZ * 100) - piece.sz() / 2;
        BlockPos at = surface(l, x, z, piece.sx(), piece.sz());
        return log(dir, piece.owner(), l, at, put(l, piece, at, true), "перед путником " + p.getGameProfile().getName());
    }

    /** На высоте 252 в clan_void, рядом с точкой. Постройка игрока или родная структура TBS. */
    static String placeVoid(MinecraftServer server, Piece piece, BlockPos near, Path dir) {
        ServerLevel v = server.getLevel(CLAN_VOID);
        if (v == null) {
            return null;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang = r.nextDouble() * Math.PI * 2;
        int dist = 60 + r.nextInt(90);
        int x = near.getX() + (int) (Math.cos(ang) * dist);
        int z = near.getZ() + (int) (Math.sin(ang) * dist);
        int sy = piece != null ? piece.sy() : 24;
        int y = Math.min(VOID_Y, v.getMaxBuildHeight() - sy - 1);
        BlockPos at = new BlockPos(x, y, z);
        v.getChunk(x >> 4, z >> 4);
        if (piece != null) {
            return log(dir, piece.owner(), v, at, put(v, piece, at), "clan_void");
        }
        String name = TBS_STRUCTURES.get(r.nextInt(TBS_STRUCTURES.size()));
        Optional<StructureTemplate> tpl = server.getStructureManager().get(ResourceLocation.fromNamespaceAndPath(TbsWorld.NS, name));
        if (tpl.isEmpty() || tpl.get().getSize().getX() > 96 || tpl.get().getSize().getZ() > 96) {
            return null;
        }
        tpl.get().placeInWorld(v, at, at, new StructurePlaceSettings(), v.getRandom(), 2);
        return log(dir, "TBS:" + name, v, at, -1, "clan_void");
    }

    private static String log(Path dir, String owner, ServerLevel target, BlockPos at, int n, String kind) {
        String where = target.dimension().location() + " " + at.getX() + " " + at.getY() + " " + at.getZ();
        try {
            Files.writeString(dir.resolve("echoes.log"), java.time.LocalDateTime.now() + " | " + kind + " | " + owner + " | " + where
                    + (n >= 0 ? " | блоков " + n : "") + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // журнал не обязателен
        }
        return where;
    }
}
