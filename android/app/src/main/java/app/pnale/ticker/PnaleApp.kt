package app.pnale.ticker

import android.app.Application

class PnaleApp : Application() {
    val config by lazy { ConfigStore(this) }
    val preferences by lazy { Preferences(this) }
    val market by lazy { MarketEngine(preferences, this) }
    val serverAlarms by lazy { ServerAlarms(this) }
    override fun onCreate() { super.onCreate(); config.start(); serverAlarms }
}
