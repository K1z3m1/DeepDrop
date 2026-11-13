/*
 * LR Image Reader - content script.
 *
 * Idle until the host app asks for a scan over the native port
 * "lrImageReader". On request it:
 *   1. passively discovers image URLs without scrolling the source page,
 *   2. collects <img>, <picture>, CSS background and linked images in
 *      DOM order,
 *   3. for each one builds an ordered list of candidate URLs, best
 *      (original resolution) first, as-found last, plus a separate
 *      as-displayed preview URL when available. The host can show the
 *      preview while loading the best candidate and fall back on error.
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

  function previewFromImg(img, cands) {
    var possible = [absolute(img.currentSrc), absolute(img.getAttribute("src"))];
    LAZY_ATTRS.forEach(function (k) {
      possible.push(absolute(img.getAttribute(k)));
    });
    for (var i = 0; i < possible.length; i++) {
      if (possible[i] && possible[i] !== cands[0]) return possible[i];
    }
    return "";
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
      var preview = previewFromImg(img, cands);
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
      items.push({ el: img, c: cands, p: preview, w: nw, h: nh });
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
      items.push({ el: el, c: cs, p: "", w: 0, h: 0 });
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
      out.push({ c: items[k].c, p: items[k].p, w: items[k].w, h: items[k].h, el: items[k].el });
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
  var MORE_TEXT = /^\s*(?:load more|show more|view more|see more|load next|โหลดเพิ่ม|โหลดเพิ่มเติม|ดูเพิ่มเติม|ดูต่อ|แสดงเพิ่ม|加载更多|載入更多|もっと見る)\s*$/i;
  var CHAPTER_WORD = /chapter|chap\b|episode|\bep\b|ตอน|章|话|話/i;
  var MAX_PAGES = 400;

  function isRealHref(h) {
    return !!h && h.charAt(0) !== "#" && !/^javascript:/i.test(h);
  }

  function isVisible(el) {
    try {
      var r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    } catch (e) {
      return true;
    }
  }

  function findNext(doc) {
    var live = doc === document;
    var rel = doc.querySelector('a[rel~="next"], link[rel~="next"]');
    if (rel && (!live || rel.tagName === "LINK" || isVisible(rel))) {
      return { el: rel, href: rel.getAttribute("href") };
    }
    var cands = doc.querySelectorAll("a, button, [role=button]");
    for (var i = 0; i < cands.length; i++) {
      var c = cands[i];
      var t = (c.textContent || c.getAttribute("aria-label") || c.getAttribute("title") || "").trim();
      var cls = (typeof c.className === "string" ? c.className : "") + " " + (c.id || "");
      if (CHAPTER_WORD.test(t) || CHAPTER_WORD.test(cls)) continue;
      if (NEXT_TEXT.test(t) || MORE_TEXT.test(t) ||
          /(?:^|[\s_-])(?:next|load-?more)(?:[\s_-]|$)/i.test(cls)) {
        if (live && !isVisible(c)) continue;
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
      list.push({ c: cands, p: previewFromImg(imgs[i], cands), w: 0, h: 0, el: null });
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

  /** Whole-page fingerprint, used when a page shows many images at once. */
  function pageSignature() {
    var imgs = document.images;
    var parts = [imgs.length];
    for (var i = 0; i < imgs.length; i++) parts.push(imgs[i].currentSrc || imgs[i].src || "");
    return parts.join("|");
  }

  /** Same de-duplication as scan(), but on the live page via collect(). */
  function addCollected(list, seen) {
    var items = collect();
    var added = 0;
    for (var k = 0; k < items.length; k++) {
      var key = items[k].c[0];
      if (seen[key]) continue;
      seen[key] = true;
      list.push({ c: items[k].c, p: items[k].p, w: items[k].w, h: items[k].h, el: null });
      added++;
    }
    return added;
  }

  function isDisabled(btn) {
    var cls = typeof btn.className === "string" ? btn.className : "";
    return btn.disabled || btn.getAttribute("aria-disabled") === "true" || /(?:^|[\s_-])disabled(?:[\s_-]|$)/i.test(cls);
  }

  /**
   * Click "Next" / "Load more" until nothing new appears. [sel] is the
   * single-image selector for one-image-per-page readers; null means the
   * page shows many images per step (e.g. 12 at a time), so every step is
   * re-collected with collect().
   */
  async function viaClick(sel, seen, extra, report) {
    var startY = window.scrollY;
    var deadline = Date.now() + 150000;
    var lastUrl = location.href;
    for (var n = 0; n < MAX_PAGES && Date.now() < deadline; n++) {
      var nx = findNext(document);
      var btn = nx && nx.el;
      if (!btn || isDisabled(btn)) break;
      var before = sel ? signature(sel) : pageSignature();
      try { btn.scrollIntoView({ block: "center" }); } catch (e) { /* ignore */ }
      btn.click();
      var changed = false;
      for (var w = 0; w < 40; w++) {
        await sleep(100);
        if ((sel ? signature(sel) : pageSignature()) !== before) { changed = true; break; }
      }
      if (!changed) break;
      if (location.href !== lastUrl) {
        // SPA moved to another URL: only keep going within the same chapter.
        if (!isSequential(lastUrl, location.href)) break;
        lastUrl = location.href;
      }
      await sleep(300);
      if (!sel) {
        // Nudge lazy loaders for the new batch.
        window.scrollTo(0, Math.max(document.documentElement.scrollHeight, 0));
        await sleep(300);
      }
      var added = sel ? addUnseen(extra, seen, document.querySelectorAll(sel)) : addCollected(extra, seen);
      if (!added) {
        await sleep(800); // slow lazy image; look once more
        added = sel ? addUnseen(extra, seen, document.querySelectorAll(sel)) : addCollected(extra, seen);
      }
      if (!added) break;
      report(extra.length);
    }
    if (!sel) window.scrollTo(0, startY);
  }

  /** Selector for the content images when a page shows several at once. */
  function pageSelector(main, count) {
    var up = main.el.parentElement;
    for (var d = 0; up && d < 3; d++, up = up.parentElement) {
      if (up === document.body) break;
      var cls = typeof up.className === "string" ? up.className.trim() : "";
      if (!up.id && !cls) continue;
      var s = selectorFor(up) + " img";
      try {
        if (document.querySelectorAll(s).length >= Math.min(count, 2)) return s;
      } catch (e) { /* bad selector */ }
    }
    return selectorFor(main.el);
  }

  /**
   * Walk "Next" / "Load more" controls. Works for one-image-per-page readers
   * and for pages that show a batch of images (about a dozen) per step.
   */
  async function paginate(raw, report) {
    if (!raw.length) return [];
    var main = raw[0];
    raw.forEach(function (it) { if (it.w * it.h > main.w * main.h) main = it; });
    var next = findNext(document);
    if (!next) return [];
    var many = raw.length > 3;
    var seen = Object.create(null);
    raw.forEach(function (it) { seen[it.c[0]] = true; });
    var extra = [];
    if (isRealHref(next.href)) {
      // Plain links: fetch the following pages. (Skipped for JS-less guesses
      // on multi-image pages unless the URL looks sequential - viaFetch checks.)
      if (!main.el || main.el.tagName !== "IMG") return [];
      await viaFetch(many ? pageSelector(main, raw.length) : selectorFor(main.el), next, seen, extra, report);
    } else {
      // JS buttons: click them on the live page.
      var sel = (!many && main.el && main.el.tagName === "IMG") ? selectorFor(main.el) : null;
      await viaClick(sel, seen, extra, report);
    }
    return extra;
  }

  // ---- Thumbnail -> full image via the detail page ----------------------
  // Some sites show low-quality thumbnails and only reveal the real image
  // on the page you reach by tapping the thumbnail. Follow those links
  // (same site only), find the main image there and put it first.

  var THUMB_HINT = /(?:thumb|small|preview|mini|_s\.|-s\.|_t\.|-t\.|_sm\.|-sm\.|\/s\d+\/|\/t\/)/i;
  var FULLISH_HINT = /(?:full|large|main|viewer|zoom|photo|display|original|lightbox|view-?img|picture)/i;
  var JUNK_HINT = /(?:logo|avatar|icon|banner|sprite|thumb|related|recommend|sidebar|advert|\bads?\b)/i;
  var DETAIL_CONCURRENCY = 4;

  function sameOrigin(u) {
    try { return new URL(u, document.baseURI).origin === location.origin; } catch (e) { return false; }
  }

  function stripHash(u) {
    var i = u.indexOf("#");
    return i < 0 ? u : u.slice(0, i);
  }

  /** The detail-page link wrapped around this thumbnail, or "". */
  function detailLink(it) {
    var el = it.el;
    if (!el || el.tagName !== "IMG" || !el.closest) return "";
    var a = el.closest("a[href]");
    if (!a) return "";
    var href = a.getAttribute("href") || "";
    if (!isRealHref(href)) return "";
    var abs = absolute(href);
    if (!abs || IMG_EXT.test(abs)) return "";          // already a direct image link
    if (!sameOrigin(abs)) return "";
    if (stripHash(abs) === stripHash(location.href)) return "";
    if (a.querySelectorAll("img").length !== 1) return "";
    if (a.closest("nav, footer, header, [role=navigation], [role=banner]")) return "";
    var big = Math.max(it.w, it.h);
    var thumbish = big === 0 || big < 900 || THUMB_HINT.test(it.c[it.c.length - 1]);
    return thumbish ? abs : "";
  }

  function extractFull(doc, baseUrl, thumbs) {
    var skip = Object.create(null);
    thumbs.forEach(function (t) { skip[t] = true; });
    var best = null, bestScore = 0;
    currentBase = baseUrl;
    var og = [];
    try {
      var imgs = doc.querySelectorAll("img");
      for (var i = 0; i < imgs.length; i++) {
        var img = imgs[i];
        var cands = fromImg(img);
        if (!cands.length) continue;
        if (skip[cands[0]] || /\.svg(?:$|[?#])/i.test(cands[cands.length - 1])) continue;
        var wa = parseInt(img.getAttribute("width"), 10) || 0;
        var ha = parseInt(img.getAttribute("height"), 10) || 0;
        var label = (img.className || "") + " " + (img.id || "");
        var par = img.parentElement;
        for (var d = 0; par && d < 2; d++, par = par.parentElement) {
          label += " " + (typeof par.className === "string" ? par.className : "") + " " + (par.id || "");
        }
        var score = 1 + Math.min(wa * ha, 4000000) / 10000;
        if (img.closest("main, article, figure, [role=main], #content, .content")) score += 50;
        if (FULLISH_HINT.test(label)) score += 100;
        if (FULL_ATTRS.some(function (k) { return img.hasAttribute(k); })) score += 200;
        if (img.closest("nav, header, footer, aside, [role=navigation], [role=banner]")) score -= 200;
        if (JUNK_HINT.test(label) || JUNK_HINT.test(cands[0])) score -= 150;
        score -= i * 0.5; // earlier in the page wins ties
        if (score > bestScore) { bestScore = score; best = cands; }
      }
      var metas = doc.querySelectorAll(
        'meta[property="og:image"], meta[property="og:image:url"], meta[name="twitter:image"], link[rel="image_src"]');
      for (var m = 0; m < metas.length; m++) {
        var v = absolute(metas[m].getAttribute("content") || metas[m].getAttribute("href"));
        if (v && !skip[v] && !/\.svg(?:$|[?#])/i.test(v)) og.push(v);
      }
    } finally {
      currentBase = null;
    }
    var out = [];
    if (best && bestScore >= 100) out = best.concat(og);
    else out = og.concat(best || []);
    return uniq(out).slice(0, 4);
  }

  function probeSize(url) {
    return new Promise(function (resolve) {
      if (/^data:/i.test(url)) { resolve({ ok: true, w: 9999, h: 9999 }); return; }
      var im = new Image();
      var done = false;
      var t = setTimeout(function () { fin({ ok: false }); }, 6000);
      function fin(r) { if (done) return; done = true; clearTimeout(t); im.onload = im.onerror = null; resolve(r); }
      im.onload = function () { fin({ ok: true, w: im.naturalWidth, h: im.naturalHeight }); };
      im.onerror = function () { fin({ ok: false }); };
      im.src = url;
    });
  }

  async function fetchDetail(href) {
    var ctl = new AbortController();
    var timer = setTimeout(function () { ctl.abort(); }, 10000);
    try {
      var r = await fetch(href, { credentials: "include", signal: ctl.signal });
      if (!r.ok) return null;
      var html = await r.text();
      return new DOMParser().parseFromString(html, "text/html");
    } catch (e) {
      return null;
    } finally {
      clearTimeout(timer);
    }
  }

  async function resolveDetail(list, report, scanStartedAt) {
    if (Date.now() - scanStartedAt > 100000) return list;
    var jobs = [];
    for (var i = 0; i < list.length && jobs.length < DETAIL_MAX; i++) {
      var href = detailLink(list[i]);
      if (href) jobs.push({ it: list[i], href: href });
    }
    // A single such link is probably a normal "related" link, not a gallery.
    if (jobs.length < 2) return list;

    var deadline = Date.now() + DETAIL_BUDGET_MS;
    var cache = Object.create(null);   // href -> Promise<candidate urls>
    var next = 0, finished = 0;

    async function worker() {
      while (next < jobs.length && Date.now() < deadline) {
        var job = jobs[next++];
        try { report(finished); } catch (e) { /* ignore */ }
        if (!cache[job.href]) {
          cache[job.href] = (async function (h, thumbs) {
            var doc = await fetchDetail(h);
            return doc ? extractFull(doc, h, thumbs) : [];
          })(job.href, job.it.c);
        }
        var found = await cache[job.href];
        var thumbBig = Math.max(job.it.w, job.it.h);
        var need = Math.max(thumbBig * 1.25, 500);
        var accepted = "", unverified = "";
        for (var f = 0; f < found.length && f < 2 && !accepted; f++) {
          var pr = await probeSize(found[f]);
          if (pr.ok && Math.max(pr.w, pr.h) >= need) accepted = found[f];
          else if (!pr.ok && !unverified) unverified = found[f]; // hotlink-protected? let the host try
        }
        if (accepted) {
          job.it.c = uniq([accepted].concat(job.it.c));
        } else if (unverified) {
          var c = job.it.c.slice();
          c.splice(Math.max(0, c.length - 1), 0, unverified);
          job.it.c = uniq(c);
        }
        finished++;
        try { report(finished); } catch (e) { /* ignore */ }
      }
    }

    var pool = [];
    for (var p = 0; p < DETAIL_CONCURRENCY; p++) pool.push(worker());
    await Promise.all(pool);

    // Different thumbnails may resolve to the same full image.
    var seen = Object.create(null), out = [];
    for (var k = 0; k < list.length; k++) {
      var key = list[k].c[0];
      if (seen[key]) continue;
      seen[key] = true;
      out.push(list[k]);
    }
    return out;
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
      // Keep discovery passive: do not scroll the source page to force every
      // lazy image to download. The native reader's LazyColumn/Coil requests
      // image bytes only as each item approaches the viewport.
      var rep = report || function () {};
      var raw = collect();
      var more = [];
      try {
        more = await paginate(raw, rep);
      } catch (e) { /* keep what the first page gave */ }
      var all = raw.concat(more);
      // Do not fetch every thumbnail's detail page up front. This fan-out
      // eagerly downloads metadata for hundreds of pages before the reader
      // opens. Keep candidate URLs and let the native reader load visible
      // images on demand.
      result.images = all.map(function (it) {
        return { c: it.c, p: it.p || "", w: it.w, h: it.h };
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
