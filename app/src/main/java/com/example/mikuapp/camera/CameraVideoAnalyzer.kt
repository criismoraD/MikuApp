package com.example.mikuapp.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.mikuapp.util.MikuLog
import java.io.ByteArrayOutputStream

class CameraVideoAnalyzer(
    private val onFrame: (jpeg: ByteArray) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) : ImageAnalysis.Analyzer {

    private val lock = Any()
    private var lastFrameAt = 0L
    private var frameCount = 0L

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        val send: Boolean = synchronized(lock) {
            if (now - lastFrameAt >= FRAME_INTERVAL_MS) {
                lastFrameAt = now
                true
            } else {
                false
            }
        }
        if (!send) {
            image.close()
            return
        }

        val jpeg = try {
            encodeJpeg(image)
        } catch (t: Throwable) {
            MikuLog.d(TAG, "Error convirtiendo frame: ${t.message}")
            onError(t)
            null
        } finally {
            image.close()
        }
        if (jpeg != null) {
            frameCount++
            if (frameCount == 1L || frameCount % 25L == 0L) {
                MikuLog.d(TAG, "Frames capturados: $frameCount (${jpeg.size / 1024} KB JPEG)")
            }
            onFrame(jpeg)
        }
    }

    private fun encodeJpeg(image: ImageProxy): ByteArray? {
        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val raw = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, image.width, image.height),
            JPEG_QUALITY,
            raw,
        )

        val degrees = image.imageInfo.rotationDegrees
        if (degrees == 0) {
            return raw.toByteArray()
        }

        val bitmap = BitmapFactory.decodeByteArray(raw.toByteArray(), 0, raw.size())
            ?: return raw.toByteArray()
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
        val output = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        return output.toByteArray()
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + (width * height / 2))

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var offset = 0
        if (yRowStride == width && yPlane.pixelStride == 1) {
            yBuffer.get(nv21, 0, ySize)
            offset = ySize
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, offset, width)
                offset += width
            }
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        var index = offset
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                nv21[index++] = vBuffer.get(row * vRowStride + col * vPixelStride)
                nv21[index++] = uBuffer.get(row * uRowStride + col * uPixelStride)
            }
        }
        return nv21
    }

    companion object {
        private const val TAG = "Camara"
        private const val FRAME_INTERVAL_MS = 1000L
        private const val JPEG_QUALITY = 55
    }
}