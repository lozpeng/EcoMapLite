@file:OptIn(ExperimentalComposeUiApi::class)

package org.cwcc.open.geokori.ui.material3.bottomsheet.core

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * Adds paddings to accommodate the fully expanded size excluding the window insets.
 *
 * If you set [sheetState.flexibleSheetSize.fullyExpanded] to a value less than 1.0f,
 * the full content size may be hidden under the screen space and window insets (status + navigation) bars.
 * In such cases, you may need to add calculated paddings when using fully served content inside the bottom sheet.
 *
 * Note: When [FlexibleSheetState.containSystemBars] is true, system bar padding is not applied,
 * allowing the sheet to extend edge-to-edge (useful with enableEdgeToEdge()).
 */
public fun Modifier.sheetPaddings(sheetState: FlexibleSheetState): Modifier = composed {
  // When containSystemBars is true, the sheet should extend edge-to-edge without system bar padding.
  // This is useful when using enableEdgeToEdge() or WindowCompat.setDecorFitsSystemWindows(window, false).
  if (sheetState.containSystemBars) {
    return@composed this
  }

  val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
  val paddings =
    systemBarsPadding.calculateBottomPadding() + systemBarsPadding.calculateTopPadding()
  val availableHeight = screenHeight() * (1 - sheetState.flexibleSheetSize.fullyExpanded)
  val padding = availableHeight - paddings

  if (sheetState.currentValue == FlexibleSheetValue.FullyExpanded && padding.toPx() > 0) {
    Modifier.padding(bottom = availableHeight - paddings)
  } else {
    this
  }
}
