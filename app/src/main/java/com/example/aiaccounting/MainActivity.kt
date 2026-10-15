package com.example.aiaccounting

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.aiaccounting.ui.AccountingApp
import com.example.aiaccounting.ui.LedgerViewModel
import com.example.aiaccounting.ui.theme.AIAccountingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIAccountingTheme {
                val ledger: LedgerViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        val app = application as AccountingApplication
                        LedgerViewModel(app.repository, createSavedStateHandle(), app.textAccounting, app.apiKeys)
                    }
                })
                AccountingApp(ledger)
            }
        }
    }
}
