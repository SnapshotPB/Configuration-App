package com.snapshotpb.configuration.camera

import com.snapshotpb.configuration.common.media.ImagePicker
import org.kodein.di.DI
import org.kodein.di.bindSingleton

val cameraModule = DI.Module("cameraModule") {
    bindSingleton<ImagePicker> { CameraImagePicker() }
}
