package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.media.MediaRequestManager
import com.snapshotpb.configuration.media.MediaStateManager
import org.kodein.di.DI
import org.kodein.di.bindSingletonOf

val mediaCoreModule = DI.Module("mediaCoreModule") {
    bindSingletonOf(::MediaStateManager)
    bindSingletonOf(::MediaRequestManager)
}
