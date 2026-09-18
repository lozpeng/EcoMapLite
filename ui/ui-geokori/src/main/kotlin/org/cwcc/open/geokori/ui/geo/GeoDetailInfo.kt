package org.cwcc.open.geokori.ui.geo

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.cwcc.open.geokori.Utils
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position
import uniffi.ferrostar.RecordingException

/**
 * 地理信息消息
 */
@Serializable
data class GeoDetailInfo(
    val layerId:String,
    @Serializable(with = PositionSerializer::class)
    val coordinate: Position,
    val properties: Map<String, String>,
    val featureJson:String,
    val displayFields:Map<String,String>
)
{
  fun toJsonString():String
  {
    return Json.encodeToString(this)
  }
  fun getFeature():Feature<Geometry, JsonObject?>{
    return Feature.fromJson(featureJson)
  }

  /**
   * 获取附件信息
   */
//  fun getAttachments(): String?{
//    return if(properties.containsKey("img_type"))
//      properties["img_type"]
//    else null
//  }
//
  /**
   * 判断附件是否未视频，
   */
  fun attachmentIsVideo(): Boolean? {
    // 安全获取并转换为 String
    val attTypes = properties["img_types"] as? String ?: return null
    if (attTypes.isBlank()) return null
    // 多个类型返回 false
    if (attTypes.contains(",")) return false
    // 单个类型判断
    return Utils.isVideoType(attTypes)
  }

  fun getAttachmentId():String?{
    val attIds = properties["att_ids"] as? String ?: return null
    if (attIds.isBlank()) return null
    return if (attIds.contains(",")) {
      attIds.split(",")[0].trim()
    } else {
      attIds.trim()
    }
  }
  fun getImgAttIds():List<String>{
    val attIds = properties["att_ids"] as? String ?: return emptyList()
    if (attIds.isBlank()) return emptyList()
    return if (attIds.contains(",")) {
      attIds.split(",").map { it.trim() }
    } else {
      listOf(attIds.trim())
    }
  }
//  fun getSNO():String?{
//    return if(properties.containsKey("rowid"))
//      properties["rowid"]
//    else null
//  }

  companion object {
    fun fromJsonString(jsonString: String): GeoDetailInfo {
      return Json.decodeFromString<GeoDetailInfo>(jsonString)
    }
  }
}


object PositionSerializer : KSerializer<Position> {
  override val descriptor: SerialDescriptor =
      PrimitiveSerialDescriptor("Position", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: Position) {
    encoder.encodeString("${value.latitude},${value.longitude}")
  }

  override fun deserialize(decoder: Decoder): Position {
    val string = decoder.decodeString()
    val parts = string.split(",")
    if (parts.size != 2) {
      throw RecordingException.SerializationException("Invalid Position format: $string")
    }
    return Position(
        parts[1].trim().toDouble(),
        parts[0].trim().toDouble()
    )
  }
}
