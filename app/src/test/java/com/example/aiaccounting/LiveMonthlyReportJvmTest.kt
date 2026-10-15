package com.example.aiaccounting

import com.example.aiaccounting.data.local.*
import com.example.aiaccounting.data.remote.*
import com.example.aiaccounting.data.repository.LedgerSnapshot
import com.example.aiaccounting.domain.ai.*
import com.example.aiaccounting.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** Three paid requests, explicitly enabled; synthetic records only. */
class LiveMonthlyReportJvmTest {
    @Test fun currentIncomeOnlyAndHistoricalReportsHaveVerifiableEvidence() = runBlocking {
        assumeTrue("Paid API tests require explicit opt-in",System.getenv("AI_ACCOUNTING_LIVE_INQUIRY")=="true")
        val path=System.getenv("AI_ACCOUNTING_LIVE_KEY_FILE").orEmpty()
        check(path.isNotBlank()) { "Provide a local credential file path" }
        val credential=File(path).readText(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        check(credential.isNotBlank() && credential.none { it.isWhitespace() }) { "The local credential must be one nonempty line" }
        val keys=object : ApiKeyStore {
            override fun read()=credential
            override fun write(key: String)=error("Read-only test credential")
            override fun clear()=error("Read-only test credential")
        }
        val completion=SiliconFlowCompletion(keys)
        val service=MonthlyReportGenerator { report ->
            val raw=completion.complete(ReportProtocol.request(report))
            try { ReportProtocol.parse(raw,report) }
            catch (e: AiFailure) {
                // This test sends only fixed synthetic data; never enable this logging in production.
                println("LIVE_MONTHLY_REPORT synthetic-response-on-failure=" + raw.take(6000))
                throw e
            }
        }
        val today=LocalDate.now(); val month=YearMonth.from(today); val last=month.minusMonths(1)
        val categories=listOf(CategoryEntity(1,EntryKind.EXPENSE,"餐饮","food","orange",0),CategoryEntity(2,EntryKind.EXPENSE,"交通","transport","blue",1),
            CategoryEntity(3,EntryKind.EXPENSE,"居住","home","blue",2),CategoryEntity(4,EntryKind.INCOME,"工资","work","green",0))
        fun e(id: Long, cents: Long, cat: Long, date: String, note: String)=EntryEntity(id,if(cat==4L) EntryKind.INCOME else EntryKind.EXPENSE,cents,cat,date,note,1,1)
        val a=month.atDay(1).toString(); val b=last.atDay(1).toString()
        val data=LedgerSnapshot(categories,listOf(e(1,180000,3,a,"本期房租"),e(2,4000,1,a,"小巷餐厅午餐"),e(3,2000,1,a,"牛肉面"),
            e(4,600,2,a,"地铁去公司"),e(5,3000,1,a,"晚饭"),e(6,1,1,a,"小额记录"),e(7,500000,4,a,"工资"),
            e(8,180000,3,b,"对照房租"),e(9,5000,1,b,"对照午饭"),e(10,1200,2,b,"对照地铁")))
        val historical=MonthlyReport.build(last,data,today)
        assertEquals(last.atEndOfMonth().toString(),historical.to)
        val oldClaims=timed("historical-month") { service.generate(historical) }
        historical.validateConclusions(oldClaims)
        assertEquals(186200L,historical.expense)
        val current=MonthlyReport.build(month,data,today)
        assertEquals(189601L,current.expense); assertEquals(500000L,current.income)
        val claims=timed("current-month") { service.generate(current) }
        current.validateConclusions(claims)
        assertTrue(claims.size in 3..5)
        assertTrue(claims.contains(ReportConclusion(ReportClaimKind.CATEGORY,3)))
        assertTrue(claims.contains(ReportConclusion(ReportClaimKind.TOP_EXPENSE,1)))
        claims.forEach { assertTrue(current.evidence(it).isNotEmpty()); assertTrue(current.conclusionText(it).isNotBlank()) }

        val income=MonthlyReport.build(month,data.copy(entries=data.entries.filter { it.kind==EntryKind.INCOME }),today)
        val incomeClaims=timed("income-only") { service.generate(income) }
        assertEquals(listOf(ReportConclusion(ReportClaimKind.OVERVIEW)),incomeClaims)
        assertEquals(0L,income.expense); assertEquals(500000L,income.net)

        assertEquals(10,data.entries.size)
    }

    private suspend fun <T> timed(phase: String, block: suspend () -> T): T {
        val start=System.nanoTime()
        return block().also { println("LIVE_MONTHLY_REPORT $phase elapsedMs=${(System.nanoTime()-start)/1_000_000} $it") }
    }
}
