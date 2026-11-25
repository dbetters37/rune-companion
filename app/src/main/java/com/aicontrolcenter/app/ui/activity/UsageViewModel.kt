@file:Phase6Stub("WP10a")

package com.aicontrolcenter.app.ui.activity

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.UsageReports
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId

/** What the Usage and cost tab shows (WP10a fills it). */
@Immutable
data class UsageUiState(val isLoading: Boolean = true)

/**
 * The Usage and cost tab (WP10a). Read only: usage numbers (rule 7: numbers and ids only), the prices as read, agent and
 * model names, and the recent requests for "how long requests waited for you". Built off the main thread
 * (`flowOn(Dispatchers.Default)`).
 *
 * @param recentRequests the owner's newest requests (`OwnerRequests.observeRecent`), for answer times only.
 * @param zone the owner's time zone (`AppGraph.zone`), where a day and a month start.
 */
class UsageViewModel(
    @Suppress("unused") private val reports: UsageReports,
    @Suppress("unused") private val prices: ModelPrices,
    @Suppress("unused") private val directory: RegistryDirectory,
    @Suppress("unused") private val recentRequests: (limit: Int) -> Flow<List<OwnerRequest>>,
    @Suppress("unused") private val clock: Clock,
    @Suppress("unused") private val zone: () -> ZoneId,
) : ViewModel() {
    val uiState: StateFlow<UsageUiState> = MutableStateFlow(UsageUiState()).asStateFlow()
}
