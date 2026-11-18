package com.example.routermanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.routermanager.ui.*
import com.example.routermanager.ui.theme.RouterManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                val vm: RouterViewModel = viewModel()
                val settings by vm.settings.collectAsState()

                RouterManagerTheme(themeMode = settings.theme) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val navController = rememberNavController()
                        val connected by vm.connected.collectAsState()

                        androidx.compose.runtime.LaunchedEffect(connected) {
                            if (connected) {
                                navController.navigate("dashboard") { popUpTo("login") { inclusive = true } }
                            }
                        }

                        NavHost(navController = navController, startDestination = "login") {
                            composable("login") { LoginScreen(vm = vm) }
                            
                            composable("dashboard") {
                                val dashboardState by vm.dashboard.collectAsState()
                                DashboardScreen(
                                    state = dashboardState,
                                    viewModel = vm,
                                    onNavigateToSpeedTest = { navController.navigate("speedtest") },
                                    onNavigateToHealth = { navController.navigate("health") },
                                    onNavigateToVouchers = { navController.navigate("vouchers") } // المسار الجديد
                                )
                            }
                            
                            composable("speedtest") {
                                val speedTestState by vm.speedTest.collectAsState()
                                SpeedTestScreen(state = speedTestState, onStartTest = vm::startSpeedTest, onBack = { navController.popBackStack() })
                            }
                            
                            composable("health") {
                                val dashboardState by vm.dashboard.collectAsState()
                                RouterHealthScreen(state = dashboardState, onBack = { navController.popBackStack() })
                            }

                            // شاشة الكروت الجديدة
                            composable("vouchers") {
                                val dashboardState by vm.dashboard.collectAsState()
                                val vouchersList by vm.vouchers.collectAsState()
                                VoucherScreen(
                                    state = dashboardState,
                                    vouchers = vouchersList,
                                    onCreateVoucher = vm::createVoucher,
                                    onDeleteVoucher = vm::deleteVoucher,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}