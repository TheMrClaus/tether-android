package com.tether.app.ui.log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.tether.app.crash.CrashRecord
import com.tether.app.crash.CrashRecordHandler
import com.tether.app.crash.CrashRecordStore
import com.tether.app.crash.ProcessExit
import com.tether.app.crash.ProcessExits
import com.tether.app.protocol.LogEntry
import com.tether.app.protocol.model.AgentSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * ta-otgf: [LogDialog] with the device's own records: the last crash (the private file the crash
 * recorder keeps) and the system's recent process exits. Both are read on the IO dispatcher each
 * time the dialog opens (this is composed only while it is open, so a cleared record cannot come
 * back from a cache); Clear deletes the record on IO and the section follows.
 */
@Composable
fun DeviceLogDialog(
    entries: List<LogEntry>,
    sessions: List<AgentSession>,
    state: LogDialogState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    DeviceLogDialog(
        entries = entries,
        sessions = sessions,
        state = state,
        onRefresh = onRefresh,
        onDismiss = onDismiss,
        store = remember(context) { CrashRecordStore(CrashRecordHandler.fileIn(context.noBackupFilesDir)) },
        readExits = { ProcessExits.read(context) },
    )
}

@Composable
internal fun DeviceLogDialog(
    entries: List<LogEntry>,
    sessions: List<AgentSession>,
    state: LogDialogState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    store: CrashRecordStore,
    readExits: () -> List<ProcessExit>,
    io: CoroutineContext = Dispatchers.IO,
) {
    var crash by remember { mutableStateOf<CrashRecord?>(null) }
    var exits by remember { mutableStateOf<List<ProcessExit>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) {
        val read = withContext(io) { runCatching { store.read() }.getOrNull() to runCatching(readExits).getOrDefault(emptyList()) }
        crash = read.first
        exits = read.second
    }
    LogDialog(
        entries = entries,
        sessions = sessions,
        state = state,
        onRefresh = onRefresh,
        onDismiss = onDismiss,
        crash = crash,
        exits = exits,
        onClearCrash = {
            crash = null
            scope.launch(io) { runCatching { store.clear() } }
        },
    )
}
