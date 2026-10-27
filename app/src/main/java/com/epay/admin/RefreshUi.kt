package com.epay.admin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

internal val refreshableRoutes = setOf("home", "orders", "merchants", "channels", "resource", "sessions", "order", "order-notifications", "stats", "tasks", "task-plugin", "task-history", "task-logs", "complaints", "complaint", "complaint-accounts")
internal val listRoutes = setOf("orders", "merchants", "channels", "resource", "sessions")

/** A nested-scroll gesture: only the unused downward scroll at the top refreshes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RefreshContainer(
    isRefreshing: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()
    Box(Modifier.fillMaxSize().testTag("pull-refresh")
        .semantics { stateDescription = if (isRefreshing) "正在刷新" else if (enabled) "下拉刷新" else "" }
        .pullToRefresh(isRefreshing = isRefreshing, state = state, enabled = enabled,
            onRefresh = { if (enabled && !isRefreshing) onRefresh() })) {
        content()
        if (enabled || isRefreshing) PullToRefreshDefaults.Indicator(
            state = state, isRefreshing = isRefreshing, modifier = Modifier.align(Alignment.TopCenter),
            containerColor = MaterialTheme.colorScheme.surface, color = MaterialTheme.colorScheme.primary
        )
    }
}
