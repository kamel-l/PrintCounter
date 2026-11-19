
package com.printcounter

import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext

class CameraPreviewManager :
    SimpleViewManager<CameraPreview>() {

    override fun getName(): String {
        return "CameraPreview"
    }

    override fun createViewInstance(
        reactContext: ThemedReactContext
    ): CameraPreview {
        return CameraPreview(reactContext)
    }

    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> {
        return MapBuilder.of(
            "topMarkDetected",
            MapBuilder.of("registrationName", "onMarkDetected")
        )
    }
}
