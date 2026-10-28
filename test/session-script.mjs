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
// an element playing a MediaStream that is not a call (Twitch plays its live stream like this) is recorded; on a page of its
// own, so nothing else can make the sound
const sp = await b.newPage();
const sposts = [];
await sp.exposeFunction('__post', (t, d) => sposts.push([t, d]));
await sp.setContent('<!doctype html><html><body></body></html>');
await sp.addScriptTag({ content: 'window.TurboBoxBridge = { post: function (t, d) { window.__post(t, d); } };' });
await sp.addScriptTag({ content: script });
await sp.evaluate(() => {
  const ac = new AudioContext(), dest = ac.createMediaStreamDestination(), o = ac.createOscillator(), g = ac.createGain();
  g.gain.value = 0.4; o.frequency.value = 440; o.connect(g); g.connect(dest); o.start();
  const v = document.createElement('video'); v.srcObject = dest.stream; document.body.appendChild(v); v.play();
  window.__turbobox.setTap(true);
});
await sp.waitForTimeout(3500);
sposts.length = 0;
await sp.waitForTimeout(1500);
const streamPeak = peakOf(sposts);
check('an element playing a plain MediaStream is recorded', streamPeak > 3000, streamPeak);
await sp.close();

// Page info lists what makes sound
const rep = await pw.evaluate(() => { let r; window.TurboBoxBridge.post = (t, d) => { if (t === 'info') r = d; }; window.__turbobox.info(); return r; });
const repAudio = typeof rep === 'string' ? JSON.parse(rep).audio : rep && rep.audio;
check('page info carries an audio report', /recording tap on/.test(String(repAudio)) && /audio 0:|no media elements|\n  audio/.test(String(repAudio)), repAudio);
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
// virtual devices: pages see one fixed set whatever the phone has
const devs = await p2.evaluate(async () => (await navigator.mediaDevices.enumerateDevices()).map(d => d.kind + ':' + d.label));
check('a page sees one microphone and one speaker, nothing else', JSON.stringify(devs) === JSON.stringify(['audioinput:Microphone', 'audiooutput:Speaker']), devs);
await p2.evaluate(() => window.__turbobox.configure({ cameraAllowed: true }));
const devs2 = await p2.evaluate(async () => (await navigator.mediaDevices.enumerateDevices()).map(d => d.kind + ':' + d.label));
check('a front and a back camera are listed once the camera is allowed', devs2.length === 4 && devs2.includes('videoinput:Back camera'), devs2);
await p2.evaluate(() => window.__turbobox.configure({ cameraAllowed: false }));
const sink = await p2.evaluate(async () => { const a = new Audio(); await a.setSinkId('anything'); return a.sinkId + '|' + (await navigator.mediaDevices.selectAudioOutput()).label; });
check('choosing an output device is accepted and changes nothing', sink === '|Speaker', sink);
const micPick = await p2.evaluate(async () => { const s = await navigator.mediaDevices.getUserMedia({ audio: { deviceId: { exact: 'some-real-headset' } } }); const l = s.getAudioTracks().length; s.getTracks().forEach(t => t.stop()); return l; });
check('a page that asks for another microphone id still gets the virtual one', micPick === 1, micPick);
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
// page volume 0 silences everything, including a live stream element; 50% takes a stream through the output stage
await page.evaluate(async () => {
  const ac = new AudioContext(); const osc = ac.createOscillator(); const dest = ac.createMediaStreamDestination(); osc.connect(dest); osc.start();
  const v = document.createElement('video'); v.id = 'livevid'; v.srcObject = dest.stream; v.muted = false; document.body.appendChild(v);
  try { await v.play(); } catch (e) { /* autoplay rules */ }
  window.__turbobox.setVolume(0);
});
await page.waitForTimeout(700);
const vol0 = await page.evaluate(() => { const v = document.getElementById('livevid'); return { real: window.__turbobox.realMuted(v), page: v.muted }; });
check('page volume 0 silences a live stream element and the page still reads unmuted', vol0.real === true && vol0.page === false, vol0);
await page.evaluate(() => window.__turbobox.setVolume(0.5));
await page.waitForTimeout(1200);
const vol50 = await page.evaluate(() => { const v = document.getElementById('livevid'); return { real: window.__turbobox.realMuted(v), page: v.muted }; });
check('at 50% the stream is taken through the output stage (element silent, page unchanged)', vol50.real === true && vol50.page === false, vol50);
await page.evaluate(() => window.__turbobox.setVolume(1));
await page.waitForTimeout(1200);
const vol100 = await page.evaluate(() => { const v = document.getElementById('livevid'); const r = window.__turbobox.realMuted(v); v.remove(); return r; });
check('back at 100% the element plays by itself again', vol100 === false, vol100);

// page volume is a middleman: the page's own volume stays readable and is not the one that is scaled
await page.evaluate(() => window.__turbobox.setVolume(0.5));
await page.waitForTimeout(300);
const pv = await page.evaluate(() => { const a = document.getElementById('a'); return { masters: window.__turbobox.state().masters, own: a.volume, real: (window.__turbobox.state().masters.length, a.volume) }; });
check('page volume scales the master gain, not what the page reads from its element', pv.masters.some(g => Math.abs(g - 0.5) < 0.01) && pv.own === 1, pv);
await page.evaluate(() => { window.__na.volume = 0.8; });
const pv2 = await page.evaluate(() => window.__na.volume);
check('what the page sets on its element reads back unchanged', pv2 === 0.8, pv2);
// a media file is taken into the page bus whatever the volume, and the one gain on the bus is the page volume
await page.evaluate(async () => {
  const sr = 8000, n = sr, buf = new ArrayBuffer(44 + n * 2), dv = new DataView(buf);
  const w = (o, t) => { for (let i = 0; i < t.length; i++) dv.setUint8(o + i, t.charCodeAt(i)); };
  w(0, 'RIFF'); dv.setUint32(4, 36 + n * 2, true); w(8, 'WAVEfmt '); dv.setUint32(16, 16, true); dv.setUint16(20, 1, true); dv.setUint16(22, 1, true);
  dv.setUint32(24, sr, true); dv.setUint32(28, sr * 2, true); dv.setUint16(32, 2, true); dv.setUint16(34, 16, true); w(36, 'data'); dv.setUint32(40, n * 2, true);
  for (let i = 0; i < n; i++) dv.setInt16(44 + i * 2, Math.round(Math.sin(i / 8) * 8000), true);
  const el = document.createElement('audio'); el.id = 'filea'; el.loop = true; el.src = URL.createObjectURL(new Blob([buf], { type: 'audio/wav' })); document.body.appendChild(el);
  window.__turbobox.setVolume(0.5);
  try { await el.play(); } catch (e) { /* autoplay rules */ }
});
await page.waitForTimeout(2000);
const fb = await page.evaluate(() => { const e = document.getElementById('filea'); return { bus: window.__turbobox.state().bus, routed: window.__turbobox.isSilenced(e), page: e.volume }; });
check('a media file goes through the page bus and the bus gain is the volume', fb.routed === true && Math.abs(fb.bus - 0.5) < 0.01 && fb.page === 1, fb);
await page.evaluate(() => window.__turbobox.setVolume(1.5));
const fb2 = await page.evaluate(() => window.__turbobox.state().bus);
check('above 100% the same gain turns it up', Math.abs(fb2 - 1.5) < 0.01, fb2);
await page.evaluate(() => { document.getElementById('filea').remove(); });
await page.evaluate(() => window.__turbobox.setVolume(1));
await page.waitForTimeout(200);
await page.evaluate(() => { window.__osc.stop(); window.__na.pause(); });

// turning the phone does not put back the scroll position of the other orientation
await page.setViewportSize({ width: 400, height: 800 });
await page.evaluate(() => { document.body.style.height = '6000px'; window.scrollTo(0, 900); });
await page.waitForTimeout(500);
await page.evaluate(() => window.__turbobox.resized());      // the app tells the page first, then the page turns
await page.setViewportSize({ width: 800, height: 400 });
await page.waitForTimeout(150);
await page.evaluate(() => window.scrollTo(0, 100));          // where the page itself puts things in landscape
await page.waitForTimeout(1800);
const turned = await page.evaluate(() => document.scrollingElement.scrollTop);
check('after turning the phone, the old orientation\'s scroll position is not put back', turned < 400, turned);
await page.evaluate(() => { document.body.style.height = ''; window.scrollTo(0, 0); });

// ad hiding: valid selectors hide, one the browser does not understand costs only its own chunk
await page.evaluate(() => { document.body.insertAdjacentHTML('beforeend', '<div class="adx" id="adx">ad</div><div class="keep" id="keepme">content</div>'); window.__turbobox.hide(['.adx', 'div:bogus-pseudo(x)', '.nothing']); });
const hid = await page.evaluate(() => ({ ad: getComputedStyle(document.getElementById('adx')).display, keep: getComputedStyle(document.getElementById('keepme')).display }));
check('ad boxes are hidden and the page is left alone', hid.ad === 'none' && hid.keep !== 'none', hid);

// cursor mode: a tap clicks where the pointer is, not where the finger touched
{
  const ctx = await b.newContext({ hasTouch: true, viewport: { width: 400, height: 600 } });
  const tp = await ctx.newPage();
  await tp.route('http://localhost/', (route) => route.fulfill({ contentType: 'text/html', body: '<!doctype html><body style="margin:0"><button id="a" style="position:absolute;left:60px;top:60px;width:100px;height:60px" onclick="window.hit=\'a\'">A</button><button id="b" style="position:absolute;left:250px;top:400px;width:100px;height:60px" onclick="window.hit=\'b\'">B</button></body>' }));
  await tp.goto('http://localhost/');
  await tp.addScriptTag({ content: 'window.TurboBoxBridge = { post: function () {} };' });
  await tp.addScriptTag({ content: script });
  await tp.evaluate(() => window.__turbobox.setCursorMode(true));
  await tp.touchscreen.tap(300, 430);   // finger on B: the pointer is near the top left, over A
  const hit1 = await tp.evaluate(() => window.hit || '');
  check('in cursor mode a tap clicks under the pointer, not under the finger', hit1 === 'a', hit1);
  await tp.evaluate(() => window.__turbobox.setCursorMode(false));
  await tp.touchscreen.tap(300, 430);
  const hit2 = await tp.evaluate(() => window.hit || '');
  check('with it off the finger clicks as usual', hit2 === 'b', hit2);
  // element picker: a touch picks the element and does not click it
  await tp.evaluate(() => { window.hit = ''; window.__picked = []; window.TurboBoxBridge.post = function (k, d) { if (k === 'picked') window.__picked.push(d); }; window.__turbobox.pick(true); });
  await tp.touchscreen.tap(300, 430);
  const got = await tp.evaluate(() => ({ picked: window.__picked, hit: window.hit }));
  check('a tap in pick mode copies the element\'s selector and does not click it', got.picked.length === 1 && /#b/.test(got.picked[0]) && got.hit === '', got);
  await ctx.close();
}

// private display mode: focusing a field asks the app for text; the app's text goes in like typing
await page.evaluate(() => { window.__said = []; const prev = window.TurboBoxBridge.post; window.__prevPost = prev; window.TurboBoxBridge.post = function (k, d) { if (k === 'field') window.__said.push(d); prev(k, d); }; window.__turbobox.configure({ privateDisplay: true }); document.body.insertAdjacentHTML('beforeend', '<input id="fld" value="old">'); document.getElementById('fld').focus(); });
const fieldSaid = await page.evaluate(() => window.__said);
check('focusing a field asks the app for its text', fieldSaid.length === 1 && JSON.parse(fieldSaid[0]).value === 'old', fieldSaid);
const typed = await page.evaluate(() => { const f = document.getElementById('fld'); let ev = 0; f.addEventListener('input', () => ev++); window.__turbobox.setField('new text', false); return { v: f.value, ev }; });
check('the text from the app is put in and the page is told', typed.v === 'new text' && typed.ev === 1, typed);
await page.evaluate(() => window.__turbobox.configure({ privateDisplay: false }));

// the layout is fitted to the physical width of the view over the browser's own pixel ratio
await page.evaluate(() => window.__turbobox.setViewPx(640));
await page.waitForTimeout(50);
const fitMeta = await page.evaluate(() => document.querySelector('meta[name=viewport]').content);
const dprNow = await page.evaluate(() => window.devicePixelRatio);
check('the viewport scale fits physical pixels / pixel ratio', fitMeta === 'width=1280, initial-scale=' + (640 / dprNow / 1280).toFixed(3), fitMeta);
await page.evaluate(() => { window.__turbobox.configure({ dsf: 2 }); window.__turbobox.setViewPx(640); });
await page.waitForTimeout(50);
const fitMeta2 = await page.evaluate(() => document.querySelector('meta[name=viewport]').content);
check('the pixel ratio the app supplies is used for the fit', fitMeta2 === 'width=1280, initial-scale=0.250', fitMeta2);
await page.evaluate(() => { window.__turbobox.configure({ dsf: 1 }); window.__turbobox.setViewPx(0); });

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
// a site that starts its video muted: one call turns the sound on
const um = await page.evaluate(() => { const a = document.getElementById('a'); a.muted = true; const before = window.__turbobox.realMuted(a); const n = window.__turbobox.unmuteMedia(); return { before, after: window.__turbobox.realMuted(a), n, page: a.muted }; });
check('unmuteMedia switches a muted element on', um.before === true && um.after === false && um.page === false && um.n >= 1, um);

// the page always looks visible (a video keeps playing with the screen off), and desktop Chrome's window.chrome exists
const vis = await page.evaluate(() => {
  const heard = [];
  document.addEventListener('visibilitychange', () => heard.push('page'));
  document.dispatchEvent(new Event('visibilitychange'));
  const on = { hidden: document.hidden, state: document.visibilityState, focus: document.hasFocus(), heard: heard.slice(), chrome: typeof window.chrome, load: typeof (window.chrome && window.chrome.loadTimes), app: typeof (window.chrome && window.chrome.app) };
  window.__turbobox.configure({ keepVisible: false });
  document.dispatchEvent(new Event('visibilitychange'));
  const off = { heard: heard.slice() };
  window.__turbobox.configure({ keepVisible: true });
  return { on, off };
});
check('the page sees itself as visible and hears no visibilitychange', vis.on.hidden === false && vis.on.state === 'visible' && vis.on.focus === true && vis.on.heard.length === 0, vis.on);
check('with keepVisible off the events come through again', vis.off.heard.length === 1, vis.off);
check('window.chrome exists like a desktop Chrome', vis.on.chrome === 'object' && vis.on.load === 'function' && vis.on.app === 'object', vis.on);
// a video that goes full screen tells the native side its shape, so the split can give the empty space to other pages
posts.length = 0;
await page.evaluate(async () => {
  const c = document.createElement('canvas'); c.width = 640; c.height = 360;
  const g = c.getContext('2d'); setInterval(() => { g.fillStyle = '#' + Math.floor(Math.random() * 0xffffff).toString(16).padStart(6, '0'); g.fillRect(0, 0, 640, 360); }, 50);
  const v = document.createElement('video'); v.id = 'fsv'; v.muted = true; v.srcObject = c.captureStream(20); document.body.appendChild(v);
  await v.play();
  await new Promise(r => setTimeout(r, 300));
  Object.defineProperty(document, 'fullscreenElement', { get: () => v, configurable: true });
  document.dispatchEvent(new Event('fullscreenchange'));
  Object.defineProperty(document, 'fullscreenElement', { get: () => null, configurable: true });
  document.dispatchEvent(new Event('fullscreenchange'));
});
await page.waitForTimeout(900);
const fs = posts.filter(p => p[0] === 'fsratio').map(p => Number(p[1]));
check('a full-screen video reports its shape (16:9), and 0 when it leaves', fs.length >= 2 && Math.abs(fs[0] - 640 / 360) < 0.01 && fs[fs.length - 1] === 0, fs);

// a resize (entering the Mini window) must not move a long feed: the scroll position from before it is put back
await page.evaluate(() => window.scrollTo(0, 1500));
await page.waitForTimeout(500);
await page.evaluate(() => { window.scrollTo(0, 300); window.__turbobox.resized(); });
await page.waitForTimeout(250);
const sy = await page.evaluate(() => Math.round(window.scrollY));
check('the scroll position is put back after a resize', sy === 1500, sy);
await page.evaluate(() => window.scrollTo(0, 0));
await page.waitForTimeout(1700);

// media controls for the Mini window: play / pause, seek, and the state is reported
await page.waitForTimeout(300);
posts.length = 0;
const mc = await page.evaluate(async () => {
  const old = document.getElementById('fsv'); if (old) { old.pause(); old.remove(); }
  const a = document.getElementById('a');
  a.pause(); a.currentTime = 0;
  const T = window.__turbobox;
  await new Promise(r => setTimeout(r, 400));
  T.mediaToggle();
  await new Promise(r => setTimeout(r, 400));
  const playing = !a.paused;
  const t0 = a.currentTime;
  T.mediaSeek(1);
  await new Promise(r => setTimeout(r, 100));
  const jumped = a.currentTime - t0;
  T.mediaToggle();
  await new Promise(r => setTimeout(r, 400));
  return { playing, jumped, pausedAfter: a.paused };
});
await page.waitForTimeout(300);
const said = posts.filter(p => p[0] === 'media').map(p => p[1]).join();
check('media toggle plays and pauses, seek jumps, and the state is reported', mc.playing && mc.jumped > 0.8 && mc.pausedAfter && said.split(',').pop() === '0', { mc, said });

console.log(fails ? `${fails} FAILED` : 'all ok');
await b.close();
process.exit(fails ? 1 : 0);
