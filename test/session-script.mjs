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
const mutedNow = await page.evaluate(() => window.__turbobox.isSilenced(document.getElementById('a')));
const audio2 = posts.filter(p => p[0] === 'audio');
let peak2 = 0;
for (const [, d] of audio2) { const buf = Buffer.from(d, 'base64'); for (let i = 0; i + 1 < buf.length; i += 2) peak2 = Math.max(peak2, Math.abs(buf.readInt16LE(i))); }
check('local mute silences the page element', mutedNow === true);
check('the tap still records the page while it is locally muted', peak2 > 3000, peak2);
await page.evaluate(() => window.__turbobox.setLocalMute(false));
check('local mute restores the element', (await page.evaluate(() => document.getElementById('a').muted)) === false);
await page.evaluate(() => window.__turbobox.setTap(false));

// an element the page keeps silent (muted, or volume 0: Twitch does this to an ad while the stream plays) is not recorded,
// with or without local mute
for (const [name, away, silence, restore] of [
  ['muted by the page', false, a => { a.muted = true; }, a => { a.muted = false; }],
  ['muted by the page, while stepped away', true, a => { a.muted = true; }, a => { a.muted = false; }],
  ['volume 0, while stepped away', true, a => { a.volume = 0; }, a => { a.volume = 1; }],
]) {
  posts.length = 0;
  await page.evaluate(([away, silence]) => {
    const a = document.getElementById('a'); eval('(' + silence + ')')(a); a.play();
    window.__turbobox.setLocalMute(away); window.__turbobox.setTap(true);
  }, [away, silence.toString()]);
  await page.waitForTimeout(2500);
  let quiet = 0;
  for (const [t, d] of posts) if (t === 'audio') { const bf = Buffer.from(d, 'base64'); for (let i = 0; i + 1 < bf.length; i += 2) quiet = Math.max(quiet, Math.abs(bf.readInt16LE(i))); }
  await page.evaluate(([restore]) => { eval('(' + restore + ')')(document.getElementById('a')); window.__turbobox.setTap(false); window.__turbobox.setLocalMute(false); }, [restore.toString()]);
  check('the tap skips an element ' + name, quiet < 300, quiet);
}

// sound a page makes with its own Web Audio graph is recorded too (the page-audio tap), including while stepped away,
// and never on top of the track/element tap (that would double everything)
const peakOf = (arr) => { let k = 0; for (const [t, d] of arr) if (t === 'audio') { const bf = Buffer.from(d, 'base64'); for (let i = 0; i + 1 < bf.length; i += 2) k = Math.max(k, Math.abs(bf.readInt16LE(i))); } return k; };
const pw = await b.newPage();
const wposts = [];
await pw.exposeFunction('__post', (t, d) => wposts.push([t, d]));
await pw.setContent('<!doctype html><html><body><audio id="e" loop></audio></body></html>');
await pw.addScriptTag({ content: 'window.TurboBoxBridge = { post: function (t, d) { window.__post(t, d); } };' });
await pw.addScriptTag({ content: script });
await pw.evaluate(() => {
  window.__ac = new AudioContext(); window.__g = window.__ac.createGain(); window.__g.gain.value = 0.4;
  const o = window.__ac.createOscillator(); o.frequency.value = 330; o.connect(window.__g); window.__g.connect(window.__ac.destination); o.start();
  window.__turbobox.setTap(true);
});
await pw.waitForTimeout(5500);   // A has nothing, so after its 3 s of silence the page's own audio is used
const wBefore = peakOf(wposts.slice(-8)); wposts.length = 0;
await pw.evaluate(() => window.__turbobox.setLocalMute(true));
await pw.waitForTimeout(2500);
const wAway = peakOf(wposts);
check('a page that makes sound with Web Audio is recorded', wBefore > 8000 && wBefore < 15000, wBefore);
check('and still while stepped away (the speaker is silent, the recording is not)', wAway > 8000 && wAway < 15000, wAway);
// now the track/element tap has sound as well: only one of the two is used, so the level stays one tone's, not two added
wposts.length = 0;
await pw.evaluate((w) => { const e = document.getElementById('e'); e.src = 'data:audio/wav;base64,' + w; e.play(); }, wav);
await pw.waitForTimeout(3500);
wposts.length = 0;
await pw.waitForTimeout(1500);
const both = peakOf(wposts);
check('with both kinds of sound present they are not added together', both > 8000 && both < 17000, both);
await pw.close();

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

// local mute holds against a page that un-mutes itself, covers Web Audio, detached elements and frames
await page.evaluate(() => {
  window.__ac = new AudioContext(); window.__osc = window.__ac.createOscillator(); window.__osc.connect(window.__ac.destination); window.__osc.start();
  window.__na = new Audio('data:audio/wav;base64,' + document.getElementById('a').src.split(',')[1]); window.__na.loop = true; window.__na.play();
  window.__turbobox.setLocalMute(true);
});
await page.waitForTimeout(300);
await page.evaluate(() => { document.getElementById('a').muted = false; window.__na.muted = false; });
await page.waitForTimeout(100);
const lm = await page.evaluate(() => ({
  master: window.__turbobox.state().masters,
  real: [document.getElementById('a'), window.__na].map(e => window.__turbobox.isSilenced(e)),
  seen: [document.getElementById('a'), window.__na].map(e => e.muted),
}));
check('Web Audio goes through a master gain that local mute closes', lm.master.length > 0 && lm.master.every(g => g === 0), lm.master);
check('a page that un-mutes itself (page element and new Audio()) cannot get through', lm.real.every(Boolean), lm.real);
check('the page still reads back what it set', lm.seen.every(v => v === false), lm.seen);
await page.evaluate(() => window.__turbobox.setLocalMute(false));
const lm2 = await page.evaluate(() => ({ master: window.__turbobox.state().masters, real: [document.getElementById('a'), window.__na].map(e => window.__turbobox.realMuted(e)) }));
check('letting go restores the page\'s own settings', lm2.master.every(g => g === 1) && lm2.real.every(v => v === false), lm2);
await page.evaluate(() => { window.__osc.stop(); window.__na.pause(); });

// permission signalling follows the privacy switches
await p2.evaluate(() => { window.__perm = []; });
const st = await p2.evaluate(async () => {
  const s = await navigator.permissions.query({ name: 'microphone' });
  window.__st = s; s.onchange = () => window.__perm.push(s.state);
  const before = s.state;
  window.__turbobox.configure({ micAllowed: false });
  window.__turbobox.configure({ micAllowed: true });
  return { before, now: s.state, events: window.__perm.slice() };
});
check('the Permissions API reports the switch and fires change events', st.before === 'granted' && st.now === 'granted' && st.events.join() === 'denied,granted', st);
const cam = await p2.evaluate(async () => { try { await navigator.mediaDevices.getUserMedia({ video: true }); return 'allowed'; } catch (e) { return e.name; } });
check('the camera is refused until it is allowed', cam === 'NotAllowedError', cam);

// a small window: the layout scale follows the area the page has, not the screen
await page.evaluate(() => window.__turbobox.setViewWidth(400));
const vp = await page.evaluate(() => document.querySelector('meta[name=viewport]').content);
check('viewport scale fits the real area (400 css px of 1280)', /initial-scale=0\.313/.test(vp), vp);
console.log(fails ? `${fails} FAILED` : 'all ok');
await b.close();
process.exit(fails ? 1 : 0);
