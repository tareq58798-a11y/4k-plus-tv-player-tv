package com.fourkplus.tvplayer.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore

/** Encrypted, on-device CRUD for the user's saved playlist sources and which one is active. */
internal class PlaylistSourceStore(context: Context) {
    private val preferences = openStore(context)

    // Sources only ever enter this store already validated — either through the manual/login UI's
    // own ApprovedServers.allows() check, or via the trusted device-activation backend, which
    // deliberately resolves to servers *outside* that allowlist (that's the point of activation).
    // Re-applying the allowlist here would silently drop legitimately-saved activation sources on
    // every read, so reads trust whatever was written rather than re-validating it.
    fun savedSources(): List<PlaylistInput> {
        val stored = preferences.getString("sources_json", null)
        if (!stored.isNullOrBlank()) {
            return runCatching {
                val array = JSONArray(stored)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        val kind = PlaylistKind.valueOf(item.getString("kind"))
                        add(
                            PlaylistInput(
                                name = item.getString("name"),
                                kind = kind,
                                address = item.getString("address"),
                                username = item.optString("username"),
                                password = item.optString("password")
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
        val legacy = legacySavedSource() ?: return emptyList()
        writeSources(listOf(legacy), legacy.sourceId())
        return listOf(legacy)
    }

    fun savedSource(): PlaylistInput? {
        val sources = savedSources()
        if (sources.isEmpty()) return null
        val activeId = preferences.getString("active_source_id", null)
        return sources.firstOrNull { it.sourceId() == activeId } ?: sources.first()
    }

    fun selectSavedSource(input: PlaylistInput) {
        require(savedSources().any { it.sourceId() == input.sourceId() }) { "Playlist is no longer saved." }
        writeActiveSource(input)
    }

    fun renameSavedSource(name: String) {
        val cleaned = name.trim()
        require(cleaned.isNotBlank()) { "Playlist name cannot be empty." }
        val active = savedSource() ?: throw IllegalStateException("No saved playlist.")
        val renamed = active.copy(name = cleaned)
        val updated = savedSources().map { if (it.sourceId() == active.sourceId()) renamed else it }
        writeSources(updated, renamed.sourceId())
        writeActiveSource(renamed)
    }

    fun saveSource(input: PlaylistInput) {
        val cleaned = input.copy(name = input.name.trim(), address = input.address.trim())
        val id = cleaned.sourceId()
        val updated = savedSources().filterNot { it.sourceId() == id } + cleaned
        writeSources(updated, id)
        writeActiveSource(cleaned)
    }

    /** Clears the active source's saved credentials/preferences. Does not touch cache files on disk — the caller owns that. */
    fun clearActiveSource() {
        val active = savedSource()
        val remaining = if (active == null) emptyList() else savedSources().filterNot { it.sourceId() == active.sourceId() }
        preferences.edit().remove("name").remove("kind").remove("address").remove("username").remove("password").apply()
        if (remaining.isEmpty()) {
            preferences.edit().remove("sources_json").remove("active_source_id").apply()
        } else {
            writeSources(remaining, remaining.first().sourceId())
            writeActiveSource(remaining.first())
        }
    }

    private fun legacySavedSource(): PlaylistInput? {
        val name = preferences.getString("name", null) ?: return null
        val kind = preferences.getString("kind", null)?.let {
            runCatching { PlaylistKind.valueOf(it) }.getOrNull()
        } ?: return null
        val address = preferences.getString("address", null) ?: return null
        return PlaylistInput(
            name = name,
            kind = kind,
            address = address,
            username = preferences.getString("username", "").orEmpty(),
            password = preferences.getString("password", "").orEmpty()
        )
    }

    private fun writeSources(sources: List<PlaylistInput>, activeId: String) {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(JSONObject().apply {
                put("name", source.name)
                put("kind", source.kind.name)
                put("address", source.address)
                put("username", source.username)
                put("password", source.password)
            })
        }
        preferences.edit().putString("sources_json", array.toString())
            .putString("active_source_id", activeId).apply()
    }

    private fun writeActiveSource(input: PlaylistInput) {
        preferences.edit().putString("active_source_id", input.sourceId())
            .putString("name", input.name.trim()).putString("kind", input.kind.name)
            .putString("address", input.address.trim()).putString("username", input.username)
            .putString("password", input.password).apply()
    }

    internal companion object {
        private const val FILE = "playlist_source"

        /** androidx.security's own name for the key it creates. Deleting it forces a fresh one. */
        private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

        /**
         * The encrypted store, or the nearest thing that works on this device.
         *
         * This used to be a bare call to EncryptedSharedPreferences.create in a field
         * initialiser, which meant any failure inside it took the whole app down before the first
         * frame - the object could not be constructed, so neither could the repository that owns
         * it, nor the view model that owns that.
         *
         * And it does fail. The library is androidx.security-crypto 1.1.0-alpha06, an alpha that
         * has since been abandoned, and its master key lives in the hardware keystore, whose
         * behaviour is the manufacturer's rather than Android's. The failures it throws -
         * "master key exists but is unusable", AEADBadTagException, a protobuf parse error on the
         * key file - are all well known and all device-specific. A key can also be invalidated
         * out from under the app by a restore, a clone, or an OEM's own maintenance, at which
         * point a working install starts crashing on launch and nothing the viewer does fixes it.
         *
         * So: try, and if that fails, throw away both halves of the state and try once more. The
         * saved playlist goes with it and the viewer has to activate again, which is a bad
         * afternoon rather than an app that will not open.
         *
         * If even that fails the store falls back to plain preferences. That is a real reduction
         * in protection and it is a deliberate one: what is kept here is a provider address and
         * login, the same thing the Tizen port already keeps in the clear because a web app has
         * no key store to reach for at all. An app that cannot be opened protects nothing.
         */
        fun openStore(context: Context): SharedPreferences {
            fun create(): SharedPreferences = EncryptedSharedPreferences.create(
                context, FILE,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            runCatching { return create() }.onFailure {
                Log.w("PlaylistSourceStore", "encrypted store unusable, rebuilding it", it)
            }

            // Both halves, because either can be the broken one: the key in the keystore, or the
            // file it was used to encrypt. Leaving one behind leaves the same mismatch.
            runCatching { context.deleteSharedPreferences(FILE) }
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .deleteEntry(MASTER_KEY_ALIAS)
            }

            runCatching { return create() }.onFailure {
                Log.w("PlaylistSourceStore", "encrypted store still unusable, storing in the clear", it)
            }

            return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }
}
