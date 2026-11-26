package com.aicontrolcenter.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.activity.LIMITS_STOP_WORK
import com.aicontrolcenter.app.ui.activity.TOKENS_IN_PLAIN_WORDS
import com.aicontrolcenter.app.ui.activity.WHEN_A_LIMIT_IS_REACHED
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.components.digitsOnly
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone

/** The Daily limits screen (WP10a): the three limits as fields, today's use, and what happens when one is reached. */
@Composable
fun DailyLimitsRoute(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel { DailyLimitsViewModel(graph.settings, graph.todayUsage) }
    DailyLimitsScreen(viewModel, onBack)
}

@Composable
fun DailyLimitsScreen(viewModel: DailyLimitsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Daily limits", onBack = onBack) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ScreenScaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .readableWidth()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            state.reached?.let { InfoBanner(it, tone = StatusTone.ATTENTION, title = "Today's limit is reached") }
            Column {
                SectionHeader("Today")
                AppCard {
                    state.lines.forEachIndexed { index, line ->
                        if (index > 0) RowDivider()
                        LimitLine(line)
                    }
                    Text(
                        "Models on this phone cost nothing and are never limited. The counts start again at midnight.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            }
            Column {
                SectionHeader("Limits for each day")
                AppCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        AppTextField(
                            state = viewModel.answers,
                            label = "Model answers a day",
                            supportingText = "Each request a model receives counts as one answer.",
                            errorText = state.errors.answers,
                            inputTransformation = digitsOnly(6),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        )
                        AppTextField(
                            state = viewModel.tokens,
                            label = "Tokens a day",
                            supportingText = TOKENS_IN_PLAIN_WORDS,
                            errorText = state.errors.tokens,
                            inputTransformation = digitsOnly(10),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        )
                        RowDivider(Modifier.padding(vertical = Spacing.xs))
                        SwitchRow(
                            title = "Limit money too",
                            description = "Counts the estimated cost of the models you entered a price for. Models without a price are limited by answers and tokens only.",
                            checked = state.moneyLimitOn,
                            onCheckedChange = viewModel::setMoneyLimitOn,
                        )
                        AppTextField(
                            state = viewModel.money,
                            label = "Money a day (estimated)",
                            supportingText = if (state.moneyLimitOn) "In the currency of your prices, for example 1.00 for one US dollar." else "Off: no money limit.",
                            errorText = state.errors.money,
                            enabled = state.moneyLimitOn,
                            inputTransformation = DailyLimitsViewModel.MoneyInput,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                            onKeyboardAction = { viewModel.save() },
                        )
                    }
                    Button(
                        onClick = viewModel::save,
                        enabled = !state.isSaving,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.md).heightIn(min = 48.dp),
                    ) { Text("Save limits") }
                    if (!state.usual) {
                        OutlinedButton(
                            onClick = viewModel::restoreUsual,
                            enabled = !state.isSaving,
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).heightIn(min = 48.dp),
                        ) { Text("Use the usual limits (200 answers, 500,000 tokens, US$1.00)") }
                    }
                    state.savedMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = Spacing.sm).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
            Column {
                SectionHeader("When a limit is reached")
                AppCard {
                    Text(WHEN_A_LIMIT_IS_REACHED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        LIMITS_STOP_WORK + " Costs are estimates from the prices you entered; your provider's bill is what counts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            }
        }
    }
}
