package com.jarvis.shared.data

import com.jarvis.shared.model.CallKind
import com.jarvis.shared.model.DayRecord
import com.jarvis.shared.model.JarvisSettings
import com.jarvis.shared.model.ScheduledCall
import com.jarvis.shared.voice.VoiceProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * Repository pattern: the ONE place that reads and writes Jarvis's data.
 * Screens and brains ask the repository; they never touch files directly.
 * The Mutex makes sure two coroutines can't write the same file at once.
 */
class JarvisRepository(private val storage: TextStorage) {
    private val mutex = Mutex()
    private val _version = MutableStateFlow(0)

    /** Bumps on every write so the UI knows to refresh. */
    val version: StateFlow<Int> = _version.asStateFlow()

    suspend fun settings(): JarvisSettings = mutex.withLock { load(SETTINGS, JarvisSettings.serializer()) { JarvisSettings() } }

    suspend fun updateSettings(change: (JarvisSettings) -> JarvisSettings): JarvisSettings = mutex.withLock {
        val updated = change(load(SETTINGS, JarvisSettings.serializer()) { JarvisSettings() })
        save(SETTINGS, JarvisSettings.serializer(), updated)
        updated
    }

    suspend fun day(date: LocalDate): DayRecord = mutex.withLock { loadDay(date) }

    suspend fun updateDay(date: LocalDate, change: (DayRecord) -> DayRecord): DayRecord = mutex.withLock {
        val updated = change(loadDay(date))
        save(dayKey(date), DayRecord.serializer(), updated)
        updated
    }

    /** Most recent days first (used for streaks and weekly stats). */
    suspend fun recentDays(limit: Int): List<DayRecord> = mutex.withLock {
        storage.keys(DAYS_PREFIX).sortedDescending().take(limit)
            .mapNotNull { key -> runCatching { LocalDate.parse(key.removePrefix(DAYS_PREFIX).removeSuffix(".json")) }.getOrNull() }
            .map { loadDay(it) }
    }

    suspend fun calls(): List<ScheduledCall> = mutex.withLock { loadCalls() }

    suspend fun addCall(kind: CallKind, at: LocalDateTime, label: String): ScheduledCall = mutex.withLock {
        val calls = loadCalls()
        val id = maxOf(FIRST_ONE_OFF_CALL_ID - 1, calls.maxOfOrNull { it.id } ?: 0) + 1
        val call = ScheduledCall(id, kind, at, label)
        save(CALLS, callsSerializer, (calls + call).sortedBy { it.at })
        call
    }

    suspend fun removeCalls(predicate: (ScheduledCall) -> Boolean): List<ScheduledCall> = mutex.withLock {
        val (removed, kept) = loadCalls().partition(predicate)
        if (removed.isNotEmpty()) save(CALLS, callsSerializer, kept)
        removed
    }

    suspend fun voiceProfile(): VoiceProfile? = mutex.withLock {
        storage.read(VOICE_PROFILE)?.let { runCatching { JarvisJson.decodeFromString(VoiceProfile.serializer(), it) }.getOrNull() }
    }

    suspend fun saveVoiceProfile(profile: VoiceProfile) = mutex.withLock {
        save(VOICE_PROFILE, VoiceProfile.serializer(), profile)
    }

    suspend fun deleteVoiceProfile() = mutex.withLock {
        storage.delete(VOICE_PROFILE)
        _version.value++
    }

    private suspend fun loadDay(date: LocalDate): DayRecord = load(dayKey(date), DayRecord.serializer()) { DayRecord(date) }

    private suspend fun loadCalls(): List<ScheduledCall> = load(CALLS, callsSerializer) { emptyList() }

    private suspend fun <T> load(key: String, serializer: KSerializer<T>, default: () -> T): T {
        val text = storage.read(key) ?: return default()
        return try {
            JarvisJson.decodeFromString(serializer, text)
        } catch (e: Exception) {
            // Keep a copy of the damaged file instead of silently losing it.
            storage.write("$key.corrupt", text)
            default()
        }
    }

    private suspend fun <T> save(key: String, serializer: KSerializer<T>, value: T) {
        storage.write(key, JarvisJson.encodeToString(serializer, value))
        _version.value++
    }

    private fun dayKey(date: LocalDate) = "$DAYS_PREFIX$date.json"

    companion object {
        /** IDs below this are reserved for the daily calls (see CallIds). */
        const val FIRST_ONE_OFF_CALL_ID = 100
        private const val SETTINGS = "settings.json"
        private const val CALLS = "calls.json"
        private const val VOICE_PROFILE = "voice/profile.json"
        private const val DAYS_PREFIX = "days/"
        private val callsSerializer = ListSerializer(ScheduledCall.serializer())
    }
}
