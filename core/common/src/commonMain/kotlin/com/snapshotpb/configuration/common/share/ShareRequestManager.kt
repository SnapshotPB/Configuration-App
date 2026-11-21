package com.snapshotpb.configuration.common.share

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The bus from shared code to the share handler of each app (Android, iOS, desktop, web). */
class ShareRequestManager {
    private val _bus = MutableSharedFlow<ShareRequest>()
    val bus: SharedFlow<ShareRequest> = _bus
    val subscriptionCount: StateFlow<Int> get() = _bus.subscriptionCount
    suspend fun send(request: ShareRequest) = _bus.emit(request)
}
