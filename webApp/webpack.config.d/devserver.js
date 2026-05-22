// Serve index.html for the YouAuth popup's /authorization-code-callback route instead of 404ing.
// `historyApiFallback: true` relies on the request's Accept header + a "no dot in path" heuristic,
// which can miss; an explicit rewrite for our callback path is unconditional and reliable.
// Only affects the dev server.
config.devServer = config.devServer || {};
config.devServer.historyApiFallback = {
    disableDotRule: true,
    index: '/index.html',
    rewrites: [
        { from: /^\/authorization-code-callback/, to: '/index.html' }
    ]
};
