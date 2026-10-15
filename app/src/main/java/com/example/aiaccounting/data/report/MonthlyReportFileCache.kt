package com.example.aiaccounting.data.report

import com.example.aiaccounting.data.remote.ReportProtocol
import com.example.aiaccounting.domain.ai.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.time.Instant
import java.time.YearMonth

/** App-private, reconstructible cache. No Key or raw ledger notes are persisted here. */
class MonthlyReportFileCache(private val root: File) : MonthlyReportCache {
    private fun file(month: YearMonth): File {
        require(Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(month.toString()))
        return File(root, "$month.json")
    }
    @Synchronized override fun read(month: YearMonth): SavedMonthlyReport? {
        val target = file(month)
        if (!target.exists()) return null
        require(target.length() <= 128 * 1024)
        val data = ReportProtocol.json(target.readText(Charsets.UTF_8))
        require(data.getInt("version") == 1 && data.getString("month") == month.toString())
        val fingerprint = data.getString("fingerprint"); require(Regex("[0-9a-f]{64}").matches(fingerprint))
        val generated = data.getString("generated_at"); Instant.parse(generated)
        return SavedMonthlyReport(month, fingerprint, generated, ReportProtocol.claims(data))
    }
    @Synchronized override fun write(report: SavedMonthlyReport) {
        check(root.isDirectory || root.mkdirs())
        val target = file(report.month)
        val temp = File(root, "${report.month}.tmp")
        val data = JSONObject().put("version", 1).put("month", report.month.toString()).put("fingerprint", report.fingerprint)
            .put("generated_at", report.generatedAt).put("conclusions", ReportProtocol.claimsJson(report.conclusions))
        try {
            temp.outputStream().use { stream -> stream.write(data.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } finally { temp.delete() }
    }
    private fun files() = root.listFiles().orEmpty().filter { it.isFile && Regex("[0-9]{4}-(0[1-9]|1[0-2])\\.json").matches(it.name) }
    @Synchronized override fun clear() { files().forEach { check(it.delete()) { "Could not clear cached report" } } }
}
