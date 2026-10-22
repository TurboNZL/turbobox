// Tries the page script (android/app/src/main/assets/session.js) in a real Chromium: the workflow steps (click, type, key,
// scroll, js, waitfor, across shadow roots and same-origin frames) and the audio tap behind the recording buffer.
//   node test/session-script.mjs
// Needs playwright (PLAYWRIGHT_MODULE=<path to its index.mjs> if it is not installed here) and a Chromium (CHROMIUM=<binary>).
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const script = readFileSync(fileURLToPath(new URL('../android/app/src/main/assets/session.js', import.meta.url)), 'utf8');
const wav = (() => { // 3 s of 440 Hz at 22050 Hz mono 16-bit
  const sr = 22050, n = sr * 3, buf = Buffer.alloc(44 + n * 2);
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + n * 2, 4); buf.write('WAVEfmt ', 8); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(1, 22); buf.writeUInt32LE(sr, 24); buf.writeUInt32LE(sr * 2, 28); buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34);
  buf.write('data', 36); buf.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) buf.writeInt16LE(Math.round(Math.sin(2 * Math.PI * 440 * i / sr) * 12000), 44 + i * 2);
  return buf.toString('base64');
})();
const html = `<!doctype html><html><body>
<button id="accept" style="display:none">Accept cookies</button>
<button id="join" onclick="document.getElementById('log').textContent+='joined;'">Join now</button>
<button aria-label="Leave call" onclick="document.getElementById('log').textContent+='left;'">X</button>
<button disabled>Disabled one</button>
<input id="name" placeholder="Your name" oninput="document.getElementById('log').textContent+='input:'+this.value+';'">
<form onsubmit="event.preventDefault();document.getElementById('log').textContent+='submitted;'"><input id="q"></form>
<select id="sel"><option value="a">Alpha</option><option value="b">Beta</option></select>
<div id="host"></div>
<iframe id="fr" srcdoc="<button id='inner'>Inside frame</button>"></iframe>
<div style="height:3000px"></div>
<audio id="a" src="data:audio/wav;base64,${wav}" loop></audio>
<div id="log"></div>
<script>
  const r = document.getElementById('host').attachShadow({mode:'open'});
  r.innerHTML = '<button id="deep">Shadow button</button>';
  r.getElementById('deep').onclick = () => document.getElementById('log').textContent += 'shadow;';
  document.addEventListener('keydown', e => document.getElementById('log').textContent += 'key:' + e.key + ';');
</script></body></html>`;
const b = await chromium.launch({ executablePath: process.env.CHROMIUM || undefined, args: ['--autoplay-policy=no-user-gesture-required', '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] });
const page = await b.newPage();
const posts = [];
await page.exposeFunction('__post', (t, d) => posts.push([t, d]));
page.on('pageerror', e => console.log('PAGE ERROR', e.message));
page.on('console', m => { if (m.type() === 'error') console.log('console error', m.text()); });
await page.setContent(html);
await page.addScriptTag({ content: 'window.TurboBoxBridge = { post: function (t, d) { window.__post(t, d); } };' });
await page.addScriptTag({ content: script });
const step = (s) => page.evaluate((x) => window.__turbobox.step(x), s);
const log = () => page.evaluate(() => document.getElementById('log').textContent);
let fails = 0;
const check = (name, cond, extra) => { console.log((cond ? 'ok   ' : 'FAIL ') + name + (extra ? '  ' + JSON.stringify(extra) : '')); if (!cond) fails++; };

let r = await step({ type: 'click', target: 'text:Join' }); check('click by words', r.ok, r);
check('click ran handler', (await log()).includes('joined;'));
r = await step({ type: 'click', target: 'text:Leave call' }); check('click by aria-label', r.ok, r);
r = await step({ type: 'click', target: 'text:Accept cookies' }); check('hidden button is "found but not visible"', !r.ok && /not visible/.test(r.msg), r);
r = await step({ type: 'click', target: 'text:Nothing like this' }); check('missing target fails', !r.ok && /not found/.test(r.msg), r);
r = await step({ type: 'click', target: 'text:Disabled one' }); check('disabled target fails', !r.ok && /disabled/.test(r.msg), r);
r = await step({ type: 'click', target: 'css:#join' }); check('click by css', r.ok, r);
r = await step({ type: 'type', target: 'css:#name', text: 'Ada Lovelace' }); check('type fires input', r.ok && (await log()).includes('input:Ada Lovelace;'), r);
r = await step({ type: 'type', target: 'text:Your name', text: 'Grace' }); check('type finds field by placeholder', r.ok, r);
check('value really set', (await page.inputValue('#name')) === 'Grace');
r = await step({ type: 'type', target: 'css:#sel', text: 'Beta' }); check('select option by text', r.ok && (await page.inputValue('#sel')) === 'b', r);
r = await step({ type: 'click', target: 'text:Shadow button' }); check('click inside shadow root', r.ok && (await log()).includes('shadow;'), r);
r = await step({ type: 'click', target: 'text:Inside frame' }); check('click inside same-origin iframe', r.ok, r);
r = await step({ type: 'waitfor', target: 'text:Join now' }); check('waitfor present', r.ok, r);
r = await step({ type: 'waitfor', target: 'css:#nope' }); check('waitfor absent', !r.ok, r);
await page.focus('#q');
r = await step({ type: 'key', text: 'Enter' }); check('Enter submits the focused form', r.ok && (await log()).includes('submitted;') && (await log()).includes('key:Enter;'), r);
r = await step({ type: 'scroll', text: 'down', number: 500 }); const y = await page.evaluate(() => window.scrollY); check('scroll down', r.ok && y >= 400, y);
r = await step({ type: 'scroll', text: 'top' }); check('scroll top', (await page.evaluate(() => window.scrollY)) === 0);
r = await step({ type: 'js', text: 'document.title = "x"; 1 + 2' }); check('js returns value', r.ok && r.msg === '3', r);
r = await step({ type: 'js', text: 'throw new Error("boom")' }); check('js error is reported, not thrown', !r.ok && /boom/.test(r.msg), r);
r = await step({ type: 'bogus' }); check('unknown step', !r.ok);

// audio tap
await page.evaluate(() => { document.getElementById('a').play(); window.__turbobox.setTap(true); });
await page.waitForTimeout(3500);
await page.evaluate(() => window.__turbobox.setTap(false));
const audio = posts.filter(p => p[0] === 'audio');
let total = 0, peak = 0;
for (const [, d] of audio) { const buf = Buffer.from(d, 'base64'); total += buf.length / 2; for (let i = 0; i + 1 < buf.length; i += 2) peak = Math.max(peak, Math.abs(buf.readInt16LE(i))); }
check('audio tap delivers PCM chunks', audio.length > 3, audio.length);
check('audio tap is ~24 kHz (about 2-3 s of samples)', total > 24000 * 1.5 && total < 24000 * 4.5, total);
check('audio tap carries the actual sound (peak well above silence)', peak > 3000, peak);
// stepping away: the speaker is muted but the recording still hears the page
posts.length = 0;
await page.evaluate(() => { const a = document.getElementById('a'); a.muted = false; a.play(); window.__turbobox.setLocalMute(true); window.__turbobox.setTap(true); });
await page.waitForTimeout(2500);
const mutedNow = await page.evaluate(() => document.getElementById('a').muted);
const audio2 = posts.filter(p => p[0] === 'audio');
let peak2 = 0;
for (const [, d] of audio2) { const buf = Buffer.from(d, 'base64'); for (let i = 0; i + 1 < buf.length; i += 2) peak2 = Math.max(peak2, Math.abs(buf.readInt16LE(i))); }
check('local mute silences the page element', mutedNow === true);
check('the tap still records the page while it is locally muted', peak2 > 3000, peak2);
await page.evaluate(() => window.__turbobox.setLocalMute(false));
check('local mute restores the element', (await page.evaluate(() => document.getElementById('a').muted)) === false);
await page.evaluate(() => window.__turbobox.setTap(false));

// microphone privacy switch (needs a secure origin for navigator.mediaDevices, so a page of its own on localhost)
const p2 = await b.newPage();
await p2.route('http://localhost/', (route) => route.fulfill({ contentType: 'text/html', body: '<!doctype html><html><body></body></html>' }));
await p2.goto('http://localhost/');
await p2.addScriptTag({ content: 'window.TurboBoxBridge = { post: function () {} };' });
await p2.addScriptTag({ content: script });
await p2.evaluate(() => window.__turbobox.configure({ micAllowed: false }));
const denied = await p2.evaluate(async () => { try { await navigator.mediaDevices.getUserMedia({ audio: true }); return 'allowed'; } catch (e) { return e.name; } });
check('a blocked microphone is refused like a browser "Block"', denied === 'NotAllowedError', denied);
await p2.evaluate(() => window.__turbobox.configure({ micAllowed: true }));
const allowed = await p2.evaluate(async () => { try { const s = await navigator.mediaDevices.getUserMedia({ audio: true }); s.getTracks().forEach(t => t.stop()); return 'allowed'; } catch (e) { return e.name; } });
check('an allowed microphone is handed over', allowed === 'allowed', allowed);

// a small window: the layout scale follows the area the page has, not the screen
await page.evaluate(() => window.__turbobox.setViewWidth(400));
const vp = await page.evaluate(() => document.querySelector('meta[name=viewport]').content);
check('viewport scale fits the real area (400 css px of 1280)', /initial-scale=0\.313/.test(vp), vp);
console.log(fails ? `${fails} FAILED` : 'all ok');
await b.close();
process.exit(fails ? 1 : 0);
