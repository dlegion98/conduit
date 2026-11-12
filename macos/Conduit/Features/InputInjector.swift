import AppKit
import ApplicationServices
import Carbon.HIToolbox
import IOKit.hidsystem

/// Phone as trackpad / keyboard / media remote for the Mac. Needs Accessibility permission
/// (System Settings → Privacy & Security → Accessibility → Conduit).
final class InputInjector {
    static let shared = InputInjector()

    private var buttonDown = false
    private var lastClickTime = Date.distantPast
    private var lastClickLocation = CGPoint.zero
    private var clickCount = 1
    private var promptedForAccess = false

    var hasAccess: Bool { AXIsProcessTrusted() }

    func requestAccess() {
        let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(opts)
    }

    func handle(_ msg: [String: Any]) {
        guard hasAccess else {
            if !promptedForAccess { promptedForAccess = true; requestAccess() }
            return
        }
        let dx = CGFloat(msg.double("dx")), dy = CGFloat(msg.double("dy"))
        switch msg.str("kind") {
        case "move": move(dx, dy, dragging: false)
        case "drag": move(dx, dy, dragging: true)
        case "down": mouse(.leftMouseDown, button: .left); buttonDown = true
        case "up": mouse(.leftMouseUp, button: .left); buttonDown = false
        case "click": click(.left)
        case "doubleclick": click(.left); click(.left)
        case "rightclick": click(.right)
        case "scroll": scroll(dx, dy)
        case "text": type(msg.str("text"))
        case "key": key(msg.str("key"))
        case "media": media(msg.str("key"))
        default: break
        }
    }

    // MARK: Mouse

    private var location: CGPoint { CGEvent(source: nil)?.location ?? .zero }

    private func clamp(_ p: CGPoint) -> CGPoint {
        // CGEvent coordinates: origin at top-left of the main display.
        let screens = NSScreen.screens
        guard let main = screens.first else { return p }
        let mainHeight = main.frame.height
        let rects = screens.map { s -> CGRect in
            CGRect(x: s.frame.minX, y: mainHeight - s.frame.maxY, width: s.frame.width, height: s.frame.height)
        }
        if rects.contains(where: { $0.contains(p) }) { return p }
        let union = rects.reduce(rects[0]) { $0.union($1) }
        return CGPoint(x: min(max(p.x, union.minX), union.maxX - 1), y: min(max(p.y, union.minY), union.maxY - 1))
    }

    private func move(_ dx: CGFloat, _ dy: CGFloat, dragging: Bool) {
        let p = clamp(CGPoint(x: location.x + dx, y: location.y + dy))
        let type: CGEventType = (dragging || buttonDown) ? .leftMouseDragged : .mouseMoved
        let e = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: p, mouseButton: .left)
        e?.setIntegerValueField(.mouseEventDeltaX, value: Int64(dx))
        e?.setIntegerValueField(.mouseEventDeltaY, value: Int64(dy))
        e?.post(tap: .cghidEventTap)
    }

    private func mouse(_ type: CGEventType, button: CGMouseButton, clicks: Int = 1) {
        let e = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: location, mouseButton: button)
        e?.setIntegerValueField(.mouseEventClickState, value: Int64(clicks))
        e?.post(tap: .cghidEventTap)
    }

    private func click(_ button: CGMouseButton) {
        let here = location
        if button == .left {
            let quick = Date().timeIntervalSince(lastClickTime) < NSEvent.doubleClickInterval
            let near = hypot(here.x - lastClickLocation.x, here.y - lastClickLocation.y) < 6
            clickCount = (quick && near) ? min(clickCount + 1, 3) : 1
            lastClickTime = Date(); lastClickLocation = here
            mouse(.leftMouseDown, button: .left, clicks: clickCount)
            mouse(.leftMouseUp, button: .left, clicks: clickCount)
        } else {
            mouse(.rightMouseDown, button: .right)
            mouse(.rightMouseUp, button: .right)
        }
    }

    private func scroll(_ dx: CGFloat, _ dy: CGFloat) {
        let e = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2,
                        wheel1: Int32(dy.rounded()), wheel2: Int32(dx.rounded()), wheel3: 0)
        e?.post(tap: .cghidEventTap)
    }

    // MARK: Keyboard

    private func type(_ text: String) {
        let utf16 = Array(text.utf16)
        var i = 0
        while i < utf16.count {
            let chunk = Array(utf16[i..<min(i + 16, utf16.count)])
            for down in [true, false] {
                let e = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: down)
                e?.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: chunk)
                e?.post(tap: .cghidEventTap)
            }
            i += 16
        }
    }

    private func press(_ code: Int, flags: CGEventFlags = []) {
        for down in [true, false] {
            let e = CGEvent(keyboardEventSource: nil, virtualKey: CGKeyCode(code), keyDown: down)
            e?.flags = flags
            e?.post(tap: .cghidEventTap)
        }
    }

    private func key(_ name: String) {
        switch name {
        case "escape": press(kVK_Escape)
        case "tab": press(kVK_Tab)
        case "delete": press(kVK_Delete)
        case "return": press(kVK_Return)
        case "space": press(kVK_Space)
        case "left": press(kVK_LeftArrow)
        case "right": press(kVK_RightArrow)
        case "up": press(kVK_UpArrow)
        case "down": press(kVK_DownArrow)
        case "cmdtab": press(kVK_Tab, flags: .maskCommand)
        case "spotlight": press(kVK_Space, flags: .maskCommand)
        case "lock": press(kVK_ANSI_Q, flags: [.maskCommand, .maskControl])
        case "missioncontrol":
            NSWorkspace.shared.openApplication(at: URL(fileURLWithPath: "/System/Applications/Mission Control.app"),
                                               configuration: .init())
        default: break
        }
    }

    // MARK: Media keys (play/pause, next, previous, volume)

    private func media(_ name: String) {
        let code: Int32
        switch name {
        case "playpause": code = NX_KEYTYPE_PLAY
        case "next": code = NX_KEYTYPE_NEXT
        case "previous": code = NX_KEYTYPE_PREVIOUS
        case "volup": code = NX_KEYTYPE_SOUND_UP
        case "voldown": code = NX_KEYTYPE_SOUND_DOWN
        case "mute": code = NX_KEYTYPE_MUTE
        default: return
        }
        for down in [true, false] {
            let flags = NSEvent.ModifierFlags(rawValue: down ? 0xA00 : 0xB00)
            let data1 = Int((code << 16) | ((down ? 0xA : 0xB) << 8))
            let ev = NSEvent.otherEvent(with: .systemDefined, location: .zero, modifierFlags: flags,
                                        timestamp: 0, windowNumber: 0, context: nil, subtype: 8, data1: data1, data2: -1)
            ev?.cgEvent?.post(tap: .cghidEventTap)
        }
    }
}
