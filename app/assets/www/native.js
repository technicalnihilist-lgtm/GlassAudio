/* Glass Audio - native bridge shim.
 *
 * The web app builds its output as a blob: URL on an <a download> link.
 * A plain WebView can't download blob URLs, so we intercept those clicks,
 * stream the blob to the native side in 3 MB base64 chunks and let Android
 * write it into Music/GlassAudio via MediaStore.
 *
 * Loaded only when window.GlassNative exists, so the same index.html still
 * works perfectly in a desktop browser.
 */
(function () {
  if (!window.GlassNative) return;

  var CHUNK = 3 * 1024 * 1024;

  function b64(blob) {
    return new Promise(function (ok, no) {
      var fr = new FileReader();
      fr.onload = function () {
        var s = String(fr.result);
        var i = s.indexOf(',');
        ok(i >= 0 ? s.slice(i + 1) : '');
      };
      fr.onerror = function () { no(new Error('could not read the converted audio')); };
      fr.readAsDataURL(blob);
    });
  }

  var saving = false;

  function saveBlob(url, name) {
    if (saving) return Promise.resolve();
    saving = true;
    return fetch(url)
      .then(function (r) { return r.blob(); })
      .then(function (blob) {
        var type = blob.type || 'application/octet-stream';
        GlassNative.beginFile(name, type);
        var offset = 0;
        function step() {
          if (offset >= blob.size) {
            GlassNative.endFile();
            saving = false;
            return;
          }
          var slice = blob.slice(offset, offset + CHUNK);
          offset += CHUNK;
          return b64(slice)
            .then(function (data) {
              GlassNative.appendChunk(data);
              return new Promise(function (r) { setTimeout(r, 0); });
            })
            .then(step);
        }
        return step();
      })
      .catch(function (e) {
        saving = false;
        GlassNative.toastMsg('Save failed: ' + (e && e.message ? e.message : e));
      });
  }

  document.addEventListener('click', function (e) {
    var el = e.target;
    if (!el || !el.closest) return;
    var a = el.closest('a[download]');
    if (!a || !a.href || a.href.indexOf('blob:') !== 0) return;
    e.preventDefault();
    e.stopPropagation();
    saveBlob(a.href, a.getAttribute('download') || 'audio.mp3');
  }, true);

  // Long-press / context menu on the audio player shouldn't open a dead menu.
  document.addEventListener('contextmenu', function (e) {
    if (e.target && e.target.tagName === 'A') e.preventDefault();
  });
})();
