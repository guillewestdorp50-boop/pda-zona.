const CACHE = 'pda-zona-shell-v107';
const CORE = ['./', './index.html', './radio-stalker-campfire.jpg?v=67', './manifest.webmanifest?v=66', './pda-icon.svg?v=66', './pda-icon-192.png?v=66'];
self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(CORE)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key !== CACHE).map(key => caches.delete(key)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  if (req.mode === 'navigate') {
    event.respondWith(fetch(req).then(response => {
      const copy = response.clone();
      caches.open(CACHE).then(cache => cache.put('./index.html', copy));
      return response;
    }).catch(() => caches.match('./index.html').then(hit => hit || caches.match('./'))));
    return;
  }
  event.respondWith(caches.match(req).then(hit => hit || fetch(req).then(response => {
    if (response.ok) { const copy = response.clone(); caches.open(CACHE).then(cache => cache.put(req, copy)); }
    return response;
  })));
});

self.addEventListener('message', event => {
  if (event.data && event.data.type === 'PDA_GET_VERSION' && event.ports && event.ports[0]) {
    event.ports[0].postMessage({ cache: CACHE });
  }
});

self.addEventListener('push', event => {
  event.waitUntil((async () => {
    let payload = {};
    try { payload = event.data ? event.data.json() : {}; }
    catch { payload = { body: event.data ? event.data.text() : '' }; }
    const title = typeof payload.title === 'string' && payload.title.trim()
      ? payload.title.trim().slice(0, 80) : 'PDA // ZONA';
    const body = typeof payload.body === 'string' ? payload.body.slice(0, 240) : 'Nueva transmisión recibida.';
    const target = typeof payload.url === 'string' ? payload.url : './';
    let safeUrl = new URL('./', self.location.origin).href;
    try {
      const parsed = new URL(target, self.location.origin);
      if (parsed.origin === self.location.origin) safeUrl = parsed.href;
    } catch {}
    const stamp = Number.isFinite(Number(payload.timestamp)) ? Number(payload.timestamp) : Date.now();
    await self.registration.showNotification(title, {
      body,
      icon: './pda-icon-192.png?v=66',
      badge: './pda-icon-192.png?v=66',
      tag: typeof payload.tag === 'string' ? payload.tag.slice(0, 80) : 'pda-zona-alert',
      renotify: true,
      timestamp: stamp,
      vibrate: [180, 90, 180],
      data: { url: safeUrl, category: typeof payload.category === 'string' ? payload.category.slice(0, 40) : 'general' }
    });
  })());
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const target = event.notification.data && event.notification.data.url;
  event.waitUntil((async () => {
    let safeUrl = new URL('./', self.location.origin).href;
    try {
      const parsed = new URL(target || './', self.location.origin);
      if (parsed.origin === self.location.origin) safeUrl = parsed.href;
    } catch {}
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const client of windows) {
      if ('focus' in client) {
        await client.focus();
        if ('navigate' in client) await client.navigate(safeUrl);
        return;
      }
    }
    if (self.clients.openWindow) await self.clients.openWindow(safeUrl);
  })());
});
