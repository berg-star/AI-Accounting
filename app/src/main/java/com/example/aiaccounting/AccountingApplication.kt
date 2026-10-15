package com.example.aiaccounting

import android.app.Application
import com.example.aiaccounting.data.local.LedgerDatabase
import com.example.aiaccounting.data.repository.LedgerRepository
import com.example.aiaccounting.data.security.EncryptedApiKeyStore
import com.example.aiaccounting.data.remote.SiliconFlowTextAccounting

class AccountingApplication : Application() {
    val database by lazy { LedgerDatabase.open(this) }
    val repository by lazy { LedgerRepository(database) }
    val apiKeys by lazy { EncryptedApiKeyStore(this) }
    val textAccounting by lazy { SiliconFlowTextAccounting(apiKeys) }
}
