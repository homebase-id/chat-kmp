// Pins this build's Compose string tables (.cvr) for the life of the tab. Each StringResource in
// the .wasm is a byte offset into its table, and Compose re-reads the table from Cache Storage,
// which every tab of the origin shares and which a newer tab wipes and refills with the newer
// deploy's table. Must load before the app bundle.
(function () {
  var realFetch = window.fetch.bind(window);
  var pinned = new Map();

  function isTable(input) {
    var raw = typeof input === 'string' ? input : (input && input.url) || String(input);
    var parsed;
    try { parsed = new URL(raw, document.baseURI); } catch (e) { return null; }
    var path = parsed.pathname;
    return path.indexOf('/composeResources/') !== -1 && path.slice(-4) === '.cvr' ? parsed.href : null;
  }

  window.fetch = function (input, init) {
    var url = isTable(input);
    if (url === null) return realFetch(input, init);
    var body = pinned.get(url);
    if (!body) {
      body = realFetch(input, init).then(function (response) {
        if (!response.ok) throw new Error('HTTP ' + response.status + ' for ' + url);
        return response.arrayBuffer();
      });
      pinned.set(url, body);
      body.catch(function () { if (pinned.get(url) === body) pinned.delete(url); });
    }
    return body.then(function (buffer) {
      return new Response(buffer.slice(0), { status: 200, headers: { 'Content-Type': 'application/octet-stream' } });
    });
  };
})();
