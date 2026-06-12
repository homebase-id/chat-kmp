package id.homebase.core.location.tracking

actual fun observeAppForeground(onChange: (Boolean) -> Unit) {
    // Desktop: no tracker, so the distinction is irrelevant — report foreground once.
    onChange(true)
}
