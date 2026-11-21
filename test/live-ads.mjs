// Tries the live-stream ad-break remover (liveAdHook in android/app/src/main/assets/session.js) against invented playlists
// and a fake network: a clean copy is played when one exists, the ad segments are cut out when none does, and nothing is
// touched when there is no ad break.   node test/live-ads.mjs
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
const src = readFileSync(fileURLToPath(new URL('../android/app/src/main/assets/session.js', import.meta.url)), 'utf8');
const start = src.indexOf('function liveAdHook(');
const end = src.indexOf('var liveAdOn = false;');
const hook = new Function('return (' + src.slice(start, end).trim().replace(/;\s*$/, '') + ')')();

const MASTER = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080\nhttps://cdn.example.com/src.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720\nhttps://cdn.example.com/720.m3u8\n';
const BACKUP_MASTER = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080\nhttps://backup.example.com/src.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720\nhttps://backup.example.com/720.m3u8\n';
const CLEAN = '#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:2.000,live\nhttps://backup.example.com/a.ts\n';
const ADS = '#EXTM3U\n#EXT-X-DATERANGE:ID="stitched-ad-1",CLASS="twitch-stitched-ad",START-DATE="2000-01-01T00:00:00Z"\n#EXTINF:2.000,live\nhttps://cdn.example.com/live1.ts\n#EXTINF:2.000,Amazon\nhttps://cdn.example.com/ad1.ts\n#EXTINF:2.000,Amazon\nhttps://cdn.example.com/ad2.ts\n#EXT-X-TWITCH-PREFETCH:https://cdn.example.com/ad3.ts\n';

function world({ backupWorks }) {
  const calls = [];
  const scope = {
    location: { href: 'https://www.example.com/chan' },
    fetch: async (input, init) => {
      const url = typeof input === 'string' ? input : input.url;
      calls.push(url);
      const text = (t) => new Response(t, { status: 200 });
      if (url === 'https://gql.example.com/gql') {
        const type = /"playerType":"(\w+)"/.exec(init.body)[1];
        return new Response(JSON.stringify({ data: { streamPlaybackAccessToken: { value: 'tok-' + type, signature: 'sig-' + type } } }), { status: 200 });
      }
      if (url.startsWith('https://usher.example.com/')) return text(new URL(url).searchParams.get('token') === 'tok-embed' && backupWorks ? BACKUP_MASTER : MASTER);
      if (url === 'https://cdn.example.com/src.m3u8') return text(ADS);
      if (url === 'https://backup.example.com/src.m3u8') return text(CLEAN);
      if (url === 'https://cdn.example.com/720.m3u8') return text('#EXTM3U\n#EXTINF:2.000,live\nhttps://cdn.example.com/x.ts\n');
      return new Response('', { status: 404 });
    },
  };
  hook(scope, () => {});
  return { scope, calls };
}
const gql = { method: 'POST', headers: { 'client-id': 'x' }, body: '{"operationName":"PlaybackAccessToken","variables":{"playerType":"site"}}' };
async function play(w) {
  await w.scope.fetch('https://gql.example.com/gql', gql);
  await w.scope.fetch('https://usher.example.com/api/channel/hls/chan.m3u8?token=tok-site&sig=sig-site');
  return (await w.scope.fetch('https://cdn.example.com/src.m3u8')).text();
}

// 1. an ad break with a clean copy available: the clean playlist is played
let w = world({ backupWorks: true });
assert.equal(await play(w), CLEAN);
// 2. no clean copy: ad segments and the prefetch hint are cut, the live one stays
w = world({ backupWorks: false });
const stripped = await play(w);
assert.ok(stripped.includes('live1.ts') && !stripped.includes('ad1.ts') && !stripped.includes('ad3.ts') && !stripped.includes('stitched'), stripped);
// 3. no ad break: the very same playlist comes back, and nothing extra is requested
w = world({ backupWorks: true });
await w.scope.fetch('https://gql.example.com/gql', gql);
await w.scope.fetch('https://usher.example.com/api/channel/hls/chan.m3u8?token=tok-site&sig=sig-site');
const before = w.calls.length;
assert.equal(await (await w.scope.fetch('https://cdn.example.com/720.m3u8')).text(), '#EXTM3U\n#EXTINF:2.000,live\nhttps://cdn.example.com/x.ts\n');
assert.equal(w.calls.length, before + 1);
// 4. a break with nothing but ads is left alone rather than handed to the player empty
const only = scopeStrip('#EXTM3U\n#EXTINF:2.000,Amazon\nhttps://cdn.example.com/ad1.ts\n');
assert.ok(only.includes('ad1.ts'));
function scopeStrip(t) { const s = { fetch() {}, location: { href: '' } }; hook(s, null); return s.__tbLiveAdTest.stripAds(t); }
console.log('live-ads: all checks passed');
