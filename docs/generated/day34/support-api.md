# Серверное HTTP API поддержки TalkLoop

## Маршруты
- **GET `/api/support/catalog`** — возвращает список демо-пользователей и всех демо-тикетов.
- **POST `/api/support/ask`** — отправляет запрос на получение ответа поддержки. Требует заголовка `Content-Type: application/json`. Отклоняет запросы с заголовком `Origin`.

## Поля запроса (POST `/api/support/ask`)
```jsonc
{
  "userId": "U1001",      // Строка, формат U[0-9]{4}
  "ticketId": "T1001",    // Строка, формат T[0-9]{4}
  "question": "Текст вопроса", // Строка, макс. 1500 символов
  "history": [            // Массив объектов (опционально)
    {"fromUser": true, "text": "Предыдущий вопрос"}  // текст макс. 1000 символов
  ]
}
```
Ограничение: общий размер тела запроса не более 12 000 символов. История содержит не более четырёх сообщений.

## Поля ответа (схематично, HTTP 200)
```text
{
  "answer": "Текст ответа поддержки", // Строка, макс. 6000 символов
  "disposition": "answered" | "needs_info" | "escalate",
  "context": {
    "user": {"id": "U1001", "name": "Имя", "plan": "Basic", "language": "ru"},
    "ticket": {"id": "T1001", "userId": "U1001", "subject": "Тема", "status": "open", "platform": "Android", "appVersion": "1.1.0", "issueCode": "EMAIL_NOT_VERIFIED", "facts": {...}, "notes": ""}
  },
  "sources": [
    {"number": 1, "file": "docs/support/faq.md", "section": "Вход", "text": "..."}
  ],
  "usedSources": [1],
  "mcpTool": "support_ticket_context"
}
```

## Ошибки
| Код | Описание |
|-----|----------|
| **400 Bad Request** | Неверный формат JSON, пустой вопрос, нарушение формата ID (userId/ticketId), история слишком длинная. |
| **409 Conflict** | Поддержка уже обрабатывает запрос (SupportBusy). Повторите позже. |
| **404 Not Found** | Тикет недоступен или не найден в MCP (SupportTicketUnavailable). |
| **503 Service Unavailable** | Ошибка сервера, проблемы с MCP/Ollama или индексом FAQ. |
| **504 Gateway Timeout** | Превышено время ожидания MCP или ответа модели. |

## Примечания
- API работает только с демо-данными (файл `tools/day33/support-demo.json`).
- До LLM проверяются формат ID клиента/тикета, длина вопроса и размер истории. Выбор демо-клиента не является аутентификацией.
- Ответы содержат ссылки на источники из базы знаний (`docs/support/*.md`).

Сгенерировано локальной Ollama; формулировки уточнены Codex по исходникам при проверке diff. Для HTTP отправляйте обычный JSON без комментариев.

<!-- talkloop-file-agent-sources -->
## Прочитанные файлы

Проверялись разрешённые текстовые файлы Git-проекта размером до 80 КБ. Секреты, бинарные файлы и каталоги сборки исключены. Файлы ниже могли читаться фрагментами.

- `core/src/commonMain/kotlin/com/mau89/talkloop/llm/SupportAssistant.kt` · SHA-256 `b1484aba7830`
- `server/src/main/kotlin/com/mau89/talkloop/SupportApi.kt` · SHA-256 `8527b7eb882b`
- `server/src/test/kotlin/com/mau89/talkloop/SupportServiceTest.kt` · SHA-256 `6d04b0052f5a`

## Фактические совпадения поиска

Запрос `supportRoutes`: 4 совпадений.

- `server/src/main/kotlin/com/mau89/talkloop/Application.kt:102` — `        supportRoutes(support)`
- `server/src/main/kotlin/com/mau89/talkloop/SupportApi.kt:73` — `fun Route.supportRoutes(service: SupportService) {`
- `server/src/test/kotlin/com/mau89/talkloop/SupportServiceTest.kt:72` — `        application { routing { supportRoutes(service) } }`
- `server/src/test/kotlin/com/mau89/talkloop/SupportServiceTest.kt:91` — `        application { routing { supportRoutes(service) } }`
Запрос `SupportRequest`: 10 совпадений.

- `core/src/commonMain/kotlin/com/mau89/talkloop/llm/SupportAssistant.kt:26` — `data class SupportRequest(`
- `core/src/commonMain/kotlin/com/mau89/talkloop/llm/SupportAssistant.kt:57` — `    suspend fun answer(request: SupportRequest): SupportReply {`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt:40` — `        val first = agent.answer(SupportRequest("U1001", "T1001", "Почему не работает авторизация?"))`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt:43` — `        val second = agent.answer(SupportRequest("U1001", "T1001", "Что дальше?", history))`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt:58` — `        assertFailsWith<IllegalArgumentException> { agent.answer(SupportRequest("U1001", "T1001", "Вход")) }`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt:59` — `        assertFailsWith<IllegalArgumentException> { agent.answer(SupportRequest("../secrets", "T1001", "Вход")) }`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt:67` — `        assertEquals("needs_info", agent.answer(SupportRequest("U1001", "T1001", "Вопрос")).disposition)`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt:69` — `        assertFailsWith<IllegalArgumentException> { cookbook.answer(SupportRequest("U1001", "T1001", "Вопрос")) }`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt:84` — `            assertFailsWith<IllegalStateException>(bad) { agent.answer(SupportRequest("U1001", "T1001", "Вопрос")) }`
- `core/src/commonTest/kotlin/com/mau89/talkloop/llm/SupportAssistantTest.kt:87` — `        assertFailsWith<IllegalStateException> { agent.answer(SupportRequest("U1001", "T1001", "Вопрос")) }`
