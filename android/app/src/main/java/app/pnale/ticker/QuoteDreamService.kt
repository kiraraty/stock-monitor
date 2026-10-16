package app.pnale.ticker

import android.service.dreams.DreamService

class QuoteDreamService : DreamService() {
    private val app get() = application as PnaleApp
    private var board: MarketBoard? = null
    private var acquired = false
    private val listener: (MarketState) -> Unit = { board?.render(it) }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        board = MarketBoard(this).also { setContentView(it) }
        window?.let { it.attributes = it.attributes.apply { screenBrightness = app.preferences.boardBrightness / 100f } }
    }
    override fun onDreamingStarted() {
        super.onDreamingStarted()
        if (!acquired) { acquired = true; board?.start(); app.market.listen(listener); app.market.acquire(this) }
    }
    private fun stopMarket() {
        board?.stop()
        if (acquired) { app.market.unlisten(listener); app.market.release(this); acquired = false }
    }
    override fun onDreamingStopped() { stopMarket(); super.onDreamingStopped() }
    override fun onDetachedFromWindow() { stopMarket(); board = null; super.onDetachedFromWindow() }
    override fun onDestroy() { stopMarket(); super.onDestroy() }
}
