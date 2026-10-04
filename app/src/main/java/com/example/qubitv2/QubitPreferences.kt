package com.example.qubitv2

import android.content.Context


data class CameraEndpoint(
    val host: String = "192.168.43.200",
    val port: Int = 81,
    val path: String = "/stream"
) {
    fun url(): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "http://$host:$port$cleanPath"
    }
}


class QubitPreferences(
    context: Context
) {

    private val prefs =
        context.getSharedPreferences(
            "qubit_v2_settings",
            Context.MODE_PRIVATE
        )


    // ============================================================
    // FLIGHT SETTINGS
    // ============================================================

    fun loadFlightSettings(): FlightSettings {

        return FlightSettings(
            flightMode = prefs.getInt("flight_mode", 2),
            deadzone = prefs.getFloat("deadzone", 0.20f),
            rollTrim = prefs.getFloat("roll_trim", 0.0f),
            pitchTrim = prefs.getFloat("pitch_trim", 0.0f),
            maxRollPitch = prefs.getInt("max_roll_pitch", 10),
            maxYaw = prefs.getInt("max_yaw", 45),
            maxThrust = prefs.getInt("max_thrust", 55),
            minThrust = prefs.getInt("min_thrust", 8),
            xMode = prefs.getInt("x_mode", 1),
            revCH1 = prefs.getBoolean("rev_ch1", false),
            revCH2 = prefs.getBoolean("rev_ch2", true),
            revCH3 = prefs.getBoolean("rev_ch3", true),
            revCH4 = prefs.getBoolean("rev_ch4", false)
        )
    }


    fun saveFlightSettings(
        settings: FlightSettings
    ) {

        prefs.edit()
            .putInt("flight_mode", settings.flightMode)
            .putFloat("deadzone", settings.deadzone)
            .putFloat("roll_trim", settings.rollTrim)
            .putFloat("pitch_trim", settings.pitchTrim)
            .putInt("max_roll_pitch", settings.maxRollPitch)
            .putInt("max_yaw", settings.maxYaw)
            .putInt("max_thrust", settings.maxThrust)
            .putInt("min_thrust", settings.minThrust)
            .putInt("x_mode", settings.xMode)
            .putBoolean("rev_ch1", settings.revCH1)
            .putBoolean("rev_ch2", settings.revCH2)
            .putBoolean("rev_ch3", settings.revCH3)
            .putBoolean("rev_ch4", settings.revCH4)
            .apply()
    }


    fun resetFlightSettings(): FlightSettings {

        val defaults = FlightSettings()

        saveFlightSettings(defaults)

        return defaults
    }


    // ============================================================
    // FC WIFI
    // ============================================================

    fun saveFcWifi(
        ssid: String,
        password: String
    ) {

        prefs.edit()
            .putString("fc_ssid", ssid)
            .putString("fc_password", password)
            .apply()
    }


    fun loadFcSsid(): String =
        prefs.getString("fc_ssid", "") ?: ""


    fun loadFcPassword(): String =
        prefs.getString("fc_password", "") ?: ""


    // ============================================================
    // CAMERA WIFI
    // ============================================================

    fun saveCameraWifi(
        ssid: String,
        password: String
    ) {

        prefs.edit()
            .putString("cam_ssid", ssid)
            .putString("cam_password", password)
            .apply()
    }


    fun loadCameraSsid(): String =
        prefs.getString("cam_ssid", "") ?: ""


    fun loadCameraPassword(): String =
        prefs.getString("cam_password", "") ?: ""


    // ============================================================
    // CAMERA STREAM ENDPOINT
    // ============================================================

    fun saveCameraEndpoint(
        endpoint: CameraEndpoint
    ) {

        prefs.edit()
            .putString("cam_host", endpoint.host)
            .putInt("cam_port", endpoint.port)
            .putString("cam_path", endpoint.path)
            .apply()
    }


    fun loadCameraEndpoint(): CameraEndpoint {

        val savedHost =
            prefs.getString(
                "cam_host",
                "192.168.43.200"
            ) ?: "192.168.43.200"

        // Migrasi otomatis dari desain lama:
        // 192.168.4.1 adalah alamat saat ESP32-CAM menjadi AP sendiri.
        // Sekarang kamera menjadi STA pada jaringan FC.
        val resolvedHost =
            if (
                savedHost ==
                "192.168.4.1"
            ) {
                "192.168.43.200"
            } else {
                savedHost
            }

        return CameraEndpoint(
            host = resolvedHost,

            port = prefs.getInt(
                "cam_port",
                81
            ),

            path = prefs.getString(
                "cam_path",
                "/stream"
            ) ?: "/stream"
        )
    }
}