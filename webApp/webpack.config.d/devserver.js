// Serve index.html for the YouAuth popup's authorization-code-callback route instead of 404ing.
// `historyApiFallback: true` relies on the request's Accept header + a "no dot in path" heuristic,
// which can miss; an explicit rewrite for our callback path is unconditional and reliable.
// Only affects the dev server.
//
// WEBAPP_PUBLIC_PATH is set by the generated 00-publicPath.js (loaded first thanks to alphabetic
// ordering of config.d files) from the -PpublicPath Gradle property; defaults to '/'.
const publicPath = globalThis.WEBAPP_PUBLIC_PATH || '/';
const escapedPublicPath = publicPath.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

config.devServer = config.devServer || {};
config.devServer.historyApiFallback = {
    disableDotRule: true,
    index: publicPath + 'index.html',
    rewrites: [
        { from: new RegExp('^' + escapedPublicPath + 'authorization-code-callback'), to: publicPath + 'index.html' }
    ]
};

// Open Chrome (not the OS default browser) when running `wasmJsBrowserDevelopmentRun`.
// webpack-dev-server delegates to the `open` npm package, which spawns the named browser.
// IMPORTANT: `open` emits a fatal 'error' event if the named binary doesn't exist (ENOENT),
// which crashes the Gradle task — it does NOT silently fall back. So on Linux we must resolve a
// binary that actually exists rather than hardcoding `google-chrome` (many distros ship only
// `chromium` / `chromium-browser`, e.g. via apt or snap). macOS/Windows use the `open`/`start`
// app-name conventions, which are stable.
//
// Resolution order on Linux: explicit CHROME_BIN/BROWSER override → first Chrome/Chromium-family
// binary found on PATH → fall back to the OS default browser (open: true) so the dev server still
// auto-opens *something* instead of crashing.
function resolveLinuxBrowser() {
    const fs = require('fs');
    const path = require('path');

    const explicit = process.env.CHROME_BIN || process.env.BROWSER;
    if (explicit && fs.existsSync(explicit)) return explicit;

    const candidates = [
        'google-chrome', 'google-chrome-stable', 'chromium', 'chromium-browser',
        'brave-browser', 'microsoft-edge', 'microsoft-edge-stable',
    ];
    const dirs = (process.env.PATH || '').split(path.delimiter).filter(Boolean);
    for (const name of candidates) {
        for (const dir of dirs) {
            const full = path.join(dir, name);
            if (fs.existsSync(full)) return full;
        }
    }
    return null;
}

// Default: open a Chrome-family browser. When HB_DEBUG_CHROME=1, suppress auto-open so a separate
// remote-debugging Chrome (launched out-of-band with --remote-debugging-port) is the only
// app window — lets the console be captured over CDP without a competing tab.
let openConfig;
if (process.env.HB_DEBUG_CHROME === '1') {
    openConfig = false;
} else if (process.platform === 'darwin') {
    openConfig = { app: { name: 'google chrome' } };
} else if (process.platform === 'win32') {
    openConfig = { app: { name: 'chrome' } };
} else {
    const bin = resolveLinuxBrowser();
    openConfig = bin ? { app: { name: bin } } : true;
}
config.devServer.open = openConfig;