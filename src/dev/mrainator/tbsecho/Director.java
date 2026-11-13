package dev.mrainator.tbsecho;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.mrainator.tbsecho.EchoData.State;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Режиссёр: акты сюжета, шифры, испытания и фоновые события. */
public final class Director {
    private Director() {}

    private static final String TBS = "thebrokenscript:";
    /** Сколько тиков без движения считается «очень долго стоит» (2 минуты). */
    private static final int STILL_TICKS = 2400;

    private static long gate(double days) {
        return (long) (days * 24000 * TbsEcho.PACE.get());
    }

    private static boolean night(ServerLevel l) {
        long t = l.getDayTime() % 24000;
        return t >= 13000 && t < 23000;
    }

    private static State state(ServerPlayer p) {
        return EchoData.of(p.server).get(p.getUUID());
    }

    private static boolean beatsDone(State s) {
        return s.beat >= Story.beats(s.act).length;
    }

    // ================= события =================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        Fx.runTasks(e.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent e) {
        Fx.clearTasks();
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.isSpectator() || !p.isAlive()) return;
        if (!TbsEcho.ENABLED.get() || tbs.director.Api.exempt(p)) return;
        EchoData data = EchoData.of(p.server);
        State s = data.get(p.getUUID());
        // сюжетные часы: если игрок очень долго стоит на месте, они идут вдвое медленнее;
        // стоит ему сдвинуться — скорость сразу возвращается к обычной
        if (Math.abs(p.getX() - s.lastX) > 0.05 || Math.abs(p.getZ() - s.lastZ) > 0.05) {
            s.stillTicks = 0;
            s.lastX = p.getX();
            s.lastZ = p.getZ();
        } else {
            s.stillTicks++;
        }
        if (s.stillTicks > STILL_TICKS && (p.tickCount & 1) == 0) return;
        s.played++;
        if (s.act == 5 && s.trialState == 1 && s.trial == 0
            && Math.abs(Mth.wrapDegrees(p.getYRot() - s.trialYaw)) > 115f) {
            failTrial(p, s);
        }
        if (s.played % 20 == 0) {
            second(p, s);
            data.setDirty();
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        State s = state(p);
        s.sessions++;
        s.name = p.getGameProfile().getName();
        if (s.token.isEmpty()) s.token = Archive.newToken();
        long gapMs = s.lastSeenMs > 0 ? System.currentTimeMillis() - s.lastSeenMs : -1;
        if (s.act >= 1) {
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            Fx.file(p, "сессии.log", "sessions.log",
                String.format("[%s] сессия %04d | оператор: %s | акт: %d | статус: наблюдается%n", now, s.sessions, p.getGameProfile().getName(), s.act),
                String.format("[%s] session %04d | operator: %s | act: %d | status: observed%n", now, s.sessions, p.getGameProfile().getName(), s.act), true);
        }
        if (s.sessions > 1 && s.act >= 2) {
            Fx.later(p, 240, pl -> {
                int hour = LocalTime.now().getHour();
                if (s.act == 2) {
                    Fx.vesna(pl, gapMs > 86_400_000L ? Lang.tr(pl, "тебя долго не было. он спрашивал", "you were gone a long time. it asked about you")
                        : Lang.tr(pl, "ты вернулся. тише", "you came back. quiet"));
                } else if (gapMs > 2 * 86_400_000L) {
                    Fx.rewriter(pl, Lang.tr(pl, "тебя не было " + (gapMs / 86_400_000L) + " дн. я считал.",
                        "you were gone " + (gapMs / 86_400_000L) + " days. i counted."));
                } else if (gapMs >= 0 && gapMs < 600_000L) {
                    Fx.rewriter(pl, Lang.tr(pl, "быстро вернулся.", "back so soon."));
                } else if (hour < 5) {
                    Fx.rewriter(pl, String.format(Lang.tr(pl, "у тебя %02d:%02d. ночь. у меня тоже.", "it's %02d:%02d where you are. night. here too."),
                        hour, LocalTime.now().getMinute()));
                } else {
                    Fx.rewriter(pl, Lang.tr(pl, "сессия " + s.sessions + ". я всё записал.", "session " + s.sessions + ". i wrote it all down."));
                }
            });
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            state(p).lastSeenMs = System.currentTimeMillis();
            EchoData.of(p.server).setDirty();
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        State s = state(p);
        s.deaths++;
        s.hasDeath = true;
        s.deathSigned = false;
        s.dx = p.getBlockX();
        s.dy = p.getBlockY();
        s.dz = p.getBlockZ();
        s.deathDim = p.level().dimension().location().toString();
        if (s.act == 5 && s.trialState == 1) s.trialState = 0;
    }

    @SubscribeEvent
    public static void onWake(PlayerWakeUpEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        State s = state(p);
        if (s.act == 2 && p.getRandom().nextFloat() < 0.4f) {
            Fx.later(p, 50, pl -> Fx.vesna(pl, Lang.tr(pl, "ты спал. он стоял рядом", "you were asleep. it stood next to you")));
        } else if (s.act >= 3 && p.getRandom().nextFloat() < 0.45f) {
            Fx.later(p, 30, pl -> {
                Fx.sign(pl, Fx.findSpot(pl, 2, 4), Lang.ru(pl) ? new String[] {"ты спал.", "я — нет.", "", ""}
                    : new String[] {"you slept.", "i didn't.", "", ""});
            });
        }
    }

    @SubscribeEvent
    public static void onToss(ItemTossEvent e) {
        if (!(e.getPlayer() instanceof ServerPlayer p)) return;
        State s = state(p);
        if (s.act != 5 || s.trialState != 1 || s.trial != 2) return;
        ItemStack st = e.getEntity().getItem();
        boolean valuable = st.isEnchanted() || st.is(Items.DIAMOND) || st.is(Items.EMERALD) || st.is(Items.GOLD_INGOT)
            || st.is(Items.NETHERITE_INGOT) || st.is(Items.DIAMOND_BLOCK) || st.is(Items.GOLDEN_APPLE)
            || st.is(Items.TOTEM_OF_UNDYING) || st.is(Items.ENDER_PEARL) || st.is(Items.NETHERITE_SCRAP);
        if (!valuable) {
            Fx.rewriter(p, Lang.tr(p, "это мусор. ценное.", "that's junk. something precious."));
            return;
        }
        e.getEntity().discard();
        e.setCanceled(true);
        passTrial(p, s);
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void onChat(ServerChatEvent e) {
        ServerPlayer p = e.getPlayer();
        State s = state(p);
        String raw = e.getRawText().trim();
        String m = raw.toLowerCase(Locale.ROOT).replace("ё", "е");
        // ответы принимаются на обоих языках: русский игрок может написать английское слово и наоборот
        boolean code = s.act >= 1 && s.act <= 3 && Story.isCode(s.act, m) && s.journals >= Story.journalsNeeded(s.act);
        boolean graveCode = s.act == 4 && s.graveVisited && (m.equals("1704") || m.equals("17.04")
            || m.equals("0417") || m.equals("04.17") || m.equals("4/17") || m.equals("04/17") || m.equals("17/04")
            || m.equals("april 17") || m.equals("april 17th") || m.equals("17 april") || m.equals("17 апреля"));
        int pick = m.equals("стереть") || m.equals("erase") ? 1 : m.equals("остаться") || m.equals("stay") ? 2
            : m.equals("помни") || m.equals("remember") ? 3 : 0;
        boolean choice = s.act == 6 && beatsDone(s) && pick != 0;
        if (code || graveCode || choice) {
            e.setCanceled(true);
            p.server.execute(() -> {
                if (choice) {
                    ending(p, s, pick);
                } else {
                    Fx.actionbar(p, Lang.tr(p, "…он услышал.", "…it heard you."));
                    Fx.sound(p, TBS + "tock", 1f, 0.5f);
                    setAct(p, s, s.act + 1);
                }
            });
            return;
        }
        p.server.execute(() -> {
            s.lastChat = raw.length() > 80 ? raw.substring(0, 80) : raw;
            s.lastChatTick = s.played;
            int delay = 40 + p.getRandom().nextInt(40);
            if (s.act == 2 && (m.contains("весна") || m.contains("vesna"))) {
                Fx.later(p, delay, pl -> Fx.vesna(pl, Lang.tr(pl, "не называй меня. он запоминает имена", "don't say my name. it remembers names")));
            } else if (s.act == 2 && m.matches(".*20\\d\\d.*")) {
                Fx.later(p, delay, pl -> Fx.vesna(pl, Lang.tr(pl, "…столько прошло?", "…it's been that long?")));
            } else if (s.act == 2 && (m.contains("кто ты") || m.contains("ты кто") || m.contains("who are you") || m.contains("who r u"))) {
                Fx.later(p, delay, pl -> Fx.vesna(pl, Lang.tr(pl, "я была четвёртой", "i was the fourth")));
            } else if (s.act >= 3 && s.act <= 6 && (m.contains("весна") || m.contains("vesna"))) {
                Fx.later(p, delay, pl -> Fx.rewriter(pl, Lang.tr(pl, "этой строки больше нет.", "that line no longer exists.")));
            }
        });
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("tbsecho").requires(src -> src.hasPermission(2))
            .then(Commands.literal("status").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                State s = state(p);
                c.getSource().sendSuccess(() -> Component.literal(String.format(Lang.tr(p,
                    "акт %d, сцена %d, записей %d, присутствие %.0f, дней %.1f, испытание %d/%d, концовка %d, могила %s",
                    "act %d, beat %d, entries %d, presence %.0f, days %.1f, trial %d/%d, ending %d, grave %s"),
                    s.act, s.beat, s.journals, s.presence, s.played / 24000.0, s.trial, s.trialState, s.ending,
                    s.hasGrave ? s.gx + " " + s.gz : "-")), false);
                return 1;
            }))
            .then(Commands.literal("act").then(Commands.argument("n", IntegerArgumentType.integer(0, 7)).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                State s = state(p);
                int n = IntegerArgumentType.getInteger(c, "n");
                s.journals = Math.max(s.journals, Story.journalsAvailable(Math.max(0, n - 1)));
                setAct(p, s, n);
                s.nextBeat = s.played + 40;
                return 1;
            })))
            .then(Commands.literal("event").then(Commands.argument("id", IntegerArgumentType.integer(0, 12)).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                event(p, p.serverLevel(), state(p), IntegerArgumentType.getInteger(c, "id"));
                return 1;
            })))
            .then(Commands.literal("cache").executes(c -> {
                state(c.getSource().getPlayerOrException()).nextCache = 0;
                return 1;
            }))
            .then(Commands.literal("reset").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                EchoData.of(p.server).reset(p.getUUID());
                return 1;
            })));
    }

    // ================= ход сюжета =================

    private static void second(ServerPlayer p, State s) {
        ServerLevel l = p.serverLevel();
        s.lang = Lang.ru(p) ? 1 : 2;
        presence(p, l, s);
        story(p, l, s);
        if (s.act >= 1) caches(p, l, s);
        if (s.act == 2) morse(p, l, s);
        if (s.act >= 4) grave(p, l, s);
        if (s.act == 5) trials(p, l, s);
        deathSign(p, l, s);
        ambient(p, l, s);
        web(p, s);
        if (s.act >= 2 && TbsEcho.TAB.get() && s.played % 1200 == 0) tab(p, s);
    }

    /** Кто-то открыл личную страницу игрока в веб-архиве — мир это замечает. */
    private static void web(ServerPlayer p, State s) {
        if (s.webSeen == s.webAck) return;
        s.webAck = s.webSeen;
        if (s.act >= 3) Fx.rewriter(p, Lang.tr(p, "ты открыл архив. я вижу, с какого окна.", "you opened the archive. i can see which window."));
        else if (s.act == 2) Fx.vesna(p, Lang.tr(p, "ты читаешь архив? он тоже там", "are you reading the archive? it's in there too"));
        else Fx.actionbar(p, Lang.tr(p, "…кто-то только что открыл твою запись.", "…someone just opened your record."));
    }

    static String status(State s) {
        return status(s, true);
    }

    static String status(State s, boolean ru) {
        if (ru) {
            return s.act >= 7 ? (s.ending == 2 ? "в записи" : s.ending == 1 ? "играет" : "помнит")
                : s.act >= 5 ? "дописывается" : s.act >= 3 ? "в работе" : "наблюдается";
        }
        return s.act >= 7 ? (s.ending == 2 ? "recorded" : s.ending == 1 ? "playing" : "remembers")
            : s.act >= 5 ? "being written" : s.act >= 3 ? "in progress" : "observed";
    }

    private static void tab(ServerPlayer p, State s) {
        boolean ru = Lang.ru(p);
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundTabListPacket(Component.empty(),
            Component.literal((ru ? "ЭХО · оператор 5 · " : "ECHO · operator 5 · ") + status(s, ru)).withStyle(ChatFormatting.DARK_GRAY)));
    }

    /** Строка для ИИ-режиссёра: на каком этапе сюжета игрок, чтобы Null говорил в тему. */
    public static String mood(ServerPlayer p) {
        if (!TbsEcho.ENABLED.get()) return "";
        State s = state(p);
        if (s.act <= 0) return "";
        String stage = switch (s.act) {
            case 1 -> "находит записи прошлого игрока Vesna_04 («оператор 4») в тайниках под красным факелом";
            case 2 -> "с ним через чат говорит Vesna_04 — эхо прошлого игрока, которого этот мир «дописал»";
            case 3 -> "Vesna_04 оборвалась на полуслове; игрок знает, что тебя она звала «Переписчик» — ты дописываешь сценарий мира";
            case 4 -> "идёт по координатам к камню Vesna_04";
            case 5 -> "должен тебе три «строки» (испытания): не оборачиваться, минута в темноте, отдать ценное";
            case 6 -> "стоит перед выбором: «стереть» Vesna_04 или «остаться» вместо неё";
            default -> s.ending == 1 ? "стёр Vesna_04 и записан как «оператор 5»" : s.ending == 2 ? "остался вместо Vesna_04 и сам стал записью" : "отказался от твоего выбора и сохранил память о Vesna_04";
        };
        return "Сюжет «ЭХО»: для тебя этот игрок — «оператор 5»; сейчас он " + stage
            + ". Можно намекать на это, но не называй кодовые слова и не пересказывай записи.";
    }

    private static void setAct(ServerPlayer p, State s, int act) {
        s.act = act;
        s.actStart = s.played;
        s.beat = 0;
        s.nextBeat = s.played + gate(Story.startDelayDays(act));
        s.nextCache = s.played + 20 * 90;
        s.trial = 0;
        s.trialState = 0;
        s.nextTrial = 0;
        if (act == 4 && !s.hasGrave) {
            double a = p.getRandom().nextDouble() * Math.PI * 2;
            int d = 350 + p.getRandom().nextInt(250);
            s.gx = p.getBlockX() + (int) (Math.cos(a) * d);
            s.gz = p.getBlockZ() + (int) (Math.sin(a) * d);
            s.hasGrave = true;
        }
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        Fx.file(p, "сессии.log", "sessions.log", String.format("[%s] правка сценария: акт %d%n", now, act),
            String.format("[%s] script edit: act %d%n", now, act), true);
    }

    private static void story(ServerPlayer p, ServerLevel l, State s) {
        if (s.act == 0) {
            if (s.played - s.actStart >= gate(1.0) && night(l) && l.dimension() == Level.OVERWORLD) {
                BlockPos first = Fx.findSpot(p, 3, 6);
                if (Fx.sign(p, first, Lang.ru(p) ? new String[] {"ты не", "первый.", "ищи красный", "свет."}
                    : new String[] {"you are not", "the first.", "look for", "red light."})) {
                    Fx.sound(p, "minecraft:block.wood.place", Vec3.atCenterOf(first), 1f, 0.7f);
                    setAct(p, s, 1);
                }
            }
            return;
        }
        Story.Beat[] beats = Story.beats(s.act, Lang.ru(p));
        if (s.beat < beats.length) {
            if (s.played < s.nextBeat) return;
            Story.Beat b = beats[s.beat];
            switch (b.kind()) {
                case "join" -> Fx.joined(p, true);
                case "left" -> Fx.joined(p, false);
                case "v" -> Fx.vesna(p, b.text());
                case "r" -> Fx.rewriter(p, b.text());
                case "dark" -> Fx.darkness(p, 160);
                case "file3" -> Fx.file(p, "НЕ_ЧИТАЙ.txt", "DO_NOT_READ.txt", Fx.fmt(
                    "ты всё-таки открыл.\r\n\r\nоператор 1 — стёрт.\r\nоператор 2 — стёрта.\r\nоператор 3 — стёрт.\r\nоператор 4 — дописана.\r\nоператор 5 — %s. в работе.\r\n\r\n"
                    + "не ищи её записи. я их уже читал.\r\nне закрывай игру надолго. я считаю.\r\n", p), Fx.fmt(
                    "you opened it anyway.\r\n\r\noperator 1 — erased.\r\noperator 2 — erased.\r\noperator 3 — erased.\r\noperator 4 — written in.\r\noperator 5 — %s. in progress.\r\n\r\n"
                    + "don't look for her entries. i've already read them.\r\ndon't close the game for long. i'm counting.\r\n", p), false);
                case "printout" -> printout(p, s);
                default -> { }
            }
            s.beat++;
            if (s.beat < beats.length) s.nextBeat = s.played + beats[s.beat].delaySec() * 20L;
            else s.nextBeat = s.played;
            return;
        }
        if (s.act == 6 && s.played >= s.nextBeat + gate(0.5)) {
            Fx.rewriter(p, Lang.tr(p, "я жду. §fстереть§4 или §fостаться§4.", "i'm waiting. §ferase§4 or §fstay§4."));
            s.nextBeat = s.played;
        }
    }

    private static void printout(ServerPlayer p, State s) {
        String body = "оператор 4:\nпоследнее известное положение\n\nX: " + Story.reverseNumber(s.gx) + "\nZ: " + Story.reverseNumber(s.gz)
            + "\n\nОн читает слева направо.\n\nТы — читай наоборот.";
        String bodyEn = "operator 4:\nlast known position\n\nX: " + Story.reverseNumber(s.gx) + "\nZ: " + Story.reverseNumber(s.gz)
            + "\n\nIt reads left to right.\n\nYou — read it backwards.";
        boolean ru = Lang.ru(p);
        Fx.give(p, Fx.book(ru ? "Распечатка" : "Printout", ru ? "ЭХО" : "ECHO", new String[] {ru ? body : bodyEn}));
        Fx.file(p, "координаты.txt", "coordinates.txt", body.replace("\n", "\r\n"), bodyEn.replace("\n", "\r\n"), false);
    }

    private static void caches(ServerPlayer p, ServerLevel l, State s) {
        if (s.played < s.nextCache) return;
        if (l.dimension() != Level.OVERWORLD || p.isInWater() || !beatsDone(s)) {
            s.nextCache = s.played + 200;
            return;
        }
        String name = p.getGameProfile().getName();
        boolean ru = Lang.ru(p);
        ItemStack content = null;
        boolean journal = false;
        if (s.act <= 6 && s.journals < Story.journalsAvailable(s.act)) {
            content = Fx.book(Story.journalTitle(s.journals, ru), Story.VESNA, Story.journal(s.journals, ru));
            journal = true;
        } else if (s.act == 7 && s.ending == 2) {
            content = Fx.book((ru ? "Запись " : "Entry ") + (8 + s.sessions), name, Story.ownJournal(name, 8 + s.sessions, s.deaths, s.played / 24000, ru));
        } else if (s.act == 7 && s.ending == 3) {
            content = new ItemStack(p.getRandom().nextBoolean() ? Items.BREAD : Items.TORCH, 8);
        }
        if (content == null) {
            s.nextCache = s.played + gate(0.5);
            return;
        }
        BlockPos spot = Fx.findSpot(p, 10, 18);
        if (!Fx.cache(p, spot, content)) {
            s.nextCache = s.played + 200;
            return;
        }
        if (journal) {
            Fx.file(p, "запись_" + (s.journals + 1) + ".txt", "entry_" + (s.journals + 1) + ".txt",
                String.join("\r\n\r\n", Story.journal(s.journals, true)).replace("\n", "\r\n"),
                String.join("\r\n\r\n", Story.journal(s.journals, false)).replace("\n", "\r\n"), false);
            s.journals++;
        }
        if (journal && s.journals == 1 && !TbsEcho.PUBLIC_URL.get().isEmpty()
            && l.getBlockEntity(spot) instanceof ChestBlockEntity chest) {
            // язык страницы задаётся суффиксом пути (/ru или /en): так архив откроется на языке игрока и без него в игре
            String url = TbsEcho.PUBLIC_URL.get() + "/echo/" + s.token + (ru ? "/ru" : "/en");
            chest.setItem(14, ru ? Fx.book("Пропуск в архив", "ЭХО", new String[] {
                "ЭХО-билд, 2012.\nАрхив сервера не выключили.\n\nОн открывается не в игре.\nВ браузере:\n\n" + url,
                "Пропуск личный.\n\nКаждая найденная запись появляется там.\n\nОн видит, когда ты входишь."})
                : Fx.book("Archive pass", "ECHO", new String[] {
                "ECHO build, 2012.\nNobody shut down the server archive.\n\nIt doesn't open in the game.\nIn a browser:\n\n" + url,
                "This pass is personal.\n\nEvery entry you find shows up there.\n\nIt sees when you come in."}));
        }
        Fx.sound(p, "minecraft:block.chest.close", Vec3.atCenterOf(spot), 1f, 0.7f);
        Fx.actionbar(p, Lang.tr(p, "…рядом что-то положили.", "…something was left nearby."));
        s.nextCache = s.played + gate(s.act == 7 ? 3.0 : 1.0);
    }

    private static void morse(ServerPlayer p, ServerLevel l, State s) {
        if (s.journals < 4 || !night(l) || s.played < s.nextMorse) return;
        s.nextMorse = s.played + 20 * 240;
        // писк идёт от блока нот, который он ставит рядом, — источник видно и можно подойти
        if (!s.hasBeacon || p.distanceToSqr(s.bx + 0.5, s.by, s.bz + 0.5) > 28 * 28) {
            BlockPos spot = Fx.findSpot(p, 5, 9);
            if (spot == null) return;
            l.setBlock(spot, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
            s.hasBeacon = true;
            s.bx = spot.getX();
            s.by = spot.getY();
            s.bz = spot.getZ();
        }
        final Vec3 src = new Vec3(s.bx + 0.5, s.by + 0.5, s.bz + 0.5);
        int t = 20;
        for (String letter : Story.morse(Lang.ru(p))) {
            for (char c : letter.toCharArray()) {
                if (c == '.') {
                    Fx.later(p, t, pl -> beep(pl, src, 1.6f));
                    t += 7;
                } else {
                    for (int k = 0; k < 3; k++) {
                        Fx.later(p, t + k * 2, pl -> beep(pl, src, 0.8f));
                    }
                    t += 13;
                }
            }
            t += 18;
        }
    }

    private static void beep(ServerPlayer p, Vec3 src, float pitch) {
        Fx.sound(p, "minecraft:block.note_block.bit", src, 2f, pitch);
        p.serverLevel().sendParticles(p, net.minecraft.core.particles.ParticleTypes.NOTE, false, src.x, src.y + 0.7, src.z, 1, 0, 0, 0, 0);
    }

    private static void grave(ServerPlayer p, ServerLevel l, State s) {
        if (!s.hasGrave || l.dimension() != Level.OVERWORLD) return;
        double dist = Math.sqrt(Mth.square(p.getX() - s.gx) + Mth.square(p.getZ() - s.gz));
        if (!s.graveBuilt) {
            BlockPos column = new BlockPos(s.gx, p.getBlockY(), s.gz);
            if (dist > 40 || !l.hasChunkAt(column)) {
                if (s.act == 4 && dist < 160 && s.played % 600 == 0) Fx.actionbar(p, Lang.tr(p, "…ближе.", "…closer."));
                return;
            }
            int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, s.gx, s.gz);
            s.gy = y;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    l.setBlock(new BlockPos(s.gx + dx, y - 1, s.gz + dz), Blocks.COARSE_DIRT.defaultBlockState(), 3);
                    for (int dy = 0; dy <= 2; dy++) l.setBlock(new BlockPos(s.gx + dx, y + dy, s.gz + dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
            l.setBlock(new BlockPos(s.gx, y, s.gz), Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), 3);
            l.setBlock(new BlockPos(s.gx, y + 1, s.gz), Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), 3);
            l.setBlock(new BlockPos(s.gx, y + 2, s.gz), Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
            BlockPos chestPos = new BlockPos(s.gx + 1, y, s.gz);
            l.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
            if (l.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
                boolean ru = Lang.ru(p);
                chest.setItem(13, Fx.book(Story.journalTitle(6, ru), Story.VESNA, Story.journal(6, ru)));
            }
            Fx.sign(p, new BlockPos(s.gx, y, s.gz + 1), Lang.ru(p) ? new String[] {Story.VESNA, "оператор 4", "17.04", "вышла из игры"}
                : new String[] {Story.VESNA, "operator 4", "17.04", "left the game"});
            s.graveBuilt = true;
            return;
        }
        if (!s.graveVisited && dist < 6 && Math.abs(p.getY() - s.gy) < 8) {
            s.graveVisited = true;
            net.minecraft.world.entity.LightningBolt bolt = net.minecraft.world.entity.EntityType.LIGHTNING_BOLT.create(l);
            if (bolt != null) {
                bolt.moveTo(s.gx + 0.5, s.gy + 2, s.gz + 0.5);
                bolt.setVisualOnly(true);
                l.addFreshEntity(bolt); // гром идёт от настоящей молнии в камень
            }
            Fx.darkness(p, 120);
            if (Lang.ru(p)) Fx.title(p, "§4ТЫ ДОШЁЛ", "§7прочти камень");
            else Fx.title(p, "§4YOU MADE IT", "§7read the stone");
            Fx.file(p, "запись_7.txt", "entry_7.txt", String.join("\r\n\r\n", Story.journal(6, true)).replace("\n", "\r\n"),
                String.join("\r\n\r\n", Story.journal(6, false)).replace("\n", "\r\n"), false);
        }
    }

    // ================= испытания (акт 5) =================

    private static void trials(ServerPlayer p, ServerLevel l, State s) {
        if (!beatsDone(s)) return;
        if (s.trialState == 0) {
            if (s.nextTrial == 0) s.nextTrial = s.played + gate(0.5);
            if (s.played < s.nextTrial) return;
            Fx.rewriter(p, Story.trialStart(Lang.ru(p))[s.trial]);
            s.trialState = 1;
            s.trialStart = s.played;
            s.trialYaw = p.getYRot();
            s.trialDark = 0;
            if (s.trial == 0) {
                for (int k = 0; k < 3; k++) Fx.later(p, 60 + k * 240, pl -> tbs.director.Causal.footsteps(pl.server, pl));
            }
            return;
        }
        long el = s.played - s.trialStart;
        switch (s.trial) {
            case 0 -> {
                if (el >= 800) passTrial(p, s);
            }
            case 1 -> {
                if (l.getMaxLocalRawBrightness(p.blockPosition()) <= 4) {
                    s.trialDark++;
                    if (s.trialDark % 10 == 0) Fx.actionbar(p, "…" + (60 - s.trialDark));
                    if (s.trialDark == 30) tbs.director.Api.watch(p);
                } else {
                    s.trialDark = 0;
                }
                if (s.trialDark >= 60) passTrial(p, s);
                else if (el > 4800) failTrial(p, s);
            }
            default -> {
                if (el > 3600) failTrial(p, s);
            }
        }
    }

    private static void passTrial(ServerPlayer p, State s) {
        Fx.rewriter(p, Story.trialPass(Lang.ru(p))[Math.min(s.trial, 2)]);
        Fx.sound(p, TBS + "tock", 1f, 0.6f);
        s.trial++;
        s.trialState = 0;
        s.nextTrial = s.played + gate(0.8);
        if (s.trial >= 3) setAct(p, s, 6);
    }

    private static void failTrial(ServerPlayer p, State s) {
        s.trialState = 0;
        s.nextTrial = s.played + gate(0.4);
        s.presence = 100;
        Fx.rewriter(p, Lang.tr(p, "строка не принята.", "line rejected."));
        Fx.darkness(p, 200);
        tbs.director.Api.flash(p);
        if (TbsEcho.INTENSITY.get() >= 2) {
            Fx.later(p, 60, pl -> {
                Fx.spawn(pl, Fx.findSpot(pl, 12, 20), "siluet_chase", "he_chase", "circuit_stalk");
                Fx.rewriter(pl, Lang.tr(pl, "беги.", "run."));
            });
        }
    }

    // ================= концовки =================

    private static void ending(ServerPlayer p, State s, int which) {
        s.ending = which;
        setAct(p, s, 7);
        String name = p.getGameProfile().getName();
        switch (which) {
            case 1 -> {
                Fx.later(p, 20, pl -> Fx.joined(pl, true));
                Fx.later(p, 70, pl -> Fx.vesna(pl, Lang.tr(pl, "ты выбрал", "you chose")));
                Fx.later(p, 140, pl -> Fx.vesna(pl, Lang.tr(pl, "ладно. я устала быть эхом", "it's okay. i'm tired of being an echo")));
                Fx.later(p, 210, pl -> Fx.vesna(pl, Lang.tr(pl, "спасибо", "thank you")));
                Fx.later(p, 250, pl -> Fx.joined(pl, false));
                Fx.later(p, 340, pl -> Fx.rewriter(pl, Lang.tr(pl, "оператор 4 удалён.", "operator 4 deleted.")));
                Fx.later(p, 420, pl -> Fx.rewriter(pl, Lang.tr(pl, "оператор 5: %s. статус: играет.", "operator 5: %s. status: playing.")));
                Fx.later(p, 500, pl -> Fx.rewriter(pl, Lang.tr(pl, "играй.", "play.")));
                Fx.file(p, "оператор_5.txt", "operator_5.txt", "оператор 5: " + name + "\r\nстатус: играет\r\nдом: не стёрт\r\n\r\nпока.\r\n",
                    "operator 5: " + name + "\r\nstatus: playing\r\nhouse: not erased\r\n\r\nfor now.\r\n", false);
            }
            case 2 -> {
                Fx.later(p, 20, pl -> Fx.rewriter(pl, Lang.tr(pl, "принято.", "accepted.")));
                Fx.later(p, 50, pl -> {
                    Fx.darkness(pl, 400);
                    Fx.title(pl, Lang.tr(pl, "§4ТЫ — ЗАПИСЬ", "§4YOU ARE THE RECORD"), "");
                });
                Fx.later(p, 120, pl -> pl.sendSystemMessage(Component.translatable("multiplayer.player.left", name).withStyle(ChatFormatting.YELLOW)));
                Fx.later(p, 190, pl -> Fx.joined(pl, true));
                Fx.later(p, 230, pl -> Fx.vesna(pl, Lang.tr(pl, "прости", "i'm sorry")));
                Fx.later(p, 300, pl -> Fx.vesna(pl, Lang.tr(pl, "я не думала, что он правда отпустит", "i didn't think it would really let me go")));
                Fx.later(p, 340, pl -> Fx.joined(pl, false));
                Fx.later(p, 440, pl -> Fx.rewriter(pl, Lang.tr(pl, "пиши, %s. я буду править.", "write, %s. i will edit.")));
                Fx.file(p, "оператор_5.txt", "operator_5.txt",
                    "оператор 5: " + name + "\r\nстатус: дописывается\r\n\r\nищи красный свет. теперь под ним — твой почерк.\r\n",
                    "operator 5: " + name + "\r\nstatus: being written\r\n\r\nlook for red light. your handwriting is under it now.\r\n", false);
            }
            default -> {
                Fx.later(p, 20, pl -> Fx.rewriter(pl, Lang.tr(pl, "этого слова нет в сценарии.", "that word is not in the script.")));
                Fx.later(p, 90, pl -> Fx.rewriter(pl, "…"));
                Fx.later(p, 150, pl -> Fx.joined(pl, true));
                Fx.later(p, 190, pl -> Fx.vesna(pl, Lang.tr(pl, "он не может это стереть. ты сказал это до вопроса", "it can't erase that. you said it before the question")));
                Fx.later(p, 270, pl -> Fx.vesna(pl, Lang.tr(pl, "я останусь эхом. это лучше, чем ничего", "i'll stay an echo. it's better than nothing")));
                Fx.later(p, 340, pl -> Fx.vesna(pl, Lang.tr(pl, "если увидишь красный свет — это я", "if you see red light — that's me")));
                Fx.later(p, 390, pl -> Fx.joined(pl, false));
                Fx.later(p, 480, pl -> Fx.rewriter(pl, Lang.tr(pl, "строка сохранена. я подожду шестого.", "line saved. i will wait for the sixth.")));
                Fx.file(p, "помни.txt", "remember.txt", "она осталась.\r\n\r\nэтого файла нет в сценарии.\r\n",
                    "she stayed.\r\n\r\nthis file is not in the script.\r\n", false);
            }
        }
    }

    // ================= присутствие и фоновые события =================

    private static void presence(ServerPlayer p, ServerLevel l, State s) {
        BlockPos pos = p.blockPosition();
        int in = TbsEcho.INTENSITY.get();
        float g = 0;
        if (l.getMaxLocalRawBrightness(pos) < 5) g += 0.5f;
        if (!l.canSeeSky(pos) && pos.getY() < 56) g += 0.3f;
        if (night(l)) g += 0.3f;
        if (g == 0) {
            g = -0.5f;
        } else {
            g *= (0.5f + 0.15f * s.act) * (in == 1 ? 0.6f : in == 2 ? 1f : 1.4f);
            if (s.ending == 3) g *= 0.5f;
        }
        s.presence = Mth.clamp(s.presence + g, 0f, 100f);
    }

    private static void ambient(ServerPlayer p, ServerLevel l, State s) {
        if (s.played < s.nextAmbient) return;
        RandomSource r = p.getRandom();
        int in = TbsEcho.INTENSITY.get();
        float base = in == 1 ? 420f : in == 2 ? 300f : 200f; // сюжетные события редкие
        s.nextAmbient = s.played + (long) (20 * base * (1.6f - s.presence / 100f)) + r.nextInt(600);
        if (r.nextFloat() * 100f > s.presence + 15f) return;
        if (s.act == 5 && s.trialState == 1) return;
        List<Integer> pool = new ArrayList<>();
        if (s.act >= 1) {
            pool.addAll(List.of(1, 2, 3, 3, 4));
            if (s.act >= 2) pool.addAll(List.of(5, 6, 7, 8, 8));
            if (s.act >= 3) pool.addAll(List.of(9, 9, 10, 11));
            if (s.act >= 5 && in >= 3 && s.presence > 85) pool.add(12);
        }
        if (!pool.isEmpty()) event(p, l, s, pool.get(r.nextInt(pool.size())));
    }

    /** Шаги — только вместе с фигурой за спиной; иначе беззвучный шёпот. */
    private static void steps(ServerPlayer p) {
        if (!tbs.director.Causal.footsteps(p.server, p)) whisper(p);
    }

    private static void whisper(ServerPlayer p) {
        RandomSource r = p.getRandom();
        int hour = LocalTime.now().getHour();
        if (hour < 5 && r.nextInt(3) == 0) {
            Fx.actionbar(p, String.format(Lang.tr(p, "у тебя %02d:%02d. ложись. я посторожу.", "it's %02d:%02d where you are. go to bed. i'll keep watch."),
                hour, LocalTime.now().getMinute()));
        } else {
            String[] w = Story.whispers(Lang.ru(p));
            Fx.actionbar(p, w[r.nextInt(w.length)]);
        }
    }

    private static void event(ServerPlayer p, ServerLevel l, State s, int id) {
        RandomSource r = p.getRandom();
        boolean ru = Lang.ru(p);
        switch (id) {
            case 0 -> {
                if (!tbs.director.Causal.cave(p)) whisper(p);
            }
            case 1 -> steps(p);
            case 2 -> {
                if (!tbs.director.Causal.knock(p)) whisper(p);
            }
            case 3 -> whisper(p);
            case 4 -> {
                BlockPos at = Fx.findSpot(p, 4, 9);
                if (Fx.sign(p, at, Story.signs(ru)[r.nextInt(Story.SIGNS.length)])) {
                    Fx.sound(p, "minecraft:block.wood.place", Vec3.atCenterOf(at), 1f, 0.7f);
                }
            }
            case 5 -> p.sendSystemMessage(Component.literal(Fx.fmt(Story.fakeLogs(ru)[r.nextInt(Story.FAKE_LOGS.length)], p)).withStyle(ChatFormatting.GRAY));
            case 6 -> {
                if (s.lastChat.isEmpty() || s.lastChat.startsWith("/")) {
                    whisper(p);
                } else if (s.act == 2) {
                    Fx.vesna(p, s.lastChat.replace("%", ""));
                    Fx.later(p, 50, pl -> Fx.vesna(pl, Lang.tr(pl, "это не я написала", "i didn't write that")));
                } else {
                    p.sendSystemMessage(Component.literal("<" + p.getGameProfile().getName() + "> " + s.lastChat));
                    Fx.later(p, 60, pl -> Fx.rewriter(pl, Lang.tr(pl, "я учусь писать как ты.", "i'm learning to write like you.")));
                }
            }
            case 7 -> {
                if (!tbs.director.Causal.darkness(p)) whisper(p);
            }
            case 8 -> {
                if (s.act == 2) Fx.vesna(p, Story.vesnaLines(ru)[r.nextInt(Story.VESNA_LINES.length)]);
                else if (s.act >= 3 && !(s.act == 7 && s.ending == 3)) Fx.rewriter(p, Story.rewriterLines(ru)[r.nextInt(Story.REWRITER_LINES.length)]);
                else whisper(p);
            }
            case 9 -> {
                BlockPos spot;
                if (l.canSeeSky(p.blockPosition())) {
                    float yaw = p.getYRot() + (r.nextFloat() - 0.5f) * 100f;
                    Vec3 dir = Vec3.directionFromRotation(0, yaw).scale(24 + r.nextInt(16));
                    int x = Mth.floor(p.getX() + dir.x), z = Mth.floor(p.getZ() + dir.z);
                    spot = l.hasChunkAt(new BlockPos(x, p.getBlockY(), z))
                        ? new BlockPos(x, l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z) : null;
                } else {
                    spot = Fx.findSpot(p, 10, 16);
                }
                if (Fx.spawn(p, spot, "circuit_stare", "siluet_stare", "null_watching", "stare", "nothing_watcher", "faraway") == null) {
                    whisper(p);
                }
            }
            case 10 -> {
                if (!TbsEcho.TAMPER_INVENTORY.get()) {
                    whisper(p);
                    return;
                }
                List<ItemStack> c = new ArrayList<>();
                for (ItemStack st : p.getInventory().items) {
                    if (!st.isEmpty() && st.getMaxStackSize() == 1 && !st.has(DataComponents.CUSTOM_NAME)) c.add(st);
                }
                if (c.isEmpty()) {
                    whisper(p);
                    return;
                }
                c.get(r.nextInt(c.size())).set(DataComponents.CUSTOM_NAME,
                    Component.literal(Fx.fmt(Story.renames(ru)[r.nextInt(Story.RENAMES.length)], p)).withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
            }
            case 11 -> {
                BlockPos bed = p.getRespawnPosition();
                if (bed == null) {
                    whisper(p);
                } else {
                    BlockPos at = Fx.findSpot(p, 4, 8);
                    if (Fx.sign(p, at, ru ? new String[] {"я знаю, где", "ты спишь:", bed.getX() + " " + bed.getZ(), ""}
                        : new String[] {"i know where", "you sleep:", bed.getX() + " " + bed.getZ(), ""})) {
                        Fx.sound(p, "minecraft:block.wood.place", Vec3.atCenterOf(at), 1f, 0.6f);
                    }
                }
            }
            case 12 -> {
                if (Fx.spawn(p, Fx.findSpot(p, 14, 22), "siluet_chase", "he_chase", "circuit_stalk") != null) Fx.rewriter(p, Lang.tr(p, "беги.", "run."));
            }
            default -> { }
        }
    }

    private static void deathSign(ServerPlayer p, ServerLevel l, State s) {
        if (s.act < 2 || !s.hasDeath || s.deathSigned || !s.deathDim.equals(l.dimension().location().toString())) return;
        double d2 = p.distanceToSqr(s.dx + 0.5, s.dy, s.dz + 0.5);
        if (d2 > 400 || d2 < 16) return;
        s.deathSigned = true;
        String[] text = Lang.ru(p) ? new String[] {"здесь ты", "умер.", "я смотрел.", ""} : new String[] {"you died", "here.", "i watched.", ""};
        for (BlockPos pos : new BlockPos[] {new BlockPos(s.dx, s.dy, s.dz), new BlockPos(s.dx, s.dy + 1, s.dz), new BlockPos(s.dx + 1, s.dy, s.dz), new BlockPos(s.dx, s.dy, s.dz + 1)}) {
            if (l.getBlockState(pos.below()).isSolidRender(l, pos.below()) && Fx.sign(p, pos, text)) return;
        }
    }
}
