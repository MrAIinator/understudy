# Understudy

> *Something learns how you play — and starts playing in your place.*
> *Что-то изучает, как ты играешь, — и начинает играть вместо тебя.*

**An unofficial add-on for The Broken Script.** Requires The Broken Script 2.0.5+ · NeoForge 1.21.1
**Works in single player, on LAN and on dedicated servers / hosting.** Players don't need the mod to join a server that has it.

---

## 🇬🇧 English

### What's inside
- **A director that watches you.** It notices what you are doing — digging down alone, standing still too long, building at dusk, dying in the same cave — and stages scares around it. Every sound has a source you can find.
- **Mimics.** Offline players "join the game". Some attack at once. Others walk around, mine, place a torch, chat with you for a minute… then stop and stare. In single player they come with *your* name.
- **A dying world.** Over hours of play the world decays in five stages. Your builds are never touched.
- **Echoes.** Copies of your builds appear far away.
- **Protocol ECHO.** A long ARG-style story: hidden logs, ciphers, code words in chat, trials, three endings.
- **English and Russian**, chosen by each player's game language.
- **Optional AI** — Null improvises short lines about what you're doing. Everything works without it.

### Config
`config/understudy/config.properties` — created on first launch. Every option has a comment in English and Russian.
Next to it you'll find **`AI-SETUP.txt`** — a step-by-step guide for connecting an AI.

- Single player: `.minecraft/config/understudy/` (your launcher's instance folder)
- Server / hosting: `config/understudy/` in the server files

### Connecting an AI (optional)
Supported: **Claude** (Anthropic), **Grok** (xAI) and **any OpenAI-compatible API** — OpenAI, OpenRouter, Google Gemini, DeepSeek, Mistral, Groq, or a free **local model in Ollama / LM Studio** (no key, no internet). You use your own key and pay the provider yourself.

**In game** — no file editing (server owner; in single player: cheats on). On hosting, type it in the server console without `/`:
```
/nullAI key claude <key>          /nullAI key grok <key>
/nullAI custom openrouter         /nullAI key custom <key>
/nullAI model claude claude-haiku-5-5        ← choose the model (any of the three)
/nullAI brain grok    /nullAI speech claude  ← which AI decides, which AI speaks
/nullAI                                       ← current settings, clickable buttons
```
Presets for `/nullAI custom`: `openai`, `openrouter`, `gemini`, `deepseek`, `mistral`, `groq`, `ollama`, `lmstudio`.

**In the config** — paste the key after `=`, then `/null reload`:
```
apiKey=sk-ant-...          model=claude-sonnet-5-5
grokApiKey=xai-...         grokModel=grok-4
customUrl=https://openrouter.ai/api/v1
customApiKey=sk-or-...     customModel=google/gemini-2.5-flash
```
Keys are never shown in full and never written to the log. Hard limits (`maxAiCallsPerHour`, `maxAiCallsPerDay`) keep the bill under control. **Never share a config or screenshot with your key.**

### Admin commands
`/null status | on | off | reload | scare <player> | mimic <player> | why <player>` · `/nullAI` · `/tbsecho`

---

## 🇷🇺 Русский

### Что внутри
- **Режиссёр, который за тобой следит.** Замечает, что ты делаешь: копаешь вниз один, слишком долго стоишь, строишь в сумерках, снова умер в той же пещере, — и строит испуги вокруг этого. У каждого звука есть источник, который можно найти.
- **Мимики.** Игроки не в сети «заходят в игру». Одни сразу нападают. Другие минуту ходят рядом, копают, ставят факел, пишут в чат… потом замирают и смотрят. В одиночке приходят с *твоим* ником.
- **Мир умирает.** За часы игры мир проходит пять стадий. Твои постройки не трогаются.
- **Эхо.** Копии твоих построек появляются где-то далеко.
- **«Протокол ЭХО».** Долгий ARG-сюжет: тайники с записями, шифры, кодовые слова в чат, испытания, три концовки.
- **Русский и английский** — по языку игры каждого игрока.
- **ИИ по желанию** — Null импровизирует короткие реплики про то, что ты делаешь. Без ИИ всё тоже работает.

### Конфиг
`config/understudy/config.properties` — создаётся при первом запуске, у каждой настройки комментарий на русском и английском.
Рядом лежит **`AI-SETUP.txt`** — пошаговая инструкция по подключению ИИ.

- Одиночка: `.minecraft/config/understudy/` (папка сборки в лаунчере)
- Сервер / хостинг: `config/understudy/` в файлах сервера

### Подключение ИИ (по желанию)
Поддерживаются **Claude** (Anthropic), **Grok** (xAI) и **любой OpenAI-совместимый API** — OpenAI, OpenRouter, Google Gemini, DeepSeek, Mistral, Groq или бесплатная **локальная модель в Ollama / LM Studio** (без ключа и интернета). Ключ — твой, за запросы платишь сервису сам.

**В игре** — без правки файлов (владелец сервера; в одиночке — с читами). На хостинге вводи в консоли сервера без `/`:
```
/nullAI key claude <ключ>         /nullAI key grok <ключ>
/nullAI custom openrouter         /nullAI key custom <ключ>
/nullAI model claude claude-haiku-5-5        ← выбрать модель (у любого из трёх)
/nullAI brain grok    /nullAI speech claude  ← какой ИИ решает, какой говорит
/nullAI                                       ← текущие настройки, кнопки в чате
```
Наборы для `/nullAI custom`: `openai`, `openrouter`, `gemini`, `deepseek`, `mistral`, `groq`, `ollama`, `lmstudio`.

**В конфиге** — вставь ключ после `=`, потом `/null reload`:
```
apiKey=sk-ant-...          model=claude-sonnet-5-5
grokApiKey=xai-...         grokModel=grok-4
customUrl=https://openrouter.ai/api/v1
customApiKey=sk-or-...     customModel=google/gemini-2.5-flash
```
Ключ нигде не показывается целиком и не пишется в лог. Жёсткие лимиты (`maxAiCallsPerHour`, `maxAiCallsPerDay`) держат расходы под контролем. **Никому не показывай конфиг и скриншоты с ключом.**

### Команды админа
`/null status | on | off | reload | scare <ник> | mimic <ник> | why <ник>` · `/nullAI` · `/tbsecho`

---
*Not affiliated with the authors of The Broken Script. Contains none of its code or assets.*
*Не связан с авторами The Broken Script. Не содержит их кода и ресурсов.*
