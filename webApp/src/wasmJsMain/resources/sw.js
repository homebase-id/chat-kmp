// Push-only service worker for Homebase Chat on the web.
//
// There is deliberately NO fetch handler. Push, showNotification and notificationclick all work
// without one, and a pass-through handler would force a worker start on every request and break
// the HTTP Range requests this app streams media over.
//
// The VAPID payload is a BATCH, not the single-payload FCM shape the KMP client reads:
//   {"payloads":[{"senderId","appDisplayName","timestamp","options":{appId,typeId,tagId,silent,
//                 unEncryptedMessage}}]}

var CHAT_APP_ID = '2d781401-3804-4b57-b4aa-d8e4e2ef39f4';

self.addEventListener('install', function () {
  self.skipWaiting();
});

self.addEventListener('activate', function (event) {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('push', function (event) {
  var batch = null;
  try {
    batch = event.data ? event.data.json() : null;
  } catch (e) {
    batch = null;
  }
  var payloads = (batch && batch.payloads) || [];

  event.waitUntil(Promise.all(payloads.map(function (payload) {
    var options = payload.options || {};
    if (options.silent) return Promise.resolve();

    // Coalesce per conversation for chat, per notification elsewhere.
    var tag = options.appId === CHAT_APP_ID ? options.typeId : options.tagId;

    return self.registration.showNotification(payload.appDisplayName || 'Homebase', {
      body: options.unEncryptedMessage || payload.senderId || '',
      tag: tag || undefined,
      renotify: !!tag,
      timestamp: payload.timestamp || Date.now(),
      // Shaped like the FCM payload map so the page can hand it straight to
      // NotificationService.handleNotificationClicked.
      data: { data: JSON.stringify(payload) },
    });
  })));
});

self.addEventListener('notificationclick', function (event) {
  event.notification.close();
  var payload = (event.notification.data && event.notification.data.data) || null;
  var scope = self.registration.scope;

  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (clients) {
      for (var i = 0; i < clients.length; i++) {
        var client = clients[i];
        if (client.url.indexOf(scope) !== 0) continue;
        if (payload) client.postMessage({ type: 'odin-push-click', data: payload });
        return client.focus();
      }
      return self.clients.openWindow(scope);
    })
  );
});
