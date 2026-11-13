const CACHE = 'pda-zona-shell-v83';
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
