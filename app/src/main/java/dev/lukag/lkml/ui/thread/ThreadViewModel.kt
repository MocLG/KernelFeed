package dev.lukag.lkml.ui.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lukag.lkml.core.AppError
import dev.lukag.lkml.core.NetworkMonitor
import dev.lukag.lkml.core.Resource
import dev.lukag.lkml.domain.model.BodyBlock
import dev.lukag.lkml.domain.model.FlatMessage
import dev.lukag.lkml.domain.model.Message
import dev.lukag.lkml.domain.model.MessageNode
import dev.lukag.lkml.domain.model.ThreadSummary
import dev.lukag.lkml.domain.parser.ThreadTreeBuilder
import dev.lukag.lkml.domain.repository.ThreadRepository
import dev.lukag.lkml.domain.usecase.GetThreadTreeUseCase
import dev.lukag.lkml.domain.usecase.ParseMessageBodyUseCase
import dev.lukag.lkml.di.DefaultDispatcher
import dev.lukag.lkml.ui.navigation.MessageIdRoute
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ThreadUiState(
    val rootMessageId: String = "",
    val summary: ThreadSummary? = null,
    val flattened: List<FlatMessage> = emptyList(),
    val roots: List<MessageNode> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val collapsed: Set<String> = emptySet(),
    val bodies: Map<String, List<BodyBlock>> = emptyMap(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isOffline: Boolean = false,
    val error: AppError? = null,
    /** Set when the user asks to jump to a parent; consumed by the list to scroll. */
    val scrollTarget: String? = null,
) {
    val messageCount: Int get() = flattened.size
    val isEmpty: Boolean get() = flattened.isEmpty() && !isLoading
}

@HiltViewModel
class ThreadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ThreadRepository,
    private val getThreadTree: GetThreadTreeUseCase,
    private val parseBody: ParseMessageBodyUseCase,
    networkMonitor: NetworkMonitor,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : ViewModel() {

    /** The route carries a Base64-URL Message-ID; see `MessageIdRoute` for why. */
    private val rootMessageId: String = MessageIdRoute.decode(
        checkNotNull(savedStateHandle["rootMessageId"]) {
            "ThreadViewModel requires a rootMessageId route argument"
        },
    )

    /** UI-owned state that is not derived from the database. */
    private val local = MutableStateFlow(LocalState())

    private data class LocalState(
        val expanded: Set<String> = emptySet(),
        val collapsed: Set<String> = emptySet(),
        val bodies: Map<String, List<BodyBlock>> = emptyMap(),
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val error: AppError? = null,
        val scrollTarget: String? = null,
    )

    /**
     * Tree state is derived, not stored.
     *
     * `combine` over (tree, thread row, local UI state, connectivity) means a message
     * batch landing in Room during the mbox download re-flattens the tree automatically —
     * the thread visibly fills in as it downloads with no progress plumbing of its own.
     * Flattening runs on `Default` because collapsing a large subtree is O(n).
     */
    val state: StateFlow<ThreadUiState> = combine(
        getThreadTree(rootMessageId),
        repository.observeThread(rootMessageId),
        local,
        networkMonitor.isOnline,
    ) { roots, summary, localState, online ->
        val flat = withContext(default) {
            ThreadTreeBuilder.flatten(roots, localState.collapsed)
        }
        ThreadUiState(
            rootMessageId = rootMessageId,
            summary = summary,
            flattened = flat,
            roots = roots,
            expanded = localState.expanded.ifEmpty {
                // Open the first message by default: it is the one the reader came for.
                setOfNotNull(flat.firstOrNull()?.message?.messageId)
            },
            collapsed = localState.collapsed,
            bodies = localState.bodies,
            isLoading = localState.isLoading && flat.isEmpty(),
            isRefreshing = localState.isRefreshing,
            isOffline = !online,
            error = localState.error,
            scrollTarget = localState.scrollTarget,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThreadUiState(rootMessageId = rootMessageId, isLoading = true),
    )

    init {
        load(force = false)
    }

    fun load(force: Boolean) {
        viewModelScope.launch {
            local.update { it.copy(isRefreshing = force, error = null) }
            when (val result = repository.ensureThreadCached(rootMessageId, force)) {
                is Resource.Success ->
                    local.update { it.copy(isLoading = false, isRefreshing = false) }
                is Resource.Offline ->
                    local.update { it.copy(isLoading = false, isRefreshing = false) }
                is Resource.Error ->
                    local.update {
                        it.copy(isLoading = false, isRefreshing = false, error = result.error)
                    }
                is Resource.Loading -> Unit
            }
        }
    }

    /**
     * Parses a body on demand and memoises it.
     *
     * Bodies are parsed when a message is first opened rather than all at once: a 300-
     * message series would otherwise pay for hundreds of full diff parses that the reader
     * will never scroll to.
     */
    fun requestBody(message: Message) {
        if (local.value.bodies.containsKey(message.messageId)) return
        viewModelScope.launch {
            val blocks = parseBody(message.body)
            local.update { it.copy(bodies = it.bodies + (message.messageId to blocks)) }
        }
    }

    fun toggleExpanded(messageId: String) {
        local.update { s ->
            val current = s.expanded.ifEmpty {
                setOfNotNull(state.value.flattened.firstOrNull()?.message?.messageId)
            }
            s.copy(expanded = if (messageId in current) current - messageId else current + messageId)
        }
    }

    fun toggleCollapsed(messageId: String) {
        local.update { s ->
            s.copy(collapsed = if (messageId in s.collapsed) s.collapsed - messageId else s.collapsed + messageId)
        }
    }

    fun collapseAll() {
        val withChildren = state.value.flattened.filter { it.childCount > 0 }.map { it.message.messageId }
        local.update { it.copy(collapsed = withChildren.toSet()) }
    }

    fun expandAll() {
        local.update { it.copy(collapsed = emptySet()) }
    }

    /**
     * Jumps to a message's parent, un-collapsing whatever hides it.
     *
     * Navigating to a node that is inside a collapsed subtree would otherwise scroll to an
     * item that is not in the list, so every ancestor is expanded first.
     */
    fun jumpToParent(messageId: String) {
        viewModelScope.launch {
            val ancestors = withContext(default) {
                ThreadTreeBuilder.ancestorsOf(state.value.roots, messageId)
            }
            val parent = ancestors.firstOrNull() ?: return@launch
            val ancestorIds = ancestors.map { it.messageId }.toSet()
            local.update { s ->
                s.copy(
                    collapsed = s.collapsed - ancestorIds,
                    scrollTarget = parent.messageId,
                    expanded = s.expanded + parent.messageId,
                )
            }
        }
    }

    fun consumeScrollTarget() {
        local.update { it.copy(scrollTarget = null) }
    }

    fun toggleSaved() {
        val summary = state.value.summary ?: return
        viewModelScope.launch {
            repository.setSaved(rootMessageId, !summary.isSaved)
            // Saving implies the user wants it offline now, not at the next sync.
            if (!summary.isSaved) repository.ensureThreadCached(rootMessageId, force = false)
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }
}
