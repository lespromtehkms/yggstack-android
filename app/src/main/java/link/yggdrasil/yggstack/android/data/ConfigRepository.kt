package link.yggdrasil.yggstack.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "yggstack_config")

enum class ServiceStartMode(val value: String) {
    ALWAYS("always"),
    KEEP_STATE("keep_state"),
    DO_NOTHING("do_nothing");

    companion object {
        fun fromValue(value: String?): ServiceStartMode = when (value) {
            "stopped" -> DO_NOTHING
            else -> entries.firstOrNull { it.value == value } ?: DO_NOTHING
        }
    }
}

enum class UiMode(val value: String) {
    LIGHT("light"),
    PRO("pro");

    companion object {
        fun fromValue(value: String?): UiMode? = entries.firstOrNull { it.value == value }
    }
}

class ConfigRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private val HOST_PORT_REGEX = Regex("^[^:\\[\\]]+:\\d+$")
        private val PORT_ONLY_REGEX = Regex("^:\\d+$")
        private val DEFAULT_PORT_SUFFIX_REGEX = Regex("(:53)+$")
        private val CONFIG_SNAPSHOT = stringPreferencesKey("config_snapshot")
        private val PEERS_KEY = stringPreferencesKey("peers")
        private val PRIVATE_KEY = stringPreferencesKey("private_key")
        private val SOCKS_PROXY = stringPreferencesKey("socks_proxy")
        private val DNS_SERVER = stringPreferencesKey("dns_server")
        private val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        private val EXPOSE_MAPPINGS = stringPreferencesKey("expose_mappings")
        private val EXPOSE_ENABLED = booleanPreferencesKey("expose_enabled")
        private val FORWARD_MAPPINGS = stringPreferencesKey("forward_mappings")
        private val FORWARD_ENABLED = booleanPreferencesKey("forward_enabled")
        private val THEME_KEY = stringPreferencesKey("theme")
        private val USE_SYSTEM_COLORS_KEY = booleanPreferencesKey("use_system_colors")
        private val AUTOSTART_KEY = booleanPreferencesKey("autostart")
        private val AUTO_UPDATE_KEY = booleanPreferencesKey("auto_update")
        private val MULTICAST_BEACON = booleanPreferencesKey("multicast_beacon")
        private val MULTICAST_LISTEN = booleanPreferencesKey("multicast_listen")
        private val GROUP_PASSWORD_ENABLED = booleanPreferencesKey("group_password_enabled")
        private val GROUP_PASSWORD = stringPreferencesKey("group_password")
        private val LOG_LEVEL = stringPreferencesKey("log_level")
        private val CACHED_PEERS = stringPreferencesKey("cached_peers")
        private val MAX_BACKOFF_ENABLED = booleanPreferencesKey("max_backoff_enabled")
        private val YGGDRASIL_CONF_EXPANDED = booleanPreferencesKey("yggdrasil_conf_expanded")
        private val MULTICAST_EXPANDED = booleanPreferencesKey("multicast_expanded")
        private val MAX_BACKOFF = intPreferencesKey("max_backoff")
        private val LOGS_ENABLED = booleanPreferencesKey("logs_enabled")
        private val DISABLED_PEERS = stringPreferencesKey("disabled_peers")
        private val DIAGNOSTICS_TAB_KEY = intPreferencesKey("diagnostics_tab")
        private val DIAGNOSTICS_TAB_MIGRATED = booleanPreferencesKey("diagnostics_tab_ports_migrated")
        private val DIAGNOSTICS_TAB_PING_MIGRATED = booleanPreferencesKey("diagnostics_tab_ping_migrated")
        private val DIAGNOSTICS_TAB_ORDER = stringPreferencesKey("diagnostics_tab_order")
        private val PUBLIC_PEERS_CACHE = stringPreferencesKey("public_peers_cache")
        private val SORTED_PEERS_CACHE = stringPreferencesKey("sorted_peers_cache")
        private val LAST_EXTERNAL_IP = stringPreferencesKey("last_external_ip")
        private val COUNTRY_FILTER = stringSetPreferencesKey("country_filter")
        private val LANGUAGE_KEY = stringPreferencesKey("language")
        private val SUPPRESS_TRANSIT_WARNING = booleanPreferencesKey("suppress_transit_warning")
        private val APP_LINKS_DIALOG_SUPPRESSED = booleanPreferencesKey("app_links_dialog_suppressed")
        private val SERVICE_ON_APP_START_KEY = stringPreferencesKey("service_on_app_start")
        private val SERVICE_WAS_RUNNING_KEY = booleanPreferencesKey("service_was_running")
        private val PORTS_COMPACT_MODE = booleanPreferencesKey("ports_compact_mode")
        private val PEERS_SORT_MODE = stringPreferencesKey("peers_sort_mode")
        private val POWER_SAVE_ENABLED = booleanPreferencesKey("power_save_enabled")
        private val POWER_SAVE_IDLE_TIMEOUT = intPreferencesKey("power_save_idle_timeout")
        private val PING_TARGET_KEY = stringPreferencesKey("ping_target")
        private val PING_COUNT_KEY = intPreferencesKey("ping_count")
        private val UI_MODE_KEY = stringPreferencesKey("ui_mode")
        private val RTT_AUTO_CHECK_DONE = booleanPreferencesKey("rtt_auto_check_done")

        fun normalizeDnsServer(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isBlank()) return trimmed

            if (trimmed.startsWith("[")) {
                val closingBracketIndex = trimmed.indexOf(']')
                if (closingBracketIndex > 0) {
                    val host = trimmed.substring(0, closingBracketIndex + 1)
                    val remainder = trimmed.substring(closingBracketIndex + 1)

                    return when {
                        remainder.isEmpty() -> "$host:53"
                        remainder == ":53" -> "$host:53"
                        DEFAULT_PORT_SUFFIX_REGEX.matches(remainder) -> "$host:53"
                        PORT_ONLY_REGEX.matches(remainder) -> "$host$remainder"
                        else -> "$host:53"
                    }
                }
            }

            if (HOST_PORT_REGEX.matches(trimmed)) {
                return trimmed
            }

            return when {
                trimmed.contains(":") -> "[$trimmed]:53"
                else -> "$trimmed:53"
            }
        }
    }

    val configFlow: Flow<YggstackConfig> = context.dataStore.data.map { preferences ->
        preferences[CONFIG_SNAPSHOT]?.let { return@map ConfigSerializer.decode(it) }
        YggstackConfig(
            peers = preferences[PEERS_KEY]?.let {
                json.decodeFromString<List<String>>(it)
            } ?: emptyList(),
            privateKey = preferences[PRIVATE_KEY] ?: generatePrivateKey(),
            socksProxy = preferences[SOCKS_PROXY] ?: "127.0.0.1:1080",
            dnsServer = preferences[DNS_SERVER] ?: "[308:62:45:62::]:53",
            proxyEnabled = preferences[PROXY_ENABLED] ?: false,
            exposeMappings = preferences[EXPOSE_MAPPINGS]?.let {
                json.decodeFromString<List<ExposeMapping>>(it)
            } ?: emptyList(),
            exposeEnabled = preferences[EXPOSE_ENABLED] ?: false,
            forwardMappings = preferences[FORWARD_MAPPINGS]?.let {
                json.decodeFromString<List<ForwardMapping>>(it)
            } ?: ForwardPresets.defaults,
            forwardEnabled = preferences[FORWARD_ENABLED] ?: true,
            multicastBeacon = preferences[MULTICAST_BEACON] ?: false,
            multicastListen = preferences[MULTICAST_LISTEN] ?: true,
            groupPasswordEnabled = preferences[GROUP_PASSWORD_ENABLED] ?: false,
            groupPassword = preferences[GROUP_PASSWORD] ?: "",
            logLevel = preferences[LOG_LEVEL] ?: "error",
            cachedPeers = preferences[CACHED_PEERS]?.let {
                json.decodeFromString<List<CachedPeer>>(it)
            } ?: emptyList(),
            maxBackoffEnabled = preferences[MAX_BACKOFF_ENABLED] ?: true,
            maxBackoff = preferences[MAX_BACKOFF] ?: 5,
            disabledPeers = preferences[DISABLED_PEERS]?.let {
                json.decodeFromString<List<String>>(it)
            } ?: emptyList(),
            powerSaveEnabled = preferences[POWER_SAVE_ENABLED] ?: true,
            powerSaveIdleTimeoutSeconds = preferences[POWER_SAVE_IDLE_TIMEOUT] ?: 60
        )
    }

    suspend fun saveConfig(config: YggstackConfig) {
        context.dataStore.edit { preferences ->
            preferences[CONFIG_SNAPSHOT] = ConfigSerializer.encode(config)
        }
    }

    val themeFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[THEME_KEY] ?: "system"
    }

    suspend fun saveTheme(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = theme
        }
    }

    val useSystemColorsFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[USE_SYSTEM_COLORS_KEY] ?: false
    }

    suspend fun saveUseSystemColors(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[USE_SYSTEM_COLORS_KEY] = enabled
        }
    }

    val uiModeFlow: Flow<UiMode> = context.dataStore.data.map { preferences ->
        UiMode.fromValue(preferences[UI_MODE_KEY]) ?: defaultUiMode(preferences)
    }

    suspend fun resolveUiMode(): UiMode {
        val preferences = context.dataStore.data.first()
        UiMode.fromValue(preferences[UI_MODE_KEY])?.let { return it }
        val mode = defaultUiMode(preferences)
        context.dataStore.edit { it[UI_MODE_KEY] = mode.value }
        return mode
    }

    suspend fun saveUiMode(mode: UiMode) {
        context.dataStore.edit { preferences ->
            preferences[UI_MODE_KEY] = mode.value
        }
    }

    suspend fun consumeFirstLaunchRttCheck(): Boolean {
        var shouldRun = false
        context.dataStore.edit { preferences ->
            if (preferences[RTT_AUTO_CHECK_DONE] != true) {
                preferences[RTT_AUTO_CHECK_DONE] = true
                shouldRun = true
            }
        }
        return shouldRun
    }

    private fun defaultUiMode(preferences: Preferences): UiMode = UiMode.PRO

    val autostartFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTOSTART_KEY] ?: false
    }

    suspend fun saveAutostart(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTOSTART_KEY] = enabled
        }
    }

    val serviceOnAppStartFlow: Flow<ServiceStartMode> = context.dataStore.data.map { preferences ->
        ServiceStartMode.fromValue(preferences[SERVICE_ON_APP_START_KEY])
    }

    suspend fun saveServiceOnAppStart(mode: ServiceStartMode) {
        context.dataStore.edit { preferences ->
            preferences[SERVICE_ON_APP_START_KEY] = mode.value
        }
    }

    val serviceWasRunningFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SERVICE_WAS_RUNNING_KEY] ?: false
    }

    suspend fun saveServiceWasRunning(running: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SERVICE_WAS_RUNNING_KEY] = running
        }
    }

    val autoUpdateFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_UPDATE_KEY] ?: false
    }

    suspend fun saveAutoUpdate(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_UPDATE_KEY] = enabled
        }
    }

    val yggdrasilConfExpandedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[YGGDRASIL_CONF_EXPANDED] ?: true
    }

    val multicastExpandedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[MULTICAST_EXPANDED] ?: false
    }

    val countryFilterFlow: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[COUNTRY_FILTER] ?: emptySet()
    }

    suspend fun saveCountryFilter(countries: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[COUNTRY_FILTER] = countries
        }
    }

    suspend fun saveYggdrasilConfExpanded(expanded: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[YGGDRASIL_CONF_EXPANDED] = expanded
        }
    }

    suspend fun saveMulticastExpanded(expanded: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[MULTICAST_EXPANDED] = expanded
        }
    }

    val logsEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LOGS_ENABLED] ?: false
    }

    suspend fun saveLogsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LOGS_ENABLED] = enabled
        }
    }

    val portsCompactModeFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PORTS_COMPACT_MODE] ?: false
    }

    suspend fun savePortsCompactMode(compact: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PORTS_COMPACT_MODE] = compact
        }
    }

    val peersSortModeFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PEERS_SORT_MODE] ?: "default"
    }

    suspend fun savePeersSortMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[PEERS_SORT_MODE] = mode
        }
    }

    val diagnosticsTabFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[DIAGNOSTICS_TAB_KEY] ?: 0).coerceIn(0, 4)
    }

    suspend fun migrateDiagnosticsTabIfNeeded() {
        context.dataStore.edit { preferences ->
            var saved = preferences[DIAGNOSTICS_TAB_KEY] ?: 0
            if (preferences[DIAGNOSTICS_TAB_MIGRATED] != true) {
                if (saved == 2) saved = 3
                preferences[DIAGNOSTICS_TAB_MIGRATED] = true
            }
            if (preferences[DIAGNOSTICS_TAB_PING_MIGRATED] != true) {
                if (saved == 3) saved = 4
                preferences[DIAGNOSTICS_TAB_PING_MIGRATED] = true
            }
            preferences[DIAGNOSTICS_TAB_KEY] = saved
        }
    }

    suspend fun saveDiagnosticsTab(tabIndex: Int) {
        context.dataStore.edit { preferences ->
            preferences[DIAGNOSTICS_TAB_KEY] = tabIndex
        }
    }

    val diagnosticsTabOrderFlow: Flow<List<Int>?> = context.dataStore.data.map { preferences ->
        preferences[DIAGNOSTICS_TAB_ORDER]?.let { stored ->
            val ids = stored.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (ids.size == 5 && ids.distinct().size == 5 && ids.all { it in 0..4 }) ids else null
        }
    }

    suspend fun saveDiagnosticsTabOrder(order: List<Int>) {
        context.dataStore.edit { preferences ->
            preferences[DIAGNOSTICS_TAB_ORDER] = order.joinToString(",")
        }
    }

    val pingTargetFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PING_TARGET_KEY] ?: ""
    }

    suspend fun savePingTarget(target: String) {
        context.dataStore.edit { preferences ->
            preferences[PING_TARGET_KEY] = target
        }
    }

    val pingCountFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PING_COUNT_KEY] ?: 5
    }

    suspend fun savePingCount(count: Int) {
        context.dataStore.edit { preferences ->
            preferences[PING_COUNT_KEY] = count
        }
    }

    val languageFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LANGUAGE_KEY] ?: "ru"
    }

    suspend fun saveLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = language
        }
    }

    val suppressTransitWarningFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SUPPRESS_TRANSIT_WARNING] ?: false
    }

    suspend fun saveSuppressTransitWarning(suppress: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SUPPRESS_TRANSIT_WARNING] = suppress
        }
    }

    val appLinksDialogSuppressedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[APP_LINKS_DIALOG_SUPPRESSED] ?: false
    }

    suspend fun saveAppLinksDialogSuppressed(suppressed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[APP_LINKS_DIALOG_SUPPRESSED] = suppressed
        }
    }

    private fun generatePrivateKey(): String {
        return ""
    }

    suspend fun getPublicPeersCache(): PublicPeersCache {
        val preferences = context.dataStore.data.first()
        return preferences[PUBLIC_PEERS_CACHE]?.let {
            json.decodeFromString<PublicPeersCache>(it)
        } ?: PublicPeersCache()
    }

    suspend fun savePublicPeersCache(cache: PublicPeersCache) {
        context.dataStore.edit { preferences ->
            preferences[PUBLIC_PEERS_CACHE] = json.encodeToString(cache)
        }
    }

    suspend fun hasPublicPeersCache(): Boolean {
        val cache = getPublicPeersCache()
        return cache.peers.isNotEmpty()
    }

    private suspend fun getSortedPeersCache(): SortedPeersCache {
        val preferences = context.dataStore.data.first()
        return preferences[SORTED_PEERS_CACHE]?.let {
            json.decodeFromString<SortedPeersCache>(it)
        } ?: SortedPeersCache()
    }

    suspend fun getSortedListForIp(externalIp: String): PeerListForIp? {
        val sortedCache = getSortedPeersCache()
        return sortedCache.sortedByIp[externalIp]
    }

    suspend fun saveSortedListForIp(externalIp: String, peerList: PeerListForIp) {
        val sortedCache = getSortedPeersCache()
        val updatedCache = sortedCache.copy(
            sortedByIp = sortedCache.sortedByIp + (externalIp to peerList)
        )
        context.dataStore.edit { preferences ->
            preferences[SORTED_PEERS_CACHE] = json.encodeToString(updatedCache)
        }
    }

    suspend fun getLastExternalIp(): String? {
        val preferences = context.dataStore.data.first()
        return preferences[LAST_EXTERNAL_IP]
    }

    suspend fun saveLastExternalIp(ip: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_EXTERNAL_IP] = ip
        }
    }

    suspend fun clearAllSortedLists() {
        context.dataStore.edit { preferences ->
            preferences.remove(SORTED_PEERS_CACHE)
        }
    }

    suspend fun clearAllPublicPeersData() {
        context.dataStore.edit { preferences ->
            preferences.remove(PUBLIC_PEERS_CACHE)
            preferences.remove(SORTED_PEERS_CACHE)
        }
    }
}
