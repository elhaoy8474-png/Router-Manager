package com.example.routermanager.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.routermanager.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    vm: RouterViewModel
) {
    val state by vm.login.collectAsState()
    
    val darkBg = Color(0xFF050510)
    val neonCyan = Color(0xFF00E5FF)

    Box(modifier = Modifier.fillMaxSize().background(darkBg)) {
        Image(
            painter = painterResource(id = R.drawable.bg_space),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.3f)
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(darkBg.copy(alpha = 0.8f), Color.Transparent, darkBg.copy(alpha = 0.9f)))
            )
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Router,
                contentDescription = null,
                tint = neonCyan,
                modifier = Modifier.size(80.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Elhaoy Router",
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Text(
                text = "تحكم كامل من هاتفك",
                color = neonCyan,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(32.dp))

            // حقل IP
            OutlinedTextField(
                value = state.host,
                onValueChange = vm::onHostChange,
                label = { Text("عنوان الراوتر (IP)") },
                placeholder = { Text("192.168.1.1") },
                modifier = Modifier.fillMaxWidth().border(1.dp, neonCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = neonCyan,
                    unfocusedLabelColor = Color.Gray
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // قائمة اختيار ماركة الراوتر
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = state.preferredBrand,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("ماركة الراوتر") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor().border(1.dp, neonCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = neonCyan,
                        unfocusedLabelColor = Color.Gray
                    )
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(Color(0xFF121212))
                ) {
                    state.supportedBrands.forEach { brand ->
                        DropdownMenuItem(
                            text = { Text(brand, color = Color.White) },
                            onClick = {
                                vm.onBrandChange(brand)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // حقل اسم المستخدم
            OutlinedTextField(
                value = state.username,
                onValueChange = vm::onUserChange,
                label = { Text("اسم المستخدم") },
                leadingIcon = { Icon(Icons.Default.Person, null, tint = neonCyan) },
                modifier = Modifier.fillMaxWidth().border(1.dp, neonCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = neonCyan,
                    unfocusedLabelColor = Color.Gray
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // حقل كلمة المرور
            OutlinedTextField(
                value = state.password,
                onValueChange = vm::onPassChange,
                label = { Text("كلمة المرور") },
                leadingIcon = { Icon(Icons.Default.Lock, null, tint = neonCyan) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().border(1.dp, neonCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = neonCyan,
                    unfocusedLabelColor = Color.Gray
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "تنبيه أمني: بعض أجهزة الراوتر لا تدعم إلا HTTP؛ في هذه الحالة قد تنتقل بيانات الدخول دون تشفير داخل الشبكة. استخدم شبكة موثوقة فقط.",
                color = Color(0xFFFFC857),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = state.allowInsecureHttp,
                    onCheckedChange = vm::onAllowInsecureHttpChange,
                    colors = CheckboxDefaults.colors(checkedColor = neonCyan)
                )
                Text(
                    text = "أوافق على استخدام HTTP غير المشفّر عند الحاجة",
                    color = Color(0xFFFFC857),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = state.rememberMe,
                    onCheckedChange = vm::onRememberChange,
                    colors = CheckboxDefaults.colors(checkedColor = neonCyan)
                )
                Text(text = "تذكرني", color = Color.LightGray)
            }

            if (state.error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.error!!,
                    color = Color.Red,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = vm::connect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(8.dp, RoundedCornerShape(30.dp)),
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(containerColor = neonCyan)
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black)
                } else {
                    Text("دخول للنظام", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { vm.enterDemoMode() },
                modifier = Modifier.fillMaxWidth().height(50.dp).border(1.dp, Color.Gray, RoundedCornerShape(30.dp)),
                shape = RoundedCornerShape(30.dp)
            ) {
                Text("🎨 تجربة التطبيق (بدون راوتر)", color = Color.LightGray)
            }
        }
    }
}