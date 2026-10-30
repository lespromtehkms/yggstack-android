package link.yggdrasil.yggstack.android.data

import kotlinx.serialization.Serializable

/**
 * Yggdrasil-specific settings included in a full backup
 */
@Serializable
data class YggdrasilSettings(
    val privateKey: String,
    val peers: List<String>,
    val multicastBeacon: Boolean,
    val multicastListen: Boolean,
    val listenEnabled: Boolean = false,
    val listenEntries: List<ListenEntry> = emptyList(),
    val groupPasswordEnabled: Boolean = false,
    val groupPassword: String = "",
    val allowedPublicKeysEnabled: Boolean = false,
    val allowedPublicKeys: List<String> = emptyList(),
    val maxBackoffEnabled: Boolean = true,
    val maxBackoff: Int = 5
)

@Serializable
data class BackupConfig(
    val yggdrasil: YggdrasilSettings? = null,
    val proxy: ProxySettings,
    val expose: ExposeSettings,
    val forward: ForwardSettings
) {
    companion object {
        fun fromYggstackConfig(
            config: YggstackConfig,
            includeYggdrasil: Boolean = false,
            includePrivateKey: Boolean = true
        ): BackupConfig {
            return BackupConfig(
                yggdrasil = if (includeYggdrasil) YggdrasilSettings(
                    privateKey = if (includePrivateKey) config.privateKey else "",
                    peers = config.peers,
                    multicastBeacon = config.multicastBeacon,
                    multicastListen = config.multicastListen,
                    listenEnabled = config.listenEnabled,
                    listenEntries = config.listenEntries,
                    groupPasswordEnabled = config.groupPasswordEnabled,
                    groupPassword = config.groupPassword,
                    allowedPublicKeysEnabled = config.allowedPublicKeysEnabled,
                    allowedPublicKeys = config.allowedPublicKeys,
                    maxBackoffEnabled = config.maxBackoffEnabled,
                    maxBackoff = config.maxBackoff
                ) else null,
                proxy = ProxySettings(
                    enabled = config.proxyEnabled,
                    socksAddress = config.socksProxy,
                    httpAddress = config.httpProxy,
                    socksEnabled = config.socksEnabled,
                    httpEnabled = config.httpEnabled,
                    dnsServer = config.dnsServer,
                    dnsServer2 = config.dnsServer2,
                    pacEnabled = config.pacEnabled,
                    pacIp = config.pacIp,
                    pacPort = config.pacPort,
                    pacAllTraffic = config.pacAllTraffic
                ),
                expose = ExposeSettings(
                    enabled = config.exposeEnabled,
                    mappings = config.exposeMappings
                ),
                forward = ForwardSettings(
                    enabled = config.forwardEnabled,
                    mappings = config.forwardMappings
                )
            )
        }

        fun fromToml(tomlString: String): Result<BackupConfig> = runCatching {
            var section = ""
            var recognizedSection = false

            // yggdrasil
            var ygPrivateKey = ""
            val ygPeers = mutableListOf<String>()
            var ygMulticastBeacon = false
            var ygMulticastListen = false
            var ygListenEnabled = false
            val ygListenEntries = mutableListOf<ListenEntry>()
            var ygGroupPasswordEnabled = false
            var ygGroupPassword = ""
            var ygAllowedPublicKeysEnabled = false
            val ygAllowedPublicKeys = mutableListOf<String>()
            var ygMaxBackoffEnabled = true
            var ygMaxBackoff = 5
            var hasYggdrasil = false

            // proxy
            var proxyEnabled = false
            var proxySocks = ""
            var proxyHttp = ""
            var proxySocksEnabled = true
            var proxyHttpEnabled = false
            var proxyDns = ""
            var proxyDns2 = ""
            var proxyPacEnabled = false
            var proxyPacIp = "127.0.0.1"
            var proxyPacPort = 8081
            var proxyPacAllTraffic = false

            // expose
            var exposeEnabled = false
            val exposeMappings = mutableListOf<ExposeMapping>()

            // forward
            var forwardEnabled = false
            val forwardMappings = mutableListOf<ForwardMapping>()

            var curLocalPort = 0
            var curLocalIp = "127.0.0.1"
            var curYggPort = 0
            var curRemotePort = 0
            var curRemoteIp = ""
            var curProtocol = Protocol.TCP
            var curShortName = ""
            var curNote = ""
            var inExposeMappingEntry = false
            var inForwardMappingEntry = false

            fun flushMapping() {
                if (inExposeMappingEntry) {
                    exposeMappings += ExposeMapping(
                        protocol = curProtocol,
                        localPort = curLocalPort,
                        localIp = curLocalIp,
                        yggPort = curYggPort,
                        shortName = curShortName,
                        note = curNote
                    )
                    inExposeMappingEntry = false
                }
                if (inForwardMappingEntry) {
                    forwardMappings += ForwardMapping(
                        protocol = curProtocol,
                        localIp = curLocalIp,
                        localPort = curLocalPort,
                        remoteIp = curRemoteIp,
                        remotePort = curRemotePort,
                        shortName = curShortName,
                        note = curNote
                    )
                    inForwardMappingEntry = false
                }
                curLocalPort = 0; curLocalIp = "127.0.0.1"; curYggPort = 0; curRemotePort = 0
                curRemoteIp = ""; curProtocol = Protocol.TCP; curShortName = ""; curNote = ""
            }

            for (rawLine in tomlString.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue

                if (line.startsWith("[[")) {
                    flushMapping()
                    val headerInner = line.removePrefix("[[").removeSuffix("]]").trim()
                    section = headerInner
                    when (headerInner) {
                        "expose.mappings"  -> { inExposeMappingEntry  = true; recognizedSection = true }
                        "forward.mappings" -> { inForwardMappingEntry = true; recognizedSection = true }
                    }
                    continue
                }

                if (line.startsWith("[")) {
                    flushMapping()
                    section = line.removePrefix("[").removeSuffix("]").trim()
                    when (section) {
                        "yggdrasil" -> { hasYggdrasil = true; recognizedSection = true }
                        "proxy", "expose", "forward" -> recognizedSection = true
                    }
                    continue
                }

                val eqIdx = line.indexOf('=')
                if (eqIdx < 0) continue
                val key = line.substring(0, eqIdx).trim()
                val rawVal = line.substring(eqIdx + 1).trim()

                fun strVal() = rawVal.removeSurrounding("\"").tomlUnescape()
                fun boolVal() = rawVal.trim() == "true"
                fun intVal()  = rawVal.trim().toInt()

                when (section) {
                    "yggdrasil" -> when (key) {
                        "privateKey"      -> ygPrivateKey       = strVal()
                        "peers"           -> ygPeers.addAll(parseTomlStringArray(rawVal))
                        "multicastBeacon" -> ygMulticastBeacon  = boolVal()
                        "multicastListen" -> ygMulticastListen  = boolVal()
                        "listenEnabled"   -> ygListenEnabled    = boolVal()
                        "listen"          -> ygListenEntries.addAll(
                            parseTomlStringArray(rawVal).mapNotNull(ListenEntry::fromUri))
                        "groupPasswordEnabled" -> ygGroupPasswordEnabled = boolVal()
                        "groupPassword"  -> ygGroupPassword = strVal()
                        "allowedPublicKeysEnabled" -> ygAllowedPublicKeysEnabled = boolVal()
                        "allowedPublicKeys" -> ygAllowedPublicKeys.addAll(parseTomlStringArray(rawVal))
                        "maxBackoffEnabled" -> ygMaxBackoffEnabled = boolVal()
                        "maxBackoff"      -> ygMaxBackoff        = intVal()
                    }
                    "proxy" -> when (key) {
                        "enabled"     -> proxyEnabled = boolVal()
                        "socksAddress" -> proxySocks   = strVal()
                        "httpAddress" -> proxyHttp    = strVal()
                        "socksEnabled" -> proxySocksEnabled = boolVal()
                        "httpEnabled"  -> proxyHttpEnabled  = boolVal()
                        "dnsServer"   -> proxyDns      = strVal()
                        "dnsServer2"  -> proxyDns2     = strVal()
                        "pacEnabled"      -> proxyPacEnabled = boolVal()
                        "pacIp"           -> proxyPacIp = strVal()
                        "pacPort"         -> proxyPacPort = intVal()
                        "pacAllTraffic"   -> proxyPacAllTraffic = boolVal()
                    }
                    "expose" -> when (key) {
                        "enabled" -> exposeEnabled = boolVal()
                    }
                    "forward" -> when (key) {
                        "enabled" -> forwardEnabled = boolVal()
                    }
                    "expose.mappings" -> when (key) {
                        "localPort" -> curLocalPort = intVal()
                        "localIp"   -> curLocalIp   = strVal()
                        "yggPort"   -> curYggPort   = intVal()
                        "protocol"  -> curProtocol  = Protocol.valueOf(strVal().uppercase())
                        "shortName" -> curShortName = strVal()
                        "note"   -> curNote   = strVal()
                    }
                    "forward.mappings" -> when (key) {
                        "localPort"  -> curLocalPort  = intVal()
                        "localIp"    -> curLocalIp    = strVal()
                        "remoteIp"   -> curRemoteIp   = strVal()
                        "remotePort" -> curRemotePort = intVal()
                        "protocol"   -> curProtocol   = Protocol.valueOf(strVal().uppercase())
                        "shortName"  -> curShortName  = strVal()
                        "note"    -> curNote    = strVal()
                    }
                }
            }
            flushMapping()
            require(recognizedSection) { "Not a yggstack backup file" }

            BackupConfig(
                yggdrasil = if (hasYggdrasil) YggdrasilSettings(
                    privateKey      = ygPrivateKey,
                    peers           = ygPeers,
                    multicastBeacon = ygMulticastBeacon,
                    multicastListen = ygMulticastListen,
                    listenEnabled   = ygListenEnabled,
                    listenEntries   = ygListenEntries,
                    groupPasswordEnabled = ygGroupPasswordEnabled,
                    groupPassword = ygGroupPassword,
                    allowedPublicKeysEnabled = ygAllowedPublicKeysEnabled,
                    allowedPublicKeys = ygAllowedPublicKeys,
                    maxBackoffEnabled = ygMaxBackoffEnabled,
                    maxBackoff      = ygMaxBackoff
                ) else null,
                proxy   = ProxySettings(enabled = proxyEnabled, socksAddress = proxySocks, httpAddress = proxyHttp, socksEnabled = proxySocksEnabled, httpEnabled = proxyHttpEnabled, dnsServer = proxyDns, dnsServer2 = proxyDns2,
                    pacEnabled = proxyPacEnabled, pacIp = proxyPacIp, pacPort = proxyPacPort, pacAllTraffic = proxyPacAllTraffic),
                expose  = ExposeSettings(exposeEnabled, exposeMappings),
                forward = ForwardSettings(forwardEnabled, forwardMappings)
            )
        }

        private fun parseTomlStringArray(raw: String): List<String> {
            val inner = raw.trim().removePrefix("[").removeSuffix("]")
            if (inner.isBlank()) return emptyList()
            val result = mutableListOf<String>()
            var i = 0
            while (i < inner.length) {
                when {
                    inner[i] == '"' -> {
                        val end = inner.indexOf('"', i + 1)
                        if (end < 0) break
                        result += inner.substring(i + 1, end).tomlUnescape()
                        i = end + 1
                    }
                    else -> i++
                }
            }
            return result
        }
    }

    fun toToml(): String = buildString {
        if (yggdrasil != null) {
            appendLine("[yggdrasil]")
            if (yggdrasil.privateKey.isNotBlank()) {
                appendLine("privateKey = \"${yggdrasil.privateKey.tomlEscape()}\"")
            }
            val peersToml = yggdrasil.peers.joinToString(", ") { "\"${it.tomlEscape()}\"" }
            appendLine("peers = [$peersToml]")
            appendLine("multicastBeacon = ${yggdrasil.multicastBeacon}")
            appendLine("multicastListen = ${yggdrasil.multicastListen}")
            val listenToml = yggdrasil.listenEntries.joinToString(", ") { "\"${it.toUri().tomlEscape()}\"" }
            appendLine("listen = [$listenToml]")
            appendLine("listenEnabled = ${yggdrasil.listenEnabled}")
            appendLine("groupPasswordEnabled = ${yggdrasil.groupPasswordEnabled}")
            appendLine("groupPassword = \"${yggdrasil.groupPassword.tomlEscape()}\"")
            appendLine("allowedPublicKeysEnabled = ${yggdrasil.allowedPublicKeysEnabled}")
            val allowedKeysToml = yggdrasil.allowedPublicKeys.joinToString(", ") { "\"${it.tomlEscape()}\"" }
            appendLine("allowedPublicKeys = [$allowedKeysToml]")
            appendLine("maxBackoffEnabled = ${yggdrasil.maxBackoffEnabled}")
            appendLine("maxBackoff = ${yggdrasil.maxBackoff}")
            appendLine()
        }

        appendLine("[proxy]")
        appendLine("enabled = ${proxy.enabled}")
        appendLine("socksAddress = \"${proxy.socksAddress.tomlEscape()}\"")
        appendLine("httpAddress = \"${proxy.httpAddress.tomlEscape()}\"")
        appendLine("socksEnabled = ${proxy.socksEnabled}")
        appendLine("httpEnabled = ${proxy.httpEnabled}")
        appendLine("dnsServer = \"${proxy.dnsServer.tomlEscape()}\"")
        if (proxy.dnsServer2.isNotBlank()) {
            appendLine("dnsServer2 = \"${proxy.dnsServer2.tomlEscape()}\"")
        }
        appendLine("pacEnabled = ${proxy.pacEnabled}")
        appendLine("pacIp = \"${proxy.pacIp.tomlEscape()}\"")
        appendLine("pacPort = ${proxy.pacPort}")
        appendLine("pacAllTraffic = ${proxy.pacAllTraffic}")
        appendLine()

        appendLine("[expose]")
        appendLine("enabled = ${expose.enabled}")
        expose.mappings.forEach { m ->
            appendLine()
            appendLine("[[expose.mappings]]")
            appendLine("localPort = ${m.localPort}")
            appendLine("localIp = \"${m.localIp.tomlEscape()}\"")
            appendLine("yggPort = ${m.yggPort}")
            appendLine("protocol = \"${m.protocol.name}\"")
            if (m.shortName.isNotBlank()) appendLine("shortName = \"${m.shortName.tomlEscape()}\"")
            if (m.note.isNotBlank()) appendLine("note = \"${m.note.tomlEscape()}\"")
        }
        appendLine()

        appendLine("[forward]")
        appendLine("enabled = ${forward.enabled}")
        forward.mappings.forEach { m ->
            appendLine()
            appendLine("[[forward.mappings]]")
            appendLine("localPort = ${m.localPort}")
            appendLine("localIp = \"${m.localIp.tomlEscape()}\"")
            appendLine("remoteIp = \"${m.remoteIp.tomlEscape()}\"")
            appendLine("remotePort = ${m.remotePort}")
            appendLine("protocol = \"${m.protocol.name}\"")
            if (m.shortName.isNotBlank()) appendLine("shortName = \"${m.shortName.tomlEscape()}\"")
            if (m.note.isNotBlank()) appendLine("note = \"${m.note.tomlEscape()}\"")
        }
    }

    fun applyTo(config: YggstackConfig): YggstackConfig {
        val base = config.copy(
            proxyEnabled = proxy.enabled,
            socksProxy = proxy.socksAddress,
            httpProxy = proxy.httpAddress,
            socksEnabled = proxy.socksEnabled,
            httpEnabled = proxy.httpEnabled,
            dnsServer = proxy.dnsServer,
            dnsServer2 = proxy.dnsServer2,
            pacEnabled = proxy.pacEnabled,
            pacIp = proxy.pacIp,
            pacPort = proxy.pacPort.coerceIn(1, 65535),
            pacAllTraffic = proxy.pacAllTraffic,
            exposeEnabled = expose.enabled,
            exposeMappings = expose.mappings,
            forwardEnabled = forward.enabled,
            forwardMappings = forward.mappings
        )
        return yggdrasil?.let {
            base.copy(
                privateKey = it.privateKey.ifBlank { config.privateKey },
                peers = it.peers,
                multicastBeacon = it.multicastBeacon,
                multicastListen = it.multicastListen,
                listenEnabled = it.listenEnabled,
                listenEntries = it.listenEntries,
                groupPasswordEnabled = it.groupPasswordEnabled,
                groupPassword = it.groupPassword,
                allowedPublicKeysEnabled = it.allowedPublicKeysEnabled,
                allowedPublicKeys = it.allowedPublicKeys,
                maxBackoffEnabled = it.maxBackoffEnabled,
                maxBackoff = it.maxBackoff.coerceIn(5, 30)
            )
        } ?: base
    }

    fun validate(): Result<Unit> {
        return try {
            expose.mappings.forEach { mapping ->
                if (mapping.localPort !in 1..65535) {
                    return@validate Result.failure(IllegalArgumentException("Invalid local port: ${mapping.localPort}"))
                }
                if (mapping.yggPort !in 1..65535) {
                    return@validate Result.failure(IllegalArgumentException("Invalid Yggdrasil port: ${mapping.yggPort}"))
                }
            }
            forward.mappings.forEach { mapping ->
                if (mapping.localPort !in 1..65535) {
                    return@validate Result.failure(IllegalArgumentException("Invalid local port: ${mapping.localPort}"))
                }
                if (mapping.remotePort !in 1..65535) {
                    return@validate Result.failure(IllegalArgumentException("Invalid remote port: ${mapping.remotePort}"))
                }
            }
            yggdrasil?.listenEntries?.forEach { entry ->
                if (entry.port !in 1..65535) {
                    return@validate Result.failure(IllegalArgumentException("Invalid listen port: ${entry.port}"))
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

@Serializable
data class ProxySettings(
    val enabled: Boolean,
    val socksAddress: String,
    val httpAddress: String = "",
    val socksEnabled: Boolean = true,
    val httpEnabled: Boolean = false,
    val dnsServer: String,
    val dnsServer2: String = "",
    val pacEnabled: Boolean = false,
    val pacIp: String = "127.0.0.1",
    val pacPort: Int = 8081,
    val pacAllTraffic: Boolean = false
)

@Serializable
data class ExposeSettings(
    val enabled: Boolean,
    val mappings: List<ExposeMapping> = emptyList()
)

@Serializable
data class ForwardSettings(
    val enabled: Boolean,
    val mappings: List<ForwardMapping> = emptyList()
)

private fun String.tomlEscape(): String = buildString {
    for (c in this@tomlEscape) when (c) {
        '\\' -> append("\\\\")
        '"'  -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(c)
    }
}

private fun String.tomlUnescape(): String {
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '\\' && i + 1 < length) {
            when (this[i + 1]) {
                '\\' -> sb.append('\\')
                '"'  -> sb.append('"')
                'n'  -> sb.append('\n')
                'r'  -> sb.append('\r')
                't'  -> sb.append('\t')
                else -> { sb.append(c); sb.append(this[i + 1]) }
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}
