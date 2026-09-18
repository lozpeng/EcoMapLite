package org.cwcc.open.geokori.ui.material3.center.model

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


// ==================== QuickActionItem ====================
@Composable
fun QuickActionItem(
    action: QuickAction,
    onClick: () -> Unit = {}
) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // ✅ 使用 FloatingActionButton
    FloatingActionButton(
        onClick = onClick,
        containerColor = if (action.checked) action.checkedContainerColor else action.containerColor,
        contentColor = if (action.checked) action.checkedContentColor else action.contentColor,
        elevation = if (action.checked)
          FloatingActionButtonDefaults.elevation(8.dp)
        else
          FloatingActionButtonDefaults.elevation(2.dp),
        modifier = Modifier.size(48.dp)
    ) {
      if (action.icon != null) {
        Icon(
            imageVector = action.icon,
            contentDescription = action.label,
            modifier = Modifier.size(24.dp)
        )
      } else {
        Text(
            text = action.label.take(1),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
      }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = action.label,
        fontSize = 12.sp,
        color = if (action.checked) Color(0xFF4285F4) else Color(0xFF5F6368),
        fontWeight = if (action.checked) FontWeight.SemiBold else FontWeight.Normal,
    )
  }
}

// ==================== POI 列表项 ====================
@Composable
fun PoiListItemV2(poi: PoiItem, onClick: () -> Unit) {
  Card(
      shape = RoundedCornerShape(12.dp),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
      modifier = Modifier
          .fillMaxWidth()
          .clickable(onClick = onClick)
  ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
          modifier = Modifier
              .size(48.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(Color(0xFFE3F2FD)),
          contentAlignment = Alignment.Center
      ) {
        Icon(
            imageVector = when (poi.category) {
              "餐饮" -> Icons.Default.Restaurant
              "购物" -> Icons.Default.ShoppingCart
              "交通" -> Icons.Default.DirectionsTransit
              "医疗" -> Icons.Default.LocalHospital
              else -> Icons.Default.Place
            },
            contentDescription = null,
            tint = Color(0xFF4285F4),
            modifier = Modifier.size(24.dp)
        )
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
              text = poi.name,
              fontSize = 15.sp,
              fontWeight = FontWeight.SemiBold,
              color = Color(0xFF202124)
          )
          Spacer(modifier = Modifier.width(6.dp))
          if (poi.rating > 0) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFFFF9800)
            ) {
              Text(
                  text = "${poi.rating}",
                  fontSize = 11.sp,
                  color = Color.White,
                  modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                  fontWeight = FontWeight.Bold
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = poi.address,
            fontSize = 13.sp,
            color = Color(0xFF5F6368),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row {
          poi.tags.forEach { tag ->
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFFF1F3F4),
                modifier = Modifier.padding(end = 4.dp)
            ) {
              Text(
                  text = tag,
                  fontSize = 11.sp,
                  color = Color(0xFF5F6368),
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }
        }
      }

      Column(horizontalAlignment = Alignment.End) {
        Text(
            text = poi.distance,
            fontSize = 13.sp,
            color = Color(0xFF5F6368)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Icon(
            imageVector = Icons.Default.Directions,
            contentDescription = "导航",
            tint = Color(0xFF4285F4),
            modifier = Modifier.size(28.dp)
        )
      }
    }
  }
}

// ==================== POI 详情卡片 ====================
@Composable
fun PoiDetailCardV2(
    poi: PoiItem,
    onClose: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier
) {
  Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
      modifier = modifier
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.Top
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
              text = poi.name,
              fontSize = 18.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF202124)
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
              text = "${poi.category} · ${poi.rating}分 · ${poi.distance}",
              fontSize = 14.sp,
              color = Color(0xFF5F6368)
          )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
          Icon(Icons.Default.Close, null, tint = Color(0xFF5F6368))
        }
      }

      Spacer(modifier = Modifier.height(8.dp))
      Text(
          text = poi.address,
          fontSize = 14.sp,
          color = Color(0xFF5F6368)
      )

      Spacer(modifier = Modifier.height(12.dp))

      Row(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onNavigate,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f)
        ) {
          Icon(Icons.Default.Directions, null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("路线", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.width(12.dp))
        OutlinedButton(
            onClick = { },
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f)
        ) {
          Icon(Icons.Default.Phone, null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("电话", fontSize = 15.sp)
        }
      }
    }
  }
}

// ==================== 数据模型 ====================
data class QuickAction(
    val icon: ImageVector?=null,
    val label: String,
    val containerColor: Color,
    val contentColor: Color,
    var checked:Boolean =false,
    val checkedContainerColor: Color = Color(0xFFD81E06),  // checked 状态背景色
    val checkedContentColor: Color = Color.White           // checked 状态图标/文字颜色
)

data class PoiItem(
    val id: String,
    val name: String,
    val category: String,
    val rating: Float,
    val distance: String,
    val address: String,
    val tags: List<String>
)
///========少量快捷操作按钮
@Composable
fun CollapsedQuickActions(
    actions: List<QuickAction>,
    onActionClick: (QuickAction) -> Unit,
) {
  Row(
      modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp),
      horizontalArrangement = Arrangement.SpaceEvenly
  ) {
    actions.take(4).forEach { action ->
      QuickActionItem(action) { onActionClick(action) }
    }
  }
}
///========所有的快捷操作按钮，建议不要超过12个
@Composable
fun ExpandedQuickActions(
    actions: List<QuickAction>,
    onActionClick: (QuickAction) -> Unit,
) {
  // 按每行 4 个自动切分，支持任意数量
  val rows = actions.chunked(4)
  Column {
    rows.forEachIndexed { index, rowActions ->
      if (index > 0) {
        Spacer(modifier = Modifier.height(12.dp))
      }
      Row(
          modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.SpaceEvenly
      ) {
        rowActions.forEach { action ->
          QuickActionItem(
              action = action,
              onClick = { onActionClick(action) }
          )
        }
      }
    }
  }
}
