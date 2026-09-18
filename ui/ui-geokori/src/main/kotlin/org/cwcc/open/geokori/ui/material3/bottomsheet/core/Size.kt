package org.cwcc.open.geokori.ui.material3.bottomsheet.core

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

@Composable
public fun Dp.toPx(): Float = with(LocalDensity.current) { this@toPx.toPx() }

@Composable
public fun Int.pxToDp(): Dp = with(LocalDensity.current) { this@pxToDp.toDp() }

