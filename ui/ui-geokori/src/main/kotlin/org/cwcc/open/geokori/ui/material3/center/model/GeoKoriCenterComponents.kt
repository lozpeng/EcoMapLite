package org.cwcc.open.geokori.ui.material3.center.model

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ==================== 搜索栏 V2 ====================
@Composable
fun SearchHeaderV2() {
  Column(modifier = Modifier.fillMaxWidth()) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
      Row(
          modifier = Modifier
              .fillMaxSize()
              .padding(horizontal = 16.dp),
          verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFF4285F4)),
            contentAlignment = Alignment.Center
        ) {
          Icon(
              imageVector = Icons.Default.Person,
              contentDescription = "个人中心",
              tint = Color.White,
              modifier = Modifier.size(20.dp)
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
        TextField(
            placeholder = { Text("请输入查询字符串") },
            state = rememberTextFieldState(),
            modifier = Modifier.semantics { contentType = ContentType.Username },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            )
        )
        IconButton(onClick = { }) {
          Icon(
              imageVector = Icons.Default.Mic,
              contentDescription = "语音搜索",
              tint = Color(0xFF4285F4),
              modifier = Modifier.size(24.dp)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
      Spacer(modifier = Modifier.width(8.dp))
      WeatherChip(icon = Icons.Default.WbSunny, text = "26°C 晴", bg = Color(0xFFFFF3E0))
      Spacer(modifier = Modifier.width(8.dp))
      WeatherChip(icon = Icons.Default.Air, text = "AQI 45 优", bg = Color(0xFFE8F5E9))
      Spacer(modifier = Modifier.width(8.dp))
      WeatherChip(icon = Icons.Default.Traffic, text = "路况畅通", bg = Color(0xFFE3F2FD))
      Spacer(modifier = Modifier.width(8.dp))
      WeatherChip(icon = Icons.Default.LocationSearching, text = "我在哪", bg = Color(0xFFE3F2FD), onClick = {

      })
    }
  }
}

@Composable
fun WeatherChip(icon: ImageVector,
                text: String,
                bg: Color,
                onClick: () -> Unit = {}) {
  Surface(
      shape = RoundedCornerShape(16.dp),
      color = bg.copy(alpha = 0.9f),
      modifier = Modifier.height(28.dp)
          .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,  // 去掉默认 ripple，保持 chip 的简洁感；如需 ripple 可删除这行
          onClick = onClick
      )
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(icon, null, modifier = Modifier.size(14.dp), tint = Color.DarkGray)
      Spacer(modifier = Modifier.width(4.dp))
      Text(text, fontSize = 12.sp, color = Color.DarkGray, fontWeight = FontWeight.Medium)
    }
  }
}

