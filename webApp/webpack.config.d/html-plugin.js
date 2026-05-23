// HtmlWebpackPlugin owns the emission of index.html so it can substitute the (content-hashed in
// production) bundle filename into the bootstrap script. The Gradle `generateHtmlTemplate` task
// pre-substitutes @PUBLIC_PATH@ into the template at build/generated/html-template/index.html;
// here we just hand that template to the plugin and let it resolve <%= htmlWebpackPlugin.files.js[0] %>.
//
// `inject: false` because our bootstrap IIFE handles dynamic script loading (and skips the bundle
// entirely in YouAuth popup callbacks). Letting HtmlWebpackPlugin auto-inject would defeat that.
//
// Production: content-hash output.filename and output.chunkFilename so each deploy produces unique
// URLs and downstream caches can use long-lived TTLs without stale-bundle risk.
// Development: keep unhashed names — hashing on every recompile would needlessly invalidate the
// dev server's in-memory file resolution on every keystroke.

const path = require('path');
const HtmlWebpackPlugin = require('html-webpack-plugin');

const templatePath = path.resolve(
    __dirname,
    '../../../../webApp/build/generated/html-template/index.html'
);

config.plugins = config.plugins || [];
config.plugins.push(new HtmlWebpackPlugin({
    template: templatePath,
    filename: 'index.html',
    inject: false,
    minify: false,
}));

if (config.mode === 'production') {
    config.output = config.output || {};
    config.output.filename = (chunkData) => {
        const base = chunkData.chunk && chunkData.chunk.name === 'main'
            ? 'homebase-app'
            : 'homebase-app-[name]';
        return `${base}.[contenthash:8].js`;
    };
    config.output.chunkFilename = 'homebase-app-[name].[contenthash:8].js';
}
