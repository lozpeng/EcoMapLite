package org.cwcc.open.geokori.ui.material3.bottomsheet.core

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun screenHeight(): Dp = LocalConfiguration.current.screenHeightDp.dp
