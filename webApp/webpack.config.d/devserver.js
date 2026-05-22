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