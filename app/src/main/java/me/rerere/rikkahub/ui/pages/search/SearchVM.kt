package me.rerere.rikkahub.ui.pages.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import kotlin.uuid.Uuid

private const val SORT_ORDER_PREF_KEY = "search_page_sort_order"

enum class MessageSearchScope {
    CURRENT_ASSISTANT,
    ALL_ASSISTANTS,
}

private data class SearchRequest(
    val query: String,
    val sort: MessageSearchSort,
    val scope: MessageSearchScope,
    val assistantId: Uuid?,
    val debounce: Boolean,
    val filter: me.rerere.rikkahub.data.db.fts.WorkSearchFilter,
    val offset: Int = 0,
)

class SearchVM(
    private val context: Application,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    val projectRepository: me.rerere.rikkahub.data.repository.ProjectRepository,
) : ViewModel() {
    private val searchRequests = Channel<SearchRequest>(Channel.CONFLATED)
    private var currentAssistantId: Uuid? = null

    var searchQuery by mutableStateOf("")
        private set
    var searchScope by mutableStateOf(MessageSearchScope.CURRENT_ASSISTANT)
        private set
    var sortOrder by mutableStateOf(
        runCatching {
            MessageSearchSort.valueOf(
                context.readStringPreference(SORT_ORDER_PREF_KEY, MessageSearchSort.RELEVANCE.name)!!
            )
        }.getOrDefault(MessageSearchSort.RELEVANCE)
    )
        private set
    var results by mutableStateOf<List<MessageSearchResult>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isRebuilding by mutableStateOf(false)
        private set
    var rebuildProgress by mutableStateOf(0 to 0)
        private set

    var projectId by mutableStateOf<String?>(null)
        private set
    var kind by mutableStateOf(me.rerere.rikkahub.data.db.fts.WorkSearchKind.ALL)
        private set
    var includeChildren by mutableStateOf(true)
        private set
    var afterDate by mutableStateOf("")
        private set
    var beforeDate by mutableStateOf("")
        private set
    var toolName by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(false)
        private set

    fun filters(project: String?, type: me.rerere.rikkahub.data.db.fts.WorkSearchKind, children: Boolean, after: String, before: String, tool: String) {
        projectId = project; kind = type; includeChildren = children; afterDate = after; beforeDate = before; toolName = tool
        search()
    }

    fun loadMore() { if (!isLoading && hasMore) requestSearch(offset = results.size) }

    init {
        viewModelScope.launch {
            searchRequests.receiveAsFlow().collectLatest { request -> performSearch(request) }
        }
        viewModelScope.launch {
            settingsStore.settingsFlow
                .map { it.getCurrentAssistant().id }
                .distinctUntilChanged()
                .collect { assistantId ->
                    currentAssistantId = assistantId
                    if (searchScope == MessageSearchScope.CURRENT_ASSISTANT) {
                        search()
                    }
                }
        }
    }

    fun onQueryChange(query: String) {
        searchQuery = query
        requestSearch(debounce = true)
    }

    fun onScopeChange(scope: MessageSearchScope) {
        if (searchScope == scope) return
        searchScope = scope
        search()
    }

    fun onSortChange(sort: MessageSearchSort) {
        if (sortOrder == sort) return
        sortOrder = sort
        context.writeStringPreference(SORT_ORDER_PREF_KEY, sort.name)
        search()
    }

    fun search() {
        requestSearch()
    }

    private fun requestSearch(debounce: Boolean = false, offset: Int = 0) {
        val assistantId = when (searchScope) {
            MessageSearchScope.CURRENT_ASSISTANT -> currentAssistantId
            MessageSearchScope.ALL_ASSISTANTS -> null
        }
        fun date(value: String, inclusiveEnd: Boolean = false): Long? = value.takeIf { it.isNotBlank() }?.let {
            java.time.LocalDate.parse(it).plusDays(if (inclusiveEnd) 1 else 0).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val filter = try {
            val after = date(afterDate)
            val before = date(beforeDate, true)
            require(after == null || before == null || before > after) { "Date range is reversed" }
            me.rerere.rikkahub.data.db.fts.WorkSearchFilter(kind, projectId?.let { id -> projectRepository.projects.value.firstOrNull { it.id == id }?.conversationIds.orEmpty() }, includeChildren, after, before, toolName)
        } catch (failure: Exception) { error = failure.message; return }
        error = null
        searchRequests.trySend(
            SearchRequest(
                query = searchQuery,
                sort = sortOrder,
                scope = searchScope,
                assistantId = if (projectId == null) assistantId else null,
                debounce = debounce,
                filter = filter,
                offset = offset,
            )
        )
    }

    fun rebuildIndex() {
        viewModelScope.launch {
            isRebuilding = true
            rebuildProgress = 0 to 0
            try {
                conversationRepo.rebuildAllIndexes { current, total ->
                    rebuildProgress = current to total
                }
            } finally {
                isRebuilding = false
            }
        }
    }

    private suspend fun performSearch(request: SearchRequest) {
        if (request.offset == 0) { results = emptyList(); hasMore = false }
        if (request.query.isBlank() ||
            (request.scope == MessageSearchScope.CURRENT_ASSISTANT && request.assistantId == null && request.filter.conversationIds == null)
        ) {
            return
        }
        isLoading = true
        try {
            if (request.debounce) delay(300L)
            val page = conversationRepo.searchMessages(request.query, request.sort, request.assistantId, request.filter, limit = 50, offset = request.offset)
            results = if (request.offset == 0) page else results + page
            hasMore = page.size == 50
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        } finally {
            isLoading = false
        }
    }
}
