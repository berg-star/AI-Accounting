package com.example.aiaccounting

import android.app.Application
import com.example.aiaccounting.data.local.LedgerDatabase
import com.example.aiaccounting.data.repository.LedgerRepository

class AccountingApplication : Application() {
    val database by lazy { LedgerDatabase.open(this) }
    val repository by lazy { LedgerRepository(database) }
}
