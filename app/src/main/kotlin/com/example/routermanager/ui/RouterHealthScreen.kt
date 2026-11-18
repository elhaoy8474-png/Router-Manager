package com.example.routermanager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterHealthScreen(
    state: DashboardState,
    onBack: () -> Unit
) {
    val darkBg = Color(0xFF050510)
    val neonCyan = Color(0xFF00E5FF)
    val neonGreen = Color(0xFF00FF00)
    val neonPurple = Color(0xFFB026FF)

    val info = state.routerInfo

    // حساب النسب
    val cpu = info?.cpuLoad ?: 0f
    val totalMem = info?.memTotalKb ?: 1L
    val freeMem = info?.memFreeKb ?: 1L
    val usedMem = totalMem - freeMem
    val ramPercent = if (totalMem > 0) (usedMem.toFloat() / totalMem.toFloat()) * 100f else 0f
    val uptimeSec = info?.uptimeSeconds ?: 0L
    val days = uptimeSec / 86400
    val hours = (uptimeSec % 86400) / 3600

    Scaffold(
        containerColor = darkBg,
        topBar = {
            TopAppBar(
                title = { Text("صحة الراوتر", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (info == null) {
                Text("هذه البيانات غير مدعومة في راوترك الحالي.", color = Color.LightGray)
            } else {
                Text(info.model, color = neonCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Firmware: ${info.firmware}", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(32.dp))

                // عداد المعالج
                HealthGauge(
                    title = "المعالج CPU",
                    value = cpu,
                    max = 100f,
                    unit = "%",
                    color = neonCyan
                )
                
                Spacer(modifier = Modifier.height(24.dp))

                // عداد الذاكرة
                HealthGauge(
                    title = "الذاكرة RAM",
                    value = ramPercent,
                    max = 100f,
                    unit = "%",
                    color = neonGreen
                )

                Spacer(modifier = Modifier.height(24.dp))

                // وقت التشغيل
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("مدة التشغيل", color = Color.Gray, fontSize = 14.sp)
                        Text("$days يوم و $hours ساعة", color = neonPurple, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun HealthGauge(title: String, value: Float, max: Float, unit: String, color: Color) {
    val progress = (value / max).coerceIn(0f, 1f)
    
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2 - 15f
            // الدائرة الخلفية
            drawCircle(color = Color.DarkGray, radius = radius, style = Stroke(width = 20f))
            // القوس الملون
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = progress * 360f,
                useCenter = false,
                topLeft = Offset(15f, 15f),
                size = Size(size.width - 30f, size.height - 30f),
                style = Stroke(width = 20f, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(String.format("%.1f$unit", value), color = color, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}