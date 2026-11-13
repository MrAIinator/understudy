# Understudy — publication texts / тексты для публикации

**Name / Название:** Understudy
**Summary (one line, ≤ 100 chars):** Something learns how you play — and starts playing in your place. A The Broken Script add-on.
**Кратко (одна строка):** Что-то изучает, как ты играешь, — и начинает играть вместо тебя. Дополнение к The Broken Script.
**Loader / Загрузчик:** NeoForge 1.21.1 · **Requires / Требует:** The Broken Script 2.0.5+
**Environment / Сторона:** server-side; works in single player, on LAN and on dedicated servers. Clients don't need it to join a server.
**Suggested tags / Теги:** Horror, Adventure, Game Mechanics, Mobs, Server Utility

---

## English

> *An understudy learns every line of the lead — so that one night, nobody notices the swap.*

**Understudy** is an unofficial add-on for **The Broken Script**. It doesn't add new monsters to throw at you. It watches **how you play** and makes the world answer.

### What it does
- **A director that watches.** Every player is observed: digging down alone, standing still too long, building at dusk, just died in this cave. Scares are staged around *what you are doing right now* — and every sound has a source you can find. A door knocks because there is a door. Footsteps come from something that was standing there.
- **Mimics.** Players who are offline "join the game". Sometimes they attack at once. Sometimes they walk around, mine a little, place a torch, ask if you have iron — for a minute or so. Then they stop and stare. In single player they come wearing *your* name.
- **A world that dies.** Over hours of play the world decays in five stages: grass dries, trees go bare, ash, animals freeze. Your builds are never touched.
- **Echoes.** Sometimes a copy of something you built shows up far away. You didn't build it there.
- **Protocol ECHO.** A long ARG-style story: logs left by previous "operators" in hidden caches, ciphers, words you have to type in chat, trials, three endings. It moves slowly on purpose — and slows down further while you stand still.
- **Optional AI.** Bring your own Anthropic (Claude) and/or xAI (Grok) key — paste it with `/nullAI key claude <key>` or `/nullAI key grok <key>`, no file editing needed — and Null starts improvising short, specific lines about what you're doing. Without a key everything still works with hand-written lines. Hard cost limits are built in. `/nullAI` switches which AI decides and which AI speaks.

### Connecting an AI (optional)
Any of these works: **Claude** (Anthropic), **Grok** (xAI), or **any OpenAI-compatible API** — OpenAI, OpenRouter, Google Gemini, DeepSeek, Mistral, Groq, or a free local model in **Ollama / LM Studio** (no key, no internet).
- In game (server owner / single player with cheats): `/nullAI key claude <key>`, `/nullAI key grok <key>`, or `/nullAI custom openrouter` then `/nullAI key custom <key>`.
- Or in `config/understudy/config.properties`. A step-by-step guide in English and Russian is created next to it: `config/understudy/AI-SETUP.txt`.
- Keys are never shown in full and never written to the log. Hard hourly/daily request limits keep costs under control.

### Languages
English and Russian, chosen per player by game language.

### Configuration
`config/understudy/config.properties` is created on first launch. Every important option has a comment in English and Russian. Notable switches:
- `allowWorld` / `allowHard` / `allowExtreme` — how far Null may go (extreme = irreversible, off by default)
- `decayEnabled`, `echoEnabled`, `protocolEnabled`, `mimicSelfFallback`
- `oldCombat`, `noSweepAttack` — optional pre-1.9 combat, off by default
- `webArchiveEnabled` — optional story pages in a browser; **off by default, no ports are opened unless you enable it**

Admin commands (op): `/null status | on | off | reload | scare <player> | mimic <player> | why <player>`, `/nullAI`, `/nullAI key claude|grok <key|clear>` (owner only; on a hosted server prefer the server console), `/tbsecho`.

### Notes
- Not affiliated with the authors of The Broken Script. This add-on contains none of its code or assets and requires it to be installed.
- No data leaves your computer or server unless you add an AI key yourself.

---

## Русский

> *Дублёр учит каждую реплику главного актёра — чтобы однажды ночью подмены никто не заметил.*

**Understudy** («Дублёр») — неофициальное дополнение к **The Broken Script**. Оно не добавляет новых монстров. Оно следит за тем, **как ты играешь**, — и мир начинает отвечать.

### Что внутри
- **Режиссёр, который смотрит.** За каждым игроком наблюдают: копает вниз один, слишком долго стоит на месте, строит в сумерках, только что умер в этой пещере. Испуги строятся вокруг того, *что ты делаешь прямо сейчас*, — и у каждого звука есть источник, который можно найти. Стук — потому что рядом есть дверь. Шаги — от того, что там стояло.
- **Мимики.** Игроки, которых нет в сети, «заходят в игру». Иногда сразу нападают. Иногда минуту-другую ходят рядом, копают, ставят факел, спрашивают, есть ли у тебя железо. Потом замирают и смотрят. В одиночной игре они приходят с *твоим* ником.
- **Мир умирает.** За часы игры мир проходит пять стадий: трава сохнет, деревья лысеют, пепел, звери замирают. Твои постройки не трогаются.
- **Эхо.** Иногда копия того, что ты построил, появляется где-то далеко. Ты её там не строил.
- **«Протокол ЭХО».** Долгий сюжет в духе ARG: записи прошлых «операторов» в тайниках, шифры, слова, которые нужно написать в чат, испытания, три концовки. Он нарочно идёт медленно — а пока ты стоишь на месте, ещё медленнее.
- **ИИ по желанию.** Вставь свой ключ Anthropic (Claude) и/или xAI (Grok) командой `/nullAI key claude <ключ>` или `/nullAI key grok <ключ>` — без правки файлов — и Null начнёт импровизировать короткие точные реплики о том, что ты делаешь. Без ключа всё работает на готовых фразах. Лимиты расходов встроены. `/nullAI` переключает, какой ИИ решает, а какой говорит.

### Подключение ИИ (по желанию)
Подходит любой: **Claude** (Anthropic), **Grok** (xAI) или **любой OpenAI-совместимый API** — OpenAI, OpenRouter, Google Gemini, DeepSeek, Mistral, Groq или бесплатная локальная модель в **Ollama / LM Studio** (без ключа и без интернета).
- В игре (владелец сервера / одиночка с читами): `/nullAI key claude <ключ>`, `/nullAI key grok <ключ>` или `/nullAI custom openrouter`, затем `/nullAI key custom <ключ>`.
- Или в `config/understudy/config.properties`. Рядом создаётся пошаговая инструкция на русском и английском: `config/understudy/AI-SETUP.txt`.
- Ключ нигде не показывается целиком и не пишется в лог. Жёсткие лимиты запросов в час и в сутки держат расходы под контролем.

### Языки
Русский и английский — по языку игры каждого игрока.

### Настройки
`config/understudy/config.properties` создаётся при первом запуске, у каждой важной настройки есть комментарий на английском и русском. Главное:
- `allowWorld` / `allowHard` / `allowExtreme` — насколько далеко заходит Null (extreme — необратимо, по умолчанию выключено)
- `decayEnabled`, `echoEnabled`, `protocolEnabled`, `mimicSelfFallback`
- `oldCombat`, `noSweepAttack` — старый бой как до 1.9, по умолчанию выключен
- `webArchiveEnabled` — страницы сюжета в браузере; **по умолчанию выключено, пока не включишь — мод не открывает никаких портов**

Команды админа (оп): `/null status | on | off | reload | scare <ник> | mimic <ник> | why <ник>`, `/nullAI`, `/nullAI key claude|grok <ключ|clear>` (только владелец; на хостинге лучше вводить в консоли сервера), `/tbsecho`.

### Важно
- Не связан с авторами The Broken Script. Мод не содержит их кода и ресурсов и требует установленный The Broken Script.
- Никакие данные не уходят с твоего компьютера или сервера, пока ты сам не впишешь ключ ИИ.
