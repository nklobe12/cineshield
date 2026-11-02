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

  var MAX_Z = 2147480000;
  function isOverlay(el) {
    if (el.nodeType !== 1 || el === document.documentElement || el === document.body) return false;
    if (el.querySelector && el.querySelector('video')) return false;
    if (el.tagName === 'VIDEO') return false;
    var s = getComputedStyle(el);
    if (s.position !== 'fixed' && s.position !== 'absolute') return false;
    var r = el.getBoundingClientRect();
    var area = (r.width * r.height) / Math.max(1, innerWidth * innerHeight);
    if (el.tagName === 'A' && area > 0.25) return !linkAllowed(el.getAttribute('href'));
    var z = parseInt(s.zIndex, 10) || 0;
    return z >= MAX_Z && area > 0.25 && !(el.textContent || '').trim();
  }
  function sweep(root) {
    if (root.nodeType !== 1) return;
    if (isOverlay(root)) { root.remove(); return; }
    var list = root.querySelectorAll ? root.querySelectorAll('a[href], div') : [];
    for (var i = 0; i < list.length; i++) {
      if (list[i].isConnected && isOverlay(list[i])) list[i].remove();
    }
  }
  function startObserver() {
    new MutationObserver(function (records) {
      records.forEach(function (rec) {
        for (var i = 0; i < rec.addedNodes.length; i++) sweep(rec.addedNodes[i]);
      });
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
