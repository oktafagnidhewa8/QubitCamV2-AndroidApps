package com.example.qubitv2

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer

val QubitBackground = Color(0xFF07111F)
val QubitSurface = Color(0xFF0D1B2E)
val QubitSurface2 = Color(0xFF12243A)
val QubitBlue = Color(0xFF168BFF)
val QubitBlueLight = Color(0xFF55B6FF)
val QubitBlueDark = Color(0xFF075BAC)
val QubitBorder = Color(0xFF213B59)
val QubitText = Color(0xFFF3F8FF)
val QubitTextSecondary = Color(0xFF91A7C2)
val QubitGreen = Color(0xFF38D996)
val QubitRed = Color(0xFFFF5D67)
val QubitOrange = Color(0xFFFFB547)

// ============================================================
// ESP-DRONE FLIGHT CONTROLLER ENDPOINT
// Firmware ESP-Drone Qubit memakai AP/gateway 192.168.43.42
// dan UDP control port 2390.
// ============================================================
const val ESP_DRONE_FC_IP = "192.168.43.42"
const val ESP_DRONE_FC_PORT = 2390

const val ESP_CAM_IP = "192.168.43.200"
const val ESP_CAM_PORT = 81
const val ESP_CAM_PATH = "/stream"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = QubitBlue,
                    secondary = QubitBlueLight,
                    background = QubitBackground,
                    surface = QubitSurface
                )
            ) { QubitApp() }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

enum class QubitScreen { MAIN, FLIGHT, CONNECTION_FC, SETTINGS, REVERSE, CAMERA }
enum class QubitMenuIconType { FLIGHT, WIFI, SETTINGS, REVERSE, CAMERA }
data class QubitMenuItem(
    val icon: QubitMenuIconType,
    val title: String,
    val description: String,
    val action: () -> Unit
)

@Composable
fun QubitApp() {
    val context = LocalContext.current

    val activity =
        context as? Activity

    var showSplash by
    remember {
        mutableStateOf(true)
    }

    var loadingProgress by
    remember {
        mutableIntStateOf(0)
    }

    // ========================================================
    // PRELOAD DRONE V2 OFF THE MAIN/UI THREAD
    //
    // Tujuan:
    // - hindari gambar muncul setengah pada frame pertama
    // - splash baru tampil setelah bitmap drone siap penuh
    // - loading progress baru berjalan setelah asset selesai decode
    // ========================================================
    val splashDroneBitmap by
    produceState<ImageBitmap?>(
        initialValue = null,
        context
    ) {
        value =
            withContext(
                Dispatchers.IO
            ) {
                BitmapFactory
                    .decodeResource(
                        context.resources,
                        R.drawable.qubit_drone_v2
                    )
                    ?.asImageBitmap()
            }
    }

    LaunchedEffect(
        splashDroneBitmap
    ) {

        if (
            splashDroneBitmap ==
            null
        ) {
            return@LaunchedEffect
        }

        loadingProgress = 0

        for (
        value in 0..100
        ) {

            loadingProgress =
                value

            val delayMs =
                when {
                    value < 20 -> 12L
                    value < 65 -> 18L
                    value < 90 -> 24L
                    else -> 32L
                }

            delay(
                delayMs
            )
        }

        delay(
            220L
        )

        showSplash =
            false
    }

    val preferences = remember { QubitPreferences(context) }
    val engine = remember { QubitControllerEngine(preferences.loadFlightSettings()) }
    val wifiManager = remember { QubitWifiManager(context) }
    val cameraStream = remember { CameraStreamManager() }
    var screen by remember { mutableStateOf(QubitScreen.MAIN) }

    var pendingWifiAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) pendingWifiAction?.invoke()
        pendingWifiAction = null
    }

    fun runWithScanPermission(action: () -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action() else {
            pendingWifiAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun runWithConnectionPermission(action: () -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action() else {
            pendingWifiAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    DisposableEffect(Unit) {
        engine.start()
        onDispose {
            cameraStream.close()
            wifiManager.close()
            engine.close()
        }
    }

    if (showSplash) {

        val droneBitmap =
            splashDroneBitmap

        if (
            droneBitmap ==
            null
        ) {
            QubitPreloadScreen()
        } else {
            QubitSplashScreen(
                progress =
                    loadingProgress,
                droneBitmap =
                    droneBitmap
            )
        }

    } else {

        when (screen) {
            QubitScreen.MAIN -> MainMenuScreen(
                wifiManager, engine, cameraStream,
                onFlight = { screen = QubitScreen.FLIGHT },
                onConnection = { screen = QubitScreen.CONNECTION_FC },
                onSettings = { screen = QubitScreen.SETTINGS },
                onReverse = { screen = QubitScreen.REVERSE },
                onCamera = { screen = QubitScreen.CAMERA },

                onExit = {

                    cameraStream.stop()

                    engine.disarm()

                    activity?.finishAndRemoveTask()
                }
            )

            QubitScreen.FLIGHT -> FlightScreen(
                engine, wifiManager, cameraStream, preferences,
                onBack = {
                    // FPV connection is intentionally kept alive here.
                    // Leaving Flight Control only hides the video.
                    // Camera is disconnected only from FPV Camera menu
                    // (or when FC network itself is disconnected).
                    engine.disarm()
                    screen = QubitScreen.MAIN
                }
            )

            QubitScreen.CONNECTION_FC -> FcConnectionScreen(
                wifiManager = wifiManager,
                preferences = preferences,
                onScan = { runWithScanPermission { wifiManager.scanNetworks() } },
                onConnect = { network, password ->
                    runWithConnectionPermission {
                        preferences.saveFcWifi(network.ssid, password)
                        wifiManager.connectFc(
                            network.ssid,
                            password,
                            network.bssid,
                            network.securityLabel
                        ) { fcNetwork ->

                            // Firmware ESP-Drone Qubit memiliki endpoint
                            // kontrol tetap 192.168.43.42:2390.
                            //
                            // Jangan memakai gateway Android di sini karena
                            // beberapa perangkat melaporkan 0.0.0.0 untuk
                            // local-only WiFi Network.
                            engine.attachNetwork(
                                fcNetwork,
                                ESP_DRONE_FC_IP
                            )
                        }
                    }
                },
                onManualConnect = { ssid, password ->
                    runWithConnectionPermission {
                        preferences.saveFcWifi(ssid, password)
                        wifiManager.connectFc(
                            ssid,
                            password,
                            security =
                                if (password.isBlank())
                                    "OPEN"
                                else
                                    "WPA2"
                        ) { fcNetwork ->

                            // Sama dengan koneksi hasil scan:
                            // target UDP FC ditetapkan langsung mengikuti
                            // firmware ESP-Drone Qubit.
                            engine.attachNetwork(
                                fcNetwork,
                                ESP_DRONE_FC_IP
                            )
                        }
                    }
                },
                onDisconnect = {
                    cameraStream.stop()
                    engine.detachNetwork()
                    wifiManager.disconnectFc()
                },
                onBack = { screen = QubitScreen.MAIN }
            )

            QubitScreen.CAMERA -> CameraConnectionScreen(
                wifiManager = wifiManager,
                preferences = preferences,
                cameraStream = cameraStream,
                onBack = { screen = QubitScreen.MAIN }
            )

            QubitScreen.SETTINGS -> FlightSettingsScreen(
                engine, preferences, onBack = { screen = QubitScreen.MAIN }
            )

            QubitScreen.REVERSE -> ChannelReverseScreen(
                engine, preferences, onBack = { screen = QubitScreen.MAIN }
            )
        }
    }

}


// ============================================================
// STARTUP / LOADING PAGE
// ============================================================

@Composable
fun QubitPreloadScreen() {

    // Sangat ringan: tidak memuat bitmap besar.
    // Ditampilkan hanya selama drone V2 sedang didecode di background.
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors =
                            listOf(
                                Color(0xFFF8FAFD),
                                Color(0xFFF8FAFD),
                                Color(0xFF06192E)
                            )
                    )
                ),
        contentAlignment =
            Alignment.Center
    ) {

        Text(
            text =
                "QUBIT V2",
            color =
                Color(0xFF0B416F),
            fontSize =
                20.sp,
            fontWeight =
                FontWeight.Bold,
            letterSpacing =
                3.sp
        )
    }
}


@Composable
fun QubitSplashScreen(
    progress: Int,
    droneBitmap: ImageBitmap
) {

    val splashWhite =
        Color(0xFFF8FAFD)

    val splashNavy =
        Color(0xFF06192E)

    val splashBlue =
        Color(0xFF0B416F)

    val splashBlueLight =
        Color(0xFF36A8FF)

    val splashRed =
        Color(0xFFE84250)

    val splashGray =
        Color(0xFF718397)


    // ========================================================
    // DIAGONAL PANELS
    // ========================================================

    val leftShape =
        GenericShape {
                size,
                _ ->

            moveTo(
                0f,
                0f
            )

            lineTo(
                size.width * 0.49f,
                0f
            )

            lineTo(
                size.width * 0.37f,
                size.height
            )

            lineTo(
                0f,
                size.height
            )

            close()
        }


    val rightShape =
        GenericShape {
                size,
                _ ->

            moveTo(
                size.width * 0.47f,
                0f
            )

            lineTo(
                size.width,
                0f
            )

            lineTo(
                size.width,
                size.height
            )

            lineTo(
                size.width * 0.35f,
                size.height
            )

            close()
        }


    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    splashWhite
                )
                .safeDrawingPadding()
    ) {

        // ====================================================
        // LEFT PANEL
        // ====================================================

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(
                        leftShape
                    )
                    .background(
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    Color.White,
                                    splashWhite,
                                    Color(
                                        0xFFEDF4FA
                                    )
                                )
                        )
                    )
        )


        // ====================================================
        // RIGHT PANEL
        // Gradient murni supaya gambar PNG transparan drone V2
        // tetap bersih dan tidak bergantung background foto lama.
        // ====================================================

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(
                        rightShape
                    )
                    .background(
                        Brush.linearGradient(
                            colors =
                                listOf(
                                    Color(
                                        0xFF061A30
                                    ),
                                    Color(
                                        0xFF0B3A63
                                    ),
                                    Color(
                                        0xFF082644
                                    ),
                                    Color(
                                        0xFF041425
                                    )
                                )
                        )
                    )
        )


        // ====================================================
        // TECH BACKGROUND
        // Mengisi area biru tanpa membuatnya ramai.
        // ====================================================

        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(
                        rightShape
                    )
        ) {

            val cx =
                size.width * 0.73f

            val cy =
                size.height * 0.38f


            drawCircle(
                color =
                    splashBlueLight.copy(
                        alpha = 0.08f
                    ),
                radius =
                    size.minDimension *
                            0.28f,
                center =
                    Offset(
                        cx,
                        cy
                    )
            )


            drawCircle(
                color =
                    splashBlueLight.copy(
                        alpha = 0.16f
                    ),
                radius =
                    size.minDimension *
                            0.23f,
                center =
                    Offset(
                        cx,
                        cy
                    ),
                style =
                    Stroke(
                        width = 1.2f
                    )
            )


            drawCircle(
                color =
                    splashBlueLight.copy(
                        alpha = 0.09f
                    ),
                radius =
                    size.minDimension *
                            0.18f,
                center =
                    Offset(
                        cx,
                        cy
                    ),
                style =
                    Stroke(
                        width = 1f
                    )
            )


            drawLine(
                color =
                    splashBlueLight.copy(
                        alpha = 0.08f
                    ),
                start =
                    Offset(
                        size.width *
                                0.47f,
                        size.height *
                                0.18f
                    ),
                end =
                    Offset(
                        size.width,
                        size.height *
                                0.02f
                    ),
                strokeWidth =
                    1f
            )


            drawLine(
                color =
                    splashBlueLight.copy(
                        alpha = 0.06f
                    ),
                start =
                    Offset(
                        size.width *
                                0.40f,
                        size.height *
                                0.83f
                    ),
                end =
                    Offset(
                        size.width,
                        size.height *
                                0.58f
                    ),
                strokeWidth =
                    1f
            )
        }


        // ====================================================
        // QUBIT DRONE V2
        // ====================================================

        Image(
            bitmap =
                droneBitmap,

            contentDescription =
                "Qubit V2 Drone",

            contentScale =
                ContentScale.Fit,

            alignment =
                Alignment.Center,

            modifier =
                Modifier
                    .align(
                        Alignment.TopEnd
                    )
                    .fillMaxWidth(
                        0.60f
                    )
                    .fillMaxHeight(
                        0.73f
                    )
                    .padding(
                        top = 18.dp,
                        end = 18.dp
                    )
                    .graphicsLayer(
                        scaleX = 1.03f,
                        scaleY = 1.03f,
                        translationX = 10f,
                        translationY = 0f
                    )
        )


        // ====================================================
        // DIAGONAL ACCENTS
        // ====================================================

        Canvas(
            modifier =
                Modifier.fillMaxSize()
        ) {

            drawLine(
                color =
                    splashRed,

                start =
                    Offset(
                        size.width *
                                0.48f,
                        0f
                    ),

                end =
                    Offset(
                        size.width *
                                0.36f,
                        size.height
                    ),

                strokeWidth =
                    5f,

                cap =
                    StrokeCap.Round
            )


            drawLine(
                color =
                    splashBlueLight.copy(
                        alpha = 0.34f
                    ),

                start =
                    Offset(
                        size.width *
                                0.489f,
                        0f
                    ),

                end =
                    Offset(
                        size.width *
                                0.369f,
                        size.height
                    ),

                strokeWidth =
                    1.5f
            )
        }


        // ====================================================
        // LEFT CONTENT
        // ====================================================

        Column(
            modifier =
                Modifier
                    .align(
                        Alignment.CenterStart
                    )
                    .fillMaxWidth(
                        0.40f
                    )
                    .padding(
                        start = 48.dp,
                        end = 18.dp
                    )
        ) {

            Box(
                modifier =
                    Modifier
                        .width(
                            38.dp
                        )
                        .height(
                            4.dp
                        )
                        .background(
                            splashRed,
                            RoundedCornerShape(
                                20.dp
                            )
                        )
            )


            Spacer(
                Modifier.height(
                    12.dp
                )
            )


            Text(
                text =
                    "QUBIT V2",

                color =
                    splashNavy,

                fontSize =
                    31.sp,

                fontWeight =
                    FontWeight.Bold,

                letterSpacing =
                    2.5.sp
            )


            Text(
                text =
                    "ANDROID FLIGHT CONTROL",

                color =
                    splashBlue,

                fontSize =
                    11.sp,

                fontWeight =
                    FontWeight.Bold,

                letterSpacing =
                    1.7.sp
            )


            Spacer(
                Modifier.height(
                    4.dp
                )
            )


            Text(
                text =
                    "CONTROL • FPV • CONNECTIVITY",

                color =
                    splashGray,

                fontSize =
                    7.sp,

                fontWeight =
                    FontWeight.Bold,

                letterSpacing =
                    1.2.sp
            )


            Spacer(
                Modifier.height(
                    31.dp
                )
            )


            // ================================================
            // AMX UAV ONLY
            // Tidak ada DEVELOPED BY / INDUSTRY PARTNER.
            // ================================================

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                // =================================================
                // AMX UAV LOGO - dibuat lebih dominan
                // =================================================
                Image(
                    painter =
                        painterResource(
                            id =
                                R.drawable
                                    .logo_amx_uav
                        ),

                    contentDescription =
                        "AMX UAV",

                    modifier =
                        Modifier
                            .width(
                                104.dp
                            )
                            .height(
                                82.dp
                            ),

                    contentScale =
                        ContentScale.Fit
                )


                Spacer(
                    Modifier.width(
                        12.dp
                    )
                )


                // Divider tipis
                Box(
                    modifier =
                        Modifier
                            .width(
                                1.dp
                            )
                            .height(
                                62.dp
                            )
                            .background(
                                splashGray.copy(
                                    alpha = 0.24f
                                )
                            )
                )


                Spacer(
                    Modifier.width(
                        13.dp
                    )
                )


                // =================================================
                // TEKS KANAN - lebih kecil agar logo jadi fokus
                // =================================================
                Column(
                    verticalArrangement =
                        Arrangement.Center
                ) {

                    Text(
                        text =
                            "AMX UAV",

                        color =
                            splashNavy,

                        fontSize =
                            13.sp,

                        fontWeight =
                            FontWeight.Bold,

                        letterSpacing =
                            0.35.sp
                    )


                    Spacer(
                        Modifier.height(
                            3.dp
                        )
                    )


                    Text(
                        text =
                            "UAV TECHNOLOGY",

                        color =
                            splashRed,

                        fontSize =
                            7.sp,

                        fontWeight =
                            FontWeight.SemiBold,

                        letterSpacing =
                            1.0.sp
                    )


                    Spacer(
                        Modifier.height(
                            4.dp
                        )
                    )


                    Text(
                        text =
                            "AERIAL ROBOTICS • FLIGHT SYSTEMS",

                        color =
                            splashGray,

                        fontSize =
                            5.5.sp,

                        fontWeight =
                            FontWeight.Medium,

                        letterSpacing =
                            0.55.sp
                    )
                }
            }


            Spacer(
                Modifier.height(
                    30.dp
                )
            )


            // Elemen kecil untuk menyeimbangkan area putih
            Text(
                text =
                    "QUBIT V2 FLIGHT PLATFORM",

                color =
                    splashBlue.copy(
                        alpha = 0.75f
                    ),

                fontSize =
                    6.sp,

                fontWeight =
                    FontWeight.Bold,

                letterSpacing =
                    1.7.sp
            )


            Spacer(
                Modifier.height(
                    6.dp
                )
            )


            Box(
                modifier =
                    Modifier
                        .width(
                            26.dp
                        )
                        .height(
                            2.dp
                        )
                        .background(
                            splashRed,
                            RoundedCornerShape(
                                10.dp
                            )
                        )
            )
        }


        // ====================================================
        // RIGHT CONTENT
        // ====================================================

        Column(
            modifier =
                Modifier
                    .align(
                        Alignment.BottomEnd
                    )
                    .fillMaxWidth(
                        0.51f
                    )
                    .padding(
                        end = 55.dp,
                        bottom = 43.dp
                    )
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Box(
                    modifier =
                        Modifier
                            .size(
                                7.dp
                            )
                            .background(
                                splashRed,
                                CircleShape
                            )
                )


                Spacer(
                    Modifier.width(
                        8.dp
                    )
                )


                Text(
                    text =
                        "FLIGHT SYSTEM STARTUP",

                    color =
                        splashBlueLight,

                    fontSize =
                        8.sp,

                    fontWeight =
                        FontWeight.Bold,

                    letterSpacing =
                        1.8.sp
                )
            }


            Spacer(
                Modifier.height(
                    7.dp
                )
            )


            Text(
                text =
                    when {

                        progress < 20 ->
                            "Initializing\nQubit Platform"

                        progress < 55 ->
                            "Loading\nFlight Control"

                        progress < 80 ->
                            "Preparing\nWireless Links"

                        progress < 100 ->
                            "Finalizing\nFlight Interface"

                        else ->
                            "System\nReady"
                    },

                color =
                    Color.White,

                fontSize =
                    23.sp,

                fontWeight =
                    FontWeight.Bold,

                lineHeight =
                    25.sp
            )


            Spacer(
                Modifier.height(
                    13.dp
                )
            )


            Row(
                modifier =
                    Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.SpaceBetween,

                verticalAlignment =
                    Alignment.Bottom
            ) {

                Text(
                    text =
                        "Qubit V2 Control Engine",

                    color =
                        Color.White.copy(
                            alpha = 0.60f
                        ),

                    fontSize =
                        8.sp
                )


                Text(
                    text =
                        "$progress%",

                    color =
                        Color.White,

                    fontSize =
                        19.sp,

                    fontWeight =
                        FontWeight.Bold
                )
            }


            Spacer(
                Modifier.height(
                    6.dp
                )
            )


            LinearProgressIndicator(

                progress =
                    progress
                        .coerceIn(
                            0,
                            100
                        ) /
                            100f,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            5.dp
                        ),

                color =
                    splashBlueLight,

                trackColor =
                    Color.White.copy(
                        alpha = 0.17f
                    )
            )
        }


        // ====================================================
        // RIGHT TOP MICRO BRANDING
        // ====================================================

        Column(
            modifier =
                Modifier
                    .align(
                        Alignment.TopEnd
                    )
                    .padding(
                        top = 24.dp,
                        end = 38.dp
                    ),
            horizontalAlignment =
                Alignment.End
        ) {

            Text(
                text =
                    "PRECISION",

                color =
                    Color.White.copy(
                        alpha = 0.55f
                    ),

                fontSize =
                    6.sp,

                fontWeight =
                    FontWeight.Bold,

                letterSpacing =
                    2.sp
            )


            Text(
                text =
                    "STABILITY • CONNECTED",

                color =
                    Color.White.copy(
                        alpha = 0.42f
                    ),

                fontSize =
                    5.sp,

                fontWeight =
                    FontWeight.Medium,

                letterSpacing =
                    1.2.sp
            )


            Spacer(
                Modifier.height(
                    5.dp
                )
            )


            Box(
                modifier =
                    Modifier
                        .width(
                            22.dp
                        )
                        .height(
                            2.dp
                        )
                        .background(
                            splashRed,
                            RoundedCornerShape(
                                10.dp
                            )
                        )
            )
        }
    }
}


@Composable
fun QubitBackgroundContainer(
    showSilhouette: Boolean = true,
    content: @Composable () -> Unit
) {

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0B2138),
                        Color(0xFF08182A),
                        Color(0xFF06111E)
                    )
                )
            )
            .safeDrawingPadding()
    ) {

        // ========================================================
        // SOFT BLUE AMBIENT LIGHT
        // Membuat background tidak terasa hitam/polos.
        // ========================================================

        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        QubitBlue.copy(alpha = 0.13f),
                        Color.Transparent
                    ),
                    center = Offset(
                        size.width * 0.18f,
                        size.height * 0.78f
                    ),
                    radius = size.minDimension * 0.65f
                ),
                radius = size.minDimension * 0.65f,
                center = Offset(
                    size.width * 0.18f,
                    size.height * 0.78f
                )
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        QubitBlueLight.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = Offset(
                        size.width * 0.86f,
                        size.height * 0.18f
                    ),
                    radius = size.minDimension * 0.50f
                ),
                radius = size.minDimension * 0.50f,
                center = Offset(
                    size.width * 0.86f,
                    size.height * 0.18f
                )
            )


            // ----------------------------------------------------
            // Garis horizon teknologi yang sangat tipis.
            // ----------------------------------------------------

            drawLine(
                color = QubitBlue.copy(alpha = 0.045f),
                start = Offset(
                    0f,
                    size.height * 0.74f
                ),
                end = Offset(
                    size.width,
                    size.height * 0.52f
                ),
                strokeWidth = 1.5f
            )

            drawLine(
                color = QubitBlueLight.copy(alpha = 0.03f),
                start = Offset(
                    size.width * 0.18f,
                    size.height
                ),
                end = Offset(
                    size.width * 0.80f,
                    0f
                ),
                strokeWidth = 1.2f
            )
        }


        // ========================================================
        // SILUET DRONE
        // Ditampilkan pada menu/setting, tetapi dimatikan pada
        // Flight Control agar joystick tetap sangat jelas.
        // ========================================================

        if (showSilhouette) {

            QubitDroneSilhouette(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(330.dp),
                alpha = 0.075f
            )

            QubitDroneSilhouette(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = 12.dp,
                        end = 18.dp
                    )
                    .size(210.dp),
                alpha = 0.045f
            )
        }


        // Konten utama selalu berada paling depan.
        content()
    }
}


// ============================================================
// DECORATIVE DRONE SILHOUETTE
// Digambar langsung dengan Canvas.
// Tidak perlu asset PNG/SVG tambahan.
// ============================================================

@Composable
fun QubitDroneSilhouette(
    modifier: Modifier = Modifier,
    alpha: Float = 0.07f
) {

    Canvas(
        modifier = modifier
    ) {

        val lineColor =
            QubitBlueLight.copy(
                alpha = alpha
            )

        val bodyColor =
            QubitBlue.copy(
                alpha = alpha * 1.20f
            )

        val centerX =
            size.width / 2f

        val centerY =
            size.height / 2f

        val armX =
            size.width * 0.28f

        val armY =
            size.height * 0.23f

        val rotorRadius =
            size.minDimension * 0.12f

        val motorRadius =
            size.minDimension * 0.032f


        // --------------------------------------------------------
        // FRAME X
        // --------------------------------------------------------

        drawLine(
            color = lineColor,
            start = Offset(
                centerX - armX,
                centerY - armY
            ),
            end = Offset(
                centerX + armX,
                centerY + armY
            ),
            strokeWidth =
                size.minDimension * 0.026f,
            cap = StrokeCap.Round
        )

        drawLine(
            color = lineColor,
            start = Offset(
                centerX + armX,
                centerY - armY
            ),
            end = Offset(
                centerX - armX,
                centerY + armY
            ),
            strokeWidth =
                size.minDimension * 0.026f,
            cap = StrokeCap.Round
        )


        // --------------------------------------------------------
        // CENTRAL BODY
        // --------------------------------------------------------

        drawCircle(
            color = bodyColor,
            radius =
                size.minDimension * 0.105f,
            center = Offset(
                centerX,
                centerY
            )
        )

        drawCircle(
            color = lineColor,
            radius =
                size.minDimension * 0.14f,
            center = Offset(
                centerX,
                centerY
            ),
            style = Stroke(
                width =
                    size.minDimension * 0.012f
            )
        )


        // --------------------------------------------------------
        // FOUR MOTORS / PROPELLER GUARDS
        // --------------------------------------------------------

        val motorCenters =
            listOf(
                Offset(
                    centerX - armX,
                    centerY - armY
                ),
                Offset(
                    centerX + armX,
                    centerY - armY
                ),
                Offset(
                    centerX - armX,
                    centerY + armY
                ),
                Offset(
                    centerX + armX,
                    centerY + armY
                )
            )


        motorCenters.forEach { motor ->

            // Guard / propeller circle
            drawCircle(
                color = lineColor,
                radius = rotorRadius,
                center = motor,
                style = Stroke(
                    width =
                        size.minDimension * 0.015f
                )
            )

            // Motor
            drawCircle(
                color = bodyColor,
                radius = motorRadius,
                center = motor
            )

            // Propeller horizontal
            drawLine(
                color = lineColor,
                start = Offset(
                    motor.x - rotorRadius * 0.72f,
                    motor.y
                ),
                end = Offset(
                    motor.x + rotorRadius * 0.72f,
                    motor.y
                ),
                strokeWidth =
                    size.minDimension * 0.010f,
                cap = StrokeCap.Round
            )

            // Propeller vertical
            drawLine(
                color = lineColor,
                start = Offset(
                    motor.x,
                    motor.y - rotorRadius * 0.72f
                ),
                end = Offset(
                    motor.x,
                    motor.y + rotorRadius * 0.72f
                ),
                strokeWidth =
                    size.minDimension * 0.010f,
                cap = StrokeCap.Round
            )
        }


        // --------------------------------------------------------
        // SMALL FRONT CAMERA / DIRECTION MARK
        // --------------------------------------------------------

        drawCircle(
            color = lineColor,
            radius =
                size.minDimension * 0.028f,
            center = Offset(
                centerX,
                centerY - size.height * 0.095f
            )
        )
    }
}

@Composable
fun MainMenuScreen(
    wifiManager: QubitWifiManager,
    engine: QubitControllerEngine,
    cameraStream: CameraStreamManager,
    onFlight: () -> Unit,
    onConnection: () -> Unit,
    onSettings: () -> Unit,
    onReverse: () -> Unit,
    onCamera: () -> Unit,
    onExit: () -> Unit
) {
    val menuItems = listOf(
        QubitMenuItem(QubitMenuIconType.FLIGHT, "Flight Control",
            "Virtual joystick, ARM, DISARM dan monitor penerbangan", onFlight),
        QubitMenuItem(QubitMenuIconType.WIFI, "Connection FC",
            "Pair dan hubungkan Android dengan Flight Controller", onConnection),
        QubitMenuItem(QubitMenuIconType.SETTINGS, "Flight Settings",
            "Deadzone, trim, sudut, yaw, thrust dan X-Mode", onSettings),
        QubitMenuItem(QubitMenuIconType.REVERSE, "Channel Reverse",
            "Pengaturan arah CH1, CH2, CH3 dan CH4", onReverse),
        QubitMenuItem(QubitMenuIconType.CAMERA, "FPV Camera",
            "Live video ESP32-CAM melalui jaringan Flight Controller", onCamera)
    )

    QubitBackgroundContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(24.dp, 14.dp, 24.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                QubitMainHeader(
                    onExit = onExit
                )
            }
            item { DualConnectionStatusCard(wifiManager, engine, cameraStream) }
            item {
                Text(
                    "CONTROL CENTER", color = QubitTextSecondary, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
                    modifier = Modifier.fillMaxWidth(0.72f).padding(top = 7.dp)
                )
            }
            items(menuItems) { ModernMenuCard(it) }
            item { DeveloperCollaborationCard() }
        }
    }
}

@Composable
fun QubitMainHeader(
    onExit: () -> Unit
) {

    Box(
        modifier =
            Modifier.fillMaxWidth(0.72f)
    ) {

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 86.dp
                    ),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Text(
                text = "QUBIT",
                color = QubitBlueLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            )

            Text(
                text = "FLIGHT CONTROL",
                color = QubitText,
                fontSize = 29.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Qubit V2 Android Control Platform",
                color = QubitTextSecondary,
                fontSize = 12.sp
            )
        }


        OutlinedButton(
            modifier =
                Modifier.align(
                    Alignment.CenterEnd
                ),

            onClick =
                onExit,

            shape =
                RoundedCornerShape(
                    11.dp
                ),

            border =
                androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color =
                        QubitRed.copy(
                            alpha = 0.55f
                        )
                )
        ) {

            Text(
                text = "EXIT",
                color = QubitRed,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }
    }
}

@Composable
fun DualConnectionStatusCard(
    wifiManager: QubitWifiManager,
    engine: QubitControllerEngine,
    cameraStream: CameraStreamManager
) {
    Card(
        modifier = Modifier.fillMaxWidth(0.72f)
            .border(1.dp, QubitBorder, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = QubitSurface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LinkStatusItem(
                "FLIGHT CONTROLLER",
                wifiManager.isFcConnected,
                wifiManager.fcSsid,
                if (wifiManager.isFcConnected) engine.status else wifiManager.fcStatus,
                Modifier.weight(1f)
            )

            Box(
                Modifier.width(1.dp).height(42.dp).background(QubitBorder)
            )

            LinkStatusItem(
                "FPV CAMERA",
                cameraStream.isStreaming,
                "$ESP_CAM_IP:$ESP_CAM_PORT",
                when {
                    cameraStream.isStreaming ->
                        "CONNECTED • $ESP_CAM_IP:$ESP_CAM_PORT"
                    wifiManager.isFcConnected ->
                        "NOT CONNECTED • OPEN FPV CAMERA"
                    else ->
                        "FC NETWORK REQUIRED"
                },
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun LinkStatusItem(
    title: String, connected: Boolean, ssid: String, status: String, modifier: Modifier = Modifier
) {
    Row(modifier.padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (connected) QubitGreen else QubitRed, CircleShape))
        Spacer(Modifier.width(9.dp))
        Column {
            Text(title, color = QubitTextSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(if (connected) ssid else "OFFLINE",
                color = if (connected) QubitText else QubitRed,
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(status, color = QubitTextSecondary, fontSize = 8.sp)
        }
    }
}

@Composable
fun ModernMenuCard(item: QubitMenuItem) {
    Card(
        onClick = item.action,
        modifier = Modifier.fillMaxWidth(0.72f)
            .border(1.dp, QubitBorder, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = QubitSurface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MenuIconBox(item.icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, color = QubitText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(item.description, color = QubitTextSecondary, fontSize = 10.sp)
            }
            Text("›", color = QubitBlueLight, fontSize = 27.sp)
        }
    }
}

@Composable
fun MenuIconBox(type: QubitMenuIconType) {
    Box(
        Modifier.size(47.dp).background(
            Brush.linearGradient(listOf(QubitBlue, QubitBlueDark)), RoundedCornerShape(14.dp)
        ), contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(27.dp)) {
            val c = Color.White
            when (type) {
                QubitMenuIconType.FLIGHT -> {
                    val a = size.minDimension * .31f
                    drawLine(c, Offset(center.x-a, center.y), Offset(center.x+a, center.y), 3.2f, cap=StrokeCap.Round)
                    drawLine(c, Offset(center.x, center.y-a), Offset(center.x, center.y+a), 3.2f, cap=StrokeCap.Round)
                    drawCircle(c, size.minDimension*.09f, center)
                    listOf(
                        Offset(center.x-a, center.y), Offset(center.x+a, center.y),
                        Offset(center.x, center.y-a), Offset(center.x, center.y+a)
                    ).forEach { drawCircle(c, size.minDimension*.11f, it, style=Stroke(2.2f)) }
                }
                QubitMenuIconType.WIFI -> {
                    drawArc(c,210f,120f,false,Offset(size.width*.05f,size.height*.18f),
                        Size(size.width*.9f,size.height*.74f), style=Stroke(2.6f, cap=StrokeCap.Round))
                    drawArc(c,215f,110f,false,Offset(size.width*.22f,size.height*.39f),
                        Size(size.width*.56f,size.height*.44f), style=Stroke(2.6f, cap=StrokeCap.Round))
                    drawCircle(c,size.minDimension*.07f,Offset(center.x,size.height*.78f))
                }
                QubitMenuIconType.SETTINGS -> {
                    val ys=listOf(size.height*.27f,size.height*.5f,size.height*.73f)
                    val ks=listOf(.68f,.36f,.58f)
                    ys.forEachIndexed { i,y ->
                        drawLine(c,Offset(size.width*.15f,y),Offset(size.width*.85f,y),2.5f,cap=StrokeCap.Round)
                        drawCircle(c,size.minDimension*.07f,Offset(size.width*ks[i],y))
                    }
                }
                QubitMenuIconType.REVERSE -> {
                    drawLine(c,Offset(size.width*.18f,size.height*.36f),Offset(size.width*.78f,size.height*.36f),2.7f,cap=StrokeCap.Round)
                    drawLine(c,Offset(size.width*.78f,size.height*.36f),Offset(size.width*.64f,size.height*.23f),2.7f,cap=StrokeCap.Round)
                    drawLine(c,Offset(size.width*.78f,size.height*.64f),Offset(size.width*.18f,size.height*.64f),2.7f,cap=StrokeCap.Round)
                    drawLine(c,Offset(size.width*.18f,size.height*.64f),Offset(size.width*.32f,size.height*.77f),2.7f,cap=StrokeCap.Round)
                }
                QubitMenuIconType.CAMERA -> {
                    drawRoundRect(c,Offset(size.width*.15f,size.height*.28f),Size(size.width*.7f,size.height*.5f),
                        androidx.compose.ui.geometry.CornerRadius(4f,4f),style=Stroke(2.5f))
                    drawCircle(c,size.minDimension*.13f,Offset(size.width*.5f,size.height*.53f),style=Stroke(2.5f))
                    drawLine(c,Offset(size.width*.3f,size.height*.28f),Offset(size.width*.38f,size.height*.18f),2.5f)
                    drawLine(c,Offset(size.width*.38f,size.height*.18f),Offset(size.width*.55f,size.height*.18f),2.5f)
                }
            }
        }
    }
}

@Composable
fun DeveloperCollaborationCard() {
    Column(
        modifier = Modifier.fillMaxWidth(0.72f).padding(top = 7.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            Modifier.fillMaxWidth(), RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = QubitBlue.copy(alpha=.07f))
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal=22.dp, vertical=13.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("DEVELOPED BY", color=QubitTextSecondary, fontSize=8.sp,
                        fontWeight=FontWeight.Bold, letterSpacing=1.3.sp)
                    Spacer(Modifier.height(3.dp))
                    Text("Universitas Gadjah Mada", color=QubitText, fontSize=13.sp, fontWeight=FontWeight.Bold)
                    Text("UGM", color=QubitBlueLight, fontSize=9.sp)
                }
                Box(Modifier.width(1.dp).height(36.dp).background(QubitBorder))
                Column(Modifier.weight(1f), horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("INDUSTRY PARTNER", color=QubitTextSecondary, fontSize=8.sp,
                        fontWeight=FontWeight.Bold, letterSpacing=1.3.sp)
                    Spacer(Modifier.height(3.dp))
                    Text("AMX UAV", color=QubitText, fontSize=13.sp, fontWeight=FontWeight.Bold)
                    Text("Technology & UAV Partner", color=QubitTextSecondary, fontSize=9.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Core system engineering supported by Qubit Instrument",
            color=QubitTextSecondary.copy(alpha=.55f), fontSize=7.sp, textAlign=TextAlign.Center)
    }
}

@Composable
fun ModernScreenHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom=14.dp), verticalAlignment=Alignment.CenterVertically) {
        OutlinedButton(onClick=onBack, shape=RoundedCornerShape(12.dp)) { Text("‹", fontSize=22.sp) }
        Spacer(Modifier.width(13.dp))
        Column {
            Text(title, color=QubitText, fontSize=23.sp, fontWeight=FontWeight.Bold)
            Text(subtitle, color=QubitTextSecondary, fontSize=10.sp)
        }
    }
}

@Composable
fun FcConnectionScreen(
    wifiManager: QubitWifiManager,
    preferences: QubitPreferences,
    onScan: () -> Unit,
    onConnect: (QubitWifiNetwork, String) -> Unit,
    onManualConnect: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit
) {
    val savedSsid = preferences.loadFcSsid()
    val savedPassword = preferences.loadFcPassword()
    var selected by remember { mutableStateOf<QubitWifiNetwork?>(null) }
    var password by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var manualSsid by remember { mutableStateOf("") }
    var manualPassword by remember { mutableStateOf("") }

    val visible = if (showAll) wifiManager.networks else wifiManager.networks.filter {
        it.isQubit && !it.ssid.contains("CAM", ignoreCase = true)
    }

    QubitBackgroundContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
            ModernScreenHeader("FC Connection", "Scan, pilih dan hubungkan Qubit Flight Controller", onBack)
            LinkDetailCard(
                "FLIGHT CONTROLLER", wifiManager.isFcConnected, wifiManager.fcSsid,
                wifiManager.fcStatus, if (wifiManager.isFcConnected) onDisconnect else null
            )
            Spacer(Modifier.height(16.dp))

            if (savedSsid.isNotBlank()) {
                SectionLabel("SAVED QUBIT")
                SavedDeviceCard("FC", savedSsid, "Saved Flight Controller") {
                    onManualConnect(savedSsid, savedPassword)
                }
                Spacer(Modifier.height(18.dp))
            }

            WifiScanSection(
                title = "NEW QUBIT",
                description = "Available Flight Controllers",
                wifiManager = wifiManager,
                showAll = showAll,
                onToggleAll = { showAll = !showAll },
                onScan = onScan
            )
            Spacer(Modifier.height(8.dp))

            visible.forEach { network ->
                WifiNetworkCard(network, selected == network) {
                    selected = network
                    password = if (network.ssid == savedSsid) savedPassword else ""
                }
                Spacer(Modifier.height(7.dp))
            }

            selected?.let { network ->
                SelectedNetworkCard(
                    network = network,
                    password = password,
                    onPasswordChange = { password = it },
                    buttonText = "CONNECT FLIGHT CONTROLLER",
                    onConnect = { onConnect(network, password) }
                )
                Spacer(Modifier.height(18.dp))
            }

            ManualConnectionSection(
                show = showManual,
                title = "ADVANCED",
                ssid = manualSsid,
                password = manualPassword,
                onToggle = { showManual = !showManual },
                onSsidChange = { manualSsid = it },
                onPasswordChange = { manualPassword = it },
                onConnect = { onManualConnect(manualSsid, manualPassword) }
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun CameraConnectionScreen(
    wifiManager: QubitWifiManager,
    preferences: QubitPreferences,
    cameraStream: CameraStreamManager,
    onBack: () -> Unit
) {
    val savedEndpoint = preferences.loadCameraEndpoint()

    var host by remember {
        mutableStateOf(
            if (savedEndpoint.host == "192.168.4.1")
                ESP_CAM_IP
            else
                savedEndpoint.host
        )
    }

    var port by remember {
        mutableStateOf(
            savedEndpoint.port.toString()
        )
    }

    var path by remember {
        mutableStateOf(
            savedEndpoint.path
        )
    }

    fun currentEndpoint(): CameraEndpoint {
        return CameraEndpoint(
            host = host.trim().ifBlank { ESP_CAM_IP },
            port = port.toIntOrNull() ?: ESP_CAM_PORT,
            path = path.trim().ifBlank { ESP_CAM_PATH }
        )
    }

    QubitBackgroundContainer {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(22.dp)
        ) {
            ModernScreenHeader(
                "FPV Camera",
                "ESP32-CAM memakai jaringan WiFi Flight Controller yang sama",
                onBack
            )

            // ====================================================
            // NETWORK STATUS
            // ====================================================

            SectionLabel("NETWORK")

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = QubitSurface
                )
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(11.dp)
                            .background(
                                if (wifiManager.isFcConnected)
                                    QubitGreen
                                else
                                    QubitRed,
                                CircleShape
                            )
                    )

                    Spacer(
                        Modifier.width(11.dp)
                    )

                    Column(
                        Modifier.weight(1f)
                    ) {
                        Text(
                            "FLIGHT CONTROLLER NETWORK",
                            color = QubitTextSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            if (wifiManager.isFcConnected)
                                wifiManager.fcSsid
                            else
                                "FC OFFLINE",
                            color = if (wifiManager.isFcConnected)
                                QubitText
                            else
                                QubitRed,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            if (wifiManager.isFcConnected)
                                "ESP32-CAM diakses melalui jaringan FC yang sama"
                            else
                                "Hubungkan Flight Controller terlebih dahulu",
                            color = QubitTextSecondary,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            Spacer(
                Modifier.height(16.dp)
            )

            // ====================================================
            // CAMERA STATUS
            // ====================================================

            SectionLabel("CAMERA")

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = QubitSurface
                )
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(11.dp)
                            .background(
                                if (cameraStream.isStreaming)
                                    QubitGreen
                                else
                                    QubitOrange,
                                CircleShape
                            )
                    )

                    Spacer(
                        Modifier.width(11.dp)
                    )

                    Column(
                        Modifier.weight(1f)
                    ) {
                        Text(
                            "ESP32-CAM FPV",
                            color = QubitTextSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            when {
                                cameraStream.isStreaming ->
                                    "CAMERA CONNECTED"
                                cameraStream.status.contains(
                                    "CONNECTING",
                                    ignoreCase = true
                                ) ->
                                    "CONNECTING CAMERA..."
                                else ->
                                    "CAMERA NOT CONNECTED"
                            },
                            color =
                                if (cameraStream.isStreaming)
                                    QubitGreen
                                else
                                    QubitText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            if (cameraStream.isStreaming)
                                "$ESP_CAM_IP:$ESP_CAM_PORT • CONNECTED • READY FOR FLIGHT"
                            else
                                cameraStream.status,
                            color = QubitTextSecondary,
                            fontSize = 9.sp
                        )
                    }

                    if (cameraStream.isStreaming) {
                        OutlinedButton(
                            onClick = {
                                // This is the ONLY normal user action
                                // that disconnects the ESP32-CAM stream.
                                cameraStream.stop()
                            }
                        ) {
                            Text(
                                "DISCONNECT"
                            )
                        }
                    }
                }
            }

            Spacer(
                Modifier.height(16.dp)
            )

            // ====================================================
            // STREAM ENDPOINT
            // ====================================================

            SectionLabel("STREAM ENDPOINT")

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = QubitSurface
                )
            ) {
                Column(
                    Modifier.padding(17.dp)
                ) {
                    Text(
                        "Camera Endpoint",
                        color = QubitText,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Qubit default: $ESP_CAM_IP : $ESP_CAM_PORT $ESP_CAM_PATH",
                        color = QubitTextSecondary,
                        fontSize = 9.sp
                    )

                    Spacer(
                        Modifier.height(10.dp)
                    )

                    Row(
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                8.dp
                            )
                    ) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = {
                                host = it
                            },
                            label = {
                                Text(
                                    "Host / IP"
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.weight(
                                1.4f
                            )
                        )

                        OutlinedTextField(
                            value = port,
                            onValueChange = {
                                port =
                                    it.filter(
                                        Char::isDigit
                                    )
                            },
                            label = {
                                Text(
                                    "Port"
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.weight(
                                .6f
                            )
                        )

                        OutlinedTextField(
                            value = path,
                            onValueChange = {
                                path = it
                            },
                            label = {
                                Text(
                                    "Path"
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.weight(
                                1f
                            )
                        )
                    }

                    Spacer(
                        Modifier.height(12.dp)
                    )

                    Row(
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                10.dp
                            )
                    ) {
                        OutlinedButton(
                            modifier =
                                Modifier.weight(
                                    1f
                                ),
                            onClick = {
                                val endpoint =
                                    currentEndpoint()

                                preferences
                                    .saveCameraEndpoint(
                                        endpoint
                                    )
                            }
                        ) {
                            Text(
                                "SAVE ENDPOINT"
                            )
                        }

                        Button(
                            modifier =
                                Modifier.weight(
                                    1f
                                ),
                            enabled =
                                wifiManager
                                    .isFcConnected,
                            onClick = {
                                val endpoint =
                                    currentEndpoint()

                                preferences
                                    .saveCameraEndpoint(
                                        endpoint
                                    )

                                val fcNetwork =
                                    wifiManager
                                        .getFcNetwork()

                                if (
                                    fcNetwork != null
                                ) {
                                    cameraStream
                                        .start(
                                            fcNetwork,
                                            endpoint.url()
                                        )
                                }
                            }
                        ) {
                            Text(
                                if (
                                    cameraStream
                                        .isStreaming
                                )
                                    "RECONNECT CAMERA"
                                else
                                    "START CAMERA"
                            )
                        }
                    }
                }
            }

            Spacer(
                Modifier.height(
                    28.dp
                )
            )
        }
    }
}

@Composable
fun DualStaCapabilityCard(supported: Boolean, fcConnected: Boolean) {
    Card(
        modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(16.dp),
        colors=CardDefaults.cardColors(
            containerColor=if(supported) QubitGreen.copy(alpha=.07f) else QubitOrange.copy(alpha=.08f)
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(if(supported) QubitGreen else QubitOrange, CircleShape))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(if(supported) "DUAL WIFI SUPPORTED" else "DUAL WIFI NOT REPORTED",
                    color=QubitText, fontSize=10.sp, fontWeight=FontWeight.Bold)
                Text(
                    if(supported) "FC dan ESP32-CAM dapat dipertahankan sebagai koneksi lokal terpisah."
                    else if(fcConnected) "Aplikasi akan menolak koneksi Camera kedua agar link FC tidak terputus."
                    else "Perangkat hanya dapat diuji satu link pada satu waktu.",
                    color=QubitTextSecondary, fontSize=8.sp
                )
            }
        }
    }
}

@Composable
fun LinkDetailCard(
    title: String, connected: Boolean, ssid: String, status: String, onDisconnect: (() -> Unit)?
) {
    Card(
        modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(18.dp),
        colors=CardDefaults.cardColors(containerColor=QubitSurface)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(11.dp).background(if(connected) QubitGreen else QubitRed, CircleShape))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color=QubitTextSecondary, fontSize=9.sp, fontWeight=FontWeight.Bold)
                Text(if(connected) ssid else status, color=QubitText, fontSize=14.sp, fontWeight=FontWeight.Bold)
                Text(status, color=QubitTextSecondary, fontSize=9.sp)
            }
            if(onDisconnect!=null) OutlinedButton(onClick=onDisconnect) { Text("DISCONNECT") }
        }
    }
}

@Composable
fun WifiScanSection(
    title: String, description: String, wifiManager: QubitWifiManager,
    showAll: Boolean, onToggleAll: () -> Unit, onScan: () -> Unit
) {
    SectionLabel(title)
    Card(
        modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(18.dp),
        colors=CardDefaults.cardColors(containerColor=QubitSurface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(description, color=QubitText, fontSize=15.sp, fontWeight=FontWeight.Bold)
                    Text(wifiManager.scanStatus, color=QubitTextSecondary, fontSize=9.sp)
                }
                Button(enabled=!wifiManager.isScanning,onClick=onScan) {
                    Text(if(wifiManager.isScanning) "SCANNING..." else "SCAN WIFI")
                }
            }
            if(!wifiManager.isLocationEnabled()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().background(QubitOrange.copy(alpha=.08f),RoundedCornerShape(10.dp)).padding(9.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Text("Location harus ON untuk WiFi scan",color=QubitOrange,fontSize=9.sp,modifier=Modifier.weight(1f))
                    OutlinedButton(onClick={wifiManager.openLocationSettings()}) { Text("ENABLE") }
                }
            }
            if(wifiManager.networks.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick=onToggleAll) {
                    Text(if(showAll) "SHOW QUBIT ONLY" else "SHOW ALL NETWORKS")
                }
            }
        }
    }
}

@Composable
fun SavedDeviceCard(label:String, ssid:String, description:String, onConnect:()->Unit) {
    Card(
        modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(16.dp),
        colors=CardDefaults.cardColors(containerColor=QubitSurface)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(QubitBlue.copy(alpha=.14f),RoundedCornerShape(12.dp)),
                contentAlignment=Alignment.Center) {
                Text(label,color=QubitBlueLight,fontWeight=FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ssid,color=QubitText,fontWeight=FontWeight.Bold)
                Text(description,color=QubitTextSecondary,fontSize=9.sp)
            }
            Button(onClick=onConnect) { Text("CONNECT") }
        }
    }
}

@Composable
fun WifiNetworkCard(network:QubitWifiNetwork, selected:Boolean, onClick:()->Unit) {
    Card(
        onClick=onClick,
        modifier=Modifier.fillMaxWidth().border(
            if(selected) 1.5.dp else 1.dp, if(selected) QubitBlue else QubitBorder, RoundedCornerShape(16.dp)
        ),
        shape=RoundedCornerShape(16.dp),
        colors=CardDefaults.cardColors(containerColor=if(selected) QubitBlue.copy(alpha=.09f) else QubitSurface)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(QubitBlue.copy(alpha=.13f),RoundedCornerShape(12.dp)),
                contentAlignment=Alignment.Center) {
                Text(signalGlyph(network.rssi),color=QubitBlueLight,fontSize=8.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(network.ssid,color=QubitText,fontWeight=FontWeight.Bold,fontSize=14.sp)
                Text("${network.securityLabel} • ${network.signalLabel}",color=QubitTextSecondary,fontSize=9.sp)
            }
            Text("${network.rssi} dBm",color=when {
                network.rssi>=-55 -> QubitGreen
                network.rssi>=-70 -> QubitOrange
                else -> QubitRed
            },fontSize=10.sp,fontWeight=FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text(if(selected) "✓" else "›",color=QubitBlueLight,fontSize=22.sp)
        }
    }
}

fun signalGlyph(rssi:Int):String = when {
    rssi>=-50 -> "▮▮▮▮"
    rssi>=-60 -> "▮▮▮"
    rssi>=-70 -> "▮▮"
    else -> "▮"
}

@Composable
fun SelectedNetworkCard(
    network:QubitWifiNetwork, password:String, onPasswordChange:(String)->Unit,
    buttonText:String, onConnect:()->Unit
) {
    Card(
        modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(18.dp),
        colors=CardDefaults.cardColors(containerColor=QubitSurface2)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(network.ssid,color=QubitBlueLight,fontSize=17.sp,fontWeight=FontWeight.Bold)
            Text("${network.securityLabel} • ${network.rssi} dBm • ${network.signalLabel}",
                color=QubitTextSecondary,fontSize=9.sp)
            if(!network.isOpen) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value=password,onValueChange=onPasswordChange,label={Text("WiFi Password")},
                    visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(11.dp))
            Button(
                modifier=Modifier.fillMaxWidth(), enabled=network.isOpen || password.length>=8, onClick=onConnect
            ) { Text(buttonText) }
        }
    }
}

@Composable
fun ManualConnectionSection(
    show:Boolean,title:String,ssid:String,password:String,onToggle:()->Unit,
    onSsidChange:(String)->Unit,onPasswordChange:(String)->Unit,onConnect:()->Unit
) {
    SectionLabel(title)
    OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick=onToggle) {
        Text(if(show) "HIDE MANUAL CONNECTION" else "ENTER SSID MANUALLY")
    }
    if(show) {
        Spacer(Modifier.height(8.dp))
        Card(
            modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(16.dp),
            colors=CardDefaults.cardColors(containerColor=QubitSurface)
        ) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(value=ssid,onValueChange=onSsidChange,label={Text("SSID")},singleLine=true,
                    modifier=Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value=password,onValueChange=onPasswordChange,label={Text("Password")},
                    visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Button(modifier=Modifier.fillMaxWidth(),enabled=ssid.isNotBlank(),onClick=onConnect) {
                    Text("CONNECT MANUALLY")
                }
            }
        }
    }
}

@Composable
fun SectionLabel(text:String) {
    Text(text,color=QubitTextSecondary,fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=1.4.sp,
        modifier=Modifier.padding(bottom=6.dp))
}

@Composable
fun FlightScreen(
    engine: QubitControllerEngine,
    wifiManager: QubitWifiManager,
    cameraStream: CameraStreamManager,
    preferences: QubitPreferences,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val mediaManager =
        remember(context) {
            FpvMediaManager(
                context.applicationContext
            )
        }

    var pendingMediaAction by
    remember {
        mutableStateOf<(() -> Unit)?>(null)
    }

    val storagePermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                pendingMediaAction?.invoke()
            }
            pendingMediaAction = null
        }

    fun runWithMediaPermission(
        action: () -> Unit
    ) {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {
            action()
            return
        }

        val permission =
            Manifest.permission.WRITE_EXTERNAL_STORAGE

        if (
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingMediaAction = action
            storagePermissionLauncher.launch(
                permission
            )
        }
    }

    DisposableEffect(mediaManager) {
        onDispose {
            mediaManager.close()
        }
    }

    var leftX by remember { mutableFloatStateOf(0f) }
    var leftY by remember { mutableFloatStateOf(0f) }
    var rightX by remember { mutableFloatStateOf(0f) }
    var rightY by remember { mutableFloatStateOf(0f) }
    var showFpv by remember { mutableStateOf(false) }

    val rc = FlightMapper.sticksToRc(leftX,leftY,rightX,rightY,engine.settings)

    LaunchedEffect(wifiManager.isFcConnected) {
        if(!wifiManager.isFcConnected) {
            if (mediaManager.isRecording) {
                mediaManager.stopVideo()
            }
            cameraStream.stop()
            showFpv=false
        }
    }

    QubitBackgroundContainer(showSilhouette = false) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick=onBack,shape=RoundedCornerShape(10.dp)) { Text("‹ MENU") }
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("FLIGHT CONTROL",color=QubitText,fontSize=19.sp,fontWeight=FontWeight.Bold)
                    Text("QUBIT V2",color=QubitBlueLight,fontSize=8.sp,letterSpacing=2.sp)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    StatusBadge("FC",wifiManager.isFcConnected)
                    StatusBadge("CAM",cameraStream.isStreaming)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(10.dp)
            ) {
                VirtualJoystick(
                    leftStickModeLabel(engine.settings.flightMode)
                ) { x,y ->
                    leftX=x; leftY=y
                    engine.updateSticks(leftX,leftY,rightX,rightY)
                }
                FlightCenterPanel(
                    modifier=Modifier.weight(1f).widthIn(min=280.dp,max=430.dp),
                    engine=engine, rc=rc,
                    fcConnected=wifiManager.isFcConnected,
                    cameraConnected=cameraStream.isStreaming,
                    cameraStream=cameraStream,
                    mediaManager=mediaManager,
                    showFpv=showFpv,
                    onCapturePhoto={
                        runWithMediaPermission {
                            mediaManager.capturePhoto(
                                cameraStream.latestFrameForCapture()
                            )
                        }
                    },
                    onToggleVideo={
                        runWithMediaPermission {
                            mediaManager.toggleVideo {
                                cameraStream.latestFrameForCapture()
                            }
                        }
                    },
                    onToggleFpv={
                        if(showFpv) {
                            // Hiding FPV also finalizes an active recording,
                            // but the ESP32-CAM network stream stays connected.
                            if(mediaManager.isRecording) {
                                mediaManager.stopVideo()
                            }
                            showFpv=false
                        } else {
                            // Camera was already started from FPV Camera menu.
                            // Do NOT restart CameraStreamManager here.
                            if(cameraStream.isStreaming) {
                                showFpv=true
                            }
                        }
                    }
                )
                VirtualJoystick(
                    rightStickModeLabel(engine.settings.flightMode)
                ) { x,y ->
                    rightX=x; rightY=y
                    engine.updateSticks(leftX,leftY,rightX,rightY)
                }
            }
        }
    }
}


fun leftStickModeLabel(
    mode: Int
): String =
    when (mode) {
        1 -> "PITCH / YAW"
        2 -> "THROTTLE / YAW"
        3 -> "PITCH / ROLL"
        4 -> "THROTTLE / ROLL"
        else -> "THROTTLE / YAW"
    }


fun rightStickModeLabel(
    mode: Int
): String =
    when (mode) {
        1 -> "THROTTLE / ROLL"
        2 -> "PITCH / ROLL"
        3 -> "THROTTLE / YAW"
        4 -> "PITCH / YAW"
        else -> "PITCH / ROLL"
    }


@Composable
fun StatusBadge(text:String, connected:Boolean) {
    Row(
        Modifier.background(
            if(connected) QubitGreen.copy(alpha=.12f) else QubitRed.copy(alpha=.12f),
            RoundedCornerShape(18.dp)
        ).padding(horizontal=9.dp,vertical=5.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(if(connected) QubitGreen else QubitRed,CircleShape))
        Spacer(Modifier.width(5.dp))
        Text("$text ${if(connected) "ONLINE" else "OFFLINE"}",
            color=if(connected) QubitGreen else QubitRed,fontSize=8.sp,fontWeight=FontWeight.Bold)
    }
}

@Composable
fun FlightCenterPanel(
    modifier:Modifier,
    engine:QubitControllerEngine,
    rc:RcState,
    fcConnected:Boolean,
    cameraConnected:Boolean,
    cameraStream:CameraStreamManager,
    mediaManager:FpvMediaManager,
    showFpv:Boolean,
    onCapturePhoto:()->Unit,
    onToggleVideo:()->Unit,
    onToggleFpv:()->Unit
) {
    Card(
        modifier=modifier, shape=RoundedCornerShape(18.dp),
        colors=CardDefaults.cardColors(containerColor=QubitSurface)
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            if(showFpv && cameraConnected) {
                CameraStreamView(
                    manager = cameraStream,
                    mediaManager = mediaManager,
                    onCapturePhoto = onCapturePhoto,
                    onToggleVideo = onToggleVideo
                )
            } else {
                ChannelMonitor(engine,rc)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                Button(
                    enabled=fcConnected && !engine.isArmed,
                    colors=ButtonDefaults.buttonColors(containerColor=QubitBlue),
                    onClick={engine.arm()}
                ) { Text("ARM") }
                Button(
                    colors=ButtonDefaults.buttonColors(containerColor=QubitRed),
                    onClick={engine.disarm()}
                ) { Text("DISARM") }
                Button(
                    enabled=cameraConnected,
                    colors=ButtonDefaults.buttonColors(containerColor=if(showFpv) QubitOrange else QubitBlueDark),
                    onClick=onToggleFpv
                ) { Text(if(showFpv) "STOP FPV" else "FPV") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    mediaManager.isRecording ->
                        "REC ${mediaManager.formattedDuration()} • FPV LIVE • ${"%.1f".format(cameraStream.fps)} FPS"
                    mediaManager.isBusy ->
                        mediaManager.status
                    showFpv && cameraStream.isStreaming ->
                        "FPV LIVE • ${"%.1f".format(cameraStream.fps)} FPS"
                    showFpv ->
                        "FPV NOT CONNECTED"
                    else ->
                        engine.status
                },
                color = if(mediaManager.isRecording) QubitRed else QubitTextSecondary,
                fontSize=8.sp,
                textAlign=TextAlign.Center
            )
        }
    }
}

@Composable
fun ChannelMonitor(engine:QubitControllerEngine, rc:RcState) {
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        Text(if(engine.isArmed) "ARMED" else "DISARMED",
            color=if(engine.isArmed) QubitRed else QubitGreen,fontSize=12.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        ChannelValue("ROLL",rc.ch1)
        ChannelValue("PITCH",rc.ch2)
        ChannelValue("THROTTLE",rc.ch3)
        ChannelValue("YAW",rc.ch4)
    }
}

@Composable
fun CameraStreamView(
    manager: CameraStreamManager,
    mediaManager: FpvMediaManager,
    onCapturePhoto: () -> Unit,
    onToggleVideo: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(
                    Color.Black,
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = manager.frame

            if(bitmap != null) {
                Image(
                    bitmap.asImageBitmap(),
                    "Qubit FPV",
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "FPV",
                        color = QubitBlueLight,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        manager.status,
                        color = QubitTextSecondary,
                        fontSize = 8.sp
                    )
                }
            }

            // LIVE / FPS badge
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .background(
                        Color.Black.copy(alpha = .55f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(
                        horizontal = 7.dp,
                        vertical = 3.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(
                            if(manager.isStreaming) QubitRed
                            else QubitTextSecondary,
                            CircleShape
                        )
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if(manager.isStreaming)
                        "LIVE ${"%.1f".format(manager.fps)} FPS"
                    else
                        "OFF",
                    color = Color.White,
                    fontSize = 7.sp
                )
            }

            // Recording indicator and timer
            if(mediaManager.isRecording) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .background(
                            Color.Black.copy(alpha = .62f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(
                            horizontal = 7.dp,
                            vertical = 3.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(
                                QubitRed,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "REC ${mediaManager.formattedDuration()}",
                        color = Color.White,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // PHOTO + VIDEO controls only appear when a real FPV frame exists.
            if(
                manager.isStreaming &&
                bitmap != null
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(
                            Color.Black.copy(alpha = .58f),
                            RoundedCornerShape(14.dp)
                        )
                        .padding(
                            horizontal = 6.dp,
                            vertical = 5.dp
                        ),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FpvCaptureIconButton(
                        type = FpvCaptureIcon.PHOTO,
                        active = false,
                        enabled = true,
                        onClick = onCapturePhoto
                    )

                    FpvCaptureIconButton(
                        type = FpvCaptureIcon.VIDEO,
                        active = mediaManager.isRecording,
                        enabled = mediaManager.isRecording || !mediaManager.isBusy,
                        onClick = onToggleVideo
                    )
                }
            }
        }
    }
}


enum class FpvCaptureIcon {
    PHOTO,
    VIDEO
}


@Composable
fun FpvCaptureIconButton(
    type: FpvCaptureIcon,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor =
        when {
            !enabled ->
                Color.Black.copy(alpha = 0.32f)
            active ->
                QubitRed
            else ->
                Color.Black.copy(alpha = 0.72f)
        }

    val iconColor =
        if(enabled) Color.White
        else Color.White.copy(alpha = 0.35f)

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(38.dp),
        shape = CircleShape,
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if(active) QubitRed.copy(alpha = 0.95f)
            else Color.White.copy(alpha = 0.22f)
        )
    ) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                Modifier.size(22.dp)
            ) {
                when(type) {
                    FpvCaptureIcon.PHOTO -> {
                        val stroke =
                            Stroke(
                                width = 2.2f,
                                cap = StrokeCap.Round
                            )

                        drawRoundRect(
                            color = iconColor,
                            topLeft = Offset(
                                size.width * 0.12f,
                                size.height * 0.27f
                            ),
                            size = Size(
                                size.width * 0.76f,
                                size.height * 0.56f
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                3.5f,
                                3.5f
                            ),
                            style = stroke
                        )

                        drawCircle(
                            color = iconColor,
                            radius = size.minDimension * 0.14f,
                            center = center,
                            style = stroke
                        )

                        drawLine(
                            color = iconColor,
                            start = Offset(
                                size.width * 0.30f,
                                size.height * 0.27f
                            ),
                            end = Offset(
                                size.width * 0.38f,
                                size.height * 0.17f
                            ),
                            strokeWidth = 2.2f,
                            cap = StrokeCap.Round
                        )

                        drawLine(
                            color = iconColor,
                            start = Offset(
                                size.width * 0.38f,
                                size.height * 0.17f
                            ),
                            end = Offset(
                                size.width * 0.57f,
                                size.height * 0.17f
                            ),
                            strokeWidth = 2.2f,
                            cap = StrokeCap.Round
                        )
                    }

                    FpvCaptureIcon.VIDEO -> {
                        if(active) {
                            drawRoundRect(
                                color = Color.White,
                                topLeft = Offset(
                                    size.width * 0.31f,
                                    size.height * 0.31f
                                ),
                                size = Size(
                                    size.width * 0.38f,
                                    size.height * 0.38f
                                ),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                    2.5f,
                                    2.5f
                                )
                            )
                        } else {
                            drawCircle(
                                color = QubitRed,
                                radius = size.minDimension * 0.25f,
                                center = center
                            )

                            drawCircle(
                                color = iconColor,
                                radius = size.minDimension * 0.34f,
                                center = center,
                                style = Stroke(
                                    width = 2f
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChannelValue(name:String,value:Int) {
    Row(Modifier.fillMaxWidth().padding(vertical=1.dp),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(name,color=QubitTextSecondary,fontSize=10.sp)
        Text(value.toString(),color=QubitBlueLight,fontSize=10.sp,fontWeight=FontWeight.Bold)
    }
}

@Composable
fun VirtualJoystick(
    title: String,
    onChange: (Float, Float) -> Unit
) {

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = title,
            color = QubitText.copy(alpha = 0.82f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )


        Spacer(
            Modifier.height(5.dp)
        )


        var knob by remember {
            mutableStateOf(Offset.Zero)
        }


        Canvas(
            modifier = Modifier
                .size(205.dp)
                .pointerInput(Unit) {

                    fun updateJoystick(
                        position: Offset
                    ) {

                        val center =
                            Offset(
                                size.width / 2f,
                                size.height / 2f
                            )

                        var dx =
                            position.x - center.x

                        var dy =
                            position.y - center.y

                        val radius =
                            min(
                                size.width,
                                size.height
                            ) * 0.38f

                        val distance =
                            sqrt(
                                dx * dx +
                                        dy * dy
                            )

                        if (
                            distance > radius
                        ) {

                            dx =
                                dx / distance *
                                        radius

                            dy =
                                dy / distance *
                                        radius
                        }

                        knob =
                            Offset(
                                dx,
                                dy
                            )

                        onChange(
                            dx / radius,
                            dy / radius
                        )
                    }


                    detectDragGestures(

                        onDragStart = {
                            updateJoystick(it)
                        },

                        onDrag = {
                                change,
                                _ ->

                            change.consume()

                            updateJoystick(
                                change.position
                            )
                        },

                        onDragEnd = {

                            knob =
                                Offset.Zero

                            onChange(
                                0f,
                                0f
                            )
                        },

                        onDragCancel = {

                            knob =
                                Offset.Zero

                            onChange(
                                0f,
                                0f
                            )
                        }
                    )
                }
        ) {


            val radius =
                min(
                    size.width,
                    size.height
                ) / 2f


            val center =
                Offset(
                    size.width / 2f,
                    size.height / 2f
                )


            // ====================================================
            // OUTER GLOW
            // ====================================================

            drawCircle(
                color =
                    QubitBlue.copy(
                        alpha = 0.11f
                    ),
                radius =
                    radius * 0.98f,
                center = center
            )


            // ====================================================
            // MAIN JOYSTICK BODY
            // Lebih terang daripada background sehingga joystick
            // langsung terbaca sebagai kontrol utama.
            // ====================================================

            drawCircle(
                color =
                    Color(0xFF123152),
                radius =
                    radius * 0.91f,
                center = center
            )


            // Outer ring kuat
            drawCircle(
                color =
                    QubitBlueLight.copy(
                        alpha = 0.78f
                    ),
                radius =
                    radius * 0.91f,
                center = center,
                style =
                    Stroke(
                        width = 4.2f
                    )
            )


            // Inner active area
            drawCircle(
                color =
                    QubitBlue.copy(
                        alpha = 0.17f
                    ),
                radius =
                    radius * 0.66f,
                center = center
            )


            drawCircle(
                color =
                    QubitBlueLight.copy(
                        alpha = 0.16f
                    ),
                radius =
                    radius * 0.66f,
                center = center,
                style =
                    Stroke(
                        width = 1.8f
                    )
            )


            // ====================================================
            // CROSSHAIR
            // ====================================================

            val crossColor =
                QubitBlueLight.copy(
                    alpha = 0.28f
                )


            drawLine(
                color = crossColor,
                start =
                    Offset(
                        center.x -
                                radius * 0.68f,
                        center.y
                    ),
                end =
                    Offset(
                        center.x +
                                radius * 0.68f,
                        center.y
                    ),
                strokeWidth = 2.2f
            )


            drawLine(
                color = crossColor,
                start =
                    Offset(
                        center.x,
                        center.y -
                                radius * 0.68f
                    ),
                end =
                    Offset(
                        center.x,
                        center.y +
                                radius * 0.68f
                    ),
                strokeWidth = 2.2f
            )


            // Center marker
            drawCircle(
                color =
                    QubitBlueLight.copy(
                        alpha = 0.22f
                    ),
                radius =
                    radius * 0.055f,
                center = center
            )


            // ====================================================
            // MOVABLE KNOB
            // ====================================================

            val knobCenter =
                Offset(
                    center.x + knob.x,
                    center.y + knob.y
                )


            // Knob halo
            drawCircle(
                color =
                    QubitBlue.copy(
                        alpha = 0.22f
                    ),
                radius =
                    radius * 0.31f,
                center =
                    knobCenter
            )


            // Knob
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF7DD3FF),
                            QubitBlue,
                            QubitBlueDark
                        ),
                        center = knobCenter,
                        radius =
                            radius * 0.27f
                    ),
                radius =
                    radius * 0.25f,
                center =
                    knobCenter
            )


            // Knob border
            drawCircle(
                color =
                    Color.White.copy(
                        alpha = 0.28f
                    ),
                radius =
                    radius * 0.25f,
                center =
                    knobCenter,
                style =
                    Stroke(
                        width = 2.2f
                    )
            )


            // Highlight
            drawCircle(
                color =
                    Color.White.copy(
                        alpha = 0.32f
                    ),
                radius =
                    radius * 0.10f,
                center =
                    Offset(
                        knobCenter.x -
                                radius * 0.055f,
                        knobCenter.y -
                                radius * 0.055f
                    )
            )
        }
    }
}

@Composable
fun FlightSettingsScreen(
    engine:QubitControllerEngine,
    preferences:QubitPreferences,
    onBack:()->Unit
) {
    val settings=engine.settings
    fun apply(newSettings:FlightSettings) {
        engine.updateSettings(newSettings)
        preferences.saveFlightSettings(newSettings)
    }
    QubitBackgroundContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
            ModernScreenHeader("Flight Settings","Parameter kendali penerbangan Qubit",onBack)
            ModernSettingCard(
                title = "RC Stick Mode",
                subtitle = "Pilih tata letak fungsi joystick sesuai kebiasaan pilot",
                content = {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {

                        RcModeOption(
                            mode = 1,
                            selected = settings.flightMode == 1,
                            leftText = "Pitch / Yaw",
                            rightText = "Thrust / Roll",
                            modifier = Modifier.weight(1f)
                        ) {
                            apply(
                                settings.copy(
                                    flightMode = 1
                                )
                            )
                        }

                        RcModeOption(
                            mode = 2,
                            selected = settings.flightMode == 2,
                            leftText = "Thrust / Yaw",
                            rightText = "Pitch / Roll",
                            modifier = Modifier.weight(1f)
                        ) {
                            apply(
                                settings.copy(
                                    flightMode = 2
                                )
                            )
                        }
                    }

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {

                        RcModeOption(
                            mode = 3,
                            selected = settings.flightMode == 3,
                            leftText = "Pitch / Roll",
                            rightText = "Thrust / Yaw",
                            modifier = Modifier.weight(1f)
                        ) {
                            apply(
                                settings.copy(
                                    flightMode = 3
                                )
                            )
                        }

                        RcModeOption(
                            mode = 4,
                            selected = settings.flightMode == 4,
                            leftText = "Thrust / Roll",
                            rightText = "Pitch / Yaw",
                            modifier = Modifier.weight(1f)
                        ) {
                            apply(
                                settings.copy(
                                    flightMode = 4
                                )
                            )
                        }
                    }

                    Spacer(
                        Modifier.height(9.dp)
                    )

                    Text(
                        text = when (settings.flightMode) {
                            1 -> "Mode 1 • Left: Pitch & Yaw  |  Right: Thrust & Roll"
                            2 -> "Mode 2 • Left: Thrust & Yaw  |  Right: Pitch & Roll"
                            3 -> "Mode 3 • Left: Pitch & Roll  |  Right: Thrust & Yaw"
                            4 -> "Mode 4 • Left: Thrust & Roll  |  Right: Pitch & Yaw"
                            else -> "Mode 2 • Left: Thrust & Yaw  |  Right: Pitch & Roll"
                        },
                        color = QubitBlueLight,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            )

            Spacer(Modifier.height(9.dp))

            ModernSettingCard(
                title = "Response",
                subtitle = "Deadzone dan trim controller",
                content = {
                    SettingFloatSlider(
                        title = "Deadzone",
                        value = settings.deadzone,
                        range = 0f..1f,
                        decimals = 2,
                        onChange = { value ->
                            apply(settings.copy(deadzone = (value * 20f).roundToInt() / 20f))
                        }
                    )

                    SettingFloatSlider(
                        title = "Roll Trim",
                        value = settings.rollTrim,
                        range = -0.5f..0.5f,
                        decimals = 2,
                        onChange = { value ->
                            apply(settings.copy(rollTrim = (value * 100f).roundToInt() / 100f))
                        }
                    )

                    SettingFloatSlider(
                        title = "Pitch Trim",
                        value = settings.pitchTrim,
                        range = -0.5f..0.5f,
                        decimals = 2,
                        onChange = { value ->
                            apply(settings.copy(pitchTrim = (value * 100f).roundToInt() / 100f))
                        }
                    )
                }
            )

            Spacer(Modifier.height(9.dp))

            ModernSettingCard(
                title = "Flight Limits",
                subtitle = "Batas sudut dan thrust",
                content = {
                    SettingIntSlider(
                        title = "Max Roll / Pitch",
                        value = settings.maxRollPitch,
                        range = 0f..80f,
                        suffix = "°",
                        onChange = { value ->
                            apply(settings.copy(maxRollPitch = value))
                        }
                    )

                    SettingIntSlider(
                        title = "Max Yaw",
                        value = settings.maxYaw,
                        range = 0f..300f,
                        suffix = "°",
                        onChange = { value ->
                            apply(settings.copy(maxYaw = value))
                        }
                    )

                    SettingIntSlider(
                        title = "Max Thrust",
                        value = settings.maxThrust,
                        range = 0f..100f,
                        suffix = "%",
                        onChange = { value ->
                            apply(settings.copy(maxThrust = value))
                        }
                    )

                    SettingIntSlider(
                        title = "Min Thrust",
                        value = settings.minThrust,
                        range = 0f..50f,
                        suffix = "%",
                        onChange = { value ->
                            apply(settings.copy(minThrust = value))
                        }
                    )

                    SettingSwitch(
                        title = "X Mode",
                        checked = settings.xMode == 1,
                        onChange = { enabled ->
                            apply(settings.copy(xMode = if (enabled) 1 else 0))
                        }
                    )
                }
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={
                engine.updateSettings(preferences.resetFlightSettings())
            }) { Text("RESET DEFAULT SETTINGS",color=QubitOrange) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun RcModeOption(
    mode: Int,
    selected: Boolean,
    leftText: String,
    rightText: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {

    Card(
        onClick = onClick,
        modifier = modifier
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) QubitBlue else QubitBorder,
                shape = RoundedCornerShape(13.dp)
            ),
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(
            containerColor =
                if (selected)
                    QubitBlue.copy(alpha = 0.12f)
                else
                    QubitSurface2
        )
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 11.dp,
                    vertical = 9.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        if (selected)
                            QubitBlue
                        else
                            QubitBlue.copy(alpha = 0.12f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {

                Text(
                    text = "$mode",
                    color =
                        if (selected)
                            Color.White
                        else
                            QubitBlueLight,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(
                Modifier.width(9.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    text = "MODE $mode",
                    color = QubitText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "L  $leftText",
                    color = QubitTextSecondary,
                    fontSize = 8.sp
                )

                Text(
                    text = "R  $rightText",
                    color = QubitTextSecondary,
                    fontSize = 8.sp
                )
            }

            if (selected) {

                Text(
                    text = "✓",
                    color = QubitGreen,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


@Composable
fun ModernSettingCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),
        colors=CardDefaults.cardColors(containerColor=QubitSurface)
    ) {
        Column(Modifier.padding(17.dp)) {
            Text(title,color=QubitText,fontSize=16.sp,fontWeight=FontWeight.Bold)
            Text(subtitle,color=QubitTextSecondary,fontSize=9.sp)
            Spacer(Modifier.height(10.dp));content()
        }
    }
}

@Composable
fun ChannelReverseScreen(engine:QubitControllerEngine,preferences:QubitPreferences,onBack:()->Unit) {
    val settings=engine.settings
    fun apply(newSettings:FlightSettings) {
        engine.updateSettings(newSettings);preferences.saveFlightSettings(newSettings)
    }
    QubitBackgroundContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
            ModernScreenHeader("Channel Reverse","Konfigurasi arah channel virtual RC",onBack)
            ReverseRow("CH1","ROLL",settings.revCH1) { apply(settings.copy(revCH1=it)) }
            ReverseRow("CH2","PITCH",settings.revCH2) { apply(settings.copy(revCH2=it)) }
            ReverseRow("CH3","THROTTLE",settings.revCH3) { apply(settings.copy(revCH3=it)) }
            ReverseRow("CH4","YAW",settings.revCH4) { apply(settings.copy(revCH4=it)) }
            Spacer(Modifier.height(12.dp))
            Text("Default Qubit V2: CH2 dan CH3 reversed.",color=QubitTextSecondary,fontSize=10.sp)
        }
    }
}

@Composable
fun ReverseRow(title:String,subtitle:String,checked:Boolean,onCheckedChange:(Boolean)->Unit) {
    Card(
        modifier=Modifier.fillMaxWidth().padding(vertical=4.dp),shape=RoundedCornerShape(16.dp),
        colors=CardDefaults.cardColors(containerColor=QubitSurface)
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(QubitBlue.copy(alpha=.15f),RoundedCornerShape(11.dp)),
                contentAlignment=Alignment.Center) {
                Text(title,color=QubitBlueLight,fontWeight=FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(subtitle,color=QubitText,fontWeight=FontWeight.Bold)
                Text(if(checked) "REVERSED" else "NORMAL",
                    color=if(checked) QubitOrange else QubitGreen,fontSize=9.sp)
            }
            Switch(checked=checked,onCheckedChange=onCheckedChange,
                colors=SwitchDefaults.colors(checkedTrackColor=QubitBlue))
        }
    }
}

@Composable
fun SettingFloatSlider(
    title:String,value:Float,range:ClosedFloatingPointRange<Float>,decimals:Int,onChange:(Float)->Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical=3.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(title,color=QubitTextSecondary)
            Text("%.${decimals}f".format(value),color=QubitBlueLight,fontWeight=FontWeight.Bold)
        }
        Slider(value=value,onValueChange=onChange,valueRange=range)
    }
}

@Composable
fun SettingIntSlider(
    title:String,value:Int,range:ClosedFloatingPointRange<Float>,suffix:String,onChange:(Int)->Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical=3.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(title,color=QubitTextSecondary)
            Text("$value$suffix",color=QubitBlueLight,fontWeight=FontWeight.Bold)
        }
        Slider(value=value.toFloat(),onValueChange={onChange(it.roundToInt())},valueRange=range)
    }
}

@Composable
fun SettingSwitch(title:String,checked:Boolean,onChange:(Boolean)->Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical=6.dp),
        horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically
    ) {
        Text(title,color=QubitTextSecondary)
        Switch(checked=checked,onCheckedChange=onChange,
            colors=SwitchDefaults.colors(checkedTrackColor=QubitBlue))
    }
}
