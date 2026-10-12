package com.gonibix.rx8.sync

import java.io.IOException
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class UploadSettings(val baseUrl: String? = null, val enabled: Boolean = false, val boundBaseUrl: String? = null)
data class UploadRuntimeStatus(val isRunning: Boolean = false, val message: String? = null, val error: String? = null)

interface UploadSettingsRepository {
    val settings: StateFlow<UploadSettings>
    suspend fun load()
    suspend fun save(baseUrl: String?, enabled: Boolean)
}

/** A server identity is permanent once a request can be leased, including after local trip deletion. */
internal interface UploadSettingsStorage {
    fun read(): UploadSettings
    fun write(settings: UploadSettings): Boolean
}

internal class PersistentUploadSettings(
    private val storage: UploadSettingsStorage,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onChanged: suspend (UploadSettings) -> Unit = {},
) : UploadSettingsRepository {
    private val mutex = Mutex()
    private var loaded = false
    private val mutableSettings = MutableStateFlow(UploadSettings())
    override val settings = mutableSettings.asStateFlow()

    override suspend fun load() = mutex.withLock { readIfNeeded() }

    override suspend fun save(baseUrl: String?, enabled: Boolean) = withContext(NonCancellable) { mutex.withLock {
        readIfNeeded()
        val canonical = UploadEndpoint.normalize(baseUrl)
        require(!enabled || canonical != null) { "An HTTPS server is required" }
        val bound = mutableSettings.value.boundBaseUrl
        require(canonical == null || bound == null || canonical == bound) { "The server identity cannot be changed" }
        val updated = UploadSettings(canonical, enabled, bound)
        persist(updated)
        onChanged(updated)
    } }

    /** Persist the binding before the caller can lease work or perform any request. */
    suspend fun bindForUpload(): UploadSettings? = mutex.withLock {
        readIfNeeded()
        val current = mutableSettings.value
        if (!current.enabled || current.baseUrl == null) return@withLock null
        if (current.boundBaseUrl == null) persist(current.copy(boundBaseUrl = current.baseUrl))
        check(mutableSettings.value.boundBaseUrl == current.baseUrl)
        mutableSettings.value
    }

    private suspend fun readIfNeeded() {
        if (loaded) return
        val stored = withContext(dispatcher) { storage.read() }
        val base = UploadEndpoint.normalize(stored.baseUrl)
        val bound = UploadEndpoint.normalize(stored.boundBaseUrl)
        check(!stored.enabled || base != null)
        check(base == null || bound == null || base == bound)
        mutableSettings.value = UploadSettings(base, stored.enabled, bound)
        loaded = true
    }

    private suspend fun persist(value: UploadSettings) = withContext(NonCancellable) {
        if (!withContext(dispatcher) { storage.write(value) }) throw IOException("Upload settings could not be saved")
        mutableSettings.value = value
    }
}

object UploadEndpoint {
    fun normalize(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(text.length <= 2_048 && text.none { it.code <= 32 || it.code >= 127 || it == '\\' }) { "Invalid HTTPS server" }
        val uri = try { URI(text) } catch (_: Exception) { throw IllegalArgumentException("Invalid HTTPS server") }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)) { "Invalid HTTPS server" }
        val path = uri.rawPath.orEmpty()
        // A base path is allowed; ambiguous encoded separators or traversal are not.
        require(!Regex("(?i)%(2f|5c|2e|00)").containsMatchIn(path) &&
            path.split('/').none { it == "." || it == ".." } && !path.contains("//")) { "Invalid HTTPS server" }
        val host = uri.host.lowercase(Locale.ROOT)
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        return "https://$host$port${path.trimEnd('/')}"
    }
}
