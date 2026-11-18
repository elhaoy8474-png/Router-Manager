package com.example.routermanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.routermanager.domain.ConnectedDevice
import com.example.routermanager.domain.Voucher

// تعريف الألوان محلياً لتجنب أي مشاكل في الاستيراد
private val NeonCyan = Color(0xFF00E5FF)
private val NeonGreen = Color(0xFF00FF00)
private val DarkBg = Color(0xFF050510)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoucherScreen(
    state: DashboardState,
    vouchers: List<Voucher>,
    onCreateVoucher: (ConnectedDevice, Int, String) -> Unit,
    onDeleteVoucher: (Voucher) -> Unit,
    onBack: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text("كروت الضيوف", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = NeonCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = NeonCyan,
                contentColor = Color.Black
            ) {
                Icon(Icons.Default.Add, contentDescription = "إضافة كرت")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2D16)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "تنبيه: هذه الكروت تُدار محليًا داخل التطبيق حاليًا، ولا تنشئ حسابات Hotspot أو Captive Portal فعلية على الراوتر.",
                        color = Color(0xFFFFD27A),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            if (vouchers.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 50.dp), contentAlignment = Alignment.Center) {
                        Text("لا توجد كروت نشطة حالياً.", color = Color.Gray, fontSize = 16.sp)
                    }
                }
            } else {
                items(vouchers, key = { it.code }) { voucher ->
                    VoucherCard(voucher, onDelete = { onDeleteVoucher(voucher) })
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateVoucherDialog(
            devices = state.devices,
            onDismiss = { showCreateDialog = false },
            onCreate = { device, mins, text ->
                onCreateVoucher(device, mins, text)
                showCreateDialog = false
            }
        )
    }
}

@Composable
fun VoucherCard(voucher: Voucher, onDelete: () -> Unit) {
    val remainingTime = voucher.expiryTime - System.currentTimeMillis()
    val isExpired = remainingTime <= 0
    val borderColor = if (isExpired) Color.Red else NeonGreen

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ConfirmationNumber, null, tint = borderColor, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(voucher.code, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                Text(voucher.deviceName, color = Color.LightGray, fontSize = 14.sp)
                
                val hours = remainingTime / (1000 * 60 * 60)
                val mins = (remainingTime / (1000 * 60)) % 60
                val secs = (remainingTime / 1000) % 60
                
                if (isExpired) {
                    Text("انتهت الصلاحية", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("متبقي: ${hours}س ${mins}د ${secs}ث", color = NeonGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = Color.Red)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateVoucherDialog(
    devices: List<ConnectedDevice>,
    onDismiss: () -> Unit,
    onCreate: (ConnectedDevice, Int, String) -> Unit
) {
    var selectedDevice by remember { mutableStateOf<ConnectedDevice?>(null) }
    var expandedDevice by remember { mutableStateOf(false) }
    var expandedDuration by remember { mutableStateOf(false) }

    val durations = listOf(
        Triple("ساعة واحدة", 60, "1H"),
        Triple("6 ساعات", 360, "6H"),
        Triple("24 ساعة", 1440, "24H"),
        Triple("أسبوع", 10080, "7D")
    )
    var selectedDuration by remember { mutableStateOf(durations[0]) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121212),
        title = { Text("إنشاء كرت جديد", color = NeonCyan, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("اختر الجهاز:", color = Color.White)
                ExposedDropdownMenuBox(
                    expanded = expandedDevice,
                    onExpandedChange = { expandedDevice = !expandedDevice }
                ) {
                    OutlinedTextField(
                        value = selectedDevice?.name ?: "اختر...",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDevice) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    ExposedDropdownMenu(expanded = expandedDevice, onDismissRequest = { expandedDevice = false }) {
                        devices.forEach { device ->
                            DropdownMenuItem(
                                text = { Text(device.name, color = Color.White) },
                                onClick = { selectedDevice = device; expandedDevice = false }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("اختر المدة:", color = Color.White)
                ExposedDropdownMenuBox(
                    expanded = expandedDuration,
                    onExpandedChange = { expandedDuration = !expandedDuration }
                ) {
                    OutlinedTextField(
                        value = selectedDuration.first,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDuration) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    ExposedDropdownMenu(expanded = expandedDuration, onDismissRequest = { expandedDuration = false }) {
                        durations.forEach { duration ->
                            DropdownMenuItem(
                                text = { Text(duration.first, color = Color.White) },
                                onClick = { selectedDuration = duration; expandedDuration = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedDevice?.let { onCreate(it, selectedDuration.second, selectedDuration.third) } },
                enabled = selectedDevice != null,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Text("إنشاء", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء", color = Color.Gray) } }
    )
}