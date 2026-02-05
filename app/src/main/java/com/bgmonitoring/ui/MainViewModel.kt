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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.bgmonitoring.shizuku.ShizukuShell
import androidx.work.WorkManager

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
    val snapshotVersion: Int = 0
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = SettingsRepository(application)
    private val status = MutableStateFlow(readStatus())
    private val selected = MutableStateFlow<String?>(null)
    private val appsFlow = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val countsFlow = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val collecting = MutableStateFlow(false)
    private val snapshotVersion = MutableStateFlow(0)

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
        snapshotVersion.value = snapshotVersion.value + 1
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
            val labelMap = fetchLabels(packages)
            val list = packages.map { pkg ->
                InstalledApp(
                    label = labelMap[pkg] ?: pkg,
                    packageName = pkg
                )
            }
            appsFlow.value = list
            refreshCounts(packages)
        }
    }

    /**
     * Attempts to fetch app labels using package manager; if not accessible, falls back to pkg name.
     * Since we list only user apps (-3), labels should be accessible without extra perms.
     */
    private fun fetchLabels(packages: List<String>): Map<String, String> {
        return try {
            val pm = getApplication<Application>().packageManager
            packages.associateWith { pkg ->
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun selectPackage(pkg: String) {
        selected.value = pkg
        updateTarget(pkg)
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
        val packageName: String
    )
}
