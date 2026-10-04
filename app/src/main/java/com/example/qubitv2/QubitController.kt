package com.example.qubitv2

import android.net.Network
import android.os.Handler
import android.os.Looper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import kotlin.math.abs
import kotlin.math.roundToInt


// ============================================================
// FLIGHT SETTINGS
// Default dibuat sama dengan qubit_joystick.ino
// ============================================================

data class FlightSettings(
    val flightMode: Int = 2,
    val deadzone: Float = 0.20f,

    val rollTrim: Float = 0.0f,
    val pitchTrim: Float = 0.0f,

    val maxRollPitch: Int = 10,
    val maxYaw: Int = 45,

    val maxThrust: Int = 55,
    val minThrust: Int = 8,

    val xMode: Int = 1,

    val revCH1: Boolean = false,
    val revCH2: Boolean = true,
    val revCH3: Boolean = true,
    val revCH4: Boolean = false
)


data class RcState(
    val ch1: Int = 1500,   // Roll
    val ch2: Int = 1500,   // Pitch
    val ch3: Int = 1500,   // Throttle
    val ch4: Int = 1500    // Yaw
)


data class FlightCommand(
    val roll: Float = 0f,
    val pitch: Float = 0f,
    val yaw: Float = 0f,
    val thrust: Int = 0
)


// ============================================================
// VIRTUAL STICK -> RC CHANNEL
// ============================================================

object FlightMapper {

    private const val ADC_MIN = 0
    private const val ADC_MAX = 4095
    private const val ADC_CENTER = 2048

    const val RC_MIN = 1000
    const val RC_MID = 1500
    const val RC_MAX = 2000

    private const val THR_CENTER_BAND = 40
    private const val THR_START_BAND = 80


    private fun arduinoMap(
        x: Long,
        inMin: Long,
        inMax: Long,
        outMin: Long,
        outMax: Long
    ): Long {

        return (x - inMin) *
                (outMax - outMin) /
                (inMax - inMin) +
                outMin
    }


    /*
     * Axis Compose:
     * -1 = kiri / atas
     *  0 = center
     * +1 = kanan / bawah
     *
     * Dibuat menyerupai ADC ESP32 0..4095 agar reverse,
     * deadzone, dan nilai RC sama dengan joystick Arduino.
     */
    private fun axisToRc(
        axis: Float,
        reversed: Boolean,
        deadzone: Float
    ): Int {

        val a =
            axis.coerceIn(
                -1f,
                1f
            )


        var raw =
            (
                    ((a + 1f) / 2f) *
                            ADC_MAX
                    ).roundToInt()


        val deadzoneAdc =
            (
                    deadzone *
                            500f
                    ).roundToInt()


        if (
            abs(
                raw -
                        ADC_CENTER
            ) <
            deadzoneAdc
        ) {

            raw =
                ADC_CENTER
        }


        var out =
            if (
                raw >=
                ADC_CENTER
            ) {

                arduinoMap(
                    raw.toLong(),
                    ADC_CENTER.toLong(),
                    ADC_MAX.toLong(),
                    RC_MID.toLong(),
                    RC_MAX.toLong()
                ).toInt()

            } else {

                arduinoMap(
                    raw.toLong(),
                    ADC_MIN.toLong(),
                    ADC_CENTER.toLong(),
                    RC_MIN.toLong(),
                    RC_MID.toLong()
                ).toInt()
            }


        out =
            out.coerceIn(
                RC_MIN,
                RC_MAX
            )


        if (reversed) {

            out =
                RC_MAX -
                        (
                                out -
                                        RC_MIN
                                )
        }


        return out
    }


    /*
     * RC stick modes:
     *
     * MODE 1
     * Left  : Y=Pitch,    X=Yaw
     * Right : Y=Throttle, X=Roll
     *
     * MODE 2
     * Left  : Y=Throttle, X=Yaw
     * Right : Y=Pitch,    X=Roll
     *
     * MODE 3
     * Left  : Y=Pitch,    X=Roll
     * Right : Y=Throttle, X=Yaw
     *
     * MODE 4
     * Left  : Y=Throttle, X=Roll
     * Right : Y=Pitch,    X=Yaw
     *
     * Setelah mapping, channel tetap:
     * CH1 Roll, CH2 Pitch, CH3 Throttle, CH4 Yaw.
     */
    fun sticksToRc(
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float,
        settings: FlightSettings
    ): RcState {

        val rollAxis: Float
        val pitchAxis: Float
        val throttleAxis: Float
        val yawAxis: Float


        when (
            settings.flightMode
        ) {

            1 -> {

                rollAxis =
                    rightX

                pitchAxis =
                    leftY

                throttleAxis =
                    rightY

                yawAxis =
                    leftX
            }


            2 -> {

                rollAxis =
                    rightX

                pitchAxis =
                    rightY

                throttleAxis =
                    leftY

                yawAxis =
                    leftX
            }


            3 -> {

                rollAxis =
                    leftX

                pitchAxis =
                    leftY

                throttleAxis =
                    rightY

                yawAxis =
                    rightX
            }


            4 -> {

                rollAxis =
                    leftX

                pitchAxis =
                    rightY

                throttleAxis =
                    leftY

                yawAxis =
                    rightX
            }


            else -> {

                // fallback Mode 2
                rollAxis =
                    rightX

                pitchAxis =
                    rightY

                throttleAxis =
                    leftY

                yawAxis =
                    leftX
            }
        }


        return RcState(

            ch1 =
                axisToRc(
                    rollAxis,
                    settings.revCH1,
                    settings.deadzone
                ),

            ch2 =
                axisToRc(
                    pitchAxis,
                    settings.revCH2,
                    settings.deadzone
                ),

            ch3 =
                axisToRc(
                    throttleAxis,
                    settings.revCH3,
                    settings.deadzone
                ),

            ch4 =
                axisToRc(
                    yawAxis,
                    settings.revCH4,
                    settings.deadzone
                )
        )
    }


    private fun rcToAngle(
        rc: Int,
        maxAngle: Int
    ): Float {

        return arduinoMap(
            rc.toLong(),
            RC_MIN.toLong(),
            RC_MAX.toLong(),
            (-maxAngle * 100L),
            (maxAngle * 100L)
        ) / 100f
    }


    private fun rcToYaw(
        rc: Int,
        maxYaw: Int
    ): Float {

        return arduinoMap(
            rc.toLong(),
            RC_MIN.toLong(),
            RC_MAX.toLong(),
            (-maxYaw * 100L),
            (maxYaw * 100L)
        ) / 100f
    }


    private fun rcToThrust(
        rc: Int,
        settings: FlightSettings
    ): Int {

        val startRc =
            RC_MID +
                    THR_START_BAND


        // sama dengan Arduino:
        // if (rc <= 1580) thrust = 0
        if (
            rc <=
            startRc
        ) {

            return 0
        }


        val thrustMin =
            (
                    (
                            settings.minThrust /
                                    100f
                            ) *
                            60000f
                    ).toLong()


        val thrustMax =
            (
                    (
                            settings.maxThrust /
                                    100f
                            ) *
                            60000f
                    ).toLong()


        return arduinoMap(
            rc.toLong(),
            startRc.toLong(),
            RC_MAX.toLong(),
            thrustMin,
            thrustMax
        )
            .coerceIn(
                0,
                60000
            )
            .toInt()
    }


    fun throttleSafeToArm(
        rc: Int
    ): Boolean {

        return abs(
            rc -
                    RC_MID
        ) <=
                THR_CENTER_BAND
    }


    /*
     * Persis loop Arduino:
     *
     * rollCmd  = mapRcToAngle(CH1)
     * pitchCmd = mapRcToAngle(CH2)
     * yawCmd   = mapRcToYaw(CH4)
     * thrust   = mapRcToThrustCentered(CH3)
     *
     * lalu X-mode.
     */
    fun rcToCommand(
        rc: RcState,
        settings: FlightSettings,
        armed: Boolean
    ): FlightCommand {

        if (!armed) {

            return FlightCommand()
        }


        var roll =
            rcToAngle(
                rc.ch1,
                settings.maxRollPitch
            ) +
                    settings.rollTrim


        var pitch =
            rcToAngle(
                rc.ch2,
                settings.maxRollPitch
            ) +
                    settings.pitchTrim


        val yaw =
            rcToYaw(
                rc.ch4,
                settings.maxYaw
            )


        val thrust =
            rcToThrust(
                rc.ch3,
                settings
            )


        if (
            settings.xMode ==
            1
        ) {

            val r =
                roll

            val p =
                pitch


            roll =
                0.7071f *
                        (
                                r -
                                        p
                                )


            pitch =
                0.7071f *
                        (
                                r +
                                        p
                                )
        }


        return FlightCommand(
            roll =
                roll,

            pitch =
                pitch,

            yaw =
                yaw,

            thrust =
                thrust
        )
    }
}


// ============================================================
// ESP-DRONE / QUBIT UDP PACKET
// Berdasarkan qubit_joystick.ino
// ============================================================

object FlightProtocol {

    /*
     * COMMANDER
     *
     * [0]     = 0x30
     * [1..4]  = float roll LE
     * [5..8]  = float -pitch LE
     * [9..12] = float yaw LE
     * [13..14]= uint16 thrust LE
     * [15]    = checksum sum byte 0..14 modulo 256
     */
    fun commanderPacket(
        command: FlightCommand
    ): ByteArray {

        val payload =
            ByteBuffer
                .allocate(
                    15
                )
                .order(
                    ByteOrder
                        .LITTLE_ENDIAN
                )


        payload.put(
            0x30
                .toByte()
        )


        payload.putFloat(
            command.roll
        )


        // Arduino:
        // float txPitch = -pitch;
        payload.putFloat(
            -command.pitch
        )


        payload.putFloat(
            command.yaw
        )


        payload.putShort(
            command.thrust
                .coerceIn(
                    0,
                    60000
                )
                .toShort()
        )


        val body =
            payload.array()


        var checksum =
            0


        for (
        b in body
        ) {

            checksum =
                (
                        checksum +
                                (
                                        b.toInt() and
                                                0xFF
                                        )
                        ) and
                        0xFF
        }


        return body +
                byteArrayOf(
                    checksum
                        .toByte()
                )
    }


    /*
     * ARM:
     * D0 01 01 D2
     *
     * DISARM:
     * D0 01 00 D1
     */
    fun armPacket(
        arm: Boolean
    ): ByteArray {

        val data =
            ByteArray(
                4
            )


        data[0] =
            0xD0
                .toByte()


        data[1] =
            0x01


        data[2] =
            if (arm)
                1
            else
                0


        var checksum =
            0


        for (
        i in 0..2
        ) {

            checksum =
                (
                        checksum +
                                (
                                        data[i]
                                            .toInt() and
                                                0xFF
                                        )
                        ) and
                        0xFF
        }


        data[3] =
            checksum
                .toByte()


        return data
    }
}


// ============================================================
// CONTROLLER ENGINE
// ============================================================

class QubitControllerEngine(
    initialSettings: FlightSettings =
        FlightSettings()
) {

    companion object {

        const val FALLBACK_FC_IP =
            "192.168.43.42"

        const val FC_PORT =
            2390

        const val LOCAL_PORT =
            1234
    }


    private val executor =
        Executors
            .newSingleThreadScheduledExecutor()


    private val mainHandler =
        Handler(
            Looper
                .getMainLooper()
        )


    private var socket:
            DatagramSocket? =
        null


    private var targetAddress:
            InetAddress? =
        null


    @Volatile
    private var leftX =
        0f


    @Volatile
    private var leftY =
        0f


    @Volatile
    private var rightX =
        0f


    @Volatile
    private var rightY =
        0f


    @Volatile
    private var armedFlag =
        false


    @Volatile
    private var networkAttached =
        false


    @Volatile
    private var started =
        false


    var settings by
    mutableStateOf(
        initialSettings
    )
        private set


    var status by
    mutableStateOf(
        "UDP belum aktif"
    )
        private set


    var isArmed by
    mutableStateOf(
        false
    )
        private set


    var targetIp by
    mutableStateOf(
        FALLBACK_FC_IP
    )
        private set


    var txCount by
    mutableStateOf(
        0L
    )
        private set


    // ========================================================
    // START
    // ========================================================

    fun start() {

        if (started) {

            return
        }


        started =
            true


        /*
         * Scheduler 50 Hz sama dengan Arduino:
         * millis() - lastUdpMs >= 20
         */
        executor
            .scheduleAtFixedRate(
                {

                    try {

                        sendCurrentFlightPacket()

                    } catch (
                        e: Exception
                    ) {

                        updateStatus(
                            "UDP TX ERROR: ${e.message}"
                        )
                    }
                },
                20,
                20,
                TimeUnit.MILLISECONDS
            )


        updateStatus(
            "UDP READY • menunggu FC WiFi"
        )
    }


    // ========================================================
    // SETTINGS / STICK
    // ========================================================

    fun updateSettings(
        newSettings: FlightSettings
    ) {

        mainHandler.post {

            settings =
                newSettings
        }
    }


    fun updateSticks(
        lx: Float,
        ly: Float,
        rx: Float,
        ry: Float
    ) {

        leftX =
            lx

        leftY =
            ly

        rightX =
            rx

        rightY =
            ry
    }


    fun currentRc(): RcState {

        return FlightMapper
            .sticksToRc(
                leftX,
                leftY,
                rightX,
                rightY,
                settings
            )
    }


    // ========================================================
    // UDP TX 50 Hz
    // ========================================================

    private fun sendCurrentFlightPacket() {

        if (!networkAttached) {

            return
        }


        val rc =
            currentRc()


        val command =
            FlightMapper
                .rcToCommand(
                    rc,
                    settings,
                    armedFlag
                )


        send(
            FlightProtocol
                .commanderPacket(
                    command
                )
        )
    }


    private fun sendZero() {

        send(
            FlightProtocol
                .commanderPacket(
                    FlightCommand()
                )
        )
    }


    private fun send(
        bytes: ByteArray
    ) {

        if (!networkAttached) {

            return
        }


        val s =
            socket
                ?: return


        val address =
            targetAddress
                ?: return


        val packet =
            DatagramPacket(
                bytes,
                bytes.size,
                address,
                FC_PORT
            )


        s.send(
            packet
        )


        val next =
            txCount +
                    1


        if (
            next %
            50L ==
            0L
        ) {

            mainHandler.post {
                txCount =
                    next
            }

        } else {

            txCount =
                next
        }
    }


    // ========================================================
    // ARM
    // Persis Arduino:
    // zero 8x @20 ms
    // ARM packet
    // delay 30 ms
    // set armed
    // zero 5x @20 ms
    // ========================================================

    fun arm() {

        executor.execute {

            if (!networkAttached) {

                updateStatus(
                    "ARM ditolak: FC belum terhubung"
                )

                return@execute
            }


            val rc =
                currentRc()


            if (
                !FlightMapper
                    .throttleSafeToArm(
                        rc.ch3
                    )
            ) {

                updateStatus(
                    "ARM ditolak: throttle harus center"
                )

                return@execute
            }


            repeat(
                8
            ) {

                sendZero()

                Thread.sleep(
                    20
                )
            }


            send(
                FlightProtocol
                    .armPacket(
                        true
                    )
            )


            Thread.sleep(
                30
            )


            armedFlag =
                true


            updateArmedState(
                true
            )


            repeat(
                5
            ) {

                sendZero()

                Thread.sleep(
                    20
                )
            }


            updateStatus(
                "ARM command sent → $targetIp:$FC_PORT"
            )
        }
    }


    // ========================================================
    // DISARM
    // ========================================================

    fun disarm() {

        executor.execute {

            armedFlag =
                false


            updateArmedState(
                false
            )


            if (!networkAttached) {

                updateStatus(
                    "DISARMED"
                )

                return@execute
            }


            repeat(
                10
            ) {

                sendZero()

                Thread.sleep(
                    20
                )
            }


            send(
                FlightProtocol
                    .armPacket(
                        false
                    )
            )


            Thread.sleep(
                20
            )


            repeat(
                4
            ) {

                sendZero()

                Thread.sleep(
                    20
                )
            }


            updateStatus(
                "DISARM command sent"
            )
        }
    }


    // ========================================================
    // ATTACH FC NETWORK
    //
    // targetIp HARUS memakai gateway WiFi FC bila tersedia.
    // Ini meniru Arduino:
    //
    // IPAddress gw = WiFi.gatewayIP();
    //
    // bukan selalu hard-code 192.168.4.1.
    // ========================================================

    fun attachNetwork(
        network: Network,
        targetIp: String =
            FALLBACK_FC_IP
    ) {

        executor.execute {

            activeAttach(
                network,
                targetIp
            )
        }
    }


    private fun activeAttach(
        network: Network,
        requestedTargetIp: String
    ) {

        try {

            networkAttached =
                false


            socket?.close()

            socket =
                null


            val resolvedIp =
                requestedTargetIp
                    .trim()
                    .ifBlank {
                        FALLBACK_FC_IP
                    }


            targetAddress =
                InetAddress
                    .getByName(
                        resolvedIp
                    )


            /*
             * ESP-DRONE COMPATIBILITY MODE
             *
             * QubitWifiManager sudah bind seluruh PROCESS ke WiFi FC.
             * Maka UDP dibuat sederhana seperti ESP32 Arduino:
             *
             *     udp.begin(1234);
             *
             * Tidak memakai Network.bindSocket() per-socket lagi.
             */
            val newSocket =
                DatagramSocket(
                    null
                )


            newSocket
                .reuseAddress =
                true


            newSocket.bind(
                InetSocketAddress(
                    LOCAL_PORT
                )
            )


            socket =
                newSocket


            targetIp =
                resolvedIp


            networkAttached =
                true


            updateStatus(
                "FC DIRECT READY • $resolvedIp:$FC_PORT • local:$LOCAL_PORT"
            )


        } catch (
            e: Exception
        ) {

            networkAttached =
                false


            try {

                socket?.close()

            } catch (
                _: Exception
            ) {
            }


            socket =
                null


            updateStatus(
                "FC UDP bind ERROR: ${e.message}"
            )
        }
    }


    fun detachNetwork() {

        executor.execute {

            networkAttached =
                false


            armedFlag =
                false


            updateArmedState(
                false
            )


            try {

                socket?.close()

            } catch (
                _: Exception
            ) {
            }


            socket =
                null


            targetAddress =
                null


            updateStatus(
                "FC LINK DETACHED"
            )
        }
    }


    // ========================================================
    // UI STATE
    // ========================================================

    private fun updateStatus(
        text: String
    ) {

        mainHandler.post {

            status =
                text
        }
    }


    private fun updateArmedState(
        state: Boolean
    ) {

        mainHandler.post {

            isArmed =
                state
        }
    }


    // ========================================================
    // CLOSE
    // ========================================================

    fun close() {

        networkAttached =
            false


        armedFlag =
            false


        try {

            socket?.close()

        } catch (
            _: Exception
        ) {
        }


        socket =
            null


        executor
            .shutdownNow()
    }
}