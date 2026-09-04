const NETEASE_ORIGIN = "https://music.163.com";
const LRCLIB_ORIGIN = "https://lrclib.net";
const USER_AGENT = "Astra-Music-Pocket/0.3.6.8 (https://github.com/vickyriri/linjian-peek-public)";

const DEFAULT_CACHE_TTL_MS = 6 * 60 * 60 * 1000;
const DEFAULT_CACHE_MAX_ENTRIES = 50;
const DEFAULT_RATE_LIMIT_MAX = 12;
const DEFAULT_RATE_LIMIT_WINDOW_MS = 15 * 60 * 1000;
const DEFAULT_FETCH_TIMEOUT_MS = 8_000;
const DEFAULT_CIRCUIT_FAILURES = 3;
const DEFAULT_CIRCUIT_COOLDOWN_MS = 5 * 60 * 1000;

const lyricCache = new Map();
const requestTimes = [];
const providerCircuits = new Map();

function boundedNumber(value, fallback, min, max) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.min(max, Math.max(min, number)) : fallback;
}

function envFlag(value, fallback = true) {
  if (value === undefined || value === null || value === "") return fallback;
  return !["0", "false", "off", "disabled", "no"].includes(String(value).trim().toLowerCase());
}

function musicConfig(overrides = {}) {
  return {
    enabled: overrides.enabled ?? envFlag(process.env.MUSIC_COMPANION_ENABLED, true),
    cacheTtlMs: boundedNumber(overrides.cacheTtlMs ?? process.env.MUSIC_CACHE_TTL_MS, DEFAULT_CACHE_TTL_MS, 60_000, 24 * 60 * 60 * 1000),
    cacheMaxEntries: boundedNumber(overrides.cacheMaxEntries ?? process.env.MUSIC_CACHE_MAX_ENTRIES, DEFAULT_CACHE_MAX_ENTRIES, 1, 200),
    rateLimitMax: boundedNumber(overrides.rateLimitMax ?? process.env.MUSIC_RATE_LIMIT_MAX, DEFAULT_RATE_LIMIT_MAX, 1, 100),
    rateLimitWindowMs: boundedNumber(overrides.rateLimitWindowMs ?? process.env.MUSIC_RATE_LIMIT_WINDOW_MS, DEFAULT_RATE_LIMIT_WINDOW_MS, 60_000, 60 * 60 * 1000),
    fetchTimeoutMs: boundedNumber(overrides.fetchTimeoutMs ?? process.env.MUSIC_FETCH_TIMEOUT_MS, DEFAULT_FETCH_TIMEOUT_MS, 1_000, 20_000),
    circuitFailures: boundedNumber(overrides.circuitFailures, DEFAULT_CIRCUIT_FAILURES, 1, 10),
    circuitCooldownMs: boundedNumber(overrides.circuitCooldownMs, DEFAULT_CIRCUIT_COOLDOWN_MS, 10_000, 60 * 60 * 1000)
  };
}

export function normalizeMusicText(value = "") {
  return String(value || "")
    .normalize("NFKC")
    .toLowerCase()
    .replace(/[’‘]/g, "'")
    .replace(/[“”]/g, '"')
    .replace(/&/g, " and ")
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim()
    .replace(/\s+/g, " ");
}

function baseTitle(value = "") {
  return normalizeMusicText(
    String(value || "")
      .replace(/[（(【\[].*?(?:live|remaster(?:ed)?|remix|mix|edit|version|ver\.?|feat\.?|ft\.?|伴奏|现场|重制|翻唱).*?[）)】\]]/giu, " ")
      .replace(/\s[-–—]\s.*?(?:live|remaster(?:ed)?|remix|mix|edit|version|ver\.?|伴奏|现场|重制).*$/giu, " ")
  );
}

function compact(value = "") {
  return normalizeMusicText(value).replace(/\s+/g, "");
}

function bigramDice(a, b) {
  const left = [...compact(a)];
  const right = [...compact(b)];
  if (!left.length || !right.length) return 0;
  if (left.join("") === right.join("")) return 1;
  if (left.length === 1 || right.length === 1) return left[0] === right[0] ? 1 : 0;
  const counts = new Map();
  for (let i = 0; i < left.length - 1; i += 1) {
    const gram = left[i] + left[i + 1];
    counts.set(gram, (counts.get(gram) || 0) + 1);
  }
  let overlap = 0;
  for (let i = 0; i < right.length - 1; i += 1) {
    const gram = right[i] + right[i + 1];
    const count = counts.get(gram) || 0;
    if (count > 0) {
      overlap += 1;
      counts.set(gram, count - 1);
    }
  }
  return (2 * overlap) / ((left.length - 1) + (right.length - 1));
}

function textSimilarity(expected, actual, { title = false } = {}) {
  const a = normalizeMusicText(expected);
  const b = normalizeMusicText(actual);
  if (!a || !b) return 0;
  if (a === b) return 1;
  const direct = bigramDice(a, b);
  if (!title) return direct;
  const baseA = baseTitle(expected);
  const baseB = baseTitle(actual);
  if (baseA && baseA === baseB) return 0.97;
  return Math.max(direct, bigramDice(baseA, baseB) * 0.97);
}

function artistSimilarity(expected, artists = []) {
  if (!expected) return 0;
  const wanted = normalizeMusicText(expected);
  const choices = Array.isArray(artists) ? artists : [artists];
  let best = 0;
  for (const artist of choices) {
    const value = normalizeMusicText(artist);
    if (!value) continue;
    if (value === wanted) return 1;
    best = Math.max(best, bigramDice(wanted, value));
  }
  const joined = normalizeMusicText(choices.filter(Boolean).join(" "));
  if (joined === wanted) return 1;
  return Math.max(best, bigramDice(wanted, joined));
}

export function scoreMusicCandidate(query, candidate) {
  const titleScore = textSimilarity(query.title, candidate.title, { title: true });
  const artistScore = query.artist ? artistSimilarity(query.artist, candidate.artists) : null;
  const albumScore = query.album ? textSimilarity(query.album, candidate.album) : null;
  const wantedDuration = Number(query.duration_seconds || 0);
  const actualDuration = Number(candidate.duration_seconds || 0);
  let durationScore = null;
  if (wantedDuration > 0 && actualDuration > 0) {
    const difference = Math.abs(wantedDuration - actualDuration);
    durationScore = difference <= 2 ? 1 : Math.max(0, 1 - ((difference - 2) / 18));
  }

  const weighted = [
    [titleScore, 0.65],
    [artistScore, 0.23],
    [albumScore, 0.07],
    [durationScore, 0.05]
  ].filter(([value]) => value !== null);
  const weightTotal = weighted.reduce((sum, [, weight]) => sum + weight, 0);
  const score = weightTotal
    ? weighted.reduce((sum, [value, weight]) => sum + (value * weight), 0) / weightTotal
    : 0;

  return {
    ...candidate,
    score: Number(score.toFixed(4)),
    score_breakdown: {
      title: Number(titleScore.toFixed(4)),
      artist: artistScore === null ? null : Number(artistScore.toFixed(4)),
      album: albumScore === null ? null : Number(albumScore.toFixed(4)),
      duration: durationScore === null ? null : Number(durationScore.toFixed(4))
    }
  };
}

export function rankMusicCandidates(query, candidates = []) {
  return candidates
    .map((candidate) => scoreMusicCandidate(query, candidate))
    .sort((a, b) => b.score - a.score);
}

function chooseCandidate(query, candidates) {
  const ranked = rankMusicCandidates(query, candidates);
  const top = ranked[0] || null;
  const second = ranked[1] || null;
  if (!top) return { match: null, confidence: "none", ambiguous: false, ranked };

  const margin = second ? top.score - second.score : top.score;
  const titleStrong = top.score_breakdown.title >= 0.92;
  const artistStrong = !query.artist || top.score_breakdown.artist >= 0.75;
  const sameTitleAlternatives = !query.artist && ranked
    .slice(1, 5)
    .some((candidate) => candidate.score_breakdown.title >= 0.96);

  let confidence = "low";
  if (titleStrong && artistStrong && (query.artist || !sameTitleAlternatives)) confidence = top.score >= 0.88 ? "high" : "medium";
  else if (top.score >= 0.72 && titleStrong) confidence = "medium";

  const ambiguous = confidence === "low"
    || (!query.artist && sameTitleAlternatives)
    || (margin < 0.025 && !artistStrong);

  return { match: top, confidence, ambiguous, margin: Number(margin.toFixed(4)), ranked };
}

function publicCandidate(candidate) {
  if (!candidate) return null;
  return {
    provider: candidate.provider,
    id: candidate.id,
    title: candidate.title,
    artists: candidate.artists,
    album: candidate.album,
    duration_seconds: candidate.duration_seconds,
    score: candidate.score,
    page_url: candidate.page_url || null
  };
}

function mapNeteaseSong(song) {
  const album = song?.album ?? song?.al ?? {};
  const artists = song?.artists ?? song?.ar ?? [];
  const durationMs = Number(song?.duration ?? song?.dt ?? 0);
  return {
    provider: "netease",
    id: String(song?.id ?? ""),
    title: String(song?.name ?? ""),
    artists: Array.isArray(artists) ? artists.map((artist) => artist?.name).filter(Boolean) : [],
    album: String(album?.name ?? ""),
    duration_seconds: durationMs > 0 ? Math.round(durationMs / 1000) : null,
    page_url: song?.id ? `https://music.163.com/song?id=${song.id}` : null
  };
}

function mapLrclibRecord(record) {
  return {
    provider: "lrclib",
    id: String(record?.id ?? ""),
    title: String(record?.trackName ?? ""),
    artists: record?.artistName ? [String(record.artistName)] : [],
    album: String(record?.albumName ?? ""),
    duration_seconds: Number(record?.duration || 0) || null,
    page_url: record?.id ? `https://lrclib.net/api/get/${record.id}` : null,
    _lyrics: {
      original: String(record?.syncedLyrics || record?.plainLyrics || ""),
      translation: "",
      romanization: ""
    },
    instrumental: Boolean(record?.instrumental)
  };
}

function truncateLyrics(value, maxLength = 30_000) {
  const text = typeof value === "string" ? value : "";
  return text.length <= maxLength ? text : `${text.slice(0, maxLength)}\n…[歌词过长，已截断]`;
}

function fetchHeaders(provider) {
  if (provider === "netease") {
    return {
      Accept: "application/json",
      Referer: `${NETEASE_ORIGIN}/`,
      "User-Agent": "Mozilla/5.0 AppleWebKit/537.36 Chrome/136 Safari/537.36"
    };
  }
  return { Accept: "application/json", "User-Agent": USER_AGENT };
}

async function fetchJson(url, { fetchImpl, timeoutMs, provider }) {
  const response = await fetchImpl(url, {
    headers: fetchHeaders(provider),
    redirect: "error",
    signal: AbortSignal.timeout(timeoutMs)
  });
  if (!response.ok) throw new Error(`${provider}_http_${response.status}`);
  const contentType = response.headers?.get?.("content-type") || "";
  if (contentType && !contentType.toLowerCase().includes("json")) throw new Error(`${provider}_non_json_response`);
  return response.json();
}

function circuitFor(provider) {
  if (!providerCircuits.has(provider)) providerCircuits.set(provider, { failures: 0, openedUntil: 0 });
  return providerCircuits.get(provider);
}

async function withProviderCircuit(provider, task, config, now) {
  const circuit = circuitFor(provider);
  if (circuit.openedUntil > now) {
    const error = new Error(`${provider}_circuit_open`);
    error.retryAfterMs = circuit.openedUntil - now;
    throw error;
  }
  try {
    const result = await task();
    circuit.failures = 0;
    circuit.openedUntil = 0;
    return result;
  } catch (error) {
    circuit.failures += 1;
    if (circuit.failures >= config.circuitFailures) {
      circuit.openedUntil = now + config.circuitCooldownMs;
      circuit.failures = 0;
    }
    throw error;
  }
}

async function searchNetease(query, context) {
  const url = new URL("/api/search/get/web", NETEASE_ORIGIN);
  url.searchParams.set("s", [query.title, query.artist].filter(Boolean).join(" "));
  url.searchParams.set("type", "1");
  url.searchParams.set("limit", "10");
  url.searchParams.set("offset", "0");
  url.searchParams.set("total", "true");
  url.searchParams.set("csrf_token", "");
  const payload = await fetchJson(url, { ...context, provider: "netease" });
  return (Array.isArray(payload?.result?.songs) ? payload.result.songs : []).map(mapNeteaseSong);
}

async function getNeteaseLyrics(songId, context) {
  const url = new URL("/api/song/lyric", NETEASE_ORIGIN);
  url.searchParams.set("id", String(songId));
  url.searchParams.set("lv", "-1");
  url.searchParams.set("kv", "-1");
  url.searchParams.set("tv", "-1");
  url.searchParams.set("rv", "-1");
  const payload = await fetchJson(url, { ...context, provider: "netease" });
  return {
    original: truncateLyrics(payload?.lrc?.lyric),
    translation: truncateLyrics(payload?.tlyric?.lyric),
    romanization: truncateLyrics(payload?.romalrc?.lyric),
    instrumental: Boolean(payload?.nolyric),
    uncollected: Boolean(payload?.uncollected)
  };
}

async function searchLrclib(query, context) {
  const url = new URL("/api/search", LRCLIB_ORIGIN);
  if (query.artist) {
    url.searchParams.set("track_name", query.title);
    url.searchParams.set("artist_name", query.artist);
    if (query.album) url.searchParams.set("album_name", query.album);
  } else {
    url.searchParams.set("q", query.title);
  }
  const payload = await fetchJson(url, { ...context, provider: "lrclib" });
  return (Array.isArray(payload) ? payload : []).slice(0, 15).map(mapLrclibRecord);
}

function cacheKey(query) {
  return [
    normalizeMusicText(query.title),
    normalizeMusicText(query.artist),
    normalizeMusicText(query.album),
    Number(query.duration_seconds || 0)
  ].join("|");
}

function cleanExpiredCache(now) {
  for (const [key, value] of lyricCache.entries()) {
    if (value.expiresAt <= now) lyricCache.delete(key);
  }
}

function readCache(key, now) {
  cleanExpiredCache(now);
  const entry = lyricCache.get(key);
  if (!entry) return null;
  lyricCache.delete(key);
  lyricCache.set(key, entry);
  return {
    ...entry.value,
    cache: {
      hit: true,
      expires_at: new Date(entry.expiresAt).toISOString()
    }
  };
}

function writeCache(key, value, now, config) {
  lyricCache.set(key, { value, expiresAt: now + config.cacheTtlMs });
  while (lyricCache.size > config.cacheMaxEntries) {
    const oldestKey = lyricCache.keys().next().value;
    lyricCache.delete(oldestKey);
  }
}

function takeRateLimit(now, config) {
  while (requestTimes.length && requestTimes[0] <= now - config.rateLimitWindowMs) requestTimes.shift();
  if (requestTimes.length >= config.rateLimitMax) {
    return Math.max(1, Math.ceil((requestTimes[0] + config.rateLimitWindowMs - now) / 1000));
  }
  requestTimes.push(now);
  return 0;
}

function safeLogValue(value) {
  return String(value || "").replace(/[\r\n\t]+/g, " ").slice(0, 120);
}

function auditLookup(logger, details) {
  const output = {
    event: "music_companion_lookup",
    title: safeLogValue(details.title),
    artist: safeLogValue(details.artist),
    ok: Boolean(details.ok),
    source: details.source || "",
    cache_hit: Boolean(details.cacheHit),
    elapsed_ms: details.elapsedMs,
    error: details.error || ""
  };
  logger?.(`[music_companion] ${JSON.stringify(output)}`);
}

function normalizedQuery(input = {}) {
  const title = String(input.title || "").trim();
  const artist = String(input.artist || "").trim();
  const album = String(input.album || "").trim();
  const durationSeconds = Number(input.duration_seconds || 0);
  if (!title || title.length > 200) throw new Error("title_must_be_1_to_200_characters");
  if (artist.length > 200) throw new Error("artist_too_long");
  if (album.length > 200) throw new Error("album_too_long");
  if (durationSeconds && (!Number.isFinite(durationSeconds) || durationSeconds < 1 || durationSeconds > 86_400)) {
    throw new Error("invalid_duration_seconds");
  }
  return {
    title,
    artist,
    album,
    duration_seconds: durationSeconds > 0 ? Math.round(durationSeconds) : null
  };
}

function successfulResult(query, selection, lyrics, provider, attempts) {
  const match = publicCandidate(selection.match);
  return {
    ok: true,
    action: "lyrics",
    query,
    match: {
      ...match,
      confidence: selection.confidence,
      score_breakdown: selection.match.score_breakdown,
      margin_to_next: selection.margin
    },
    lyrics: {
      original: truncateLyrics(lyrics.original),
      translation: truncateLyrics(lyrics.translation),
      romanization: truncateLyrics(lyrics.romanization),
      instrumental: Boolean(lyrics.instrumental),
      uncollected: Boolean(lyrics.uncollected)
    },
    source: {
      provider,
      page_url: match?.page_url || null,
      attribution: provider === "netease" ? "网易云音乐公开网页接口" : "LRCLIB"
    },
    alternatives: selection.ranked.slice(1, 4).map(publicCandidate),
    provider_attempts: attempts,
    cache: { hit: false },
    runtime: "按需查询；没有轮询、心跳或后台定时任务。",
    privacy: "不使用音乐账号或 Cookie；日志不会记录歌词正文。"
  };
}

export async function lookupLyrics(input, options = {}) {
  const startedAt = Date.now();
  const nowFn = options.now || (() => Date.now());
  const now = nowFn();
  const logger = options.logger === undefined ? console.info : options.logger;
  const config = musicConfig(options);
  const fetchImpl = options.fetchImpl || globalThis.fetch;
  let query = { title: input?.title || "", artist: input?.artist || "" };
  let finalResult = null;

  try {
    query = normalizedQuery(input);
    if (!config.enabled) {
      finalResult = {
        ok: false,
        error: "music_companion_disabled",
        message: "音乐口袋已通过 MUSIC_COMPANION_ENABLED 关闭。",
        runtime: "功能关闭时不会发出任何歌词请求。"
      };
      return finalResult;
    }
    if (typeof fetchImpl !== "function") throw new Error("fetch_unavailable");

    const key = cacheKey(query);
    const cached = readCache(key, now);
    if (cached) {
      finalResult = cached;
      return cached;
    }

    const retryAfterSeconds = takeRateLimit(now, config);
    if (retryAfterSeconds > 0) {
      finalResult = {
        ok: false,
        error: "music_rate_limited",
        message: "短时间查歌词次数有点多，稍后再试。",
        retry_after_seconds: retryAfterSeconds,
        runtime: "限流只阻止新的外部请求，不会启动后台重试。"
      };
      return finalResult;
    }

    const context = { fetchImpl, timeoutMs: config.fetchTimeoutMs };
    const attempts = [];
    const ambiguous = [];

    try {
      const candidates = await withProviderCircuit("netease", () => searchNetease(query, context), config, now);
      const selection = chooseCandidate(query, candidates);
      if (selection.match && !selection.ambiguous) {
        try {
          const lyrics = await withProviderCircuit("netease", () => getNeteaseLyrics(selection.match.id, context), config, now);
          if (lyrics.original || lyrics.instrumental) {
            finalResult = successfulResult(query, selection, lyrics, "netease", [...attempts, { provider: "netease", status: "matched" }]);
            writeCache(key, finalResult, now, config);
            return finalResult;
          }
          attempts.push({ provider: "netease", status: "lyrics_unavailable" });
        } catch (error) {
          attempts.push({ provider: "netease", status: "lyrics_failed", error: safeLogValue(error?.message || error) });
        }
      } else {
        attempts.push({ provider: "netease", status: candidates.length ? "ambiguous" : "no_results" });
        ambiguous.push(...selection.ranked.slice(0, 5));
      }
    } catch (error) {
      attempts.push({ provider: "netease", status: "search_failed", error: safeLogValue(error?.message || error) });
    }

    try {
      const candidates = await withProviderCircuit("lrclib", () => searchLrclib(query, context), config, now);
      const selection = chooseCandidate(query, candidates);
      if (selection.match && !selection.ambiguous) {
        const lyrics = selection.match._lyrics || {};
        if (lyrics.original || selection.match.instrumental) {
          finalResult = successfulResult(
            query,
            selection,
            { ...lyrics, instrumental: selection.match.instrumental },
            "lrclib",
            [...attempts, { provider: "lrclib", status: "matched" }]
          );
          writeCache(key, finalResult, now, config);
          return finalResult;
        }
        attempts.push({ provider: "lrclib", status: "lyrics_unavailable" });
      } else {
        attempts.push({ provider: "lrclib", status: candidates.length ? "ambiguous" : "no_results" });
        ambiguous.push(...selection.ranked.slice(0, 5));
      }
    } catch (error) {
      attempts.push({ provider: "lrclib", status: "search_failed", error: safeLogValue(error?.message || error) });
    }

    const alternatives = rankMusicCandidates(query, ambiguous)
      .filter((candidate, index, all) => all.findIndex((item) => `${item.provider}:${item.id}` === `${candidate.provider}:${candidate.id}`) === index)
      .slice(0, 5)
      .map(publicCandidate);
    const hadAmbiguous = alternatives.length > 0;
    finalResult = {
      ok: false,
      error: hadAmbiguous ? "ambiguous_match" : "lyrics_not_found",
      message: hadAmbiguous
        ? "找到了几首可能的歌，但还不能放心认定是哪一首；补充歌手、专辑或时长会更准。"
        : "两个歌词来源都没有找到可用歌词。",
      query,
      candidates: alternatives,
      provider_attempts: attempts,
      cache: { hit: false },
      runtime: "查询到此结束；不会在后台自动重试。"
    };
    return finalResult;
  } catch (error) {
    finalResult = {
      ok: false,
      error: safeLogValue(error?.message || error) || "music_lookup_failed",
      message: "歌词查询参数无效或请求暂时失败。",
      runtime: "失败后不会在后台自动重试。"
    };
    return finalResult;
  } finally {
    auditLookup(logger, {
      title: query?.title,
      artist: query?.artist,
      ok: finalResult?.ok,
      source: finalResult?.source?.provider,
      cacheHit: finalResult?.cache?.hit,
      elapsedMs: Math.max(0, Date.now() - startedAt),
      error: finalResult?.error
    });
  }
}

export function getMusicCompanionStatus() {
  const config = musicConfig();
  return {
    enabled: config.enabled,
    mode: "on_demand",
    providers: ["netease", "lrclib"],
    polling: false,
    heartbeat: false,
    background_retry: false,
    cache: "memory_only",
    cache_ttl_minutes: Math.round(config.cacheTtlMs / 60_000),
    rate_limit: {
      max_uncached_lookups: config.rateLimitMax,
      window_minutes: Math.round(config.rateLimitWindowMs / 60_000)
    }
  };
}

export function __resetMusicCompanionForTests() {
  lyricCache.clear();
  requestTimes.splice(0, requestTimes.length);
  providerCircuits.clear();
}
