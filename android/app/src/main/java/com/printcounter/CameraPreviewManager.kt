package com.printcounter

import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext

class CameraPreviewManager :
    SimpleViewManager<CameraPreview>() {

    override fun getName(): String = "CameraPreview"

    override fun createViewInstance(
        reactContext: ThemedReactContext
    ): CameraPreview = CameraPreview(reactContext)

    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> {
        val events: MutableMap<String, Any> = HashMap()
        events["topMarkDetected"] = mapOf("registrationName" to "onMarkDetected")
        events["topCameraError"]  = mapOf("registrationName" to "onCameraError")
        return events
    }
}