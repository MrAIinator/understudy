package tbs.director;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Мир медленно умирает. Стадия 0–5 растёт, пока на сервере играют люди (и от их смертей).
 * Вокруг игроков природа тихо увядает, падает пепел, животные замирают, на поздних стадиях
 * появляются «битые» блоки TBS. Постройки игроков не трогаем: меняем только природные блоки
 * и обходим места, где рядом есть что-то рукотворное.
 */
public final class Decay {
    static final int MAX_STAGE = 5;
    private static final String[] STAGE_TEXT = {
            "",
            "Трава стала суше. Ты заметил?",
            "Листья опадают не по сезону. Что-то уходит из этого мира.",
            "Звери перестали бояться. Они просто смотрят.",
            "Мир забывает собственные блоки. Ты видишь дыры в нём?",
            "Здесь почти ничего не осталось. Только ты. И я."
    };
    private static final String[] STAGE_TEXT_EN = {
            "",
            "The grass has gone dry. Did you notice?",
            "Leaves are falling out of season. Something is leaving this world.",
            "The animals stopped being afraid. They just stare.",
            "The world is forgetting its own blocks. Do you see the holes?",
            "There's almost nothing left here. Just you. And me."
    };
    /** Признаки рукотворного — рядом с ними природу не трогаем. */
    private static final Set<String> BUILT = Set.of("planks", "glass", "door", "chest", "bed", "torch", "lantern", "furnace",
            "crafting_table", "bricks", "wool", "slab", "stairs", "fence", "carpet", "barrel", "farmland", "rail", "sign", "concrete",
            "cobblestone", "polished_", "smooth_", "tiles", "terracotta", "quartz", "_wall", "pane", "trapdoor", "ladder", "lamp",
            "button", "pressure_plate", "lever", "_table", "bookshelf", "stripped_", "chiseled_", "cut_", "item_frame", "banner", "hay_block");

    private final Path file;
    int stage;
    long minutes;          // «минуты жизни» мира с игроками онлайн
    boolean enabled = true;
    private int secondsAcc;
    private int saveAcc;
    private final DirectorConfigView cfg;

    /** Только то, что нужно из конфига. */
    interface DirectorConfigView {
        int decayMinutesPerStage();
        boolean decayEnabled();
    }

    Decay(Path dir, DirectorConfigView cfg) {
        this.file = dir.resolve("decay.json");
        this.cfg = cfg;
        try {
            if (Files.exists(file)) {
                JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                stage = Math.min(MAX_STAGE, Math.max(0, o.get("stage").getAsInt()));
                minutes = o.get("minutes").getAsLong();
            }
        } catch (Exception e) {
            DirectorMod.LOG.warn("[understudy] decay.json не прочитался: {}", e.toString());
        }
    }

    void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("stage", stage);
            o.addProperty("minutes", minutes);
            Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            DirectorMod.LOG.warn("[understudy] decay.json не сохранился: {}", e.toString());
        }
    }

    /** По-русски: для ИИ и админки. */
    String describe() {
        return describe(true);
    }

    String describe(boolean ru) {
        if (!ru) {
            return switch (stage) {
                case 0 -> "The world is still alive.";
                case 1 -> "The world has begun to wither (stage 1 of 5): the grass is drying.";
                case 2 -> "The world is withering (stage 2 of 5): leaves fall, ash in the air.";
                case 3 -> "The world is dying (stage 3 of 5): animals freeze, nature dead in patches.";
                case 4 -> "The world is almost dead (stage 4 of 5): blocks lose themselves, the sky glitches.";
                default -> "The world is at death's door (stage 5 of 5): almost nothing alive.";
            };
        }
        return switch (stage) {
            case 0 -> "Мир ещё жив.";
            case 1 -> "Мир начал увядать (стадия 1 из 5): трава сохнет.";
            case 2 -> "Мир увядает (стадия 2 из 5): листья опадают, пепел в воздухе.";
            case 3 -> "Мир умирает (стадия 3 из 5): звери замирают, природа мертва пятнами.";
            case 4 -> "Мир почти мёртв (стадия 4 из 5): блоки теряют себя, небо сбоит.";
            default -> "Мир при смерти (стадия 5 из 5): почти ничего живого.";
        };
    }

    /** Смерть игрока приближает конец. */
    void onDeath() {
        minutes += 15;
    }

    void setStage(MinecraftServer server, int s) {
        stage = Math.max(0, Math.min(MAX_STAGE, s));
        minutes = (long) stage * cfg.decayMinutesPerStage();
        save();
        announce(server);
    }

    private void announce(MinecraftServer server) {
        if (stage <= 0) {
            return;
        }
        Hooks.SELF.set(true);
        try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(Component.literal((L.ru(p) ? STAGE_TEXT : STAGE_TEXT_EN)[stage]).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
                Causal.thunder(p); // вместо гула из ниоткуда — молния на горизонте, если видно небо
            }
        } finally {
            Hooks.SELF.set(false);
        }
    }

    /** Вызывается раз в 2 секунды. */
    void tick(MinecraftServer server, List<ServerPlayer> players) {
        if (!enabled || !cfg.decayEnabled() || players.isEmpty()) {
            return;
        }
        secondsAcc += 2;
        if (secondsAcc >= 60) {
            secondsAcc = 0;
            minutes++;
            int target = (int) Math.min(MAX_STAGE, minutes / Math.max(1, cfg.decayMinutesPerStage()));
            if (target > stage) {
                stage = target;
                save();
                announce(server);
                DirectorMod.LOG.info("[understudy] мир умирает: стадия {}", stage);
            }
            if (++saveAcc >= 5) {
                saveAcc = 0;
                save();
            }
        }
        if (stage == 0) {
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (ServerPlayer p : players) {
            ServerLevel l = p.serverLevel();
            if (l.dimension() != Level.OVERWORLD) {
                continue;
            }
            // пепел — постоянно, гуще с каждой стадией
            l.sendParticles(p, ParticleTypes.WHITE_ASH, false, p.getX(), p.getY() + 6, p.getZ(), stage * 12, 10, 4, 10, 0.0);
            if (stage >= 3) {
                l.sendParticles(p, ParticleTypes.ASH, false, p.getX(), p.getY() + 4, p.getZ(), stage * 4, 8, 3, 8, 0.0);
            }
            // природа увядает: несколько блоков каждые ~2 секунды с шансом от стадии
            if (r.nextInt(10) < stage + 1) {
                for (int i = 0; i < stage; i++) {
                    witherNear(l, p, r, 12, 40);
                }
            }
            if (stage >= 3 && r.nextInt(150) == 0) {
                freezeAnimal(server, p);
            }
            if (stage >= 2 && r.nextInt(900) == 0 && !l.isRaining()) {
                l.setWeatherParameters(0, 6000 + r.nextInt(6000), true, false);
            }
            if (stage >= 4 && r.nextInt(1200) == 0) {
                TbsBridge.run(r.nextBoolean() ? "moon_glitch" : "bad_sun", p);
            }
        }
    }

    /** «Увядание на глазах»: целый участок перед игроком умирает сразу. */
    boolean burst(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int n = 0;
        for (int i = 0; i < 80; i++) {
            if (witherNear(l, p, r, 3, 14)) {
                n++;
            }
        }
        if (n > 0) {
            l.playSound(null, p.blockPosition(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.AMBIENT, 1f, 0.5f);
            l.sendParticles(p, ParticleTypes.ASH, false, p.getX(), p.getY() + 1, p.getZ(), 120, 6, 2, 6, 0.0);
        }
        return n > 0;
    }

    private boolean witherNear(ServerLevel l, ServerPlayer p, ThreadLocalRandom r, int minR, int maxR) {
        double ang = r.nextDouble() * Math.PI * 2;
        int dist = minR + r.nextInt(Math.max(1, maxR - minR));
        int x = p.getBlockX() + (int) (Math.cos(ang) * dist);
        int z = p.getBlockZ() + (int) (Math.sin(ang) * dist);
        if (!l.hasChunkAt(new BlockPos(x, 0, z))) {
            return false;
        }
        BlockPos top = l.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
        for (int dy = 0; dy <= 6; dy++) {
            BlockPos pos = top.below(dy);
            BlockState s = l.getBlockState(pos);
            BlockState to = decayed(s, r);
            if (to != null) {
                if (nearBuilt(l, pos)) {
                    return false;
                }
                l.setBlock(pos, to, 3);
                return true;
            }
            if (!s.isAir() && !s.is(net.minecraft.tags.BlockTags.LEAVES) && !s.canBeReplaced()) {
                return false;
            }
        }
        return false;
    }

    private BlockState decayed(BlockState s, ThreadLocalRandom r) {
        if (s.is(Blocks.GRASS_BLOCK)) {
            return r.nextInt(3) == 0 ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.DIRT.defaultBlockState();
        }
        if (s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN) || s.is(net.minecraft.tags.BlockTags.SMALL_FLOWERS)) {
            return r.nextInt(3) == 0 ? Blocks.AIR.defaultBlockState() : Blocks.DEAD_BUSH.defaultBlockState();
        }
        if (stage >= 2 && s.is(net.minecraft.tags.BlockTags.LEAVES)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (stage >= 4 && r.nextInt(40) == 0 && (s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.STONE))) {
            Block missing = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(TbsWorld.NS, "block_is_missing_id"));
            if (missing != null && missing != Blocks.AIR) {
                return missing.defaultBlockState();
            }
        }
        return null;
    }

    static boolean isBuilt(BlockState s) {
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        for (String b : BUILT) {
            if (id.contains(b)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearBuilt(ServerLevel l, BlockPos c) {
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-4, -2, -4), c.offset(4, 3, 4))) {
            if (isBuilt(l.getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }

    /** Мелочь на глазах: одна травинка/кусочек травы у ног умирает. */
    boolean small(ServerPlayer p) {
        return witherNear(p.serverLevel(), p, ThreadLocalRandom.current(), 2, 8);
    }

    void freeze(MinecraftServer server, ServerPlayer p) {
        freezeAnimal(server, p);
    }

    private static void freezeAnimal(MinecraftServer server, ServerPlayer p) {
        List<Animal> near = p.serverLevel().getEntitiesOfClass(Animal.class, p.getBoundingBox().inflate(24),
                a -> !a.hasCustomName() && !a.isLeashed() && !a.isNoAi());
        if (near.isEmpty()) {
            return;
        }
        Animal a = near.get(ThreadLocalRandom.current().nextInt(near.size()));
        a.setNoAi(true);
        a.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, p.getEyePosition());
        a.setYHeadRot(a.getYRot());
        Sched.tell(new Sched.Later(400, () -> {
            if (a.isAlive()) {
                a.setNoAi(false);
            }
        }));
    }
}
