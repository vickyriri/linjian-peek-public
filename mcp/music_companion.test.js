import test from "node:test";
import assert from "node:assert/strict";

import {
  __resetMusicCompanionForTests,
  lookupLyrics,
  normalizeMusicText,
  rankMusicCandidates
} from "./music_companion.js";

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => "application/json; charset=utf-8" },
    json: async () => body
  };
}

test.beforeEach(() => {
  __resetMusicCompanionForTests();
});

test("normalizes full-width and punctuation differences", () => {
  assert.equal(normalizeMusicText("ＡＳＴＲＡ（Live）"), "astra live");
});

test("ranks an exact title and artist above a namesake", () => {
  const ranked = rankMusicCandidates(
    { title: "晴天", artist: "周杰伦", album: "", duration_seconds: 269 },
    [
      { provider: "netease", id: "1", title: "晴天", artists: ["其他歌手"], album: "", duration_seconds: 269 },
      { provider: "netease", id: "2", title: "晴天", artists: ["周杰伦"], album: "叶惠美", duration_seconds: 269 }
    ]
  );
  assert.equal(ranked[0].id, "2");
  assert.ok(ranked[0].score > ranked[1].score);
});

test("returns NetEase original, translation, romanization and then uses memory cache", async () => {
  let calls = 0;
  const fetchImpl = async (url) => {
    calls += 1;
    const value = String(url);
    if (value.includes("/api/search/get/web")) {
      return jsonResponse({
        result: {
          songs: [{
            id: 123,
            name: "Lemon",
            artists: [{ name: "米津玄師" }],
            album: { name: "BOOTLEG" },
            duration: 256_000
          }]
        }
      });
    }
    if (value.includes("/api/song/lyric")) {
      return jsonResponse({
        lrc: { lyric: "[00:01.00]夢ならば" },
        tlyric: { lyric: "[00:01.00]如果这是一场梦" },
        romalrc: { lyric: "[00:01.00]yume naraba" }
      });
    }
    throw new Error(`unexpected_url:${value}`);
  };

  const input = { title: "Lemon", artist: "米津玄師", album: "BOOTLEG", duration_seconds: 256 };
  const first = await lookupLyrics(input, { fetchImpl, logger: null });
  const second = await lookupLyrics(input, { fetchImpl, logger: null });

  assert.equal(first.ok, true);
  assert.equal(first.source.provider, "netease");
  assert.equal(first.lyrics.original, "[00:01.00]夢ならば");
  assert.equal(first.lyrics.translation, "[00:01.00]如果这是一场梦");
  assert.equal(first.lyrics.romanization, "[00:01.00]yume naraba");
  assert.equal(first.cache.hit, false);
  assert.equal(second.cache.hit, true);
  assert.equal(calls, 2);
});

test("falls back to LRCLIB when NetEase is unavailable", async () => {
  const fetchImpl = async (url) => {
    const value = String(url);
    if (value.startsWith("https://music.163.com/")) return jsonResponse({}, 503);
    if (value.startsWith("https://lrclib.net/api/search")) {
      return jsonResponse([{
        id: 456,
        trackName: "The Chain",
        artistName: "Fleetwood Mac",
        albumName: "Rumours",
        duration: 270,
        syncedLyrics: "[00:01.00]Listen to the wind blow",
        plainLyrics: "Listen to the wind blow",
        instrumental: false
      }]);
    }
    throw new Error(`unexpected_url:${value}`);
  };

  const result = await lookupLyrics(
    { title: "The Chain", artist: "Fleetwood Mac", album: "Rumours", duration_seconds: 270 },
    { fetchImpl, logger: null }
  );

  assert.equal(result.ok, true);
  assert.equal(result.source.provider, "lrclib");
  assert.equal(result.lyrics.original, "[00:01.00]Listen to the wind blow");
  assert.equal(result.lyrics.translation, "");
});

test("does not guess between namesakes when the artist is missing", async () => {
  const namesakes = [
    { id: 1, name: "Stay", artists: [{ name: "Artist A" }], album: { name: "One" }, duration: 200_000 },
    { id: 2, name: "Stay", artists: [{ name: "Artist B" }], album: { name: "Two" }, duration: 210_000 }
  ];
  const fetchImpl = async (url) => {
    const value = String(url);
    if (value.includes("/api/search/get/web")) return jsonResponse({ result: { songs: namesakes } });
    if (value.includes("lrclib.net/api/search")) {
      return jsonResponse([
        { id: 3, trackName: "Stay", artistName: "Artist C", albumName: "Three", duration: 205, plainLyrics: "lyrics" },
        { id: 4, trackName: "Stay", artistName: "Artist D", albumName: "Four", duration: 215, plainLyrics: "lyrics" }
      ]);
    }
    throw new Error(`unexpected_url:${value}`);
  };

  const result = await lookupLyrics({ title: "Stay" }, { fetchImpl, logger: null });
  assert.equal(result.ok, false);
  assert.equal(result.error, "ambiguous_match");
  assert.ok(result.candidates.length >= 2);
  assert.equal(result.candidates[0].title, "Stay");
  assert.equal("lyrics" in result, false);
});

test("kill switch prevents every external request", async () => {
  let calls = 0;
  const result = await lookupLyrics(
    { title: "Anything", artist: "Anyone" },
    { enabled: false, fetchImpl: async () => { calls += 1; }, logger: null }
  );
  assert.equal(result.ok, false);
  assert.equal(result.error, "music_companion_disabled");
  assert.equal(calls, 0);
});

test("rate limit never schedules a background retry", async () => {
  let calls = 0;
  const fetchImpl = async () => {
    calls += 1;
    return jsonResponse({ result: { songs: [] } });
  };
  const options = { fetchImpl, logger: null, rateLimitMax: 1, rateLimitWindowMs: 60_000 };
  const first = await lookupLyrics({ title: "Missing One" }, options);
  const callsAfterFirst = calls;
  const second = await lookupLyrics({ title: "Missing Two" }, options);

  assert.equal(first.ok, false);
  assert.equal(second.error, "music_rate_limited");
  assert.equal(calls, callsAfterFirst);
  assert.ok(second.retry_after_seconds > 0);
});
