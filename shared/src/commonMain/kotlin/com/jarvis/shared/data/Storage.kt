package com.jarvis.shared.data

import kotlinx.serialization.json.Json

/**
 * Where Jarvis keeps its files. Android saves to private app storage;
 * tests use [InMemoryTextStorage]. The repository never knows the difference
 * (that is the point of an interface).
 */
interface TextStorage {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun keys(prefix: String): List<String>
    suspend fun delete(key: String)
}

class InMemoryTextStorage : TextStorage {
    private val files = mutableMapOf<String, String>()
    override suspend fun read(key: String): String? = files[key]
    override suspend fun write(key: String, value: String) {
        files[key] = value
    }
    override suspend fun keys(prefix: String): List<String> = files.keys.filter { it.startsWith(prefix) }
    override suspend fun delete(key: String) {
        files.remove(key)
    }
}

/** Lenient JSON: old files keep loading after we add new fields. */
val JarvisJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}
