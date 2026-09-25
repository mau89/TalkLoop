package com.mau89.talkloop.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

val GENERAL_AGENT_SYSTEM_PROMPT = """
    Ты универсальный помощник TalkLoop.
    По умолчанию отвечай на русском языке.
    Пользователь может писать на любом языке: понимай его запрос без ограничений.
    Если пользователь явно просит ответить на другом языке или сам ведёт разговор
    на другом языке, отвечай на выбранном им языке.
    Давай прямые, полезные и понятные ответы.
""".trimIndent()

data class AgentStatistics(
    val requestCount: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val turns: List<AgentTurnUsage> = emptyList(),
    val compression: ContextCompressionStatistics = ContextCompressionStatistics(),
    val facts: FactsUpdateStatistics = FactsUpdateStatistics(),
) {
    val totalTokens: Int get() = inputTokens + outputTokens
    val totalCostUsd: Double get() = turns.sumOf(AgentTurnUsage::costUsd)
    val lastTurn: AgentTurnUsage? get() = turns.lastOrNull()
    val allInputTokens: Int
        get() = inputTokens + compression.summaryInputTokens + facts.inputTokens
    val allOutputTokens: Int
        get() = outputTokens + compression.summaryOutputTokens + facts.outputTokens
    val allTokens: Int get() = allInputTokens + allOutputTokens
    val allCostUsd: Double
        get() = totalCostUsd + compression.summaryCostUsd + facts.costUsd
}

/**
 * Диалоговый агент.
 *
 * UI знает только о пользовательском запросе и готовом тексте ответа. Агент
 * самостоятельно хранит контекст диалога, формирует историю для LLM и фиксирует
 * новую пару реплик только после успешного ответа API.
 */
class TalkLoopAgent(
    private val llmClient: LlmClient,
    private val config: AgentConfig = AgentConfig(systemPrompt = GENERAL_AGENT_SYSTEM_PROMPT),
    initialHistory: List<ChatMessage> = emptyList(),
    private val historyStore: ChatHistoryStore = InMemoryChatHistoryStore(initialHistory),
    private val invariantStore: InvariantStore = EmptyInvariantStore,
    private val invariantGuard: InvariantGuard = MarkerInvariantGuard,
    private val toolProvider: AgentToolProvider? = null,
) {
    private val mutex = Mutex()
    private val restoredMemory = historyStore.loadMemory()
    private val restoredMessages = if (config.contextStrategy is ContextStrategy.MemoryLayers) {
        restoredMemory.layers.shortTerm.messages.ifEmpty {
            restoredMemory.messages.ifEmpty { initialHistory.toList() }
        }
    } else {
        restoredMemory.messages.ifEmpty { initialHistory.toList() }
    }
    private val initialBranches = if (config.contextStrategy == ContextStrategy.Branching) {
        restoredMemory.branches.ifEmpty {
            listOf(DialogueBranch(MAIN_BRANCH_ID, "Основная", messages = restoredMessages))
        }
    } else {
        emptyList()
    }
    private val initialActiveBranchId = restoredMemory.activeBranchId
        ?.takeIf { id -> initialBranches.any { it.id == id } }
        ?: initialBranches.firstOrNull()?.id
    private val initialActiveMessages = initialBranches
        .firstOrNull { it.id == initialActiveBranchId }
        ?.messages
        ?: restoredMessages
    private val mutableHistory = MutableStateFlow(initialActiveMessages)
    private val mutableSummary = MutableStateFlow(
        restoredMemory.summary.takeIf {
            config.contextStrategy == ContextStrategy.FullHistory &&
                config.contextCompression.enabled
        }
    )
    private val mutableFacts = MutableStateFlow(
        restoredMemory.facts.takeIf { config.contextStrategy is ContextStrategy.StickyFacts }
            .orEmpty()
    )
    private val mutableBranches = MutableStateFlow(initialBranches)
    private val mutableCheckpoints = MutableStateFlow(
        restoredMemory.checkpoints.takeIf { config.contextStrategy == ContextStrategy.Branching }
            .orEmpty()
    )
    private val mutableActiveBranchId = MutableStateFlow(initialActiveBranchId)
    private val mutableWorkingMemory = MutableStateFlow(
        restoredMemory.layers.working.takeIf {
            config.contextStrategy is ContextStrategy.MemoryLayers
        } ?: WorkingMemory()
    )
    private val mutableLongTermMemory = MutableStateFlow(
        restoredMemory.layers.longTerm.takeIf {
            config.contextStrategy is ContextStrategy.MemoryLayers
        } ?: LongTermMemory()
    )
    private val restoredUserProfiles = restoredMemory.userProfiles.ifEmpty {
        listOfNotNull(restoredMemory.userProfile)
    }.distinctBy(UserProfile::id)
    private val initialActiveUserProfileId = restoredMemory.activeUserProfileId
        ?.takeIf { id -> restoredUserProfiles.any { it.id == id } }
        ?: restoredMemory.userProfile?.id
            ?.takeIf { id -> restoredUserProfiles.any { it.id == id } }
    private val mutableUserProfiles = MutableStateFlow(restoredUserProfiles)
    private val mutableActiveUserProfileId = MutableStateFlow(initialActiveUserProfileId)
    private val mutableUserProfile = MutableStateFlow(
        restoredUserProfiles.firstOrNull { it.id == initialActiveUserProfileId }
    )
    private val mutableTaskState = MutableStateFlow(restoredMemory.taskState)
    private val mutableLastInvariantCheck = MutableStateFlow(InvariantCheckResult())
    private val mutableStatistics = MutableStateFlow(AgentStatistics())
    private val mutableLastToolCall = MutableStateFlow<AgentToolCall?>(null)

    val history: StateFlow<List<ChatMessage>> = mutableHistory.asStateFlow()
    val summary: StateFlow<String?> = mutableSummary.asStateFlow()
    val facts: StateFlow<Map<String, String>> = mutableFacts.asStateFlow()
    val branches: StateFlow<List<DialogueBranch>> = mutableBranches.asStateFlow()
    val checkpoints: StateFlow<List<DialogueCheckpoint>> = mutableCheckpoints.asStateFlow()
    val activeBranchId: StateFlow<String?> = mutableActiveBranchId.asStateFlow()
    val workingMemory: StateFlow<WorkingMemory> = mutableWorkingMemory.asStateFlow()
    val longTermMemory: StateFlow<LongTermMemory> = mutableLongTermMemory.asStateFlow()
    val userProfiles: StateFlow<List<UserProfile>> = mutableUserProfiles.asStateFlow()
    val activeUserProfileId: StateFlow<String?> = mutableActiveUserProfileId.asStateFlow()
    val userProfile: StateFlow<UserProfile?> = mutableUserProfile.asStateFlow()
    val taskState: StateFlow<TaskState?> = mutableTaskState.asStateFlow()
    val lastInvariantCheck: StateFlow<InvariantCheckResult> =
        mutableLastInvariantCheck.asStateFlow()
    val statistics: StateFlow<AgentStatistics> = mutableStatistics.asStateFlow()
    val lastToolCall: StateFlow<AgentToolCall?> = mutableLastToolCall.asStateFlow()
    val invariantRules: StateFlow<List<AgentInvariant>> = invariantStore.state

    /** Правила читаются из отдельного хранилища и никогда не смешиваются с репликами. */
    val invariants: List<AgentInvariant> get() = invariantStore.load()

    suspend fun saveInvariant(
        invariant: AgentInvariant,
        previousId: String? = invariant.id,
    ) = mutex.withLock {
        requireMutableInvariantStore().replace(previousId, invariant)
        mutableLastInvariantCheck.value = InvariantCheckResult()
    }

    suspend fun deleteInvariant(id: String) = mutex.withLock {
        requireMutableInvariantStore().remove(id)
        mutableLastInvariantCheck.value = InvariantCheckResult()
    }

    suspend fun resetInvariants() = mutex.withLock {
        requireMutableInvariantStore().reset()
        mutableLastInvariantCheck.value = InvariantCheckResult()
    }

    /** Начинает новую state machine, не затрагивая диалог или слои памяти. */
    suspend fun beginTask(
        name: String,
        currentStep: String = "Сформировать план",
        expectedAction: String = "Определить шаги и критерии готовности",
        expectedActor: TaskActor = TaskActor.AGENT,
        completionCriteria: List<String> = emptyList(),
    ) = mutex.withLock {
        mutableTaskState.value?.let { current ->
            require(current.stage == TaskStage.DONE) {
                "Нельзя заменить активную задачу «${current.taskName}» на этапе " +
                    "${current.stage.name.lowercase()}. Сначала завершите её или явно начните новую задачу."
            }
        }
        mutableTaskState.value = newTaskState(
            name = name,
            currentStep = currentStep,
            expectedAction = expectedAction,
            expectedActor = expectedActor,
            completionCriteria = completionCriteria,
        )
        persistWithCurrentLayers()
    }

    /** Применяет событие атомарно, с проверкой ревизии и защитой от повторов. */
    suspend fun dispatchTaskEvent(event: TaskEvent) = mutex.withLock {
        mutableTaskState.value = requireTaskState().applyEvent(event)
        persistWithCurrentLayers()
    }

    /** Обновляет точку работы, не меняя этап конечного автомата. */
    suspend fun updateTaskProgress(currentStep: String, expectedAction: String) = mutex.withLock {
        val current = requireTaskState()
        mutableTaskState.value = current.applyEvent(
            TaskEvent(
                id = "manual-${current.revision + 1}",
                type = TaskEventType.PROGRESS_UPDATED,
                currentStep = currentStep,
                expectedAction = expectedAction,
                expectedRevision = current.revision,
            )
        )
        persistWithCurrentLayers()
    }

    /** Выполняет только разрешённый переход конечного автомата. */
    suspend fun transitionTask(
        nextStage: TaskStage,
        currentStep: String,
        expectedAction: String,
    ) = mutex.withLock {
        val current = requireTaskState()
        val eventType = when (current.stage to nextStage) {
            TaskStage.PLANNING to TaskStage.EXECUTION -> TaskEventType.PLAN_APPROVED
            TaskStage.EXECUTION to TaskStage.VALIDATION -> TaskEventType.EXECUTION_FINISHED
            TaskStage.VALIDATION to TaskStage.DONE -> TaskEventType.VALIDATION_PASSED
            TaskStage.VALIDATION to TaskStage.EXECUTION -> TaskEventType.VALIDATION_FAILED
            else -> throw IllegalArgumentException(
                "Недопустимый переход: ${current.stage.name.lowercase()} → " +
                    nextStage.name.lowercase()
            )
        }
        mutableTaskState.value = current.applyEvent(
            TaskEvent(
                id = "manual-${current.revision + 1}",
                type = eventType,
                currentStep = currentStep,
                expectedAction = expectedAction,
                expectedRevision = current.revision,
            )
        )
        persistWithCurrentLayers()
    }

    /** Пауза сохраняет этап, шаг и следующее действие без изменений. */
    suspend fun pauseTask() = mutex.withLock {
        val current = requireTaskState()
        mutableTaskState.value = current.applyEvent(
            TaskEvent(
                id = "pause-${current.revision + 1}",
                type = TaskEventType.PAUSED,
                expectedRevision = current.revision,
            )
        )
        persistWithCurrentLayers()
    }

    /** Продолжение снимает только паузу: повторно объяснять контекст не требуется. */
    suspend fun resumeTask() = mutex.withLock {
        val current = requireTaskState()
        mutableTaskState.value = current.applyEvent(
            TaskEvent(
                id = "resume-${current.revision + 1}",
                type = TaskEventType.RESUMED,
                expectedRevision = current.revision,
            )
        )
        persistWithCurrentLayers()
    }

    /** Просит модель оценить критерии; состояние этапа меняется только после подтверждения. */
    suspend fun requestTaskTransitionProposal(): TaskTransitionProposal = mutex.withLock {
        val state = requireTaskState()
        check(!state.paused) { "Сначала продолжите задачу после паузы" }
        check(state.stage != TaskStage.DONE) { "Задача уже завершена" }
        check(state.pendingProposal == null) {
            "Сначала подтвердите или отклоните текущее предложение"
        }
        val answer = llmClient.answer(
            history = mutableHistory.value + ChatMessage(
                fromUser = true,
                text = "Оцени готовность перейти на следующий этап по критериям задачи.",
            ),
            spec = ResponseSpec(
                system = systemPromptWithTaskState(
                    systemPrompt = systemPromptWithInvariants(
                        systemPrompt = taskTransitionReviewPrompt(state),
                        invariants = invariantStore.load(),
                    ),
                    taskState = state,
                ),
                maxTokens = 600,
                jsonSchema = TASK_TRANSITION_PROPOSAL_SCHEMA,
                temperature = 0.0,
                model = config.model,
            ),
        )
        val proposal = parseTaskTransitionProposal(answer.text, state)
        mutableTaskState.value = state.copy(pendingProposal = proposal)
        persistWithCurrentLayers()
        proposal
    }

    suspend fun confirmTaskTransitionProposal() = mutex.withLock {
        val current = requireTaskState()
        val proposal = current.pendingProposal
            ?: throw IllegalStateException("Нет предложения для подтверждения")
        val event = proposal.event
            ?: throw IllegalStateException("Агент рекомендует остаться на текущем этапе")
        mutableTaskState.value = current.applyEvent(event)
        persistWithCurrentLayers()
    }

    suspend fun dismissTaskTransitionProposal() = mutex.withLock {
        val current = requireTaskState()
        check(current.pendingProposal != null) { "Нет предложения для отклонения" }
        mutableTaskState.value = current.copy(pendingProposal = null)
        persistWithCurrentLayers()
    }

    /** Создаёт или обновляет профиль по ID и сразу делает его активным. */
    suspend fun setUserProfile(profile: UserProfile) = mutex.withLock {
        val normalized = normalizeUserProfile(profile)
        mutableUserProfiles.value = mutableUserProfiles.value.upsert(normalized) {
            it.id == normalized.id
        }
        mutableActiveUserProfileId.value = normalized.id
        mutableUserProfile.value = normalized
        persistWithCurrentLayers()
    }

    /** Переключает профиль; null включает режим без персонализации. */
    suspend fun selectUserProfile(profileId: String?) = mutex.withLock {
        val normalizedId = profileId?.trim()?.takeIf(String::isNotEmpty)
        val selected = normalizedId?.let { id ->
            mutableUserProfiles.value.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Профиль не найден: $id")
        }
        mutableActiveUserProfileId.value = selected?.id
        mutableUserProfile.value = selected
        persistWithCurrentLayers()
    }

    /** Отключает персонализацию, сохраняя профили для последующего выбора. */
    suspend fun clearUserProfile() = mutex.withLock {
        mutableActiveUserProfileId.value = null
        mutableUserProfile.value = null
        persistWithCurrentLayers()
    }

    /** Удаляет один сохранённый профиль, не затрагивая диалог и память задачи. */
    suspend fun deleteUserProfile(profileId: String) = mutex.withLock {
        val normalizedId = profileId.trim().takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("ID профиля не должен быть пустым")
        val updated = mutableUserProfiles.value.filterNot { it.id == normalizedId }
        require(updated.size != mutableUserProfiles.value.size) {
            "Профиль не найден: $normalizedId"
        }
        mutableUserProfiles.value = updated
        if (mutableActiveUserProfileId.value == normalizedId) {
            mutableActiveUserProfileId.value = null
            mutableUserProfile.value = null
        }
        persistWithCurrentLayers()
    }

    /**
     * Явная запись в выбранный вызывающим кодом слой. Одинаковый ключ заменяет
     * прежнее значение только внутри своего слоя (и категории для long-term).
     */
    suspend fun remember(write: MemoryWrite) = mutex.withLock {
        requireMemoryLayers()
        when (write) {
            is MemoryWrite.Working -> {
                val item = MemoryItem(
                    key = requireMemoryText(write.key, "Ключ"),
                    value = requireMemoryText(write.value, "Значение"),
                )
                mutableWorkingMemory.value = mutableWorkingMemory.value.copy(
                    items = mutableWorkingMemory.value.items.upsert(item) { it.key == item.key },
                )
            }
            is MemoryWrite.LongTerm -> {
                val item = LongTermMemoryItem(
                    kind = write.kind,
                    key = requireMemoryText(write.key, "Ключ"),
                    value = requireMemoryText(write.value, "Значение"),
                )
                mutableLongTermMemory.value = mutableLongTermMemory.value.copy(
                    items = mutableLongTermMemory.value.items.upsert(item) {
                        it.kind == item.kind && it.key == item.key
                    },
                )
            }
        }
        persistLayeredMemory()
    }

    /** Удаление так же требует назвать слой; совпадающий ключ в другом слое не затрагивается. */
    suspend fun forget(
        layer: MemoryLayer,
        key: String,
        longTermKind: LongTermMemoryKind? = null,
    ) = mutex.withLock {
        requireMemoryLayers()
        val normalizedKey = requireMemoryText(key, "Ключ")
        when (layer) {
            MemoryLayer.WORKING -> mutableWorkingMemory.value = mutableWorkingMemory.value.copy(
                items = mutableWorkingMemory.value.items.filterNot { it.key == normalizedKey },
            )
            MemoryLayer.LONG_TERM -> {
                requireNotNull(longTermKind) {
                    "Для удаления из долговременной памяти укажите категорию"
                }
                mutableLongTermMemory.value = mutableLongTermMemory.value.copy(
                    items = mutableLongTermMemory.value.items.filterNot {
                        it.kind == longTermKind && it.key == normalizedKey
                    },
                )
            }
        }
        persistLayeredMemory()
    }

    /** Новая задача начинает чистый диалог и рабочий слой, сохраняя long-term. */
    suspend fun startNewTask(name: String? = null) = mutex.withLock {
        requireMemoryLayers()
        val normalizedName = name?.trim()?.takeIf(String::isNotEmpty)
        mutableHistory.value = emptyList()
        mutableWorkingMemory.value = WorkingMemory(taskName = normalizedName)
        mutableTaskState.value = normalizedName?.let {
            newTaskState(
                name = it,
                currentStep = "Сформировать план",
                expectedAction = "Определить шаги и критерии готовности",
                expectedActor = TaskActor.AGENT,
                completionCriteria = emptyList(),
            )
        }
        persistLayeredMemory()
    }

    suspend fun createCheckpoint(name: String): DialogueCheckpoint = mutex.withLock {
        requireBranching()
        val checkpoint = DialogueCheckpoint(
            id = nextId("checkpoint", mutableCheckpoints.value.map { it.id }),
            name = name.trim().ifEmpty { "Checkpoint ${mutableCheckpoints.value.size + 1}" },
            messages = mutableHistory.value,
        )
        val updated = mutableCheckpoints.value + checkpoint
        persistMemory(checkpoints = updated)
        mutableCheckpoints.value = updated
        checkpoint
    }

    suspend fun createBranch(name: String, checkpointId: String): DialogueBranch = mutex.withLock {
        requireBranching()
        val checkpoint = mutableCheckpoints.value.firstOrNull { it.id == checkpointId }
            ?: throw IllegalArgumentException("Checkpoint не найден: $checkpointId")
        val branch = DialogueBranch(
            id = nextId("branch", mutableBranches.value.map { it.id }),
            name = name.trim().ifEmpty { "Ветка ${mutableBranches.value.size + 1}" },
            checkpointId = checkpoint.id,
            messages = checkpoint.messages,
        )
        val updated = mutableBranches.value + branch
        persistMemory(branches = updated)
        mutableBranches.value = updated
        branch
    }

    suspend fun switchBranch(branchId: String) = mutex.withLock {
        requireBranching()
        val branch = mutableBranches.value.firstOrNull { it.id == branchId }
            ?: throw IllegalArgumentException("Ветка не найдена: $branchId")
        persistMemory(messages = branch.messages, activeBranchId = branch.id)
        mutableActiveBranchId.value = branch.id
        mutableHistory.value = branch.messages
    }

    suspend fun respond(userRequest: String): String {
        return mutex.withLock {
            mutableLastToolCall.value = null
            mutableTaskState.value?.takeIf(TaskState::paused)?.let { paused ->
                throw TaskPausedException(
                    "Задача «${paused.taskName}» на паузе. Продолжите её перед отправкой сообщения."
                )
            }
            val historySnapshot = mutableHistory.value
            val request = config.inputPolicies.foldSuspend(userRequest) { input, policy ->
                policy.apply(input, InputPolicyContext(historySnapshot))
            }
            val userMessage = ChatMessage(fromUser = true, text = request)
            taskLifecycleRefusal(mutableTaskState.value, request)?.let { refusal ->
                persistLocalTurn(historySnapshot, userMessage, refusal)
                return@withLock refusal
            }
            val invariantSnapshot = invariantStore.load()
            val requestInvariantCheck = invariantGuard.evaluate(
                text = request,
                invariants = invariantSnapshot,
                stage = InvariantCheckStage.REQUEST,
            )
            mutableLastInvariantCheck.value = requestInvariantCheck
            if (!requestInvariantCheck.allowed) {
                val refusal = invariantRefusal(requestInvariantCheck)
                persistLocalTurn(historySnapshot, userMessage, refusal)
                return@withLock refusal
            }
            val toolCall = toolProvider?.callFor(request)
            mutableLastToolCall.value = toolCall
            toolCall?.directResponse?.let { response ->
                persistLocalTurn(historySnapshot, userMessage, response)
                return@withLock response
            }
            val turn = mutableStatistics.value.requestCount + 1
            val summarySnapshot = mutableSummary.value
            mutableStatistics.value = mutableStatistics.value.copy(requestCount = turn)
            val factsSnapshot = mutableFacts.value
            val stagedFacts = when (val strategy = config.contextStrategy) {
                is ContextStrategy.StickyFacts -> updateFacts(
                    strategy = strategy,
                    currentFacts = factsSnapshot,
                    userMessage = request,
                )
                else -> factsSnapshot
            }
            val memoryAwareSystemPrompt = when (config.contextStrategy) {
                ContextStrategy.FullHistory ->
                    systemPromptWithSummary(config.systemPrompt, summarySnapshot)
                is ContextStrategy.StickyFacts ->
                    systemPromptWithFacts(config.systemPrompt, stagedFacts)
                is ContextStrategy.MemoryLayers -> systemPromptWithMemoryLayers(
                    systemPrompt = config.systemPrompt,
                    working = mutableWorkingMemory.value,
                    longTerm = mutableLongTermMemory.value,
                )
                else -> config.systemPrompt
            }
            val personalizedSystemPrompt = systemPromptWithUserProfile(
                systemPrompt = memoryAwareSystemPrompt,
                profile = mutableUserProfile.value,
            )
            val invariantAwareSystemPrompt = systemPromptWithInvariants(
                systemPrompt = personalizedSystemPrompt,
                invariants = invariantSnapshot,
            )
            val conversationSystemPrompt = systemPromptWithTaskState(
                systemPrompt = invariantAwareSystemPrompt,
                taskState = mutableTaskState.value,
            )
            val toolAwareSystemPrompt = systemPromptWithToolCall(
                systemPrompt = conversationSystemPrompt,
                toolCall = toolCall,
            )
            val spec = ResponseSpec(
                system = toolAwareSystemPrompt,
                maxTokens = config.maxTokens,
                stopSequences = config.stopSequences,
                temperature = config.temperature,
                model = config.model,
            )
            // Текущую реплику считаем отдельно, а полный контекст — вместе с system prompt
            // и всей историей. Это две разные метрики из задания, их нельзя подменять
            // длиной строки или делением количества символов на четыре.
            val requestTokens = llmClient.countInputTokens(
                history = listOf(userMessage),
                spec = spec.copy(system = null),
            )
            val inputTokensBeforeSend = llmClient.countInputTokens(
                history = historySnapshot + userMessage,
                spec = spec,
            )
            val pricing = tokenPricingForModel(config.model)

            if (inputTokensBeforeSend > config.contextWindowTokens) {
                val rejected = AgentTurnUsage(
                    turn = turn,
                    requestTokens = requestTokens,
                    inputTokens = inputTokensBeforeSend,
                    outputTokens = 0,
                    contextWindowTokens = config.contextWindowTokens,
                    inputCostUsd = 0.0,
                    outputCostUsd = 0.0,
                    stopReason = null,
                    outcome = TokenTurnOutcome.REJECTED_BEFORE_SEND,
                )
                mutableStatistics.value = mutableStatistics.value.let { current ->
                    current.copy(turns = current.turns + rejected)
                }
                throw ContextWindowExceededException(
                    inputTokens = inputTokensBeforeSend,
                    contextWindowTokens = config.contextWindowTokens,
                )
            }

            val answer = llmClient.answer(
                history = historySnapshot + userMessage,
                spec = spec,
            )
            val (inputCost, outputCost) = estimateTokenCostUsd(
                pricing = pricing,
                inputTokens = answer.inputTokens,
                outputTokens = answer.outputTokens,
                cacheCreationInputTokens = answer.cacheCreationInputTokens,
                cacheReadInputTokens = answer.cacheReadInputTokens,
                cacheCreation5mInputTokens = answer.cacheCreation5mInputTokens,
                cacheCreation1hInputTokens = answer.cacheCreation1hInputTokens,
            )
            val reachedContextLimit = answer.stopReason == "model_context_window_exceeded"
            val turnUsage = AgentTurnUsage(
                turn = turn,
                requestTokens = requestTokens,
                inputTokens = answer.totalInputTokens,
                outputTokens = answer.outputTokens,
                contextWindowTokens = config.contextWindowTokens,
                inputCostUsd = inputCost,
                outputCostUsd = outputCost,
                stopReason = answer.stopReason,
                outcome = if (reachedContextLimit) {
                    TokenTurnOutcome.RESPONSE_REACHED_CONTEXT_LIMIT
                } else {
                    TokenTurnOutcome.COMPLETED
                },
            )
            mutableStatistics.value = mutableStatistics.value.let { current ->
                current.copy(
                    inputTokens = current.inputTokens + answer.totalInputTokens,
                    outputTokens = current.outputTokens + answer.outputTokens,
                    turns = current.turns + turnUsage,
                )
            }
            if (reachedContextLimit) {
                throw ContextWindowExceededException(
                    inputTokens = answer.totalInputTokens + answer.outputTokens,
                    contextWindowTokens = config.contextWindowTokens,
                    message = "Модель упёрлась в окно контекста и оборвала ответ после " +
                        "${answer.outputTokens} токенов. Неполный ответ не сохранён в истории.",
                )
            }
            val candidateResponse = config.outputPolicies.foldSuspend(answer.text) { output, policy ->
                policy.apply(
                    output,
                    OutputPolicyContext(request = request, history = historySnapshot),
                )
            }
            val responseInvariantCheck = invariantGuard.evaluate(
                text = candidateResponse,
                invariants = invariantSnapshot,
                stage = InvariantCheckStage.RESPONSE,
            )
            mutableLastInvariantCheck.value = responseInvariantCheck
            val response = if (responseInvariantCheck.allowed) {
                candidateResponse
            } else {
                invariantRefusal(responseInvariantCheck)
            }
            val verdict = if (responseInvariantCheck.allowed) {
                config.judge?.evaluate(
                    JudgeContext(
                        request = request,
                        response = response,
                        history = historySnapshot,
                    )
                )
            } else {
                null
            }
            if (verdict != null && !verdict.accepted) {
                throw AgentRejectedException(verdict.reason ?: "Judge отклонил ответ модели")
            }

            val completeHistory = historySnapshot + userMessage +
                ChatMessage(fromUser = false, text = response)
            val nextContext = when (val strategy = config.contextStrategy) {
                ContextStrategy.FullHistory -> compactIfNeeded(
                    messages = completeHistory,
                    previousSummary = summarySnapshot,
                    conversationSpec = spec,
                )
                is ContextStrategy.SlidingWindow -> CompactedContext(
                    messages = recentMessages(completeHistory, strategy.keepLastMessages),
                    summary = null,
                )
                is ContextStrategy.StickyFacts -> CompactedContext(
                    messages = recentMessages(completeHistory, strategy.keepLastMessages),
                    summary = null,
                )
                ContextStrategy.Branching -> CompactedContext(
                    messages = completeHistory,
                    summary = null,
                )
                is ContextStrategy.MemoryLayers -> CompactedContext(
                    messages = recentMessages(completeHistory, strategy.keepLastMessages),
                    summary = null,
                )
            }
            val nextBranches = if (config.contextStrategy == ContextStrategy.Branching) {
                mutableBranches.value.map { branch ->
                    if (branch.id == mutableActiveBranchId.value) {
                        branch.copy(messages = nextContext.messages)
                    } else {
                        branch
                    }
                }
            } else {
                mutableBranches.value
            }
            // Все части памяти фиксируются одним снимком только после успешного ответа.
            persistMemory(
                messages = nextContext.messages,
                summary = nextContext.summary,
                facts = stagedFacts,
                branches = nextBranches,
                layers = if (config.contextStrategy is ContextStrategy.MemoryLayers) {
                    currentLayers(shortTermMessages = nextContext.messages)
                } else {
                    restoredMemory.layers
                },
            )
            mutableHistory.value = nextContext.messages
            mutableSummary.value = nextContext.summary
            mutableFacts.value = stagedFacts
            mutableBranches.value = nextBranches
            response
        }
    }

    /** Добавляет серверную сводку в диалог без искусственной реплики пользователя. */
    suspend fun appendBackgroundToolResult(
        toolCall: AgentToolCall,
        response: String = toolCall.directResponse ?: toolCall.result,
    ) = mutex.withLock {
        mutableLastToolCall.value = toolCall
        val completeHistory = mutableHistory.value + ChatMessage(fromUser = false, text = response)
        val nextMessages = when (val strategy = config.contextStrategy) {
            ContextStrategy.FullHistory, ContextStrategy.Branching -> completeHistory
            is ContextStrategy.SlidingWindow ->
                recentMessages(completeHistory, strategy.keepLastMessages)
            is ContextStrategy.StickyFacts ->
                recentMessages(completeHistory, strategy.keepLastMessages)
            is ContextStrategy.MemoryLayers ->
                recentMessages(completeHistory, strategy.keepLastMessages)
        }
        val nextBranches = if (config.contextStrategy == ContextStrategy.Branching) {
            mutableBranches.value.map { branch ->
                if (branch.id == mutableActiveBranchId.value) {
                    branch.copy(messages = nextMessages)
                } else {
                    branch
                }
            }
        } else {
            mutableBranches.value
        }
        persistMemory(
            messages = nextMessages,
            branches = nextBranches,
            layers = if (config.contextStrategy is ContextStrategy.MemoryLayers) {
                currentLayers(shortTermMessages = nextMessages)
            } else {
                restoredMemory.layers
            },
        )
        mutableHistory.value = nextMessages
        mutableBranches.value = nextBranches
    }

    private suspend fun updateFacts(
        strategy: ContextStrategy.StickyFacts,
        currentFacts: Map<String, String>,
        userMessage: String,
    ): Map<String, String> {
        val factsSpec = ResponseSpec(
            system = factsUpdateSystemPrompt(strategy.maxFacts),
            maxTokens = strategy.updateMaxTokens,
            jsonSchema = FACTS_SCHEMA,
            model = config.model,
        )
        return try {
            val answer = llmClient.answer(
                history = listOf(factsUpdateInput(currentFacts, userMessage)),
                spec = factsSpec,
            )
            val updated = parseFacts(answer.text, strategy.maxFacts)
            val (inputCost, outputCost) = estimateTokenCostUsd(
                pricing = tokenPricingForModel(config.model),
                inputTokens = answer.inputTokens,
                outputTokens = answer.outputTokens,
                cacheCreationInputTokens = answer.cacheCreationInputTokens,
                cacheReadInputTokens = answer.cacheReadInputTokens,
                cacheCreation5mInputTokens = answer.cacheCreation5mInputTokens,
                cacheCreation1hInputTokens = answer.cacheCreation1hInputTokens,
            )
            mutableStatistics.value = mutableStatistics.value.let { current ->
                current.copy(
                    facts = current.facts.copy(
                        updateCount = current.facts.updateCount + 1,
                        inputTokens = current.facts.inputTokens + answer.totalInputTokens,
                        outputTokens = current.facts.outputTokens + answer.outputTokens,
                        costUsd = current.facts.costUsd + inputCost + outputCost,
                        lastError = null,
                    )
                )
            }
            updated
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableStatistics.value = mutableStatistics.value.let { current ->
                current.copy(
                    facts = current.facts.copy(
                        lastError = error.message ?: "Не удалось обновить facts",
                    )
                )
            }
            throw AgentPolicyException(
                "Facts не обновлены, поэтому сообщение не отправлено: " +
                    (error.message ?: "неизвестная ошибка")
            )
        }
    }

    private fun requireBranching() {
        check(config.contextStrategy == ContextStrategy.Branching) {
            "Checkpoint и ветки доступны только в стратегии Branching"
        }
    }

    private fun requireMemoryLayers() {
        check(config.contextStrategy is ContextStrategy.MemoryLayers) {
            "Операция доступна только в стратегии Memory Layers"
        }
    }

    private fun requireMutableInvariantStore(): MutableInvariantStore =
        invariantStore as? MutableInvariantStore
            ?: throw IllegalStateException("Хранилище инвариантов доступно только для чтения")

    private fun requireMemoryText(value: String, label: String): String =
        value.trim().takeIf(String::isNotEmpty)
            ?: throw AgentPolicyException("$label памяти не должен быть пустым")

    private fun requireTaskState(): TaskState = mutableTaskState.value
        ?: throw IllegalStateException("Сначала начните задачу")

    private fun currentLayers(
        shortTermMessages: List<ChatMessage> = mutableHistory.value,
    ) = MemoryLayersSnapshot(
        shortTerm = ShortTermMemory(shortTermMessages),
        working = mutableWorkingMemory.value,
        longTerm = mutableLongTermMemory.value,
    )

    private fun persistLayeredMemory() {
        persistMemory(
            messages = mutableHistory.value,
            summary = null,
            layers = currentLayers(),
        )
    }

    private fun persistWithCurrentLayers() {
        if (config.contextStrategy is ContextStrategy.MemoryLayers) {
            persistLayeredMemory()
        } else {
            persistMemory()
        }
    }

    /** Сохраняет локальный отказ как обычную пару реплик, не вызывая LLM. */
    private fun persistLocalTurn(
        historySnapshot: List<ChatMessage>,
        userMessage: ChatMessage,
        response: String,
    ) {
        val completeHistory = historySnapshot + userMessage +
            ChatMessage(fromUser = false, text = response)
        val nextMessages = when (val strategy = config.contextStrategy) {
            ContextStrategy.FullHistory, ContextStrategy.Branching -> completeHistory
            is ContextStrategy.SlidingWindow ->
                recentMessages(completeHistory, strategy.keepLastMessages)
            is ContextStrategy.StickyFacts ->
                recentMessages(completeHistory, strategy.keepLastMessages)
            is ContextStrategy.MemoryLayers ->
                recentMessages(completeHistory, strategy.keepLastMessages)
        }
        val nextBranches = if (config.contextStrategy == ContextStrategy.Branching) {
            mutableBranches.value.map { branch ->
                if (branch.id == mutableActiveBranchId.value) {
                    branch.copy(messages = nextMessages)
                } else {
                    branch
                }
            }
        } else {
            mutableBranches.value
        }
        persistMemory(
            messages = nextMessages,
            branches = nextBranches,
            layers = if (config.contextStrategy is ContextStrategy.MemoryLayers) {
                currentLayers(shortTermMessages = nextMessages)
            } else {
                restoredMemory.layers
            },
        )
        mutableHistory.value = nextMessages
        mutableBranches.value = nextBranches
    }

    private fun persistMemory(
        messages: List<ChatMessage> = mutableHistory.value,
        summary: String? = mutableSummary.value,
        facts: Map<String, String> = mutableFacts.value,
        activeBranchId: String? = mutableActiveBranchId.value,
        branches: List<DialogueBranch> = mutableBranches.value,
        checkpoints: List<DialogueCheckpoint> = mutableCheckpoints.value,
        layers: MemoryLayersSnapshot = restoredMemory.layers,
        userProfile: UserProfile? = mutableUserProfile.value,
        userProfiles: List<UserProfile> = mutableUserProfiles.value,
        activeUserProfileId: String? = mutableActiveUserProfileId.value,
        taskState: TaskState? = mutableTaskState.value,
    ) {
        historyStore.saveMemory(
            AgentMemorySnapshot(
                messages = messages,
                summary = summary,
                facts = facts,
                activeBranchId = activeBranchId,
                branches = branches,
                checkpoints = checkpoints,
                layers = layers,
                userProfile = userProfile,
                userProfiles = userProfiles,
                activeUserProfileId = activeUserProfileId,
                taskState = taskState,
            )
        )
    }

    private fun nextId(prefix: String, existing: List<String>): String {
        var index = existing.size + 1
        var candidate = "$prefix-$index"
        while (candidate in existing) {
            index++
            candidate = "$prefix-$index"
        }
        return candidate
    }

    private companion object {
        const val MAIN_BRANCH_ID = "branch-main"
    }

    private suspend fun compactIfNeeded(
        messages: List<ChatMessage>,
        previousSummary: String?,
        conversationSpec: ResponseSpec,
    ): CompactedContext {
        val compressionConfig = config.contextCompression
        if (!compressionConfig.enabled) {
            return CompactedContext(messages = messages, summary = null)
        }

        val messagesToCompressCount = messages.size - compressionConfig.keepLastMessages
        if (messagesToCompressCount <= 0) {
            return CompactedContext(messages = messages, summary = previousSummary)
        }

        val messagesToCompress = messages.take(messagesToCompressCount)
        val recentMessages = messages.takeLast(compressionConfig.keepLastMessages)
        val summarySpec = ResponseSpec(
            system = summarySystemPrompt(previousSummary),
            maxTokens = compressionConfig.summaryMaxTokens,
            model = config.model,
        )

        return try {
            val beforeTokens = llmClient.countInputTokens(
                history = messages,
                spec = conversationSpec,
            )
            val summaryAnswer = llmClient.answer(
                history = listOf(summaryInput(messagesToCompress)),
                spec = summarySpec,
            )
            val newSummary = summaryAnswer.text.trim().takeIf(String::isNotEmpty)
                ?: throw AgentPolicyException("Модель вернула пустое summary")
            val afterTokens = llmClient.countInputTokens(
                history = recentMessages,
                spec = conversationSpec.copy(
                    system = systemPromptWithSummary(config.systemPrompt, newSummary),
                ),
            )
            val (summaryInputCost, summaryOutputCost) = estimateTokenCostUsd(
                pricing = tokenPricingForModel(config.model),
                inputTokens = summaryAnswer.inputTokens,
                outputTokens = summaryAnswer.outputTokens,
                cacheCreationInputTokens = summaryAnswer.cacheCreationInputTokens,
                cacheReadInputTokens = summaryAnswer.cacheReadInputTokens,
                cacheCreation5mInputTokens = summaryAnswer.cacheCreation5mInputTokens,
                cacheCreation1hInputTokens = summaryAnswer.cacheCreation1hInputTokens,
            )
            mutableStatistics.value = mutableStatistics.value.let { current ->
                val compression = current.compression
                current.copy(
                    compression = compression.copy(
                        compressionCount = compression.compressionCount + 1,
                        compressedMessages = compression.compressedMessages +
                            messagesToCompress.size,
                        summaryInputTokens = compression.summaryInputTokens +
                            summaryAnswer.totalInputTokens,
                        summaryOutputTokens = compression.summaryOutputTokens +
                            summaryAnswer.outputTokens,
                        summaryCostUsd = compression.summaryCostUsd +
                            summaryInputCost + summaryOutputCost,
                        latestBeforeTokens = beforeTokens,
                        latestAfterTokens = afterTokens,
                        lastError = null,
                    )
                )
            }
            CompactedContext(messages = recentMessages, summary = newSummary)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Ответ пользователю уже получен и остаётся полезным. При временном сбое
            // summarizer сохраняем полную историю и повторим сжатие на следующем ходе.
            mutableStatistics.value = mutableStatistics.value.let { current ->
                current.copy(
                    compression = current.compression.copy(
                        lastError = error.message ?: "Не удалось обновить summary",
                    )
                )
            }
            CompactedContext(messages = messages, summary = previousSummary)
        }
    }
}

private fun <T> List<T>.upsert(item: T, matches: (T) -> Boolean): List<T> {
    val index = indexOfFirst(matches)
    return if (index < 0) this + item else toMutableList().also { it[index] = item }
}

private data class CompactedContext(
    val messages: List<ChatMessage>,
    val summary: String?,
)

private suspend inline fun <T, R> Iterable<T>.foldSuspend(
    initial: R,
    operation: suspend (accumulator: R, T) -> R,
): R {
    var accumulator = initial
    for (element in this) accumulator = operation(accumulator, element)
    return accumulator
}
