package com.android.gpstest.library.di

import android.content.Context
import android.content.SharedPreferences
import com.android.gpstest.library.data.LocationRepository
import com.android.gpstest.library.data.SharedAntennaManager
import com.android.gpstest.library.data.SharedGnssMeasurementManager
import com.android.gpstest.library.data.SharedGnssStatusManager
import com.android.gpstest.library.data.SharedLocationManager
import com.android.gpstest.library.data.SharedNavMessageManager
import com.android.gpstest.library.data.SharedNmeaManager
import com.android.gpstest.library.data.SharedSensorManager
import com.android.gpstest.library.ui.SignalInfoViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.core.module.dsl.viewModel

@OptIn(ExperimentalCoroutinesApi::class)
val appModule = module {

  // === SharedPreferences ===
  single {
    androidContext().getSharedPreferences(
        "${androidContext().packageName}_preferences",
        Context.MODE_PRIVATE
    )
  }

  // === CoroutineScope - 使用 SupervisorJob 避免取消传播 ===
  single(named("appScope")) {
    CoroutineScope(SupervisorJob() + Dispatchers.IO)
  }

  // === Data 层 ===
  single {
    SharedLocationManager(
        context = androidContext(),
        externalScope = get(named("appScope")),
        prefs = get()
    )
  }

  single {
    SharedGnssStatusManager(
        context = androidContext(),
        externalScope = get(named("appScope")),
        prefs = get()
    )
  }

  single {
    SharedNmeaManager(
        context = androidContext(),
        externalScope = get(named("appScope")),
        prefs = get()
    )
  }

  single {
    SharedSensorManager(
        prefs = get(),
        context = androidContext(),
        externalScope = get(named("appScope"))
    )
  }

  single {
    SharedNavMessageManager(
        context = androidContext(),
        externalScope = get(named("appScope")),
        prefs = get()
    )
  }

  single {
    SharedGnssMeasurementManager(
        prefs = get(),
        context = androidContext(),
        externalScope = get(named("appScope"))
    )
  }

  single {
    SharedAntennaManager(
        context = androidContext(),
        externalScope = get(named("appScope")),
        prefs = get()
    )
  }

  // === Repository ===
  single {
    LocationRepository(
        sharedLocationManager = get(),
        sharedGnssStatusManager = get(),
        sharedNmeaManager = get(),
        sharedSensorManager = get(),
        sharedNavMessageManager = get(),
        sharedGnssMeasurementManager = get(),
        sharedAntennaManager = get()
    )
  }

  // === ViewModel ===
  viewModel {
    SignalInfoViewModel(
        context = androidContext(),
        application = androidApplication(),
        repository = get(),
        prefs = get()
    )
  }
}
