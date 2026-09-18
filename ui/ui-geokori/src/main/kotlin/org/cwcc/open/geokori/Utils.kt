package org.cwcc.open.geokori

object Utils {
  fun isVideoType(type: String): Boolean {
    return type.endsWith("mp4", ignoreCase = true) ||
        type.endsWith("mov", ignoreCase = true) ||
        type.endsWith("avi", ignoreCase = true) ||
        type.endsWith("3gp", ignoreCase = true) ||
        type.endsWith("mkv", ignoreCase = true) ||
        type.endsWith("wmv", ignoreCase = true)
  }

  fun isImageType(type: String): Boolean {
    return type.endsWith("jpg", ignoreCase = true) ||
        type.endsWith("jpeg", ignoreCase = true) ||
        type.endsWith("png", ignoreCase = true) ||
        type.endsWith("gif", ignoreCase = true) ||
        type.endsWith("webp", ignoreCase = true) ||
        type.endsWith("bmp", ignoreCase = true)
  }
}
