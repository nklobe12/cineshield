// Runs at document start in every frame, before the page's own scripts.
(function () {
  'use strict';
  if (window.__cineshield) return;
  Object.defineProperty(window, '__cineshield', { value: true });

  var SITES = ['cinejoy.pk', 'cinejoy.to'];
  var isTop = window === window.top;

  function hostOf(url) {
    try { return new URL(url, location.href).hostname.toLowerCase(); } catch (e) { return ''; }
  }
  function isSite(host) {
    return SITES.some(function (s) { return host === s || host.slice(-s.length - 1) === '.' + s; });
  }
  // A link may go to the site, or stay on the page it is already on.
  function linkAllowed(url) {
    if (!url || /^(#|javascript:)/i.test(url)) return true;
    var host = hostOf(url);
    return isSite(host) || host === location.hostname.toLowerCase();
  }

  function fakeWindow() {
    var noop = function () {};
    return {
      closed: true, close: noop, focus: noop, blur: noop, postMessage: noop,
      document: { write: noop, writeln: noop, close: noop, open: noop },
      location: { href: '', assign: noop, replace: noop }
    };
  }

  var blockedOpen = function (url) {
    if (url && isTop && isSite(hostOf(String(url)))) location.href = new URL(String(url), location.href).href;
    return fakeWindow();
  };
  try {
    Object.defineProperty(window, 'open', { value: blockedOpen, writable: false, configurable: false });
  } catch (e) { window.open = blockedOpen; }

  // Web notification and push prompts are a common ad channel.
  try { if (window.Notification) window.Notification.requestPermission = function () { return Promise.resolve('denied'); }; } catch (e) {}

  function guardClick(e) {
    var node = e.target;
    while (node && node.nodeType === 1 && node.tagName !== 'A') node = node.parentNode;
    if (!node || node.nodeType !== 1 || node.tagName !== 'A') return;
    var href = node.getAttribute('href');
    var newTab = (node.getAttribute('target') || '').toLowerCase() === '_blank';
    if (!linkAllowed(href)) {
      e.preventDefault();
      e.stopImmediatePropagation();
    } else if (newTab && isTop && href) {
      // Same-site "open in new tab" links open here instead.
      e.preventDefault();
      location.href = node.href;
    }
  }
  window.addEventListener('click', guardClick, true);

  // Scripts that build a link and click it with no element in the page.
  var nativeClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (!linkAllowed(this.getAttribute('href'))) return;
    return nativeClick.call(this);
  };
  var nativeSubmit = HTMLFormElement.prototype.submit;
  HTMLFormElement.prototype.submit = function () {
    if (!linkAllowed(this.getAttribute('action'))) return;
    return nativeSubmit.call(this);
  };

  // Lightweight mode: on the site's own pages only, never inside video players.
  var lite = isTop && isSite(location.hostname.toLowerCase());
  var LITE_CSS =
    '*,*::before,*::after{animation-duration:.001s!important;animation-delay:0s!important;' +
    'animation-iteration-count:1!important;transition-duration:.001s!important;' +
    'transition-delay:0s!important;backdrop-filter:none!important;' +
    '-webkit-backdrop-filter:none!important;filter:none!important;box-shadow:none!important;' +
    'text-shadow:none!important;scroll-behavior:auto!important}' +
    'img[src*=".gif"]{visibility:hidden!important}' +
    'html,body{background-image:none!important;background-color:#0b0d10!important}';
  function addLiteStyle() {
    var style = document.createElement('style');
    style.textContent = LITE_CSS;
    (document.head || document.documentElement).appendChild(style);
  }
  // Decorative clips are looped or short. Films, episodes and live streams are not.
  function stopBackgroundVideo(e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO') return;
    if (!v.loop && !(isFinite(v.duration) && v.duration > 0 && v.duration < 300)) return;
    v.pause();
    v.removeAttribute('src');
    v.load();
    v.style.setProperty('display', 'none', 'important');
  }
  if (lite) {
    if (document.documentElement) addLiteStyle();
    else document.addEventListener('readystatechange', addLiteStyle, { once: true });
    window.addEventListener('loadedmetadata', stopBackgroundVideo, true);
    window.addEventListener('play', stopBackgroundVideo, true);
  }

  var MAX_Z = 2147480000;
  function isOverlay(el) {
    if (el.nodeType !== 1 || el === document.documentElement || el === document.body) return false;
    if (el.querySelector && el.querySelector('video')) return false;
    if (el.tagName === 'VIDEO') return false;
    if (el.tagName === 'A' && linkAllowed(el.getAttribute('href'))) return false;
    var s = getComputedStyle(el);
    if (s.position !== 'fixed' && s.position !== 'absolute') return false;
    var r = el.getBoundingClientRect();
    var area = (r.width * r.height) / Math.max(1, innerWidth * innerHeight);
    if (el.tagName === 'A' && area > 0.25) return !linkAllowed(el.getAttribute('href'));
    var z = parseInt(s.zIndex, 10) || 0;
    return z >= MAX_Z && area > 0.25 && !(el.textContent || '').trim();
  }
  function solidify(el) {
    // With blur off, see-through search and menu layers need a plain backing.
    if (el.nodeType !== 1 || el.tagName === 'IFRAME' || el.tagName === 'VIDEO') return;
    var s = getComputedStyle(el);
    if (s.position !== 'fixed') return;
    var r = el.getBoundingClientRect();
    if (r.width * r.height < 0.5 * innerWidth * innerHeight) return;
    var m = s.backgroundColor.match(/[\d.]+/g);
    var alpha = !m ? 0 : (m.length > 3 ? parseFloat(m[3]) : 1);
    if (alpha < 0.9) el.style.setProperty('background-color', '#0b0d10', 'important');
  }
  function sweep(root) {
    if (root.nodeType !== 1) return;
    if (isOverlay(root)) { root.remove(); return; }
    if (lite) solidify(root);
    var kids = root.children || [];
    for (var i = 0; i < kids.length && i < 40; i++) {
      if (lite) solidify(kids[i]);
      if (kids[i].tagName === 'DIV' && isOverlay(kids[i])) kids[i].remove();
    }
    var links = root.querySelectorAll ? root.querySelectorAll('a[href^="http"]') : [];
    for (var j = 0; j < links.length; j++) {
      if (!linkAllowed(links[j].getAttribute('href')) && isOverlay(links[j])) links[j].remove();
    }
    if (lite) {
      var frames = root.tagName === 'IFRAME' ? [root] : root.querySelectorAll('iframe[src*="youtube"]');
      for (var k = 0; k < frames.length; k++) {
        if (/[?&]mute=1/.test(frames[k].src)) stopFrame(frames[k]);
      }
    }
  }
  function stopFrame(f) {
    f.removeAttribute('src');
    f.style.setProperty('display', 'none', 'important');
  }
  // Pages change in bursts, so check them in batches rather than per element.
  var queue = [], timer = 0;
  function flush() {
    timer = 0;
    var batch = queue;
    queue = [];
    batch.forEach(function (n) { if (n.isConnected) sweep(n); });
  }
  function startObserver() {
    new MutationObserver(function (records) {
      records.forEach(function (rec) {
        for (var i = 0; i < rec.addedNodes.length; i++) {
          if (rec.addedNodes[i].nodeType === 1) queue.push(rec.addedNodes[i]);
        }
      });
      if (queue.length && !timer) timer = setTimeout(flush, 150);
    }).observe(document.documentElement, { childList: true, subtree: true });
    if (document.body) sweep(document.body);
  }
  if (document.documentElement) startObserver();
  else document.addEventListener('readystatechange', startObserver, { once: true });
  window.addEventListener('load', function () { if (document.body) sweep(document.body); });

  // Remote-control play, pause and seek. Reaches players in cross-origin frames.
  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || !d.cineshield) return;
    var videos = document.getElementsByTagName('video');
    for (var i = 0; i < videos.length; i++) {
      var v = videos[i];
      if (!v.currentSrc) continue;
      if (d.cineshield === 'toggle') { if (v.paused) v.play(); else v.pause(); }
      else if (d.cineshield === 'seek') v.currentTime = Math.max(0, v.currentTime + d.s);
    }
  });
})();
