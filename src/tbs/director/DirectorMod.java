package tbs.director;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * TBS Director: следит, что делает каждый игрок, и в подходящий момент
 * просит Claude придумать, как его напугать. Только безобидные испуги:
 * звуки, шёпот, надписи, эффект темноты. Блоки, вещи и здоровье не трогает.
 */
@Mod("understudy")
public final class DirectorMod {
    static final Logger LOG = LogUtils.getLogger();
    private static final Pattern CALL = Pattern.compile(
            "(?iU)\\b(null|нулл|нул|кто\\s*(тут|здесь|там)|help|помоги|помогите|хиробрин|herobrine|ты\\s*кто|кто\\s*ты|entity|тбс|tbs|who\\s*(is|'s)?\\s*(there|here)|who\\s*are\\s*you|anyone\\s*(there|here))\\b");

    private final Path dir = configDir();

    /** config/understudy; до 3.1.0 — config/tbsdirector (переносим один раз, старая папка остаётся). */
    private static Path configDir() {
        Path root = FMLPaths.CONFIGDIR.get(), now = root.resolve("understudy"), old = root.resolve("tbsdirector");
        if (!java.nio.file.Files.exists(now) && java.nio.file.Files.isDirectory(old)) {
            try (var walk = java.nio.file.Files.walk(old)) {
                for (Path from : (Iterable<Path>) walk::iterator) {
                    Path to = now.resolve(old.relativize(from).toString());
                    if (java.nio.file.Files.isDirectory(from)) {
                        java.nio.file.Files.createDirectories(to);
                    } else {
                        java.nio.file.Files.copy(from, to);
                    }
                }
            } catch (Exception e) {
                LOG.warn("[understudy] could not copy config/tbsdirector: {}", e.toString());
            }
        }
        return now;
    }
    private final Brain brain = new Brain();
    private final Map<UUID, Watch> watched = new ConcurrentHashMap<>();
    private volatile DirectorConfig cfg = new DirectorConfig();
    private MinecraftServer server;
    private Decay decay;
    private Path worldDir;
    private Memory memory;
    private Hunt hunt;
    private Story story;
    /** Библиотека копий — в папке конфига: общая для всех миров. */
    private Path libDir;
    private long lastEcho;
    private long lastAheadGlobal;
    private long lastMimic;
    private int ticks;

    /** Что мы знаем о поведении игрока за последние минуты. */
    static final class Watch {
        double x, y, z;
        int stillSeconds;
        final ArrayDeque<Long> mined = new ArrayDeque<>();
        final ArrayDeque<Long> placed = new ArrayDeque<>();
        final ArrayDeque<String> chat = new ArrayDeque<>();
        final ArrayDeque<String> lines = new ArrayDeque<>();
        final ArrayDeque<String> actions = new ArrayDeque<>();
        final ArrayDeque<TbsEvent> tbs = new ArrayDeque<>();
        long nextScareAt;
        long lastChatReply;
        long lastDeath;
        long lastTbsAt;
        long lastTalkerAt;
        final java.util.Map<String, Long> voiceSeen = new java.util.HashMap<>(); // голос → когда его сущность была рядом
        long lastHardAt;
        long nextAmbientAt;
        long nextVoidAt;
        long lastAheadAt;
        long lastCaptureAt;
        long lastTrailAt;
        final ArrayDeque<double[]> trail = new ArrayDeque<>();
        long lastRepChange;
        boolean followUp;
        boolean pending;
    }

    record TbsEvent(long time, String id, String text) {}

    static final long VOICE_WINDOW = 120_000L;

    /** Голоса, которыми режиссёр может говорить сейчас: Null всегда, остальные — пока их сущность рядом. */
    static List<String> voices(Watch w, long now) {
        List<String> out = new ArrayList<>(List.of("Null"));
        synchronized (w) {
            for (String sp : Brain.SPEAKERS) {
                Long t = w.voiceSeen.get(sp);
                if (t != null && now - t < VOICE_WINDOW) {
                    out.add(sp);
                }
            }
        }
        return out;
    }

    public DirectorMod() {
        NeoForge.EVENT_BUS.addListener(this::onStarted);
        NeoForge.EVENT_BUS.addListener(this::onStopped);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onChat);
        NeoForge.EVENT_BUS.addListener(this::onBreak);
        NeoForge.EVENT_BUS.addListener(this::onPlace);
        NeoForge.EVENT_BUS.addListener(this::onDeath);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        NeoForge.EVENT_BUS.addListener(this::onCommands);
        NeoForge.EVENT_BUS.addListener(this::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(this::onEntityLeave);
        NeoForge.EVENT_BUS.addListener(this::onSleep);
        dev.mrainator.tbsecho.TbsEcho.register(); // сюжет «Протокол ЭХО» и старый бой
    }

    // ---------- сущности The Broken Script ----------

    private static String tbsId(Entity e) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return key != null && TbsWorld.NS.equals(key.getNamespace()) ? key.getPath() : null;
    }

    private void onEntityJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || e.loadedFromDisk()) {
            return;
        }
        if (Mimic.isCircuitFromMimic(e.getEntity())) {
            e.setCanceled(true); // TBS превращает ударенного мимика в «Схему» — у нас мимики свои
            return;
        }
        tbsEvent(e.getEntity(), "появился(ась)");
    }

    /** Во время охоты (и финала) поспать не выйдет: «рядом монстры». */
    private void onSleep(net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent e) {
        if (hunt != null && hunt.active) {
            e.setProblem(net.minecraft.world.entity.player.Player.BedSleepingProblem.NOT_SAFE);
        }
    }

    private void onAttack(net.neoforged.neoforge.event.entity.player.AttackEntityEvent e) {
        var p = e.getEntity();
        if (p.level().isClientSide()) {
            return;
        }
        float scale = p.getAttackStrengthScale(0.5f);
        float dmg = (float) p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) * (0.2f + scale * scale * 0.8f);
        if (Mimic.onHit(server, e.getTarget(), p, dmg)) {
            p.resetAttackStrengthTicker();
            e.setCanceled(true);
        }
    }

    private void onProjectile(net.neoforged.neoforge.event.entity.ProjectileImpactEvent e) {
        if (e.getProjectile().level().isClientSide()
                || !(e.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit)
                || !(e.getProjectile().getOwner() instanceof net.minecraft.world.entity.player.Player p)) {
            return;
        }
        float dmg = (float) Math.max(2.0, e.getProjectile().getDeltaMovement().length() * 2.0);
        if (Mimic.onHit(server, hit.getEntity(), p, dmg)) {
            e.setCanceled(true);
            e.getProjectile().discard();
        }
    }

    private void onEntityLeave(EntityLeaveLevelEvent e) {
        if (e.getLevel().isClientSide()) {
            return;
        }
        tbsEvent(e.getEntity(), "исчез(ла)");
    }

    private void tbsEvent(Entity ent, String what) {
        if (Scream.OURS.contains(ent.getUUID())) {
            return; // свои скримеры — не событие TBS
        }
        String id = tbsId(ent);
        if (!TbsWorld.relevant(id) || !(ent.level().getNearestPlayer(ent, 128) instanceof ServerPlayer p)) {
            return;
        }
        Watch w = watch(p);
        long now = now();
        int dist = (int) Math.round(ent.distanceTo(p));
        synchronized (w) {
            w.tbs.addLast(new TbsEvent(now, id, TbsWorld.name(id) + " " + what + " в " + dist + " блоках"));
            while (w.tbs.size() > 6) {
                w.tbs.pollFirst();
            }
        }
        w.lastTbsAt = now;
        if (what.startsWith("появ")) {
            remember(p, "видел " + TbsWorld.name(id), "saw:" + id, 6 * 3_600_000L);
        }
        if (TbsWorld.TALKERS.contains(id)) {
            w.lastTalkerAt = now;
        }
        if (TbsWorld.voiceOf(id) != null && dist <= 64) {
            synchronized (w) {
                w.voiceSeen.put(TbsWorld.voiceOf(id), now);
            }
        }
        // не перебиваем сцену мода: следующий испуг — продолжение этой истории, через 45–90 с
        if (what.startsWith("появ")) {
            w.nextScareAt = Math.max(w.nextScareAt, now + 45_000L + ThreadLocalRandom.current().nextLong(45_000L));
            w.followUp = true;
        }
    }

    // ---------- события ----------

    private void onStarted(ServerStartedEvent e) {
        server = e.getServer();
        cfg = DirectorConfig.load(dir);
        worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("tbsdirector");
        migrateToWorld();
        memory = new Memory(worldDir);
        hunt = new Hunt(worldDir);
        story = new Story(worldDir);
        libDir = dir;
        moveLibraryToConfig();
        startWeb();
        decay = new Decay(worldDir, new Decay.DirectorConfigView() {
            public int decayMinutesPerStage() { return cfg.decayMinutesPerStage; }
            public boolean decayEnabled() { return cfg.decayEnabled; }
        });
        syncDatapack();
        LOG.info("[understudy] мир: стадия {} из {}", decay.stage, Decay.MAX_STAGE);
        LOG.info("[understudy] включён={}, Claude={} ({}), Grok={} ({}), мозг={}, речь={}", cfg.enabled, !cfg.apiKey.isBlank(), cfg.model,
                !cfg.grokApiKey.isBlank(), cfg.grokModel, Brain.provider(cfg.brain, cfg),
                Brain.provider(cfg.speech, cfg));
    }

    /** Встроенный веб-сервер: только если владелец включил веб-архив или API лаунчера. Иначе портов не открываем. */
    private void startWeb() {
        if (!cfg.webArchiveEnabled && !cfg.launcherApi) {
            return;
        }
        try {
            tbs.stats.StatsServer.INSTANCE = tbs.stats.StatsServer.start(FMLPaths.CONFIGDIR.get().resolve("tbsstats"), cfg.webBind, cfg.webPort, cfg.launcherApi);
            LOG.info("[understudy] web server on {}:{} (archive={}, launcher={})", cfg.webBind, cfg.webPort, cfg.webArchiveEnabled, cfg.launcherApi);
        } catch (Exception ex) {
            LOG.error("[understudy] web server did not start (port {} busy or not allocated by the host?)", cfg.webPort, ex);
            dev.mrainator.tbsecho.TbsEcho.PUBLIC_URL.set("");
            return;
        }
        if (cfg.launcherApi) {
            AdminApi.register(server, this::apiStatus, this::apiAct);
        }
        if (cfg.webArchiveEnabled && !dev.mrainator.tbsecho.Archive.register(server)) {
            dev.mrainator.tbsecho.TbsEcho.PUBLIC_URL.set("");
        }
    }

    /** Мир закрыт (в одиночке это бывает много раз за запуск игры) — освобождаем порт и сбрасываем мимиков. */
    private void onStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e) {
        tbs.stats.StatsServer s = tbs.stats.StatsServer.INSTANCE;
        if (s != null) {
            s.stop();
        }
        Mimic.CHASES.clear();
        server = null;
    }

    /** Пока режиссёр включён, датапак tbs_fear не пугает по своему таймеру (иначе будет дважды). */
    private void syncDatapack() {
        run("scoreboard players set #enabled tbs_fear_cfg " + (cfg.enabled ? 0 : 1));
    }

    private void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        Watch w = watched.computeIfAbsent(e.getEntity().getUUID(), k -> new Watch());
        w.nextScareAt = now() + gap(false) * 1000L; // сначала дать освоиться
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        watched.remove(e.getEntity().getUUID());
    }

    private void onBreak(BlockEvent.BreakEvent e) {
        if (e.getPlayer() instanceof ServerPlayer p) {
            push(watch(p).mined, now());
        }
    }

    private void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            push(watch(p).placed, now());
        }
    }

    private void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            Watch w = watch(p);
            w.lastDeath = now();
            if (decay != null) {
                decay.onDeath();
            }
            if (hunt != null) {
                hunt.onDeath(p.getUUID());
            }
            BlockPos dp = p.blockPosition();
            boolean under = !p.serverLevel().canSeeSky(dp) && dp.getY() < p.serverLevel().getSeaLevel();
            String biome = p.serverLevel().getBiome(dp).unwrapKey().map(k -> k.location().getPath()).orElse("?");
            remember(p, "умер (" + e.getSource().getMsgId() + ") " + (under ? "под землёй" : "на поверхности") + ", биом " + biome, "death", 0);
            // через ~40 секунд после возрождения — «я предупреждал»
            w.nextScareAt = Math.min(w.nextScareAt, now() + 40_000L);
        }
    }

    private void onChat(ServerChatEvent e) {
        ServerPlayer p = e.getPlayer();
        String text = Lines.clean(e.getRawText());
        Watch w = watch(p);
        synchronized (w) {
            w.chat.addLast(text);
            if (server != null) {
                Mimic.onChat(server, p);
            }
            while (w.chat.size() > 4) {
                w.chat.pollFirst();
            }
        }
        if (!cfg.enabled || !cfg.replyToChat || !eligible(p) || !CALL.matcher(text).find() || server == null || !TbsBridge.nullArrived(server)) {
            return;
        }
        if (now() - w.lastChatReply < 60_000L || w.pending) {
            return;
        }
        // Лихорадка и фальшивые игроки сами отвечают в чате — не говорим поверх них
        if (now() - w.lastTalkerAt < 180_000L) {
            return;
        }
        remember(p, "спрашивал Null: «" + text + "»", "ask", 600_000L);
        w.lastChatReply = now();
        long saidAt = now();
        UUID id = p.getUUID();
        // Сначала даём ответить самому The Broken Script (у него свои ответы на «кто ты», «null» и т.п.).
        // Если за 8 секунд он заговорил — молчим. Иначе отвечаем мы.
        java.util.concurrent.CompletableFuture.delayedExecutor(8, java.util.concurrent.TimeUnit.SECONDS).execute(() -> server.execute(() -> {
            ServerPlayer pl = server.getPlayerList().getPlayer(id);
            if (pl == null || Hooks.lastTbsSpoke(id) >= saidAt - 500L) {
                return;
            }
            Situation s = snapshot(pl, w);
            s.chat = text;
            scare(id, s, 0, true);
        }));
    }

    private void onTick(ServerTickEvent.Post e) {
        Sched.tick();
        if (server != null) {
            Mimic.tick(server);
            Scream.tick(server);
        }
        if (++ticks % 40 != 0 || server == null) {
            return;
        }
        // всё начинается только после того, как Null пришёл на сервер (сюжет TBS)
        if (!TbsBridge.nullArrived(server)) {
            return;
        }
        if (decay != null) {
            decay.tick(server, server.getPlayerList().getPlayers());
            if (hunt != null) {
                hunt.tick(server, decay.stage >= 1 && story.chapter >= 3, (pl, c) -> mimic(pl, c));
                story.tick(server, decay, hunt);
            }
            Echo.decayStage = decay.stage;
        }
        if (!cfg.enabled) {
            return;
        }
        long now = now();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Watch w = watch(p);
            double dx = p.getX() - w.x, dy = p.getY() - w.y, dz = p.getZ() - w.z;
            w.stillSeconds = dx * dx + dy * dy + dz * dz < 0.04 ? w.stillSeconds + 2 : 0;
            w.x = p.getX();
            w.y = p.getY();
            w.z = p.getZ();
            if (!eligible(p) || w.pending) {
                continue;
            }
            // мелочи между испугами: мир живёт сам, без ИИ
            if (cfg.ambientEnabled && now >= w.nextAmbientAt) {
                w.nextAmbientAt = now + 60_000L + ThreadLocalRandom.current().nextLong(120_000L);
                ambient(p);
            }
            travel(p, w, now);
            // clan_void: на высоте 252 появляются постройки игроков и родные структуры TBS
            if (p.serverLevel().dimension().equals(Echo.CLAN_VOID) && story.chapter >= 3 && now >= w.nextVoidAt) {
                w.nextVoidAt = now + 180_000L + ThreadLocalRandom.current().nextLong(180_000L);
                String vw = Echo.placeVoid(server, ThreadLocalRandom.current().nextBoolean() ? Echo.load(libDir, null) : null, p.blockPosition(), worldDir);
                if (vw != null) {
                    LOG.info("[understudy] clan_void: постройка у {} -> {}", p.getGameProfile().getName(), vw);
                }
            }
            // библиотека: тихо запоминаем постройки, у которых стоит игрок
            if (cfg.echoEnabled && now - w.lastCaptureAt > 1_200_000L && ThreadLocalRandom.current().nextInt(300) == 0) {
                w.lastCaptureAt = now;
                Echo.Piece lib = Echo.capture(p, decay == null ? 0 : decay.stage);
                if (lib != null) {
                    Echo.save(libDir, lib);
                }
            }
            // мимики: на поздних стадиях, не к тем, кого Null уважает
            if (story.chapter >= 2 && now - lastMimic > 1_800_000L && ThreadLocalRandom.current().nextInt(2400) == 0
                    && !"GOOD".equals(TbsBridge.repTier(TbsBridge.reputation(p))) && mimic(p, 0)) {
                continue;
            }
            // эхо: иногда мир забирает копию постройки
            if (cfg.echoEnabled && story.chapter >= 2 && now - lastEcho > cfg.echoMinutes * 60_000L
                    && ThreadLocalRandom.current().nextInt(600) == 0 && echo(p, true)) {
                continue;
            }
            if (now < w.nextScareAt) {
                continue;
            }
            Situation s = snapshot(p, w);
            // сущность TBS совсем рядом — её сцена ещё идёт, подождём
            if (s.tbsClose) {
                w.nextScareAt = now + 30_000L;
                w.followUp = true;
                continue;
            }
            // TBS только что писал игроку — не говорим следом
            if (now - Hooks.lastTbsSpoke(p.getUUID()) < 60_000L) {
                w.nextScareAt = now + 45_000L;
                continue;
            }
            boolean follow = w.followUp;
            w.followUp = false;
            long g = gap(s.alone && (s.night || s.dark || s.underground));
            // репутация: на плохих Null набрасывается чаще, хороших — тревожит реже
            g = switch (s.repTier()) {
                case "BAD" -> g * 6 / 10;
                case "GOOD" -> g * 3 / 2;
                default -> g;
            };
            w.nextScareAt = now + (long) (g * 1000L / cfg.aiActivity);
            // экономия: ИИ придумывает только часть плановых испугов; продолжение сцены TBS — всегда ИИ
            scare(p.getUUID(), s, 0, follow || ThreadLocalRandom.current().nextInt(100) < cfg.aiChancePercent);
        }
    }

    // ---------- испуг ----------

    private void scare(UUID id, Situation s, long delayMs, boolean useAi) {
        Watch w = watched.get(id);
        if (w == null) {
            return;
        }
        w.pending = true;
        java.util.concurrent.CompletableFuture<Brain.Decision> ask = useAi
                ? brain.decide(cfg, s.describe(), s.ru)
                : java.util.concurrent.CompletableFuture.completedFuture(null);
        ask.thenAccept(decision -> {
            Brain.Decision d = decision != null ? decision : Lines.fallback(s);
            Runnable act = () -> server.execute(() -> {
                w.pending = false;
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null && cfg.enabled) {
                    perform(p, w, d, decision != null, s);
                }
            });
            if (delayMs > 0) {
                java.util.concurrent.CompletableFuture.delayedExecutor(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS).execute(act);
            } else {
                act.run();
            }
        });
    }

    /** Можно ли сейчас это действие этому игроку: уровень в конфиге, репутация, перезарядка. */
    private boolean permitted(Catalog.Act a, ServerPlayer p, Watch w) {
        if (a == null || !Catalog.allowed(a, cfg)) {
            return false;
        }
        String tier = TbsBridge.repTier(TbsBridge.reputation(p));
        if (a.tier() == Catalog.Tier.HARD || a.tier() == Catalog.Tier.EXTREME) {
            if ("GOOD".equals(tier)) {
                return false; // к тем, кого Null уважает, — без вреда
            }
            if (a.tier() == Catalog.Tier.EXTREME && !"BAD".equals(tier)) {
                return false; // необратимое — только тем, кто его разозлил
            }
            return now() - w.lastHardAt > cfg.hardCooldownSeconds * 1000L;
        }
        return true;
    }

    /** Данные мира (стадия, библиотека, память) живут в папке мира. Старые из config переносим один раз. */
    private void migrateToWorld() {
        try {
            java.nio.file.Files.createDirectories(worldDir);
            for (String name : new String[] {"decay.json", "echoes.log"}) { // echoes — в конфиге, общие для миров
                Path from = dir.resolve(name), to = worldDir.resolve(name);
                if (java.nio.file.Files.exists(from) && !java.nio.file.Files.exists(to)) {
                    java.nio.file.Files.move(from, to);
                    LOG.info("[understudy] перенёс {} в папку мира", name);
                }
            }
        } catch (Exception ex) {
            LOG.warn("[understudy] перенос в папку мира не удался: {}", ex.toString());
        }
    }

    /** Копии построек теперь в config/tbsdirector/echoes — переживают смену мира. Старые из папки мира переносим. */
    private void moveLibraryToConfig() {
        try {
            Path from = worldDir.resolve("echoes"), to = libDir.resolve("echoes");
            if (!java.nio.file.Files.isDirectory(from)) {
                return;
            }
            java.nio.file.Files.createDirectories(to);
            int moved = 0;
            try (var files = java.nio.file.Files.list(from)) {
                for (Path f : files.toList()) {
                    Path dst = to.resolve(f.getFileName());
                    if (!java.nio.file.Files.exists(dst)) {
                        java.nio.file.Files.move(f, dst);
                        moved++;
                    }
                }
            }
            LOG.info("[understudy] библиотека копий: перенёс {} шт. в config/tbsdirector/echoes (общая для всех миров)", moved);
        } catch (Exception ex) {
            LOG.warn("[understudy] перенос библиотеки не удался: {}", ex.toString());
        }
    }

    private void remember(ServerPlayer p, String text, String key, long cooldownMs) {
        if (memory != null) {
            memory.remember(p.getUUID(), text, key, cooldownMs);
        }
    }

    // ---------- вкладка «Режиссёр» в админке ----------

    private String apiStatus() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("enabled", cfg.enabled);
        o.addProperty("nullArrived", TbsBridge.nullArrived(server));
        o.addProperty("stage", decay == null ? 0 : decay.stage);
        o.addProperty("chapter", story == null ? 0 : story.chapter);
        o.addProperty("chapterName", story == null ? "" : story.describe());
        o.addProperty("cycle", story == null ? 0 : story.cycle);
        o.addProperty("minutes", decay == null ? 0 : decay.minutes);
        o.addProperty("minutesPerStage", cfg.decayMinutesPerStage);
        o.addProperty("hunt", hunt != null && hunt.active);
        o.addProperty("aiHour", brain.callsLastHour());
        o.addProperty("aiDay", brain.callsLastDay());
        o.addProperty("model", cfg.model);
        o.addProperty("ai", !cfg.apiKey.isBlank() || !cfg.grokApiKey.isBlank());
        o.addProperty("aiClaude", !cfg.apiKey.isBlank());
        o.addProperty("aiGrok", !cfg.grokApiKey.isBlank());
        o.addProperty("aiLast", Brain.lastSource);
        com.google.gson.JsonArray ps = new com.google.gson.JsonArray();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            com.google.gson.JsonObject j = new com.google.gson.JsonObject();
            j.addProperty("name", p.getGameProfile().getName());
            int rep = TbsBridge.reputation(p);
            j.addProperty("rep", rep);
            j.addProperty("tier", TbsBridge.repTier(rep));
            j.addProperty("dim", p.serverLevel().dimension().location().getPath());
            com.google.gson.JsonArray mem = new com.google.gson.JsonArray();
            if (memory != null) {
                memory.recall(p.getUUID(), 4).forEach(mem::add);
            }
            j.add("memory", mem);
            ps.add(j);
        }
        o.add("players", ps);
        return o.toString();
    }

    private String apiAct(String body) {
        com.google.gson.JsonObject in = com.google.gson.JsonParser.parseString(body == null || body.isBlank() ? "{}" : body).getAsJsonObject();
        String action = in.has("action") ? in.get("action").getAsString() : "";
        String name = in.has("player") ? in.get("player").getAsString() : "";
        ServerPlayer p = name.isEmpty() ? null : server.getPlayerList().getPlayerByName(name);
        String msg;
        switch (action) {
            case "enable" -> { cfg.enabled = true; syncDatapack(); msg = "режиссёр включён"; }
            case "disable" -> { cfg.enabled = false; syncDatapack(); msg = "режиссёр выключен"; }
            case "hunt_start" -> { hunt.start(server, server.overworld().getDayTime() / 24000L); msg = "охота началась"; }
            case "hunt_end" -> { hunt.end(server); msg = "охота закончена"; }
            case "stage" -> { decay.setStage(server, in.get("value").getAsInt()); msg = decay.describe(); }
            case "chapter" -> { story.setManual(server, in.get("value").getAsInt(), decay); msg = story.describe(); }
            default -> {
                if (p == null) {
                    return "{\"ok\":false,\"msg\":\"игрок не в сети\"}";
                }
                msg = switch (action) {
                    case "scare" -> { Situation fs = snapshot(p, watch(p)); fs.forceWorld = true; scare(p.getUUID(), fs, 0, true); yield "думаю, как напугать " + name; }
                    case "mimic" -> mimic(p, 0) ? "мимики идут к " + name : "некого звать (или оберег)";
                    case "ward" -> { Ward.give(p); yield "оберег выдан " + name; }
                    case "flash" -> Scream.flash(server, p) ? "скример у " + name : "не вышло";
                    case "watch" -> Scream.watch(server, p) ? "за спиной у " + name + " кто-то стоит" : "нет места за спиной";
                    case "echo" -> echo(p, true) ? "эхо поставлено" : "рядом нет постройки";
                    case "ahead" -> {
                        Echo.Piece piece = Echo.load(libDir, null);
                        net.minecraft.world.phys.Vec3 look = p.getLookAngle();
                        double len = Math.max(0.01, Math.hypot(look.x, look.z));
                        yield piece == null ? "библиотека пуста" : "впереди: " + Echo.placeAhead(piece, p, look.x / len, look.z / len, worldDir);
                    }
                    default -> "неизвестное действие";
                };
            }
        }
        AdminApi.feed("[админ] " + msg);
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        out.addProperty("ok", true);
        out.addProperty("msg", msg);
        return out.toString();
    }

    /** Тихая мелочь: дверь, факел, зверь, увядшая трава, сундук, дыхание. */
    private void ambient(ServerPlayer p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // вместе с друзьями — чаще «личное»: видит только он
        if (Split.together(p, server.getPlayerList().getPlayers()) && r.nextBoolean() && Split.any(p)) {
            return;
        }
        int stage = decay == null ? 0 : decay.stage;
        for (int tries = 0; tries < 3; tries++) {
            boolean ok = switch (r.nextInt(7)) {
                case 0 -> WorldActs.doorCreak(p);
                case 1 -> (p.serverLevel().getMaxLocalRawBrightness(p.blockPosition()) < 10 || stage >= 2) && WorldActs.torchesOut(p, 1);
                case 2 -> WorldActs.chestOpen(p);
                case 3 -> decay != null && decay.small(p);
                case 4 -> { if (decay != null && stage >= 1) { decay.freeze(server, p); yield true; } yield false; }
                case 5 -> Causal.cave(p) || Causal.knock(p);
                default -> Split.any(p);
            };
            if (ok) {
                return;
            }
        }
    }

    /** Копия постройки игрока: в библиотеку и сразу далеко в обычный мир или на 252 в clan_void. */
    private boolean echo(ServerPlayer p, boolean auto) {
        Echo.Piece piece = Echo.capture(p, decay == null ? 0 : decay.stage);
        if (piece == null) {
            return false;
        }
        Echo.save(libDir, piece);
        boolean toVoid = server.getLevel(Echo.CLAN_VOID) != null && ThreadLocalRandom.current().nextBoolean();
        String where = toVoid ? Echo.placeVoid(server, piece, p.blockPosition(), worldDir) : Echo.placeFar(server, piece, p.blockPosition(), worldDir);
        if (where == null) {
            return false;
        }
        lastEcho = now();
        remember(p, "Null забрал копию его постройки", "echo", 0);
        AdminApi.feed("эхо постройки " + piece.owner() + " → " + where);
        LOG.info("[understudy] эхо постройки {} ({} блоков) -> {}", piece.owner(), piece.states().size(), where);
        if (auto) {
            Hooks.SELF.set(true);
            try {
                p.sendSystemMessage(whisper(Lines.echoLine(L.ru(p))));
            } finally {
                Hooks.SELF.set(false);
            }
        }
        return true;
    }

    /** Набег мимиков. count=0 — по репутации: BAD — 2–3, иначе один. */
    private boolean mimic(ServerPlayer p, int count) {
        if (count <= 0) {
            count = "BAD".equals(TbsBridge.repTier(TbsBridge.reputation(p))) ? 2 + ThreadLocalRandom.current().nextInt(2) : 1;
        }
        if (Ward.blocks(p, "mimic_raid")) {
            return false;
        }
        int n = Mimic.raid(server, p, count, cfg.mimicSelfFallback);
        if (n > 0) {
            lastMimic = now();
            remember(p, "к нему зашли мимики под видом игроков (" + n + ")", "mimic", 0);
            AdminApi.feed("мимики (" + n + ") напали на " + p.getGameProfile().getName());
        }
        return n > 0;
    }

    /** Долгий путь в одну сторону — впереди, в 100 блоках, вырастает чья-то постройка. */
    private void travel(ServerPlayer p, Watch w, long now) {
        if (now - w.lastTrailAt < 10_000L) {
            return;
        }
        w.lastTrailAt = now;
        if (!p.serverLevel().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            w.trail.clear();
            return;
        }
        w.trail.addLast(new double[] {p.getX(), p.getZ()});
        while (w.trail.size() > 18) {
            w.trail.pollFirst();
        }
        if (!cfg.echoEnabled || story.chapter < 3 || w.trail.size() < 12 || now - w.lastAheadAt < 1_500_000L || now - lastAheadGlobal < 480_000L) {
            return;
        }
        double[] a = w.trail.peekFirst();
        double[] b = w.trail.peekLast();
        double dx = b[0] - a[0], dz = b[1] - a[1], dist = Math.hypot(dx, dz);
        double path = 0;
        double[] prev = null;
        for (double[] s : w.trail) {
            if (prev != null) {
                path += Math.hypot(s[0] - prev[0], s[1] - prev[1]);
            }
            prev = s;
        }
        if (dist < 350 || path > dist * 1.3) {
            return;
        }
        // глава IV: мир вспоминает — чаще всего старый дом самого игрока
        Echo.Piece piece = story.chapter >= 4 && ThreadLocalRandom.current().nextBoolean()
                ? Echo.loadOldest(libDir, p.getGameProfile().getName()) : null;
        if (piece == null) {
            piece = Echo.load(libDir, null); // любая постройка, в том числе своя
        }
        if (piece == null) {
            return;
        }
        String where = Echo.placeAhead(piece, p, dx / dist, dz / dist, worldDir);
        w.lastAheadAt = now;
        lastAheadGlobal = now;
        w.trail.clear();
        LOG.info("[understudy] путник {}: впереди постройка {} -> {}", p.getGameProfile().getName(), piece.owner(), where);
    }
    /** Выполняет действие; false — если делать нечего (нет сундука, факелов, событие TBS отказалось). */
    private boolean act(String id, ServerPlayer p, String line) {
        if (!"whisper".equals(id) && Ward.blocks(p, id)) {
            return true; // оберег погасил — это тоже событие
        }
        if (id.startsWith("tbs:")) {
            String ev = id.substring(4);
            // звуковые события TBS — только когда у звука есть видимый источник
            if (Causal.SOUND_ONLY.contains(ev) && !Scream.watch(server, p)) {
                return false;
            }
            if ("heartbeat".equals(ev) && p.getHealth() > 10f && !Scream.watch(server, p)) {
                return false;
            }
            return TbsBridge.run(ev, p);
        }
        return switch (id) {
            case "whisper" -> true;
            case "torches_out" -> WorldActs.torchesOut(p);
            case "chest_open" -> WorldActs.chestOpen(p);
            case "chest_shuffle" -> WorldActs.chestShuffle(p);
            case "look_behind" -> WorldActs.lookBehind(p);
            case "glass_break" -> WorldActs.glassBreak(p);
            case "wither_burst" -> decay != null && decay.burst(p);
            case "door_creak" -> WorldActs.doorCreak(p);
            case "private_vision" -> Split.any(p);
            case "echo_build" -> echo(p, false);
            case "mimic_raid" -> story.chapter >= 2 && mimic(p, 0);
            case "sign" -> WorldActs.sign(p, line);
            case "hurt" -> Scream.flash(server, p) && WorldActs.hurt(p); // удар наносит тот, кого видно
            case "knock" -> Causal.knock(p);
            case "footsteps" -> Causal.footsteps(server, p);
            case "cave" -> Causal.cave(p);
            case "thunder" -> Causal.thunder(p);
            case "darkness" -> Causal.darkness(p);
            case "summon_hunter" -> WorldActs.summon(p, true);
            case "summon_watcher" -> WorldActs.summon(p, false);
            case "scream_flash" -> Scream.flash(server, p);
            case "scream_watch" -> Scream.watch(server, p);
            case "advancement" -> fakeAdvancement(p);
            case "self_message" -> selfMessage(p);
            default -> {
                run("execute as " + p.getUUID() + " at @s run function tbs_fear:event/" + id);
                yield true;
            }
        };
    }

    private void perform(ServerPlayer p, Watch w, Brain.Decision d, boolean ai, Situation s) {
        String line = Lines.clean(d.line());
        String action = d.action();
        // шёпот дважды подряд (кроме ответа в чате) или по просьбе админа — меняем на действие в мире
        boolean lastWasWhisper;
        synchronized (w) {
            lastWasWhisper = !w.actions.isEmpty() && "whisper".equals(w.actions.peekLast());
        }
        if ("whisper".equals(action) && s.chat == null && (lastWasWhisper || s.forceWorld)) {
            action = Lines.worldPick(s);
        }
        Catalog.Act a = Catalog.ALL.get(action);
        boolean done = permitted(a, p, w) && act(action, p, line);
        if (!done && !"whisper".equals(action)) { // не вышло — пробуем другое действие в мире
            String alt = Lines.worldPick(s);
            Catalog.Act aa = Catalog.ALL.get(alt);
            if (permitted(aa, p, w) && act(alt, p, line)) {
                action = alt;
                a = aa;
                done = true;
            }
        }
        if (!done) {
            action = "whisper"; // нельзя или нечего делать — остаётся только голос
            if (line.isEmpty()) {
                line = Lines.fallback(snapshot(p, w)).line();
            }
        } else if (a.tier() == Catalog.Tier.HARD || a.tier() == Catalog.Tier.EXTREME) {
            w.lastHardAt = now();
        }
        // последствия для репутации — не чаще раза в 10 минут
        if (!"none".equals(d.rep()) && now() - w.lastRepChange > 600_000L && TbsBridge.changeRep(p, "gain".equals(d.rep()))) {
            w.lastRepChange = now();
            remember(p, "gain".equals(d.rep()) ? "был уважителен к Null" : "грубил Null", "rep", 1_800_000L);
            LOG.info("[understudy] {}: репутация {}", p.getGameProfile().getName(), "gain".equals(d.rep()) ? "+" : "−");
        }
        // говорить чужим голосом можно, только пока его сущность рядом или была рядом последние 2 минуты
        String speaker = voices(w, now()).contains(d.speaker()) ? d.speaker() : "Null";
        boolean speak = !line.isEmpty() && !"sign".equals(action);
        if (speak || "whisper".equals(action)) {
            if (line.isEmpty()) {
                line = "...";
            }
            Hooks.SELF.set(true);
            try {
                if ("Null".equals(speaker)) {
                    p.sendSystemMessage(whisper(line));
                } else {
                    // реплика в чат в стиле самого мода: <Лихорадка> ... (англ. игроку — <Fever> ...)
                    p.sendSystemMessage(Component.literal("<" + L.speaker(speaker, L.ru(p)) + "> " + line));
                }
            } finally {
                Hooks.SELF.set(false);
            }
            synchronized (w) {
                w.lines.addLast(line);
                while (w.lines.size() > 6) {
                    w.lines.pollFirst();
                }
            }
        }
        synchronized (w) {
            w.actions.addLast(action);
            while (w.actions.size() > 5) {
                w.actions.pollFirst();
            }
        }
        AdminApi.feed(p.getGameProfile().getName() + " ← " + action + (ai ? " (ИИ)" : "") + (line.isEmpty() ? "" : " «" + line + "»"));
        // после любого нашего хода — пауза минимум 90 с, чтобы не было «очереди» реплик
        w.nextScareAt = Math.max(w.nextScareAt, now() + 90_000L);
        LOG.info("[understudy] {} ← {}{}{} [{}] «{}» (реп {})", p.getGameProfile().getName(), action, ai ? " (ИИ)" : "",
                action.equals(d.action()) ? "" : " вместо " + d.action(), speaker.equals(d.speaker()) ? speaker : speaker + " вместо " + d.speaker(), line, TbsBridge.reputation(p));
    }

    // ---------- анализ ----------

    private Situation snapshot(ServerPlayer p, Watch w) {
        long now = now();
        ServerLevel level = p.serverLevel();
        BlockPos pos = p.blockPosition();
        Situation s = new Situation();
        s.name = p.getGameProfile().getName();
        s.ru = L.ru(p);
        s.dimension = level.dimension().location().getPath();
        s.biome = level.getBiome(pos).unwrapKey().map(k -> k.location().getPath()).orElse("?");
        s.y = pos.getY();
        s.light = level.getMaxLocalRawBrightness(pos);
        s.underground = !level.canSeeSky(pos) && pos.getY() < level.getSeaLevel();
        s.dark = s.light <= 4;
        long day = level.getDayTime() % 24000L;
        s.night = day >= 13000 && day <= 23000;
        double best = -1;
        for (ServerPlayer o : level.players()) {
            if (o != p) {
                double d = o.distanceTo(p);
                if (best < 0 || d < best) {
                    best = d;
                }
            }
        }
        s.nearestPlayer = (int) Math.round(best);
        s.alone = best < 0 || best > 48;
        s.afkSeconds = w.stillSeconds;
        s.mined = count(w.mined, now, 180_000L);
        s.placed = count(w.placed, now, 180_000L);
        s.health = p.getHealth();
        s.maxHealth = p.getMaxHealth();
        s.recentlyDied = w.lastDeath > 0 && now - w.lastDeath < 300_000L;
        s.sleeping = p.isSleeping();
        s.held = p.getMainHandItem().isEmpty() ? "" : p.getMainHandItem().getHoverName().getString();
        synchronized (w) {
            s.recentChat = new ArrayList<>(w.chat);
            s.pastLines = new ArrayList<>(w.lines);
            s.pastActions = new ArrayList<>(w.actions);
            List<String> ev = new ArrayList<>();
            for (TbsEvent t : w.tbs) {
                if (now - t.time() < 600_000L) {
                    ev.add(t.text() + " (" + (now - t.time()) / 1000 + " с назад)");
                }
            }
            s.tbsEvents = ev;
        }
        List<String> near = new ArrayList<>();
        for (Entity ent : level.getEntities(p, p.getBoundingBox().inflate(64))) {
            String id = tbsId(ent);
            if (TbsWorld.relevant(id)) {
                int dist = (int) Math.round(ent.distanceTo(p));
                near.add(TbsWorld.name(id) + ", " + dist + " блоков");
                if (TbsWorld.voiceOf(id) != null) {
                    synchronized (w) {
                        w.voiceSeen.put(TbsWorld.voiceOf(id), now);
                    }
                }
                if (dist <= 32) {
                    s.tbsClose = true;
                }
            }
        }
        s.nearbyTbs = near;
        s.voices = voices(w, now);
        s.reputation = TbsBridge.reputation(p);
        s.memories = memory == null ? List.of() : memory.recall(p.getUUID(), 6);
        s.worldState = (decay == null ? "" : decay.describe()) + (story == null || story.mood().isEmpty() ? "" : "\n" + story.mood());
        String echoMood = dev.mrainator.tbsecho.Director.mood(p);
        if (!echoMood.isEmpty()) {
            s.worldState += "\n" + echoMood;
        }
        return s;
    }

    private static boolean eligible(ServerPlayer p) {
        return !p.isCreative() && !p.isSpectator() && !p.getTags().contains("tbs_nofear");
    }

    private Watch watch(ServerPlayer p) {
        return watched.computeIfAbsent(p.getUUID(), k -> {
            Watch w = new Watch();
            w.nextScareAt = now() + gap(false) * 1000L;
            return w;
        });
    }

    private long gap(boolean tense) {
        long g = ThreadLocalRandom.current().nextLong(cfg.minGapSeconds, cfg.maxGapSeconds + 1L);
        return tense ? g / 2 : g;
    }

    private static void push(ArrayDeque<Long> q, long t) {
        synchronized (q) {
            q.addLast(t);
            while (q.size() > 500) {
                q.pollFirst();
            }
        }
    }

    private static int count(ArrayDeque<Long> q, long now, long window) {
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > window) {
                q.pollFirst();
            }
            return q.size();
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /**
     * «Null шепчет вам: …» — ванильный ключ личного сообщения: клиент сам подставит свой язык
     * (ru_ru: «%s шепчет вам: %s», en_us: «%s whispers to you: %s»).
     */
    static Component whisper(String line) {
        return Component.translatable("commands.message.display.incoming", "Null", line).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    }

    /** Фальшивая ачивка (раньше — tbs_fear:event/advancement): теперь на языке игрока. */
    private static boolean fakeAdvancement(ServerPlayer p) {
        boolean ru = L.ru(p);
        Component adv = Component.literal(L.tr(ru, "[Не оглядывайся]", "[Don't Look Back]")).withStyle(s -> s.withColor(ChatFormatting.DARK_PURPLE)
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                        Component.literal(L.tr(ru, "Он уже за твоей спиной", "He's already behind you")).withStyle(ChatFormatting.DARK_RED))));
        Hooks.SELF.set(true);
        try {
            p.sendSystemMessage(Component.empty().append(p.getDisplayName())
                    .append(L.tr(ru, " получил достижение ", " has made the advancement ")).append(adv));
        } finally {
            Hooks.SELF.set(false);
        }
        p.playNotifySound(net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, net.minecraft.sounds.SoundSource.MASTER, 0.5f, 0.5f);
        return true;
    }

    /** «<ник> помогите» от имени самого игрока (раньше — tbs_fear:event/self_message). */
    private boolean selfMessage(ServerPlayer p) {
        Hooks.SELF.set(true);
        try {
            p.sendSystemMessage(Component.literal("<").append(p.getDisplayName()).append("> " + L.tr(p, "помогите", "help me")));
        } finally {
            Hooks.SELF.set(false);
        }
        // продолжение сцены датапака (followup), как в self_message.mcfunction
        run("scoreboard players set " + p.getGameProfile().getName() + " tbs_fear_k 5");
        run("scoreboard players set " + p.getGameProfile().getName() + " tbs_fear_f 3");
        return true;
    }

    private void run(String command) {
        if (server == null) {
            return;
        }
        CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        server.getCommands().performPrefixedCommand(src, command);
    }

    // ---------- команды /null ----------

    private void onCommands(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        NullAiCommand.register(d, () -> cfg, dir);
        d.register(Commands.literal("null").requires(s -> s.hasPermission(2))
                .then(Commands.literal("status").executes(c -> {
                    boolean ru = L.ru(c.getSource());
                    c.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, ru
                            ? (TbsBridge.nullArrived(server) ? "[Null пришёл] " : "[Null ещё не пришёл — события ждут] ") + "[Null] %s, ИИ: %s (%s, %d%% испугов), запросов: за час %d/%d, за сутки %d/%d, пауза %d–%d с"
                            : (TbsBridge.nullArrived(server) ? "[Null has arrived] " : "[Null hasn't arrived yet — events are waiting] ") + "[Null] %s, AI: %s (%s, %d%% of scares), requests: last hour %d/%d, last day %d/%d, gap %d–%d s",
                            cfg.enabled ? L.tr(ru, "включён", "enabled") : L.tr(ru, "выключен", "disabled"),
                            cfg.apiKey.isBlank() && cfg.grokApiKey.isBlank() ? L.tr(ru, "нет ключа — готовые фразы", "no key — canned lines") : L.tr(ru, "да", "yes"),
                            cfg.model, cfg.aiChancePercent, brain.callsLastHour(), cfg.maxAiCallsPerHour,
                            brain.callsLastDay(), cfg.maxAiCallsPerDay, cfg.minGapSeconds, cfg.maxGapSeconds)), false);
                    return 1;
                }))
                .then(Commands.literal("on").executes(c -> {
                    cfg.enabled = true;
                    syncDatapack();
                    c.getSource().sendSuccess(() -> Component.literal(L.tr(c.getSource(), "[Null] режиссёр включён", "[Null] director enabled")), true);
                    return 1;
                }))
                .then(Commands.literal("off").executes(c -> {
                    cfg.enabled = false;
                    syncDatapack();
                    c.getSource().sendSuccess(() -> Component.literal(L.tr(c.getSource(), "[Null] режиссёр выключен, пугает только датапак", "[Null] director disabled, only the datapack scares now")), true);
                    return 1;
                }))
                .then(Commands.literal("reload").executes(c -> {
                    cfg = DirectorConfig.load(dir);
                    syncDatapack();
                    c.getSource().sendSuccess(() -> Component.literal(L.tr(c.getSource(), "[Null] настройки перечитаны", "[Null] config reloaded")), true);
                    return 1;
                }))
                .then(Commands.literal("scare").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                    Situation fs = snapshot(p, watch(p));
                    fs.forceWorld = true;
                    scare(p.getUUID(), fs, 0, true);
                    c.getSource().sendSuccess(() -> Component.literal(L.tr(c.getSource(), "[Null] думаю, как напугать ", "[Null] thinking how to scare ") + p.getGameProfile().getName() + "…"), false);
                    return 1;
                })))
                .then(Commands.literal("ward").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                    Ward.give(p);
                    c.getSource().sendSuccess(() -> Component.literal(L.tr(c.getSource(), "[Null] оберег выдан " + p.getGameProfile().getName() + " (тест)",
                            "[Null] ward given to " + p.getGameProfile().getName() + " (test)")), true);
                    return 1;
                })))
                .then(Commands.literal("hunt").then(Commands.literal("start").executes(c -> {
                    hunt.start(server, server.overworld().getDayTime() / 24000L);
                    c.getSource().sendSuccess(() -> Component.literal(L.tr(c.getSource(), "[Null] охота началась (волны идут ночью, днём — закончится)",
                            "[Null] the hunt has begun (waves come at night, it ends at daybreak)")), true);
                    return 1;
                })).then(Commands.literal("end").executes(c -> {
                    hunt.end(server);
                    return 1;
                })))
                .then(Commands.literal("mimic").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                    boolean ok = mimic(p, 0);
                    c.getSource().sendSuccess(() -> Component.literal(ok ? L.tr(c.getSource(), "[Null] они уже заходят…", "[Null] they're already joining…")
                            : L.tr(c.getSource(), "[Null] некого звать: нет офлайн-игроков в usercache.json", "[Null] no one to call: no offline players in usercache.json")), false);
                    return 1;
                }).then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 5)).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                    boolean ok = mimic(p, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "count"));
                    c.getSource().sendSuccess(() -> Component.literal(ok ? L.tr(c.getSource(), "[Null] они уже заходят…", "[Null] they're already joining…")
                            : L.tr(c.getSource(), "[Null] некого звать", "[Null] no one to call")), false);
                    return 1;
                }))))
                .then(Commands.literal("echo").then(Commands.literal("ahead").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                    Echo.Piece piece = Echo.load(libDir, null);
                    net.minecraft.world.phys.Vec3 look = p.getLookAngle();
                    double len = Math.max(0.01, Math.hypot(look.x, look.z));
                    String where = piece == null ? null : Echo.placeAhead(piece, p, look.x / len, look.z / len, worldDir);
                    c.getSource().sendSuccess(() -> Component.literal(where == null
                            ? L.tr(c.getSource(), "[Null] библиотека построек пуста — сначала /null echo <ник> у постройки", "[Null] the build library is empty — run /null echo <name> near a build first")
                            : L.tr(c.getSource(), "[Null] впереди: ", "[Null] ahead: ") + where), false);
                    return 1;
                }))).then(Commands.literal("void").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                    String where = Echo.placeVoid(server, ThreadLocalRandom.current().nextBoolean() ? Echo.load(libDir, null) : null, p.blockPosition(), worldDir);
                    c.getSource().sendSuccess(() -> Component.literal(where == null ? L.tr(c.getSource(), "[Null] clan_void недоступен", "[Null] clan_void is unavailable") : "[Null] clan_void: " + where), false);
                    return 1;
                }))).then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                    boolean ok = echo(p, true);
                    c.getSource().sendSuccess(() -> Component.literal(ok
                            ? L.tr(c.getSource(), "[Null] эхо поставлено, место — в логе и config/tbsdirector/echoes.log", "[Null] echo placed, location is in the log and config/tbsdirector/echoes.log")
                            : L.tr(c.getSource(), "[Null] рядом с игроком нет постройки (нужна настоящая постройка: 60+ рукотворных блоков, 3+ материала)",
                                    "[Null] no build near the player (needs a real build: 60+ placed blocks, 3+ materials)")), false);
                    return 1;
                })))
                .then(Commands.literal("decay").executes(c -> {
                    boolean ru = L.ru(c.getSource());
                    c.getSource().sendSuccess(() -> Component.literal("[Null] " + decay.describe(ru) + L.tr(ru, " Минут жизни: ", " Minutes lived: ") + decay.minutes
                            + L.tr(ru, ", на стадию: ", ", per stage: ") + cfg.decayMinutesPerStage), false);
                    return 1;
                }).then(Commands.literal("set").then(Commands.argument("stage", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, Decay.MAX_STAGE)).executes(c -> {
                    decay.setStage(server, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "stage"));
                    c.getSource().sendSuccess(() -> Component.literal("[Null] " + decay.describe(L.ru(c.getSource()))), true);
                    return 1;
                }))))
                .then(Commands.literal("echoes").executes(c -> {
                    int n = Echo.libraryCount(libDir == null ? dir : libDir);
                    c.getSource().sendSuccess(() -> Component.literal(L.tr(c.getSource(),
                            "[Null] Сохранённых копий построек: " + n + ". Удалить все: /null echoes clear",
                            "[Null] Saved build copies: " + n + ". Delete all: /null echoes clear")), false);
                    return n;
                }).then(Commands.literal("clear").requires(s -> s.hasPermission(4)).executes(c -> {
                    int n = Echo.clearLibrary(libDir == null ? dir : libDir);
                    LOG.info("[understudy] {} cleared {} saved build copies", c.getSource().getTextName(), n);
                    c.getSource().sendSuccess(() -> Component.literal(L.tr(c.getSource(),
                            "[Null] Удалено сохранённых копий: " + n + ". Уже поставленные в мире постройки остаются — их можно снести вручную.",
                            "[Null] Deleted saved copies: " + n + ". Copies already placed in the world stay — remove them by hand if needed.")), true);
                    return n;
                })))
                .then(Commands.literal("why").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                    String text = snapshot(p, watch(p)).describe();
                    c.getSource().sendSuccess(() -> Component.literal(L.tr(c.getSource(), "[Null] вижу:\n", "[Null] I see (AI input, Russian):\n") + text), false);
                    return 1;
                }))));
    }

    // для тестов без сервера
    static List<String> actions() {
        return Brain.ACTIONS;
    }
}
