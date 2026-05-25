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
// webpack-dev-server delegates to the `open` npm package, whose Chrome app name differs per OS.
// If Chrome isn't installed the dev server still starts — it just won't auto-open a tab, so this
// is safe for teammates on other setups.
const chromeAppName =
    process.platform === 'darwin' ? 'google chrome' :
    process.platform === 'win32' ? 'chrome' :
    'google-chrome';
// Default: open plain Chrome. When HB_DEBUG_CHROME=1, suppress auto-open so a separate
// remote-debugging Chrome (launched out-of-band with --remote-debugging-port) is the only
// app window — lets the console be captured over CDP without a competing tab.
config.devServer.open = process.env.HB_DEBUG_CHROME === '1'
    ? false
    : { app: { name: chromeAppName } };