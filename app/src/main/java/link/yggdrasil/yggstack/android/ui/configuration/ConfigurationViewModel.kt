package link.yggdrasil.yggstack.android.ui.configuration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import link.yggdrasil.yggstack.android.data.*
import link.yggdrasil.yggstack.android.service.YggstackConfigParcelable
import link.yggdrasil.yggstack.android.service.YggstackService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Represents a mapping or peer pre-filled from a deep link, waiting to be shown in the Add dialog.
 */
sealed class PendingDeepLink {
    data class ExposeLink(val mapping: ExposeMapping) : PendingDeepLink()
    data class ForwardLink(val mapping: ForwardMapping) : PendingDeepLink()
    data class PeerLink(val peerUri: String) : PendingDeepLink()
}

/**
 * ViewModel for Configuration screen
 */
class ConfigurationViewModel(
    private val repository: ConfigRepository,
    private val context: Context
) : ViewModel() {

    private val _config = MutableStateFlow(YggstackConfig())
    val config: StateFlow<YggstackConfig> = _config.asStateFlow()

    private val _configLoaded = MutableStateFlow(false)
    val configLoaded: StateFlow<Boolean> = _configLoaded.asStateFlow()

    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _yggdrasilIp = MutableStateFlow<String?>(null)
    val yggdrasilIp: StateFlow<String?> = _yggdrasilIp.asStateFlow()

    private val _showPrivateKey = MutableStateFlow(false)
    val showPrivateKey: StateFlow<Boolean> = _showPrivateKey.asStateFlow()

    private var debouncedPersistJob: kotlinx.coroutines.Job? = null
    private val PERSIST_DEBOUNCE_MS = 500L

    private val _scrollPosition = MutableStateFlow(0 to 0)
    val scrollPosition: StateFlow<Pair<Int, Int>> = _scrollPosition.asStateFlow()

    private val _pendingDeepLink = MutableStateFlow<PendingDeepLink?>(null)
    val pendingDeepLink: StateFlow<PendingDeepLink?> = _pendingDeepLink.asStateFlow()

    private val _yggdrasilConfExpanded = MutableStateFlow(true)
    val yggdrasilConfExpanded: StateFlow<Boolean> = _yggdrasilConfExpanded.asStateFlow()

    private val _multicastExpanded = MutableStateFlow(false)
    val multicastExpanded: StateFlow<Boolean> = _multicastExpanded.asStateFlow()

    private val _suppressTransitWarning = MutableStateFlow(false)
    private val _showTransitTrafficWarning = MutableStateFlow(false)
    val showTransitTrafficWarning: StateFlow<Boolean> = _showTransitTrafficWarning.asStateFlow()

    private val _peerCountries = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerCountries: StateFlow<Map<String, String>> = _peerCountries.asStateFlow()

    private var yggstackService: YggstackService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            android.util.Log.d("ConfigViewModel", "onServiceConnected called, binder=$binder")
            val localBinder = binder as? YggstackService.YggstackBinder
            yggstackService = localBinder?.getService()
            serviceBound = true
            android.util.Log.d("ConfigViewModel", "Service bound successfully, service=$yggstackService")

            yggstackService?.let { service ->
                val isActuallyRunning = service.isRunning.value
                val hasIpAddress = service.yggdrasilIp.value != null
                
                if (isActuallyRunning && !hasIpAddress) {
                    android.util.Log.w("ConfigViewModel", "State desync detected: service reports running but no IP address")
                }
                
                _serviceState.value = when {
                    service.isPowerSaveIdle.value -> ServiceState.PowerSaving
                    isActuallyRunning -> ServiceState.Running
                    else -> ServiceState.Stopped
                }
                _yggdrasilIp.value = service.yggdrasilIp.value

                viewModelScope.launch {
                    combine(service.isRunning, service.isPowerSaveIdle) { running, idle -> running to idle }
                        .collect { (running, idle) ->
                            _serviceState.value = when {
                                idle -> ServiceState.PowerSaving
                                running -> ServiceState.Running
                                else -> ServiceState.Stopped
                            }
                        }
                }
                
                viewModelScope.launch {
                    service.isTransitioning.collect { transitioning ->
                        if (transitioning) {
                            if (_serviceState.value is ServiceState.Stopped || _serviceState.value is ServiceState.PowerSaving) {
                                _serviceState.value = ServiceState.Starting
                            } else if (_serviceState.value is ServiceState.Running) {
                                _serviceState.value = ServiceState.Stopping
                            }
                        }
                    }
                }
                
                viewModelScope.launch {
                    service.yggdrasilIp.collect { ip ->
                        _yggdrasilIp.value = ip
                    }
                }

                viewModelScope.launch {
                    service.generatedPrivateKey.collect { key ->
                        if (key != null && _config.value.privateKey.isBlank()) {
                            updateConfig(_config.value.copy(privateKey = key))
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            android.util.Log.d("ConfigViewModel", "onServiceDisconnected called")
            yggstackService = null
            _serviceState.value = ServiceState.Stopped
            _yggdrasilIp.value = null
        }
    }

    init {
        android.util.Log.d("ConfigViewModel", "ViewModel init, context=$context, hashCode=${this.hashCode()}")
        loadConfig()
        bindToService()

        viewModelScope.launch {
            repository.suppressTransitWarningFlow.collect { suppressed ->
                _suppressTransitWarning.value = suppressed
            }
        }

        viewModelScope.launch {
            repository.yggdrasilConfExpandedFlow.collect { expanded ->
                _yggdrasilConfExpanded.value = expanded
            }
        }
        viewModelScope.launch {
            repository.multicastExpandedFlow.collect { expanded ->
                _multicastExpanded.value = expanded
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (debouncedPersistJob?.isActive == true) {
            debouncedPersistJob?.cancel()
            kotlinx.coroutines.runBlocking {
                repository.saveConfig(_config.value)
            }
        }
        unbindFromService()
    }

    private fun bindToService() {
        android.util.Log.d("ConfigViewModel", "bindToService called, context=$context")
        val intent = Intent(context, YggstackService::class.java)
        serviceBound = try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            android.util.Log.e("ConfigViewModel", "bindService failed: ${e.message}")
            false
        }
        android.util.Log.d("ConfigViewModel", "bindService result: $serviceBound")
    }

    private fun unbindFromService() {
        if (serviceBound) {
            serviceBound = false
            try {
                context.unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private fun loadConfig() {
        viewModelScope.launch {
            repository.configFlow.collect { config ->
                _config.value = config
                _configLoaded.value = true
            }
        }
    }

    fun addPeer(peerUri: String) {
        if (peerUri.isNotBlank()) {
            val currentPeers = _config.value.peers.toMutableList()
            currentPeers.add(peerUri.trim())
            updateConfig(_config.value.copy(peers = currentPeers))
        }
    }

    fun removePeer(peerUri: String) {
        val currentPeers = _config.value.peers.toMutableList()
        currentPeers.remove(peerUri)
        updateConfig(_config.value.copy(peers = currentPeers))
    }

    fun updatePeer(oldPeer: String, newPeer: String) {
        val currentPeers = _config.value.peers.toMutableList()
        val index = currentPeers.indexOf(oldPeer)
        if (index != -1 && newPeer !in currentPeers) {
            currentPeers[index] = newPeer.trim()
            val currentDisabled = _config.value.disabledPeers.toMutableList()
            val disabledIndex = currentDisabled.indexOf(oldPeer)
            if (disabledIndex != -1) {
                currentDisabled[disabledIndex] = newPeer.trim()
                updateConfig(_config.value.copy(peers = currentPeers, disabledPeers = currentDisabled))
            } else {
                updateConfig(_config.value.copy(peers = currentPeers))
            }
        }
    }

    fun reorderPeers(newOrder: List<String>) {
        updateConfig(_config.value.copy(peers = newOrder))
    }

    fun refreshPeerCountries() {
        viewModelScope.launch {
            val cache = repository.getPublicPeersCache()
            _peerCountries.value = cache.peers.associate { it.uri to it.country }
        }
    }

    fun togglePeerEnabled(peer: String) {
        val currentDisabled = _config.value.disabledPeers.toMutableList()
        if (peer in currentDisabled) {
            currentDisabled.remove(peer)
            val newConfig = _config.value.copy(disabledPeers = currentDisabled)
            updateConfig(newConfig)
            if (_serviceState.value is ServiceState.Running) {
                yggstackService?.addLivePeer(peer)
                yggstackService?.updateLiveConfig(newConfig)
            }
        } else {
            currentDisabled.add(peer)
            val newConfig = _config.value.copy(disabledPeers = currentDisabled)
            updateConfig(newConfig)
            if (_serviceState.value is ServiceState.Running) {
                yggstackService
