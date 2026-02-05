package com.bgmonitoring.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val DATASTORE_NAME = "bgmonitoring_settings"

val Context.dataStore by preferencesDataStore(name = DATASTORE_NAME)

data class Settings(
    val targetPackage: String,
    val periodicPackages: Set<String>,
    val ignoreBatteryOpt: Boolean
)

class SettingsRepository(private val context: Context) {
    private val KEY_TARGET = stringPreferencesKey("target_package")
    private val KEY_PERIODIC_SET = stringPreferencesKey("periodic_packages_csv")
    private val KEY_IGNORE_BATTERY = booleanPreferencesKey("ignore_battery_opt")

    val settingsFlow: Flow<Settings> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            Settings(
                targetPackage = prefs[KEY_TARGET].orEmpty(),
                periodicPackages = prefs[KEY_PERIODIC_SET]
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    ?: emptySet(),
                ignoreBatteryOpt = prefs[KEY_IGNORE_BATTERY] ?: false
            )
        }

    suspend fun updateTarget(pkg: String) {
        context.dataStore.edit { it[KEY_TARGET] = pkg }
    }

    suspend fun setPeriodic(packageName: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PERIODIC_SET]
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.toMutableSet()
                ?: mutableSetOf()
            if (enabled) current.add(packageName) else current.remove(packageName)
            prefs[KEY_PERIODIC_SET] = current.joinToString(",")
        }
    }

    suspend fun setIgnoreBatteryOpt(granted: Boolean) {
        context.dataStore.edit { it[KEY_IGNORE_BATTERY] = granted }
    }
}
