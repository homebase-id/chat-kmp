// Bridge for the Kotlin/Wasm web-push actual (WebPush.web.kt).
//
// Raw VAPID, not FCM: the server routes on an empty FirebaseDeviceToken, and DotYouCore carries
// no WebpushConfig, so an FCM-routed browser subscription would be accepted and never delivered.
//
// Every op resolves (never rejects) so the DOMException.name survives the Kotlin boundary —
// awaiting a rejected JS promise on kotlinx-coroutines 1.10.2 collapses it to a generic
// IllegalStateException, and NotAllowedError (user denied) vs AbortError (push service
// unreachable) is exactly the distinction the UI needs.
//
// Results are ";"-joined with the endpoint LAST so a ";" inside an endpoint survives the split:
//   ok;<auth>;<p256dh>;<expirationTime|"">;<endpoint>   |   err;<DOMExceptionName>   |   ""
(function () {
  var SUBSCRIBE_TIMEOUT_MS = 30000;

  var registrationPromise = null;
  var clickHandler = null;

  // capability() values that cannot show anything, as the code the Kotlin side maps to a message.
  var NOT_GRANTED_CODE = {
    denied: 'NotAllowedError',
    'default': 'PermissionNotGranted',
    'needs-install': 'NeedsInstall',
    unsupported: 'Unsupported',
  };

  function isIosLike() {
    var ua = navigator.userAgent || '';
    return /iPad|iPhone|iPod/.test(ua) ||
      (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  }

  function isStandalone() {
    return (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches) ||
      navigator.standalone === true;
  }

  // index.html installs a stub Notification class when the API is absent, so trust its
  // supportsNotifications flag over a `typeof Notification` probe.
  function hasNotificationApi() {
    return typeof self.supportsNotifications === 'boolean'
      ? self.supportsNotifications
      : typeof Notification !== 'undefined';
  }

  function capability() {
    var usable = ('serviceWorker' in navigator) && ('PushManager' in self) &&
      hasNotificationApi() && self.isSecureContext;
    if (!usable) {
      // On a non-installed iOS Safari tab the APIs are absent rather than denied (16.4+ fires
      // web push only for a Home Screen install), so the UI can say "add to Home Screen" instead
      // of showing a dead button.
      return (isIosLike() && !isStandalone()) ? 'needs-install' : 'unsupported';
    }
    var permission = Notification.permission;
    return (permission === 'granted' || permission === 'denied') ? permission : 'default';
  }

  // Lazy: registering eagerly would log a MIME failure on every dev-server load, because
  // historyApiFallback + disableDotRule answers a missed /sw.js with index.html.
  function registration() {
    if (registrationPromise) return registrationPromise;
    if (!('serviceWorker' in navigator)) {
      registrationPromise = Promise.resolve(null);
      return registrationPromise;
    }
    var swUrl = new URL('sw.js', document.baseURI).href;
    var scope = new URL('./', document.baseURI).href;
    // Deliberately not awaiting navigator.serviceWorker.ready: a worker that registers but fails
    // to install leaves it pending forever, and pushManager is usable the moment register()
    // resolves.
    registrationPromise = navigator.serviceWorker.register(swUrl, { scope: scope })
      .catch(function (e) {
        console.warn('[odin-push] service worker unavailable:', (e && e.message) || e);
        return null;
      });
    return registrationPromise;
  }

  function b64url(buffer) {
    if (!buffer) return '';
    var bytes = new Uint8Array(buffer);
    var binary = '';
    for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    // Base64URL, not plain base64 — the server decodes these as URL-safe.
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  function format(sub) {
    if (!sub) return '';
    return [
      'ok',
      b64url(sub.getKey('auth')),
      b64url(sub.getKey('p256dh')),
      sub.expirationTime == null ? '' : String(sub.expirationTime),
      sub.endpoint,
    ].join(';');
  }

  function fail(e) {
    return 'err;' + ((e && e.name) || 'Error');
  }

  function withTimeout(promise) {
    return Promise.race([
      promise,
      new Promise(function (resolve) {
        setTimeout(function () { resolve('err;TimeoutError'); }, SUBSCRIBE_TIMEOUT_MS);
      }),
    ]);
  }

  function doSubscribe(reg, vapidKey) {
    // The server hands out a bare base64url string and it goes straight into
    // applicationServerKey — no urlBase64ToUint8Array conversion.
    return reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: vapidKey });
  }

  globalThis.__odinPush = {
    capability: capability,

    requestPermission: function () {
      return new Promise(function (resolve) {
        if (capability() !== 'default') { resolve(capability()); return; }
        try {
          // Safari <16 only has the legacy callback form; everything else returns a promise.
          var result = Notification.requestPermission(function (p) { resolve(p || 'denied'); });
          if (result && typeof result.then === 'function') {
            result.then(function (p) { resolve(p || 'denied'); }, function () { resolve('denied'); });
          }
        } catch (e) {
          resolve('denied');
        }
      });
    },

    subscribe: function (vapidKey) {
      return withTimeout(registration().then(function (reg) {
        if (!reg) return 'err;NoServiceWorker';
        return doSubscribe(reg, vapidKey).then(format, function (e) {
          // A subscription left behind by another identity carries that tenant's VAPID key and
          // makes subscribe() reject InvalidStateError. Drop it and take the new key.
          if (!e || e.name !== 'InvalidStateError') throw e;
          return reg.pushManager.getSubscription().then(function (stale) {
            return (stale ? stale.unsubscribe() : Promise.resolve())
              .then(function () { return doSubscribe(reg, vapidKey); })
              .then(format);
          });
        });
      }).catch(fail));
    },

    currentSubscription: function () {
      return registration().then(function (reg) {
        if (!reg) return '';
        return reg.pushManager.getSubscription().then(format);
      }).catch(function () { return ''; });
    },

    unsubscribe: function () {
      return registration().then(function (reg) {
        if (!reg) return null;
        return reg.pushManager.getSubscription().then(function (sub) {
          return sub ? sub.unsubscribe() : null;
        });
      }).catch(function () { return null; });
    },

    // Developer menu only — never on the push path, which sw.js displays. showNotification() on
    // the registration rather than `new Notification(...)`: the constructor is unsupported on
    // Android Chrome and deprecated wherever a worker is registered.
    showLocalNotification: function (title, body) {
      var cap = capability();
      if (cap !== 'granted') return Promise.resolve('err;' + (NOT_GRANTED_CODE[cap] || 'Unsupported'));
      return withTimeout(registration().then(function (reg) {
        if (!reg) return 'err;NoServiceWorker';
        return reg.showNotification(title, {
          body: body,
          icon: new URL('icon-192.png', document.baseURI).href,
          tag: 'odin-dev-test',
          renotify: true,
        }).then(function () { return 'ok'; });
      }).catch(fail));
    },

    onClick: function (handler) {
      clickHandler = handler;
    },
  };

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.addEventListener('message', function (event) {
      var data = event.data;
      if (!data || data.type !== 'odin-push-click' || typeof data.data !== 'string') return;
      if (clickHandler) clickHandler(data.data);
    });
  }
})();
