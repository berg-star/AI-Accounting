package com.example.aiaccounting

import com.example.aiaccounting.data.local.*
import com.example.aiaccounting.data.remote.ReportProtocol
import com.example.aiaccounting.data.report.MonthlyReportFileCache
import com.example.aiaccounting.data.repository.LedgerSnapshot
import com.example.aiaccounting.domain.ai.*
import com.example.aiaccounting.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

class MonthlyReportTest {
    @get:Rule val temp = TemporaryFolder(File("build/tmp/report-tests").apply { mkdirs() })
    private val month = YearMonth.of(2024,3)
    private val today = LocalDate.of(2024,3,15)
    private val categories = listOf(CategoryEntity(1,EntryKind.EXPENSE,"餐饮","food","orange",0),
        CategoryEntity(2,EntryKind.EXPENSE,"交通","transport","blue",1,active=false), CategoryEntity(3,EntryKind.INCOME,"工资","work","green",0))
    private fun e(id: Long, cents: Long, cat: Long, date: String, note: String = "账目$id", deleted: Long? = null) =
        EntryEntity(id,if(cat == 3L) EntryKind.INCOME else EntryKind.EXPENSE,cents,cat,date,note,1,1,deleted)
    private val data = LedgerSnapshot(categories,listOf(e(1,15000,1,"2024-03-01"),e(2,3000,2,"2024-03-15"),
        e(3,10000,1,"2024-02-01"),e(4,6000,2,"2024-02-15"),e(5,50000,3,"2024-03-02","收入隐私备注"),
        e(6,999999,1,"2024-03-16","未来记录"),e(7,999999,1,"2024-03-10",deleted=1),e(8,999999,1,"2024-01-01","其他月份")))
    private fun report(data: LedgerSnapshot = this.data) = MonthlyReport.build(month,data,today)
    private fun valid(r: MonthlyReport = report()) = listOf(ReportConclusion(ReportClaimKind.OVERVIEW),
        ReportConclusion(ReportClaimKind.CATEGORY,r.categories.first().id),ReportConclusion(ReportClaimKind.TOP_EXPENSE,r.largest.first().id))
    private fun envelope(claims: List<ReportConclusion>) = JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop")
        .put("message",JSONObject().put("content",JSONObject().put("conclusions",ReportProtocol.claimsJson(claims)).toString())))).toString()

    @Test fun currentMonthEndsTodayHistoricalMonthIncludesLeapDayFutureAndEmptyStayLocal() {
        val r = report()
        assertEquals("2024-03-15",r.to); assertEquals(setOf(1L,2L,5L),r.rows.map { it.id }.toSet())
        assertEquals("2024-02-15",r.comparison!!.query.baselineToDate)
        assertEquals("2024-02-29",MonthlyReport.build(YearMonth.of(2024,2),data,today).to)
        val short = MonthlyReport.build(month,data,LocalDate.of(2024,3,31))
        assertEquals("2024-02-29",short.comparison!!.query.baselineToDate)
        val future = MonthlyReport.build(month.plusMonths(1),data,today)
        assertFalse(future.available); assertTrue(future.rows.isEmpty()); assertNull(future.comparison)
        val empty = report(LedgerSnapshot(categories,emptyList()))
        assertEquals(0L,empty.expense); assertEquals(0L,empty.income); assertTrue(empty.categories.isEmpty())
        empty.validateConclusions(listOf(ReportConclusion(ReportClaimKind.OVERVIEW)))
    }

    @Test fun totalsCategorySharesTopFiveAndNegativeNetUseExactLocalCents() {
        val r = report()
        assertEquals(18000L,r.expense); assertEquals(50000L,r.income); assertEquals(32000L,r.net)
        assertEquals("83.3",r.categories.first().percent); assertEquals("16.7",r.categories.last().percent)
        assertEquals(listOf(1L,2L),r.largest.map { it.id })
        val many = LedgerSnapshot(categories,(1L..8L).map { e(it,it*100,1,"2024-03-01") })
        val top = report(many)
        assertEquals(listOf(8L,7L,6L,5L,4L),top.largest.map { it.id }); assertEquals(-3600L,top.net)
        val incomeOnly = report(LedgerSnapshot(categories,listOf(data.entries.single { it.id == 5L })))
        incomeOnly.validateConclusions(listOf(ReportConclusion(ReportClaimKind.OVERVIEW)))
    }

    @Test fun conclusionsOpenActualRowsAndComparisonEvidenceContainsBothPeriods() {
        val r = report()
        assertEquals(setOf(1L,2L,5L),r.evidence(ReportConclusion(ReportClaimKind.OVERVIEW)).map { it.id }.toSet())
        assertEquals(listOf(1L),r.evidence(ReportConclusion(ReportClaimKind.CATEGORY,1)).map { it.id })
        assertEquals(setOf(2L,4L),r.evidence(ReportConclusion(ReportClaimKind.CATEGORY_CHANGE,2)).map { it.id }.toSet())
        val claims = valid() + ReportConclusion(ReportClaimKind.CATEGORY_CHANGE,2)
        assertEquals(claims,ReportProtocol.parse(envelope(claims),r))
        assertTrue(r.conclusionText(claims.last()).contains("少花 ¥ 30.00"))
        assertEquals(8,data.entries.size)
    }

    @Test fun modelCannotInventIdsUseWrongEvidenceOrOmitMainFacts() {
        val r = report()
        val good = valid()
        val invalid = listOf(good.drop(1),good + ReportConclusion(ReportClaimKind.CATEGORY,999),good + good.last(),
            listOf(ReportConclusion(ReportClaimKind.OVERVIEW)),good + ReportConclusion(ReportClaimKind.TOP_EXPENSE,5),
            good + ReportConclusion(ReportClaimKind.OVERVIEW,1),good + ReportConclusion(ReportClaimKind.CATEGORY_CHANGE,999))
        invalid.forEach { claims -> try { ReportProtocol.parse(envelope(claims),r); fail("Invalid evidence accepted") } catch (_: AiFailure) { } }
        listOf(envelope(good)+" trailing",envelope(good).replace("\"stop\"","\"length\""),
            envelope(good).replace("\\\"id\\\":1","\\\"id\\\":\\\"1\\\"")) .forEach { raw ->
            try { ReportProtocol.parse(raw,r); fail("Malformed report accepted") } catch (_: AiFailure) { }
        }
    }

    @Test fun requestSendsAggregatesAndOnlyTopExpenseNotesWithThinkingDisabled() {
        val more = data.copy(entries=data.entries + (20L..25L).map { e(it,1,1,"2024-03-01","小额私密备注$it") })
        val request = ReportProtocol.request(report(more))
        assertEquals(AiLimits.MODEL,request.getString("model")); assertFalse(request.getBoolean("enable_thinking"))
        val sent = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(5,sent.getJSONArray("top_expenses").length())
        assertEquals(3,sent.getJSONArray("required_conclusions").length())
        assertFalse(sent.toString().contains("小额私密备注20")); assertFalse(sent.toString().contains("收入隐私备注"))
        assertFalse(sent.toString().contains("未来记录")); assertFalse(sent.toString().contains("其他月份"))
        assertTrue(sent.has("comparison"))
    }

    @Test fun fingerprintsTrackRelevantEditsNamesDatesAndBaselineWithoutUnrelatedHistory() {
        val r = report()
        val changedNote = data.copy(entries=data.entries.map { if(it.id==1L) it.copy(note="改了用途") else it })
        assertNotEquals(r.fingerprint,report(changedNote).fingerprint)
        val baseline = data.copy(entries=data.entries.map { if(it.id==4L) it.copy(cents=7000) else it })
        assertNotEquals(r.fingerprint,report(baseline).fingerprint)
        assertNotEquals(r.fingerprint,report(data.copy(categories=categories.map { if(it.id==1L) it.copy(name="三餐") else it })).fingerprint)
        assertEquals(r.fingerprint,report(data.copy(entries=data.entries + e(999,1,1,"2020-01-01"))).fingerprint)
        assertNotEquals(r.fingerprint,MonthlyReport.build(month,data,today.plusDays(1)).fingerprint)
    }

    @Test fun localCacheSurvivesReopeningRejectsCorruptionAndKeepsEachGeneratedMonth() {
        val root = temp.newFolder()
        val cache = MonthlyReportFileCache(root)
        val r = report(); val saved = SavedMonthlyReport(month,r.fingerprint,"2026-10-15T00:00:00Z",valid())
        cache.write(saved)
        assertEquals(saved,MonthlyReportFileCache(root).read(month))
        assertFalse(File(root,"$month.json").readText().contains("账目1"))
        File(root,"$month.json").writeText("broken")
        try { cache.read(month); fail("Corrupt cache accepted") } catch (_: Exception) { }
        cache.write(saved)
        repeat(25) { i -> cache.write(saved.copy(month=month.minusMonths(i.toLong()))) }
        assertEquals(25,root.listFiles()!!.count { it.extension=="json" })
        cache.clear(); assertNull(cache.read(month)); assertTrue(root.listFiles()!!.isEmpty())
    }
}
