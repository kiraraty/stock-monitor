import AppKit
import SwiftUI
import Combine
import MarketCore

final class FloatingPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    let store = MarketStore()
    private var statusItem: NSStatusItem!
    private var panel: FloatingPanel!
    private let popover = NSPopover()
    private var observers = Set<AnyCancellable>()
    private var workspaceObservers: [NSObjectProtocol] = []
    private var floatingHost: NSHostingView<Dashboard>!
    private var coinManager: NSPanel?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.target = self
        statusItem.button?.action = #selector(statusClicked)
        statusItem.button?.sendAction(on: [.leftMouseUp, .rightMouseUp])
        panel = FloatingPanel(contentRect: NSRect(x: 0, y: 0, width: 380, height: 620),
                              styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.title = "Pnale · 加密行情"
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.hidesOnDeactivate = false
        panel.isMovableByWindowBackground = true
        panel.isReleasedWhenClosed = false
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.delegate = self
        floatingHost = NSHostingView(rootView: dashboard(floating: true))
        panel.contentView = floatingHost
        panel.alphaValue = store.opacity
        resize()
        if let saved = UserDefaults.standard.string(forKey: "panelFrame") {
            let frame = NSRectFromString(saved)
            if NSScreen.screens.contains(where: { $0.visibleFrame.intersects(frame) }) {
                panel.setFrameOrigin(NSPoint(x: frame.minX, y: frame.maxY - panel.frame.height))
            } else { positionPanel() }
        } else { positionPanel() }

        popover.behavior = .transient
        popover.animates = true
        popover.contentViewController = NSHostingController(rootView: dashboard(floating: false))
        popover.appearance = NSAppearance(named: .darkAqua)
        store.$now.sink { [weak self] _ in self?.updateStatus() }.store(in: &observers)
        store.$quotes.sink { [weak self] _ in
            DispatchQueue.main.async { self?.updateStatus() }
        }.store(in: &observers)
        store.$selected.sink { [weak self] _ in
            DispatchQueue.main.async { self?.updateStatus() }
        }.store(in: &observers)
        store.$opacity.sink { [weak self] alpha in self?.panel.alphaValue = alpha }.store(in: &observers)
        store.$coins.dropFirst().sink { [weak self] _ in self?.resize() }.store(in: &observers)
        let center = NSWorkspace.shared.notificationCenter
        workspaceObservers.append(center.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.store.restart() }
        })
        workspaceObservers.append(center.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.store.stop() }
        })
        if UserDefaults.standard.object(forKey: "floatingVisible") == nil || UserDefaults.standard.bool(forKey: "floatingVisible") {
            showFloating()
        }
        store.restart()
        updateStatus()
    }

    private func dashboard(floating: Bool) -> Dashboard {
        Dashboard(store: store, floating: floating, togglePresentation: { [weak self] in
            if floating { self?.hideFloating() } else { self?.showFloating() }
        }, resize: { [weak self] in self?.resize() }, manageCoins: { [weak self] in self?.showCoinManager() })
    }

    private func positionPanel() {
        guard let screen = NSScreen.main?.visibleFrame else { return }
        panel.setFrameOrigin(NSPoint(x: screen.maxX - panel.frame.width - 24, y: screen.maxY - panel.frame.height - 24))
    }

    func resize() {
        // Defer until SwiftUI has observed the setting change, keeping the top edge stationary.
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let size = self.floatingHost.fittingSize
            let height = max(size.height, self.store.compact ? 218 : 560)
            let old = self.panel.frame
            self.panel.setFrame(NSRect(x: old.minX, y: old.maxY - height, width: 380, height: height), display: true)
            self.popover.contentSize = NSSize(width: 380, height: height)
        }
    }

    @objc private func statusClicked() {
        if NSApp.currentEvent?.type == .rightMouseUp { showContextMenu(); return }
        if popover.isShown { popover.performClose(nil); return }
        guard let button = statusItem.button else { return }
        resize()
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
        popover.contentViewController?.view.window?.makeKey()
    }

    private func showContextMenu() {
        let menu = NSMenu()
        menu.addItem(withTitle: panel.isVisible ? "收起悬浮窗" : "显示置顶悬浮窗", action: #selector(toggleFloating), keyEquivalent: "")
        menu.addItem(NSMenuItem.separator())
        for coin in store.coins {
            let item = menu.addItem(withTitle: coin + " / USDT 永续", action: #selector(selectCoin(_:)), keyEquivalent: "")
            item.representedObject = coin
            item.state = coin == store.selected ? .on : .off
        }
        menu.addItem(NSMenuItem.separator())
        menu.addItem(withTitle: "管理自选币种…", action: #selector(showCoinManager), keyEquivalent: "")
        menu.addItem(withTitle: "重新连接", action: #selector(reconnect), keyEquivalent: "r")
        menu.addItem(withTitle: "退出 Pnale", action: #selector(quit), keyEquivalent: "q")
        for item in menu.items { item.target = self }
        statusItem.menu = menu
        statusItem.button?.performClick(nil)
        statusItem.menu = nil
    }

    @objc private func selectCoin(_ sender: NSMenuItem) { if let coin = sender.representedObject as? String { store.select(coin) } }
    @objc private func reconnect() { store.restart() }
    @objc private func quit() { NSApp.terminate(nil) }
    @objc private func toggleFloating() { panel.isVisible ? hideFloating() : showFloating() }

    @objc private func showCoinManager() {
        popover.performClose(nil)
        if coinManager == nil {
            let editor = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 400, height: 570),
                                 styleMask: [.titled, .closable], backing: .buffered, defer: false)
            editor.title = "Pnale · 自选币种"
            editor.level = .floating
            editor.isReleasedWhenClosed = false
            editor.hidesOnDeactivate = false
            editor.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            editor.center()
            coinManager = editor
        }
        // Start each edit from the saved watchlist; closing discards an unsaved draft.
        coinManager?.contentView = NSHostingView(rootView: CoinManager(store: store, close: { [weak self] in
            self?.coinManager?.orderOut(nil)
        }))
        NSApp.activate(ignoringOtherApps: true)
        coinManager?.makeKeyAndOrderFront(nil)
    }

    func showFloating() {
        popover.performClose(nil)
        resize()
        panel.orderFrontRegardless()
        UserDefaults.standard.set(true, forKey: "floatingVisible")
    }
    func hideFloating() {
        panel.orderOut(nil)
        UserDefaults.standard.set(false, forKey: "floatingVisible")
    }

    private func updateStatus() {
        guard let button = statusItem?.button else { return }
        let quote = store.quote
        let value = quote.map { PriceFormat.string($0.price) } ?? "—"
        let arrow = quote.map { $0.change >= 0 ? "↗" : "↘" } ?? ""
        let stale = quote != nil && !store.fresh
        let text = "\(stale ? "◷ " : "")\(store.selected) 永续 \(value) \(arrow)"
        let title = NSMutableAttributedString(string: text, attributes: [
            .font: NSFont.monospacedDigitSystemFont(ofSize: 11, weight: .medium),
            .foregroundColor: stale ? NSColor.secondaryLabelColor : NSColor.labelColor
        ])
        if let quote, store.fresh {
            title.addAttribute(.foregroundColor, value: quote.change >= 0 ? NSColor.systemGreen : NSColor.systemRed,
                               range: (text as NSString).range(of: arrow))
        }
        button.attributedTitle = title
        button.toolTip = "Pnale · \(store.selected)/USDT U 本位永续 · 最新成交价 · \(store.status)\n点击查看 K 线，右键管理悬浮窗"
        button.setAccessibilityLabel(text + "，" + store.status)
    }

    func windowDidMove(_ notification: Notification) { UserDefaults.standard.set(NSStringFromRect(panel.frame), forKey: "panelFrame") }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func applicationWillTerminate(_ notification: Notification) {
        store.stop()
        workspaceObservers.forEach { NSWorkspace.shared.notificationCenter.removeObserver($0) }
    }
}
