package io.github.arnavdugad.metropulse.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.metropulse.core.data.AppBuildInfo
import io.github.arnavdugad.metropulse.core.data.update.UpdateManager
import io.github.arnavdugad.metropulse.core.data.update.UpdatePreferences
import io.github.arnavdugad.metropulse.core.data.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val manager: UpdateManager,
    private val prefs: UpdatePreferences,
    info: AppBuildInfo,
) : ViewModel() {

    val installedVersion: String = info.versionName
    val updatesEnabled: Boolean = info.updatesEnabled
    val state: StateFlow<UpdateState> = manager.state

    val autoCheck: StateFlow<Boolean> = prefs.autoCheck.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val lastCheckedAt: StateFlow<Long?> = prefs.lastCheckedAt.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _sheetVisible = MutableStateFlow(false)
    val sheetVisible: StateFlow<Boolean> = _sheetVisible.asStateFlow()

    /** True while we are waiting for the user to allow "install unknown apps" in system settings. */
    private val _awaitingInstallPermission = MutableStateFlow(false)
    val awaitingInstallPermission: StateFlow<Boolean> = _awaitingInstallPermission.asStateFlow()

    private var launchCheckDone = false

    /** Quiet check on app start; shows the sheet once per new version. */
    fun onAppStart() {
        if (!updatesEnabled || launchCheckDone) return
        launchCheckDone = true
        viewModelScope.launch {
            val result = manager.check(userInitiated = false)
            if (result is UpdateState.Available && !result.skipped && prefs.claimPrompt(result.release.version)) {
                _sheetVisible.value = true
            }
        }
    }

    /** "Check for updates" in Settings. Opens the sheet only when there is something to show. */
    fun checkNow() {
        viewModelScope.launch {
            val result = manager.check(userInitiated = true)
            if (result is UpdateState.Available || result is UpdateState.ReadyToInstall || result is UpdateState.Downloading) {
                _sheetVisible.value = true
            }
        }
    }

    /** From the "update available / ready" notification. */
    fun openFromNotification() {
        if (!updatesEnabled) return
        _sheetVisible.value = true
        if (manager.state.value is UpdateState.Idle) viewModelScope.launch { manager.check(userInitiated = true) }
    }

    fun dismissSheet() {
        _sheetVisible.value = false
        _awaitingInstallPermission.value = false
        manager.dismissError()
    }

    fun download() = manager.startDownload()

    fun cancelDownload() = manager.cancelDownload()

    fun install() {
        if (!manager.canRequestInstalls()) {
            _awaitingInstallPermission.value = true
            return
        }
        _awaitingInstallPermission.value = false
        manager.install()
    }

    /** Called from Activity.onResume: continue automatically once the permission is granted. */
    fun onResumed() {
        if (_awaitingInstallPermission.value && manager.canRequestInstalls()) install()
    }

    /** Re-reads the feed first: a release whose asset failed verification may have been re-uploaded. */
    fun retry() {
        if (manager.state.value !is UpdateState.Failed) return
        manager.dismissError()
        viewModelScope.launch {
            val result = manager.check(userInitiated = true)
            if (result is UpdateState.Available) manager.startDownload()
        }
    }

    fun skipVersion() {
        viewModelScope.launch {
            manager.skip()
            _sheetVisible.value = false
        }
    }

    fun setAutoCheck(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoCheck(enabled) }
    }
}
