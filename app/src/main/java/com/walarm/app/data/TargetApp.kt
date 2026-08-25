package com.walarm.app.data

/** Which messaging platform a watchlist entry listens to. */
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
            } catch (e: IllegalArgumentException) {
                ALL
            }
        }

        /**
         * The platform a package belongs to, or null when it is not a tracked platform.
         *
         * Null means "do not filter" rather than "matches nothing" — it covers the ADB
         * shell package used by `test_device.py`, which must be able to exercise every
         * contact regardless of its target app.
         */
        fun forPackage(packageName: String): TargetApp? = when {
            packageName.startsWith("com.whatsapp") -> WHATSAPP
            packageName.startsWith("com.instagram") -> INSTAGRAM
            else -> null
        }
    }
}
