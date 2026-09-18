package org.cwcc.open.geokori.ui.geo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cwcc.open.geokori.ui.material3.bottomsheet.FlexibleBottomSheet
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.FlexibleSheetValue
import org.cwcc.open.geokori.ui.material3.pluginBottomSheetConfig
import org.cwcc.open.geokori.ui.material3.rememberPluginSheetState
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point

/**
 * 显示地理要素详细信息
 */
@Composable
fun GeoDetailBottomSheet(
    detailInfo: GeoDetailInfo,
    title:String="信息",
    onDismiss: (() -> Unit)? = null,
    onBackPressed: (() -> Unit)? = null,
    apiUrl:String? = null,
)
{
  val pluginScreenConfig = remember {
    pluginBottomSheetConfig(
        sheetWidth = null,  // null 自动适配
        sheetHorizontalAlignment = Alignment.End,  // null 自动适配
        isModal = false,
        isDraggable = true,
        fullyExpandedRatio = 0.9f,
        intermediatelyExpandedRatio = 0.7f,
        hasSlightlyExpanded = false,
        hasIntermediatelyExpanded = true,
        initialValue = FlexibleSheetValue.IntermediatelyExpanded,
    )
  }
  val pluginScreenState = rememberPluginSheetState(pluginScreenConfig)

  FlexibleBottomSheet(
      modifier = Modifier.fillMaxWidth(),
      sheetState = pluginScreenState,
      onDismissRequest = { onDismiss?.invoke() },
      onBackPressed={onBackPressed?.invoke()}
  ) {
    TitleBar(title = title,onClose = { onDismiss?.invoke() },)
    val context = LocalContext.current
    apiUrl?.let { url ->
      val attachmentIsVideo = detailInfo.attachmentIsVideo()
      if (attachmentIsVideo!=null) {
        GeoDetailInfoAttachments(detailInfo,url,context)
      }
    }
    Spacer(modifier = Modifier.height(8.dp))
    GeoDetailContent(feature = detailInfo.getFeature(),  detailInfo.displayFields)
  }
}

@Composable
fun GeoDetailContent(feature: Feature<Geometry, JsonObject?>,displayFields:Map<String, String> = mapOf()) {
  val properties = feature.properties ?: return
  Column(
      modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    displayFields.forEach { (label, key) ->
      val value = properties[key]?.jsonPrimitive?.content ?: ""
      if (value.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
              text = "$label：",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyMedium,
          )
          Text(
              text = value,
              fontWeight = FontWeight.Medium,
              style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }

    // 坐标信息
    (feature.geometry as? Point)?.coordinates?.let { coord ->
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
            text = "经度：",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "${coord.longitude}",
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
      }
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
            text = "纬度：",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "${coord.latitude}",
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
  }
}

/**
 * Dialog 中显示的内容（不使用 FlexibleBottomSheet）
 */
@Composable
fun DialogContent(
    detailInfo: GeoDetailInfo,
    title: String = "信息",
    displayFields: Map<String, String> = mapOf(),
    onDismiss: (() -> Unit)? = null
) {
  val feature = detailInfo.getFeature()
  val properties = feature.properties ?: return

  Column(
      modifier = Modifier
          .fillMaxWidth()
          .background(
              color = MaterialTheme.colorScheme.surface,
              shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
          )
          .padding(16.dp),
  ) {
    // 顶部拖拽指示器
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
      Box(
          modifier = Modifier
              .width(40.dp)
              .height(4.dp)
              .background(
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                  shape = RoundedCornerShape(2.dp),
              ),
      )
    }

    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(bottom = 16.dp),
    )

    // 显示字段
    displayFields.forEach { (label, key) ->
      val value = properties[key]?.jsonPrimitive?.content ?: ""
      if (value.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
              text = "$label：",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyMedium,
          )
          Text(
              text = value,
              fontWeight = FontWeight.Medium,
              style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }

    // 坐标信息
    (feature.geometry as? Point)?.coordinates?.let { coord ->
      Row(
          modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
            text = "经度：",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "${coord.longitude}",
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
      }
      Row(
          modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
            text = "纬度：",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "${coord.latitude}",
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
  }
}
@Composable
fun TitleBar(
    title: String,
    onClose: () -> Unit,
    subtitle: String? = null,
    showDivider: Boolean = true,
    variant: TitleBarVariant = TitleBarVariant.Default
) {
  Surface(
      modifier = Modifier.fillMaxWidth(),
      color = when (variant) {
        TitleBarVariant.Primary -> MaterialTheme.colorScheme.primaryContainer
        TitleBarVariant.Surface -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surface
      },
      shape = MaterialTheme.shapes.medium,
  ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        // 左侧内容
        Column {
          Text(
              text = title,
              fontWeight = FontWeight.SemiBold,
              style = MaterialTheme.typography.headlineSmall,
              color = when (variant) {
                TitleBarVariant.Primary -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
              },
          )

          subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        // 右侧关闭按钮
        AnimatedCloseButton(
            onClose = onClose,
        )
      }

      // 分割线
      if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(top = 12.dp),
            thickness = DividerDefaults.Thickness,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        )
      }
    }
  }
}

enum class TitleBarVariant {
  Default,
  Surface,
  Primary
}
@Composable
private fun AnimatedCloseButton(
    onClose: () -> Unit
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val scale by animateFloatAsState(
      targetValue = if (isPressed) 0.85f else 1f,
      animationSpec = tween(100),
  )

  IconButton(
      onClick = onClose,
      modifier = Modifier
          .size(40.dp)
          .scale(scale)
          .background(
              color = if (isPressed) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
              } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
              },
              shape = CircleShape,
          ),
      colors = IconButtonDefaults.iconButtonColors(
          contentColor = if (isPressed) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      ),
      interactionSource = interactionSource,
  ) {
    Icon(
        imageVector = Icons.Default.Close,
        contentDescription = "关闭",
        modifier = Modifier.size(20.dp),
    )
  }
}
