package com.crakac.encodingtest.util

import android.graphics.Point
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
import android.view.Display

class SmartSize(width: Int, height: Int) {
    var size = Size(width, height)
    var long = maxOf(width, height)
    var short = minOf(width, height)
    override fun toString(): String {
        return "Smartsize(${long}x${short}"
    }
}

val SIZE_1080P = SmartSize(1920, 1080)

fun getDisplaySmartSize(display: Display): SmartSize {
    val point = Point()
    display.getRealSize(point)
    return SmartSize(point.x, point.y)
}

fun <T> getPreviewOutputSize(
    display: Display,
    characteristics: CameraCharacteristics,
    targetClass: Class<T>,
    format: Int? = null
): Size {
    val screenSize = getDisplaySmartSize(display)
    val isHdScreen = screenSize.long >= SIZE_1080P.long || screenSize.short >= SIZE_1080P.short
    val maxSize = if (isHdScreen) SIZE_1080P else screenSize
    val config = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    if (format == null) {
        assert(StreamConfigurationMap.isOutputSupportedFor(targetClass))
    } else {
        assert(config.isOutputSupportedFor(format))
    }
    val allSizes = if (format == null)
        config.getOutputSizes(targetClass) else config.getOutputSizes(format)

    val validSizes = allSizes.sortedWith(compareBy { it.height * it.width })
        .map { SmartSize(it.width, it.height) }.reversed()
    return validSizes.first { it.long <= maxSize.long && it.short <= maxSize.short }.size

}