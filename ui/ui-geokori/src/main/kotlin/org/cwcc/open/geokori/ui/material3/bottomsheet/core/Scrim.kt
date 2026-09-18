package org.cwcc.open.geokori.ui.material3.bottomsheet.core

import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

@Composable
public fun Scrim(
  color: Color,
  onDismissRequest: () -> Unit,
  visible: Boolean,
) {
  if (color.isSpecified) {
    val alpha by animateFloatAsState(
      targetValue = if (visible) 1f else 0f,
      animationSpec = TweenSpec(),
      label = "Bottom Sheet Scrim",
    )
    val dismissSheet = if (visible) {
      Modifier
        .pointerInput(onDismissRequest) {
          detectTapGestures {
            onDismissRequest()
          }
        }
        .clearAndSetSemantics {}
    } else {
      Modifier
    }
    Canvas(
      Modifier
        .fillMaxSize()
        .then(dismissSheet),
    ) {
      drawRect(color = color, alpha = alpha)
    }
  }
}
