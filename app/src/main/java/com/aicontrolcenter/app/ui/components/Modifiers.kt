package com.aicontrolcenter.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import com.aicontrolcenter.app.ui.theme.Spacing

/** Centres content and stops it stretching past a comfortable reading width on wide screens. */
fun Modifier.readableWidth(): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = Spacing.maxContentWidth)

/**
 * [animateContentSize] unless Calm is on (the owner's setting or Android's animation switch, [LocalCalmMotion]): then the
 * block shows its end size at once (D3A-19; `ui/theme/Motion.kt`: under Calm, screens show the end state at once).
 */
fun Modifier.calmAnimateContentSize(): Modifier = composed {
    if (LocalCalmMotion.current) Modifier else Modifier.animateContentSize()
}
