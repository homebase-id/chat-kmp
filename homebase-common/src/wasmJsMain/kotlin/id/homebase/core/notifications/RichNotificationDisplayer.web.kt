package id.homebase.core.notifications

/**
 * Web: no-op. Every browser push is shown by `sw.js` — `userVisibleOnly: true` obliges the worker
 * to display one for each push, foreground included — and no push path reaches Kotlin here, so
 * this displayer is never on a live path.
 */
actual class RichNotificationDisplayer actual constructor() {
    actual fun show(data: RichNotificationData) {
    }
}
