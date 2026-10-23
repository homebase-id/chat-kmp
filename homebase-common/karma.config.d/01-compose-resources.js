// Compose resources load from ./composeResources/ relative to the page, but Karma serves the test
// bundle's files under /base/kotlin/; without this, stringResource and Res.readBytes fail in wasm tests.
// The flag file goes first: runComposeUiTest never yields to the event loop, so fetch never resolves;
// it selects Compose's sync-XHR test reader before the test bundle loads.
config.files = ["kotlin/compose-resources-testing.js"].concat(config.files || [], [
    { pattern: "kotlin/composeResources/**/*", included: false, watched: false, served: true },
]);
config.proxies = Object.assign({}, config.proxies || {}, {
    "/composeResources/": "/base/kotlin/composeResources/",
});
