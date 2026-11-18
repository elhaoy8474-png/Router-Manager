package com.example.routermanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.routermanager.domain.ConnectedDevice

private val DashboardBackground = Color(0xFF050510)
private val DashboardSurface = Color(0xFF111827)
private val DashboardCyan = Color(0xFF00E5FF)
private val DashboardGreen = Color(0xFF36D399)
private val DashboardMuted = Color(0xFF9CA3AF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardState,
    viewModel: RouterViewModel,
    onNavigateToSpeedTest: () -> Unit,
    onNavigateToHealth: () -> Unit,
    onNavigateToVouchers: () -> Unit
) {
    Scaffold(
        containerColor = DashboardBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Elhaoy Router", color = Color.White, fontWeight = FontWeight.ExtraBold)
                        Text(
                            state.brand.ifBlank { "إدارة الشبكة المنزلية" },
                            color = DashboardMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp).padding(end = 4.dp),
                            color = DashboardCyan,
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { viewModel.refreshDashboard() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "تحديث", tint = DashboardCyan)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DashboardBackground)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ConnectionCard(
                    connected = state.network?.connected == true,
                    ssid = state.network?.ssid,
                    wanStatus = state.wanInfo?.status
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        icon = { Icon(Icons.Default.Devices, null, tint = DashboardCyan) },
                        label = "الأجهزة المتصلة",
                        value = (state.network?.clientsOnline ?: state.devices.count { it.online }).toString()
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        icon = { Icon(Icons.Default.Wifi, null, tint = DashboardGreen) },
                        label = "اسم الشبكة",
                        value = state.network?.ssid ?: "غير معروف"
                    )
                }
            }

            item {
                Text("الوصول السريع", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionCard(
                        modifier = Modifier.weight(1f),
                        title = "اختبار السرعة",
                        subtitle = "Ping / Download",
                        icon = { Icon(Icons.Default.Speed, null, tint = DashboardCyan) },
                        onClick = onNavigateToSpeedTest
                    )
                    ActionCard(
                        modifier = Modifier.weight(1f),
                        title = "صحة الراوتر",
                        subtitle = "المعلومات والأداء",
                        icon = { Icon(Icons.Default.HealthAndSafety, null, tint = DashboardGreen) },
                        onClick = onNavigateToHealth
                    )
                    ActionCard(
                        modifier = Modifier.weight(1f),
                        title = "كروت الضيوف",
                        subtitle = "إدارة الصلاحية",
                        icon = { Icon(Icons.Default.ConfirmationNumber, null, tint = Color(0xFFB794F4)) },
                        onClick = onNavigateToVouchers
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("الأجهزة على الشبكة", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("${state.devices.size} جهاز", color = DashboardMuted, fontSize = 12.sp)
                }
            }

            if (state.devices.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DashboardSurface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Devices, null, tint = DashboardMuted, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (state.busy) "جارٍ تحميل الأجهزة..." else "لم يتم العثور على أجهزة بعد",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "حدّث البيانات أو تأكد أن الراوتر يدعم عرض الأجهزة المتصلة.",
                                color = DashboardMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else {
                items(state.devices, key = { it.mac }) { device ->
                    DeviceRow(
                        device = device,
                        onToggleBlock = {
                            if (device.blocked) viewModel.unblockDevice(device.mac)
                            else viewModel.blockDevice(device.mac)
                        }
                    )
                }
            }

            state.message?.let { message ->
                item { Text(message, color = Color(0xFFFFC857), fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun ConnectionCard(connected: Boolean, ssid: String?, wanStatus: String?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DashboardSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = (if (connected) DashboardGreen else Color(0xFFFF6B6B)).copy(alpha = 0.14f),
                modifier = Modifier.size(54.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Router, null, tint = if (connected) DashboardGreen else Color(0xFFFF6B6B), modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(if (connected) "الراوتر متصل" else "حالة الاتصال غير مؤكدة", color = Color.White, fontWeight = FontWeight.Bold)
                Text(ssid ?: "اسم الشبكة غير متاح", color = DashboardMuted, fontSize = 13.sp)
                Text("الإنترنت: ${wanStatus ?: "غير معروف"}", color = DashboardMuted, fontSize = 12.sp)
            }
            Surface(
                color = (if (connected) DashboardGreen else Color(0xFFFF6B6B)).copy(alpha = 0.16f),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    if (connected) "ONLINE" else "UNKNOWN",
                    color = if (connected) DashboardGreen else Color(0xFFFF6B6B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: String,
    value: String
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DashboardSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            icon()
            Spacer(Modifier.height(10.dp))
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, color = DashboardMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ActionCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 112.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            icon()
            Spacer(Modifier.height(10.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
            Text(subtitle, color = DashboardMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DeviceRow(device: ConnectedDevice, onToggleBlock: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DashboardSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = DashboardCyan.copy(alpha = 0.12f)) {
                Icon(Icons.Default.Devices, null, tint = DashboardCyan, modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(device.ip, color = DashboardMuted, fontSize = 11.sp)
                Text(device.mac, color = DashboardMuted, fontSize = 10.sp)
            }
            TextButton(onClick = onToggleBlock) {
                Text(if (device.blocked) "إلغاء الحظر" else "حظر", color = if (device.blocked) DashboardGreen else Color(0xFFFF8A80), fontSize = 11.sp)
            }
        }
    }
}
