package tbs.director;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Ночь охоты: раз в несколько игровых дней с наступлением ночи идут волны мимиков и охотников TBS.
 * Кто ни разу не умер до рассвета — получает награду. День последней охоты хранится в папке мира.
 */
public final class Hunt {
    static final int EVERY_DAYS = 3;
    private final Path file;
    long lastHuntDay = -1;
    boolean active;
    /** Охота, начатая днём вручную, длится хотя бы до этого тика. */
    private int forcedUntilTick;
    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> died = new HashSet<>();
    private long nextWaveTick;

    Hunt(Path worldDir) {
        this.file = worldDir.resolve("hunt.txt");
        try {
            if (Files.exists(file)) {
                lastHuntDay = Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim());
            }
        } catch (Exception ignored) {
            lastHuntDay = -1;
        }
    }

    private void save() {
        try {
            Files.writeString(file, String.valueOf(lastHuntDay), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // не критично
        }
    }

    void onDeath(UUID id) {
        if (active) {
            died.add(id);
        }
    }

    /** Заголовок и строка в чат — каждому игроку на его языке. */
    private static void title(MinecraftServer server, String bigRu, String smallRu, String bigEn, String smallEn) {
        Hooks.SELF.set(true);
        try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                boolean ru = L.ru(p);
                String big = ru ? bigRu : bigEn;
                String small = ru ? smallRu : smallEn;
                p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
                p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(big).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
                p.sendSystemMessage(Component.literal(small).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            }
        } finally {
            Hooks.SELF.set(false);
        }
    }

    void start(MinecraftServer server, long day) {
        active = true;
        lastHuntDay = day;
        save();
        participants.clear();
        died.clear();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            participants.add(p.getUUID());
        }
        nextWaveTick = server.getTickCount() + 200;
        long t = server.overworld().getDayTime() % 24000L;
        forcedUntilTick = t >= 11800 && t < 23000 ? 0 : server.getTickCount() + 20 * 60 * 5; // днём — 5 минут
        title(server, "ОХОТА", "Этой ночью они придут за всеми. Доживи до рассвета.",
                "THE HUNT", "Tonight they come for everyone. Survive until dawn.");
        DirectorMod.LOG.info("[understudy] ночь охоты началась (день {})", day);
    }

    /** Каждые 2 секунды. stageOk — мир уже начал умирать. */
    void tick(MinecraftServer server, boolean stageOk, java.util.function.BiConsumer<ServerPlayer, Integer> mimics) {
        ServerLevel ow = server.overworld();
        long day = ow.getDayTime() / 24000L;
        long time = ow.getDayTime() % 24000L;
        // охота — с заката (11800), раньше, чем можно лечь спать (12542), и до утра
        boolean night = time >= 11800 && time < 23000;
        if (!active) {
            if (stageOk && night && time < 12500 && day - lastHuntDay >= EVERY_DAYS && !server.getPlayerList().getPlayers().isEmpty()) {
                start(server, day);
            }
            return;
        }
        if (!night && server.getTickCount() >= forcedUntilTick) {
            end(server);
            return;
        }
        if (server.getTickCount() < nextWaveTick) {
            return;
        }
        nextWaveTick = server.getTickCount() + 20 * (80 + ThreadLocalRandom.current().nextInt(40));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            participants.add(p.getUUID());
            if (p.isCreative() || p.isSpectator() || p.serverLevel() != ow) {
                continue;
            }
            if (ThreadLocalRandom.current().nextBoolean()) {
                mimics.accept(p, 1 + ThreadLocalRandom.current().nextInt(2));
            } else {
                WorldActs.summon(p, true);
            }
        }
    }

    void end(MinecraftServer server) {
        if (!active) {
            return;
        }
        active = false;
        title(server, "РАССВЕТ", "Охота закончилась. Пока.", "DAWN", "The hunt is over. For now.");
        for (UUID id : participants) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || died.contains(id)) {
                continue;
            }
            p.giveExperienceLevels(10);
            ItemStack apple = new ItemStack(Items.GOLDEN_APPLE);
            if (!p.getInventory().add(apple)) {
                p.drop(apple, false);
            }
            TbsBridge.changeRep(p, true);
            Hooks.SELF.set(true);
            try {
                p.sendSystemMessage(Component.literal(L.tr(p, "Ты пережил охоту. Null это запомнит.", "You survived the hunt. Null will remember this.")).withStyle(ChatFormatting.GOLD));
            } finally {
                Hooks.SELF.set(false);
            }
        }
        DirectorMod.LOG.info("[understudy] охота закончилась, выжили: {}", participants.size() - died.size());
    }
}
