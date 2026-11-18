package com.example.routermanager.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.routermanager.data.BackupManager
import com.example.routermanager.data.CredentialStore
import com.example.routermanager.data.RouterFactory
import com.example.routermanager.data.SpeedTestManager
import com.example.routermanager.data.TrafficMonitor
import com.example.routermanager.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SpeedTestState(
    val isTesting: Boolean = false,
    val statusText: String = "جاهز للاختبار",
    val currentSpeed: Double = 0.0,
    val progress: Float = 0f,
    val ping: Long = 0L,
    val jitter: Long = 0L,
    val packetLoss: Double = 0.0,
    val download: Double = 0.0,
    val upload: Double = 0.0
)

data class TrafficSample(val download: Float, val upload: Float)

data class LoginState(
    val host: String = "",
    val username: String = "admin",
    val password: String = "",
    val rememberMe: Boolean = true,
    val preferredBrand: String = "Auto",
    val loading: Boolean = false,
    val error: String? = null,
    val autoDetecting: Boolean = false,
    val detectedBrand: String? = null,
    val supportedBrands: List<String> = listOf("Auto"),
)

data class DashboardState(
    val brand: String = "",
    val capabilities: RouterCapabilities = RouterCapabilities(),
    val network: NetworkInfo? = null,
    val devices: List<ConnectedDevice> = emptyList(),
    val macFilters: List<MacFilterRule> = emptyList(),
    val guestNetworks: List<GuestNetwork> = emptyList(),
    val wanInfo: WanInfo? = null,
    val routerInfo: RouterInfo? = null,
    val message: String? = null,
    val busy: Boolean = false,
    val lastRefresh: Long = 0L,
    val autoRefresh: Boolean = true,
    val trafficHistory: List<TrafficSample> = emptyList(),
)

data class AppSettings(
    val theme: String = "system",
    val language: String = "ar",
    val autoRefresh: Boolean = true,
    val autoRefreshInterval: Int = 30,
    val notificationsEnabled: Boolean = true,
    val notifyNewDevice: Boolean = true,
    val notifyDeviceBlocked: Boolean = true,
    val scheduleBlocking: Boolean = false,
    val scheduleStart: String = "00:00",
    val scheduleEnd: String = "06:00",
    val backupEnabled: Boolean = false,
    val showOfflineDevices: Boolean = true,
    val compactMode: Boolean = false,
)

class RouterViewModel(application: Application) : AndroidViewModel(application) {

    private val store = CredentialStore(application)
    private val prefs = application.getSharedPreferences("router_prefs", Context.MODE_PRIVATE)
    private val factory = RouterFactory(application)
    private val trafficMonitor = TrafficMonitor()

    private var api: RouterApi? = null
    private var refreshJob: Job? = null
    private var autoRefreshJob: Job? = null
    private var speedTestJob: Job? = null
    private var voucherCheckJob: Job? = null
    private var isAutoRefreshEnabled = true

    private val _login = MutableStateFlow(LoginState())
    val login: StateFlow<LoginState> = _login.asStateFlow()

    private val _dashboard = MutableStateFlow(DashboardState())
    val dashboard: StateFlow<DashboardState> = _dashboard.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    private val _deviceNicknames = MutableStateFlow<Map<String, String>>(emptyMap())
    val deviceNicknames: StateFlow<Map<String, String>> = _deviceNicknames.asStateFlow()

    private val _speedTest = MutableStateFlow(SpeedTestState())
    val speedTest: StateFlow<SpeedTestState> = _speedTest.asStateFlow()

    private val _vouchers = MutableStateFlow<List<Voucher>>(emptyList())
    val vouchers: StateFlow<List<Voucher>> = _vouchers.asStateFlow()

    init {
        runCatching { store.load() }.getOrNull()?.let { saved ->
            _login.update { it.copy(host = saved.host, username = saved.username, password = saved.password, rememberMe = true) }
        }
        _login.update { it.copy(preferredBrand = prefs.getString("brand", "Auto") ?: "Auto", supportedBrands = factory.supportedBrands) }
        
        loadVouchers()
        startTrafficMonitoring()
        startVoucherChecker()
    }

    // ================== منطق الكروت ==================
    private fun loadVouchers() {
        val jsonStr = prefs.getString("vouchers", null) ?: return
        try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Voucher>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(Voucher(
                    code = obj.getString("code"),
                    macAddress = obj.getString("mac"),
                    deviceName = obj.getString("device"),
                    expiryTime = obj.getLong("expiry"),
                    durationText = obj.getString("duration")
                ))
            }
            _vouchers.value = list
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun saveVouchers() {
        val array = JSONArray()
        _vouchers.value.forEach { v ->
            val obj = JSONObject()
            obj.put("code", v.code)
            obj.put("mac", v.macAddress)
            obj.put("device", v.deviceName)
            obj.put("expiry", v.expiryTime)
            obj.put("duration", v.durationText)
            array.put(obj)
        }
        prefs.edit().putString("vouchers", array.toString()).apply()
    }

    fun createVoucher(device: ConnectedDevice, durationMinutes: Int, durationText: String) {
        val code = "RN-" + (100000..999999).random().toString(16).uppercase()
        val expiry = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        val newVoucher = Voucher(code, device.mac, device.name, expiry, durationText)
        
        _vouchers.update { it + newVoucher }
        saveVouchers()
        unblockDevice(device.mac)
        _dashboard.update { it.copy(message = "تم إنشاء الكرت: $code") }
    }

    fun deleteVoucher(voucher: Voucher) {
        _vouchers.update { it - voucher }
        saveVouchers()
        blockDevice(voucher.macAddress)
    }

    private fun startVoucherChecker() {
        voucherCheckJob?.cancel()
        voucherCheckJob = viewModelScope.launch {
            while (isActive) {
                delay(30000)
                val now = System.currentTimeMillis()
                val expired = _vouchers.value.filter { it.expiryTime <= now }
                if (expired.isNotEmpty()) {
                    expired.forEach { v -> blockDevice(v.macAddress) }
                    _vouchers.update { current -> current - expired.toSet() }
                    saveVouchers()
                }
            }
        }
    }
    // ==================================================

    private fun startTrafficMonitoring() {
        viewModelScope.launch {
            trafficMonitor.getTrafficFlow().collect { (down, up) ->
                _dashboard.update { state ->
                    val newHistory = state.trafficHistory.toMutableList()
                    newHistory.add(TrafficSample(down, up))
                    if (newHistory.size > 30) newHistory.removeAt(0)
                    state.copy(trafficHistory = newHistory)
                }
            }
        }
    }

    fun enterDemoMode() {
        _isDemoMode.value = true
        _connected.value = true
        _dashboard.value = DashboardState(
            brand = "وضع العرض - ASUS Demo",
            network = NetworkInfo(ssid = "شبكة تجريبية", connected = true, clientCount = 3, clientsOnline = 3, clientsTotal = 3),
            devices = listOf(
                ConnectedDevice(name = "iPhone 15 Pro", ip = "192.168.1.101", mac = "AA:BB:CC:11:22:33", online = true, blocked = false),
                ConnectedDevice(name = "Samsung TV", ip = "192.168.1.102", mac = "AA:BB:CC:11:22:44", online = true, blocked = false),
            )
        )
    }

    fun exitDemoMode() {
        _isDemoMode.value = false
        _connected.value = false
        _dashboard.value = DashboardState()
    }

    fun onHostChange(v: String) = _login.update { it.copy(host = v, error = null) }
    fun onUserChange(v: String) = _login.update { it.copy(username = v, error = null) }
    fun onPassChange(v: String) = _login.update { it.copy(password = v, error = null) }
    fun onRememberChange(v: Boolean) = _login.update { it.copy(rememberMe = v) }
    fun onBrandChange(v: String) = _login.update { it.copy(preferredBrand = v, error = null) }

    fun connect() {
        val s = _login.value
        if (s.host.isBlank() || s.password.isEmpty()) {
            _login.update { it.copy(error = "Enter IP and password.") }
            return
        }
        _login.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val creds = RouterCredentials(s.host.trim(), s.username.trim(), s.password)
            when (val result = factory.connect(creds, s.preferredBrand)) {
                is RouterResult.Ok -> {
                    api = result.value.first
                    prefs.edit().putString("brand", s.preferredBrand).putString("host", s.host).apply()
                    if (s.rememberMe) runCatching { store.save(CredentialStore.Saved(creds.host, creds.username, creds.password)) } else runCatching { store.clear() }
                    _login.update { it.copy(loading = false, password = "") }
                    _connected.value = true
                    refreshDashboard()
                    startAutoRefresh()
                }
                is RouterResult.Error -> _login.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    fun refreshDashboard(notice: String? = null) {
        if (_isDemoMode.value) return
        val current = api ?: return
        _dashboard.update { it.copy(busy = true, brand = current.brand, capabilities = current.capabilities) }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val net = current.getNetworkInfo()
            val rawDevices = (current.getConnectedDevices() as? RouterResult.Ok)?.value.orEmpty()
            
            val activeVouchers = _vouchers.value.filter { it.expiryTime > System.currentTimeMillis() }
            val finalDevices = rawDevices.map { device ->
                val customName = _deviceNicknames.value[device.mac]
                var updatedDevice = if (customName != null) device.copy(name = customName) else device
                if (activeVouchers.any { it.macAddress == device.mac }) {
                    updatedDevice = updatedDevice.copy(blocked = false)
                }
                updatedDevice
            }

            _dashboard.update {
                it.copy(
                    busy = false,
                    network = (net as? RouterResult.Ok)?.value,
                    devices = finalDevices,
                    routerInfo = (current.getRouterInfo() as? RouterResult.Ok)?.value,
                    wanInfo = (current.getWanInfo() as? RouterResult.Ok)?.value,
                    lastRefresh = System.currentTimeMillis(),
                    message = notice ?: listOfNotNull((net as? RouterResult.Error)?.message).firstOrNull()
                )
            }
        }
    }

    fun blockDevice(mac: String) {
        if (_isDemoMode.value) return
        val current = api ?: return
        viewModelScope.launch { current.blockDevice(mac) }
    }

    fun unblockDevice(mac: String) {
        if (_isDemoMode.value) return
        val current = api ?: return
        viewModelScope.launch { current.unblockDevice(mac) }
    }

    fun rebootRouter() {
        val current = api ?: return
        viewModelScope.launch { current.reboot() }
    }

    fun toggleTheme(theme: String) { _settings.update { it.copy(theme = theme) } }
    fun dismissMessage() { _dashboard.update { it.copy(message = null) } }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        if (!isAutoRefreshEnabled) return
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(_settings.value.autoRefreshInterval * 1000L)
                if (api != null && !_isDemoMode.value) refreshDashboard()
            }
        }
    }

    fun changeDns(primary: String, secondary: String) {
        if (_isDemoMode.value) return
        val current = api ?: return
        viewModelScope.launch { current.changeDns(primary, secondary) }
    }

    fun getBackupData(): String = BackupManager.exportToJson(_settings.value, _deviceNicknames.value)
    fun restoreFromData(jsonString: String): Boolean = false

    fun startSpeedTest() {
        if (_speedTest.value.isTesting) return
        speedTestJob = viewModelScope.launch {
            _speedTest.value = SpeedTestState(isTesting = true, statusText = "قياس البينج...", progress = 0.1f)
            val (ping, jitter) = SpeedTestManager.measurePing()
            _speedTest.update { it.copy(ping = ping, jitter = jitter) }
            delay(1000)
            _speedTest.update { it.copy(statusText = "قياس التحميل...", progress = 0.3f) }
            val downJob = launch { while (isActive) { val p = _speedTest.value.progress; if (p < 0.8f) _speedTest.update { it.copy(progress = p + 0.01f) }; delay(100) } }
            val downloadSpeed = SpeedTestManager.measureDownloadLive { speed -> _speedTest.update { it.copy(currentSpeed = speed, download = speed) } }
            downJob.cancel()
            _speedTest.update { it.copy(statusText = "قياس الرفع...", progress = 0.8f) }
            val upJob = launch { while (isActive) { val p = _speedTest.value.progress; if (p < 0.95f) _speedTest.update { it.copy(progress = p + 0.01f) }; delay(100) } }
            val uploadSpeed = SpeedTestManager.measureUploadLive { speed -> _speedTest.update { it.copy(currentSpeed = speed, upload = speed) } }
            upJob.cancel()
            _speedTest.update { it.copy(isTesting = false, statusText = "اكتمل الاختبار", progress = 1f, currentSpeed = downloadSpeed) }
        }
    }
}