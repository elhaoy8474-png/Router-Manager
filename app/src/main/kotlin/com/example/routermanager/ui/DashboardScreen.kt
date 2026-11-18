package com.example.routermanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(state: DashboardState, viewModel: RouterViewModel, onNavigateToSpeedTest: () -> Unit, onNavigateToHealth: () -> Unit, onNavigateToVouchers: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text("لوحة التحكم تعمل!")
    }
}