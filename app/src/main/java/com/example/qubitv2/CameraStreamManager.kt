package com.example.qubitv2

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import android.os.Handler
import android.os.Looper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean


class CameraStreamManager {

    companion object {
        private const val MAX_JPEG_BYTES =
            256 * 1024

        private const val INPUT_BUFFER_BYTES =
            64 * 1024
    }


    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )


    @Volatile
    private var running =
        false


    @Volatile
    private var connection:
            HttpURLConnection? = null


    @Volatile
    private var worker:
            Thread? = null


    // Hanya satu update frame boleh menunggu di main thread.
    // Bila decoder lebih cepat daripada UI, frame lama dibuang
    // supaya FPV tetap "latest frame", bukan menumpuk latency.
    private val uiFramePending =
        AtomicBoolean(false)


    @Volatile
    private var latestBitmap:
            Bitmap? = null


    // Latest decoded camera frame for photo/video capture.
    // This is a reference to an immutable decoded frame; it is never modified.
    @Volatile
    private var captureFrame:
            Bitmap? = null


    @Volatile
    private var latestFps:
            Float? = null


    var frame by
    mutableStateOf<Bitmap?>(null)
        private set


    var status by
    mutableStateOf("FPV IDLE")
        private set


    var isStreaming by
    mutableStateOf(false)
        private set


    var fps by
    mutableStateOf(0f)
        private set


    // ============================================================
    // START
    // ============================================================

    @Synchronized
    fun start(
        network: Network,
        streamUrl: String
    ) {

        stopInternal(
            updateUi = false
        )


        running = true


        mainHandler.post {
            isStreaming = false
            status = "CONNECTING FPV..."
            fps = 0f
        }


        val thread =
            Thread(
                {
                    streamLoop(
                        network,
                        streamUrl
                    )
                },
                "Qubit-FPV-Fast"
            )


        worker = thread

        thread.priority =
            Thread.MAX_PRIORITY

        thread.start()
    }


    // ============================================================
    // MJPEG LOOP - FAST CONTENT-LENGTH PARSER
    //
    // Server Qubit mengirim setiap frame seperti:
    //
    // --boundary
    // Content-Type: image/jpeg
    // Content-Length: 12345
    //
    // <JPEG bytes>
    //
    // Versi lama membaca input SATU BYTE per read().
    // Itu sangat mahal dan dapat membatasi FPS.
    //
    // Versi ini:
    // 1. baca header per baris
    // 2. ambil Content-Length
    // 3. baca seluruh JPEG dengan buffer besar
    // 4. decode langsung
    // ============================================================

    private fun streamLoop(
        network: Network,
        streamUrl: String
    ) {

        var input:
                BufferedInputStream? = null


        try {

            val url =
                URL(
                    streamUrl
                )


            val http =
                network
                    .openConnection(
                        url
                    ) as HttpURLConnection


            connection = http


            http.connectTimeout =
                2500


            http.readTimeout =
                10000


            http.useCaches =
                false


            http.doInput =
                true


            http.setRequestProperty(
                "Connection",
                "Keep-Alive"
            )


            http.setRequestProperty(
                "Accept",
                "multipart/x-mixed-replace"
            )


            http.connect()


            val response =
                http.responseCode


            if (
                response !in
                200..299
            ) {

                throw IllegalStateException(
                    "HTTP $response"
                )
            }


            input =
                BufferedInputStream(
                    http.inputStream,
                    INPUT_BUFFER_BYTES
                )


            val decodeOptions =
                BitmapFactory.Options().apply {

                    // RGB_565 cukup untuk FPV dan jauh lebih ringan
                    // daripada ARGB_8888.
                    inPreferredConfig =
                        Bitmap.Config.RGB_565

                    inDither =
                        false
                }


            var frames =
                0


            var fpsStart =
                System.nanoTime()


            while (
                running
            ) {

                // Cari boundary / awal part berikutnya.
                var line:
                        String?


                do {

                    line =
                        readAsciiLine(
                            input
                        )


                    if (
                        line == null
                    ) {

                        throw IllegalStateException(
                            "FPV stream ended"
                        )
                    }

                } while (
                    running &&
                    !line.startsWith(
                        "--"
                    )
                )


                if (
                    !running
                ) {
                    break
                }


                // Baca header JPEG.
                var contentLength =
                    -1


                while (
                    running
                ) {

                    line =
                        readAsciiLine(
                            input
                        )


                    if (
                        line == null
                    ) {

                        throw IllegalStateException(
                            "FPV header ended"
                        )
                    }


                    if (
                        line.isEmpty()
                    ) {

                        break
                    }


                    val colon =
                        line.indexOf(
                            ':'
                        )


                    if (
                        colon >
                        0
                    ) {

                        val name =
                            line
                                .substring(
                                    0,
                                    colon
                                )
                                .trim()


                        if (
                            name.equals(
                                "Content-Length",
                                ignoreCase =
                                    true
                            )
                        ) {

                            contentLength =
                                line
                                    .substring(
                                        colon + 1
                                    )
                                    .trim()
                                    .toIntOrNull()
                                    ?: -1
                        }
                    }
                }


                if (
                    contentLength <=
                    0 ||
                    contentLength >
                    MAX_JPEG_BYTES
                ) {

                    throw IllegalStateException(
                        "Invalid JPEG size: $contentLength"
                    )
                }


                // Satu read penuh per frame, bukan byte-per-byte.
                val jpeg =
                    ByteArray(
                        contentLength
                    )


                readFully(
                    input,
                    jpeg
                )


                if (
                    !running
                ) {
                    break
                }


                val bitmap =
                    BitmapFactory
                        .decodeByteArray(
                            jpeg,
                            0,
                            jpeg.size,
                            decodeOptions
                        )


                if (
                    bitmap != null
                ) {

                    captureFrame =
                        bitmap


                    frames++


                    val now =
                        System.nanoTime()


                    val elapsed =
                        (
                                now -
                                        fpsStart
                                ) /
                                1_000_000_000.0


                    var newFps:
                            Float? = null


                    if (
                        elapsed >=
                        1.0
                    ) {

                        newFps =
                            (
                                    frames /
                                            elapsed
                                    ).toFloat()


                        frames =
                            0


                        fpsStart =
                            now
                    }


                    publishLatestFrame(
                        bitmap,
                        newFps
                    )
                }
            }


            if (
                running
            ) {

                mainHandler.post {
                    status =
                        "FPV STREAM ENDED"

                    isStreaming =
                        false
                }
            }


        } catch (
            e: Exception
        ) {

            if (
                running
            ) {

                mainHandler.post {
                    status =
                        "FPV ERROR: ${e.message}"

                    isStreaming =
                        false
                }
            }

        } finally {

            try {
                input?.close()
            } catch (
                _: Exception
            ) {
            }


            try {
                connection?.disconnect()
            } catch (
                _: Exception
            ) {
            }


            connection =
                null


            if (
                running
            ) {

                running =
                    false
            }
        }
    }


    // ============================================================
    // PUBLISH ONLY THE LATEST FRAME
    //
    // Hindari antrean puluhan Bitmap di main thread.
    // Bila UI belum sempat menggambar frame sebelumnya,
    // kita simpan hanya frame terbaru.
    // ============================================================

    private fun publishLatestFrame(
        bitmap: Bitmap,
        newFps: Float?
    ) {

        latestBitmap =
            bitmap


        if (
            newFps != null
        ) {

            latestFps =
                newFps
        }


        if (
            uiFramePending
                .compareAndSet(
                    false,
                    true
                )
        ) {

            mainHandler.post(
                object :
                    Runnable {

                    override fun run() {

                        val newest =
                            latestBitmap


                        latestBitmap =
                            null


                        val fpsValue =
                            latestFps


                        latestFps =
                            null


                        if (
                            newest != null
                        ) {

                            frame =
                                newest
                        }


                        if (
                            fpsValue != null
                        ) {

                            fps =
                                fpsValue
                        }


                        if (
                            running
                        ) {

                            isStreaming =
                                true


                            status =
                                "FPV LIVE"
                        }


                        uiFramePending.set(
                            false
                        )


                        // Jika ketika UI sedang menggambar ternyata
                        // decoder sudah menghasilkan frame baru,
                        // jadwalkan satu update terbaru lagi.
                        val pending =
                            latestBitmap


                        if (
                            pending != null &&
                            running
                        ) {

                            publishLatestFrame(
                                pending,
                                null
                            )
                        }
                    }
                }
            )
        }
    }


    // ============================================================
    // FAST IO HELPERS
    // ============================================================

    private fun readFully(
        input: BufferedInputStream,
        target: ByteArray
    ) {

        var offset =
            0


        while (
            offset <
            target.size
        ) {

            val count =
                input.read(
                    target,
                    offset,
                    target.size -
                            offset
                )


            if (
                count <
                0
            ) {

                throw IllegalStateException(
                    "Unexpected end of JPEG"
                )
            }


            offset +=
                count
        }
    }


    private fun readAsciiLine(
        input: BufferedInputStream
    ): String? {

        val builder =
            StringBuilder(
                64
            )


        while (
            true
        ) {

            val value =
                input.read()


            if (
                value <
                0
            ) {

                return if (
                    builder.isEmpty()
                ) {
                    null
                } else {
                    builder.toString()
                }
            }


            if (
                value ==
                '\n'.code
            ) {

                return builder
                    .toString()
                    .trimEnd(
                        '\r'
                    )
            }


            if (
                builder.length <
                1024
            ) {

                builder.append(
                    value.toChar()
                )
            }
        }
    }


    // ============================================================
    // PHOTO / VIDEO CAPTURE ACCESS
    // ============================================================

    fun latestFrameForCapture(): Bitmap? =
        captureFrame


    // ============================================================
    // STOP
    // ============================================================

    @Synchronized
    fun stop() {

        stopInternal(
            updateUi = true
        )
    }


    private fun stopInternal(
        updateUi: Boolean
    ) {

        running =
            false


        try {
            connection?.disconnect()
        } catch (
            _: Exception
        ) {
        }


        connection =
            null


        worker?.interrupt()


        worker =
            null


        latestBitmap =
            null


        captureFrame =
            null


        latestFps =
            null


        uiFramePending.set(
            false
        )


        if (
            updateUi
        ) {

            mainHandler.post {
                isStreaming =
                    false

                status =
                    "FPV STOPPED"

                fps =
                    0f
            }
        }
    }


    fun close() {
        stop()
    }
}
