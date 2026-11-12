package com.cookiegames.smartcookie.adblock

import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-performance ad defuser inspired by uBlock Origin's scriptlets and cosmetic filters.
 * Neutralizes video ad placements (YouTube) and cosmetic ad containers with zero latency.
 */
@Singleton
class UBlockAdDefuser @Inject constructor() {

    /**
     * Universal cosmetic stylesheet that hides ad banners, sticky overlays, and auto-placed ad units.
     * Evaluated natively by the browser's CSS rendering engine with GPU acceleration.
     */
    val cosmeticAdFilterCss: String = """
        #cts_test,
        #ad_ctd,
        #s_test_ads,
        #s_test_pagead,
        ins.adsbygoogle,
        .banner-ad,
        .ad-banner,
        .ad-container,
        .ads-wrapper,
        .ad-slot,
        .ad_slot,
        [id*="taboola-"],
        [id*="outbrain-"],
        [class*="outbrain_"],
        .rc-ad,
        .sponsored-post,
        .ad-header,
        .ad-footer,
        .ad-sidebar,
        #yandex_rtb_R-A-491776-1,
        [id*="yandex_rtb_"],
        [class*="yandex-rtb"],
        .includeWrapper,
        ytd-ad-slot-renderer,
        ytd-banner-promo-renderer,
        ytd-promoted-sparkles-web-renderer,
        ytd-compact-promoted-video-renderer,
        ytm-promoted-sparkles-web-renderer,
        #player-ads,
        #masthead-ad,
        .ytp-ad-module,
        .ytp-ad-overlay-container,
        .ytp-ad-message-container,
        .video-ads {
            display: none !important;
            height: 0 !important;
            min-height: 0 !important;
            visibility: hidden !important;
            pointer-events: none !important;
        }
    """.trimIndent().replace("\n", " ").replace("\r", "")

    /**
     * JavaScript snippet that injects the cosmetic stylesheet into the DOM as early as possible.
     */
    val cosmeticInjectionJs: String
        get() = """
        (function() {
            var cssId = 'yload-ublock-cosmetic';
            if (document.getElementById(cssId)) return;
            var style = document.createElement('style');
            style.id = cssId;
            style.type = 'text/css';
            style.textContent = '$cosmeticAdFilterCss';
            var target = document.head || document.documentElement;
            if (target) {
                target.appendChild(style);
            }
        })();
    """.trimIndent()

    /**
     * Scriptlet that intercepts fetch & XHR to immediately reject ad and tracking requests.
     */
    val generalAdDefuserJs: String = """
        (function() {
            if (window.__yload_ad_defuser__) return;
            window.__yload_ad_defuser__ = true;

            // Never interfere with Google search engine or YouTube internal player/hydration fetches
            var host = window.location.hostname || '';
            if (host.indexOf('google.') !== -1 || host.indexOf('youtube.') !== -1 || host.indexOf('youtu.be') !== -1) return;

            var adPattern = /(?:doubleclick\.net|googleads|adservice\.google|googlesyndication|google-analytics\.com|criteo\.com|taboola\.com|outbrain\.com|adnxs\.com|pubmatic\.com|rubiconproject\.com|openx\.net|scorecardresearch\.com|chartbeat\.com|hotjar\.com|clarity\.ms|sentry\.io|bugsnag\.com|luckyorange\.com|mouseflow\.com|fakepage\.html|\/pagead\/)/i;

            if (window.fetch) {
                var _fetch = window.fetch;
                window.fetch = function(input, init) {
                    var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                    if (url && adPattern.test(url)) {
                        return Promise.resolve(new Response('{}', {
                            status: 200,
                            statusText: 'OK',
                            headers: { 'Content-Type': 'application/json' }
                        }));
                    }
                    return _fetch.apply(this, arguments);
                };
            }

            if (window.XMLHttpRequest) {
                var _open = XMLHttpRequest.prototype.open;
                var _send = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__blocked = (typeof url === 'string') && adPattern.test(url);
                    return _open.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    if (this.__blocked) {
                        var self = this;
                        setTimeout(function() {
                            try {
                                Object.defineProperty(self, 'readyState', { value: 4, writable: false });
                                Object.defineProperty(self, 'status', { value: 200, writable: false });
                                Object.defineProperty(self, 'statusText', { value: 'OK', writable: false });
                                Object.defineProperty(self, 'responseText', { value: '{}', writable: false });
                                Object.defineProperty(self, 'response', { value: '{}', writable: false });
                            } catch(e) {}
                            try {
                                self.dispatchEvent(new Event('readystatechange'));
                                self.dispatchEvent(new Event('load'));
                                self.dispatchEvent(new Event('loadend'));
                            } catch(e) {}
                            if (typeof self.onreadystatechange === 'function') {
                                try { self.onreadystatechange(); } catch(e) {}
                            }
                            if (typeof self.onload === 'function') {
                                try { self.onload(); } catch(e) {}
                            }
                            if (typeof self.onloadend === 'function') {
                                try { self.onloadend(); } catch(e) {}
                            }
                        }, 1);
                        return;
                    }
                    return _send.apply(this, arguments);
                };
            }
        })();
    """.trimIndent()

    /**
     * uBlock Origin-style scriptlet defuser for YouTube.
     * 1. Prunes ad placements from ytInitialPlayerResponse.
     * 2. Intercepts fetch & XHR requests to /youtubei/v1/player.
     * 3. Fallback: Fast-forwards and auto-skips any video ads.
     */
    val youtubeAdDefuserJs: String = """
        (function() {
            if (window.__yload_ublock_yt_defuser__) return;
            window.__yload_ublock_yt_defuser__ = true;

            function pruneAds(obj) {
                if (!obj || typeof obj !== 'object') return obj;
                try {
                    if (obj.adPlacements) obj.adPlacements = [];
                    if (obj.playerAds) obj.playerAds = [];
                    if (obj.adSlots) obj.adSlots = [];
                    delete obj.adBreakHeartbeatParams;
                    if (obj.playerConfig && obj.playerConfig.adConfig) {
                        delete obj.playerConfig.adConfig;
                    }
                } catch(e) {}
                return obj;
            }

            // 1. Trap ytInitialPlayerResponse
            var _ytPlayerResp = window.ytInitialPlayerResponse;
            Object.defineProperty(window, 'ytInitialPlayerResponse', {
                get: function() { return _ytPlayerResp; },
                set: function(val) {
                    _ytPlayerResp = pruneAds(val);
                },
                configurable: true,
                enumerable: true
            });
            if (_ytPlayerResp) pruneAds(_ytPlayerResp);

            // 2. Intercept Fetch API for /youtubei/v1/player
            if (window.fetch) {
                var originalFetch = window.fetch;
                window.fetch = function() {
                    var args = arguments;
                    var url = args[0] ? (args[0].url || args[0].toString()) : '';
                    if (typeof url === 'string' && url.indexOf('/youtubei/v1/player') !== -1) {
                        return originalFetch.apply(this, args).then(function(response) {
                            return response.clone().json().then(function(data) {
                                var pruned = pruneAds(data);
                                var newHeaders = new Headers(response.headers);
                                newHeaders.delete('content-encoding');
                                newHeaders.delete('content-length');
                                newHeaders.set('content-type', 'application/json; charset=UTF-8');
                                return new Response(JSON.stringify(pruned), {
                                    status: response.status,
                                    statusText: response.statusText,
                                    headers: newHeaders
                                });
                            }).catch(function() {
                                return response;
                            });
                        });
                    }
                    return originalFetch.apply(this, args);
                };
            }

            // 3. Intercept XMLHttpRequest for /youtubei/v1/player
            if (window.XMLHttpRequest) {
                var originalXhrOpen = XMLHttpRequest.prototype.open;
                var originalXhrSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__url = url;
                    return originalXhrOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    if (this.__url && typeof this.__url === 'string' && this.__url.indexOf('/youtubei/v1/player') !== -1) {
                        this.addEventListener('readystatechange', function() {
                            if (this.readyState === 4) {
                                try {
                                    var text = '';
                                    if (this.responseType === '' || this.responseType === 'text') {
                                        text = this.responseText;
                                    }
                                    var data = null;
                                    if (text) {
                                        data = JSON.parse(text);
                                    } else if (this.responseType === 'json' && this.response) {
                                        data = this.response;
                                    }
                                    if (data) {
                                        pruneAds(data);
                                        var jsonStr = JSON.stringify(data);
                                        try {
                                            Object.defineProperty(this, 'responseText', { value: jsonStr, writable: false });
                                        } catch(e) {}
                                        try {
                                            Object.defineProperty(this, 'response', {
                                                value: (this.responseType === 'json' ? data : jsonStr),
                                                writable: false
                                            });
                                        } catch(e) {}
                                    }
                                } catch(e) {}
                            }
                        });
                    }
                    return originalXhrSend.apply(this, arguments);
                };
            }

            // 4. Auto-skip video ads safely without seeking main video or leaving playback rate stuck
            var adFastForwarded = false;
            var wasMuted = false;
            function autoSkip() {
                var player = document.querySelector('.html5-video-player') || document.querySelector('#player') || document.querySelector('.player-container');
                var isAdActive = player && player.classList.contains('ad-showing');
                var video = (player && player.querySelector('video')) || document.querySelector('video');

                if (video) {
                    if (isAdActive) {
                        if (!adFastForwarded) {
                            adFastForwarded = true;
                            wasMuted = video.muted;
                        }
                        try {
                            video.muted = true;
                            video.playbackRate = 16.0;
                        } catch(e) {}
                    } else if (adFastForwarded || video.playbackRate > 1.0) {
                        adFastForwarded = false;
                        try {
                            video.playbackRate = 1.0;
                            video.muted = wasMuted;
                        } catch(e) {}
                    }
                }

                var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-slot button, .videoAdUiSkipButton, .ytm-ad-skip-button, .ytm-ad-skip-button-modern, button.ytp-ad-skip-button, .ytp-ad-skip-button-container button, .ytp-ad-skip-button-text, [class*="ad-skip-button"], button.ytm-skip-ad-button, [aria-label*="Skip ad" i], [aria-label*="Saltar" i], .ytp-ad-overlay-close-button');
                if (skipBtn) {
                    try { skipBtn.click(); } catch(e) {}
                }
            }
            setInterval(autoSkip, 300);
        })();
    """.trimIndent()
}
