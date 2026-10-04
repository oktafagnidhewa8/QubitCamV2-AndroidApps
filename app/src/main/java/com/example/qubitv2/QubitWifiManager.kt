package com.example.qubitv2

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

import java.net.Inet4Address

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue


data class QubitWifiNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val capabilities: String
) {

    val isQubit: Boolean
        get() =
            ssid.contains(
                "QUBIT",
                ignoreCase = true
            )


    val isOpen: Boolean
        get() {

            val cap = capabilities.uppercase()

            return !(
                    cap.contains("WPA") ||
                            cap.contains("WEP") ||
                            cap.contains("SAE")
                    )
        }


    val securityLabel: String
        get() {

            val cap = capabilities.uppercase()

            return when {
                cap.contains("SAE") -> "WPA3"
                cap.contains("WPA2") -> "WPA2"
                cap.contains("WPA") -> "WPA"
                cap.contains("WEP") -> "WEP"
                else -> "OPEN"
            }
        }


    val signalLabel: String
        get() =
            when {
                rssi >= -50 -> "Excellent"
                rssi >= -60 -> "Good"
                rssi >= -70 -> "Fair"
                else -> "Weak"
            }
}


enum class QubitLinkType {
    FC,
    CAMERA
}


class QubitWifiManager(
    context: Context
) {

    private val appContext =
        context.applicationContext


    private val wifiManager =
        appContext.getSystemService(
            Context.WIFI_SERVICE
        ) as WifiManager


    private val connectivityManager =
        appContext.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager


    private val locationManager =
        appContext.getSystemService(
            Context.LOCATION_SERVICE
        ) as LocationManager


    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )


    private var scanReceiver:
            BroadcastReceiver? = null


    private var fcCallback:
            ConnectivityManager.NetworkCallback? = null


    private var cameraCallback:
            ConnectivityManager.NetworkCallback? = null


    private var fcNetworkInternal:
            Network? = null


    private var cameraNetworkInternal:
            Network? = null


    // ============================================================
    // SCAN STATE
    // ============================================================

    var isScanning by
    mutableStateOf(false)
        private set


    var scanStatus by
    mutableStateOf("Belum scan")
        private set


    var networks by
    mutableStateOf<List<QubitWifiNetwork>>(
        emptyList()
    )
        private set


    // ============================================================
    // FC STATE
    // ============================================================

    var isFcConnected by
    mutableStateOf(false)
        private set


    var fcSsid by
    mutableStateOf("")
        private set


    var fcStatus by
    mutableStateOf("FC DISCONNECTED")
        private set


    // ============================================================
    // CAMERA STATE
    // ============================================================

    var isCameraConnected by
    mutableStateOf(false)
        private set


    var cameraSsid by
    mutableStateOf("")
        private set


    var cameraStatus by
    mutableStateOf("CAMERA DISCONNECTED")
        private set


    // ============================================================
    // COMPATIBILITY ALIASES
    // agar kode lama yang masih memakai nama ini tidak langsung rusak.
    // ============================================================

    val isConnected: Boolean
        get() = isFcConnected


    val connectedSsid: String
        get() = fcSsid


    val status: String
        get() = fcStatus


    // ============================================================
    // DUAL-STA CAPABILITY
    // ============================================================

    fun isDualStaSupported(): Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {
            wifiManager
                .isStaConcurrencyForLocalOnlyConnectionsSupported
        } else {
            false
        }
    }


    // ============================================================
    // NETWORK ACCESSORS
    // ============================================================

    fun getFcNetwork(): Network? =
        fcNetworkInternal


    fun getCameraNetwork(): Network? =
        cameraNetworkInternal


    // ============================================================
    // FC GATEWAY
    //
    // Arduino memakai WiFi.gatewayIP() sebagai alamat target drone.
    // Android harus mengikuti gateway dari Network FC yang aktif,
    // bukan selalu mengasumsikan 192.168.4.1.
    // ============================================================

    fun getGatewayIp(
        network: Network
    ): String? {

        return try {

            val linkProperties =
                connectivityManager
                    .getLinkProperties(
                        network
                    )
                    ?: return null


            // Cari gateway IPv4 yang benar-benar valid.
            // Jangan pernah memilih 0.0.0.0.
            val gateway =
                linkProperties
                    .routes
                    .asSequence()
                    .mapNotNull {
                        it.gateway
                    }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull {
                        !it.isAnyLocalAddress &&
                                it.hostAddress != "0.0.0.0"
                    }


            gateway
                ?.hostAddress

        } catch (
            _: Exception
        ) {

            null
        }
    }


    // ============================================================
    // LOCATION
    // ============================================================

    fun isLocationEnabled(): Boolean =
        locationManager.isLocationEnabled


    fun openLocationSettings() {

        val intent =
            Intent(
                Settings.ACTION_LOCATION_SOURCE_SETTINGS
            )

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
        )

        appContext.startActivity(
            intent
        )
    }


    // ============================================================
    // WIFI SCAN
    // ============================================================

    @SuppressLint("MissingPermission")
    fun scanNetworks() {

        if (!isLocationEnabled()) {

            scanStatus =
                "Aktifkan Location untuk scan WiFi"

            return
        }


        removeScanReceiver()


        isScanning = true
        scanStatus = "Scanning WiFi..."


        val receiver =
            object : BroadcastReceiver() {

                override fun onReceive(
                    context: Context,
                    intent: Intent
                ) {

                    if (
                        intent.action !=
                        WifiManager
                            .SCAN_RESULTS_AVAILABLE_ACTION
                    ) {
                        return
                    }


                    val fresh =
                        intent.getBooleanExtra(
                            WifiManager.EXTRA_RESULTS_UPDATED,
                            false
                        )


                    publishScanResults(
                        fresh
                    )

                    removeScanReceiver()
                }
            }


        scanReceiver = receiver


        val filter =
            IntentFilter(
                WifiManager
                    .SCAN_RESULTS_AVAILABLE_ACTION
            )


        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            appContext.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")

            appContext.registerReceiver(
                receiver,
                filter
            )
        }


        try {

            val started =
                wifiManager.startScan()


            if (!started) {

                // Android dapat throttle scan.
                // Gunakan cached scanResults bila tersedia.
                publishScanResults(false)
                removeScanReceiver()
            }

        } catch (
            e: SecurityException
        ) {

            isScanning = false

            scanStatus =
                "Permission scan WiFi belum diberikan"

            removeScanReceiver()

        } catch (
            e: Exception
        ) {

            isScanning = false

            scanStatus =
                "Scan error: ${e.message}"

            removeScanReceiver()
        }
    }


    @SuppressLint("MissingPermission")
    private fun publishScanResults(
        fresh: Boolean
    ) {

        try {

            val bestPerSsid =
                wifiManager
                    .scanResults
                    .filter {
                        it.SSID.isNotBlank()
                    }
                    .groupBy {
                        it.SSID
                    }
                    .mapNotNull {
                            (_, results) ->

                        results.maxByOrNull {
                            it.level
                        }
                    }


            val mapped =
                bestPerSsid
                    .map {
                        toNetwork(it)
                    }
                    .sortedWith(
                        compareByDescending<QubitWifiNetwork> {
                            it.isQubit
                        }.thenByDescending {
                            it.rssi
                        }
                    )


            mainHandler.post {

                networks = mapped

                isScanning = false

                scanStatus =
                    when {
                        mapped.isEmpty() ->
                            "Tidak ada WiFi ditemukan"

                        fresh ->
                            "${mapped.size} jaringan ditemukan"

                        else ->
                            "${mapped.size} jaringan cached"
                    }
            }

        } catch (
            e: SecurityException
        ) {

            mainHandler.post {
                isScanning = false
                scanStatus =
                    "Permission lokasi diperlukan"
            }
        }
    }


    private fun toNetwork(
        result: ScanResult
    ): QubitWifiNetwork {

        return QubitWifiNetwork(
            ssid = result.SSID,
            bssid = result.BSSID ?: "",
            rssi = result.level,
            capabilities = result.capabilities
        )
    }


    private fun removeScanReceiver() {

        val receiver =
            scanReceiver ?: return


        try {

            appContext.unregisterReceiver(
                receiver
            )

        } catch (_: Exception) {
        }


        scanReceiver = null
    }


    // ============================================================
    // FC CONNECT
    // ============================================================

    fun connectFc(
        ssid: String,
        password: String,
        bssid: String? = null,
        security: String = "",
        onConnected: (Network) -> Unit
    ) {

        if (
            isCameraConnected &&
            !isDualStaSupported()
        ) {

            fcStatus =
                "Dual WiFi tidak didukung perangkat"

            return
        }


        requestLink(
            type = QubitLinkType.FC,
            ssid = ssid,
            password = password,
            bssid = bssid,
            security = security,
            onConnected = onConnected
        )
    }


    // ============================================================
    // CAMERA CONNECT
    // ============================================================

    fun connectCamera(
        ssid: String,
        password: String,
        bssid: String? = null,
        security: String = "",
        onConnected: (Network) -> Unit = {}
    ) {

        if (
            isFcConnected &&
            !isDualStaSupported()
        ) {

            cameraStatus =
                "Dual WiFi tidak didukung perangkat"

            return
        }


        requestLink(
            type = QubitLinkType.CAMERA,
            ssid = ssid,
            password = password,
            bssid = bssid,
            security = security,
            onConnected = onConnected
        )
    }


    // ============================================================
    // GENERIC LOCAL-ONLY WIFI REQUEST
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun requestLink(
        type: QubitLinkType,
        ssid: String,
        password: String,
        bssid: String?,
        security: String,
        onConnected: (Network) -> Unit
    ) {

        if (ssid.isBlank()) {

            updateLinkStatus(
                type,
                "SSID belum dipilih"
            )

            return
        }


        if (type == QubitLinkType.FC) {
            disconnectFc()
        } else {
            disconnectCamera()
        }


        updateLinkStatus(
            type,
            "Connecting $ssid ..."
        )


        try {

            val builder =
                WifiNetworkSpecifier
                    .Builder()
                    .setSsid(ssid)


            if (!bssid.isNullOrBlank()) {

                try {

                    builder.setBssid(
                        MacAddress.fromString(
                            bssid
                        )
                    )

                } catch (_: Exception) {
                }
            }


            if (password.isNotBlank()) {

                if (
                    security.contains(
                        "WPA3",
                        ignoreCase = true
                    ) ||
                    security.contains(
                        "SAE",
                        ignoreCase = true
                    )
                ) {

                    builder.setWpa3Passphrase(
                        password
                    )

                } else {

                    builder.setWpa2Passphrase(
                        password
                    )
                }
            }


            val specifier =
                builder.build()


            val request =
                NetworkRequest
                    .Builder()
                    .addTransportType(
                        NetworkCapabilities
                            .TRANSPORT_WIFI
                    )
                    .removeCapability(
                        NetworkCapabilities
                            .NET_CAPABILITY_INTERNET
                    )
                    .setNetworkSpecifier(
                        specifier
                    )
                    .build()


            val callback =
                object :
                    ConnectivityManager
                    .NetworkCallback() {


                    override fun onAvailable(
                        network: Network
                    ) {

                        // ESP-DRONE COMPATIBILITY MODE:
                        // buat WiFi FC menjadi jalur utama proses aplikasi.
                        if (
                            type ==
                            QubitLinkType.FC
                        ) {

                            try {

                                connectivityManager
                                    .bindProcessToNetwork(
                                        network
                                    )

                            } catch (
                                _: Exception
                            ) {
                            }
                        }


                        mainHandler.post {

                            if (
                                type ==
                                QubitLinkType.FC
                            ) {

                                fcNetworkInternal =
                                    network

                                isFcConnected =
                                    true

                                fcSsid =
                                    ssid

                                fcStatus =
                                    "FC CONNECTED • ESP-DRONE DIRECT"

                            } else {

                                cameraNetworkInternal =
                                    network

                                isCameraConnected =
                                    true

                                cameraSsid =
                                    ssid

                                cameraStatus =
                                    "CAMERA CONNECTED"
                            }
                        }


                        onConnected(network)
                    }


                    override fun onUnavailable() {

                        mainHandler.post {

                            if (
                                type ==
                                QubitLinkType.FC
                            ) {

                                isFcConnected =
                                    false

                                fcSsid = ""

                                fcStatus =
                                    "FC connection gagal / dibatalkan"

                            } else {

                                isCameraConnected =
                                    false

                                cameraSsid = ""

                                cameraStatus =
                                    "Camera connection gagal / dibatalkan"
                            }
                        }
                    }


                    override fun onLost(
                        network: Network
                    ) {

                        mainHandler.post {

                            if (
                                type ==
                                QubitLinkType.FC &&
                                fcNetworkInternal ==
                                network
                            ) {

                                fcNetworkInternal =
                                    null

                                isFcConnected =
                                    false

                                fcSsid = ""

                                fcStatus =
                                    "FC CONNECTION LOST"
                            }


                            if (
                                type ==
                                QubitLinkType.CAMERA &&
                                cameraNetworkInternal ==
                                network
                            ) {

                                cameraNetworkInternal =
                                    null

                                isCameraConnected =
                                    false

                                cameraSsid = ""

                                cameraStatus =
                                    "CAMERA CONNECTION LOST"
                            }
                        }
                    }
                }


            if (
                type ==
                QubitLinkType.FC
            ) {

                fcCallback = callback

            } else {

                cameraCallback = callback
            }


            connectivityManager
                .requestNetwork(
                    request,
                    callback
                )


        } catch (
            e: Exception
        ) {

            updateLinkStatus(
                type,
                "WiFi ERROR: ${e.message}"
            )
        }
    }


    // ============================================================
    // DISCONNECT FC ONLY
    // ============================================================

    fun disconnectFc() {

        fcCallback?.let {

            try {

                connectivityManager
                    .unregisterNetworkCallback(
                        it
                    )

            } catch (_: Exception) {
            }
        }


        fcCallback = null
        fcNetworkInternal = null

        // Kembalikan routing Android ke normal.
        try {

            connectivityManager
                .bindProcessToNetwork(
                    null
                )

        } catch (
            _: Exception
        ) {
        }

        mainHandler.post {
            isFcConnected = false
            fcSsid = ""
            fcStatus = "FC DISCONNECTED"
        }
    }


    // ============================================================
    // DISCONNECT CAMERA ONLY
    // ============================================================

    fun disconnectCamera() {

        cameraCallback?.let {

            try {

                connectivityManager
                    .unregisterNetworkCallback(
                        it
                    )

            } catch (_: Exception) {
            }
        }


        cameraCallback = null
        cameraNetworkInternal = null

        mainHandler.post {
            isCameraConnected = false
            cameraSsid = ""
            cameraStatus = "CAMERA DISCONNECTED"
        }
    }


    // ============================================================
    // COMPATIBILITY
    // ============================================================

    fun disconnect() =
        disconnectFc()


    private fun updateLinkStatus(
        type: QubitLinkType,
        text: String
    ) {

        mainHandler.post {

            if (
                type ==
                QubitLinkType.FC
            ) {
                fcStatus = text
            } else {
                cameraStatus = text
            }
        }
    }


    fun close() {

        removeScanReceiver()

        disconnectCamera()

        disconnectFc()
    }
}