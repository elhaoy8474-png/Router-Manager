package com.example.routermanager.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border // تم إضافة هذا الاستدعاء
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha // تم إضافة هذا الاستدعاء
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.routermanager.R
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(
    state: SpeedTestState,
    onStartTest: () -> Unit,
    onBack: () -> Unit
) {
    // الألوان النيونية
    val cyan = Color(0xFF00E5FF)
    val pink = Color(0xFFFF007F)
    val purple = Color(0xFF9C27B0)
    val green = Color(0xFF00FF00)
    val darkBg = Color(0xFF050510)

    Box(modifier = Modifier.fillMaxSize().background(darkBg)) {
        
        // محاولة عرض الخلفية إذا كانت موجودة
        // ⚠️ ملاحظة: تأكد من وضع صورة باسم speed_bg.jpg في مجلد res/drawable
        Image(
            painter = painterResource(id = R.drawable.speed_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.4f)
        )
        
        // تدرج لوني لإخفاء حواف الصورة
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(darkBg.copy(alpha = 0.8f), Color.Transparent, darkBg.copy(alpha = 0.9f))
                    )
                )
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ===== 1. الشريط العلوي =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Elhaoy", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Row {
                    Icon(Icons.Default.Info, null, tint = cyan, modifier = Modifier.padding(8.dp))
                    Icon(Icons.Default.Settings, null, tint = cyan, modifier = Modifier.padding(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ===== 2. شبكة من 3 أعمدة (اليمين، الوسط، اليسار) =====
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                
                // --- العمود الأيسر (معلومات الاتصال) ---
                Column(modifier = Modifier.weight(0.25f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    InfoBox("حالة الاتصال", "متصل", green, cyan)
                    InfoBox("الخادم", "القاهرة\n🇪🇬", cyan, cyan)
                    InfoBox("الجودة", "ممتاز\n⭐⭐⭐⭐⭐", green, cyan)
                }

                // --- العمود الأوسط (العداد) ---
                Box(
                    modifier = Modifier.weight(0.5f).aspectRatio(1f),
                    contentAlignment = Alignment.Center
                ) {
                    SpeedGauge(state, cyan, pink)
                }

                // --- العمود الأيمن (السرعات) ---
                Column(modifier = Modifier.weight(0.25f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SpeedBox("سرعة التحميل", state.download, cyan, Icons.Default.ArrowDownward)
                    SpeedBox("سرعة الرفع", state.upload, pink, Icons.Default.ArrowUpward)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ===== 3. الصف السفلي (Ping, Jitter, Packet Loss) =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniBox("زمن الاستجابة", "${if (state.ping > 0) state.ping else "--"} ms", green, Modifier.weight(1f))
                MiniBox("تذبذب الاتصال", "${if (state.jitter > 0) state.jitter else "--"} ms", cyan, Modifier.weight(1f))
                MiniBox("فقدان الحزم", "${state.packetLoss} %", cyan, Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.weight(1f))

            // ===== 4. زر البدء =====
            Button(
                onClick = onStartTest,
                enabled = !state.isTesting,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(60.dp)
                    .background(
                        brush = Brush.horizontalGradient(listOf(cyan.copy(alpha = 0.8f), purple.copy(alpha = 0.8f))),
                        shape = RoundedCornerShape(30.dp)
                    ),
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Text(
                    text = if (state.isTesting) state.statusText else "ابدأ الاختبار الآن 🚀",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ==================== المكونات الفرعية ====================

@Composable
fun SpeedGauge(state: SpeedTestState, cyan: Color, pink: Color) {
    val animatedSpeed by animateFloatAsState(
        targetValue = state.currentSpeed.toFloat(),
        animationSpec = tween(300, easing = LinearEasing),
        label = "speed"
    )
    
    // تحويل السرعة إلى زاوية (0 إلى 300 ميجا)
    val maxSpeed = 300f
    val sweepAngle = (animatedSpeed / maxSpeed).coerceIn(0f, 1f) * 270f

    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.width / 2 - 20f

            // الدائرة الخارجية
            drawCircle(color = cyan.copy(alpha = 0.2f), radius = radius, center = center, style = Stroke(width = 8f))
            
            // علامات التدريج
            for (i in 0..6) {
                val angle = 135f + (270f / 6) * i
                val rad = Math.toRadians(angle.toDouble())
                val start = Offset(center.x + (radius - 30f) * cos(rad).toFloat(), center.y + (radius - 30f) * sin(rad).toFloat())
                val end = Offset(center.x + radius * cos(rad).toFloat(), center.y + radius * sin(rad).toFloat())
                drawLine(color = Color.White.copy(alpha = 0.5f), start = start, end = end, strokeWidth = 4f)
            }

            // شريط التقدم
            drawArc(
                brush = Brush.sweepGradient(listOf(cyan, pink, cyan)),
                startAngle = 135f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 20f, cap = StrokeCap.Round)
            )
            
            // المؤشر (العقرب)
            val needleAngle = 135f + sweepAngle
            val needleRad = Math.toRadians(needleAngle.toDouble())
            val needleEnd = Offset(center.x + (radius - 50f) * cos(needleRad).toFloat(), center.y + (radius - 50f) * sin(needleRad).toFloat())
            drawLine(color = Color.White, start = center, end = needleEnd, strokeWidth = 6f, cap = StrokeCap.Round)
            drawCircle(color = Color.White, radius = 10f, center = center)
        }

        // النص في المنتصف
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("سرعة التحميل", color = Color.White, fontSize = 14.sp)
            Text(
                text = String.format("%.1f", state.currentSpeed),
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text("Mbps", color = cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun InfoBox(title: String, value: String, valueColor: Color, borderColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(8.dp)
            .border(1.dp, borderColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
    ) {
        Column {
            Text(title, color = Color.Gray, fontSize = 10.sp)
            Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SpeedBox(title: String, speed: Double, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(8.dp)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Text(title, color = Color.Gray, fontSize = 10.sp)
            Text(String.format("%.1f", speed), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Mbps", color = color, fontSize = 10.sp)
        }
    }
}

@Composable
fun MiniBox(title: String, value: String, color: Color, modifier: Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(8.dp)
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.Gray, fontSize = 10.sp)
            Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}