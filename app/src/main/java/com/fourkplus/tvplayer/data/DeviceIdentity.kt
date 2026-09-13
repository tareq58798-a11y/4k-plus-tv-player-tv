package com.fourkplus.tvplayer.data

import android.content.Context
import java.security.MessageDigest

/** Stable per-install device identity shown to the user (Home screen, activation card) and sent
 *  to the activation server. Derived from ANDROID_ID, which is unique per device+app-signing-key
 *  and stable across reinstalls of this app on the same device. */
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
        return identity.take(6).joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }

    fun deviceKey(context: Context): String {
        val identity = rawIdentity(context)
        val value = identity.take(4).fold(0L) { result, byte ->
            (result shl 8) or (byte.toLong() and 0xFF)
        }
        return "%06d".format(value % 1_000_000L)
    }
}
