package tbs.director;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Сюжет режиссёра поверх сюжета TBS. Главы открываются по вехам TBS (приход Null, ачивки мода),
 * в следующих кругах — по стадиям умирания мира. После финальной охоты мир откатывается на стадию 1.
 * Прогресс общий на сервер: world/tbsdirector/story.json.
 */
final class Story {
    static final int FINALE = 6;
    static final String[] ROMAN = {"0", "I", "II", "III", "IV", "V", "ФИНАЛ"};
    static final String[] NAMES = {"Тишина", "Он пришёл", "Here I am.", "Go away", "Воспоминание", "Библиотека", "It was your fault."};
    /** Для игроков с английским языком (заголовки глав). */
    static final String[] ROMAN_EN = {"0", "I", "II", "III", "IV", "V", "FINALE"};
    static final String[] NAMES_EN = {"Silence", "He Came", "Here I am.", "Go away", "Remembrance", "The Library", "It was your fault."};
    /** Ачивка TBS, открывающая главу в первом круге. */
    private static final String[] ADV = {null, null, "can_you_see_me", "can_someone_hear_me", "polaroid_craft", "arches_of_yarn",
            "you_ve_brought_it_upon_yourself"};
    /** Подсказка ИИ: о чём эта глава. */
    private static final String[] MOOD = {
            "",
            "Null только пришёл. Он присматривается, мелкие знаки, шёпот.",
            "Он показал себя. Мир начинает подменять людей (мимики) и уносить постройки.",
            "Он гонит игроков: «это место не для вас». Копии появляются на пути, бывают охоты.",
            "Мир вспоминает. Говори о прошлом игрока, о том, что ты о нём помнишь, о его старых домах.",
            "Библиотека собрана. Мир доживает последние дни перед удалением, всё рушится.",
            "Финал. «Это была твоя вина». Последняя ночь перед удалением мира."};

    static volatile int current;

    int chapter;
    int cycle;
    boolean finaleStarted;
    long since = System.currentTimeMillis();
    /** Ачивки, которые уже «сработали» (или были у игроков, когда админ сам выставил главу). */
    final java.util.Set<String> seen = new java.util.HashSet<>();
    private final Path file;

    Story(Path worldDir) {
        this.file = worldDir.resolve("story.json");
        try {
            if (Files.exists(file)) {
                JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                chapter = o.has("chapter") ? o.get("chapter").getAsInt() : 0;
                cycle = o.has("cycle") ? o.get("cycle").getAsInt() : 0;
                finaleStarted = o.has("finaleStarted") && o.get("finaleStarted").getAsBoolean();
                since = o.has("since") ? o.get("since").getAsLong() : since;
                if (o.has("seen")) {
                    o.getAsJsonArray("seen").forEach(e -> seen.add(e.getAsString()));
                }
            }
        } catch (Exception e) {
            DirectorMod.LOG.warn("[understudy] story.json не прочитался: {}", e.toString());
        }
        current = chapter;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("chapter", chapter);
            o.addProperty("cycle", cycle);
            o.addProperty("finaleStarted", finaleStarted);
            o.addProperty("since", since);
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            seen.forEach(arr::add);
            o.add("seen", arr);
            Files.writeString(file, o.toString());
        } catch (Exception e) {
            DirectorMod.LOG.warn("[understudy] story.json не сохранился: {}", e.toString());
        }
    }

    String describe() {
        return "Глава " + ROMAN[chapter] + " «" + NAMES[chapter] + "»" + (cycle > 0 ? ", круг " + (cycle + 1) : "");
    }

    /** Для ИИ: какая сейчас глава и её тон. */
    String mood() {
        return chapter == 0 ? "" : "Сюжет: " + describe() + ". " + MOOD[chapter];
    }

    private static boolean earned(MinecraftServer server, String adv) {
        AdvancementHolder h = server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath(TbsWorld.NS, adv));
        if (h == null) {
            return false;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getAdvancements().getOrStartProgress(h).isDone()) {
                return true;
            }
        }
        return false;
    }

    /** Каждые 2 секунды (после прихода Null). */
    void tick(MinecraftServer server, Decay decay, Hunt hunt) {
        int target = chapter == 0 && seen.contains("null") ? 0 : Math.max(chapter, 1); // Null уже пришёл — это и есть глава I
        if (cycle == 0) {
            // глава поднимается только от ачивки, которую ещё не учитывали
            boolean changed = false;
            for (int k = 2; k <= FINALE; k++) {
                if (!seen.contains(ADV[k]) && earned(server, ADV[k])) {
                    seen.add(ADV[k]);
                    changed = true;
                    target = Math.max(target, k);
                }
            }
            if (changed && target <= chapter) {
                save();
            }
        } else {
            target = Math.max(target, Math.min(5, decay.stage));
            // финал нового круга: мир на стадии 5 уже 2 часа
            if (chapter == 5 && decay.stage >= 5 && System.currentTimeMillis() - since > 2 * 3_600_000L) {
                target = FINALE;
            }
        }
        if (target > chapter) {
            set(server, target, decay);
        }
        if (chapter == FINALE) {
            long time = server.overworld().getDayTime() % 24000L;
            boolean night = time >= 11800 && time < 22000;
            if (!finaleStarted && night && !hunt.active && !server.getPlayerList().getPlayers().isEmpty()) {
                finaleStarted = true;
                save();
                hunt.start(server, server.overworld().getDayTime() / 24000L);
                AdminApi.feed("финальная охота началась");
            } else if (finaleStarted && !hunt.active) {
                newCycle(server, decay);
            }
        }
    }

    /** Админ выставил главу вручную: уже полученные ачивки её не перебивают. */
    void setManual(MinecraftServer server, int n, Decay decay) {
        seen.add("null");
        for (int k = 2; k <= FINALE; k++) {
            if (earned(server, ADV[k])) {
                seen.add(ADV[k]);
            }
        }
        set(server, n, decay);
    }

    void set(MinecraftServer server, int n, Decay decay) {
        n = Math.max(0, Math.min(FINALE, n));
        boolean up = n > chapter;
        chapter = n;
        current = n;
        since = System.currentTimeMillis();
        if (n < FINALE) {
            finaleStarted = false;
        }
        save();
        if (n >= 1 && decay.stage < 1) {
            decay.setStage(server, 1);
        }
        if (n == 5 && decay.stage < 4) {
            decay.setStage(server, 4); // библиотека собрана — мир быстро доживает
        }
        if (up && n > 0) {
            announce(server, "Глава " + ROMAN[n], NAMES[n], (n == FINALE ? "" : "Chapter ") + ROMAN_EN[n], NAMES_EN[n]);
        }
        AdminApi.feed("сюжет: " + describe());
        DirectorMod.LOG.info("[understudy] сюжет: {}", describe());
    }

    private void newCycle(MinecraftServer server, Decay decay) {
        cycle++;
        chapter = 1;
        current = 1;
        finaleStarted = false;
        since = System.currentTimeMillis();
        save();
        decay.setStage(server, 1);
        announce(server, "Круг " + (cycle + 1), "Мир удалён. Мир создан. Что-то осталось.",
                "Cycle " + (cycle + 1), "World deleted. World created. Something remained.");
        AdminApi.feed("новый круг: " + describe());
        DirectorMod.LOG.info("[understudy] новый круг: {}", describe());
    }

    /** Заголовок главы — каждому игроку на его языке. */
    private static void announce(MinecraftServer server, String bigRu, String smallRu, String bigEn, String smallEn) {
        Hooks.SELF.set(true);
        try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                boolean ru = L.ru(p);
                String big = ru ? bigRu : bigEn;
                String small = ru ? smallRu : smallEn;
                p.connection.send(new ClientboundSetTitlesAnimationPacket(20, 80, 30));
                p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(small).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
                p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(big).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
                p.playNotifySound(SoundEvents.BEACON_DEACTIVATE, SoundSource.MASTER, 1f, 0.5f);
            }
        } finally {
            Hooks.SELF.set(false);
        }
    }
}