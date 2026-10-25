@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.app

import id.homebase.core.ui.navigation.AppLink
import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import org.w3c.dom.PopStateEvent

/** `#filesync`-style links into a mini app. */
object WebAppLink {
    private const val PENDING_KEY = "homebase_app_link"

    /** Call before any sign-in redirect: it drops the hash, sessionStorage survives it. */
    fun captureFromUrl() {
        val link = AppLink.fromSlug(window.location.hash)
        if (link != null) {
            sessionStorage.setItem(PENDING_KEY, link.slug)
        } else if (!SeamlessOwnerLogin.isCallbackUrl()) {
            sessionStorage.removeItem(PENDING_KEY)
        }
    }

    fun consumePending(): AppLink? {
        val slug = sessionStorage.getItem(PENDING_KEY) ?: return null
        sessionStorage.removeItem(PENDING_KEY)
        return AppLink.fromSlug(slug)
    }

    /** A hash typed into the address bar; back/forward entries carry the navigator's state and are skipped. */
    fun listenForTypedHash(onLink: (AppLink) -> Unit) {
        window.addEventListener("popstate", { event ->
            if ((event as PopStateEvent).state != null) return@addEventListener
            AppLink.fromSlug(window.location.hash)?.let(onLink)
        })
    }
}
