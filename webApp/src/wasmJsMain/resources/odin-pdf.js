// Bridge for the Kotlin/Wasm PDF thumbnail actual (PdfThumbnailGenerator.web.kt).
// pdf.js is vendored under pdfjs/ and loaded on the first PDF, so it costs nothing until then.
(function () {
  var libPromise = null;

  function assetUrl(rel) {
    return new URL(rel, document.baseURI).href;
  }

  function lib() {
    if (!libPromise) {
      libPromise = import(assetUrl("pdfjs/pdf.min.mjs")).then(function (pdfjs) {
        pdfjs.GlobalWorkerOptions.workerSrc = assetUrl("pdfjs/pdf.worker.min.mjs");
        return pdfjs;
      });
      libPromise.catch(function () { libPromise = null; });
    }
    return libPromise;
  }

  // Resolves { jpeg: Uint8Array, pageCount } or null for anything unusable (not a PDF, no pages,
  // password protected); the caller treats a rejection the same way.
  function renderFirstPage(bytes, maxWidth) {
    return lib().then(function (pdfjs) {
      var task = pdfjs.getDocument({ data: bytes, isEvalSupported: false });
      // Destroying the task, not only the document, also ends its worker when loading fails.
      return task.promise.then(function (doc) {
        var pageCount = doc.numPages;
        if (pageCount < 1) return null;
        return doc.getPage(1).then(function (page) {
          var base = page.getViewport({ scale: 1 });
          var viewport = page.getViewport({ scale: maxWidth / base.width });
          var canvas = document.createElement("canvas");
          canvas.width = Math.max(1, Math.round(viewport.width));
          canvas.height = Math.max(1, Math.round(viewport.height));
          var ctx = canvas.getContext("2d");
          ctx.fillStyle = "#ffffff";
          ctx.fillRect(0, 0, canvas.width, canvas.height);
          return page.render({ canvasContext: ctx, viewport: viewport }).promise.then(function () {
            return new Promise(function (resolve, reject) {
              canvas.toBlob(function (blob) {
                blob ? resolve(blob.arrayBuffer()) : reject(new Error("toBlob failed"));
              }, "image/jpeg", 0.8);
            });
          }).then(function (buf) {
            return { jpeg: new Uint8Array(buf), pageCount: pageCount };
          });
        });
      }).finally(function () { return task.destroy(); });
    });
  }

  globalThis.__odinPdf = { renderFirstPage: renderFirstPage };
})();
