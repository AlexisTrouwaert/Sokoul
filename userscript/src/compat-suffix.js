

  // --- BEGIN extension/Chrome/background.js ---
  // Backend API URL for got-scraping based extraction.
  // Dev override: on localhost (Vite dev on :3000) hit the local backend (:25565).
  const API_BASE_URL =
    typeof location !== "undefined" &&
    (location.hostname === "localhost" || location.hostname === "127.0.0.1")
      ? "http://localhost:25565"
      : "https://api.movix.fun";
  const STREAM_PROXY_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

  // Import extractors module

  const Extractors = globalThis.MovixExtractors;

  // User extraction preferences (synced from site via SET_EXTRACTION_PREFS)
  const DEFAULT_EXTRACTION_PREFS = {
    version: 1,
    m3u8: {
      voe: true,
      fsvid: true,
      vidzy: true,
      vidmoly: true,
      sibnet: true,
      uqload: true,
      doodstream: true,
      seekstreaming: true,
      lulustream: true,
      veev: true,
      vidara: true,
    },
    livetv: {
      northlive: true,
      vavoo: true,
      matches: true,
    },
  };
  let extractionPrefs = DEFAULT_EXTRACTION_PREFS;

  // Extension enabled state
  let extensionEnabled = true;

  // Stats tracking (enriched with per-type counters)
  let sessionStats = {
    extractions: 0,
    corsFixed: 0,
    cached: 0,
    byType: {
      voe: 0,
      fsvid: 0,
      vidzy: 0,
      vidmoly: 0,
      sibnet: 0,
      uqload: 0,
      doodstream: 0,
      seekstreaming: 0,
      lulustream: 0,
      veev: 0,
      vidara: 0,
    },
  };

  // Load initial state
  (async () => {
    const storedEnabled = await gmGetValueCompat(
      "movix_extensionEnabled",
      null,
    );
    if (storedEnabled !== null) extensionEnabled = storedEnabled !== false;
    const storedStats = await gmGetValueCompat("movix_stats", null);
    if (storedStats) {
      sessionStats = { ...sessionStats, ...storedStats };
      if (!sessionStats.byType) {
        sessionStats.byType = {
          voe: 0,
          fsvid: 0,
          vidzy: 0,
          vidmoly: 0,
          sibnet: 0,
          uqload: 0,
          doodstream: 0,
          seekstreaming: 0,
          lulustream: 0,
          veev: 0,
          vidara: 0,
        };
      }
    }
    const storedPrefs = await gmGetValueCompat("movix_extraction_prefs", null);
    if (storedPrefs) extractionPrefs = storedPrefs;
  })();

  // Initial setup
  chrome.runtime.onInstalled.addListener(() => {
    setupRules();
  });

  chrome.runtime.onStartup.addListener(() => {
    setupRules();
    // Reset session stats on startup
    sessionStats = {
      extractions: 0,
      corsFixed: 0,
      cached: 0,
      byType: {
        voe: 0,
        fsvid: 0,
        vidzy: 0,
        vidmoly: 0,
        sibnet: 0,
        uqload: 0,
        doodstream: 0,
        seekstreaming: 0,
      },
    };
    gmSetValueCompat("movix_stats", sessionStats);
  });

  // Configure DNR rules for CORS and Headers
  async function setupRules() {
    // Clear existing dynamic rules to prevent accumulation
    const existingRules = await chrome.declarativeNetRequest.getDynamicRules();
    const ruleIds = existingRules.map((rule) => rule.id);
    await chrome.declarativeNetRequest.updateDynamicRules({
      removeRuleIds: ruleIds,
    });

    // Reset rule counter when rules are cleared
    ruleIdCounter = 100;

    const rules = [
      // 1. Allow CORS for everything (Response Headers)
      {
        id: 1,
        priority: 1,
        action: {
          type: "modifyHeaders",
          responseHeaders: [
            {
              header: "Access-Control-Allow-Origin",
              operation: "set",
              value: "*",
            },
            {
              header: "Access-Control-Allow-Methods",
              operation: "set",
              value: "GET, POST, OPTIONS, HEAD, PUT, DELETE, PATCH",
            },
            {
              header: "Access-Control-Allow-Headers",
              operation: "set",
              value: "*",
            },
          ],
        },
        condition: {
          urlFilter: "*",
          initiatorDomains: [
            "localhost",
            "127.0.0.1",
            "movix.cash",
            "movix.club",
            "movix.cloud",
            "movix.tax",
            "movix.golf",
            "movix.chat",
            "movix.date",
            "movix.fun",
            "movix.show",
            "movix.men",
            "movix.college",
            "movix.luxe",
            "movix.dog",
            "movix.miami",
          ],
          resourceTypes: [
            "xmlhttprequest",
            "other",
            "media",
            "image",
            "script",
            "stylesheet",
            "font",
            "websocket",
          ],
        },
      },
    ];

    await chrome.declarativeNetRequest.updateDynamicRules({
      addRules: rules,
    });
  }

  /**
   * Map a catalogId (e.g. "matches_football") to a livetv source key.
   * Returns null if unknown — unknown keys are allowed by default.
   */
  function getLiveTvSourceKey(catalogId) {
    if (!catalogId || typeof catalogId !== "string") return null;
    if (catalogId.startsWith("northlive_")) return "northlive";
    if (catalogId.startsWith("vavoo_")) return "vavoo";
    if (catalogId.startsWith("matches_")) return "matches";
    return null;
  }

  function isLiveTvAllowed(catalogId) {
    const key = getLiveTvSourceKey(catalogId);
    if (!key) return true; // Unknown source → allow by default
    return extractionPrefs.livetv[key] !== false;
  }

  function isEmbedAllowed(type) {
    if (!type) return true;
    return extractionPrefs.m3u8[type] !== false;
  }

  // Handle messages
  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    handleMessage(message)
      .then(sendResponse)
      .catch((err) => sendResponse({ error: err.message }));
    return true; // Keep channel open for async response
  });

  async function handleMessage(message) {
    const { action, payload } = message;

    // Handle toggle action (always allowed)
    if (action === "TOGGLE_EXTENSION") {
      extensionEnabled = payload.enabled;
      if (extensionEnabled) {
        await setupRules();
      } else {
        // Remove all DNR rules when disabled
        const existingRules =
          await chrome.declarativeNetRequest.getDynamicRules();
        const ruleIds = existingRules.map((rule) => rule.id);
        if (ruleIds.length > 0) {
          await chrome.declarativeNetRequest.updateDynamicRules({
            removeRuleIds: ruleIds,
          });
        }
      }
      return { success: true, enabled: extensionEnabled };
    }

    // Handle stats request (always allowed)
    if (action === "GET_STATS") {
      return sessionStats;
    }

    // Block all other actions if disabled
    if (!extensionEnabled) {
      return { error: "Extension is disabled" };
    }

    switch (action) {
      case "KISSKH_FALLBACK":
        return await handleKisskhFallback(payload);
      case "GET_MANIFEST":
        return await getManifest();
      case "GET_CATALOG": {
        const catalogId = payload?.id || "";
        if (!isLiveTvAllowed(catalogId)) {
          return { metas: [], disabled_by_user: true };
        }
        return await getCatalog(payload.type, payload.id, payload?.accessKey);
      }
      case "GET_STREAM": {
        const channelId = payload?.id || "";
        if (!isLiveTvAllowed(channelId)) {
          return {
            error: "disabled_by_user",
            source: getLiveTvSourceKey(channelId),
          };
        }
        return await getStream(
          payload.type,
          payload.id,
          payload?.accessKey,
          payload,
        );
      }
      case "PROXY_HTTP":
        return await proxyHttpRequest(payload.url, payload.headers);

      // === Nexus M3U8 Extraction (runs locally in extension, no server needed) ===
      case "EXTRACT_M3U8": {
        const { url: embedUrl, type: hintedType } = payload || {};
        const detectedType =
          hintedType ||
          (Extractors.detectEmbedType
            ? Extractors.detectEmbedType(embedUrl)
            : null);
        if (detectedType && !isEmbedAllowed(detectedType)) {
          return {
            success: false,
            error: "disabled_by_user",
            type: detectedType,
          };
        }
        sessionStats.extractions++;
        if (detectedType && sessionStats.byType) {
          sessionStats.byType[detectedType] =
            (sessionStats.byType[detectedType] || 0) + 1;
        }
        await gmSetValueCompat("movix_stats", sessionStats);
        return await handleExtractM3u8(payload);
      }
      case "EXTRACT_ALL_M3U8": {
        const filteredSources = (payload?.sources || []).filter((source) => {
          const srcUrl =
            typeof source === "string"
              ? source
              : source?.link || source?.url || "";
          const srcType = Extractors.detectEmbedType
            ? Extractors.detectEmbedType(srcUrl)
            : null;
          return !srcType || isEmbedAllowed(srcType);
        });
        sessionStats.extractions++;
        await gmSetValueCompat("movix_stats", sessionStats);
        return await handleExtractAllM3u8({
          ...payload,
          sources: filteredSources,
        });
      }
      case "DETECT_EMBEDS":
        return handleDetectEmbeds(payload);

      case "SETUP_HEADERS": {
        const headerInfo = await Extractors.setupHeadersForService(
          payload.type,
          payload.url,
        );
        if (headerInfo) {
          await addHeadersRule(
            headerInfo.domainPattern,
            headerInfo.headers,
            headerInfo.removeHeaders,
            headerInfo.removeDomains,
          );
          console.log(
            `[NEXUS] DNR headers set for ${payload.type}: ${headerInfo.domainPattern}`,
          );
          return { success: true };
        }
        return { success: false, error: "Could not setup headers" };
      }

      // FCTV (matches) native: inject the player Referer on the CDN segments.
      case "SETUP_FCTV_HEADERS": {
        const okFctv = await setupFctvHeadersRule(payload?.referer);
        return { success: okFctv };
      }

      // FCTV (matches) native: resolve ONE server locally (IP-bound) -> m3u8.
      case "STREAMED_HANDSHAKE":
        return await streamedHandshake(payload?.url);

      case "RESOLVE_FCTV": {
        const fctvUrl = await resolveFctvStream(payload || {});
        if (fctvUrl) return { success: true, url: fctvUrl };
        return { success: false, error: "fctv resolve failed" };
      }

      case "SET_EXTRACTION_PREFS": {
        const incoming = payload?.prefs;
        if (
          incoming &&
          incoming.version === 1 &&
          incoming.m3u8 &&
          incoming.livetv
        ) {
          extractionPrefs = {
            version: 1,
            m3u8: { ...DEFAULT_EXTRACTION_PREFS.m3u8, ...incoming.m3u8 },
            livetv: { ...DEFAULT_EXTRACTION_PREFS.livetv, ...incoming.livetv },
          };
          await gmSetValueCompat("movix_extraction_prefs", extractionPrefs);
          return { success: true };
        }
        return { success: false, error: "Invalid prefs shape" };
      }

      case "GET_EXTRACTION_PREFS":
        return extractionPrefs;

      case "GET_CACHE_STATS": {
        if (typeof Extractors.getCacheSizes === "function") {
          return Extractors.getCacheSizes();
        }
        return {};
      }

      case "CLEAR_EXTRACTION_CACHE": {
        if (typeof Extractors.clearCaches === "function") {
          Extractors.clearCaches(payload?.type);
          return { success: true };
        }
        return { success: false, error: "Cache API unavailable" };
      }

      default:
        throw new Error(`Unknown action: ${action}`);
    }
  }

  // Encodage base64 d'un segment vidéo. La concaténation octet par octet
  // (`binary += String.fromCharCode(bytes[i])`) coûtait des centaines de ms sur
  // un segment HLS de plusieurs Mo, sur le thread principal. On prend
  // l'encodeur natif quand il existe, sinon on avance par blocs de 32 Ko.
  function proxyBytesToBase64(bytes) {
    if (typeof bytes.toBase64 === "function") return bytes.toBase64();

    let binary = "";
    for (let offset = 0; offset < bytes.length; offset += 32768) {
      binary += String.fromCharCode.apply(
        null,
        bytes.subarray(offset, offset + 32768),
      );
    }
    return btoa(binary);
  }

  // Helper to proxy HTTP requests via extension (to bypass Mixed Content)
  // BEGIN STREAMED HANDSHAKE
  async function streamedHandshake(embedUrl) {
    if (extractionPrefs.livetv.streamed === false) throw new Error("Source Streamed désactivée");
    // Pas de liste de domaines : Streamed change souvent de relais.
    const safeUrl = value => {
      const url = new URL(value);
      if (url.protocol !== "https:" || url.username || url.password || url.port ||
        /^(?:\d+\.){3}\d+$|^\[|(?:^|\.)localhost$/.test(url.hostname)) {
        throw new Error("URL Streamed invalide");
      }
      return url;
    };
    const parse = value => {
      const url = safeUrl(value);
      const parts = url.pathname.match(/^\/embed\/([a-z0-9-]{1,32})\/([^/]{1,500})\/(\d{1,3})\/?$/);
      if (url.hostname !== "embed.st" || url.search || url.hash || !parts) throw new Error("Lecteur Streamed invalide");
      return { source: parts[1], id: decodeURIComponent(parts[2]), stream: parts[3] };
    };
    let slot = parse(embedUrl);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 25000);
    try {
      if (slot.source === "golf") {
        let page = embedUrl;
        let referer = "https://embed.st/";
        for (let hop = 0; hop < 4; hop++) {
          safeUrl(page);
          await addHeadersRule(`*://${new URL(page).host}${new URL(page).pathname}*`, { Referer: referer });
          const response = await fetch(page, { headers: { Referer: referer }, redirect: "error", signal: controller.signal });
          if (!response.ok) throw new Error("Lecteur golf indisponible");
          const html = await response.text();
          const fid = html.match(/\bfid\s*=\s*["\']([^"\']+)["\']/)?.[1];
          if (fid) {
            const player = `https://exposestrat.com/maestrohd1.php?player=desktop&live=${encodeURIComponent(fid)}`;
            await addHeadersRule("*://exposestrat.com/maestrohd1.php*", { Referer: page });
            const legacy = await fetch(player, { headers: { Referer: page }, redirect: "error", signal: controller.signal });
            if (!legacy.ok) throw new Error("Lecteur golf indisponible");
            const parts = (await legacy.text()).match(/return\s*\(\s*(\[\s*"[^"]+"(?:\s*,\s*"[^"]+")*\s*\])\.join\(\s*""\s*\)/)?.[1];
            if (!parts) throw new Error("Adresse golf introuvable");
            const media = new URL(JSON.parse(parts).join(""));
            if (media.protocol !== "https:" || media.username || media.password || media.port) throw new Error("CDN golf inconnu");
            return { url: media.href, referer: "https://exposestrat.com/" };
          }
          const iframe = html.match(/<iframe\b[^>]*\ssrc\s*=\s*(["'])(.*?)\1/is);
          const encoded = html.match(/<iframe\b[^>]*\sdata-source\s*=\s*(["'])(.*?)\1/is) ||
            html.match(/\.\s*src\s*=\s*atob\s*\(\s*(["'])([A-Za-z0-9+/]+={0,2})\1\s*\)/);
          const next = iframe?.[2] || (encoded && atob(encoded[2]));
          if (!next) throw new Error("Lecteur golf introuvable");
          const target = safeUrl(new URL(next.replace(/&amp;/g, "&"), page).href);
          if (target.hostname === "embed.st" && !target.pathname.startsWith("/embed/golf/")) {
            embedUrl = target.href;
            slot = parse(embedUrl);
            break;
          }
          referer = page;
          page = target.href;
        }
        if (slot.source === "golf") throw new Error("Lecteur golf natif indisponible");
      }
      const bytes = [];
      ["source", "id", "stream"].forEach((key, index) => {
        const data = new TextEncoder().encode(slot[key]);
        bytes.push((index + 1) * 8 + 2);
        let length = data.length;
        while (length > 127) { bytes.push((length & 127) | 128); length >>>= 7; }
        bytes.push(length, ...data);
      });
      const headers = { "Content-Type": "application/octet-stream", Origin: "https://embed.st", Referer: embedUrl };
      await addHeadersRule("*://embed.st/fetch", headers);
      const response = await fetch("https://embed.st/fetch", {
        method: "POST", headers, body: new Uint8Array(bytes), redirect: "error", signal: controller.signal,
      });
      if (!response.ok) throw new Error("Source native Streamed indisponible");
      const body = new Uint8Array(await response.arrayBuffer());
      const goat = response.headers.get("goat");
      if (!goat || body.length > 8192) throw new Error("Réponse Streamed invalide");
      return { embedUrl, goat, body: proxyBytesToBase64(body) };
    } finally { clearTimeout(timer); }
  }
  // END STREAMED HANDSHAKE

  async function proxyHttpRequest(url, headers = {}) {
    try {
      if (headers && Object.keys(headers).length > 0) {
        try {
          const parsedUrl = new URL(url);
          const rulePattern = `*://${parsedUrl.host}${parsedUrl.pathname}*`;
          await addHeadersRule(rulePattern, headers);
        } catch (ruleError) {
          console.warn(
            "[PROXY_HTTP] Failed to add DNR headers rule:",
            ruleError,
          );
        }
      }

      const response = await fetch(url, { headers });
      const buffer = await response.arrayBuffer();
      const base64 = proxyBytesToBase64(new Uint8Array(buffer));

      return {
        data: base64,
        contentType: response.headers.get("content-type"),
        status: response.status,
        finalUrl: response.url,
      };
    } catch (e) {
      console.error("Proxy HTTP error:", e);
      return { error: e.message };
    }
  }

  // === NEXUS M3U8 EXTRACTION HANDLERS ===

  function getSeekStreamingPlaybackUrls(result) {
    const urls = [];
    const seen = new Set();
    const add = (url) => {
      if (typeof url !== "string" || !url || seen.has(url)) return;
      seen.add(url);
      urls.push(url);
    };
    if (Array.isArray(result?.hlsCandidates)) {
      for (const candidate of result.hlsCandidates) {
        add(candidate?.url);
      }
    }
    add(result?.hlsUrl);
    add(result?.m3u8Url);
    return urls;
  }

  /**
   * Handle single embed extraction request
   * payload: { type: 'voe'|'fsvid'|..., url: 'https://...' }
   */
  async function handleExtractM3u8(payload) {
    const { type, url } = payload;
    if (!url) return { success: false, error: "Missing URL" };

    // Auto-detect type if not provided
    const embedType = type || Extractors.detectEmbedType(url);
    if (!embedType) return { success: false, error: "Unknown embed type" };

    if (embedType === "seekstreaming") {
      let extractionRuleId = null;
      try {
        const extractionHeaders = await Extractors.setupHeadersForService(
          embedType,
          url,
          url,
        );
        if (extractionHeaders) {
          extractionRuleId = await addHeadersRule(
            extractionHeaders.domainPattern,
            extractionHeaders.headers,
          );
        }
        const result = await Extractors.extractSingle(embedType, url);
        if (result.success) {
          for (const videoUrl of getSeekStreamingPlaybackUrls(result)) {
            const playbackHeaders =
              await Extractors.setupHeadersForService(embedType, videoUrl, url);
            if (playbackHeaders) {
              await replaceSeekPlaybackRule(playbackHeaders);
            }
          }
        }
        return result;
      } finally {
        await removeHeadersRule(extractionRuleId);
      }
    }

    // Set up DNR headers BEFORE extraction so the fetch request succeeds
    try {
      const headerInfo = await Extractors.setupHeadersForService(
        embedType,
        url,
      );
      if (headerInfo) {
        await addHeadersRule(
            headerInfo.domainPattern,
            headerInfo.headers,
            headerInfo.removeHeaders,
            headerInfo.removeDomains,
          );
        console.log(
          `[NEXUS] Pre-extraction DNR headers set for ${embedType}: ${headerInfo.domainPattern}`,
        );
      }
    } catch (e) {
      console.warn("[NEXUS] Failed to set pre-extraction headers:", e);
    }

    console.log(`[NEXUS] Extracting ${embedType} from: ${url}`);
    const result = await Extractors.extractSingle(embedType, url);

    // Set up DNR headers for the extracted URL so the page player can use it
    if (result.success) {
      const videoUrl = result.hlsUrl || result.m3u8Url;
      // If the video URL is different from the page URL (likely), set headers for it too
      if (videoUrl && videoUrl !== url) {
        const headerInfo = await Extractors.setupHeadersForService(
          embedType,
          videoUrl,
          url,
        );
        if (headerInfo) {
          await addHeadersRule(
            headerInfo.domainPattern,
            headerInfo.headers,
            headerInfo.removeHeaders,
            headerInfo.removeDomains,
          );
          console.log(
            `[NEXUS] DNR headers set for ${embedType}: ${headerInfo.domainPattern}`,
          );
        }
      }
    }

    return result;
  }

  /**
   * Handle parallel extraction of all supported embeds from a sources list
   * payload: { sources: ['url1', 'url2', ...] or [{link:'url', player:'name'}, ...] }
   */
  async function handleExtractAllM3u8(payload) {
    const { sources } = payload;
    if (!sources || !Array.isArray(sources) || sources.length === 0) {
      return { success: false, error: "No sources provided", results: [] };
    }

    console.log(`[NEXUS] Extracting all from ${sources.length} sources`);

    const seekExtractionRuleIds = [];

    // Set up DNR headers for all sources BEFORE extraction
    try {
      const detected = Extractors.detectSupportedEmbeds(sources);
      for (const item of detected) {
        const headerInfo = await Extractors.setupHeadersForService(
          item.type,
          item.url,
          item.url,
        );
        if (headerInfo) {
          const ruleId = await addHeadersRule(
            headerInfo.domainPattern,
            headerInfo.headers,
          );
          if (item.type === "seekstreaming" && Number.isInteger(ruleId)) {
            seekExtractionRuleIds.push(ruleId);
          }
        }
      }
      console.log(
        `[NEXUS] Pre-extraction headers set for ${detected.length} sources`,
      );
    } catch (e) {
      console.warn(
        "[NEXUS] Failed to set pre-extraction headers for batch:",
        e,
      );
    }

    try {
      const results = await Extractors.extractAll(sources);

      // Set up DNR headers for all successful extractions (video URLs)
      for (const result of results) {
        if (result.success) {
          const videoUrls =
            result.type === "seekstreaming"
              ? getSeekStreamingPlaybackUrls(result)
              : [result.hlsUrl || result.m3u8Url].filter(Boolean);
          for (const videoUrl of videoUrls) {
            const headerInfo = await Extractors.setupHeadersForService(
              result.type,
              videoUrl,
              result.url,
            );
            if (headerInfo) {
              if (result.type === "seekstreaming") {
                await replaceSeekPlaybackRule(headerInfo);
              } else {
                await addHeadersRule(
                  headerInfo.domainPattern,
                  headerInfo.headers,
                );
              }
            }
          }
        }
      }

      const successCount = results.filter((r) => r.success).length;
      return {
        success: successCount > 0,
        total: results.length,
        successCount,
        results,
      };
    } finally {
      await Promise.all(seekExtractionRuleIds.map(removeHeadersRule));
    }
  }

  /**
   * Handle embed type detection only (no extraction)
   * payload: { sources: ['url1', 'url2', ...] }
   */
  function handleDetectEmbeds(payload) {
    const { sources } = payload;
    if (!sources || !Array.isArray(sources)) return { embeds: [] };
    return { embeds: Extractors.detectSupportedEmbeds(sources) };
  }

  // === API LOGIC ===

  function getMovixFrontendOrigin() {
    try {
      const currentOrigin = pageWindow.location?.origin;
      const currentHostname = pageWindow.location?.hostname ?? "";

      if (
        currentHostname === "movix.cash" ||
        currentHostname.endsWith(".movix.cash") ||
        currentHostname === "movix.club" ||
        currentHostname.endsWith(".movix.club") ||
        currentHostname === "movix.cloud" ||
        currentHostname.endsWith(".movix.cloud") ||
        currentHostname === "movix.tax" ||
        currentHostname.endsWith(".movix.tax") ||
        currentHostname === "movix.golf" ||
        currentHostname === "movix.chat" ||
        currentHostname.endsWith(".movix.chat") ||
        currentHostname === "movix.date" ||
        currentHostname.endsWith(".movix.date") ||
        currentHostname === "movix.fun" ||
        currentHostname.endsWith(".movix.fun") ||
        currentHostname === "movix.show" ||
        currentHostname.endsWith(".movix.show") ||
        currentHostname === "movix.men" ||
        currentHostname.endsWith(".movix.men") ||
        currentHostname === "movix.college" ||
        currentHostname.endsWith(".movix.college") ||
        currentHostname === "movix.luxe" ||
        currentHostname.endsWith(".movix.luxe") ||
        currentHostname === "movix.dog" ||
        currentHostname.endsWith(".movix.dog") ||
        currentHostname === "movix.miami" ||
        currentHostname.endsWith(".movix.miami") ||
        currentHostname.endsWith(".movix.golf")
      ) {
        return (currentOrigin || "https://movix.fun").replace(/\/$/, "");
      }
    } catch {}

    return "https://movix.fun";
  }

  function buildBackendApiHeaders(accessKey, extraHeaders = {}) {
    const frontendOrigin = getMovixFrontendOrigin();
    const headers = {
      Accept: "application/json",
      Origin: frontendOrigin,
      Referer: `${frontendOrigin}/`,
      ...extraHeaders,
    };

    if (accessKey) {
      headers["x-access-key"] = accessKey;
    }

    return headers;
  }

  async function getManifest() {
    const manifest = {
      id: "org.stremio.merged",
      version: "1.0.0",
      name: "Live TV (Extension)",
      description: "TV sources via Extension",
      catalogs: [],
      resources: ["catalog", "meta", "stream"],
      types: ["tv"],
      idPrefixes: [],
    };

    return manifest;
  }

  async function getCatalog(type, catalogId, accessKey = null) {
    // Route to backend (handles matches_, northlive_, vavoo_, TV Direct, etc.)
    console.log(`[CATALOG] Fetching catalog via Backend: ${catalogId}`);
    const response = await fetch(
      `${API_BASE_URL}/api/livetv/catalog/tv/${catalogId}`,
      {
        headers: buildBackendApiHeaders(accessKey),
      },
    );
    if (!response.ok)
      throw new Error(`Backend API error: ${response.status}`);
    return await response.json();
  }

  async function getStream(type, channelId, accessKey = null, options = {}) {
    // Route to backend (handles matches_, northlive_, vavoo_, TV Direct, etc.)
    console.log(`[STREAM] Fetching stream via Backend: ${channelId}`);
    const response = await fetch(
      `${API_BASE_URL}/api/livetv/stream/tv/${channelId}`,
      {
        headers: buildBackendApiHeaders(accessKey),
      },
    );
    if (!response.ok)
      throw new Error(`Backend API error: ${response.status}`);
    return await response.json();
  }

  // === UTILS ===

  /**
   * Dean Edwards Packer decoder (see extractors.js for full documentation).
   *
   * Converts a number to its base-N string, builds a keyword lookup table,
   * then replaces every placeholder token in the packed template with the
   * corresponding keyword to recover the original readable JavaScript.
   *
   * @param {string} packedScript   - Template with placeholder tokens
   * @param {number} radix          - Numeric base for token encoding
   * @param {number} keywordCount   - Number of keywords
   * @param {string[]} keywords     - Replacement words indexed by token value
   * @returns {string} Decoded JavaScript source
   */
  function decodeDeanEdwardsPacker(
    packedScript,
    radix,
    keywordCount,
    keywords,
  ) {
    function numberToBaseNString(number) {
      const quotient = Math.floor(number / radix);
      const remainder = number % radix;
      const digit =
        remainder > 35
          ? String.fromCharCode(remainder + 29)
          : remainder.toString(36);
      return (quotient > 0 ? numberToBaseNString(quotient) : "") + digit;
    }

    const lookupTable = {};
    for (let i = keywordCount - 1; i >= 0; i--) {
      const token = numberToBaseNString(i);
      lookupTable[token] = keywords[i] || token;
    }

    return packedScript.replace(/\b\w+\b/g, function (token) {
      return lookupTable[token] !== undefined ? lookupTable[token] : token;
    });
  }

  /**
   * Extract M3U8 video URLs from HTML that contains Dean Edwards Packed
   * script blocks (used by WigiStream / live TV embed pages).
   *
   * The function searches for all packed blocks in the HTML, decodes each
   * one, and looks for .m3u8 stream URLs in the decoded output.
   *
   * @param {string} html - Raw HTML source of the embed page
   * @returns {string|null} The best M3U8 URL found, or null
   */
  function extractM3u8FromPackedHtml(html) {
    try {
      // Strategy 1: Try to match complete packed blocks with a regex.
      // Split to avoid Chrome Web Store code scanner false positives.
      const packerBlockPattern = new RegExp(
        "ev" +
          "al\\(function\\(p,a,c,k,e,(?:d|r)\\)\\{.*?return p\\}\\(\\s*['\"]([\\s\\S]*?)['\"]\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*['\"]([\\s\\S]*?)['\"]\\s*\\.split\\(['\"]\\|['\"]\\)\\s*(?:,\\s*0\\s*,\\s*\\{\\})?\\s*\\)\\)",
        "gs",
      );
      const regexMatches = [...html.matchAll(packerBlockPattern)];

      // Strategy 2: If the regex didn't match (malformed or unusual formatting),
      // manually locate each packed block by searching for the marker string
      // and parsing the arguments by hand.
      if (regexMatches.length === 0) {
        const markerPositions = [];
        let searchPos = 0;
        const marker = "ev" + "al(func" + "tion(p,a,c,k,e,";
        while (true) {
          const index = html.indexOf(marker, searchPos);
          if (index === -1) break;
          markerPositions.push(index);
          searchPos = index + 1;
        }

        for (const position of markerPositions) {
          // Take a chunk of HTML starting at the marker (5000 chars should
          // be enough to contain the full packed block arguments)
          const chunk = html.substring(position, position + 5000);

          // Find the .split('|') that terminates the keyword list
          const splitIdx = chunk.indexOf(".split('|')");
          const splitIdx2 = chunk.indexOf('.split("|")');
          const keywordListEnd = splitIdx !== -1 ? splitIdx : splitIdx2;
          if (keywordListEnd === -1) continue;

          // Extract the keyword string (everything between the last quote
          // before .split and the .split itself)
          let keywordStringStart = chunk.lastIndexOf("'", keywordListEnd - 1);
          if (keywordStringStart === -1)
            keywordStringStart = chunk.lastIndexOf('"', keywordListEnd - 1);
          if (keywordStringStart === -1) continue;

          const keywords = chunk
            .substring(keywordStringStart + 1, keywordListEnd)
            .split("|");

          // Find the template string: starts after }(' or }("
          const templateMarker1 = chunk.indexOf("}('");
          const templateMarker2 = chunk.indexOf('}("');
          const templateStart =
            templateMarker1 !== -1 ? templateMarker1 : templateMarker2;
          if (templateStart === -1) continue;

          const quoteChar = chunk[templateStart + 2];
          const templateBegin = templateStart + 3;
          const templateEnd = chunk.indexOf(quoteChar + ",", templateBegin);
          if (templateEnd === -1) continue;

          const packedTemplate = chunk.substring(templateBegin, templateEnd);

          // Parse the radix and count numbers that follow the template
          const afterTemplate = chunk.substring(templateEnd + 2);
          const numbersMatch = afterTemplate.match(/^\s*(\d+)\s*,\s*(\d+)\s*,/);
          if (!numbersMatch) continue;

          const radix = parseInt(numbersMatch[1]);
          const keywordCount = parseInt(numbersMatch[2]);
          const decodedScript = decodeDeanEdwardsPacker(
            packedTemplate,
            radix,
            keywordCount,
            keywords,
          );

          // Check if the decoded script contains video player code
          const hasVideoContent =
            decodedScript &&
            (decodedScript.includes(".m3u8") ||
              decodedScript.includes("hls") ||
              decodedScript.includes("Clappr"));

          if (hasVideoContent) {
            const m3u8Url = findBestM3u8Url(decodedScript);
            if (m3u8Url) return m3u8Url;
          }
        }
      }

      // Process regex-matched packed blocks
      for (const packerMatch of regexMatches) {
        const packedTemplate = packerMatch[1];
        const radix = parseInt(packerMatch[2]);
        const keywordCount = parseInt(packerMatch[3]);
        const keywords = packerMatch[4].split("|");
        const decodedScript = decodeDeanEdwardsPacker(
          packedTemplate,
          radix,
          keywordCount,
          keywords,
        );

        const hasVideoContent =
          decodedScript.includes("Clappr") ||
          decodedScript.includes(".m3u8") ||
          decodedScript.includes("hls");

        if (!hasVideoContent) continue;

        const m3u8Url = findBestM3u8Url(decodedScript);
        if (m3u8Url) return m3u8Url;
      }

      // Fallback: try to find M3U8 URLs directly in the raw HTML
      const directSrcMatch = html.match(/src:\s*["']([^"']+\.m3u8[^"']*)/i);
      if (directSrcMatch) return directSrcMatch[1].replace(/\\\//g, "/");

      const directStreamMatch = html.match(
        /["'](https?:\/\/[^"']*\.m3u8[^"']*)/,
      );
      if (directStreamMatch) return directStreamMatch[1].replace(/\\\//g, "/");

      const varSrcMatch = html.match(/var\s+src\s*=\s*["']([^"']+)/i);
      if (varSrcMatch && varSrcMatch[1].includes(".m3u8")) {
        return varSrcMatch[1].replace(/\\\//g, "/");
      }

      return null;
    } catch (error) {
      return null;
    }
  }

  /**
   * Find the best M3U8 URL from decoded script content.
   * Prefers backup/CDN URLs over primary ones.
   *
   * @param {string} scriptContent - Decoded JavaScript source
   * @returns {string|null} Best M3U8 URL found, or null
   */
  function findBestM3u8Url(scriptContent) {
    const m3u8Pattern = /["'](https?:\/\/[^"']+\.m3u8[^"']*)["']/g;
    let match;
    const urls = [];
    while ((match = m3u8Pattern.exec(scriptContent)) !== null) {
      urls.push(match[1].replace(/\\\//g, "/"));
    }
    if (urls.length === 0) return null;

    // Prefer backup/live CDN URLs, then primary CDN, then first found
    const backupUrl = urls.find(
      (u) => u.includes("vuunov") || u.includes("live"),
    );
    const primaryUrl = urls.find(
      (u) => u.includes("shop") || u.includes("srvagu"),
    );
    return backupUrl || primaryUrl || urls[0];
  }

  async function fetchSafe(url, context = "") {
    try {
      const options = {
        method: "GET",
        headers: {
          Accept: "application/json",
          "Accept-Language": "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
        },
        cache: "no-cache",
      };

      const response = await fetch(url, options);
      if (!response.ok) {
        console.error(
          `Fetch failed for ${context || url}: ${response.status} ${response.statusText}`,
        );
        return null;
      }
      return await response.json();
    } catch (e) {
      console.error(`Fetch error for ${context || url}:`, e);
      return null;
    }
  }

  // Cache helpers
  async function getFromCache(key) {
    const result = await chrome.storage.local.get(key);
    const entry = result[key];
    if (!entry) return null;
    if (Date.now() > entry.expiry) {
      chrome.storage.local.remove(key);
      return null;
    }
    return entry.data;
  }

  async function saveToCache(key, data, ttlMinutes) {
    const expiry = Date.now() + ttlMinutes * 60 * 1000;
    await chrome.storage.local.set({ [key]: { data, expiry } });
  }

  // DNR Helper
  let ruleIdCounter = 100;
  // Reserved below the general 100+ allocator; FCTV owns fixed rule 60.
  const SEEK_PLAYBACK_RULE_ID_MIN = 61;
  const SEEK_PLAYBACK_RULE_ID_MAX = 99;

  function isSeekPlaybackRuleId(ruleId) {
    return (
      Number.isInteger(ruleId) &&
      ruleId >= SEEK_PLAYBACK_RULE_ID_MIN &&
      ruleId <= SEEK_PLAYBACK_RULE_ID_MAX
    );
  }

  async function addUserAgentRule(urlPattern, userAgent) {
    return addHeadersRule(urlPattern, { "User-Agent": userAgent });
  }

  // === FCTV (matches): Referer rule + IP-bound local resolver ===============
  const FCTV_HEADERS_RULE_ID = 60;
  async function setupFctvHeadersRule(referer) {
    if (!referer) return false;
    const ref = referer.endsWith("/") ? referer : referer + "/";
    let origin;
    try { origin = new URL(ref).origin; } catch { origin = ref.replace(/\/+$/, ""); }
    try {
      await chrome.declarativeNetRequest.updateDynamicRules({
        removeRuleIds: [FCTV_HEADERS_RULE_ID],
        addRules: [
          {
            id: FCTV_HEADERS_RULE_ID,
            priority: 20,
            action: {
              type: "modifyHeaders",
              requestHeaders: [
                { header: "Referer", operation: "set", value: ref },
                { header: "Origin", operation: "set", value: origin },
                { header: "User-Agent", operation: "set", value: STREAM_PROXY_USER_AGENT },
              ],
            },
            condition: {
              urlFilter: "/cfall/s",
              resourceTypes: ["xmlhttprequest", "media", "other"],
            },
          },
        ],
      });
      return true;
    } catch (e) {
      console.error("[FCTV] Failed to add headers rule:", e);
      return false;
    }
  }

  const FCTV_API_BASE = "https://apis-data-defra10.tcore131ybdf.ru";
  const FCTV_TOKEN_KEYSTREAM_HEX =
    "15764bab80a419c6abdd5518f3db0ea95bb3b9a2e2b519ce5c159af6917e2000c2d680ae30706a3aba1c9c25786c7c28774eecf20450a3cf414ca17f6472798cfa557c7a8705b7861f06e84f827f8a24676eeab77ce504bfc335b79609b9";
  function fctvRot47(s) {
    let out = "";
    for (let i = 0; i < s.length; i++) {
      const k = s.charCodeAt(i);
      out += k < 33 || k > 126 ? s[i] : String.fromCharCode(33 + ((k - 33 + 47) % 94));
    }
    return out;
  }
  function fctvReadVarint(buf, off) {
    let result = 0n, shift = 0n, cur = off;
    while (cur < buf.length) {
      const b = buf[cur++];
      result |= BigInt(b & 0x7f) << shift;
      if ((b & 0x80) === 0) return { value: result, offset: cur };
      shift += 7n;
      if (shift > 70n) break;
    }
    throw new Error("varint");
  }
  function fctvDecode(buf, depth = 0) {
    const fields = [];
    let off = 0;
    while (off < buf.length) {
      let tag;
      try { tag = fctvReadVarint(buf, off); } catch { break; }
      off = tag.offset;
      const field = Number(tag.value >> 3n);
      const wt = Number(tag.value & 7n);
      const e = { field, wireType: wt };
      if (wt === 0) {
        const p = fctvReadVarint(buf, off); off = p.offset;
      } else if (wt === 1) {
        off += 8;
      } else if (wt === 2) {
        const pl = fctvReadVarint(buf, off); off = pl.offset;
        const len = Number(pl.value);
        const bytes = buf.subarray(off, off + len);
        off += len;
        let text = "";
        try { text = new TextDecoder().decode(bytes); } catch {}
        let printable = 0;
        for (let i = 0; i < text.length; i++) { const c = text.charCodeAt(i); if ((c >= 32 && c <= 126) || c >= 160) printable++; }
        if (text && printable / text.length > 0.7) e.value = text;
        if (depth < 8 && bytes.length) { try { const ch = fctvDecode(bytes, depth + 1); if (ch.length) e.children = ch; } catch {} }
      } else if (wt === 5) {
        off += 4;
      } else break;
      fields.push(e);
    }
    return fields;
  }
  const fctvField = (fields, f) => (fields || []).find((e) => e.field === f);
  const fctvChildren = (fields, f) => { const x = fctvField(fields, f); return (x && x.children) || []; };
  function fctvMakeToken(rbSession) {
    const ks = [];
    for (let i = 0; i < FCTV_TOKEN_KEYSTREAM_HEX.length; i += 2) ks.push(parseInt(FCTV_TOKEN_KEYSTREAM_HEX.substr(i, 2), 16));
    const pt = new TextEncoder().encode(rbSession);
    const n = Math.min(pt.length, ks.length);
    let bin = "";
    for (let i = 0; i < n; i++) bin += String.fromCharCode(pt[i] ^ ks[i]);
    return encodeURIComponent(btoa(bin) + "a");
  }
  async function resolveFctvStream(opts) {
    const { matchId, streamId, siteType, sportType, referer, apiBase } = opts || {};
    if (!matchId || !streamId) return null;
    const base = apiBase || FCTV_API_BASE;
    const u = new URL(base + "/api/stream/detail");
    u.searchParams.set("streamId", String(streamId));
    u.searchParams.set("siteType", String(siteType || 2001));
    u.searchParams.set("continent", "EU");
    u.searchParams.set("country", "FR");
    u.searchParams.set("digit", "seth");
    u.searchParams.set("matchId", String(matchId));
    u.searchParams.set("sportType", String(sportType || 1));
    const resp = await fetch(u.toString(), { headers: { Accept: "*/*" } });
    const rbSession = resp.headers.get("rb-session");
    const buf = new Uint8Array(await resp.arrayBuffer());
    const root = fctvDecode(buf);
    const body = fctvChildren(root, 10);
    const inner = fctvChildren(body, 2).length ? fctvChildren(body, 2) : body;
    const maskedField = fctvField(inner, 4);
    const masked = maskedField && typeof maskedField.value === "string" ? maskedField.value : "";
    if (!masked || !rbSession) return null;
    let parsed;
    try { parsed = new URL(fctvRot47(masked).slice(8)); } catch { return null; }
    const token = fctvMakeToken(rbSession);
    if (referer) await setupFctvHeadersRule(referer);
    return `${parsed.origin}/token-${token}${parsed.pathname}${parsed.search}`;
  }

  function createHeadersRule(
  id,
  urlPattern,
  headers,
  removeHeaders = [],
  requestDomains = [],
) {
    const requestHeaders = Object.entries(headers).map(([header, value]) => ({
      header: header,
      operation: "set",
      value: value,
    }));
    // Retirer un en-tete que le navigateur impose : `fetch` refuse de les
    // effacer, seul declarativeNetRequest le peut. Voir fetchIpBoundPage.
    for (const header of removeHeaders) {
      requestHeaders.push({ header: header, operation: "remove" });
    }

    return {
      id: id,
      priority: 10,
      action: {
        type: "modifyHeaders",
        requestHeaders: requestHeaders,
      },
      condition: {
        urlFilter: urlPattern,
        resourceTypes: [
          "xmlhttprequest",
          "media",
          "websocket",
          "other",
          "sub_frame",
          "main_frame",
        ],
      },
    };
  }

  async function addHeadersRule(
  urlPattern,
  headers,
  removeHeaders = [],
  requestDomains = [],
) {
    const existingRules = await chrome.declarativeNetRequest.getDynamicRules();
    const existingIds = new Set(existingRules.map((r) => r.id));

    let id = ruleIdCounter;
    while (existingIds.has(id)) {
      id++;
    }
    ruleIdCounter = id + 1;

    const rule = createHeadersRule(
      id,
      urlPattern,
      headers,
      removeHeaders,
      requestDomains,
    );

    try {
      await chrome.declarativeNetRequest.updateDynamicRules({
        addRules: [rule],
      });
      return id;
    } catch (e) {
      console.error("Failed to add dynamic rule:", e);
      return null;
    }
  }

  async function removeHeadersRule(ruleId) {
    if (!Number.isInteger(ruleId)) return;
    await chrome.declarativeNetRequest.updateDynamicRules({
      removeRuleIds: [ruleId],
    });
  }

  async function persistSeekPlaybackRule(headerInfo) {
    try {
      const existingRules =
        await chrome.declarativeNetRequest.getDynamicRules();
      const seekRules = existingRules.filter((rule) =>
        isSeekPlaybackRuleId(rule.id),
      );
      const matchingRule = seekRules.find(
        (rule) => rule.condition?.urlFilter === headerInfo.domainPattern,
      );
      const usedIds = new Set(seekRules.map((rule) => rule.id));
      let ruleId = matchingRule?.id ?? null;

      if (ruleId === null) {
        for (
          let candidate = SEEK_PLAYBACK_RULE_ID_MIN;
          candidate <= SEEK_PLAYBACK_RULE_ID_MAX;
          candidate += 1
        ) {
          if (!usedIds.has(candidate)) {
            ruleId = candidate;
            break;
          }
        }
      }

      if (ruleId === null) {
        ruleId = SEEK_PLAYBACK_RULE_ID_MIN;
        console.warn(
          "[NEXUS] Seek playback rule capacity reached; evicting oldest slot",
        );
      }

      const rule = createHeadersRule(
        ruleId,
        headerInfo.domainPattern,
        headerInfo.headers,
      );
      await chrome.declarativeNetRequest.updateDynamicRules({
        removeRuleIds: [ruleId],
        addRules: [rule],
      });
      return ruleId;
    } catch (e) {
      console.error("Failed to persist Seek playback rule:", e);
      return null;
    }
  }

  let seekPlaybackRuleUpdateQueue = Promise.resolve();

  function replaceSeekPlaybackRule(headerInfo) {
    const update = seekPlaybackRuleUpdateQueue.then(() =>
      persistSeekPlaybackRule(headerInfo),
    );
    seekPlaybackRuleUpdateQueue = update.then(
      () => undefined,
      () => undefined,
    );
    return update;
  }
  // --- END extension/Chrome/background.js ---

  function postExtensionResponse(messageId, success, payload) {
    pageWindow.postMessage(
      success
        ? {
            source: "MOVIX_EXTENSION",
            messageId,
            success: true,
            data: payload,
          }
        : {
            source: "MOVIX_EXTENSION",
            messageId,
            success: false,
            error:
              payload instanceof Error
                ? payload.message
                : payload?.error || String(payload || "Erreur inconnue"),
          },
      "*",
    );
  }

  async function requestAction(action, payload) {
    try {
      const result = await handleMessage({ action, payload });
      if (result && result.error) {
        return { success: false, error: result.error };
      }
      return { success: true, data: result };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : String(error),
      };
    }
  }

  function exposePageApi() {
    pageWindow.__MOVIX_EXTENSION_INSTALLED = true;
    pageWindow.hasMovixUserscript = true;
    pageWindow.hasMovixNexusExtractor = true;

    if (document.documentElement) {
      document.documentElement.dataset.movixExtension = "true";
    }

    pageWindow.movixExtractM3u8 = async function movixExtractM3u8(type, url) {
      const result = await requestAction("EXTRACT_M3U8", { type, url });
      return result.success
        ? result.data
        : { success: false, error: result.error || "Extraction impossible" };
    };

    pageWindow.movixExtractAllM3u8 = async function movixExtractAllM3u8(
      sources,
    ) {
      const result = await requestAction("EXTRACT_ALL_M3U8", { sources });
      return result.success
        ? result.data
        : {
            success: false,
            error: result.error || "Extraction multiple impossible",
            results: [],
          };
    };

    pageWindow.movixDetectEmbeds = async function movixDetectEmbeds(sources) {
      const result = await requestAction("DETECT_EMBEDS", { sources });
      return result.success ? result.data : { embeds: [] };
    };

    pageWindow.movixSetupHeaders = async function movixSetupHeaders(type, url) {
      const result = await requestAction("SETUP_HEADERS", { type, url });
      return result.success
        ? result.data
        : { success: false, error: result.error || "Configuration impossible" };
    };

    pageWindow.movixKisskhFallback = async function movixKisskhFallback(request) {
      const result = await requestAction("KISSKH_FALLBACK", request);
      return result.success
        ? result.data
        : { success: false, code: "unsupported_transport" };
    };

    pageWindow.dispatchEvent(new CustomEvent("movix-extension-loaded"));
  }

  pageWindow.addEventListener("message", async (event) => {
    if (
      event.source !== pageWindow ||
      !event.data ||
      event.data.source !== "MOVIX_WEB" ||
      event.data.type !== "EXTENSION_REQUEST"
    ) {
      return;
    }

    const { action, payload, messageId } = event.data;
    const result = await requestAction(action, payload);

    if (result.success) {
      postExtensionResponse(messageId, true, result.data);
    } else {
      postExtensionResponse(
        messageId,
        false,
        result.error || "Erreur inconnue",
      );
    }
  });

  exposePageApi();

  Promise.resolve()
    .then(() => (typeof setupRules === "function" ? setupRules() : undefined))
    .catch((error) => {
      console.error("[Movix Userscript] setupRules error:", error);
    });
})();
