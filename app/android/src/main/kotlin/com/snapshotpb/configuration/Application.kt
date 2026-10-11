package com.snapshotpb.configuration

import com.snapshotpb.configuration.store.StoreIOProvider
import com.snapshotpb.configuration.store.StoreIOProviderAndroid
import com.toxicbakery.logging.Arbor
import com.toxicbakery.logging.LogCatSeedling
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.bindProvider
import org.kodein.di.instance

class Application : android.app.Application(), DIAware {

    override val di: DI by DI.lazy {
        bindProvider<Application> { this@Application }
        import(appModule)

        // Inject Android Specific Store provider
        bindProvider<StoreIOProvider>(overrides = true) {
            StoreIOProviderAndroid(
                context = instance<Application>()
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG)
            Arbor.sow(LogCatSeedling())

        log.d { "Application started" }
    }

    companion object {
        private val log = Arbor.tag("Application")
    }
}
