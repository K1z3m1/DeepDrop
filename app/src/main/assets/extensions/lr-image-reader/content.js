/*
 * LR Image Reader - content script.
 *
 * Idle until the host app asks for a scan over the native port
 * "lrImageReader". On request it:
 *   1. (optionally) scrolls the page once so lazy-loaders fire,
 *   2. collects <img>, <picture>, CSS background and linked images in
 *      DOM order,
 *   3. for each one builds an ordered list of candidate URLs, best
 *      (original resolution) first, as-found last. The host tries
 *      them in order and falls back on error, so an over-eager URL
 *      rewrite can never lose an image.
 */
(function () {
  "use strict";

  if (window.__lrImageReaderInstalled) return;
  window.__lrImageReaderInstalled = true;

  var NATIVE = "lrImageReader";
  var IMG_EXT = /\.(?:jpe?g|png|gif|webp|avif|bmp|jfif)(?:$|[?#])/i;
  var FULL_ATTRS = [
    "data-original", "data-orig-file", "data-full", "data-full-url",
    "data-large-file", "data-zoom-src", "data-highres", "data-hi-res",
    "data-original-src", "data-big"
  ];
  var LAZY_ATTRS = [
    "data-src", "data-lazy-src", "data-lazy", "data-url", "data-echo",
    "data-img", "data-actual-src", "data-ll-src", "data-image", "data-pagespeed-lazy-src"
  ];
  var LAZY_SRCSET_ATTRS = ["data-srcset", "data-lazy-srcset"];
  var SIGNED = /(?:^|[?&])(?:sig|signature|token|expires|x-amz-[a-z-]+|hmac|policy|key-pair-id|se|sp|sv)=/i;
  var DROP_PARAMS = ["w", "h", "width", "height", "maxwidth", "maxheight",
    "resize", "fit", "crop", "size", "thumb", "thumbnail"];
  var MAX_ITEMS = 1500;
  var MAX_BG_SCAN = 4000;

  var port = null;

  function connect() {
    try {
      port = browser.runtime.connectNative(NATIVE);
      port.onMessage.addListener(onRequest);
      port.onDisconnect.addListener(function () { port = null; });
    } catch (e) {
      port = null;
    }
  }

  function absolute(u) {
    if (!u || typeof u !== "string") return "";
    u = u.trim();
    if (!u) return "";
    if (/^data:image\//i.test(u)) {
      // Skip inline SVG and tiny placeholders.
      if (/^data:image\/svg/i.test(u) || u.length < 4000) return "";
      return u;
    }
    try {
      var a = new URL(u, document.baseURI);
      if (a.protocol !== "http:" && a.protocol !== "https:") return "";
      return a.href;
    } catch (e) {
      return "";
    }
  }

  function parseSrcset(s) {
    var best = "";
    var bestWeight = -1;
    if (!s || typeof s !== "string") return best;
    var parts = s.trim().split(/,\s+/);
    for (var i = 0; i < parts.length; i++) {
      var tokens = parts[i].trim().split(/\s+/);
      var url = tokens[0];
      if (!url) continue;
      var weight = 1;
      if (tokens[1]) {
        var m = /^([\d.]+)([wx])$/i.exec(tokens[1]);
        if (m) weight = parseFloat(m[1]) * (m[2].toLowerCase() === "x" ? 1000 : 1);
      }
      if (weight >= bestWeight) { bestWeight = weight; best = url; }
    }
    return best;
  }

  /** Well-known "thumbnail -> original" rewrites. Returns variants. */
  function upgrades(url) {
    var out = [];
    if (/^data:/i.test(url)) return out;
    var u;
    try { u = new URL(url); } catch (e) { return out; }
    var host = u.hostname;

    // Wikimedia: /thumb/a/ab/Name.jpg/320px-Name.jpg -> /a/ab/Name.jpg
    if (/wikimedia\.org$|wikipedia\.org$/.test(host)) {
      var w = /^(.*\/)thumb\/([0-9a-f]\/[0-9a-f]{2}\/[^\/]+)\/[^\/]+$/.exec(u.pathname);
      if (w) {
        var wu = new URL(u.href);
        wu.pathname = w[1] + w[2];
        wu.search = "";
        out.push(wu.href);
      }
    }

    // WordPress size suffix: name-300x200.jpg -> name.jpg
    var wp = u.pathname.replace(/-\d{2,5}x\d{2,5}(\.(?:jpe?g|png|gif|webp|avif))$/i, "$1");
    if (wp !== u.pathname) {
      var wpu = new URL(u.href);
      wpu.pathname = wp;
      out.push(wpu.href);
    }
    // WordPress "-scaled" big-image copy -> original upload
    var sc = u.pathname.replace(/-scaled(\.(?:jpe?g|png|gif|webp|avif))$/i, "$1");
    if (sc !== u.pathname) {
      var scu = new URL(u.href);
      scu.pathname = sc;
      out.push(scu.href);
    }

    // Google / Blogger style size tokens.
    if (/googleusercontent\.com$|ggpht\.com$|blogspot\.com$|bp\.blogspot\.com$/.test(host)) {
      var g = url.replace(/=(?:s\d+|w\d+(?:-h\d+)?)(?:-[a-z0-9-]+)?$/i, "=s0");
      if (g !== url) out.push(g);
      var gp = url.replace(/\/(?:s\d+|w\d+-h\d+)(?:-[a-z0-9]+)?\//i, "/s0/");
      if (gp !== url) out.push(gp);
    }

    // Pinterest: /236x/ -> /originals/
    if (/pinimg\.com$/.test(host)) {
      var p = url.replace(/\/\d+x(?:\d+)?\//, "/originals/");
      if (p !== url) out.push(p);
    }

    // Generic "resize me" query parameters (never touch signed URLs).
    if (u.search && !SIGNED.test(u.search)) {
      var q = new URL(u.href);
      var removed = false;
      DROP_PARAMS.forEach(function (k) {
        if (q.searchParams.has(k)) { q.searchParams.delete(k); removed = true; }
      });
      if (removed) out.push(q.href);
    }
    return out;
  }

  function uniq(list) {
    var seen = Object.create(null);
    var res = [];
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      if (v && !seen[v]) { seen[v] = true; res.push(v); }
    }
    return res;
  }

  function fromImg(img) {
    var full = [];
    var bases = [];

    // Linked full-size image (<a href="big.jpg"><img src="thumb.jpg"></a>)
    var a = img.closest && img.closest("a[href]");
    if (a) {
      var href = a.getAttribute("href") || "";
      var abs = absolute(href);
      if (abs && IMG_EXT.test(abs)) full.push(abs);
    }
    FULL_ATTRS.forEach(function (k) {
      var v = absolute(img.getAttribute(k));
      if (v) full.push(v);
    });

    // Largest srcset candidate (own + <picture><source>).
    var srcsetBest = "";
    var sets = [img.getAttribute("srcset")];
    LAZY_SRCSET_ATTRS.forEach(function (k) { sets.push(img.getAttribute(k)); });
    var pic = img.parentElement && img.parentElement.tagName === "PICTURE" ? img.parentElement : null;
    if (pic) {
      var sources = pic.querySelectorAll("source");
      for (var i = 0; i < sources.length; i++) {
        sets.push(sources[i].getAttribute("srcset"));
        sets.push(sources[i].getAttribute("data-srcset"));
      }
    }
    for (var s = 0; s < sets.length; s++) {
      var b = absolute(parseSrcset(sets[s]));
      if (b) { srcsetBest = b; break; }
    }
    if (srcsetBest) bases.push(srcsetBest);
    LAZY_ATTRS.forEach(function (k) {
      var v = absolute(img.getAttribute(k));
      if (v) bases.push(v);
    });
    var cur = absolute(img.currentSrc);
    if (cur) bases.push(cur);
    var src = absolute(img.getAttribute("src"));
    if (src) bases.push(src);

    var cands = full.slice();
    bases.forEach(function (bse) {
      upgrades(bse).forEach(function (u) { cands.push(u); });
    });
    cands = cands.concat(bases);
    return uniq(cands);
  }

  function skipByContext(el, w, h) {
    // Chrome/branding images in nav/header/footer are almost never content.
    var small = Math.max(w, h) < 300;
    if (small && el.closest && el.closest("nav, footer, header, [role=navigation], [role=banner]")) {
      return true;
    }
    return false;
  }

  function collect() {
    var items = [];

    var imgs = document.images;
    for (var i = 0; i < imgs.length && items.length < MAX_ITEMS; i++) {
      var img = imgs[i];
      var cands = fromImg(img);
      if (!cands.length) continue;
      var nw = img.complete ? img.naturalWidth : 0;
      var nh = img.complete ? img.naturalHeight : 0;
      var r = img.getBoundingClientRect();
      var rw = Math.round(r.width);
      var rh = Math.round(r.height);
      // Tracking pixels / icons.
      if (nw > 0 && Math.max(nw, nh) < 100) continue;
      if (nw === 0 && rw > 0 && rh > 0 && rw < 100 && rh < 100) continue;
      if (/\.svg(?:$|[?#])/i.test(cands[cands.length - 1])) continue;
      if (skipByContext(img, nw || rw, nh || rh)) continue;
      items.push({ el: img, c: cands, w: nw, h: nh });
    }

    // CSS backgrounds (hero images, some readers render pages this way).
    var nodes = document.querySelectorAll("div, section, a, span, figure, li, article");
    var limit = Math.min(nodes.length, MAX_BG_SCAN);
    for (var n = 0; n < limit && items.length < MAX_ITEMS; n++) {
      var el = nodes[n];
      var bg;
      try { bg = getComputedStyle(el).backgroundImage; } catch (e) { continue; }
      if (!bg || bg === "none") continue;
      var m = /url\((['"]?)(.*?)\1\)/.exec(bg);
      if (!m) continue;
      var rect = el.getBoundingClientRect();
      if (rect.width < 200 || rect.height < 150) continue;
      var u = absolute(m[2]);
      if (!u || /\.svg(?:$|[?#])/i.test(u)) continue;
      if (skipByContext(el, rect.width, rect.height)) continue;
      var cs = uniq(upgrades(u).concat([u]));
      items.push({ el: el, c: cs, w: 0, h: 0 });
    }

    // DOM order, then de-duplicate on the best candidate.
    items.sort(function (x, y) {
      if (x.el === y.el) return 0;
      var pos = x.el.compareDocumentPosition(y.el);
      if (pos & Node.DOCUMENT_POSITION_FOLLOWING) return -1;
      if (pos & Node.DOCUMENT_POSITION_PRECEDING) return 1;
      return 0;
    });
    var seen = Object.create(null);
    var out = [];
    for (var k = 0; k < items.length; k++) {
      var key = items[k].c[0];
      if (seen[key]) continue;
      seen[key] = true;
      out.push({ c: items[k].c, w: items[k].w, h: items[k].h });
    }
    return out;
  }

  function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  /** Scroll through the page once so IntersectionObserver lazy-loaders fire. */
  async function warmLazy() {
    var pending = 0;
    var imgs = document.images;
    for (var i = 0; i < imgs.length; i++) {
      var im = imgs[i];
      if (!im.complete || (!im.currentSrc && !im.getAttribute("src"))) pending++;
    }
    if (pending < 3) return;

    var startY = window.scrollY;
    var step = Math.max(400, Math.floor(window.innerHeight * 0.9));
    var deadline = Date.now() + 3500;
    var y = 0;
    var total = Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
    while (y < total && Date.now() < deadline) {
      window.scrollTo(0, y);
      await sleep(70);
      y += step;
      total = Math.max(document.documentElement.scrollHeight, total);
    }
    window.scrollTo(0, startY);
    await sleep(250);
  }

  async function onRequest(msg) {
    if (!msg || msg.type !== "lrImageReader:collect") return;
    var result = {
      type: "images",
      requestId: msg.requestId || "",
      pageUrl: location.href,
      title: document.title || "",
      userAgent: navigator.userAgent,
      images: []
    };
    try {
      if (msg.warm !== false) await warmLazy();
      result.images = collect();
    } catch (e) {
      result.error = String(e && e.message ? e.message : e);
    }
    try {
      if (port) port.postMessage(result);
    } catch (e) { /* port closed; host will time out and retry */ }
  }

  connect();
})();
