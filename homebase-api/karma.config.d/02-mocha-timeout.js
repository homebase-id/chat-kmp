// Mocha's 2 s default would cut off every async test before its own runTest timeout applies.
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha || {}, { timeout: 0 });
