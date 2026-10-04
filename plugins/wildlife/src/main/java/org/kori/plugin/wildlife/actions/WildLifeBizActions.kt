package org.kori.plugin.wildlife.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.ArtTrack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.ui.graphics.Color
import org.cwcc.open.geokori.ui.material3.center.model.QuickAction


fun defaultWildLifeActions(): List<QuickAction> = listOf(
    QuickAction(null, "动物", Color(0xFFE3F2FD), Color(0xFF1565C0)),
    QuickAction(null, "植物", Color(0xFFF3E5F5), Color(0xFF6A1B9A)),
    QuickAction(null, "鸟类", Color(0xFFFFF3E0), Color(0xFFEF6C00)),
    QuickAction(null, "致害", Color(0xFFFFFDE7), Color(0xFFF9A825)),
    QuickAction(null, "收容", Color(0xFFE8F5E9), Color(0xFF2E7D32)),
    QuickAction(null, "谱系", Color(0xFFFFEBEE), Color(0xFFC62828)),
    QuickAction(Icons.Default.LocalPolice, "执法", Color(0xFFFFF3E0), Color(0xFFEF6C00)),
    QuickAction(Icons.Default.Flag, "履约", Color(0xFFE0F2F1), Color(0xFF00695C)),
    QuickAction(Icons.Default.BugReport, "名录-动物", Color(0xFFE0F2F1), Color(0xFF00695C)),
    QuickAction(Icons.Filled.AcUnit, "名录-植物", Color(0xFFFFFDE7), Color(0xFF2E7D32)),
    QuickAction(Icons.Filled.ArtTrack, "名录-三有", Color(0xFFE0F2F1), Color(0xFF00695C)),
    QuickAction(null, "CITES附录", Color(0xFFE0F2F1), Color(0xFF00695C)),
)
