package com.example.qubitv2

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors


/**
 * Qubit V2 FPV media capture.
 *
 * Photo:
 *   Pictures/QubitV2/QubitV2_IMG_yyyyMMdd_HHmmss.jpg
 *
 * Video:
 *   Movies/QubitV2/QubitV2_VID_yyyyMMdd_HHmmss.mp4
 *
 * Video intentionally records at 20 FPS even when the live stream is faster.
 * This keeps CPU load moderate so flight-control UDP remains the priority.
 */
class FpvMediaManager(
    private val context: Context
) {

    companion object {
        private const val VIDEO_MIME = "video/avc"
        private const val VIDEO_FPS = 20
        private const val VIDEO_BITRATE = 1_000_000
        private const val I_FRAME_INTERVAL_SEC = 1
    }


    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )


    private val photoExecutor =
        Executors.newSingleThreadExecutor()


    @Volatile
    private var recordingRequested =
        false


    @Volatile
    private var recordingThread:
            Thread? = null


    var isRecording by
    mutableStateOf(false)
        private set


    var isBusy by
    mutableStateOf(false)
        private set


    var recordingDurationMs by
    mutableLongStateOf(0L)
        private set


    var status by
    mutableStateOf("MEDIA READY")
        private set


    // ============================================================
    // PHOTO
    // ============================================================

    fun capturePhoto(
        bitmap: Bitmap?
    ) {

        if (
            bitmap ==
            null
        ) {
            showToast(
                "Frame FPV belum tersedia"
            )
            return
        }


        // Bitmap dari decoder tidak dimodifikasi lagi setelah dipublish,
        // jadi aman dibaca di background thread.
        photoExecutor.execute {

            try {

                val name =
                    "QubitV2_IMG_${timestamp()}.jpg"


                savePhotoToGallery(
                    bitmap,
                    name
                )


                mainHandler.post {
                    status =
                        "PHOTO SAVED"

                    showToast(
                        "Foto tersimpan di Pictures/QubitV2"
                    )
                }

            } catch (
                e: Exception
            ) {

                mainHandler.post {
                    status =
                        "PHOTO ERROR"

                    showToast(
                        "Gagal menyimpan foto: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }


    // ============================================================
    // VIDEO
    // ============================================================

    fun startVideo(
        frameProvider: () -> Bitmap?
    ) {

        synchronized(
            this
        ) {

            if (
                recordingThread !=
                null ||
                isBusy
            ) {
                return
            }


            val firstFrame =
                frameProvider()


            if (
                firstFrame ==
                null
            ) {
                showToast(
                    "Frame FPV belum tersedia"
                )
                return
            }


            recordingRequested =
                true


            mainHandler.post {
                isRecording =
                    true

                isBusy =
                    true

                recordingDurationMs =
                    0L

                status =
                    "REC"
            }


            val worker =
                Thread(
                    {
                        recordVideoLoop(
                            firstFrame,
                            frameProvider
                        )
                    },
                    "Qubit-FPV-Recorder"
                )


            recordingThread =
                worker


            // Flight control / MJPEG reader remain higher priority.
            worker.priority =
                Thread.NORM_PRIORITY


            worker.start()
        }
    }


    fun stopVideo() {

        if (
            !isRecording &&
            recordingThread ==
            null
        ) {
            return
        }


        recordingRequested =
            false


        mainHandler.post {
            isRecording =
                false

            status =
                "SAVING VIDEO..."
        }
    }


    fun toggleVideo(
        frameProvider: () -> Bitmap?
    ) {

        if (
            isRecording
        ) {
            stopVideo()
        } else if (
            !isBusy
        ) {
            startVideo(
                frameProvider
            )
        }
    }


    // ============================================================
    // RECORDING CORE
    // ============================================================

    private fun recordVideoLoop(
        firstFrame: Bitmap,
        frameProvider: () -> Bitmap?
    ) {

        val tempFile =
            File(
                context.cacheDir,
                "QubitV2_recording_${System.currentTimeMillis()}.mp4"
            )


        var codec:
                MediaCodec? = null

        var muxer:
                MediaMuxer? = null

        var muxerStarted =
            false

        var trackIndex =
            -1


        try {

            val width =
                evenDimension(
                    firstFrame.width
                )


            val height =
                evenDimension(
                    firstFrame.height
                )


            require(
                width >= 2 &&
                        height >= 2
            ) {
                "Invalid FPV frame size"
            }


            val encoder =
                MediaCodec.createEncoderByType(
                    VIDEO_MIME
                )


            codec =
                encoder


            val colorFormat =
                chooseColorFormat(
                    encoder
                )


            val format =
                MediaFormat
                    .createVideoFormat(
                        VIDEO_MIME,
                        width,
                        height
                    )
                    .apply {

                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            colorFormat
                        )

                        setInteger(
                            MediaFormat.KEY_BIT_RATE,
                            VIDEO_BITRATE
                        )

                        setInteger(
                            MediaFormat.KEY_FRAME_RATE,
                            VIDEO_FPS
                        )

                        setInteger(
                            MediaFormat.KEY_I_FRAME_INTERVAL,
                            I_FRAME_INTERVAL_SEC
                        )
                    }


            encoder.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )


            encoder.start()


            val mediaMuxer =
                MediaMuxer(
                    tempFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )


            muxer =
                mediaMuxer


            val pixelBuffer =
                IntArray(
                    width *
                            height
                )


            val yuvBuffer =
                ByteArray(
                    width *
                            height *
                            3 /
                            2
                )


            val bufferInfo =
                MediaCodec.BufferInfo()


            val startNs =
                System.nanoTime()


            val frameIntervalNs =
                1_000_000_000L /
                        VIDEO_FPS


            var nextFrameNs =
                startNs


            var lastFrame:
                    Bitmap = firstFrame


            var lastUiDurationUpdateMs =
                -1L


            while (
                recordingRequested
            ) {

                val nowNs =
                    System.nanoTime()


                if (
                    nowNs <
                    nextFrameNs
                ) {

                    val sleepNs =
                        nextFrameNs -
                                nowNs


                    val sleepMs =
                        sleepNs /
                                1_000_000L


                    val extraNs =
                        (
                                sleepNs %
                                        1_000_000L
                                ).toInt()


                    try {
                        Thread.sleep(
                            sleepMs,
                            extraNs
                        )
                    } catch (
                        _: InterruptedException
                    ) {
                    }
                }


                if (
                    !recordingRequested
                ) {
                    break
                }


                frameProvider()
                    ?.let {
                        lastFrame =
                            it
                    }


                bitmapToYuv420(
                    bitmap =
                        lastFrame,
                    width =
                        width,
                    height =
                        height,
                    colorFormat =
                        colorFormat,
                    pixels =
                        pixelBuffer,
                    output =
                        yuvBuffer
                )


                val inputIndex =
                    encoder.dequeueInputBuffer(
                        10_000
                    )


                if (
                    inputIndex >=
                    0
                ) {

                    val inputBuffer =
                        encoder.getInputBuffer(
                            inputIndex
                        )


                    if (
                        inputBuffer !=
                        null
                    ) {

                        inputBuffer.clear()


                        if (
                            inputBuffer.remaining() <
                            yuvBuffer.size
                        ) {
                            throw IllegalStateException(
                                "Encoder input buffer too small"
                            )
                        }


                        inputBuffer.put(
                            yuvBuffer
                        )


                        val presentationUs =
                            (
                                    System.nanoTime() -
                                            startNs
                                    ) /
                                    1000L


                        encoder.queueInputBuffer(
                            inputIndex,
                            0,
                            yuvBuffer.size,
                            presentationUs,
                            0
                        )
                    }
                }


                val drain =
                    drainEncoder(
                        codec =
                            encoder,
                        muxer =
                            mediaMuxer,
                        bufferInfo =
                            bufferInfo,
                        muxerStarted =
                            muxerStarted,
                        trackIndex =
                            trackIndex,
                        endOfStream =
                            false
                    )


                muxerStarted =
                    drain.muxerStarted


                trackIndex =
                    drain.trackIndex


                val elapsedMs =
                    (
                            System.nanoTime() -
                                    startNs
                            ) /
                            1_000_000L


                if (
                    lastUiDurationUpdateMs <
                    0L ||
                    elapsedMs -
                    lastUiDurationUpdateMs >=
                    250L
                ) {
                    lastUiDurationUpdateMs =
                        elapsedMs

                    mainHandler.post {
                        recordingDurationMs =
                            elapsedMs
                    }
                }


                nextFrameNs +=
                    frameIntervalNs


                // If encoding was temporarily slow, skip old timestamps.
                val afterNs =
                    System.nanoTime()


                if (
                    afterNs -
                    nextFrameNs >
                    frameIntervalNs *
                    2
                ) {
                    nextFrameNs =
                        afterNs +
                                frameIntervalNs
                }
            }


            // Queue EOS.
            var eosQueued =
                false


            var eosAttempts =
                0


            while (
                !eosQueued &&
                eosAttempts <
                100
            ) {

                eosAttempts++


                val inputIndex =
                    encoder.dequeueInputBuffer(
                        10_000
                    )


                if (
                    inputIndex >=
                    0
                ) {

                    val presentationUs =
                        (
                                System.nanoTime() -
                                        startNs
                                ) /
                                1000L


                    encoder.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        presentationUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )


                    eosQueued =
                        true
                }
            }


            val finalDrain =
                drainEncoder(
                    codec =
                        encoder,
                    muxer =
                        mediaMuxer,
                    bufferInfo =
                        bufferInfo,
                    muxerStarted =
                        muxerStarted,
                    trackIndex =
                        trackIndex,
                    endOfStream =
                        true
                )


            muxerStarted =
                finalDrain.muxerStarted


            trackIndex =
                finalDrain.trackIndex


            // Stop codec first, then muxer.
            try {
                encoder.stop()
            } catch (
                _: Exception
            ) {
            }


            encoder.release()
            codec = null


            if (
                muxerStarted
            ) {
                mediaMuxer.stop()
            }


            mediaMuxer.release()
            muxer = null


            if (
                tempFile.exists() &&
                tempFile.length() >
                0L
            ) {

                val name =
                    "QubitV2_VID_${timestamp()}.mp4"


                saveVideoToGallery(
                    tempFile,
                    name
                )


                mainHandler.post {
                    status =
                        "VIDEO SAVED"

                    showToast(
                        "Video tersimpan di Movies/QubitV2"
                    )
                }

            } else {
                throw IllegalStateException(
                    "Video file is empty"
                )
            }


        } catch (
            e: Exception
        ) {

            mainHandler.post {
                status =
                    "VIDEO ERROR"

                showToast(
                    "Gagal merekam video: ${e.message ?: "unknown error"}"
                )
            }

        } finally {

            recordingRequested =
                false


            try {
                codec?.stop()
            } catch (
                _: Exception
            ) {
            }


            try {
                codec?.release()
            } catch (
                _: Exception
            ) {
            }


            try {
                if (
                    muxerStarted
                ) {
                    muxer?.stop()
                }
            } catch (
                _: Exception
            ) {
            }


            try {
                muxer?.release()
            } catch (
                _: Exception
            ) {
            }


            try {
                if (
                    tempFile.exists()
                ) {
                    tempFile.delete()
                }
            } catch (
                _: Exception
            ) {
            }


            synchronized(
                this
            ) {
                recordingThread =
                    null
            }


            mainHandler.post {
                isRecording =
                    false

                isBusy =
                    false

                recordingDurationMs =
                    0L
            }
        }
    }


    private data class DrainResult(
        val muxerStarted: Boolean,
        val trackIndex: Int
    )


    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        muxerStarted: Boolean,
        trackIndex: Int,
        endOfStream: Boolean
    ): DrainResult {

        var started =
            muxerStarted


        var track =
            trackIndex


        var eosSeen =
            false


        var idleCount =
            0


        while (
            !eosSeen
        ) {

            val outputIndex =
                codec.dequeueOutputBuffer(
                    bufferInfo,
                    if (
                        endOfStream
                    ) {
                        10_000
                    } else {
                        0
                    }
                )


            when {

                outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {

                    if (
                        !endOfStream
                    ) {
                        break
                    }


                    idleCount++


                    if (
                        idleCount >
                        200
                    ) {
                        break
                    }
                }


                outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    if (
                        started
                    ) {
                        throw IllegalStateException(
                            "Encoder format changed twice"
                        )
                    }


                    track =
                        muxer.addTrack(
                            codec.outputFormat
                        )


                    muxer.start()


                    started =
                        true
                }


                outputIndex >=
                        0 -> {

                    idleCount =
                        0


                    val encodedData =
                        codec.getOutputBuffer(
                            outputIndex
                        )


                    if (
                        (
                                bufferInfo.flags and
                                        MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                                ) !=
                        0
                    ) {
                        bufferInfo.size =
                            0
                    }


                    if (
                        bufferInfo.size >
                        0
                    ) {

                        if (
                            !started
                        ) {
                            throw IllegalStateException(
                                "Muxer has not started"
                            )
                        }


                        requireNotNull(
                            encodedData
                        )


                        encodedData.position(
                            bufferInfo.offset
                        )


                        encodedData.limit(
                            bufferInfo.offset +
                                    bufferInfo.size
                        )


                        muxer.writeSampleData(
                            track,
                            encodedData,
                            bufferInfo
                        )
                    }


                    eosSeen =
                        (
                                bufferInfo.flags and
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                ) !=
                                0


                    codec.releaseOutputBuffer(
                        outputIndex,
                        false
                    )
                }
            }
        }


        return DrainResult(
            muxerStarted =
                started,
            trackIndex =
                track
        )
    }


    // ============================================================
    // VIDEO COLOR CONVERSION
    // ============================================================

    private fun chooseColorFormat(
        codec: MediaCodec
    ): Int {

        val formats =
            codec.codecInfo
                .getCapabilitiesForType(
                    VIDEO_MIME
                )
                .colorFormats
                .toSet()


        return when {

            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in
                    formats ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar


            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in
                    formats ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar


            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in
                    formats ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible


            else ->
                throw IllegalStateException(
                    "No YUV420 AVC encoder input format"
                )
        }
    }


    private fun bitmapToYuv420(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        colorFormat: Int,
        pixels: IntArray,
        output: ByteArray
    ) {

        val source =
            if (
                bitmap.width ==
                width &&
                bitmap.height ==
                height
            ) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(
                    bitmap,
                    width,
                    height,
                    true
                )
            }


        source.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )


        if (
            source !==
            bitmap
        ) {
            source.recycle()
        }


        val frameSize =
            width *
                    height


        val semiPlanar =
            colorFormat ==
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar


        var yIndex =
            0


        var uvIndex =
            frameSize


        var uIndex =
            frameSize


        var vIndex =
            frameSize +
                    frameSize /
                    4


        for (
        j in 0 until height
        ) {

            for (
            i in 0 until width
            ) {

                val color =
                    pixels[
                        j *
                                width +
                                i
                    ]


                val r =
                    (
                            color shr
                                    16
                            ) and
                            0xFF


                val g =
                    (
                            color shr
                                    8
                            ) and
                            0xFF


                val b =
                    color and
                            0xFF


                val y =
                    (
                            (
                                    66 *
                                            r +
                                            129 *
                                            g +
                                            25 *
                                            b +
                                            128
                                    ) shr
                                    8
                            ) +
                            16


                val u =
                    (
                            (
                                    -38 *
                                            r -
                                            74 *
                                            g +
                                            112 *
                                            b +
                                            128
                                    ) shr
                                    8
                            ) +
                            128


                val v =
                    (
                            (
                                    112 *
                                            r -
                                            94 *
                                            g -
                                            18 *
                                            b +
                                            128
                                    ) shr
                                    8
                            ) +
                            128


                output[
                    yIndex++
                ] =
                    y
                        .coerceIn(
                            0,
                            255
                        )
                        .toByte()


                if (
                    j %
                    2 ==
                    0 &&
                    i %
                    2 ==
                    0
                ) {

                    if (
                        semiPlanar
                    ) {

                        // NV12 = U then V.
                        output[
                            uvIndex++
                        ] =
                            u
                                .coerceIn(
                                    0,
                                    255
                                )
                                .toByte()


                        output[
                            uvIndex++
                        ] =
                            v
                                .coerceIn(
                                    0,
                                    255
                                )
                                .toByte()

                    } else {

                        // I420 = Y plane, U plane, V plane.
                        output[
                            uIndex++
                        ] =
                            u
                                .coerceIn(
                                    0,
                                    255
                                )
                                .toByte()


                        output[
                            vIndex++
                        ] =
                            v
                                .coerceIn(
                                    0,
                                    255
                                )
                                .toByte()
                    }
                }
            }
        }
    }


    private fun evenDimension(
        value: Int
    ): Int =
        if (
            value %
            2 ==
            0
        ) {
            value
        } else {
            value -
                    1
        }


    // ============================================================
    // MEDIASTORE / PUBLIC STORAGE
    // ============================================================

    private fun savePhotoToGallery(
        bitmap: Bitmap,
        displayName: String
    ): Uri? {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {

            val resolver =
                context.contentResolver


            val values =
                ContentValues().apply {

                    put(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        displayName
                    )

                    put(
                        MediaStore.Images.Media.MIME_TYPE,
                        "image/jpeg"
                    )

                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/QubitV2"
                    )

                    put(
                        MediaStore.Images.Media.IS_PENDING,
                        1
                    )
                }


            val uri =
                resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: throw IllegalStateException(
                    "Cannot create photo in MediaStore"
                )


            try {

                resolver
                    .openOutputStream(
                        uri
                    )
                    .use {
                            output ->

                        requireNotNull(
                            output
                        )


                        if (
                            !bitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                95,
                                output
                            )
                        ) {
                            throw IllegalStateException(
                                "JPEG encode failed"
                            )
                        }
                    }


                values.clear()


                values.put(
                    MediaStore.Images.Media.IS_PENDING,
                    0
                )


                resolver.update(
                    uri,
                    values,
                    null,
                    null
                )


                uri

            } catch (
                e: Exception
            ) {

                resolver.delete(
                    uri,
                    null,
                    null
                )


                throw e
            }

        } else {

            @Suppress("DEPRECATION")
            val directory =
                File(
                    Environment
                        .getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES
                        ),
                    "QubitV2"
                )


            if (
                !directory.exists() &&
                !directory.mkdirs()
            ) {
                throw IllegalStateException(
                    "Cannot create Pictures/QubitV2"
                )
            }


            val file =
                File(
                    directory,
                    displayName
                )


            FileOutputStream(
                file
            ).use {
                    output ->

                if (
                    !bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        95,
                        output
                    )
                ) {
                    throw IllegalStateException(
                        "JPEG encode failed"
                    )
                }
            }


            MediaScannerConnection.scanFile(
                context,
                arrayOf(
                    file.absolutePath
                ),
                arrayOf(
                    "image/jpeg"
                ),
                null
            )


            Uri.fromFile(
                file
            )
        }
    }


    private fun saveVideoToGallery(
        tempFile: File,
        displayName: String
    ): Uri? {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {

            val resolver =
                context.contentResolver


            val values =
                ContentValues().apply {

                    put(
                        MediaStore.Video.Media.DISPLAY_NAME,
                        displayName
                    )

                    put(
                        MediaStore.Video.Media.MIME_TYPE,
                        "video/mp4"
                    )

                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/QubitV2"
                    )

                    put(
                        MediaStore.Video.Media.IS_PENDING,
                        1
                    )
                }


            val uri =
                resolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: throw IllegalStateException(
                    "Cannot create video in MediaStore"
                )


            try {

                resolver
                    .openOutputStream(
                        uri
                    )
                    .use {
                            output ->

                        requireNotNull(
                            output
                        )


                        FileInputStream(
                            tempFile
                        ).use {
                                input ->

                            input.copyTo(
                                output,
                                64 *
                                        1024
                            )
                        }
                    }


                values.clear()


                values.put(
                    MediaStore.Video.Media.IS_PENDING,
                    0
                )


                resolver.update(
                    uri,
                    values,
                    null,
                    null
                )


                uri

            } catch (
                e: Exception
            ) {

                resolver.delete(
                    uri,
                    null,
                    null
                )


                throw e
            }

        } else {

            @Suppress("DEPRECATION")
            val directory =
                File(
                    Environment
                        .getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_MOVIES
                        ),
                    "QubitV2"
                )


            if (
                !directory.exists() &&
                !directory.mkdirs()
            ) {
                throw IllegalStateException(
                    "Cannot create Movies/QubitV2"
                )
            }


            val outputFile =
                File(
                    directory,
                    displayName
                )


            FileInputStream(
                tempFile
            ).use {
                    input ->

                FileOutputStream(
                    outputFile
                ).use {
                        output ->

                    input.copyTo(
                        output,
                        64 *
                                1024
                    )
                }
            }


            MediaScannerConnection.scanFile(
                context,
                arrayOf(
                    outputFile.absolutePath
                ),
                arrayOf(
                    "video/mp4"
                ),
                null
            )


            Uri.fromFile(
                outputFile
            )
        }
    }


    // ============================================================
    // UI HELPERS
    // ============================================================

    fun formattedDuration(): String {

        val totalSeconds =
            recordingDurationMs /
                    1000L


        val minutes =
            totalSeconds /
                    60L


        val seconds =
            totalSeconds %
                    60L


        return String.format(
            Locale.US,
            "%02d:%02d",
            minutes,
            seconds
        )
    }


    private fun timestamp(): String =
        SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(
            Date()
        )


    private fun showToast(
        message: String
    ) {

        if (
            Looper.myLooper() ==
            Looper.getMainLooper()
        ) {
            Toast.makeText(
                context,
                message,
                Toast.LENGTH_SHORT
            ).show()
        } else {
            mainHandler.post {
                Toast.makeText(
                    context,
                    message,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }


    fun close() {

        if (
            isRecording
        ) {
            stopVideo()
        }


        photoExecutor.shutdown()
    }
}
