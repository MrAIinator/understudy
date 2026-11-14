package tbs.director;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Which blocks a PLAYER placed. Echo copies only these — never generated structures (villages, TBS buildings, ruins).
 * One set of positions per dimension, saved in the world folder (world/tbsdirector/built/*.bin).
 * Tracking starts when Understudy 3.1.2+ is first loaded in a world; earlier builds are unknown and never copied.
 * All calls happen on the server thread.
 */
final class PlayerBuilt {
    private static final Map<String, LongOpenHashSet> SETS = new HashMap<>();
    private static Path dir;
    private static boolean dirty;

    private PlayerBuilt() {
    }

    static String key(Level l) {
        return l.dimension().location().toString();
    }

    static synchronized void load(Path worldDir) {
        SETS.clear();
        dirty = false;
        dir = worldDir.resolve("built");
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".bin")) {
                    continue;
                }
                String dim = name.substring(0, name.length() - 4).replaceFirst("__", ":");
                try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(f)))) {
                    int n = in.readInt();
                    LongOpenHashSet set = new LongOpenHashSet(Math.max(16, n));
                    for (int i = 0; i < n; i++) {
                        set.add(in.readLong());
                    }
                    SETS.put(dim, set);
                }
            }
        } catch (Exception e) {
            DirectorMod.LOG.warn("[understudy] could not read player-built blocks: {}", e.toString());
        }
    }

    static synchronized void save() {
        if (!dirty || dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            for (var en : SETS.entrySet()) {
                Path f = dir.resolve(en.getKey().replace(":", "__").replaceAll("[^\\w.-]", "_") + ".bin");
                Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
                    out.writeInt(en.getValue().size());
                    for (long v : en.getValue()) {
                        out.writeLong(v);
                    }
                }
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (Exception e) {
            DirectorMod.LOG.warn("[understudy] could not save player-built blocks: {}", e.toString());
        }
    }

    /** World closed: save and forget (single player can open another world next). */
    static synchronized void close() {
        save();
        SETS.clear();
        dir = null;
    }

    static synchronized void add(String dim, long pos) {
        if (SETS.computeIfAbsent(dim, k -> new LongOpenHashSet()).add(pos)) {
            dirty = true;
        }
    }

    static synchronized void remove(String dim, long pos) {
        LongOpenHashSet set = SETS.get(dim);
        if (set != null && set.remove(pos)) {
            dirty = true;
        }
    }

    static synchronized boolean has(String dim, long pos) {
        LongOpenHashSet set = SETS.get(dim);
        return set != null && set.contains(pos);
    }

    static synchronized int count(String dim) {
        LongOpenHashSet set = SETS.get(dim);
        return set == null ? 0 : set.size();
    }

    static synchronized void clearAll() {
        SETS.clear();
        dirty = true;
        if (dir != null) {
            try (var files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (f.getFileName().toString().endsWith(".bin")) {
                        Files.deleteIfExists(f);
                    }
                }
            } catch (Exception ignored) {
                // folder may not exist yet
            }
        }
    }

    static boolean has(Level l, BlockPos p) {
        return has(key(l), p.asLong());
    }

    /** A player placed this block. Doors, tall flowers and beds also mark their second half. */
    static void placed(Level l, BlockPos p, BlockState s) {
        String k = key(l);
        add(k, p.asLong());
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            add(k, (s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? p.above() : p.below()).asLong());
        }
        if (s.getBlock() instanceof BedBlock) {
            add(k, p.relative(BedBlock.getConnectedDirection(s)).asLong());
        }
    }

    static void broken(Level l, BlockPos p) {
        remove(key(l), p.asLong());
    }
}
