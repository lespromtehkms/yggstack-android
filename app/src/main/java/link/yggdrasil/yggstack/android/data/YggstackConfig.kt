package link.yggdrasil.yggstack.android.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * Configuration model for Yggstack
 */
@Serializable
data class YggstackConfig(
    val peers: List<String> = emptyList(),
    val privateKey: String = "",
    val socksProxy: String = "",
    val httpProxy: String = "",
    val socksEnabled: Boolean = true,
    val httpEnabled: Boolean = false,
    val dnsServer: String = "",
    val dnsServer2: String = "",
    val proxyEnabled: Boolean = false,
    val pacEnabled: Boolean = false,
    val pacIp: String = "127.0.0.1",
    val pacPort: Int = 8081,
    val pacAllTraffic: Boolean = false,
    val exposeMappings: List<ExposeMapping> = emptyList(),
    val exposeEnabled: Boolean = false,
    val forwardMappings: List<ForwardMapping> = emptyList(),
    val forwardEnabled: Boolean = false,
    val multicastBeacon: Boolean = false,
    val multicastListen: Boolean = false,
    val listenEnabled: Boolean = false,
    val listenEntries: List<ListenEntry> = emptyList(),
    val logLevel: String = "info",
    val groupPasswordEnabled: Boolean = false,
    val groupPassword: String = "",
    val allowedPublicKeysEnabled: Boolean = false,
    val allowedPublicKeys: List<String> = emptyList(),
    val cachedPeers: List<CachedPeer> = emptyList(),
    val maxBackoffEnabled: Boolean = true,
    val maxBackoff: Int = 5,
    val disabledPeers: List<String> = emptyList(),
    val powerSaveEnabled: Boolean = false,
    val powerSaveIdleTimeoutSeconds: Int = 60,
    val powerSaveSleepOnPortsIdle: Boolean = true,
    val powerSaveWakeOnPortsActive: Boolean = true,
    val powerSaveSleepDuringScreenOff: Boolean = false,
    val powerSaveWakeOnScreenOn: Boolean = false
)

fun YggstackConfig.hasActiveExposedPorts(): Boolean =
    exposeEnabled && exposeMappings.any { it.enabled }

fun YggstackConfig.hasActiveListen(): Boolean =
    listenEnabled && listenEntries.isNotEmpty()

fun YggstackConfig.hasAlwaysOnInbound(): Boolean =
    hasActiveExposedPorts() || multicastBeacon || hasActiveListen()

fun YggstackConfig.hasWakeableTargets(): Boolean =
    (proxyEnabled && ((socksEnabled && socksProxy.isNotBlank()) ||
        (httpEnabled && httpProxy.isNotBlank()))) ||
        (forwardEnabled && forwardMappings.any { it.enabled })

@Serializable
data class CachedPeer(
    val uri: String,
    val discoverySource: String,
    val lastSeen: Long,
    val successCount: Int = 0,
    val failureCount: Int = 0
)

@Parcelize
@Serializable
data class ExposeMapping(
    val protocol: Protocol,
    val localPort: Int,
    val localIp: String = "127.0.0.1",
    val yggPort: Int,
    val shortName: String = "",
    val note: String = "",
    val enabled: Boolean = true
) : Parcelable

@Parcelize
@Serializable
data class ForwardMapping(
    val protocol: Protocol,
    val localIp: String,
    val localPort: Int,
    val remoteIp: String,
    val remotePort: Int,
    val shortName: String = "",
    val note: String = "",
    val enabled: Boolean = true
) : Parcelable

@Parcelize
@Serializable
enum class Protocol : Parcelable {
    TCP, UDP
}

@Parcelize
@Serializable
enum class ListenScheme : Parcelable {
    TCP, TLS, QUIC, WS;

    val uri: String get() = name.lowercase()
    val supportedByNg: Boolean get() = this == TCP || this == TLS
}

@Parcelize
@Serializable
data class ListenEntry(
    val scheme: ListenScheme,
    val ip: String,
    val port: Int
) : Parcelable {
    fun toUri(): String =
        "${scheme.uri}://" + (if (ip.contains(':')) "[$ip]" else ip) + ":$port"

    companion object {
        private val uriRegex = Regex("""^(tcp|tls|quic|ws)://(\[[^\]]+\]|[^:\s]+):(\d+)$""")

        fun fromUri(uri: String): ListenEntry? {
            val m = uriRegex.find(uri.trim()) ?: return null
            val scheme = ListenScheme.entries.firstOrNull { it.uri == m.groupValues[1] } ?: return null
            val port = m.groupValues[3].toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return ListenEntry(scheme, m.groupValues[2].removeSurrounding("[", "]"), port)
        }
    }
}

data class PeerDetail(
    val uri: String,
    val up: Boolean,
    val inbound: Boolean,
    val address: String? = null,
    val port: Long,
    val priority: Int,
    val cost: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val reconnects: Int = 0,
    val uptime: Double,
    val latency: Long
)

data class PortStatsDetail(
    val key: String,
    val kind: String,
    val listenAddr: String,
    val targetAddr: String,
    val activeConnections: Long,
    val totalConnections: Long,
    val rxBytes: Long,
    val txBytes: Long
) {
    val isTcp: Boolean get() = kind == "socks" || kind == "http" || kind == "local-tcp" || kind == "remote-tcp"

    val section: String
        get() = when (kind) {
            "remote-tcp", "remote-udp" -> "expose"
            "local-tcp", "local-udp" -> "forward"
            else -> "proxy"
        }
}
