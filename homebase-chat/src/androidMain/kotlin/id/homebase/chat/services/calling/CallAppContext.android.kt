package id.homebase.chat.services.calling

import android.content.Context
import org.koin.mp.KoinPlatformTools

internal val callAppContext: Context get() = KoinPlatformTools.defaultContext().get().get()
