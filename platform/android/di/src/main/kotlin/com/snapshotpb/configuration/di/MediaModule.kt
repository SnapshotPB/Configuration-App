package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.media.MediaManager
import org.kodein.di.DI
import org.kodein.di.bindSingletonOf

internal val mediaModule = DI.Module("mediaModule") {
    bindSingletonOf(::MediaManager)
}