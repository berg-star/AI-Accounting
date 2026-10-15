package com.example.aiaccounting

import android.app.Application
import com.example.aiaccounting.data.local.LedgerDatabase
import com.example.aiaccounting.data.repository.LedgerRepository
import com.example.aiaccounting.data.security.EncryptedApiKeyStore
import com.example.aiaccounting.data.remote.SiliconFlowTextAccounting
import com.example.aiaccounting.data.remote.SiliconFlowCompletion
import com.example.aiaccounting.data.remote.SiliconFlowLedgerInquiry
import com.example.aiaccounting.data.remote.SiliconFlowMonthlyReport
import com.example.aiaccounting.data.report.MonthlyReportFileCache
import com.example.aiaccounting.data.backup.RecoveryBackupStore
import java.io.File

class AccountingApplication : Application() {
    val database by lazy { LedgerDatabase.open(this) }
    val repository by lazy { LedgerRepository(database) }
    val apiKeys by lazy { EncryptedApiKeyStore(this) }
    val textAccounting by lazy { SiliconFlowTextAccounting(apiKeys) }
    val ledgerInquiry by lazy { SiliconFlowLedgerInquiry(SiliconFlowCompletion(apiKeys)) }
    val monthlyReportGenerator by lazy { SiliconFlowMonthlyReport(SiliconFlowCompletion(apiKeys)) }
    val monthlyReportCache by lazy { MonthlyReportFileCache(File(filesDir, "monthly-reports")) }
    val recoveryBackups by lazy { RecoveryBackupStore(File(filesDir, "backup-recovery/before-last-restore.json")) }
}
