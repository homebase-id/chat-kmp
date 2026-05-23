// Synchronous (after init) sql.js bridge for the Kotlin/Wasm SqlDriver.
//
// sql.js compiles SQLite to WebAssembly and runs it ON THE MAIN THREAD, so every
// operation here is synchronous — which is what SQLDelight's generated (non-async)
// code requires. Only initialization is async (fetch + compile the wasm), exposed as
// __odin.init() returning a Promise that the Kotlin entry point awaits before the DB
// is touched. The database is in-memory; it is lost on page refresh (no SQLCipher,
// no persistence — matching the documented first-pass web constraints).
(function () {
  var db = null;

  function b64ToBytes(b64) {
    var bin = atob(b64);
    var len = bin.length;
    var arr = new Uint8Array(len);
    for (var i = 0; i < len; i++) arr[i] = bin.charCodeAt(i);
    return arr;
  }

  function bytesToB64(u8) {
    var bin = "";
    var chunk = 0x8000;
    for (var i = 0; i < u8.length; i += chunk) {
      bin += String.fromCharCode.apply(null, u8.subarray(i, Math.min(i + chunk, u8.length)));
    }
    return btoa(bin);
  }

  globalThis.__odin = {
    // `initSqlJs` is the global exposed by sql-wasm.js (loaded just before this script).
    init: function () {
      return initSqlJs({ locateFile: function (f) { return f; } }).then(function (SQL) {
        db = new SQL.Database();
        return true;
      });
    },

    // Parameter array builders (positional `?` placeholders, 0-based index).
    newParams: function (n) { return new Array(n).fill(null); },
    setLong: function (a, i, v) { a[i] = v; },
    setDouble: function (a, i, v) { a[i] = v; },
    setString: function (a, i, v) { a[i] = v; },
    setBoolean: function (a, i, v) { a[i] = v; },
    setBlob: function (a, i, b64) { a[i] = b64ToBytes(b64); },
    setNull: function (a, i) { a[i] = null; },

    // INSERT/UPDATE/DELETE/DDL — returns rows modified.
    execute: function (sql, params) {
      db.run(sql, params);
      return db.getRowsModified();
    },

    // SELECT — returns an opaque cursor wrapper.
    openCursor: function (sql, params) {
      var stmt = db.prepare(sql);
      stmt.bind(params);
      return { stmt: stmt, row: null };
    },
    cursorNext: function (c) {
      var has = c.stmt.step();
      c.row = has ? c.stmt.get() : null;
      return has;
    },
    cursorString: function (c, i) {
      var v = c.row[i];
      return (v === null || v === undefined) ? null : ("" + v);
    },
    cursorLong: function (c, i) {
      var v = c.row[i];
      return (v === null || v === undefined) ? null : Number(v);
    },
    cursorDouble: function (c, i) {
      var v = c.row[i];
      return (v === null || v === undefined) ? null : Number(v);
    },
    cursorBoolean: function (c, i) {
      var v = c.row[i];
      return (v === null || v === undefined) ? null : (Number(v) !== 0 ? 1 : 0);
    },
    cursorBlob: function (c, i) {
      var v = c.row[i];
      return (v === null || v === undefined) ? null : bytesToB64(v);
    },
    cursorClose: function (c) { c.stmt.free(); }
  };
})();
