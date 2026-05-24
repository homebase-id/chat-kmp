// Bridge for the Kotlin/Wasm FFmpegUtils web actual (FFmpegBridge.web.kt).
//
// ffmpeg.wasm runs FFmpeg (n5.1.4, single-thread @ffmpeg/core) inside its OWN web
// worker, so every op here is async (worker round-trip) and returns a Promise that
// the Kotlin side awaits. Unlike sql.js (odin-sqljs.js), nothing here is synchronous.
//
// Loading is LAZY: the ~22 MB core wasm is only fetched on the first exec/probe-less
// op that needs ffmpeg (compress / segment). Thumbnail + duration use mp4box / the
// browser <video> element instead and never touch this loader.
//
// Vendored assets (no npm/CDN at runtime):
//   ffmpeg-wrapper.js               -> globalThis.FFmpegWASM.FFmpeg (UMD, main-thread class)
//   ffmpeg-wasm/worker-loader.js    -> the module worker that imports the core
//   ffmpeg-wasm/core/esm/ffmpeg-core.{js,wasm}
//   mp4box.all.min.js               -> globalThis.MP4Box (container probe)
// All resolved against document.baseURI so sub-path hosting (-PpublicPath=/apps/chat-wasm/)
// works.
(function () {
  var ffmpeg = null;        // shared FFmpeg instance, created lazily
  var loadPromise = null;   // in-flight load() guard
  var progressCb = null;    // Kotlin (Float)->Unit, set before each exec
  var logLines = [];        // accumulates worker stdout/stderr for version parsing

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

  // Same as @ffmpeg/util's toBlobURL: fetch a same-origin asset and re-wrap it as a
  // Blob URL so the worker can import it without cross-origin / module-resolution snags.
  function toBlobURL(url, mimeType) {
    return fetch(url)
      .then(function (r) { return r.arrayBuffer(); })
      .then(function (buf) {
        return URL.createObjectURL(new Blob([buf], { type: mimeType }));
      });
  }

  function assetUrl(rel) {
    return new URL(rel, document.baseURI).href;
  }

  function ensureLoaded() {
    if (ffmpeg) return Promise.resolve(ffmpeg);
    if (loadPromise) return loadPromise;
    loadPromise = (function () {
      var base = assetUrl("ffmpeg-wasm/core/esm/");
      return Promise.all([
        toBlobURL(base + "ffmpeg-core.js", "text/javascript"),
        toBlobURL(base + "ffmpeg-core.wasm", "application/wasm"),
      ]).then(function (urls) {
        var inst = new globalThis.FFmpegWASM.FFmpeg();
        inst.on("progress", function (e) {
          if (progressCb && e && typeof e.progress === "number") progressCb(e.progress);
        });
        inst.on("log", function (e) {
          if (e && typeof e.message === "string") logLines.push(e.message);
        });
        return inst.load({
          coreURL: urls[0],
          wasmURL: urls[1],
          // Static same-origin module worker; the loader's unpkg fallback is never hit
          // because we always pass explicit coreURL/wasmURL.
          classWorkerURL: assetUrl("ffmpeg-wasm/worker-loader.js"),
        }).then(function () {
          ffmpeg = inst;
          return inst;
        });
      });
    })();
    return loadPromise;
  }

  // ---- mp4box container probe (no ffmpeg core load) -------------------------

  // tkhd display matrix -> rotation degrees. Mirrors odin-js getRotationFromMatrix.
  function rotationFromMatrix(matrix) {
    if (!matrix || matrix.length < 5) return 0;
    function f(v) { return v / 65536; }
    var a = f(matrix[0]), b = f(matrix[1]), c = f(matrix[3]), d = f(matrix[4]);
    if (Math.abs(a) < 0.01 && Math.abs(b - 1) < 0.01 && Math.abs(c + 1) < 0.01 && Math.abs(d) < 0.01) return -90;
    if (Math.abs(a) < 0.01 && Math.abs(b + 1) < 0.01 && Math.abs(c - 1) < 0.01 && Math.abs(d) < 0.01) return 90;
    if (Math.abs(a + 1) < 0.01 && Math.abs(b) < 0.01 && Math.abs(c) < 0.01 && Math.abs(d + 1) < 0.01) return 180;
    return 0;
  }

  // Normalize an mp4box fourcc-style codec ("avc1.64001f", "hvc1...") to the short
  // form FfmpegCompressPlanner.isH264 understands ("h264"); anything non-AVC stays
  // descriptive so the planner falls through to a real transcode.
  function normalizeCodec(c) {
    if (!c) return "";
    var lc = ("" + c).toLowerCase();
    if (lc.indexOf("avc") === 0) return "h264";
    if (lc.indexOf("hvc") === 0 || lc.indexOf("hev") === 0) return "hevc";
    return lc;
  }

  // Returns "codec;width;height;durationMs;rotation" (raw container dims + rotation,
  // matching what the Android probe feeds the planner), or "" if the file isn't a
  // parseable mp4/mov. Never loads the ffmpeg core.
  function probe(b64) {
    return new Promise(function (resolve) {
      var done = false;
      function finish(v) { if (!done) { done = true; resolve(v); } }
      try {
        var mp4 = globalThis.MP4Box.createFile(true);
        mp4.onError = function () { finish(""); };
        mp4.onReady = function (info) {
          try {
            var rotation = 0;
            var moov = mp4.moov;
            if (moov && moov.traks) {
              for (var i = 0; i < moov.traks.length; i++) {
                var tk = moov.traks[i];
                if (tk.mdia && tk.mdia.hdlr && tk.mdia.hdlr.handler === "vide") {
                  if (tk.tkhd && tk.tkhd.matrix) rotation = rotationFromMatrix(tk.tkhd.matrix);
                  break;
                }
              }
            }
            var v = null;
            for (var j = 0; j < info.tracks.length; j++) {
              if (info.tracks[j].type === "video") { v = info.tracks[j]; break; }
            }
            var width = 0, height = 0, codec = "";
            if (v) {
              width = (v.video && v.video.width) || v.track_width || 0;
              height = (v.video && v.video.height) || v.track_height || 0;
              codec = normalizeCodec(v.codec);
            }
            var durationMs = info.timescale ? Math.round((info.duration / info.timescale) * 1000) : 0;
            finish(codec + ";" + width + ";" + height + ";" + durationMs + ";" + rotation);
          } catch (ex) { finish(""); }
        };
        var bytes = b64ToBytes(b64);
        var ab = bytes.buffer;
        ab.fileStart = 0;
        mp4.appendBuffer(ab);
        mp4.flush();
        // Safety net: a non-mp4 (or moov-less) input may never fire onReady/onError.
        // The planner tolerates an empty probe (falls through to transcode), so bound
        // the wait rather than hang the Kotlin coroutine.
        setTimeout(function () { finish(""); }, 10000);
      } catch (ex) { finish(""); }
    });
  }

  globalThis.__odinFfmpeg = {
    isLoaded: function () { return ffmpeg != null; },
    load: function () { return ensureLoaded().then(function () { return true; }); },

    probe: probe,

    setProgress: function (cb) { progressCb = cb; },

    writeFile: function (path, b64) {
      return ensureLoaded().then(function (f) {
        return f.writeFile(path, b64ToBytes(b64)).then(function () { return true; });
      });
    },
    writeText: function (path, text) {
      return ensureLoaded().then(function (f) {
        return f.writeFile(path, text).then(function () { return true; });
      });
    },
    readFile: function (path) {
      return ensureLoaded().then(function (f) {
        return f.readFile(path, "binary").then(function (d) { return bytesToB64(d); });
      });
    },
    deleteFile: function (path) {
      return ensureLoaded().then(function (f) {
        return f.deleteFile(path).then(function () { return true; }, function () { return false; });
      });
    },

    // Runs ffmpeg; resolves the exit code as a string ("0" = success) so it crosses
    // the wasm boundary as a plain JsString. argsJson is a JSON array of args.
    exec: function (argsJson) {
      return ensureLoaded().then(function (f) {
        return f.exec(JSON.parse(argsJson)).then(function (code) { return "" + code; });
      });
    },

    // Version support (Milestone 2). Only meaningful once the core is loaded; callers
    // must not force a load just to read the version.
    clearLog: function () { logLines = []; },
    getLog: function () { return logLines.join("\n"); },
  };
})();
