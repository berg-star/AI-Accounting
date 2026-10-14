package com.example.aiaccounting.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.aiaccounting.ui.category.*
import com.example.aiaccounting.ui.common.ErrorText
import com.example.aiaccounting.ui.entry.EntryScreen
import com.example.aiaccounting.ui.home.HomeScreen
import com.example.aiaccounting.ui.settings.SettingsScreen
import com.example.aiaccounting.ui.statistics.StatisticsScreen
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountingApp(viewModel: LedgerViewModel) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar=remember { SnackbarHostState() }
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current
    val backup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null)viewModel.exportBackup(context.contentResolver,uri) }
    LaunchedEffect(viewModel,lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.messages.collect { message ->
                val result=snackbar.showSnackbar(message.text,actionLabel=if(message.undoEntryId!=null) "撤销" else null,withDismissAction=true,duration=SnackbarDuration.Long)
                if(result==SnackbarResult.ActionPerformed && message.undoEntryId!=null)viewModel.undo(message.undoEntryId)
            }
        }
    }
    val sub=s.page in listOf(Page.ENTRY,Page.CATEGORIES,Page.CATEGORY_EDITOR)
    BackHandler(enabled=sub||s.page!=Page.HOME||s.filter!=null||s.busy) { viewModel.back() }
    val title=when(s.page) {
        Page.HOME -> "AI 记账"
        Page.STATISTICS -> "统计"
        Page.SETTINGS -> "设置"
        Page.ENTRY -> if(s.entry.id==null) "记一笔" else "编辑记录"
        Page.CATEGORIES -> "分类管理"
        Page.CATEGORY_EDITOR -> if(s.category.id==null) "新增分类" else "编辑分类"
    }
    Scaffold(
        containerColor=MaterialTheme.colorScheme.background,
        topBar={ TopAppBar(title={Text(title)},navigationIcon={if(sub)IconButton(onClick=viewModel::back,enabled=!s.busy){Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回")}},actions={if(s.page==Page.CATEGORIES)IconButton(onClick=viewModel::newCategory,enabled=!s.busy){Icon(Icons.Default.Add,"新增分类")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background)) },
        snackbarHost={ SnackbarHost(snackbar) },
        floatingActionButton={if(!s.loading&&s.data.categories.isNotEmpty()&&(s.page==Page.HOME||s.page==Page.STATISTICS))ExtendedFloatingActionButton(modifier=Modifier.testTag("add-entry").semantics { contentDescription="记一笔" },onClick={if(!s.busy)viewModel.newEntry()},icon={Icon(Icons.Default.Add,null)},text={Text("记一笔")},containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary)},
        bottomBar={
            if(s.page==Page.ENTRY||s.page==Page.CATEGORY_EDITOR) {
                Surface(color=MaterialTheme.colorScheme.surface,shadowElevation=3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        s.error?.let { ErrorText(it) }
                        Button(onClick=if(s.page==Page.ENTRY)viewModel::saveEntry else viewModel::saveCategory,enabled=!s.busy&&!s.loading,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)) {
                            if(s.busy)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.onPrimary) else Text(if(s.page==Page.ENTRY) "保存这笔记录" else "保存分类")
                        }
                    }
                }
            } else if(!sub) NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
                listOf(Triple(Page.HOME,Icons.Default.Home,"明细"),Triple(Page.STATISTICS,Icons.AutoMirrored.Filled.List,"统计"),Triple(Page.SETTINGS,Icons.Default.Settings,"设置")).forEach { (page,icon,label) ->
                    NavigationBarItem(selected=s.page==page,onClick={viewModel.navigate(page)},enabled=!s.busy,icon={Icon(icon,null)},label={Text(label)})
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if(s.busy&&s.page!=Page.ENTRY&&s.page!=Page.CATEGORY_EDITOR)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(s.loading)Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
            else if(s.data.categories.isEmpty()) Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) { ErrorText(s.error?:"账本未准备好");Button(onClick=viewModel::load){Text("重试")} }
            else {
                if(s.page!=Page.ENTRY&&s.page!=Page.CATEGORY_EDITOR)s.error?.let { Surface(color=MaterialTheme.colorScheme.errorContainer,modifier=Modifier.fillMaxWidth()){Text(it,color=MaterialTheme.colorScheme.onErrorContainer,modifier=Modifier.padding(16.dp))} }
                when(s.page) {
                    Page.HOME -> HomeScreen(s,viewModel::moveMonth,viewModel::setMonth,viewModel::editEntry,{viewModel.filterCategory(null)})
                    Page.STATISTICS -> StatisticsScreen(s,viewModel::moveMonth,viewModel::setMonth,viewModel::statisticsKind,{viewModel.filterCategory(it)})
                    Page.SETTINGS -> SettingsScreen(!s.busy,viewModel::manageCategories,{backup.launch("ai-accounting-${LocalDate.now()}.json")})
                    Page.ENTRY -> EntryScreen(s,viewModel::changeEntry,viewModel::entryKind,viewModel::manageCategories,viewModel::askDeleteEntry)
                    Page.CATEGORIES -> CategoryScreen(s,viewModel::categoryKind,viewModel::editCategory,viewModel::toggleSorting,viewModel::toggleArchived,viewModel::moveCategory,viewModel::newCategory)
                    Page.CATEGORY_EDITOR -> CategoryEditor(s,viewModel::changeCategory,viewModel::toggleCategoryActive,viewModel::askDeleteCategory)
                }
            }
        }
    }
    s.confirmation?.let { confirmation ->
        val title=when(confirmation) {
            Confirmation.DISCARD_ENTRY,Confirmation.DISCARD_CATEGORY -> "放弃未保存的修改？"
            Confirmation.DELETE_ENTRY -> "删除这笔记录？"
            Confirmation.DELETE_CATEGORY -> "删除这个分类？"
            Confirmation.STOP_USED_CATEGORY -> "这个分类已有记录"
        }
        val message=when(confirmation) {
            Confirmation.DISCARD_ENTRY,Confirmation.DISCARD_CATEGORY -> "返回后本次修改不会保存，继续编辑可保留输入。"
            Confirmation.DELETE_ENTRY -> "这笔记录将从明细和统计中移除，删除后可点“撤销”。"
            Confirmation.DELETE_CATEGORY -> "这个分类尚未使用，删除不会影响其他分类。"
            Confirmation.STOP_USED_CATEGORY -> "已有 ${s.data.usage(s.category.id?:0)} 笔历史记录。可以停用，保留原记录和统计。"
        }
        AlertDialog(onDismissRequest={if(!s.busy)viewModel.cancelConfirmation()},title={Text(title)},text={Text(message)},confirmButton={TextButton(onClick=viewModel::confirm,enabled=!s.busy){Text(when(confirmation){Confirmation.DISCARD_ENTRY,Confirmation.DISCARD_CATEGORY->"放弃修改";Confirmation.STOP_USED_CATEGORY->"停用分类";else->"确认删除"})}},dismissButton={TextButton(onClick=viewModel::cancelConfirmation,enabled=!s.busy){Text(if(confirmation==Confirmation.DISCARD_ENTRY||confirmation==Confirmation.DISCARD_CATEGORY) "继续编辑" else "取消")}})
    }
}
