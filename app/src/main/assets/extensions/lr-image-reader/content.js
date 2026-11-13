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

  var currentBase = null; // set while reading a fetched (parsed) document
  var port = null;
  var retryTimer = null;
  var attempts = 0;
  var MAX_ATTEMPTS = 40;

  // The host registers its message handler lazily (the first time the reader
  // is used), so a connection made before that is dropped. Keep reconnecting
  // with a short backoff until the host picks up.
  function scheduleReconnect() {
    if (retryTimer || attempts >= MAX_ATTEMPTS) return;
    var delay = Math.min(250 * Math.pow(1.5, attempts), 3000);
    retryTimer = setTimeout(function () {
      retryTimer = null;
      connect();
    }, delay);
  }

  function connect() {
    attempts++;
    try {
      var p = browser.runtime.connectNative(NATIVE);
      port = p;
      p.onMessage.addListener(onRequest);
      p.onDisconnect.addListener(function () {
        if (port === p) port = null;
        scheduleReconnect();
      });
    } catch (e) {
      port = null;
      scheduleReconnect();
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
      var a = new URL(u, currentBase || document.baseURI);
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
      out.push({ c: items[k].c, w: items[k].w, h: items[k].h, el: items[k].el });
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

  // ---- Paged readers: one image per page, "Next" button / link ----------

  var NEXT_TEXT = /^\s*(?:next|next page|\u203A|\u00BB|>|>>|\u2192|ถัดไป|หน้าถัดไป|ต่อไป|下一页|下一頁|次へ|次のページ)\s*$/i;
  var CHAPTER_WORD = /chapter|chap\b|episode|\bep\b|ตอน|章|话|話/i;
  var MAX_PAGES = 400;

  function isRealHref(h) {
    return !!h && h.charAt(0) !== "#" && !/^javascript:/i.test(h);
  }

  function findNext(doc) {
    var rel = doc.querySelector('a[rel~="next"], link[rel~="next"]');
    if (rel) return { el: rel, href: rel.getAttribute("href") };
    var cands = doc.querySelectorAll("a, button, [role=button]");
    for (var i = 0; i < cands.length; i++) {
      var c = cands[i];
      var t = (c.textContent || c.getAttribute("aria-label") || c.getAttribute("title") || "").trim();
      var cls = (typeof c.className === "string" ? c.className : "") + " " + (c.id || "");
      if (CHAPTER_WORD.test(t) || CHAPTER_WORD.test(cls)) continue;
      if (NEXT_TEXT.test(t) || /(?:^|[\s_-])next(?:[\s_-]|$)/i.test(cls)) {
        return { el: c, href: c.getAttribute("href") };
      }
    }
    return null;
  }

  function selectorFor(el) {
    try {
      if (el.id) return "#" + CSS.escape(el.id);
      var cls = (typeof el.className === "string" ? el.className : "")
        .trim().split(/\s+/).filter(Boolean).slice(0, 3);
      return el.tagName.toLowerCase() + cls.map(function (c) { return "." + CSS.escape(c); }).join("");
    } catch (e) {
      return "img";
    }
  }

  function digitRuns(u) {
    try {
      var x = new URL(u);
      return (x.pathname + x.search).match(/\d+/g) || [];
    } catch (e) {
      return [];
    }
  }

  /**
   * Only follow links that look like "same chapter, next page": exactly one
   * number grows by 1 (or a first "2" is appended). A jump to the next
   * chapter changes more than that, so we stop there.
   */
  function isSequential(cur, next) {
    var a = digitRuns(cur), b = digitRuns(next);
    if (b.length === a.length + 1) {
      for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
      return b[b.length - 1] === "2";
    }
    if (a.length !== b.length || !a.length) return false;
    var diffs = 0;
    for (var j = 0; j < a.length; j++) {
      if (a[j] !== b[j]) {
        if (parseInt(b[j], 10) - parseInt(a[j], 10) !== 1) return false;
        diffs++;
      }
    }
    return diffs === 1;
  }

  function addUnseen(list, seen, imgs) {
    var added = 0;
    for (var i = 0; i < imgs.length; i++) {
      var cands = fromImg(imgs[i]);
      if (!cands.length || seen[cands[0]]) continue;
      seen[cands[0]] = true;
      list.push({ c: cands, w: 0, h: 0, el: null });
      added++;
    }
    return added;
  }

  async function viaFetch(sel, firstNext, seen, extra, report) {
    var curUrl = location.href;
    var nextUrl = absolute(firstNext.href);
    var visited = Object.create(null);
    visited[curUrl] = true;
    for (var n = 0; n < MAX_PAGES && nextUrl && !visited[nextUrl]; n++) {
      if (!isSequential(curUrl, nextUrl)) break;
      visited[nextUrl] = true;
      var html;
      var ctl = new AbortController();
      var timer = setTimeout(function () { ctl.abort(); }, 10000);
      try {
        var r = await fetch(nextUrl, { credentials: "include", signal: ctl.signal });
        if (!r.ok) break;
        html = await r.text();
      } catch (e) {
        break;
      } finally {
        clearTimeout(timer);
      }
      var doc = new DOMParser().parseFromString(html, "text/html");
      var base = doc.createElement("base");
      base.setAttribute("href", nextUrl);
      doc.head.insertBefore(base, doc.head.firstChild);
      currentBase = nextUrl;
      var added, following = null;
      try {
        added = addUnseen(extra, seen, doc.querySelectorAll(sel));
        var nx = findNext(doc);
        if (nx && isRealHref(nx.href)) following = absolute(nx.href);
      } finally {
        currentBase = null;
      }
      if (!added) break;
      report(extra.length);
      curUrl = nextUrl;
      nextUrl = following;
    }
  }

  function signature(sel) {
    var els = document.querySelectorAll(sel);
    var parts = [];
    for (var i = 0; i < els.length; i++) parts.push(els[i].currentSrc || els[i].src || "");
    return parts.join("|");
  }

  async function viaClick(sel, seen, extra, report) {
    for (var n = 0; n < MAX_PAGES; n++) {
      var nx = findNext(document);
      var btn = nx && nx.el;
      if (!btn || btn.disabled || btn.getAttribute("aria-disabled") === "true") break;
      var before = signature(sel);
      btn.click();
      var changed = false;
      for (var w = 0; w < 25; w++) {
        await sleep(100);
        if (signature(sel) !== before) { changed = true; break; }
      }
      if (!changed) break;
      await sleep(150);
      var added = addUnseen(extra, seen, document.querySelectorAll(sel));
      if (!added) break;
      report(extra.length);
    }
  }

  /** If the page shows ~1 image and has a "next" control, walk the pages. */
  async function paginate(raw, report) {
    if (!raw.length || raw.length > 3) return [];
    var main = raw[0];
    raw.forEach(function (it) { if (it.w * it.h > main.w * main.h) main = it; });
    if (!main.el || main.el.tagName !== "IMG") return [];
    var sel = selectorFor(main.el);
    var next = findNext(document);
    if (!next) return [];
    var seen = Object.create(null);
    raw.forEach(function (it) { seen[it.c[0]] = true; });
    var extra = [];
    if (isRealHref(next.href)) {
      await viaFetch(sel, next, seen, extra, report);
    } else {
      await viaClick(sel, seen, extra, report);
    }
    return extra;
  }

  async function scan(warm, requestId, report) {
    var result = {
      type: "images",
      requestId: requestId || "",
      pageUrl: location.href,
      title: document.title || "",
      userAgent: navigator.userAgent,
      images: []
    };
    try {
      if (warm !== false) await warmLazy();
      var raw = collect();
      var more = [];
      try {
        more = await paginate(raw, report || function () {});
      } catch (e) { /* keep what the first page gave */ }
      result.images = raw.concat(more).map(function (it) {
        return { c: it.c, w: it.w, h: it.h };
      });
    } catch (e) {
      result.error = String(e && e.message ? e.message : e);
    }
    return result;
  }

  async function onRequest(msg) {
    if (!msg || msg.type !== "lrImageReader:collect") return;
    attempts = 0;
    var result = await scan(msg.warm, msg.requestId, function (count) {
      try {
        if (port) port.postMessage({ type: "progress", requestId: msg.requestId || "", count: count });
      } catch (e) { /* ignore */ }
    });
    try {
      if (port) port.postMessage(result);
    } catch (e) { /* port closed; host will time out and retry */ }
  }

  // Hook for the host's off-screen WebView fallback (no extension APIs there).
  window.__lrImageReaderRun = function (warm) { return scan(warm, ""); };

  if (typeof browser !== "undefined" && browser.runtime && browser.runtime.connectNative) {
    connect();
  }
})();
