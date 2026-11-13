package tbs.director;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * /nullAI — настройка ИИ без правки config.properties руками.
 * /nullAI                                    — роли, ключи и кнопки в чате
 * /nullAI brain|speech claude|grok|custom    — кто решает и кто говорит
 * /nullAI swap                               — поменять роли местами
 * /nullAI key claude|grok|custom <key|clear> — ключ (только владелец)
 * /nullAI custom <preset>                    — любой OpenAI-совместимый ИИ: адрес и модель по готовому набору
 * /nullAI custom url <url> | model <id>      — то же вручную
 * Всё сразу действует и сохраняется в конфиг; остальные строки конфига не трогаются.
 */
final class NullAiCommand {
    private NullAiCommand() {
    }

    /** Готовые наборы для «своего» ИИ: имя, база API, модель по умолчанию, где взять ключ (пусто — ключ не нужен). */
    record Preset(String id, String name, String url, String model, String keyFrom) {}

    static final List<Preset> PRESETS = List.of(
            new Preset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-5-mini", "platform.openai.com/api-keys"),
            new Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "google/gemini-2.5-flash", "openrouter.ai/keys"),
            new Preset("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "aistudio.google.com/apikey"),
            new Preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "platform.deepseek.com/api_keys"),
            new Preset("mistral", "Mistral", "https://api.mistral.ai/v1", "mistral-small-latest", "console.mistral.ai/api-keys"),
            new Preset("groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "console.groq.com/keys"),
            new Preset("ollama", "Ollama", "http://localhost:11434/v1", "llama3.1", ""),
            new Preset("lmstudio", "LM Studio", "http://localhost:1234/v1", "local-model", ""));

    static Preset preset(String id) {
        for (Preset p : PRESETS) {
            if (p.id().equalsIgnoreCase(id)) {
                return p;
            }
        }
        return null;
    }

    static void register(CommandDispatcher<CommandSourceStack> d, Supplier<DirectorConfig> cfg, Path dir) {
        // Команды в Minecraft чувствительны к регистру — регистрируем и /nullAI, и /nullai
        for (String name : new String[] {"nullAI", "nullai"}) {
            d.register(build(name, cfg, dir));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name, Supplier<DirectorConfig> cfg, Path dir) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name).requires(s -> s.hasPermission(2))
                .executes(c -> show(c, cfg.get()));
        for (String role : new String[] {"brain", "speech"}) {
            LiteralArgumentBuilder<CommandSourceStack> r = Commands.literal(role);
            for (String ai : Brain.PROVIDERS) {
                r.then(Commands.literal(ai).executes(c -> set(c, cfg.get(), dir, role, ai)));
            }
            root.then(r);
        }
        root.then(Commands.literal("swap").executes(c -> {
            DirectorConfig k = cfg.get();
            String b = k.brain;
            set(c, k, dir, "brain", k.speech, false);
            return set(c, k, dir, "speech", b);
        }));
        // ключи и адреса — только владелец (уровень 4; в одиночке — с читами)
        LiteralArgumentBuilder<CommandSourceStack> key = Commands.literal("key").requires(s -> s.hasPermission(4));
        for (String ai : Brain.PROVIDERS) {
            key.then(Commands.literal(ai)
                    .then(Commands.literal("clear").executes(c -> setKey(c, cfg.get(), dir, ai, "")))
                    .then(Commands.argument("key", StringArgumentType.greedyString())
                            .executes(c -> setKey(c, cfg.get(), dir, ai, StringArgumentType.getString(c, "key")))));
        }
        root.then(key);
        LiteralArgumentBuilder<CommandSourceStack> custom = Commands.literal("custom").requires(s -> s.hasPermission(4));
        for (Preset p : PRESETS) {
            custom.then(Commands.literal(p.id()).executes(c -> usePreset(c, cfg.get(), dir, p)));
        }
        custom.then(Commands.literal("url").then(Commands.argument("url", StringArgumentType.greedyString())
                .executes(c -> setCustom(c, cfg.get(), dir, "customUrl", StringArgumentType.getString(c, "url").trim()))));
        custom.then(Commands.literal("model").then(Commands.argument("model", StringArgumentType.greedyString())
                .executes(c -> setCustom(c, cfg.get(), dir, "customModel", StringArgumentType.getString(c, "model").trim()))));
        custom.then(Commands.literal("name").then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(c -> setCustom(c, cfg.get(), dir, "customName", StringArgumentType.getString(c, "name").trim()))));
        root.then(custom);
        // /nullAI model claude|grok|custom <id> — какая модель отвечает (влияет на цену — только владелец)
        LiteralArgumentBuilder<CommandSourceStack> model = Commands.literal("model").requires(s -> s.hasPermission(4));
        for (String ai : Brain.PROVIDERS) {
            model.then(Commands.literal(ai).then(Commands.argument("model", StringArgumentType.greedyString())
                    .executes(c -> setCustom(c, cfg.get(), dir, modelField(ai), StringArgumentType.getString(c, "model").trim()))));
        }
        root.then(model);
        return root;
    }

    /** Поле конфига с моделью этого ИИ. */
    static String modelField(String ai) {
        return switch (ai) {
            case "grok" -> "grokModel";
            case "custom" -> "customModel";
            default -> "model";
        };
    }

    /** Поле конфига для ключа этого ИИ. */
    static String keyField(String ai) {
        return switch (ai) {
            case "grok" -> "grokApiKey";
            case "custom" -> "customApiKey";
            default -> "apiKey";
        };
    }

    /** Ключ без пробелов и кавычек; пусто — значит «удалить». */
    static String cleanKey(String raw) {
        return raw == null ? "" : raw.trim().replace("\"", "").replace("'", "").replaceAll("\\s+", "");
    }

    /** Похож ли ключ на ключ этого ИИ (sk-ant-… у Anthropic, xai-… у xAI; у остальных форматы разные — только длина). */
    static boolean looksValid(String ai, String key) {
        return switch (ai) {
            case "grok" -> key.startsWith("xai-") && key.length() > 20;
            case "custom" -> key.length() >= 8;
            default -> key.startsWith("sk-ant-") && key.length() > 20;
        };
    }

    /** Показывает ключ так, чтобы его нельзя было переписать с экрана: начало-префикс и последние четыре символа. */
    static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "—";
        }
        int head = key.startsWith("sk-ant-") ? 7 : key.startsWith("xai-") ? 4 : key.startsWith("sk-") ? 3 : 0;
        return key.substring(0, head) + "…" + key.substring(Math.max(head, key.length() - 4));
    }

    private static int setKey(CommandContext<CommandSourceStack> c, DirectorConfig k, Path dir, String ai, String raw) {
        CommandSourceStack src = c.getSource();
        String v = cleanKey(raw);
        String who = Brain.label(k, ai);
        if (!v.isEmpty() && !looksValid(ai, v)) {
            String hint = switch (ai) {
                case "grok" -> L.tr(src, " (он начинается с xai-)", " (it starts with xai-)");
                case "claude" -> L.tr(src, " (он начинается с sk-ant-)", " (it starts with sk-ant-)");
                default -> "";
            };
            src.sendFailure(Component.literal(L.tr(src, "[Null] Это не похоже на ключ " + who + hint + ". Ничего не изменено.",
                    "[Null] That doesn't look like a " + who + " key" + hint + ". Nothing changed.")));
            return 0;
        }
        switch (ai) {
            case "grok" -> k.grokApiKey = v;
            case "custom" -> k.customApiKey = v;
            default -> k.apiKey = v;
        }
        save(src, dir, keyField(ai), v);
        // в лог — только факт, без ключа
        DirectorMod.LOG.info("[understudy] {} {} the {} key", src.getTextName(), v.isEmpty() ? "removed" : "set", who);
        src.sendSuccess(() -> Component.literal(v.isEmpty()
                ? L.tr(src, "[Null] Ключ " + who + " удалён.", "[Null] " + who + " key removed.")
                : L.tr(src, "[Null] Ключ " + who + " сохранён: " + mask(v) + ". Не показывай этот экран и не делись конфигом.",
                        "[Null] " + who + " key saved: " + mask(v) + ". Don't share this screen or your config."))
                .withStyle(ChatFormatting.GREEN), false);
        if ("custom".equals(ai) && (k.customUrl.isBlank() || k.customModel.isBlank())) {
            src.sendSuccess(() -> Component.literal(L.tr(src,
                    "[Null] Теперь выбери сервис: /nullAI custom <openai|openrouter|gemini|deepseek|mistral|groq|ollama|lmstudio>",
                    "[Null] Now pick the service: /nullAI custom <openai|openrouter|gemini|deepseek|mistral|groq|ollama|lmstudio>"))
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return show(c, k);
    }

    private static int usePreset(CommandContext<CommandSourceStack> c, DirectorConfig k, Path dir, Preset p) {
        CommandSourceStack src = c.getSource();
        k.customName = p.name();
        k.customUrl = p.url();
        k.customModel = p.model();
        save(src, dir, "customName", p.name());
        save(src, dir, "customUrl", p.url());
        save(src, dir, "customModel", p.model());
        DirectorMod.LOG.info("[understudy] {} set the custom AI to {} ({}, {})", src.getTextName(), p.name(), p.url(), p.model());
        String keyHint = p.keyFrom().isEmpty()
                ? L.tr(src, " Ключ не нужен: запусти " + p.name() + " на этом компьютере и скачай модель " + p.model() + ".",
                        " No key needed: run " + p.name() + " on this computer and download the model " + p.model() + ".")
                : L.tr(src, " Ключ: " + p.keyFrom() + " → /nullAI key custom <ключ>.", " Key: " + p.keyFrom() + " → /nullAI key custom <key>.");
        src.sendSuccess(() -> Component.literal(L.tr(src,
                "[Null] Свой ИИ: " + p.name() + ", модель " + p.model() + ". Другая модель: /nullAI custom model <id>.",
                "[Null] Custom AI: " + p.name() + ", model " + p.model() + ". Other model: /nullAI custom model <id>.") + keyHint)
                .withStyle(ChatFormatting.GREEN), false);
        return show(c, k);
    }

    private static int setCustom(CommandContext<CommandSourceStack> c, DirectorConfig k, Path dir, String field, String value) {
        CommandSourceStack src = c.getSource();
        if ("customUrl".equals(field) && !value.matches("(?i)https?://\\S+")) {
            src.sendFailure(Component.literal(L.tr(src, "[Null] Адрес должен начинаться с http:// или https://",
                    "[Null] The address must start with http:// or https://")));
            return 0;
        }
        if (field.endsWith("odel") && !value.matches("[\\w./:@+-]{1,120}")) {
            src.sendFailure(Component.literal(L.tr(src, "[Null] Это не похоже на id модели (без пробелов, например claude-sonnet-5-5).",
                    "[Null] That doesn't look like a model id (no spaces, e.g. claude-sonnet-5-5).")));
            return 0;
        }
        switch (field) {
            case "customUrl" -> k.customUrl = value;
            case "customModel" -> k.customModel = value;
            case "model" -> {
                k.model = value;
                if (!value.contains("haiku")) {
                    k.maxTokens = Math.max(k.maxTokens, 4096); // у думающих моделей «думание» входит в max_tokens
                }
            }
            case "grokModel" -> k.grokModel = value;
            default -> k.customName = value;
        }
        save(src, dir, field, value);
        return show(c, k);
    }

    private static void save(CommandSourceStack src, Path dir, String field, String value) {
        try {
            DirectorConfig.setKey(dir, field, value);
        } catch (Exception e) {
            src.sendFailure(Component.literal(L.tr(src, "[Null] Работает до перезапуска, но в конфиг не записалось: ",
                    "[Null] Works until restart but was not saved: ") + e.getMessage()));
        }
    }

    private static int set(CommandContext<CommandSourceStack> c, DirectorConfig k, Path dir, String role, String ai) {
        return set(c, k, dir, role, ai, true);
    }

    private static int set(CommandContext<CommandSourceStack> c, DirectorConfig k, Path dir, String role, String ai, boolean report) {
        if ("brain".equals(role)) {
            k.brain = ai;
        } else {
            k.speech = ai;
        }
        save(c.getSource(), dir, role, ai);
        DirectorMod.LOG.info("[understudy] {} set {} to {}", c.getSource().getTextName(), role, ai);
        return report ? show(c, k) : 1;
    }

    static int show(CommandContext<CommandSourceStack> c, DirectorConfig k) {
        CommandSourceStack src = c.getSource();
        boolean ru = L.ru(src);
        MutableComponent out = Component.literal(L.tr(ru, "[Null] Кто за что отвечает:", "[Null] AI roles:")).withStyle(ChatFormatting.GRAY);
        out.append(line(ru, k, "brain", L.tr(ru, "Мозг", "Brain"), L.tr(ru, "решает, чем пугать и кто говорит", "decides the scare and the speaker"), k.brain));
        out.append(line(ru, k, "speech", L.tr(ru, "Речь", "Speech"), L.tr(ru, "пишет саму реплику", "writes the line"), k.speech));
        out.append(Component.literal("\n  "));
        out.append(button(L.tr(ru, "[поменять местами]", "[swap]"), "/nullAI swap",
                L.tr(ru, "Мозг и речь поменяются", "Swap brain and speech"), ChatFormatting.AQUA));
        if (Brain.anyAi(k)) {
            String b = Brain.provider(k.brain, k), s = Brain.provider(k.speech, k);
            String mode = b.equals(s)
                    ? L.tr(ru, "  Сейчас всё делает " + Brain.label(k, b) + " одним запросом.", "  " + Brain.label(k, b) + " does everything in one request.")
                    : L.tr(ru, "  Сейчас решает " + Brain.label(k, b) + ", говорит " + Brain.label(k, s) + ".",
                            "  " + Brain.label(k, b) + " decides, " + Brain.label(k, s) + " speaks.");
            out.append(Component.literal("\n" + mode).withStyle(ChatFormatting.DARK_GRAY));
        }
        out.append(Component.literal("\n  " + L.tr(ru, "Ключи: ", "Keys: ") + "Claude " + mask(k.apiKey) + " (" + k.model + ") · Grok " + mask(k.grokApiKey) + " (" + k.grokModel + ")"
                + " · " + Brain.label(k, "custom") + " " + (Brain.has(k, "custom")
                        ? mask(k.customApiKey) + " (" + k.customModel + ")"
                        : L.tr(ru, "не настроен", "not set up")))
                .withStyle(ChatFormatting.GRAY));
        if (!Brain.anyAi(k)) {
            out.append(Component.literal("\n  " + L.tr(ru,
                    "ИИ не подключён — Null говорит готовыми фразами (бесплатно, всё работает). Подключить:",
                    "No AI connected — Null uses built-in lines (free, everything works). To connect one:"))
                    .withStyle(ChatFormatting.YELLOW));
        }
        for (String ai : Brain.PROVIDERS) {
            if (Brain.has(k, ai)) {
                continue;
            }
            String cmd = "custom".equals(ai) ? "/nullAI custom " : "/nullAI key " + ai + " ";
            String where = switch (ai) {
                case "grok" -> "xAI (console.x.ai)";
                case "custom" -> "OpenAI, OpenRouter, Gemini, DeepSeek, Mistral, Groq, Ollama, LM Studio";
                default -> "Anthropic (console.anthropic.com)";
            };
            out.append(Component.literal("\n  "));
            out.append(Component.literal("[" + cmd + "…]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, cmd))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("custom".equals(ai)
                            ? L.tr(ru, "Нажми и выбери сервис: " + where, "Click and pick a service: " + where)
                            : L.tr(ru, "Нажми и вставь свой ключ " + where + ". Платишь за запросы ты сам.",
                                    "Click and paste your " + where + " key. You pay for your own usage."))))));
        }
        src.sendSuccess(() -> out, false);
        return 1;
    }

    private static MutableComponent line(boolean ru, DirectorConfig k, String role, String title, String hint, String current) {
        MutableComponent m = Component.literal("\n  " + title + ": ").withStyle(ChatFormatting.WHITE);
        m.append(Component.literal(Brain.label(k, current)).withStyle(ChatFormatting.GOLD));
        m.append(Component.literal("  "));
        for (String ai : Brain.PROVIDERS) {
            boolean on = ai.equals(current);
            String hover = (on ? L.tr(ru, "Уже выбран", "Already selected") : L.tr(ru, "Поставить " + Brain.label(k, ai) + ": ", "Set " + Brain.label(k, ai) + ": ") + hint)
                    + (Brain.has(k, ai) ? "" : L.tr(ru, "\nНе настроен — роль возьмёт другой ИИ", "\nNot set up — another AI will take it"));
            m.append(button("[" + Brain.label(k, ai) + "]", "/nullAI " + role + " " + ai, hover,
                    on ? ChatFormatting.GREEN : Brain.has(k, ai) ? ChatFormatting.DARK_AQUA : ChatFormatting.DARK_GRAY));
            m.append(Component.literal(" "));
        }
        m.append(Component.literal("— " + hint).withStyle(ChatFormatting.DARK_GRAY));
        return m;
    }

    private static MutableComponent button(String text, String command, String hover, ChatFormatting color) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    static String nice(String ai) {
        return switch (ai) {
            case "grok" -> "Grok";
            case "custom" -> "Custom";
            default -> "Claude";
        };
    }
}
