package com.bgmonitoring

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bgmonitoring.shizuku.ShizukuHelper
import com.bgmonitoring.ui.MainViewModel
import com.bgmonitoring.ui.MainViewModel.AnalyzeState
import com.bgmonitoring.ui.UiState
import com.bgmonitoring.ui.theme.BGMonitoringTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private val SHIZUKU_REQ = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BGMonitoringTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    viewModel.refreshStatus()
                    viewModel.loadInstalledApps()
                }
                MainScreen(
                    state = state,
                    onTargetChange = viewModel::updateTarget,
                    onCollect = viewModel::collectNow,
                    onTogglePeriodic = viewModel::togglePeriodic,
                    onRequestBattery = { startActivity(viewModel.requestBatteryOptExemption()) },
                    onRequestShizuku = {
                        ShizukuHelper.requestPermission(SHIZUKU_REQ)
                        viewModel.refreshStatus()
                    },
                    onSelectApp = viewModel::selectPackage,
                    onBack = viewModel::backToList,
                    onAnalyze = viewModel::analyzeSelected
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    state: UiState,
    onTargetChange: (String) -> Unit,
    onCollect: () -> Unit,
    onTogglePeriodic: (Boolean) -> Unit,
    onRequestBattery: () -> Unit,
    onRequestShizuku: () -> Unit,
    onSelectApp: (String) -> Unit,
    onBack: () -> Unit,
    onAnalyze: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        if (state.selectedPackage == null) {
            AppListScreen(
                modifier = Modifier.padding(inner),
                state = state,
                onSelectApp = onSelectApp,
                onRequestShizuku = onRequestShizuku,
                onRequestBattery = onRequestBattery
            )
        } else {
            AppDetailScreen(
                modifier = Modifier.padding(inner),
                state = state,
                onTargetChange = onTargetChange,
                onCollect = onCollect,
                onTogglePeriodic = { onTogglePeriodic(it) },
                onBack = onBack,
                onAnalyze = onAnalyze
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: Boolean, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Row {
            Text(if (value) "yes" else "no")
            action?.invoke()
        }
    }
}

@Composable
private fun AppListScreen(
    modifier: Modifier = Modifier,
    state: UiState,
    onSelectApp: (String) -> Unit,
    onRequestShizuku: () -> Unit,
    onRequestBattery: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("BGMonitoring v0.1", fontWeight = FontWeight.Bold)
        PermissionSummary(state, onRequestShizuku, onRequestBattery)
        Divider()
        Text("Установленные приложения:")
        LazyColumn {
            items(items = state.apps) { app ->
                val count = state.snapshotCounts[app.packageName] ?: 0
                var icon by remember(app.packageName) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(app.packageName) {
                    icon = try {
                        val dr = pm.getApplicationIcon(app.packageName)
                        dr.toBitmap(64, 64).asImageBitmap()
                    } catch (_: Exception) {
                        null
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectApp(app.packageName) }
                        .padding(vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) {
                            Image(
                                bitmap = icon!!,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(32.dp)
                                    .padding(end = 8.dp)
                            )
                        }
                        Column {
                            Text(app.label, fontWeight = FontWeight.Medium)
                            Text(app.packageName, color = androidx.compose.ui.graphics.Color.Gray)
                        }
                    }
                    Text("Snapshots: $count")
                }
                Divider()
            }
        }
    }
}

@Composable
private fun PermissionSummary(
    state: UiState,
    onRequestShizuku: () -> Unit,
    onRequestBattery: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusRow("Shizuku available", state.shizukuAvailable)
        StatusRow("Shizuku granted", state.shizukuGranted, action = {
            if (!state.shizukuGranted) TextButton(onClick = onRequestShizuku) { Text("Request") }
        })
        StatusRow("Ignore battery optimizations", state.batteryOptIgnored, action = {
            if (!state.batteryOptIgnored) TextButton(onClick = onRequestBattery) { Text("Request") }
        })
    }
}

@Composable
private fun AppDetailScreen(
    modifier: Modifier = Modifier,
    state: UiState,
    onTargetChange: (String) -> Unit,
    onCollect: () -> Unit,
    onTogglePeriodic: (Boolean) -> Unit,
    onBack: () -> Unit,
    onAnalyze: () -> Unit
) {
    val pkg = state.selectedPackage ?: return
    val count = state.snapshotCounts[pkg] ?: 0
    val periodicEnabled = state.periodicPackages.contains(pkg)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("App details", fontWeight = FontWeight.Bold)
        }
        Text("Package: $pkg", fontWeight = FontWeight.Medium)
        Text("Snapshots collected: $count")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = onCollect,
                enabled = state.targetPackage.isNotBlank() && state.shizukuGranted && !state.collectingNow
            ) {
                if (state.collectingNow) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Collecting…")
                    }
                } else {
                    Text("Collect snapshot now")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("15-min periodic")
                Switch(
                    checked = periodicEnabled,
                    onCheckedChange = { onTogglePeriodic(it) },
                    enabled = state.shizukuGranted
                )
            }
        }
        Button(
            onClick = onAnalyze,
            enabled = count > 0 && state.analysisState !is AnalyzeState.Running
        ) {
            if (state.analysisState is AnalyzeState.Running) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Analyze")
            }
        }
        Divider()
        AnalysisSection(
            state = state.analysisState,
            pkg = pkg,
            snapshotsPath = state.snapshotsPath,
            modifier = Modifier.weight(1f, fill = true)
        )
    }
}

@Composable
private fun AnalysisSection(state: AnalyzeState, pkg: String, snapshotsPath: String, modifier: Modifier = Modifier) {
    when (state) {
        AnalyzeState.Idle -> {}
        AnalyzeState.Running -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Analyzing snapshots…")
            }
        }

        is AnalyzeState.Error -> {
            Text("Analyze error: ${state.message}")
        }

        is AnalyzeState.Success -> {
            val result = state.result
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val scroll = rememberScrollState()
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = modifier.verticalScroll(scroll)
            ) {
                val start = fmt.format(Date(result.coverage.startTs))
                val end = fmt.format(Date(result.coverage.endTs))
                Text("Coverage: ${result.coverage.count} snapshots, $start – $end")
                Text("Bucket timeline:")
                result.timeline.entries.forEach { entry ->
                    Text("• ${entry.dirName}: ${entry.bucket}" + if (entry.bucketChanged) " (changed)" else "")
                }
                Text("Job stats:")
                result.timeline.entries.lastOrNull()?.let { last ->
                    Text("Total=${last.jobStats.total} run=${last.jobStats.running} pend=${last.jobStats.pending} wait=${last.jobStats.waiting} canc=${last.jobStats.cancelled} fin=${last.jobStats.finished}")
                    Text("Blocked: quota=${last.jobStats.blockedWithinQuota} idle=${last.jobStats.blockedIdle}")
                    if (last.jobStats.constraintsBlocked.isNotEmpty()) {
                        val top = last.jobStats.constraintsBlocked.entries.take(3)
                        Text("Top blocked constraints: " + top.joinToString { "${it.key}=${it.value}" })
                    }
                }
                result.timeline.entries.lastOrNull()?.quota?.let { q ->
                    Text("Quota hint: remainingTime=${q.timeMs ?: -1}ms remainingCount=${q.remainingCount ?: -1}")
                }
                val changes = result.timeline.entries.flatMap { it.appOpsChanges }
                if (changes.isEmpty()) {
                    Text("AppOps changes: none")
                } else {
                    Text("AppOps changes:")
                    changes.forEach { change ->
                        Text(
                            "• ${change.op}: ${change.from ?: "(new)"} -> ${change.to ?: "(removed)"}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                val latest = result.timeline.entries.lastOrNull()
                if (latest != null) {
                    val summaryPath = File(File(snapshotsPath, pkg), latest.dirName).resolve("summary.md")
                    if (summaryPath.exists()) {
                        Text("Latest summary (${latest.dirName}):", fontWeight = FontWeight.SemiBold)
                        val text = remember(summaryPath.path) { summaryPath.readText() }
                        Text(text)
                    } else {
                        Text("Summary file not found for latest snapshot.")
                    }
                }
            }
        }
    }
}
