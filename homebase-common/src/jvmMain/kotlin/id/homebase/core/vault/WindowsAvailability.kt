package id.homebase.core.vault

import com.sun.jna.NativeLibrary

// Hello may be off, but the CredUI account-password fallback works wherever credui loads.
internal fun windowsAvailability(): Boolean =
    try {
        NativeLibrary.getInstance("credui")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }
