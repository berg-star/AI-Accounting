package com.example.aiaccounting.data.backup

import android.util.AtomicFile
import java.io.File

class RecoveryBackupStore(private val file: File) {
    fun exists() = file.exists() || File(file.path + ".bak").exists()
    fun write(json: String) {
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(json.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    fun read(): String = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
}
