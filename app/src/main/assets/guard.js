// Injected into instagram.com. Hides Reels / Explore / feed entry points and stops taps on
// links to blocked pages. The native side (MainActivity) is the backstop for anything that
// slips through: it bounces the WebView away from blocked URLs.
//
// The two placeholders below are replaced with data from UrlPolicy.kt at load time.
(function () {
  if (window.__noReelsGuard) return;
  window.__noReelsGuard = true;

  var ALLOWED_PREFIXES = /*ALLOWED_PREFIXES*/[];
  var APP_HOSTS = /*APP_HOSTS*/[];

  // Nav buttons to hide, matched by their icon's accessible label (English UI).
  var HIDDEN_NAV_LABELS = ['Reels', 'Explore', 'Search', 'New post', 'Create'];

  function isBlocked(href) {
    var u;
    try { u = new URL(href, location.href); } catch (e) { return false; }
    if (APP_HOSTS.indexOf(u.hostname.toLowerCase()) === -1) return false;
    var p = u.pathname.toLowerCase();
    if (p.charAt(p.length - 1) !== '/') p += '/';
    if (p === '/') return false;
    for (var i = 0; i < ALLOWED_PREFIXES.length; i++) {
      if (p.indexOf(ALLOWED_PREFIXES[i]) === 0) return false;
    }
    return true;
  }

  function notifyBlocked(href) {
    try {
      if (window.NoReelsBridge) window.NoReelsBridge.postMessage(String(href));
    } catch (e) { /* bridge unavailable; the tap is still blocked */ }
  }

  // 1. Swallow taps on links to blocked pages before Instagram's router sees them.
  window.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (a && isBlocked(a.href)) {
      e.preventDefault();
      e.stopImmediatePropagation();
      notifyBlocked(a.href);
    }
  }, true);

  // 2. Static hiding rules.
  var css = [
    'a[href="/explore/"], a[href^="/explore/"], a[href="/reels/"], a[href^="/reels/"],',
    'a[href*="/create/"], [data-noreels-hidden] { display: none !important; }',
    // On the home page only the stories tray should remain.
    'html.noreels-home main article { display: none !important; }'
  ].join('\n');

  function installStyle() {
    if (document.getElementById('noreels-style')) return;
    var root = document.head || document.documentElement;
    if (!root) return;
    var style = document.createElement('style');
    style.id = 'noreels-style';
    style.textContent = css;
    root.appendChild(style);
  }

  function isHome() {
    return location.pathname === '/' || location.pathname === '';
  }

  // Hide the whole feed branch, not just the posts, so the (now invisible) infinite-scroll
  // loader stops pulling in more posts. Walk up from each post to the highest ancestor
  // that doesn't share a parent with the stories tray (story rings are drawn on <canvas>).
  var feedSeenAt = 0;
  function hideFeed() {
    var main = document.querySelector('main');
    if (!main || !main.querySelector('article')) return;
    // Give the stories tray a moment to render first so we don't hide the branch it lands
    // in. (Posts are already hidden by CSS meanwhile.) If it never shows, there are simply
    // no stories right now.
    if (!main.querySelector('canvas')) {
      if (!feedSeenAt) { feedSeenAt = Date.now(); setTimeout(schedule, 3100); }
      if (Date.now() - feedSeenAt < 3000) return;
    }
    var articles = main.querySelectorAll('article:not([data-noreels-seen])');
    for (var i = 0; i < articles.length; i++) {
      var node = articles[i];
      node.setAttribute('data-noreels-seen', '');
      while (node.parentElement && node.parentElement !== main &&
             !node.parentElement.querySelector('canvas')) {
        node = node.parentElement;
      }
      if (!node.querySelector('canvas')) node.setAttribute('data-noreels-hidden', '');
    }
  }

  function hideNavButtons() {
    for (var i = 0; i < HIDDEN_NAV_LABELS.length; i++) {
      var icons = document.querySelectorAll('svg[aria-label="' + HIDDEN_NAV_LABELS[i] + '"]');
      for (var j = 0; j < icons.length; j++) {
        var btn = icons[j].closest('a, [role="link"], [role="button"], button');
        if (btn && !btn.closest('[role="dialog"]')) btn.setAttribute('data-noreels-hidden', '');
      }
    }
  }

  function pauseStrayVideos() {
    var videos = document.querySelectorAll('video');
    for (var i = 0; i < videos.length; i++) {
      if (!videos[i].paused) videos[i].pause();
    }
  }

  function apply() {
    installStyle();
    var home = isHome();
    document.documentElement.classList.toggle('noreels-home', home);
    hideNavButtons();
    if (home) {
      hideFeed();
      pauseStrayVideos();
    }
  }

  var scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    setTimeout(function () { scheduled = false; apply(); }, 100);
  }

  function start() {
    apply();
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
    window.addEventListener('popstate', schedule);
  }

  if (document.documentElement) start();
  else document.addEventListener('DOMContentLoaded', start);
})();
