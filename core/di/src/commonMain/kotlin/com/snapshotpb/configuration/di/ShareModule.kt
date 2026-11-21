package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.common.share.ShareRequestManager
import org.kodein.di.DI
import org.kodein.di.bindSingletonOf

val shareModule = DI.Module("shareModule") {
    bindSingletonOf(::ShareRequestManager)
}
