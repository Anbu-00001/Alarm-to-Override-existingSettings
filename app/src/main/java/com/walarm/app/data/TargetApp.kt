package com.walarm.app.data

enum class TargetApp(val displayName: String) {
    ALL("All Apps"),
    WHATSAPP("WhatsApp"),
    INSTAGRAM("Instagram");

    fun matchesPackage(packageName: String): Boolean = when (this) {
        ALL -> true
        WHATSAPP -> packageName.startsWith("com.whatsapp")
        INSTAGRAM -> packageName.startsWith("com.instagram")
    }

    companion object {
        fun fromString(value: String?): TargetApp {
            if (value.isNullOrBlank()) return ALL
            return try {
                valueOf(value.trim().uppercase())
            } catch (e: Exception) {
                ALL
            }
        }
    }
}
