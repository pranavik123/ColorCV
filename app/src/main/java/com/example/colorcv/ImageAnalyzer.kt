package com.example.colorcv

import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import androidx.camera.core.ImageProxy
import org.opencv.imgproc.Imgproc

fun imageProxyToMat(image: ImageProxy): Mat {
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvMat = Mat(image.height + image.height / 2, image.width, CvType.CV_8UC1)
    yuvMat.put(0, 0, nv21)
    val rgbMat = Mat()
    Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21)
    return rgbMat
}

