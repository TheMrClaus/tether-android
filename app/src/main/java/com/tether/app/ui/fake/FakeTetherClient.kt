package com.tether.app.ui.fake

import com.tether.app.client.ConnectionState
import com.tether.app.client.LoginResult
import com.tether.app.client.PairResult
import com.tether.app.client.TetherClient
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.SessionCommandOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.ApprovalChoice
import com.tether.app.protocol.model.ApprovalRequestMetadata
import com.tether.app.protocol.model.AttachmentMeta
import com.tether.app.protocol.model.DirectoryEntry
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.protocol.model.HistorySession
import com.tether.app.protocol.model.PendingApproval
import com.tether.app.protocol.model.PendingQuestion
import com.tether.app.protocol.model.ProviderCapabilities
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.model.QuestionOption
import com.tether.app.protocol.model.QuestionPrompt
import com.tether.app.protocol.model.QueuedMessage
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.SubagentEntry
import com.tether.app.protocol.model.SubagentThread
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.TurnRun
import com.tether.app.protocol.model.TurnUsage
import com.tether.app.protocol.model.Vocab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A scripted [TetherClient] so the UI runs, demos and screenshots standalone.
 * The integrator swaps this out via ClientLocator.factory -> RealTetherClient.
 */
class FakeTetherClient : TetherClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = System.currentTimeMillis()
    private var streamJob: Job? = null

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _configured = MutableStateFlow(true)
    override val configured: StateFlow<Boolean> = _configured.asStateFlow()

    // One seed session lives exactly here, so the drawer's workspace filter
    // shows it while hiding the subproject seeds (mirrors real usage).
    override val workspaceRoot: StateFlow<String?> = MutableStateFlow("/home/operator/git/aidash")

    override val providers: StateFlow<List<ProviderInfo>> = MutableStateFlow(
        listOf(
            ProviderInfo("claude", "Claude Code", "C", true, ProviderCapabilities(persistentSessions = true, interactiveApprovals = true, interactiveQuestions = true, streamingText = true, tokenDeltas = true)),
            ProviderInfo("codex", "Codex", "X", true, ProviderCapabilities(interactiveApprovals = true, streamingText = true)),
        ),
    )

    override val histories: StateFlow<List<HistorySession>> = MutableStateFlow(emptyList())
    private val _directories = MutableStateFlow<DirectoryListing?>(null)
    override val directories: StateFlow<DirectoryListing?> = _directories.asStateFlow()

    // The seed lets previews show a working picker for `s-active`.
    override val sessionControls: StateFlow<Map<String, ServerMessage.SessionControls>> = MutableStateFlow(
        mapOf(
            "s-active" to ServerMessage.SessionControls(
                sessionId = "s-active",
                models = listOf(
                    SessionModelOption(value = "default", displayName = "Default", current = true, resolvedModel = "claude-opus"),
                    SessionModelOption(value = "claude-opus", displayName = "Opus", description = "Most capable"),
                    SessionModelOption(value = "claude-sonnet", displayName = "Sonnet", description = "Balanced"),
                ),
                commands = listOf(
                    SessionCommandOption(name = "model", description = "Switch the model for this session", argumentHint = "[model]", supported = true),
                    SessionCommandOption(name = "compact", description = "Compact the conversation", supported = false),
                ),
                model = "claude-opus",
            ),
        ),
    )

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _sessions = MutableStateFlow(seedSessions())
    override val sessions: StateFlow<List<AgentSession>> = _sessions.asStateFlow()

    private val _projections = MutableStateFlow(seedProjections())
    override val projections: StateFlow<Map<String, SessionProjection>> = _projections.asStateFlow()

    // --- seeds -----------------------------------------------------------------

    private fun seedSessions(): List<AgentSession> = listOf(
        AgentSession(
            id = "s-active", provider = "claude", name = "tether-ui polish", cwd = "/home/operator/git/aidash",
            status = "active", startedAt = now - 3_600_000, updatedAt = now - 5_000, model = "claude-opus",
        ),
        AgentSession(
            id = "s-approve", provider = "codex", name = "release pipeline", cwd = "/home/operator/git/pipeline",
            status = "waiting", startedAt = now - 7_200_000, updatedAt = now - 120_000,
        ),
        AgentSession(
            id = "s-question", provider = "claude", name = "schema migration", cwd = "/home/operator/git/api",
            status = "waiting", startedAt = now - 5_400_000, updatedAt = now - 300_000,
        ),
        AgentSession(
            id = "s-ready", provider = "claude", name = "docs sweep", cwd = "/home/operator/git/docs",
            status = "ready", startedAt = now - 86_400_000, updatedAt = now - 10_800_000, pinned = true,
        ),
        AgentSession(
            id = "s-exited", provider = "codex", name = "spike: worker threads", cwd = "/home/operator/git/spike",
            status = "exited", startedAt = now - 200_000_000, updatedAt = now - 172_800_000, endedAt = now - 172_800_000, exitCode = 0,
        ),
    )

    private fun seedProjections(): Map<String, SessionProjection> = mapOf(
        "s-active" to activeProjection(),
        "s-approve" to approvalProjection(),
        "s-question" to questionProjection(),
        "s-ready" to SessionProjection(tetherSessionId = "s-ready", provider = "claude", cwd = "/home/operator/git/docs"),
        "s-exited" to SessionProjection(tetherSessionId = "s-exited", provider = "codex", cwd = "/home/operator/git/spike", status = Vocab.SESSION_EXITED),
    )

    private fun activeProjection(): SessionProjection {
        val doneTurn = TurnProjection(
            turnId = "t1",
            status = Vocab.TURN_DONE,
            startedAt = now - 3_500_000,
            activeMs = 78_000,
            outcome = Vocab.OUTCOME_OK,
            usage = TurnUsage(model = "claude-opus", perTurnTokens = 14_620, cumulativeTokens = 14_620, contextWindow = 200_000),
            blocks = listOf("b1", "b2", "b3", "b4", "b5"),
            blocksById = mapOf(
                "b1" to TurnBlock(blockId = "b1", kind = Vocab.BLOCK_USER_MESSAGE, text = "Tighten the drawer polish: the session rows need the waiting ping and the selected wash from the spec."),
                "b2" to TurnBlock(blockId = "b2", kind = Vocab.BLOCK_THINKING, done = true, text = "The spec asks for a 2s expanding halo on waiting dots and violet-wash on the selected row. I should check the current row composable first, then wire the tokens."),
                "b3" to TurnBlock(
                    blockId = "b3", kind = Vocab.BLOCK_TOOL, name = "Bash", done = true,
                    input = buildJsonObject { put("command", "rg -n \"waiting-ping\" app/globals.css | head") },
                    output = JsonPrimitive("2114:  animation: waiting-ping 2s cubic-bezier(0, 0, 0.2, 1) infinite;\n2131:@keyframes waiting-ping {\n2132:  0% { transform: scale(1); opacity: .5; }\n2133:  100% { transform: scale(2.25); opacity: 0; }"),
                ),
                "b4" to TurnBlock(
                    blockId = "b4", kind = Vocab.BLOCK_TOOL, name = "Edit", done = true,
                    input = buildJsonObject {
                        put("file_path", "components/session-sidebar.tsx")
                        put("old_string", "<span className=\"dot\" />\n  <span>{statusCopy[session.status]}</span>")
                        put("new_string", "<span className={cx(\"dot\", session.status === \"waiting\" && \"dot-ping\")} />\n  <span>{statusCopy[session.status]}</span>")
                    },
                ),
                "b5" to TurnBlock(blockId = "b5", kind = Vocab.BLOCK_MESSAGE, done = true, text = "Done. The waiting dot now carries the **2s ping halo** and the selected row uses `violet-wash` with a `violet-strong` border.\n\n- `session-sidebar.tsx` — dot ping class\n- `globals.css` — already had the keyframes\n\n```css\n.dot-ping { animation: waiting-ping 2s infinite; }\n```\n\nRun `npm run dev` and open the drawer to verify."),
            ),
        )
        val liveTurn = TurnProjection(
            turnId = "t2",
            status = Vocab.TURN_RUNNING,
            startedAt = now - 42_000,
            liveTokens = 1_204,
            run = TurnRun(index = 1, startedAt = now - 42_000, tokensStart = 0),
            runCount = 1,
            blocks = listOf("b6", "b7", "b8"),
            blocksById = mapOf(
                "b6" to TurnBlock(blockId = "b6", kind = Vocab.BLOCK_USER_MESSAGE, text = "Now do the same for the project rows."),
                "b7" to TurnBlock(
                    blockId = "b7", kind = Vocab.BLOCK_TOOL, name = "Task", done = true,
                    input = buildJsonObject { put("description", "Scan project row styles") },
                    output = JsonPrimitive("Found 3 call sites using the legacy dot markup."),
                    subagent = SubagentThread(
                        order = listOf("sa1", "sa2"),
                        entries = mapOf(
                            "sa1" to SubagentEntry(key = "sa1", kind = "tool", name = "Grep", done = true),
                            "sa2" to SubagentEntry(key = "sa2", kind = "message", text = "Three call sites in session-sidebar.tsx render the legacy dot; all reachable from ProjectRow.", done = true),
                        ),
                    ),
                ),
                "b8" to TurnBlock(blockId = "b8", kind = Vocab.BLOCK_MESSAGE, done = false, text = "Applying the same ping treatment to the project activity dots now. The"),
            ),
        )
        return SessionProjection(
            tetherSessionId = "s-active",
            provider = "claude",
            cwd = "/home/operator/git/aidash",
            status = Vocab.SESSION_ACTIVE,
            turnOrder = listOf("t1", "t2"),
            turnsById = mapOf("t1" to doneTurn, "t2" to liveTurn),
            activeTurnId = "t2",
        )
    }

    private fun approvalProjection(): SessionProjection {
        val turn = TurnProjection(
            turnId = "ta1",
            status = Vocab.TURN_RUNNING,
            startedAt = now - 400_000,
            activeMs = 61_000,
            blocks = listOf("ba1", "ba2"),
            blocksById = mapOf(
                "ba1" to TurnBlock(blockId = "ba1", kind = Vocab.BLOCK_USER_MESSAGE, text = "Cut the release and push the tag."),
                "ba2" to TurnBlock(blockId = "ba2", kind = Vocab.BLOCK_MESSAGE, done = true, text = "Version bumped. I need to run the publish script next."),
            ),
            pendingApprovals = mapOf(
                "req-1" to PendingApproval(
                    requestId = "req-1",
                    toolId = "tool-appr",
                    name = "Bash",
                    input = buildJsonObject { put("command", "./scripts/publish.sh --tag v1.4.0") },
                    choices = listOf(
                        ApprovalChoice("allow-once", "Allow once"),
                        ApprovalChoice("allow-always", "Always allow publish.sh", description = "Adds a permission for this exact command", permissionGrant = "exact"),
                    ),
                    metadata = ApprovalRequestMetadata(provider = "codex", kind = "exec", reason = "Command is outside the sandbox policy", command = "./scripts/publish.sh --tag v1.4.0", cwd = "/home/operator/git/pipeline"),
                ),
            ),
        )
        return SessionProjection(
            tetherSessionId = "s-approve",
            provider = "codex",
            cwd = "/home/operator/git/pipeline",
            status = Vocab.SESSION_WAITING,
            turnOrder = listOf("ta1"),
            turnsById = mapOf("ta1" to turn),
            activeTurnId = "ta1",
            queuedMessages = listOf(QueuedMessage("q1", "After the release, update the changelog too.")),
        )
    }

    private fun questionProjection(): SessionProjection {
        val turn = TurnProjection(
            turnId = "tq1",
            status = Vocab.TURN_RUNNING,
            startedAt = now - 900_000,
            activeMs = 122_000,
            blocks = listOf("bq1", "bq2"),
            blocksById = mapOf(
                "bq1" to TurnBlock(blockId = "bq1", kind = Vocab.BLOCK_USER_MESSAGE, text = "Migrate the users table to the new schema."),
                "bq2" to TurnBlock(blockId = "bq2", kind = Vocab.BLOCK_MESSAGE, done = true, text = "Two viable strategies here; the tradeoff is downtime versus dual-write complexity."),
            ),
            pendingQuestions = mapOf(
                "req-q1" to PendingQuestion(
                    requestId = "req-q1",
                    toolId = "tool-q",
                    questions = listOf(
                        QuestionPrompt(
                            question = "Which migration strategy should I use?",
                            header = "Strategy",
                            options = listOf(
                                QuestionOption("Expand-contract", "Dual-write to both schemas, then cut over with zero downtime"),
                                QuestionOption("Locked rewrite", "Take a short maintenance window and rewrite in place"),
                            ),
                        ),
                        QuestionPrompt(
                            question = "Which environments should the migration run in?",
                            header = "Environments",
                            multiSelect = true,
                            options = listOf(
                                QuestionOption("staging", "Run first against staging"),
                                QuestionOption("production", "Roll to production after soak"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        return SessionProjection(
            tetherSessionId = "s-question",
            provider = "claude",
            cwd = "/home/operator/git/api",
            status = Vocab.SESSION_WAITING,
            turnOrder = listOf("tq1"),
            turnsById = mapOf("tq1" to turn),
            activeTurnId = "tq1",
        )
    }

    // --- mutation helpers -------------------------------------------------------

    private fun updateProjection(sessionId: String, transform: (SessionProjection) -> SessionProjection) {
        _projections.update { map ->
            val current = map[sessionId] ?: return@update map
            map + (sessionId to transform(current))
        }
    }

    private fun touchSession(sessionId: String, status: String? = null) {
        _sessions.update { list ->
            list.map { s ->
                if (s.id == sessionId) s.copy(status = status ?: s.status, updatedAt = System.currentTimeMillis()) else s
            }
        }
    }

    // --- TetherClient -----------------------------------------------------------

    override suspend fun login(baseUrl: String, password: String): LoginResult {
        delay(400)
        return when {
            baseUrl.contains("unreachable") -> LoginResult.Unreachable("Could not reach $baseUrl")
            password == "wrong" -> LoginResult.BadPassword("That password was not accepted.")
            else -> {
                _configured.value = true
                _connection.value = ConnectionState.Connected
                LoginResult.Success
            }
        }
    }

    override suspend fun pair(baseUrl: String, code: String, label: String): PairResult {
        delay(400)
        return when {
            baseUrl.contains("unreachable") -> PairResult.Unreachable("Could not reach $baseUrl")
            baseUrl.contains("old") -> PairResult.NotSupported("This server does not support device pairing.")
            // Anything that is not a plausible 8-character code is "rejected" so
            // the screen's error path can be demoed without a server.
            code.trim().length != 8 -> PairResult.Rejected("That pairing code is not valid or has expired.")
            else -> {
                _configured.value = true
                _connection.value = ConnectionState.Connected
                PairResult.Success
            }
        }
    }

    override fun start() { _connection.value = ConnectionState.Connected }

    override fun stop() {
        streamJob?.cancel()
        _configured.value = false
        _connection.value = ConnectionState.AuthRequired
    }

    override fun attach(sessionId: String) { /* projections are pre-seeded */ }

    override fun send(sessionId: String, text: String, attachments: List<Attachment>) {
        val turnId = "t-${System.currentTimeMillis()}"
        val startTs = System.currentTimeMillis()
        val userBlock = TurnBlock(
            blockId = "$turnId-u",
            kind = Vocab.BLOCK_USER_MESSAGE,
            text = text,
            attachments = attachments.map { AttachmentMeta(it.name, it.mediaType) }.ifEmpty { null },
        )
        val agentBlock = TurnBlock(blockId = "$turnId-a", kind = Vocab.BLOCK_MESSAGE, done = false, text = "")
        updateProjection(sessionId) { p ->
            p.copy(
                status = Vocab.SESSION_ACTIVE,
                turnOrder = p.turnOrder + turnId,
                turnsById = p.turnsById + (turnId to TurnProjection(
                    turnId = turnId,
                    status = Vocab.TURN_RUNNING,
                    startedAt = startTs,
                    liveTokens = 0,
                    run = TurnRun(index = 1, startedAt = startTs, tokensStart = 0),
                    runCount = 1,
                    blocks = listOf(userBlock.blockId, agentBlock.blockId),
                    blocksById = mapOf(userBlock.blockId to userBlock, agentBlock.blockId to agentBlock),
                )),
                activeTurnId = turnId,
            )
        }
        touchSession(sessionId, status = "active")
        streamJob = scope.launch {
            val reply = "On it. I'll take that in three steps: survey the touched files, apply the change behind the existing tokens, then run the checks. Expect a diff shortly — nothing here needs an approval."
            val words = reply.split(" ")
            var acc = ""
            for ((i, word) in words.withIndex()) {
                delay(140)
                acc = if (acc.isEmpty()) word else "$acc $word"
                val doneNow = i == words.lastIndex
                updateProjection(sessionId) { p ->
                    val turn = p.turnsById[turnId] ?: return@updateProjection p
                    val block = turn.blocksById[agentBlock.blockId] ?: return@updateProjection p
                    p.copy(
                        turnsById = p.turnsById + (turnId to turn.copy(
                            liveTokens = (i + 1).toLong() * 3,
                            status = if (doneNow) Vocab.TURN_DONE else turn.status,
                            outcome = if (doneNow) Vocab.OUTCOME_OK else turn.outcome,
                            run = if (doneNow) null else turn.run,
                            activeMs = if (doneNow) turn.activeMs + (System.currentTimeMillis() - startTs) else turn.activeMs,
                            usage = if (doneNow) TurnUsage(perTurnTokens = words.size.toLong() * 3) else turn.usage,
                            blocksById = turn.blocksById + (agentBlock.blockId to block.copy(text = acc, done = doneNow)),
                        )),
                        activeTurnId = if (doneNow) null else p.activeTurnId,
                        status = if (doneNow) Vocab.SESSION_READY else p.status,
                    )
                }
                touchSession(sessionId, status = if (doneNow) "ready" else "active")
            }
        }
    }

    override fun queueAdd(sessionId: String, text: String) {
        updateProjection(sessionId) { p ->
            p.copy(queuedMessages = p.queuedMessages + QueuedMessage("q-${System.currentTimeMillis()}", text))
        }
    }

    override fun queueEdit(sessionId: String, queueId: String, text: String) {
        updateProjection(sessionId) { p ->
            p.copy(queuedMessages = p.queuedMessages.map { if (it.queueId == queueId) it.copy(text = text) else it })
        }
    }

    override fun queueRemove(sessionId: String, queueId: String) {
        updateProjection(sessionId) { p ->
            p.copy(queuedMessages = p.queuedMessages.filterNot { it.queueId == queueId })
        }
    }

    override fun interrupt(sessionId: String) {
        streamJob?.cancel()
        updateProjection(sessionId) { p ->
            val turnId = p.activeTurnId ?: return@updateProjection p
            val turn = p.turnsById[turnId] ?: return@updateProjection p
            p.copy(
                turnsById = p.turnsById + (turnId to turn.copy(
                    status = Vocab.TURN_DONE,
                    outcome = Vocab.OUTCOME_CANCELLED,
                    run = null,
                    blocksById = turn.blocksById.mapValues { (_, b) ->
                        if (b.kind == Vocab.BLOCK_MESSAGE && b.done != true) b.copy(done = true, aborted = true) else b
                    },
                )),
                activeTurnId = null,
                status = Vocab.SESSION_READY,
            )
        }
        touchSession(sessionId, status = "ready")
    }

    override fun approval(sessionId: String, requestId: String, choiceId: String?, decision: String?) {
        updateProjection(sessionId) { p ->
            val turnId = p.activeTurnId ?: return@updateProjection p
            val turn = p.turnsById[turnId] ?: return@updateProjection p
            p.copy(
                turnsById = p.turnsById + (turnId to turn.copy(pendingApprovals = turn.pendingApprovals - requestId)),
                status = Vocab.SESSION_ACTIVE,
            )
        }
        touchSession(sessionId, status = "active")
        scope.launch {
            delay(1200)
            updateProjection(sessionId) { p ->
                val turnId = p.activeTurnId ?: return@updateProjection p
                val turn = p.turnsById[turnId] ?: return@updateProjection p
                val denied = decision == "deny"
                val doneBlock = TurnBlock(
                    blockId = "$turnId-post",
                    kind = Vocab.BLOCK_MESSAGE,
                    done = true,
                    text = if (denied) "Understood — skipping the publish step." else "Publish finished cleanly; tag v1.4.0 is live.",
                )
                p.copy(
                    turnsById = p.turnsById + (turnId to turn.copy(
                        status = Vocab.TURN_DONE,
                        outcome = Vocab.OUTCOME_OK,
                        run = null,
                        blocks = turn.blocks + doneBlock.blockId,
                        blocksById = turn.blocksById + (doneBlock.blockId to doneBlock),
                    )),
                    activeTurnId = null,
                    status = Vocab.SESSION_READY,
                )
            }
            touchSession(sessionId, status = "ready")
        }
    }

    override fun answerQuestion(sessionId: String, requestId: String, answers: Map<String, String>, response: String?) {
        updateProjection(sessionId) { p ->
            val turnId = p.activeTurnId ?: return@updateProjection p
            val turn = p.turnsById[turnId] ?: return@updateProjection p
            val ack = TurnBlock(
                blockId = "$turnId-ack",
                kind = Vocab.BLOCK_MESSAGE,
                done = true,
                text = "Got it — proceeding with ${answers.values.joinToString(" / ")}.",
            )
            p.copy(
                turnsById = p.turnsById + (turnId to turn.copy(
                    status = Vocab.TURN_DONE,
                    outcome = Vocab.OUTCOME_OK,
                    run = null,
                    pendingQuestions = turn.pendingQuestions - requestId,
                    blocks = turn.blocks + ack.blockId,
                    blocksById = turn.blocksById + (ack.blockId to ack),
                )),
                activeTurnId = null,
                status = Vocab.SESSION_READY,
            )
        }
        touchSession(sessionId, status = "ready")
    }

    override fun createSession(provider: String, cwd: String?, name: String?) {
        val id = "s-${System.currentTimeMillis()}"
        val ts = System.currentTimeMillis()
        val dir = cwd ?: "/home/operator/git"
        _sessions.update { list ->
            list + AgentSession(
                id = id, provider = provider, name = name ?: "new session", cwd = dir,
                status = "ready", startedAt = ts, updatedAt = ts,
            )
        }
        _projections.update { it + (id to SessionProjection(tetherSessionId = id, provider = provider, cwd = dir)) }
    }

    override fun resumeHistory(historyId: String, cwd: String) {}
    override fun discover(cwd: String) {}
    override fun browse(cwd: String?) {
        // Scripted listing so the folder picker works in previews/demos.
        val current = cwd ?: "/home/operator/git"
        _directories.value = DirectoryListing(
            current = current,
            parent = current.substringBeforeLast('/', "").ifEmpty { null }?.takeIf { current != "/" },
            entries = listOf("aidash", "tether-android", "dotfiles").map { DirectoryEntry(it, "$current/$it") },
        )
    }
    override fun setMode(sessionId: String, permissionMode: String) {}

    override fun setModel(sessionId: String, model: String): Boolean = true
    override fun requestSessionControls(sessionId: String) {}

    override fun pin(sessionId: String, pinned: Boolean) {
        _sessions.update { list -> list.map { if (it.id == sessionId) it.copy(pinned = pinned) else it } }
    }

    override fun rename(sessionId: String, name: String) {
        _sessions.update { list -> list.map { if (it.id == sessionId) it.copy(name = name) else it } }
    }

    override fun archive(sessionId: String) {
        _sessions.update { list -> list.filterNot { it.id == sessionId } }
    }

    override fun kill(sessionId: String) {
        val ts = System.currentTimeMillis()
        _sessions.update { list ->
            list.map { if (it.id == sessionId) it.copy(status = "exited", endedAt = ts, updatedAt = ts) else it }
        }
        updateProjection(sessionId) { it.copy(status = Vocab.SESSION_EXITED, activeTurnId = null) }
    }

    override fun reconnectIfIdle() {}
}
