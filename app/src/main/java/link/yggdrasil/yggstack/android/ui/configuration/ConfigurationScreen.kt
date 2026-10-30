package link.yggdrasil.yggstack.android.ui.configuration

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.*
import link.yggdrasil.yggstack.android.ui.configuration.discovery.PeerDiscoveryScreen
import link.yggdrasil.yggstack.android.ui.configuration.discovery.PeerDiscoveryViewModel
import link.yggdrasil.yggstack.android.utils.CountryFlags
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(
    viewModel: ConfigurationViewModel,
    uiMode: UiMode,
    onUiModeChange: (UiMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val isPro = uiMode == UiMode.PRO
    val config by viewModel.config.collectAsStateWithLifecycle()
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val showPrivateKey by viewModel.showPrivateKey.collectAsStateWithLifecycle()
    val pendingDeepLink by viewModel.pendingDeepLink.collectAsStateWithLifecycle()
    val showTransitTrafficWarning by viewModel.showTransitTrafficWarning.collectAsStateWithLifecycle()
    val peerCountries by viewModel.peerCountries.collectAsStateWithLifecycle()

    val discoveryContext = LocalContext.current
    val discoveryRepository = remember { ConfigRepository(discoveryContext) }
    val discoveryViewModel: PeerDiscoveryViewModel = viewModel(
        factory = remember { PeerDiscoveryViewModel.Factory(discoveryRepository) }
    )

    LaunchedEffect(Unit) {
        discoveryViewModel.autoRttCheckOnFirstLaunch()
    }

    var editingPeer by remember { mutableStateOf<String?>(null) }
    var showPeerDialog by remember { mutableStateOf(false) }
    var peerPrefill by remember { mutableStateOf<String?>(null) }
    var showExposeDialog by remember { mutableStateOf(false) }
    var editingExposeMapping by remember { mutableStateOf<ExposeMapping?>(null) }
    var deepLinkExposePrefill by remember { mutableStateOf<ExposeMapping?>(null) }
    var showForwardDialog by remember { mutableStateOf(false) }
    var editingForwardMapping by remember { mutableStateOf<ForwardMapping?>(null) }
    var forwardPrefill by remember { mutableStateOf<ForwardMapping?>(null) }
    var showForwardPresetPicker by remember { mutableStateOf(false) }
    var showPeerDiscovery by remember { mutableStateOf(false) }
    var showListenScreen by remember { mutableStateOf(false) }
    var showGroupPassword by remember { mutableStateOf(false) }

    LaunchedEffect(pendingDeepLink) {
        when (val link = pendingDeepLink) {
            is PendingDeepLink.ExposeLink -> {
                onUiModeChange(UiMode.PRO)
                editingExposeMapping = null
                deepLinkExposePrefill = link.mapping
                showExposeDialog = true
                viewModel.consumePendingDeepLink()
            }
            is PendingDeepLink.ForwardLink -> {
                editingForwardMapping = null
                forwardPrefill = link.mapping
                showForwardDialog = true
                viewModel.consumePendingDeepLink()
            }
            is PendingDeepLink.PeerLink -> {
                editingPeer = null
                peerPrefill = link.peerUri
                showPeerDialog = true
                viewModel.consumePendingDeepLink()
            }
            null -> Unit
        }
    }

    LaunchedEffect(showPeerDiscovery, config.peers) {
        viewModel.refreshPeerCountries()
    }

    val savedScrollPosition = viewModel.scrollPosition.value
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollPosition.first,
        initialFirstVisibleItemScrollOffset = savedScrollPosition.second
    )

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    viewModel.saveScrollPosition(
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset
                    )
                }
            }
    }
    DisposableEffect(listState, viewModel) {
        onDispose {
            viewModel.saveScrollPosition(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
    }

    val isServiceRunning = serviceState is ServiceState.Running ||
        serviceState is ServiceState.PowerSaving ||
        serviceState is ServiceState.Stopping

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 62.dp)
        ) {
            item(key = "title") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .toggleable(
                            value = isPro,
                            onValueChange = { onUiModeChange(if (it) UiMode.PRO else UiMode.LIGHT) },
                            role = Role.Switch
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(if (isPro) R.string.ui_mode_pro else R.string.ui_mode_light),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isPro) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = isPro,
                        onCheckedChange = null,
                        modifier = Modifier.scale(0.8f)
                    )
                }
            }
            }
            item(key = "peers") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        text = stringResource(R.string.peers_section),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    val configLoaded by viewModel.configLoaded.collectAsStateWithLifecycle()
                    if (configLoaded && config.peers.isEmpty()) {
                        NoPeersHintCard(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    ReorderableColumn(
                        items = config.peers,
                        onReorder = { viewModel.reorderPeers(it) },
                        enabled = !isServiceRunning
                    ) { peer, isDragging ->
                        PeerItem(
                            peer = peer,
                            flag = peerCountries[peer]?.let { CountryFlags.flagEmoji(it) },
                            isEnabled = peer !in config.disabledPeers,
                            onToggleEnabled = { viewModel.togglePeerEnabled(peer) },
                            enabled = !isServiceRunning,
                            isDragging = isDragging,
                            onEdit = {
                                editingPeer = peer
                                showPeerDialog = true
                            }
                        )
                    }

                    if (!isServiceRunning) {
                        val rttCheckRunning by discoveryViewModel.isCancellable.collectAsStateWithLifecycle()
                        val rttProgress by discoveryViewModel.progress.collectAsStateWithLifecycle()
                        val fillFraction by animateFloatAsState(
                            targetValue = if (rttCheckRunning) rttProgress.coerceAtLeast(0.04f) else 0f,
                            animationSpec = tween(durationMillis = 300),
                            label = "rttCheckFill"
                        )
                        val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    editingPeer = null
                                    peerPrefill = null
                                    showPeerDialog = true
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.add_peer))
                            }

                            Button(
                                onClick = { showPeerDiscovery = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(ButtonDefaults.shape)
                                    .drawWithContent {
                                        drawContent()
                                        if (fillFraction > 0f) {
                                            drawRect(
                                                color = fillColor,
                                                size = Size(size.width * fillFraction, size.height)
                                            )
                                        }
                                    },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Icon(
                                    Icons.Default.ManageSearch,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.discover_peers))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            Spacer(modifier = Modifier.height(3.dp))
            }
            item(key = "multicast") {
            val multicastExpanded by viewModel.multicastExpanded.collectAsStateWithLifecycle()
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setMulticastExpanded(!multicastExpanded) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.multicast_discovery),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 8.dp)
                        )
                        Icon(
                            imageVector = if (multicastExpanded) Icons.Default.KeyboardArrowUp
                                else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }

                    if (multicastExpanded) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.multicast_discover),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Switch(
                                    checked = config.multicastListen,
                                    onCheckedChange = { viewModel.setMulticastListen(it) },
                                    enabled = !isServiceRunning,
                                    modifier = Modifier.scale(0.6f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.multicast_advertise),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Switch(
                                    checked = config.multicastBeacon,
                                    onCheckedChange = { viewModel.setMulticastBeacon(it) },
                                    enabled = !isServiceRunning,
                                    modifier = Modifier.scale(0.6f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            }
            item(key = "proxy") {
            var showProxyHelp by remember { mutableStateOf(false) }
            var showSocksDialog by remember { mutableStateOf(false) }
            var showHttpDialog by remember { mutableStateOf(false) }
            var showDnsDialog by remember { mutableStateOf(false) }
            var showPacDialog by remember { mutableStateOf(false) }
            val context = LocalContext.current

            if (showProxyHelp) {
                ProxyHowItWorksDialog(onDismiss = { showProxyHelp = false })
            }

            if (showSocksDialog) {
                ProxyAddressDialog(
                    title = stringResource(R.string.socks5_proxy_title),
                    initialAddress = config.socksProxy,
                    defaultPort = 1080,
                    onDismiss = { showSocksDialog = false },
                    onConfirm = { address ->
                        viewModel.updateSocksProxy(address)
                        showSocksDialog = false
                    }
                )
            }

            if (showHttpDialog) {
                ProxyAddressDialog(
                    title = stringResource(R.string.http_proxy_title),
                    initialAddress = config.httpProxy,
                    defaultPort = 8080,
                    onDismiss = { showHttpDialog = false },
                    onConfirm = { address ->
                        viewModel.updateHttpProxy(address)
                        showHttpDialog = false
                    }
                )
            }

            if (showDnsDialog) {
                DnsServerDialog(
                    initialPrimary = config.dnsServer,
                    initialSecondary = config.dnsServer2,
                    onDismiss = { showDnsDialog = false },
                    onConfirm = { primary, secondary ->
                        viewModel.updateDnsServers(primary, secondary)
                        showDnsDialog = false
                    }
                )
            }

            if (showPacDialog) {
                PacServerDialog(
                    initialIp = config.pacIp,
                    initialPort = config.pacPort,
                    initialAllTraffic = config.pacAllTraffic,
                    onDismiss = { showPacDialog = false },
                    onConfirm = { ip, port, allTraffic ->
                        viewModel.updatePacIp(ip)
                        viewModel.updatePacPort(port)
                        viewModel.setPacAllTraffic(allTraffic)
                        showPacDialog = false
                    }
                )
            }

            ConfigSectionWithToggle(
                title = stringResource(R.string.proxy_config_section),
                enabled = config.proxyEnabled,
                onToggle = { viewModel.toggleProxyEnabled() },
                isServiceRunning = isServiceRunning,
                helpContentDescription = stringResource(R.string.proxy_how_it_works),
                onHelpClick = { showProxyHelp = true }
            ) {
                val rowEnabled = !isServiceRunning && config.proxyEnabled

                ProxySettingRow(
                    label = stringResource(R.string.socks5_proxy_title),
                    value = config.socksProxy.ifBlank { stringResource(R.string.socks_proxy_hint) },
                    checked = config.socksEnabled,
                    onCheckedChange = { viewModel.toggleSocksEnabled() },
                    enabled = rowEnabled,
                    onClick = { showSocksDialog = true },
                    trailing = {
                        if (config.socksProxy.isNotBlank()) {
                            CopyAddressButton(context, config.socksProxy)
                        }
                    }
                )

                val dnsValue = config.dnsServer.ifBlank { stringResource(R.string.dns_server_hint) } +
                    if (config.dnsServer2.isNotBlank()) ", ${config.dnsServer2}" else ""
                ProxySettingRow(
                    label = stringResource(R.string.dns_server),
                    value = dnsValue,
                    enabled = rowEnabled,
                    onClick = { showDnsDialog = true }
                )

                if (isPro) {
                    ProxySettingRow(
                        label = stringResource(R.string.http_proxy_title),
                        value = config.httpProxy.ifBlank { stringResource(R.string.http_proxy_hint) },
                        checked = config.httpEnabled,
                        onCheckedChange = { viewModel.toggleHttpEnabled() },
                        enabled = rowEnabled,
                        onClick = { showHttpDialog = true },
                        trailing = {
                            if (config.httpProxy.isNotBlank()) {
                                CopyAddressButton(context, config.httpProxy)
                            }
                        }
                    )

                    ProxySettingRow(
                        label = stringResource(R.string.pac_server),
                        value = PacGenerator.pacUrl(config.pacIp, config.pacPort),
                        checked = config.pacEnabled,
                        onCheckedChange = { viewModel.togglePacEnabled() },
                        enabled = rowEnabled,
                        onClick = { showPacDialog = true },
                        trailing = {
                            IconButton(onClick = {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("PAC URL", PacGenerator.pacUrl(config.pacIp, config.pacPort))
                                )
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.pac_url_copied),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.copy_pac_url),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }

            }

            Spacer(modifier = Modifier.height(12.dp))
            }
            item(key = "forward") {
            ConfigSectionWithToggle(
                title = stringResource(R.string.forward_remote_port_section),
                enabled = config.forwardEnabled,
                onToggle = { viewModel.toggleForwardEnabled() },
                isServiceRunning = isServiceRunning
            ) {
                ReorderableColumn(
                    items = config.forwardMappings,
                    onReorder = { viewModel.reorderForwardMappings(it) },
                    enabled = !isServiceRunning && config.forwardEnabled
                ) { mapping, isDragging ->
                    ForwardMappingItem(
                        mapping = mapping,
                        enabled = !isServiceRunning && config.forwardEnabled,
                        checkboxEnabled = config.forwardEnabled,
                        isDragging = isDragging,
                        linkEnabled = isServiceRunning,
                        onEdit = {
                            editingForwardMapping = mapping
                            showForwardDialog = true
                        },
                        onToggle = { viewModel.toggleForwardMapping(mapping) }
                    )
                }

                if (!isServiceRunning && config.forwardEnabled
