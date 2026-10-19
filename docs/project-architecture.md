# Структура и API TalkLoop

## Модули проекта

TalkLoop — Kotlin Multiplatform проект с Android, iOS, сервером и CLI.

- `core` — общая логика на Kotlin: `TalkLoopAgent`, `AgentRuntime`, клиенты
  Claude и Ollama, история диалога, стратегии контекста, память, инварианты,
  состояния задач и контракты RAG. Код: `core/src/commonMain/kotlin/com/mau89/talkloop/llm`.
- `app/shared` — общий интерфейс Compose Multiplatform: `App.kt`, `ChatScreen.kt`,
  настройки подключения LLM, экран поиска документов, MCP-клиенты.
  Платформенные каталоги `androidMain` и `iosMain` содержат хранение истории,
  настроек и сетевые движки.
- `app/androidApp` — точка входа Android; сборка `:app:androidApp:assembleDebug`.
- `app/iosApp` — точка входа iOS, проект Xcode.
- `server` — Ktor на JVM, HTTP API поиска документов и MCP-серверы.
- `cli` — воспроизводимые сценарии учебных дней, включая `:cli:day31`.
- `tools/day21` — индексация кулинарной книги: Python, ONNX, multilingual E5.
- `tools/day31` — отдельная индексация README и Markdown-документов из `docs`.
- `tools/day33` — отдельная индексация `docs/support` и JSON с демо-клиентами/тикетами.
- `tools/day34` — цикл файлового ассистента: инструменты, журнал, diff, применение и повтор.
- `docs` — документация учебных дней, описание структуры и API, результаты проверок.

## Как запрос проходит через агента

`App.kt` создаёт `TalkLoopAgent` с `AgentKnowledgeTools`. Ввод из `ChatScreen.kt`
попадает в `TalkLoopAgent.respond`. Агент проверяет состояние задачи и инварианты,
получает контекст инструментов, вызывает выбранную LLM и сохраняет пару реплик.
`AgentKnowledgeTools` сначала проверяет `/help`, затем MCP-команды погоды/книг,
затем кулинарный RAG, если он включён. `/help` работает независимо от переключателя
кулинарной книги, не использует её индекс или память задачи.

`ProjectHelpTools` получает пять фрагментов документации и текущую git-ветку.
`systemPromptWithToolCall` добавляет специальную инструкцию ассистента разработчика
и JSON-контекст в системный промпт. Ответ содержит ссылки [1], [2] на документы.
Номера соответствуют карточкам источников в подробностях последнего вызова.
Проверка цитат кулинарной книги к ответам `/help` не применяется.

## Запуск

Из корня проекта: `./gradlew :server:run` запускает основной сервер на порту 8080
и MCP-серверы книг на порту 8081, проекта на порту 8082 и поддержки на `127.0.0.1:8083`.
Android Emulator обращается к Mac по адресу
`http://10.0.2.2:8080`, iOS Simulator — `http://127.0.0.1:8080`.
Адрес поиска документов задаётся в настройках агента; `/help` использует этот же
адрес для документации; проектный MCP доступен на том же хосте, порт 8082.

Первичная подготовка E5: `tools/day21/run.sh prepare`.
Индекс проекта: `.day21-venv/bin/python tools/day31/index_project.py`.
После изменения README или docs пересоберите индекс и перезапустите сервер.
`./gradlew :cli:day31 --args='Как устроен проект TalkLoop?'` выполняет `/help`
через локальную Ollama. По умолчанию используется `qwen3.5:9b`.

## HTTP API

Основной сервер, порт 8080:

- `GET /` — проверка доступности Ktor.
- `GET /api/weather/{city}` — текущая погода.
- `GET /api/documents/status` — сведения об индексе кулинарной книги.
- `POST /api/documents/search` — поиск по кулинарной книге.
- `GET /api/project/documents/status` — сведения об индексе README и docs.
- `POST /api/project/documents/search` — поиск по документации проекта.
- `GET /api/support/catalog` — демо-клиенты и краткие сведения о тикетах.
- `POST /api/support/ask` — ответ по FAQ, текущему контексту MCP и истории тикета.
- `POST /api/file-tasks` — фоновая задача по цели; `GET /api/file-tasks/latest`
  и `GET /api/file-tasks/{id}` — ход работы и diff; `POST /api/file-tasks/{id}/apply` —
  сохранение подготовленного Markdown в `docs/generated/day34`.
- `/mcp` — Streamable HTTP MCP погоды, планировщика и погодных отчётов.

Проектный сервер, порт 8082: `/mcp` — read-only инструмент `git_branch`.

Книжный сервер, порт 8081: `/mcp` — выбор жанра, поиск книг и сведения о книге.

Сервер поддержки, только loopback `127.0.0.1:8083`: `/mcp`, read-only инструмент
`support_ticket_context(userId,ticketId)`. `LocalSupportService` вызывает MCP через SDK,
E5-поиск — через отдельный `LocalDocumentIndexGateway(day33-data)`, затем
`SupportAssistant` в `core` обращается к локальной Ollama. `SupportScreen` отображает
ответ и реальные источники. JSON содержит вымышленные записи; выбор клиента не
является авторизацией. [Запуск, API и ограничения](day33-user-support.md).

## Схемы данных поиска

Общие типы описаны в `core/.../llm/DocumentIndex.kt`.
Запрос `DocumentSearchRequest`: `query` — вопрос (1–2000 символов),
`strategy` — `fixed`, `structural` или `both`, `limit` — от 1 до 10,
необязательный `model` — фильтр исходного корпуса. Для документации фильтр не нужен.

```json
{"query":"Как устроен проект TalkLoop?","strategy":"structural","limit":5}
```

Ответ `DocumentSearchResponse`: `query`, необязательный `model`, `results`,
`searchSeconds`. Каждый элемент `results` содержит `strategy` и `hits`.
`DocumentChunkHit` содержит `chunkId`, `source`, `title`, `file`, `section`,
`anchors`, `models`, `tokenCount`, `text`, `score` (косинусное сходство E5).
Неверный запрос возвращает HTTP 400, недоступный индекс — HTTP 503.

## MCP git_branch

Вход: пустой JSON-объект `{}`. Клиент не выбирает путь или команду.
Сервер читает репозиторий из `talkloop.project.root`; Gradle задаёт корень проекта.
Ответ: `branch` — имя ветки или `null`, `detached` — логическое значение,
`commit` — SHA текущего коммита. При detached HEAD имя ветки отсутствует.
Ошибка git возвращается как MCP `isError`, без выдуманной ветки.

## Индексы и хранение

`day21-data` хранит кулинарный корпус, E5 и два индекса.
`day31-data` хранит отдельный корпус README/docs, индексы `fixed` и `structural`
и ссылку на уже загруженную E5-модель. Сервер запускает для каждого корпуса
свой постоянный Python-worker из `tools/day21/index_documents.py`.
Поиск выполняется локально; ответ генерирует выбранный в приложении Claude или Ollama.
Индексатор читает только `README.md` и `docs/**/*.md`; JSON-отчёты, секреты,
исходный код и каталоги сборки не входят в корпус. Индексы исключены из Git.
