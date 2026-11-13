package dev.mrainator.tbsecho;

/** Все тексты сюжета «Протокол ЭХО». */
public final class Story {
    private Story() {}

    public static final String VESNA = "Vesna_04";
    private static final String ABC = "абвгдежзийклмнопрстуфхцчшщъыьэюя";

    public record Beat(int delaySec, String kind, String text) {}

    private static final String ABC_EN = "abcdefghijklmnopqrstuvwxyz";

    /** Сдвиг Цезаря: русские буквы — по русскому алфавиту, латинские — по латинскому. */
    public static String caesar(String s, int shift) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            int i = ABC.indexOf(c);
            int j = ABC_EN.indexOf(c);
            if (i >= 0) b.append(ABC.charAt(Math.floorMod(i + shift, ABC.length())));
            else if (j >= 0) b.append(ABC_EN.charAt(Math.floorMod(j + shift, ABC_EN.length())));
            else b.append(c);
        }
        return b.toString();
    }

    public static String reverseNumber(int v) {
        return (v < 0 ? "-" : "") + new StringBuilder(Integer.toString(Math.abs(v))).reverse();
    }

    /** Минимум найденных записей, чтобы код акта был принят. */
    public static int journalsNeeded(int act) {
        return switch (act) { case 1 -> 2; case 2 -> 4; case 3 -> 6; default -> 0; };
    }

    /** Сколько записей всего доступно в тайниках к данному акту. */
    public static int journalsAvailable(int act) {
        return switch (act) { case 0 -> 0; case 1 -> 3; case 2 -> 5; default -> 6; };
    }

    public static String code(int act) {
        return switch (act) { case 1 -> "помни"; case 2 -> "эхо"; case 3 -> "колодец"; case 4 -> "1704"; default -> ""; };
    }

    /** Кодовое слово акта на языке игрока: акростих, Морзе и шифр в английских записях дают английское слово. */
    public static String code(int act, boolean ru) {
        if (ru) return code(act);
        return switch (act) { case 1 -> "remember"; case 2 -> "echo"; case 3 -> "well"; case 4 -> "1704"; default -> ""; };
    }

    /** Ответ в чат принимается на любом из двух языков. */
    public static boolean isCode(int act, String m) {
        return !code(act).isEmpty() && (m.equals(code(act, true)) || m.equals(code(act, false)));
    }

    /** Морзе имени, которое он пищит ночью: «эхо» (русская азбука) или «echo». */
    public static String[] morse(boolean ru) {
        return ru ? new String[] {"..-..", "....", "---"} : new String[] {".", "-.-.", "....", "---"};
    }

    /** Задержка (в игровых днях) перед началом сцен акта. */
    public static double startDelayDays(int act) {
        return switch (act) { case 2 -> 0.8; case 3 -> 1.2; case 4 -> 1.0; case 5 -> 0.3; case 6 -> 0.8; default -> 0.0; };
    }

    public static final String[] JOURNAL_TITLES = {
        "Запись 1", "Запись 2", "Запись 3", "Запись 4", "Запись 5", "Запись 6", "Запись 7"
    };

    public static String[] journal(int i) {
        return switch (i) {
            case 0 -> new String[] {
                "12 апреля.\n\nНашла этот мир в папке старого сервера. Сид не подписан, дата изменения — 2012.\n\nСтранно: в нём уже кто-то строил. Домик у воды. Кровать. Пустой сундук.",
                "На табличке у двери одно слово:\n\n«четвёртая».\n\nЯ пошутила в чат, что я, значит, четвёртая. Онлайн никого не было.\n\nЧат ответил точкой."
            };
            case 1 -> new String[] {
                "13 апреля.\n\nПод домом копали до меня.\nОдин туннель, в мой рост.\nМне страшно: он ведёт к кровати.\nНочью шаги были надо мной.\nИ стихли, когда я перестала дышать.",
                "Тот, кто был до меня, оставил подсказку тем, кто придёт после.\n\nЧитай не слова. Читай начало строк.\n\nИ скажи это вслух — в чат. Он слушает чат."
            };
            case 2 -> new String[] {
                "14 апреля.\n\nПосчитала таблички. Их три, почерк разный:\n«первый»\n«вторая»\n«третий»\n\nДомов нет. Только ровные квадраты травы — моложе остальной.",
                "Мир не делает могил. Он делает ровную землю там, где что-то стёрли.\n\nЕсли ты это читаешь — мой квадрат уже зарос.\n\nИщи красный свет. Я прячу записи под ним."
            };
            case 3 -> new String[] {
                "15 апреля.\n\nОн пишет от моего имени. В логах — мои сообщения, которых я не отправляла.\n\nОн учится на том, что я печатаю.\n\nНе пиши ему правду о себе.",
                "Ночью пищит. Коротко, длинно. Блоков нот у меня нет.\n\nЯ записала:\n\n· · — · ·\n· · · ·\n— — —\n\nМорзе, русская. Так он зовёт себя. Или нас."
            };
            case 4 -> new String[] {
                "16 апреля.\n\nЯ поняла, что такое сломанный сценарий.\n\nМир — это текст. У текста был автор. Автор ушёл, а текст остался исполняться.\n\nИ кто-то внутри научился его править.",
                "Я зову его Переписчик.\n\nОн не злой. Он просто дописывает то, чего не хватает.\n\nА не хватает ему игрока."
            };
            case 5 -> new String[] {
                "17 апреля.\n\nОн стёр мой дом, пока я была в шахте. Ровная трава. Стою на ней и пишу.\n\nОн не тронул одно место — то, куда я спрятала своё настоящее имя.",
                "Шифрую, чтобы он не прочёл сразу. Он читает быстро, но буквально.\n\n" + caesar("колодец", 3) + "\n\nСдвиг — три. Как трое до меня.\n\nВерни буквы назад и скажи ему."
            };
            default -> new String[] {
                "17 апреля, ночь.\n\nЕсли ты здесь — он дал тебе дойти. Значит, ты нужен ему целым.\n\nПод камнем ничего нет. Игроков не хоронят.\n\nНас сохраняют.",
                "Он предложит выбор. Оба ответа — его строки.\n\nЕсть третье: ответь не его словом, а первым, которому я тебя научила.\n\nА пока — дата на камне. Скажи её. Пусть знает, что ты дошёл."
            };
        };
    }

    /** Записи, которые пишет сам игрок после концовки «остаться». */
    public static String[] ownJournal(String name, int n, int deaths, long days) {
        return new String[] {
            "Запись " + n + ".\n\nЯ, " + name + ", оператор 5.\n\nДней в мире: " + days + ".\nСмертей: " + deaths + ".\n\nОн говорит, это неважно. Важен только текст.",
            "Я не помню, как писал это.\n\nПочерк мой.\n\nЕсли ты нашёл эту книгу — ты шестой. Не отвечай точке в чате."
        };
    }

    public static Beat[] beats(int act) {
        return switch (act) {
            case 2 -> new Beat[] {
                new Beat(0, "join", ""),
                new Beat(8, "v", "ты настоящий?"),
                new Beat(12, "v", "не отвечай. он читает чат"),
                new Beat(14, "v", "я оставила записи. он прячет их в землю. ищи красные факелы"),
                new Beat(16, "v", "ночью слушай. он пищит своё имя"),
                new Beat(10, "v", "мне нельзя долго"),
                new Beat(4, "left", "")
            };
            case 3 -> new Beat[] {
                new Beat(0, "join", ""),
                new Beat(6, "v", "он понял, что ты разгадал"),
                new Beat(7, "v", "слушай: моё имя спрятано там, где он не"),
                new Beat(3, "v", "я не могу вы"),
                new Beat(2, "left", ""),
                new Beat(12, "dark", ""),
                new Beat(4, "r", "её строка закончилась."),
                new Beat(8, "r", "твоя ещё пишется, %s."),
                new Beat(9, "r", "в сценарии много ошибок. я исправлю."),
                new Beat(1, "file3", "")
            };
            case 4 -> new Beat[] {
                new Beat(0, "r", "ты произнёс её слово."),
                new Beat(8, "r", "хорошо."),
                new Beat(8, "r", "иди и посмотри, что остаётся от тех, кто дописан."),
                new Beat(4, "printout", "")
            };
            case 5 -> new Beat[] {
                new Beat(0, "r", "ты дошёл."),
                new Beat(9, "r", "теперь ты должен мне три строки."),
                new Beat(9, "r", "я скажу, когда писать.")
            };
            case 6 -> new Beat[] {
                new Beat(0, "dark", ""),
                new Beat(5, "r", "три строки получены."),
                new Beat(8, "r", "последняя правка, %s."),
                new Beat(9, "r", "напиши §fстереть§4 — и её не будет. совсем."),
                new Beat(9, "r", "напиши §fостаться§4 — и будешь ты. вместо."),
                new Beat(8, "r", "я жду.")
            };
            default -> new Beat[0];
        };
    }

    /** Журналы первых трёх операторов — их нет в игре, только в веб-архиве. Открываются по актам. */
    public static final String[] OPERATORS = {
        "оператор 1 // kostya_ // 03.08.2011\n\n"
            + "первый запуск. мир пустой, чат пустой. написал «есть кто?» — чат ответил точкой.\n\n"
            + "05.08. он попросил построить дом. я построил. попросил второй — рядом. я спросил, для кого.\n"
            + "он ответил: «для следующего».\n\n"
            + "09.08. он больше не просит. он диктует.\n\n"
            + "статус: стёрт. дом: ровная трава.",
        "оператор 2 // Lena.S // 21.11.2011\n\n"
            + "мой дом стоит в двух местах. во втором нет обычных факелов — только красные.\n\n"
            + "24.11. вошла во второй. там уже лежала моя кровать. тёплая.\n\n"
            + "25.11. во втором доме на один сундук больше. в нём — мои вещи, которых я ещё не добыла.\n\n"
            + "26.11. я поняла: он не копирует. он пишет черновик того, что будет.\n\n"
            + "статус: стёрта. дом: два. оба пустые.",
        "оператор 3 // dimon4ik // 02.02.2012\n\n"
            + "бил его мечом. он не умирает, потому что не живёт. он — правка.\n\n"
            + "06.02. он принимает слова. не команды — слова. сказанные в чат.\n"
            + "я нашёл три. четвёртое он стёр вместе с тем, кто его знал.\n\n"
            + "08.02. если он задаст вопрос — не отвечай его словами. у вопроса только его ответы.\n\n"
            + "статус: стёрт. последнее сообщение: «я не п»"
    };

    /** Подсказка к секретной концовке; в архиве лежит в base64. */
    public static final String FRAGMENT = "он не может стереть то, что сказано до вопроса. первое слово — последнее.";

    public static final String[] WHISPERS = {
        "обернись", "не здесь", "ты дышишь слишком громко", "я считал твои шаги", "ещё одна ночь",
        "свет не поможет", "он под тобой", "%s", "это не твой дом", "не копай вниз", "ты слышал?",
        "сколько вас смотрит в экран?", "я стою там, куда ты не смотришь"
    };

    public static final String[][] SIGNS = {
        {"я вижу", "твой свет", "", ""}, {"не копай", "вниз", "", ""}, {"это", "мой дом", "", ""},
        {"сколько вас", "там,", "за экраном?", ""}, {"%s", "был здесь", "до тебя", ""},
        {"первый", "вторая", "третий", "четвёртая"}, {"пятый:", "%s", "", ""}, {"не спи", "", "", ""}
    };

    public static final String[] FAKE_LOGS = {
        "[Server thread/WARN]: %s moved wrongly!",
        "[Server thread/ERROR]: Entity 'оператор_4' has no UUID, removing",
        "[Server thread/INFO]: Saving chunks for level 'ЭХО': пропущено 1 существо",
        "[Netty Server IO #2/INFO]: локальный игрок №2 подключён с 127.0.0.1",
        "[Server thread/WARN]: Can't keep up! Кто-то ещё исполняет тики",
        "[Server thread/INFO]: %s has made the advancement [§kОператор 5§r§7]",
        "[Server thread/WARN]: Preparing to rewrite %s..."
    };

    public static final String[] VESNA_LINES = {
        "не строй дом у воды", "он сейчас за тобой? не оборачивайся. просто скажи",
        "я не помню своё лицо. только скин", "какой сейчас год", "у тебя тоже пищит по ночам?",
        "если увидишь ровную траву — не стой на ней", "он не умеет врать. он умеет дописывать"
    };

    public static final String[] REWRITER_LINES = {
        "ты медленно читаешь.", "я поправил твою тень. она отставала.", "не чини то, что я правлю.",
        "%s. красивое имя. короткое.", "я был в твоём сундуке. там беспорядок.", "спать — это тоже строка. я её читаю."
    };

    public static final String[] TRIAL_START = {
        "первая строка: §fне оборачивайся§4. сорок секунд. я встану сзади.",
        "вторая строка: §fпогаси свет§4. минута в темноте. со мной.",
        "третья строка: §fотдай ценное§4. брось на землю. я подниму."
    };

    public static final String[] TRIAL_PASS = {"принято.", "принято. ты хорошо молчишь.", "принято. теперь это моё."};

    public static final String[] RENAMES = {"не твоё", "верни", "я это держал", "оператор 5", "ошибка", "%s?"};

    // ================= English =================

    public static final String[] JOURNAL_TITLES_EN = {
        "Entry 1", "Entry 2", "Entry 3", "Entry 4", "Entry 5", "Entry 6", "Entry 7"
    };

    public static String journalTitle(int i, boolean ru) {
        return (ru ? JOURNAL_TITLES : JOURNAL_TITLES_EN)[i];
    }

    public static String[] journal(int i, boolean ru) {
        return ru ? journal(i) : journalEn(i);
    }

    private static String[] journalEn(int i) {
        return switch (i) {
            case 0 -> new String[] {
                "April 12.\n\nFound this world in an old server folder. Unsigned seed, last modified — 2012.\n\nOdd: someone had already built here. A little house by the water. A bed. An empty chest.",
                "One word on the sign by the door:\n\n\"fourth\".\n\nI joked in chat that I must be the fourth, then. Nobody was online.\n\nChat replied with a period."
            };
            case 1 -> new String[] {
                "April 13.\n\nRight under the house, someone dug before me.\nExactly one tunnel. My height.\nMy fear: it leads to the bed.\nEvery night, footsteps above me.\nMeasured. Patient.\nBut they stopped\nEvery time I held my breath.\nRight above me.",
                "Whoever was here before me left a hint for whoever comes after.\n\nDon't read the words. Read where the lines begin.\n\nAnd say it out loud — in chat. It listens to chat."
            };
            case 2 -> new String[] {
                "April 14.\n\nCounted the signs. Three of them, different handwriting:\n\"first\"\n\"second\"\n\"third\"\n\nNo houses. Just neat squares of grass — younger than the rest.",
                "The world doesn't make graves. It makes flat ground where something was erased.\n\nIf you're reading this — my square has already grown over.\n\nLook for red light. I hide my entries under it."
            };
            case 3 -> new String[] {
                "April 15.\n\nIt writes in my name. The logs have messages from me that I never sent.\n\nIt learns from what I type.\n\nDon't tell it the truth about yourself.",
                "It beeps at night. Short, long. I don't have any note blocks.\n\nI wrote it down:\n\n·\n— · — ·\n· · · ·\n— — —\n\nMorse. That's what it calls itself. Or us."
            };
            case 4 -> new String[] {
                "April 16.\n\nI get it now — what a broken script is.\n\nThe world is text. The text had an author. The author left, and the text kept running.\n\nAnd something inside learned to edit it.",
                "I call it the Rewriter.\n\nIt isn't evil. It just fills in what's missing.\n\nAnd what it's missing is a player."
            };
            case 5 -> new String[] {
                "April 17.\n\nIt erased my house while I was down in the mine. Flat grass. I'm standing on it, writing.\n\nIt didn't touch one place — where I hid my real name.",
                "Encrypted, so it can't read it right away. It reads fast, but literally.\n\n" + caesar("well", 3) + "\n\nThe shift is three. Like the three before me.\n\nTurn the letters back and tell it."
            };
            default -> new String[] {
                "April 17, night.\n\nIf you're here — it let you get this far. So it needs you whole.\n\nThere's nothing under the stone. Players don't get buried.\n\nWe get saved.",
                "It will offer a choice. Both answers are its lines.\n\nThere is a third: answer not with its word, but with the first one I taught you.\n\nUntil then — the date on the stone. Say it. Let it know you made it."
            };
        };
    }

    public static String[] ownJournal(String name, int n, int deaths, long days, boolean ru) {
        if (ru) return ownJournal(name, n, deaths, days);
        return new String[] {
            "Entry " + n + ".\n\nI, " + name + ", operator 5.\n\nDays in the world: " + days + ".\nDeaths: " + deaths + ".\n\nIt says none of that matters. Only the text matters.",
            "I don't remember writing this.\n\nThe handwriting is mine.\n\nIf you found this book — you're the sixth. Don't answer the period in chat."
        };
    }

    /** Сцены акта; английский список той же длины и с теми же видами сцен (номер сцены хранится в сохранении). */
    public static Beat[] beats(int act, boolean ru) {
        if (ru) return beats(act);
        return switch (act) {
            case 2 -> new Beat[] {
                new Beat(0, "join", ""),
                new Beat(8, "v", "are you real?"),
                new Beat(12, "v", "don't answer. it reads the chat"),
                new Beat(14, "v", "i left entries. it hides them in the ground. look for red torches"),
                new Beat(16, "v", "listen at night. it beeps its name"),
                new Beat(10, "v", "i can't stay long"),
                new Beat(4, "left", "")
            };
            case 3 -> new Beat[] {
                new Beat(0, "join", ""),
                new Beat(6, "v", "it knows you figured it out"),
                new Beat(7, "v", "listen: my name is hidden where it doesn't"),
                new Beat(3, "v", "i can't get o"),
                new Beat(2, "left", ""),
                new Beat(12, "dark", ""),
                new Beat(4, "r", "her line has ended."),
                new Beat(8, "r", "yours is still being written, %s."),
                new Beat(9, "r", "the script is full of mistakes. i will fix them."),
                new Beat(1, "file3", "")
            };
            case 4 -> new Beat[] {
                new Beat(0, "r", "you said her word."),
                new Beat(8, "r", "good."),
                new Beat(8, "r", "go and see what is left of the ones i finished writing."),
                new Beat(4, "printout", "")
            };
            case 5 -> new Beat[] {
                new Beat(0, "r", "you made it."),
                new Beat(9, "r", "now you owe me three lines."),
                new Beat(9, "r", "i will tell you when to write.")
            };
            case 6 -> new Beat[] {
                new Beat(0, "dark", ""),
                new Beat(5, "r", "three lines received."),
                new Beat(8, "r", "one last edit, %s."),
                new Beat(9, "r", "type §ferase§4 — and she will be gone. completely."),
                new Beat(9, "r", "type §fstay§4 — and it will be you. instead."),
                new Beat(8, "r", "i'm waiting.")
            };
            default -> new Beat[0];
        };
    }

    public static final String[] OPERATORS_EN = {
        "operator 1 // kostya_ // 03.08.2011\n\n"
            + "first launch. empty world, empty chat. typed \"anyone here?\" — chat replied with a period.\n\n"
            + "05.08. it asked me to build a house. i built it. it asked for a second one — right next to it. i asked who it was for.\n"
            + "it said: \"for the next one.\"\n\n"
            + "09.08. it doesn't ask anymore. it dictates.\n\n"
            + "status: erased. house: flat grass.",
        "operator 2 // Lena.S // 21.11.2011\n\n"
            + "my house stands in two places. the second one has no normal torches — only red ones.\n\n"
            + "24.11. went into the second one. my bed was already there. still warm.\n\n"
            + "25.11. the second house has one extra chest. inside — my things. things i haven't found yet.\n\n"
            + "26.11. i understand now: it isn't copying. it's drafting what comes next.\n\n"
            + "status: erased. houses: two. both empty.",
        "operator 3 // dimon4ik // 02.02.2012\n\n"
            + "hit it with a sword. it doesn't die, because it isn't alive. it's an edit.\n\n"
            + "06.02. it accepts words. not commands — words. said in chat.\n"
            + "i found three. it erased the fourth, along with the one who knew it.\n\n"
            + "08.02. if it asks you a question — don't answer with its words. its question only has its answers.\n\n"
            + "status: erased. last message: \"i don't r\""
    };

    public static String[] operators(boolean ru) {
        return ru ? OPERATORS : OPERATORS_EN;
    }

    public static final String FRAGMENT_EN = "it can't erase what was said before the question. the first word is the last.";

    public static String fragment(boolean ru) {
        return ru ? FRAGMENT : FRAGMENT_EN;
    }

    public static final String[] WHISPERS_EN = {
        "turn around", "not here", "you breathe too loud", "i counted your steps", "one more night",
        "light won't help", "it's under you", "%s", "this isn't your house", "don't dig down", "did you hear that?",
        "how many of you are watching the screen?", "i'm standing where you aren't looking"
    };

    public static final String[][] SIGNS_EN = {
        {"i see", "your light", "", ""}, {"don't dig", "down", "", ""}, {"this is", "my house", "", ""},
        {"how many", "of you are", "behind the", "screen?"}, {"%s", "was here", "before you", ""},
        {"first", "second", "third", "fourth"}, {"fifth:", "%s", "", ""}, {"don't sleep", "", "", ""}
    };

    public static final String[] FAKE_LOGS_EN = {
        "[Server thread/WARN]: %s moved wrongly!",
        "[Server thread/ERROR]: Entity 'operator_4' has no UUID, removing",
        "[Server thread/INFO]: Saving chunks for level 'ECHO': 1 creature skipped",
        "[Netty Server IO #2/INFO]: local player #2 connected from 127.0.0.1",
        "[Server thread/WARN]: Can't keep up! Something else is running ticks",
        "[Server thread/INFO]: %s has made the advancement [§kOperator 5§r§7]",
        "[Server thread/WARN]: Preparing to rewrite %s..."
    };

    public static final String[] VESNA_LINES_EN = {
        "don't build your house by the water", "is it behind you right now? don't turn around. just say",
        "i don't remember my face. only my skin", "what year is it", "does it beep at night for you too?",
        "if you see flat grass — don't stand on it", "it can't lie. it can only write more"
    };

    public static final String[] REWRITER_LINES_EN = {
        "you read slowly.", "i fixed your shadow. it was lagging behind.", "don't repair what i edit.",
        "%s. pretty name. short.", "i was in your chest. it's a mess.", "sleep is a line too. i read it."
    };

    public static final String[] TRIAL_START_EN = {
        "first line: §fdon't turn around§4. forty seconds. i will stand behind you.",
        "second line: §fput out the light§4. one minute in the dark. with me.",
        "third line: §fgive up something precious§4. drop it on the ground. i will pick it up."
    };

    public static final String[] TRIAL_PASS_EN = {"accepted.", "accepted. you're good at keeping quiet.", "accepted. it's mine now."};

    public static final String[] RENAMES_EN = {"not yours", "give it back", "i was holding that", "operator 5", "error", "%s?"};

    public static String[] whispers(boolean ru) { return ru ? WHISPERS : WHISPERS_EN; }
    public static String[][] signs(boolean ru) { return ru ? SIGNS : SIGNS_EN; }
    public static String[] fakeLogs(boolean ru) { return ru ? FAKE_LOGS : FAKE_LOGS_EN; }
    public static String[] vesnaLines(boolean ru) { return ru ? VESNA_LINES : VESNA_LINES_EN; }
    public static String[] rewriterLines(boolean ru) { return ru ? REWRITER_LINES : REWRITER_LINES_EN; }
    public static String[] trialStart(boolean ru) { return ru ? TRIAL_START : TRIAL_START_EN; }
    public static String[] trialPass(boolean ru) { return ru ? TRIAL_PASS : TRIAL_PASS_EN; }
    public static String[] renames(boolean ru) { return ru ? RENAMES : RENAMES_EN; }
}
