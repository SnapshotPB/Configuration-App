package com.snapshotpb.configuration.bluetooth

import com.toxicbakery.logging.Arbor
import dev.bluefalcon.core.Logger

class BlueFalconLogger : Logger {
    override fun error(message: String, cause: Throwable?) {
        if (cause == null) tag.e(message)
        else tag.e(cause) { message }
    }

    override fun warn(message: String, cause: Throwable?) {
        if (cause == null) tag.w(message)
        else tag.w(cause) { message }
    }

    override fun info(message: String, cause: Throwable?) {
        if (cause == null) tag.i(message)
        else tag.i(cause) { message }
    }

    override fun debug(message: String, cause: Throwable?) {
        if (cause == null) tag.d(message)
        else tag.d(cause) { message }
    }

    companion object {
        private val tag = Arbor.tag("BlueFalcon")
    }
}
