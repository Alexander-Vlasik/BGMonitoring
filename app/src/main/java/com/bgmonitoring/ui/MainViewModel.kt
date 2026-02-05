package com.bgmonitoring.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bgmonitoring.data.SettingsRepository
import com.bgmonitoring.shizuku.ShizukuHelper
import com.bgmonitoring.work.SnapshotWorker
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.bgmonitoring.shizuku.ShizukuShell
import androidx.work.WorkManager
import com.bgmonitoring.snapshot.AnalysisResult
import com.bgmonitoring.snapshot.SnapshotAnalyzer

data class UiState(
    val targetPackage: String = "",
    val periodicEnabled: Boolean = false,
    val periodicPackages: Set<String> = emptySet(),
    val shizukuAvailable: Boolean = false,
    val shizukuGranted: Boolean = false,
    val batteryOptIgnored: Boolean = false,
    val snapshotsPath: String = "",
    val apps: List<MainViewModel.InstalledApp> = emptyList(),
    val snapshotCounts: Map<String, Int> = emptyMap(),
    val selectedPackage: String? = null,
    val collectingNow: Boolean = false,
    val snapshotVersion: Int = 0,
    val analysisState: MainViewModel.AnalyzeState = MainViewModel.AnalyzeState.Idle
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = SettingsRepository(application)
    private val status = MutableStateFlow(readStatus())
    private val selected = MutableStateFlow<String?>(null)
    private val appsFlow = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val countsFlow = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val collecting = MutableStateFlow(false)
    private val snapshotVersion = MutableStateFlow(0)
    private val analysis = MutableStateFlow<AnalyzeState>(AnalyzeState.Idle)
    private val analyzer = SnapshotAnalyzer()

    val state: StateFlow<UiState> = repo.settingsFlow
        .combine(status) { settings, status ->
            val selectedPkg = selected.value ?: settings.targetPackage
            UiState(
                targetPackage = settings.targetPackage,
                periodicEnabled = selectedPkg.isNotBlank() && settings.periodicPackages.contains(selectedPkg),
                periodicPackages = settings.periodicPackages,
                shizukuAvailable = status.shizukuAvailable,
                shizukuGranted = status.shizukuGranted,
                batteryOptIgnored = status.batteryOptIgnored,
                snapshotsPath = status.snapshotsPath,
                apps = appsFlow.value,
                snapshotCounts = countsFlow.value,
                selectedPackage = selected.value
            )
        }
        .combine(appsFlow) { ui, apps -> ui.copy(apps = apps) }
        .combine(countsFlow) { ui, counts -> ui.copy(snapshotCounts = counts) }
        .combine(selected) { ui, sel -> ui.copy(selectedPackage = sel) }
        .combine(collecting) { ui, isCollecting -> ui.copy(collectingNow = isCollecting) }
        .combine(snapshotVersion) { ui, ver -> ui.copy(snapshotVersion = ver) }
        .combine(analysis) { ui, a -> ui.copy(analysisState = a) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    fun refreshStatus() {
        status.value = readStatus()
    }

    fun updateTarget(pkg: String) {
        viewModelScope.launch { repo.updateTarget(pkg.trim()) }
    }

    fun togglePeriodic(enable: Boolean) {
        viewModelScope.launch {
            val pkg = selected.value ?: state.value.targetPackage
            if (pkg.isBlank()) return@launch
            repo.setPeriodic(pkg, enable)
            if (enable && ShizukuHelper.hasPermission()) {
                SnapshotWorker.schedulePeriodic(getApplication(), pkg)
            } else {
                SnapshotWorker.cancel(getApplication(), pkg)
            }
        }
    }

    fun collectNow() {
        val pkg = state.value.targetPackage
        if (pkg.isBlank() || !ShizukuHelper.hasPermission()) return
        viewModelScope.launch(Dispatchers.IO) {
            collecting.value = true
            val id = SnapshotWorker.once(getApplication(), pkg)
            try {
                WorkManager.getInstance(getApplication())
                    .getWorkInfoByIdFlow(id)
                    .collect { info ->
                        if (info.state.isFinished) {
                            collecting.value = false
                            bumpCount(pkg)
                            return@collect
                        }
                    }
            } catch (_: Exception) {
                collecting.value = false
            }
        }
    }

    private fun bumpCount(pkg: String) {
        val updated = countsFlow.value.toMutableMap()
        updated[pkg] = countSnapshots(pkg)
        countsFlow.value = updated
        snapshotVersion.value += 1
        analysis.value = AnalyzeState.Idle
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!ShizukuHelper.hasPermission()) {
                appsFlow.value = emptyList()
                return@launch
            }
            val res = ShizukuShell.runCommand(listOf("pm", "list", "packages", "-3"))
            val packages: List<String> = res.stdout
                .lineSequence()
                .mapNotNull { line ->
                    line.removePrefix("package:").takeIf { it.isNotBlank() }
                }
                .distinct()
                .sorted()
                .toList()
            val pm = getApplication<Application>().packageManager
            val list = packages.map { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(info).toString()
                    InstalledApp(label = label, packageName = pkg, hasIcon = true)
                } catch (_: Exception) {
                    InstalledApp(label = pkg, packageName = pkg, hasIcon = false)
                }
            }
            appsFlow.value = list
            refreshCounts(packages)
        }
    }

    fun selectPackage(pkg: String) {
        selected.value = pkg
        updateTarget(pkg)
        analysis.value = AnalyzeState.Idle
    }

    fun backToList() {
        selected.value = null
        loadInstalledApps() // refresh list/counts after returning
    }

    private suspend fun refreshCounts(packages: List<String>) {
        val counts = packages.associateWith { pkg ->
            countSnapshots(pkg)
        }
        countsFlow.value = counts
    }

    private fun countSnapshots(pkg: String): Int {
        val root = File(getApplication<Application>().filesDir, "snapshots/$pkg")
        return root.listFiles()?.size ?: 0
    }

    fun requestBatteryOptExemption(): Intent {
        val ctx = getApplication<Application>()
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun requestShizukuPermission(requestCode: Int) {
        ShizukuHelper.requestPermission(requestCode)
    }

    fun analyzeSelected() {
        val pkg = selected.value ?: state.value.selectedPackage ?: return
        val root = File(getApplication<Application>().filesDir, "snapshots/$pkg")
        viewModelScope.launch(Dispatchers.IO) {
            analysis.value = AnalyzeState.Running
            val result = try {
                analyzer.analyze(root)
            } catch (e: Exception) {
                AnalysisResult.Error(e.message ?: "error")
            }
            analysis.value = when (result) {
                is AnalysisResult.Success -> AnalyzeState.Success(result)
                is AnalysisResult.NotEnoughData -> AnalyzeState.Error("Not enough snapshots to analyze")
                is AnalysisResult.Error -> AnalyzeState.Error(result.message)
            }
        }
    }

    sealed interface AnalyzeState {
        object Idle : AnalyzeState
        object Running : AnalyzeState
        data class Success(val result: AnalysisResult.Success) : AnalyzeState
        data class Error(val message: String) : AnalyzeState
    }

    private fun readStatus(): SystemStatus {
        val app = getApplication<Application>()
        val pm = app.getSystemService(android.os.PowerManager::class.java)
        return SystemStatus(
            shizukuAvailable = ShizukuHelper.isAvailable(),
            shizukuGranted = ShizukuHelper.hasPermission(),
            batteryOptIgnored = pm.isIgnoringBatteryOptimizations(app.packageName),
            snapshotsPath = app.filesDir.resolve("snapshots").absolutePath
        )
    }

    data class SystemStatus(
        val shizukuAvailable: Boolean,
        val shizukuGranted: Boolean,
        val batteryOptIgnored: Boolean,
        val snapshotsPath: String
    )

    data class InstalledApp(
        val label: String,
        val packageName: String,
        val hasIcon: Boolean
    )
}
