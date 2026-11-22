package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aicontrolcenter.app.ui.theme.Spacing

/** Centres content and stops it stretching past a comfortable reading width on wide screens. */
fun Modifier.readableWidth(): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = Spacing.maxContentWidth)
