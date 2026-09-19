package com.fourkplus.tvplayer.data

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

/** Stable per-install device identity shown to the user (Home screen, activation card) and sent
 *  to the activation server. Derived from ANDROID_ID, which is unique per device+app-signing-key
 *  and stable across reinstalls of this app on the same device.
 *
 *  Every number here is formatted against [Locale.ROOT], never the device's own locale. Kotlin's
 *  String.format uses the default locale, and a device set to Arabic renders digits as ٠١٢٣٤٥٦٧٨٩ -
 *  so the MAC and device key came out in Arabic-Indic numerals. They looked fine on the television
 *  and were unusable: nobody could read them out, nobody could type them into the dashboard, and
 *  the value sent to the activation server no longer matched the one stored against the account.
 *
 *  This is an identifier, not a number being shown to a reader. It must render identically in
 *  every language, and the same rule applies to every port of this app. */
object DeviceIdentity {
    private fun rawIdentity(context: Context): ByteArray {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ).orEmpty().ifBlank { "4k-plus-tv-player" }
        return MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray(Charsets.UTF_8))
    }

    fun mac(context: Context): String {
        val identity = rawIdentity(context)
        return identity.take(6).joinToString(":") { byte ->
            String.format(Locale.ROOT, "%02X", byte.toInt() and 0xFF)
        }
    }

    fun deviceKey(context: Context): String {
        val identity = rawIdentity(context)
        val value = identity.take(4).fold(0L) { result, byte ->
            (result shl 8) or (byte.toLong() and 0xFF)
        }
        return String.format(Locale.ROOT, "%06d", value % 1_000_000L)
    }
}
