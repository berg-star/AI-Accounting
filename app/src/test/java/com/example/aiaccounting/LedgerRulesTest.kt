package com.example.aiaccounting

import com.example.aiaccounting.data.local.CategoryEntity
import com.example.aiaccounting.data.local.EntryEntity
import com.example.aiaccounting.data.repository.LedgerSnapshot
import com.example.aiaccounting.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

class LedgerRulesTest {
    @Test fun moneyUsesExactCentsAndRejectsAmbiguousInput() {
        assertEquals(10L,Money.parse("0.10"))
        assertEquals(2500L,Money.parse("25"))
        assertEquals(2501L,Money.parse(" 25.01 "))
        assertEquals(Money.MAX_CENTS,Money.parse("999999999.99"))
        listOf("", "0", "0.00", "-1", "1.234", "1e3", "1,000", "NaN", "1000000000", ".5").forEach { assertNull(it,Money.parse(it)) }
        assertEquals("0.10",Money.input(10))
        assertEquals("−1,901.01",Money.format(-190101))
    }
    @Test fun monthlyTotalsExcludeDeletedAndOtherMonthsAndKeepArchivedCategories() {
        val category=CategoryEntity(1,EntryKind.EXPENSE,"旧餐饮","food","orange",0,active=false)
        fun entry(id:Long,kind:EntryKind,cents:Long,date:String,deletedAt:Long?=null)=EntryEntity(id,kind,cents,1,date,"",0,0,deletedAt)
        val snapshot=LedgerSnapshot(listOf(category),listOf(entry(1,EntryKind.EXPENSE,2500,"2026-10-14"),entry(2,EntryKind.EXPENSE,10,"2026-10-15"),entry(3,EntryKind.INCOME,200000,"2026-10-01"),entry(4,EntryKind.EXPENSE,99999,"2026-09-30"),entry(5,EntryKind.EXPENSE,9000,"2026-10-16",1)))
        val totals=snapshot.totals(YearMonth.of(2026,10))
        assertEquals(2510,totals.expense)
        assertEquals(200000,totals.income)
        assertEquals(197490,totals.net)
        assertEquals(3,snapshot.monthly(YearMonth.of(2026,10),1).size)
        assertEquals(5,snapshot.usage(1))
    }
    @Test fun dateValidationHandlesLeapDays() {
        assertTrue(validDate("2024-02-29"));assertFalse(validDate("2026-02-29"));assertFalse(validDate("2026-13-01"))
    }
}
