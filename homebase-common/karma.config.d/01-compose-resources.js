// Compose resources load from ./composeResources/ relative to the page, but Karma serves the test
// bundle's files under /base/kotlin/; without this, stringResource and Res.readBytes fail in wasm tests.
config.files = (config.files || []).concat([
    { pattern: "kotlin/composeResources/**/*", included: false, watched: false, served: true },
]);
config.proxies = Object.assign({}, config.proxies || {}, {
    "/composeResources/": "/base/kotlin/composeResources/",
});
