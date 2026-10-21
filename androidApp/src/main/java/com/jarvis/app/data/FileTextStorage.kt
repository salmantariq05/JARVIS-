package com.jarvis.app.data

import com.jarvis.shared.data.TextStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Saves Jarvis's JSON files in private app storage (other apps can't read it). */
class FileTextStorage(private val root: File) : TextStorage {

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        File(root, key).takeIf { it.isFile }?.readText()
    }

    override suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        val target = File(root, key)
        target.parentFile?.mkdirs()
        // Write to a temp file, then swap it in, so a crash mid-write never corrupts your data.
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(value)
        if (!temp.renameTo(target)) {
            target.writeText(value)
            temp.delete()
        }
    }

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) { File(root, key).delete() }
    }

    override suspend fun keys(prefix: String): List<String> = withContext(Dispatchers.IO) {
        if (!root.exists()) return@withContext emptyList()
        root.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it.startsWith(prefix) }
            .toList()
    }
}
