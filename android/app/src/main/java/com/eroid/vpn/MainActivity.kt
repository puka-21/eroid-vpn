package com.eroid.vpn

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eroid.vpn.core.PingTester
import com.eroid.vpn.core.PreferencesManager
import com.eroid.vpn.core.SubscriptionFetcher
import com.eroid.vpn.model.*
import com.eroid.vpn.service.EroidVpnService
import com.eroid.vpn.ui.theme.EroidVpnTheme
import com.eroid.vpn.ui.theme.LocalThemeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val fetcher by lazy { SubscriptionFetcher(this) }
    private val pingTester by lazy { PingTester() }
    private val prefs by lazy { PreferencesManager(this) }

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnServiceInternal()
        } else {
            Toast.makeText(this, "Разрешение на VPN отклонено", Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingStartNode: Node? = null
    private var pendingStartCore: CoreConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var settings by remember { mutableStateOf(prefs.loadSettings()) }
            var currentTab by remember { mutableIntStateOf(0) }
            val vpnStatus by EroidVpnService.status.collectAsState()
            val currentNode by EroidVpnService.currentNode.collectAsState()
            val logs = remember { mutableStateListOf<String>() }
            var isLoadingSubscription by remember { mutableStateOf(false) }
            var isTestingPing by remember { mutableStateOf(false) }
            var deviceLimitBanner by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            // Observe background logs
            LaunchedEffect(Unit) {
                EroidVpnService.logs.collect { log ->
                    if (logs.size > 300) logs.removeAt(0)
                    logs.add(log)
                }
            }

            // Initial load of subscription if nodes are empty
            LaunchedEffect(Unit) {
                if (settings.nodes.isEmpty()) {
                    isLoadingSubscription = true
                    val res = fetcher.fetch(settings.subscriptionUrl, settings.customHwid)
                    isLoadingSubscription = false
                    when (res) {
                        is SubscriptionFetcher.FetchResult.Success -> {
                            settings = settings.copy(nodes = res.nodes.toMutableList())
                            prefs.saveSettings(settings)
                            deviceLimitBanner = res.isDeviceLimitReached
                        }
                        is SubscriptionFetcher.FetchResult.Error -> {
                            deviceLimitBanner = res.message.contains("лимит устройств", ignoreCase = true)
                        }
                    }
                }
            }

            EroidVpnTheme(appTheme = settings.theme) {
                val colors = LocalThemeColors.current

                Scaffold(
                    containerColor = colors.background,
                    topBar = {
                        TopAppBarCustom(
                            status = vpnStatus,
                            currentTheme = settings.theme,
                            onThemeChange = { newTheme ->
                                settings = settings.copy(theme = newTheme)
                            }
                        )
                    },
                    bottomBar = {
                        PillNavigationBar(
                            selectedTab = currentTab,
                            onTabSelected = { currentTab = it }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        when (currentTab) {
                            0 -> HomeScreen(
                                status = vpnStatus,
                                selectedNode = settings.nodes.getOrNull(settings.selectedNodeIndex),
                                core = settings.core,
                                onConnectClick = {
                                    if (vpnStatus == VpnStatus.CONNECTED || vpnStatus == VpnStatus.CONNECTING) {
                                        stopVpnService()
                                    } else {
                                        val node = settings.nodes.getOrNull(settings.selectedNodeIndex)
                                        if (node != null) {
                                            requestStartVpn(node, settings.core)
                                        } else {
                                            Toast.makeText(this@MainActivity, "Выберите сервер", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onPickServerClick = { currentTab = 1 }
                            )
                            1 -> ServersScreen(
                                nodes = settings.nodes,
                                selectedIndex = settings.selectedNodeIndex,
                                isLoading = isLoadingSubscription,
                                isTestingPing = isTestingPing,
                                showLimitBanner = deviceLimitBanner,
                                hwid = fetcher.getHardwareId(settings.customHwid),
                                onSelectNode = { index ->
                                    settings = settings.copy(selectedNodeIndex = index)
                                    prefs.saveSettings(settings)
                                },
                                onRefreshSubscription = {
                                    scope.launch {
                                        isLoadingSubscription = true
                                        val res = fetcher.fetch(settings.subscriptionUrl, settings.customHwid)
                                        isLoadingSubscription = false
                                        when (res) {
                                            is SubscriptionFetcher.FetchResult.Success -> {
                                                settings = settings.copy(nodes = res.nodes.toMutableList())
                                                prefs.saveSettings(settings)
                                                deviceLimitBanner = res.isDeviceLimitReached
                                                Toast.makeText(this@MainActivity, "Загружено серверов: ${res.nodes.size}", Toast.LENGTH_SHORT).show()
                                            }
                                            is SubscriptionFetcher.FetchResult.Error -> {
                                                deviceLimitBanner = res.message.contains("лимит устройств", ignoreCase = true)
                                                Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                onTestPing = {
                                    scope.launch {
                                        isTestingPing = true
                                        for (node in settings.nodes) {
                                            pingTester.testPing(node)
                                        }
                                        isTestingPing = false
                                    }
                                },
                                onOpenBot = {
                                    try {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/ruvpn")))
                                    } catch (_: Exception) {
                                        Toast.makeText(this@MainActivity, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onGoToSettings = { currentTab = 2 }
                            )
                            2 -> SettingsScreen(
                                settings = settings,
                                hwid = fetcher.getHardwareId(settings.customHwid),
                                onSettingsChanged = { updated ->
                                    settings = updated
                                    prefs.saveSettings(updated)
                                },
                                onCopyHwid = { hwidStr ->
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("HWID", hwidStr))
                                    Toast.makeText(this@MainActivity, "HWID скопирован в буфер", Toast.LENGTH_SHORT).show()
                                },
                                onPasteCustomHwid = {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val pasted = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                                    if (pasted.isNotBlank()) {
                                        val updated = settings.copy(customHwid = pasted)
                                        settings = updated
                                        prefs.saveSettings(updated)
                                        Toast.makeText(this@MainActivity, "Кастомный HWID вставлен", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this@MainActivity, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onOpenBot = {
                                    try {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/ruvpn")))
                                    } catch (_: Exception) {
                                        Toast.makeText(this@MainActivity, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onRefreshSubscription = {
                                    scope.launch {
                                        isLoadingSubscription = true
                                        val res = fetcher.fetch(settings.subscriptionUrl, settings.customHwid)
                                        isLoadingSubscription = false
                                        when (res) {
                                            is SubscriptionFetcher.FetchResult.Success -> {
                                                val updated = settings.copy(nodes = res.nodes.toMutableList())
                                                settings = updated
                                                prefs.saveSettings(updated)
                                                deviceLimitBanner = res.isDeviceLimitReached
                                                Toast.makeText(this@MainActivity, "Загружено серверов: ${res.nodes.size}", Toast.LENGTH_SHORT).show()
                                            }
                                            is SubscriptionFetcher.FetchResult.Error -> {
                                                deviceLimitBanner = res.message.contains("лимит устройств", ignoreCase = true)
                                                Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                            )
                            3 -> LogsScreen(
                                logs = logs,
                                onClearLogs = { logs.clear() },
                                onCopyLogs = {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Logs", logs.joinToString("\n")))
                                    Toast.makeText(this@MainActivity, "Логи скопированы", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestStartVpn(node: Node, core: CoreConfig) {
        pendingStartNode = node
        pendingStartCore = core
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            startVpnServiceInternal()
        }
    }

    private fun startVpnServiceInternal() {
        val node = pendingStartNode ?: return
        val core = pendingStartCore ?: CoreConfig()
        val intent = Intent(this, EroidVpnService::class.java).apply {
            action = EroidVpnService.ACTION_START
            putExtra(EroidVpnService.EXTRA_NODE, node)
            putExtra(EroidVpnService.EXTRA_CORE, core)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, EroidVpnService::class.java).apply {
            action = EroidVpnService.ACTION_STOP
        }
        startService(intent)
    }
}

/* ============================ UI COMPONENTS ============================ */

@Composable
fun TopAppBarCustom(
    status: VpnStatus,
    currentTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit
) {
    val colors = LocalThemeColors.current
    var showThemeMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "EROID VPN",
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = colors.primaryText,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.width(10.dp))
            // Status Dot
            val dotColor = when (status) {
                VpnStatus.CONNECTED -> colors.pingGood
                VpnStatus.CONNECTING -> colors.pingMedium
                VpnStatus.ERROR -> colors.pingBad
                VpnStatus.DISCONNECTED -> colors.textMuted
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }

        Box {
            IconButton(onClick = { showThemeMenu = true }) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = "Тема",
                    tint = colors.primary
                )
            }
            DropdownMenu(
                expanded = showThemeMenu,
                onDismissRequest = { showThemeMenu = false },
                modifier = Modifier.background(colors.surfaceElevated)
            ) {
                AppTheme.entries.forEach { theme ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                theme.displayName,
                                color = if (theme == currentTheme) colors.primary else colors.primaryText,
                                fontWeight = if (theme == currentTheme) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onThemeChange(theme)
                            showThemeMenu = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PillNavigationBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val colors = LocalThemeColors.current
    val tabs = listOf(
        Pair("Главная", Icons.Default.Shield),
        Pair("Серверы", Icons.Default.Dns),
        Pair("Настройки", Icons.Default.Tune),
        Pair("Логи", Icons.Default.Terminal)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(colors.surface)
                .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEachIndexed { index, (label, icon) ->
                val isSelected = selectedTab == index
                val itemBg = if (isSelected) colors.primary.copy(alpha = 0.15f) else Color.Transparent
                val itemContentColor = if (isSelected) colors.primary else colors.textMuted

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(itemBg)
                        .clickable { onTabSelected(index) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = itemContentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = label,
                            color = itemContentColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

/* ============================ TAB SCREENS ============================ */

@Composable
fun HomeScreen(
    status: VpnStatus,
    selectedNode: Node?,
    core: CoreConfig,
    onConnectClick: () -> Unit,
    onPickServerClick: () -> Unit
) {
    val colors = LocalThemeColors.current
    val isConnected = status == VpnStatus.CONNECTED
    val isConnecting = status == VpnStatus.CONNECTING

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Selected Server Capsule Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPickServerClick() }
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = selectedNode?.name ?: "Сервер не выбран",
                            color = colors.primaryText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${selectedNode?.type?.name ?: "VLESS"} • ${selectedNode?.address ?: "0.0.0.0"}",
                            color = colors.secondaryText,
                            fontSize = 12.sp
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = colors.textMuted
                )
            }
        }

        // Hero Monolithic Connect Button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 20.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(200.dp)
                    .clip(CircleShape)
                    .border(
                        width = 4.dp,
                        brush = Brush.radialGradient(
                            colors = if (isConnected) listOf(colors.primary, colors.pingGood)
                            else listOf(colors.surfaceElevated, colors.border)
                        ),
                        shape = CircleShape
                    )
                    .background(colors.surface)
                    .clickable { onConnectClick() }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Power",
                        tint = if (isConnected) colors.primary else colors.textMuted,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = when (status) {
                            VpnStatus.CONNECTED -> "ОТКЛЮЧИТЬ"
                            VpnStatus.CONNECTING -> "ПОДКЛЮЧЕНИЕ..."
                            VpnStatus.ERROR -> "ОШИБКА"
                            VpnStatus.DISCONNECTED -> "ПОДКЛЮЧИТЬ"
                        },
                        color = if (isConnected) colors.primary else colors.primaryText,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = when (status) {
                    VpnStatus.CONNECTED -> "Безопасное соединение активно"
                    VpnStatus.CONNECTING -> "Инициализация Sing-Box туннеля..."
                    VpnStatus.ERROR -> "Не удалось установить соединение"
                    VpnStatus.DISCONNECTED -> "Нажмите кнопку для подключения"
                },
                color = colors.secondaryText,
                fontSize = 13.sp
            )
        }

        // Active Features Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FeatureBadge(label = "GeoSite RU", active = core.enableGeoSiteRu)
            FeatureBadge(label = "GeoIP RU", active = core.enableGeoIpRu)
            FeatureBadge(label = "AdBlock", active = core.enableAdBlock)
            FeatureBadge(label = core.utlsFingerprint.uppercase(), active = true)
        }
    }
}

@Composable
fun FeatureBadge(label: String, active: Boolean) {
    val colors = LocalThemeColors.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (active) colors.surfaceElevated else colors.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (active) colors.primary.copy(alpha = 0.3f) else colors.border
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 11.sp,
            color = if (active) colors.primaryText else colors.textMuted,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ServersScreen(
    nodes: List<Node>,
    selectedIndex: Int,
    isLoading: Boolean,
    isTestingPing: Boolean,
    showLimitBanner: Boolean,
    hwid: String,
    onSelectNode: (Int) -> Unit,
    onRefreshSubscription: () -> Unit,
    onTestPing: () -> Unit,
    onOpenBot: () -> Unit,
    onGoToSettings: () -> Unit
) {
    val colors = LocalThemeColors.current
    var searchQuery by remember { mutableStateOf("") }

    val filtered = nodes.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
        it.address.contains(searchQuery, ignoreCase = true) ||
        it.type.name.contains(searchQuery, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Warning Banner if Remnawave device limit reached
        if (showLimitBanner) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.pingBad.copy(alpha = 0.15f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.pingBad.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = colors.pingBad)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Лимит устройств (1/1)",
                            color = colors.pingBad,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Сервер вернул заглушки 'Лимит устройств'. Сбросьте привязку устройств в Telegram-боте вашего провайдера или укажите HWID первого устройства в Настройках.",
                        color = colors.primaryText,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenBot,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.pingBad),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🤖 Бот @ruvpn", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onGoToSettings,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceElevated),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("⚙️ Кастомный HWID", color = colors.primary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Actions Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRefreshSubscription,
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceElevated),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = colors.primary)
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isLoading) "Загрузка..." else "Обновить", color = colors.primaryText, fontSize = 12.sp)
            }
            Button(
                onClick = onTestPing,
                enabled = !isTestingPing,
                colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceElevated),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Speed, contentDescription = null, tint = colors.primary)
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isTestingPing) "Тест..." else "Проверить пинг", color = colors.primaryText, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search Box
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Поиск локации, протокола...", color = colors.textMuted, fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = colors.textMuted) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = colors.border
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Server List
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Список серверов пуст", color = colors.textMuted)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(filtered) { _, node ->
                    val actualIndex = nodes.indexOf(node)
                    val isSelected = actualIndex == selectedIndex

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) colors.surfaceElevated else colors.surface
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) colors.primary else colors.border.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectNode(actualIndex) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                // Protocol pill badge
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.primary.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = node.type.name,
                                        color = colors.primary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = node.name,
                                        color = colors.primaryText,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${node.address}:${node.port}",
                                        color = colors.secondaryText,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            // Ping badge
                            val ping = node.pingMs
                            val pingColor = when {
                                ping == null -> colors.textMuted
                                ping < 150 -> colors.pingGood
                                ping < 300 -> colors.pingMedium
                                else -> colors.pingBad
                            }
                            Text(
                                text = if (ping != null) "$ping ms" else "—",
                                color = pingColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    hwid: String,
    onSettingsChanged: (AppSettings) -> Unit,
    onCopyHwid: (String) -> Unit,
    onPasteCustomHwid: () -> Unit,
    onOpenBot: () -> Unit,
    onRefreshSubscription: () -> Unit
) {
    val colors = LocalThemeColors.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Subscription URL Card
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Ссылка на подписку", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = settings.subscriptionUrl,
                        onValueChange = { onSettingsChanged(settings.copy(subscriptionUrl = it)) },
                        placeholder = { Text("https://...", color = colors.textMuted) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onRefreshSubscription,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceElevated),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = colors.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Обновить серверы", color = colors.primaryText, fontSize = 12.sp)
                    }
                }
            }
        }

        // HWID Manager Card (Кастомный HWID для обхода блокировок)
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Идентификатор (HWID)", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        val isCustom = settings.customHwid.isNotBlank()
                        Text(
                            text = if (isCustom) "● Кастомный (Клон)" else "● Системный ID",
                            color = if (isCustom) colors.pingGood else colors.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Позволяет эмулировать другое устройство (например, перенести HWID из INCY, HAPP или ПК для обхода лимита 1/1).",
                        color = colors.secondaryText,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Current Active HWID Display
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surfaceElevated)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = hwid,
                            color = colors.primaryText,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onCopyHwid(hwid) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = colors.primary)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Custom HWID Input Field
                    OutlinedTextField(
                        value = settings.customHwid,
                        onValueChange = { onSettingsChanged(settings.copy(customHwid = it.trim())) },
                        label = { Text("Кастомный HWID (для обхода блокировки)", fontSize = 12.sp) },
                        placeholder = { Text("Вставьте HWID из INCY, HAPP или ПК", color = colors.textMuted, fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null, tint = colors.primary) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onPasteCustomHwid,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceElevated),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📋 Вставить", color = colors.primaryText, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { onSettingsChanged(settings.copy(customHwid = "")) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceElevated),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🔄 Сбросить", color = colors.textMuted, fontSize = 11.sp)
                        }
                        Button(
                            onClick = onOpenBot,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceElevated),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🤖 Бот @ruvpn", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Routing & Protection Switches
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Маршрутизация и Фильтры", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(10.dp))

                    SettingSwitchItem(
                        title = "GeoSite RU (Обход блокировок банков)",
                        desc = "Прямой доступ к .ru, Госуслугам, Сбербанку, ВК",
                        checked = settings.core.enableGeoSiteRu,
                        onCheckedChange = {
                            val newCore = settings.core.copy(enableGeoSiteRu = it)
                            onSettingsChanged(settings.copy(core = newCore))
                        }
                    )
                    Divider(color = colors.border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))

                    SettingSwitchItem(
                        title = "GeoIP RU (Российские IP)",
                        desc = "Прямой доступ для всех IP адресов РФ",
                        checked = settings.core.enableGeoIpRu,
                        onCheckedChange = {
                            val newCore = settings.core.copy(enableGeoIpRu = it)
                            onSettingsChanged(settings.copy(core = newCore))
                        }
                    )
                    Divider(color = colors.border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))

                    SettingSwitchItem(
                        title = "Блокировка рекламы (AdBlock)",
                        desc = "Фильтрация трекеров и баннеров через geosite-ads",
                        checked = settings.core.enableAdBlock,
                        onCheckedChange = {
                            val newCore = settings.core.copy(enableAdBlock = it)
                            onSettingsChanged(settings.copy(core = newCore))
                        }
                    )
                    Divider(color = colors.border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))

                    SettingSwitchItem(
                        title = "Блокировка QUIC (HTTP/3)",
                        desc = "Принуждает сайты использовать быстрый TCP/TLS вместо замедляемого операторами UDP 443",
                        checked = settings.core.blockQuic,
                        onCheckedChange = {
                            val newCore = settings.core.copy(blockQuic = it)
                            onSettingsChanged(settings.copy(core = newCore))
                        }
                    )
                }
            }
        }

        // Core Parameters
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Параметры ядра Sing-Box", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Сетевой стек TUN", color = colors.primaryText, fontSize = 13.sp)
                        Text(settings.core.stack.uppercase(), color = colors.primary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("uTLS отпечаток браузера", color = colors.primaryText, fontSize = 13.sp)
                        Text(settings.core.utlsFingerprint.uppercase(), color = colors.primary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("MTU туннеля", color = colors.primaryText, fontSize = 13.sp)
                        Text("${settings.core.mtu}", color = colors.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingSwitchItem(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalThemeColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.primaryText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(desc, color = colors.secondaryText, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background,
                checkedTrackColor = colors.primary,
                uncheckedThumbColor = colors.textMuted,
                uncheckedTrackColor = colors.surfaceElevated
            )
        )
    }
}

@Composable
fun LogsScreen(
    logs: List<String>,
    onClearLogs: () -> Unit,
    onCopyLogs: () -> Unit
) {
    val colors = LocalThemeColors.current

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Логи Sing-Box", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Row {
                IconButton(onClick = onCopyLogs) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", tint = colors.primary)
                }
                IconButton(onClick = onClearLogs) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Очистить", tint = colors.textMuted)
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceElevated),
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                if (logs.isEmpty()) {
                    item {
                        Text("Логи пусты. Подключитесь к серверу для вывода ядра.", color = colors.textMuted, fontSize = 12.sp)
                    }
                } else {
                    itemsIndexed(logs) { _, log ->
                        Text(
                            text = log,
                            color = if (log.contains("ERROR", true)) colors.pingBad else colors.primaryText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
