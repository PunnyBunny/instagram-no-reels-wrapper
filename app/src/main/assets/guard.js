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

  // The "<" arrow at the top of a DM thread doesn't work inside the WebView, so it's handled
  // natively instead: back to the inbox, like the phone's back button.
  function isDmBackButton(t) {
    if (location.pathname.indexOf('/direct/') !== 0 || location.pathname === '/direct/inbox/') return false;
    if (t.closest('svg[aria-label="Back"]')) return true;
    var btn = t.closest('a, button, [role="button"], [role="link"]');
    return !!(btn && btn.querySelector('svg[aria-label="Back"]'));
  }

  function backToInbox() {
    try {
      if (window.NoReelsBridge) { window.NoReelsBridge.postMessage('noreels:back-to-inbox'); return; }
    } catch (e) { /* fall through */ }
    location.assign('/direct/inbox/');
  }

  // 1. Swallow taps on links to blocked pages before Instagram's router sees them.
  window.addEventListener('click', function (e) {
    var t = e.target && e.target.closest ? e.target : null;
    if (!t) return;
    if (isDmBackButton(t)) {
      e.preventDefault();
      e.stopImmediatePropagation();
      backToInbox();
      return;
    }
    var a = t.closest('a[href]');
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
    'html.noreels-home main article, html.noreels-home main [role="progressbar"],',
    'html.noreels-home main svg[aria-label^="Loading"] { display: none !important; }'
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

  var TRAY_MARKERS = 'a[href^="/stories/"], canvas, [aria-label*="Story"], [aria-label*="story"]';
  // Anything containing one of these is feed, not stories tray.
  var FEED_MARKERS = 'article, video, a[href^="/p/"], a[href^="/reel/"], a[href^="/reels/"], ' +
    '[role="progressbar"], svg[aria-label^="Loading"]';

  // The stories tray: the highest ancestor of the first story marker (below <main>) that
  // holds no feed content, so it spans every story in the row but nothing else.
  function findTray(main) {
    var marker = main.querySelector(TRAY_MARKERS);
    if (!marker || marker.closest(FEED_MARKERS)) return null;
    var tray = marker;
    while (tray.parentElement && tray.parentElement !== main &&
           !tray.parentElement.querySelector(FEED_MARKERS)) {
      tray = tray.parentElement;
    }
    return tray;
  }

  // On the home page keep only the stories tray: hide everything that comes after it at
  // every level up to <main> - posts, suggested posts/reels, and the infinite-scroll loader.
  // With the loader hidden Instagram stops paginating, so the spinner stops too.
  var noTraySince = 0;
  function hideFeed() {
    var main = document.querySelector('main');
    if (!main) return;
    var tray = findTray(main);
    if (tray) {
      noTraySince = 0;
      for (var node = tray; node && node !== main; node = node.parentElement) {
        node.removeAttribute('data-noreels-hidden'); // in case the no-tray fallback hid it
        for (var sib = node.nextElementSibling; sib; sib = sib.nextElementSibling) {
          sib.setAttribute('data-noreels-hidden', '');
        }
      }
      return;
    }
    // No tray (yet). Posts and loaders are hidden by CSS meanwhile; if it still hasn't
    // appeared after a few seconds there are no stories, so hide each post's whole branch.
    if (!noTraySince) { noTraySince = Date.now(); setTimeout(schedule, 3100); }
    if (Date.now() - noTraySince < 3000) return;
    var articles = main.querySelectorAll('article');
    for (var i = 0; i < articles.length; i++) {
      var branch = articles[i];
      while (branch.parentElement && branch.parentElement !== main) branch = branch.parentElement;
      branch.setAttribute('data-noreels-hidden', '');
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
    setTimeout(function () { scheduled = false; apply(); }, 50);
  }

  function start() {
    apply();
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
    window.addEventListener('popstate', schedule);
  }

  if (document.documentElement) start();
  else document.addEventListener('DOMContentLoaded', start);
})();
