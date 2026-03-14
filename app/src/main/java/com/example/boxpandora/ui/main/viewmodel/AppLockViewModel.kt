package com.example.boxpandora.ui.main.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.boxpandora.data.local.dao.UserPreferenceDao
import com.example.boxpandora.data.local.entity.UserPreference
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val PREF_APP_LOCK_MODE = "app_lock_mode"
private const val PREF_APP_LOCK_PIN_HASH = "app_lock_pin_hash"
private const val PREF_APP_LOCK_TIMEOUT = "app_lock_timeout"

enum class AppLockMode(val displayName: String) {
    NONE("Off"),
    DEVICE_CREDENTIAL("Phone lock"),
    PIN("4-digit passcode")
}

enum class AppLockTimeout(val displayName: String, val durationMillis: Long) {
    IMMEDIATELY("Immediately", 0L),
    SECONDS_15("After 15 seconds", 15_000L),
    MINUTE_1("After 1 minute", 60_000L),
    MINUTES_5("After 5 minutes", 5 * 60_000L)
}

data class AppLockSettings(
    val mode: AppLockMode = AppLockMode.NONE,
    val timeout: AppLockTimeout = AppLockTimeout.IMMEDIATELY,
    val hasPinConfigured: Boolean = false
) {
    val isEnabled: Boolean get() = mode != AppLockMode.NONE
    val statusLabel: String
        get() = when (mode) {
            AppLockMode.NONE -> "Off"
            AppLockMode.DEVICE_CREDENTIAL -> "Phone lock"
            AppLockMode.PIN -> "4-digit passcode"
        }
}

class AppLockViewModel(
    private val preferenceDao: UserPreferenceDao
) : ViewModel() {

    private val _settings = MutableStateFlow(AppLockSettings())
    val settings: StateFlow<AppLockSettings> = _settings.asStateFlow()

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _unlockError = MutableStateFlow<String?>(null)
    val unlockError: StateFlow<String?> = _unlockError.asStateFlow()

    private var pinHash: String? = null
    private var hasUnlockedSinceLaunch = true
    private var lastBackgroundAt: Long? = null

    init {
        viewModelScope.launch {
            preferenceDao.getAllPreferencesFlow().collect { prefs ->
                val values = prefs.associate { it.key to it.value }
                val mode = values[PREF_APP_LOCK_MODE]
                    ?.let { saved -> runCatching { AppLockMode.valueOf(saved) }.getOrNull() }
                    ?: AppLockMode.NONE
                val timeout = values[PREF_APP_LOCK_TIMEOUT]
                    ?.let { saved -> runCatching { AppLockTimeout.valueOf(saved) }.getOrNull() }
                    ?: AppLockTimeout.IMMEDIATELY
                pinHash = values[PREF_APP_LOCK_PIN_HASH]

                _settings.value = AppLockSettings(
                    mode = mode,
                    timeout = timeout,
                    hasPinConfigured = !pinHash.isNullOrBlank()
                )

                if (mode == AppLockMode.NONE) {
                    hasUnlockedSinceLaunch = true
                    _isLocked.value = false
                    _unlockError.value = null
                } else if (!hasUnlockedSinceLaunch) {
                    _isLocked.value = true
                }
            }
        }
    }

    fun onAppBackgrounded(now: Long = System.currentTimeMillis()) {
        if (_settings.value.isEnabled) {
            lastBackgroundAt = now
        }
    }

    fun onAppForegrounded(now: Long = System.currentTimeMillis()) {
        val currentSettings = _settings.value
        if (!currentSettings.isEnabled) return

        val shouldLock = !hasUnlockedSinceLaunch || lastBackgroundAt == null ||
            now - (lastBackgroundAt ?: now) >= currentSettings.timeout.durationMillis

        if (shouldLock) {
            _isLocked.value = true
            _unlockError.value = null
        }
    }

    fun unlockSuccess() {
        hasUnlockedSinceLaunch = true
        _isLocked.value = false
        _unlockError.value = null
    }

    fun unlockWithPin(pin: String): Boolean {
        val matches = verifyPin(pin)
        if (matches) {
            unlockSuccess()
        } else {
            _unlockError.value = "Incorrect passcode."
        }
        return matches
    }

    fun verifyPin(pin: String): Boolean {
        return hashPin(pin) == pinHash
    }

    fun clearUnlockError() {
        _unlockError.value = null
    }

    fun setLockTimeout(timeout: AppLockTimeout) {
        _settings.value = _settings.value.copy(timeout = timeout)
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_APP_LOCK_TIMEOUT, value = timeout.name))
        }
    }

    fun enableDeviceCredentialLock() {
        hasUnlockedSinceLaunch = true
        _settings.value = _settings.value.copy(mode = AppLockMode.DEVICE_CREDENTIAL)
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_APP_LOCK_MODE, value = AppLockMode.DEVICE_CREDENTIAL.name))
            preferenceDao.deleteByKey(PREF_APP_LOCK_PIN_HASH)
        }
        pinHash = null
        _isLocked.value = false
        _unlockError.value = null
    }

    fun enablePinLock(pin: String) {
        val nextHash = hashPin(pin)
        pinHash = nextHash
        hasUnlockedSinceLaunch = true
        _settings.value = _settings.value.copy(mode = AppLockMode.PIN, hasPinConfigured = true)
        viewModelScope.launch {
            preferenceDao.insert(UserPreference(key = PREF_APP_LOCK_MODE, value = AppLockMode.PIN.name))
            preferenceDao.insert(UserPreference(key = PREF_APP_LOCK_PIN_HASH, value = nextHash))
        }
        _isLocked.value = false
        _unlockError.value = null
    }

    fun disableLock() {
        hasUnlockedSinceLaunch = true
        pinHash = null
        _settings.value = _settings.value.copy(mode = AppLockMode.NONE, hasPinConfigured = false)
        _isLocked.value = false
        _unlockError.value = null
        viewModelScope.launch {
            preferenceDao.deleteByKey(PREF_APP_LOCK_MODE)
            preferenceDao.deleteByKey(PREF_APP_LOCK_PIN_HASH)
        }
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

class AppLockViewModelFactory(
    private val preferenceDao: UserPreferenceDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppLockViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppLockViewModel(preferenceDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}