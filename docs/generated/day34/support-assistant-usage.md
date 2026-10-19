# Использования SupportAssistant в TalkLoop

## Обзор
Компонент `SupportAssistant` обрабатывает запросы пользователей в рамках конкретного тикета, используя контекст CRM и поиск по базе знаний (`docs/support/*.md`).

## Реализация
- **Файл**: `core/src/commonMain/kotlin/com/mau89/talkloop/llm/SupportAssistant.kt`
- **Класс**: `SupportAssistant` (строка 52)
- **Метод**: `answer(request: SupportRequest): SupportReply` (строка 57)

### Логика работы
1. Валидация запроса (строки 30–34): проверка формата ID (`U####`, `T####`), длины вопроса и истории.
2. Получение контекста тикета через `SupportContextGateway` (строка 59). Проверка целостности данных (строки 60–61).
3. Поиск документов в базе знаний (строка 64). Валидация путей к файлам (строки 67–69).
4. Генерация ответа через LLM с использованием найденных источников (строки 75–83).
5. Парсинг и валидация JSON-ответа модели (строки 85–101). Проверка соответствия номеров источников тексту.

## Тесты
- **Файл**: `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt`
- **Класс**: `SupportAssistantTest` (строка 7)

### Ключевые сценарии
- Обновление тикета при каждом обращении и использование только текущего вопроса (строки 32–51).
- Отказ при невалидных ID или контексте чужого тикета (строки 54–61).
- Воздержание от ответа при отсутствии доказательств и отказ от документов из других корпусов (строки 63–71).
- Отклонение неверной схемы ответа, выдуманных источников и незавершённого ответа (строки 73–88).

## API Сервиса
- **Файл**: `server/src/main/kotlin/com/mau89/talkloop/SupportApi.kt`
- **Класс**: `LocalSupportService` (строка 50), метод `answer` (строка 61).
- **Маршрут**: `POST /api/support/ask` (строка 83)

### Запрос (`SupportRequest`)
- `userId`: String (демо: `U1001`, `U1002`...)
- `ticketId`: String (демо: `T1001`, `T1002`...)
- `question`: String (макс. 1500 символов)
- `history`: List<ChatMessage> (макс. 4 сообщения, каждое до 1000 символов)

### Ответ (`SupportReply`)
- `answer`: String (текст ответа)
- `disposition`: String (`answered`, `needs_info`, `escalate`)
- `context`: SupportContext (текущий пользователь и тикет)
- `sources`: List<SupportCitation> (ссылки на файлы базы знаний)
- `usedSources`: List<Int> (номера использованных источников)

### Ошибки
- `400 Bad Request`: Неправильный формат JSON или валидация входных данных.
- `404 Not Found`: Тикет не найден (`SupportTicketUnavailable`).
- `409 Conflict`: Поддержка занята (`SupportBusy`).
- `503 Service Unavailable`: Ошибка сервиса.
- `504 Gateway Timeout`: Превышено время ожидания MCP или модели.

## База знаний
Документы должны находиться в директории `docs/support/` с расширением `.md`. Поиск возвращает только структурные хиты из этой папки (строки 67–69).

Отчёт получен от локальной Ollama; название и формулировки тестов/ошибок уточнены Codex по исходникам. Совпадения ниже относятся к состоянию проекта на момент запуска.

<!-- talkloop-file-agent-sources -->
## Прочитанные файлы

Проверялись разрешённые текстовые файлы Git-проекта размером до 80 КБ. Секреты, бинарные файлы и каталоги сборки исключены. Файлы ниже могли читаться фрагментами.

- `core/src/commonMain/kotlin/com/mau89/talkloop/llm/SupportAssistant.kt` · SHA-256 `b1484aba7830`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt` · SHA-256 `6d8be2f52f22`
- `server/src/main/kotlin/com/mau89/talkloop/SupportApi.kt` · SHA-256 `8527b7eb882b`

## Фактические совпадения поиска

Запрос `SupportAssistant`: 12 совпадений.

- `app/shared/src/commonMain/kotlin/com/mau89/talkloop/FileTaskScreen.kt:17` — `private const val USAGE_GOAL = "Найди все места использования SupportAssistant, прочитай реализацию, вызовы и тесты, под`
- `core/src/commonMain/kotlin/com/mau89/talkloop/llm/SupportAssistant.kt:52` — `class SupportAssistant(`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt:7` — `class SupportAssistantTest {`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt:36` — `        val agent = SupportAssistant(llm, SupportContextGateway { user, ticket ->`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt:56` — `        val agent = SupportAssistant(llm, SupportContextGateway { _, _ -> context().copy(ticket = context().ticket.copy(`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt:66` — `        val agent = SupportAssistant(llm, crm, DocumentRetriever { found(it, empty = true) })`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt:68` — `        val cookbook = SupportAssistant(llm, crm, DocumentRetriever { found(it, file = "cookbook/recipe.md") })`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt:75` — `        val agent = SupportAssistant(llm, SupportContextGateway { _, _ -> context() }, DocumentRetriever { found(it) })`
- `server/src/main/kotlin/com/mau89/talkloop/SupportApi.kt:66` — `                SupportAssistant(llm, crm, DocumentRetriever { index.search(it) }).answer(request)`
- `docs/day34-file-assistant.md:11` — `**«Выполнить задачу»**. Цель — найти использования 'SupportAssistant', прочитать`
- `docs/day34-file-assistant.md:64` — `python3 tools/day34/file_agent.py --goal 'Найди использования SupportAssistant и подготовь отчёт с путями и строками' --`
- `docs/project-architecture.md:78` — `'SupportAssistant' в 'core' обращается к локальной Ollama. 'SupportScreen' отображает`

Пропущены недоступные или большие файлы: docs/day25-rag-chat.md.
