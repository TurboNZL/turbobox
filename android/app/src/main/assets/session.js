/*
 * Injected into every page of the embedded web session (document start, and again after load; it is
 * idempotent). The native side talks to it through window.__turbobox and hears back through
 * window.TurboBoxBridge.post(type, json).
 *
 *  - desktop emulation: desktop-sized layout viewport (scalable: "zoom") and desktop navigator properties, a desktop
 *                       Chrome's window.chrome, and a page that is always "visible" (a video keeps playing with the screen off)
 *  - virtual mic:       audio captured natively from a chosen input device, played into the page as its microphone
 *  - mute:              switch the outgoing audio off and on without touching the page's own UI
 *  - volume:            0-200% gain on the other person's voice (only routed when it is not 100%)
 *  - mic volume:        0-300% gain on what the other person hears
 *  - level meter:       the live outgoing level of the call
 *  - playback test:     records a few seconds the way the call captures, plays them back, and rates the level
 *  - audio tap:         everything the page plays (the other side of a call, video and audio elements), as 24 kHz mono
 *                       PCM, for the recording buffer
 *  - automation:        find and click, type, scroll and key on the page for workflows (T.step)
 */
(function () {
  if (window.__turbobox) return;
  var T = window.__turbobox = {};
  var WIDTH = 1280;
  var cfg = { muted: false, volume: 1, aec: true, zoom: 1, micGain: 1, viewW: 0, micAllowed: true, cameraAllowed: false, localMute: false, keepVisible: true };
  var tracks = [], pcs = [];

  function post(type, data) {
    try { if (window.TurboBoxBridge) window.TurboBoxBridge.post(type, typeof data === 'string' ? data : JSON.stringify(data)); } catch (e) { /* no bridge */ }
  }

  // ---- desktop emulation ----------------------------------------------------------------
  try {
    var navProto = Object.getPrototypeOf(navigator);
    Object.defineProperty(navProto, 'platform', { get: function () { return 'Linux x86_64'; }, configurable: true });
    if (window.NavigatorUAData) {
      Object.defineProperty(window.NavigatorUAData.prototype, 'mobile', { get: function () { return false; }, configurable: true });
      Object.defineProperty(window.NavigatorUAData.prototype, 'platform', { get: function () { return 'Linux'; }, configurable: true });
    }
  } catch (e) { /* best effort */ }

  // Sign-in pages (Google's, so YouTube's; Twitch's) turn away what looks like an embedded browser. A desktop Chrome has a
  // window.chrome object and a built-in PDF viewer; WebView has neither.
  try {
    // The same members a normal page sees in desktop Chrome (no `runtime`: that is only there for extension pages).
    if (!window.chrome) Object.defineProperty(window, 'chrome', { value: {}, configurable: true, writable: true });
    var ch = window.chrome;
    if (!ch.app) ch.app = { isInstalled: false };
    if (!ch.csi) ch.csi = function () { return {}; };
    if (!ch.loadTimes) ch.loadTimes = function () { return {}; };
    if (navigator.pdfViewerEnabled === undefined) Object.defineProperty(Object.getPrototypeOf(navigator), 'pdfViewerEnabled', { get: function () { return true; }, configurable: true });
  } catch (e) { /* best effort */ }

  // ---- always visible -----------------------------------------------------------------------
  // With the screen off or another app in front the browser tells the page it is hidden, and YouTube (among others) pauses
  // its video when it hears that. The page is told "visible" instead, and the change events are not delivered. (The native
  // side also keeps the page on a private display, so this is the second line.) cfg.keepVisible false turns it all off.
  try {
    var docProto = Document.prototype;
    var hiddenDesc = Object.getOwnPropertyDescriptor(docProto, 'hidden');
    var stateDesc = Object.getOwnPropertyDescriptor(docProto, 'visibilityState');
    var wkHiddenDesc = Object.getOwnPropertyDescriptor(docProto, 'webkitHidden');
    var wkStateDesc = Object.getOwnPropertyDescriptor(docProto, 'webkitVisibilityState');
    [['hidden', hiddenDesc, false], ['visibilityState', stateDesc, 'visible'], ['webkitHidden', wkHiddenDesc, false], ['webkitVisibilityState', wkStateDesc, 'visible']].forEach(function (a) {
      var desc = a[1], value = a[2];
      if (!desc || !desc.get) return;
      Object.defineProperty(docProto, a[0], { get: function () { return cfg.keepVisible ? value : desc.get.call(this); }, configurable: true, enumerable: desc.enumerable });
    });
    var realHasFocus = Document.prototype.hasFocus;
    Document.prototype.hasFocus = function () { return cfg.keepVisible ? true : realHasFocus.call(this); };
    ['visibilitychange', 'webkitvisibilitychange'].forEach(function (n) {
      window.addEventListener(n, function (e) { if (cfg.keepVisible) e.stopImmediatePropagation(); }, true);
    });
  } catch (e) { /* best effort */ }

  // Pages that ask for a device-width layout would otherwise get the phone layout whatever the UA says.
  // The layout width is also the zoom: a narrower layout shows the same page bigger (like browser zoom,
  // everything is laid out again, nothing is just magnified).
  function viewport() {
    var m = document.querySelector('meta[name=viewport]');
    if (!m) {
      if (!document.head) return;
      m = document.createElement('meta');
      m.name = 'viewport';
      document.head.appendChild(m);
    }
    var w = Math.round(WIDTH / cfg.zoom);
    // The scale fits the layout width to the area the page really has (a small floating window, half of a split
    // screen), not to the whole screen, or a page in a small window would show only its top-left corner.
    var want = 'width=' + w + ', initial-scale=' + ((cfg.viewW || window.screen.width || w) / w).toFixed(3);
    if (m.content !== want) m.content = want;
  }
  viewport();
  window.addEventListener('orientationchange', function () { setTimeout(viewport, 300); });
  T.setZoom = function (z) { cfg.zoom = Math.max(0.5, Math.min(3, +z || 1)); viewport(); };
  T.setViewWidth = function (w) { cfg.viewW = Math.max(0, +w || 0); viewport(); setTimeout(viewport, 300); };
  new MutationObserver(viewport).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });

  // ---- audio context shared by the volume path and the meters --------------------------
  var ctx = null, gain = null;
  function ensureCtx() {
    if (!ctx) {
      var AC = window.AudioContext || window.webkitAudioContext;
      // 48 kHz so the natively captured frames need no resampling (and so no seams between them).
      try { ctx = new AC({ sampleRate: 48000 }); } catch (e) { ctx = new AC(); }
      ctx.__tbOwn = true;   // ours: the page-audio tap below must not listen to it
      gain = ctx.createGain();
      gain.connect(ctx.destination);
    }
    if (ctx.state === 'suspended') ctx.resume().catch(function () {});
    return ctx;
  }

  // ---- peer connections: remembered so the outgoing audio can be muted and replaced ---------
  ['RTCPeerConnection', 'webkitRTCPeerConnection'].forEach(function (n) {
    var P = window[n];
    if (!P) return;
    window[n] = new Proxy(P, {
      construct: function (target, args, newTarget) {
        var pc = Reflect.construct(target, args, newTarget);
        pcs.push(pc);
        return pc;
      }
    });
  });
  function livePcs() {
    pcs = pcs.filter(function (p) { return p.connectionState !== 'closed'; });
    return pcs;
  }
  function outgoing() {
    var senders = [];
    livePcs().forEach(function (p) {
      p.getSenders().forEach(function (s) { if (s.track && s.track.kind === 'audio') senders.push(s); });
    });
    return senders;
  }

  // ---- mute ----------------------------------------------------------------------------------
  // The page flips the very same `enabled` switch on its tracks (its own mute button does), so a plain
  // `track.enabled = false` can be undone by the page at any moment. Instead every outgoing audio track is
  // locked: the page still reads and writes `enabled` as usual (and its own mute keeps working), but the
  // real value underneath is always "what the page set AND NOT our mute", applied synchronously on every
  // write. Every route a track can take to the call is hooked below, so none gets past the lock.
  var enabledProp = Object.getOwnPropertyDescriptor(MediaStreamTrack.prototype, 'enabled');
  var locked = [];
  function lock(t, initialWant) {
    if (!t || t.kind !== 'audio' || t.__tbLocked || !enabledProp) return t;
    var want = initialWant === undefined ? enabledProp.get.call(t) : !!initialWant;
    Object.defineProperty(t, '__tbLocked', { value: true, configurable: true });
    Object.defineProperty(t, '__tbApply', {
      value: function () { enabledProp.set.call(t, want && !cfg.muted); }, configurable: true
    });
    Object.defineProperty(t, 'enabled', {
      configurable: true,
      get: function () { return want; },
      set: function (v) { want = !!v; enabledProp.set.call(t, want && !cfg.muted); }
    });
    t.__tbApply();
    locked.push(t);
    return t;
  }
  function unlock(t) { // a track that is not part of the call (a test), so never muted with it
    if (!t || !t.__tbLocked) return;
    delete t.enabled; delete t.__tbLocked; delete t.__tbApply;
    enabledProp.set.call(t, true);
    locked = locked.filter(function (x) { return x !== t; });
  }
  function hook(proto, name, before) {
    var o = proto && proto[name];
    if (typeof o !== 'function') return;
    proto[name] = function () {
      try { before.apply(this, arguments); } catch (e) { /* never break the page */ }
      return o.apply(this, arguments);
    };
  }
  hook(window.RTCPeerConnection && RTCPeerConnection.prototype, 'addTrack', function (t) { lock(t); });
  hook(window.RTCPeerConnection && RTCPeerConnection.prototype, 'addTransceiver', function (t) { if (t && typeof t !== 'string') lock(t); });
  hook(window.RTCRtpSender && RTCRtpSender.prototype, 'replaceTrack', function (t) { lock(t); });
  (function () {
    var oc = MediaStreamTrack.prototype.clone;
    MediaStreamTrack.prototype.clone = function () {
      var c = oc.apply(this, arguments);
      // A clone of a locked track is locked too, and starts with the page's own setting (not our mute).
      try { if (this.__tbLocked) lock(c, this.enabled); } catch (e) { /* ignore */ }
      return c;
    };
  })();
  function silent() { // is every audio track the call is sending really switched off underneath?
    var senders = outgoing();
    return { senders: senders.length, silent: senders.every(function (s) { return enabledProp.get.call(s.track) === false; }) };
  }
  function applyMute() {
    tracks = tracks.filter(function (t) { return t.readyState !== 'ended'; });
    locked = locked.filter(function (t) { return t.readyState !== 'ended'; });
    outgoing().forEach(function (s) { lock(s.track); }); // anything that reached a sender some other way
    locked.forEach(function (t) { t.__tbApply(); });
  }
  setInterval(applyMute, 500);
  T.setMute = function (m) {
    cfg.muted = !!m;
    applyMute();
  };
  // The app asks (evaluateJavascript is not slowed down like page timers are in the background) and gets
  // the real state back: are the tracks the call is sending really switched off underneath?
  T.muteStatus = function () {
    applyMute();
    var r = silent();
    return { muted: cfg.muted, senders: r.senders, silent: cfg.muted ? r.silent : true };
  };

  // ---- microphone selection (getUserMedia wrapper) ----------------------------------------------
  var md = navigator.mediaDevices;
  var orig = md && md.getUserMedia ? md.getUserMedia.bind(md) : null;

  function adopt(stream) {
    stream.getAudioTracks().forEach(function (t) { tracks.push(t); lock(t); });
  }

  // ---- virtual mic: audio captured natively from a chosen device, played into the page ----------------
  // The page cannot see the phone's individual inputs, so the app captures the chosen one itself and
  // pushes 16-bit PCM here; it is scheduled into a MediaStreamDestination that stands in for the mic.
  var vm = { label: '', on: false, dest: null, gain: null, nextFrame: 0, live: false, tracks: [] };
  function vmStream() {
    var c = ensureCtx();
    if (!vm.dest) {
      vm.dest = c.createMediaStreamDestination();
      vm.gain = c.createGain();
      vm.gain.gain.value = cfg.micGain;
      vm.gain.connect(vm.dest);
    }
    var t = vm.dest.stream.getAudioTracks()[0].clone();
    vm.tracks.push(t);
    vmCheck();
    return new MediaStream([t]);
  }
  // Tell the app when the page does / does not hold the virtual mic, so capture runs only when needed.
  function vmCheck() {
    vm.tracks = vm.tracks.filter(function (t) { return t.readyState === 'live'; });
    var want = vm.on && vm.tracks.length > 0;
    if (want !== vm.live) { vm.live = want; post('vmic', want ? 'on' : 'off'); }
  }
  setInterval(vmCheck, 1000);
  T.pcm = function (b64) {
    if (!ctx || !vm.dest) return;
    var bin = atob(b64), n = bin.length >> 1, u8 = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    var i16 = new Int16Array(u8.buffer, 0, n), f = new Float32Array(n);
    for (var j = 0; j < n; j++) f[j] = i16[j] / 32768;
    var buf = ctx.createBuffer(1, n, 48000);
    buf.copyToChannel(f, 0);
    var src = ctx.createBufferSource();
    src.buffer = buf;
    src.connect(vm.gain);
    // Schedule in whole samples, aimed at the middle of the sample: a fractional start time can round one
    // sample early and overlap the previous frame, which is an audible click every 40 ms.
    var sr = ctx.sampleRate, nowFrame = Math.round(ctx.currentTime * sr);
    if (vm.nextFrame < nowFrame + 0.03 * sr) vm.nextFrame = nowFrame + Math.round(0.16 * sr);   // (re)start with a cushion
    else if (vm.nextFrame > nowFrame + 0.6 * sr) return;       // too far behind: drop this frame to stay close to live
    src.start((vm.nextFrame + 0.5) / sr);
    vm.nextFrame += Math.round(n * sr / 48000);
  };

  // Mic volume for the page's own capture: route it through a gain stage, but only when the gain is not
  // 100% and only if the audio context is really running, so the call can never go silent because of it.
  var pageGains = [];
  function unity() { return Math.abs(cfg.micGain - 1) < 0.01; }
  async function processed(raw) {
    if (unity() || raw.getAudioTracks().length === 0) return raw;
    var c = ensureCtx();
    if (c.state !== 'running') { try { await c.resume(); } catch (e) { /* stays suspended */ } }
    if (c.state !== 'running') return raw;
    var src = c.createMediaStreamSource(raw), g = c.createGain(), dest = c.createMediaStreamDestination();
    g.gain.value = cfg.micGain;
    src.connect(g);
    g.connect(dest);
    pageGains.push(g);
    var out = dest.stream, t = out.getAudioTracks()[0], stop = t.stop.bind(t);
    t.stop = function () { stop(); raw.getTracks().forEach(function (r) { r.stop(); }); try { src.disconnect(); } catch (e) { /* gone */ } };
    raw.getVideoTracks().forEach(function (v) { out.addTrack(v); });
    return out;
  }

  // ---- device list: pages check it before asking for the mic ------------------------------------------
  // A WebView may list no microphone (or only nameless, id-less entries) until the page has captured once, and
  // web apps then say "no microphone found" without ever asking. The app picks the real input itself, so the
  // page is always shown one usable, named microphone; its id is accepted (and ignored) when it asks for it.
  var MIC_ID = 'default';
  function fakeMic() {
    var d = { deviceId: MIC_ID, kind: 'audioinput', label: vm.label || 'Default microphone', groupId: 'turbobox-mic' };
    d.toJSON = function () { return { deviceId: d.deviceId, kind: d.kind, label: d.label, groupId: d.groupId }; };
    return d;
  }
  if (md && md.enumerateDevices) {
    var origEnum = md.enumerateDevices.bind(md);
    md.enumerateDevices = async function () {
      var list = [];
      try { list = Array.prototype.slice.call(await origEnum()); } catch (e) { /* none listed */ }
      var usable = list.some(function (d) { return d.kind === 'audioinput' && d.deviceId; });
      if (usable && !vm.on) return list;
      var out = list.filter(function (d) { return d.kind !== 'audioinput' || (d.deviceId && !vm.on); });
      out.unshift(fakeMic());
      return out;
    };
  }
  // The page's device choice means nothing here (the app or the system decides), and an id it was handed from
  // the list above must not make the capture fail, so device ids are dropped from the audio constraints.
  function plainAudio(a) {
    if (!a || a === true) return a;
    var o = Object.assign({}, a);
    delete o.deviceId;
    return o;
  }

  if (orig) {
    md.getUserMedia = async function (c) {
      var stream;
      // Microphone privacy switch: refuse the way a browser does when you press "Block".
      if (c && c.audio && !cfg.micAllowed) throw new DOMException('Permission denied by TurboBox (microphone blocked for this page)', 'NotAllowedError');
      if (c && c.video && !cfg.cameraAllowed) throw new DOMException('Permission denied by TurboBox (camera blocked for this page)', 'NotAllowedError');
      if (c && c.audio && vm.on) {
        stream = vmStream();
        if (c.video) {
          try { (await orig({ video: c.video })).getVideoTracks().forEach(function (t) { stream.addTrack(t); }); } catch (e) { /* audio only */ }
        }
      } else {
        if (c && c.audio) c = Object.assign({}, c, { audio: plainAudio(c.audio) });
        if (c && c.audio && !cfg.aec) {
          var audio = c.audio === true ? {} : Object.assign({}, c.audio);
          audio.echoCancellation = false; audio.noiseSuppression = false; audio.autoGainControl = false;
          c = Object.assign({}, c, { audio: audio });
        }
        try {
          stream = await orig(c);
        } catch (e) {
          // An over-specific request (rate, channels, ...) the device cannot meet reads as "no microphone".
          if (!(c && c.audio && e && (e.name === 'OverconstrainedError' || e.name === 'NotFoundError'))) throw e;
          stream = await orig(Object.assign({}, c, { audio: true }));
        }
        if (c && c.audio) stream = await processed(stream);
      }
      adopt(stream);
      return stream;
    };
  }

  // Swap the live outgoing track (a new mic, or echo cancellation toggled) without renegotiating.
  async function recapture() {
    var senders = outgoing();
    if (!senders.length || !orig) return;
    var fresh = (await md.getUserMedia({ audio: true })).getAudioTracks()[0];
    for (var i = 0; i < senders.length; i++) {
      var old = senders[i].track;
      await senders[i].replaceTrack(fresh);
      old.stop(); // stop() does not fire 'ended', so the page does not mistake it for a lost mic
    }
    applyMute();
  }
  // Use the natively captured device (true) or the page's own default capture (false); switches a live call.
  T.setVirtualMic = async function (on) {
    // Moving from one native input to another changes only what the app feeds in: the track the call is
    // already sending stays, so nothing is renegotiated or replaced (which is what broke calls).
    if (vm.on === !!on) { vm.nextFrame = 0; return; }
    vm.on = !!on;
    vmCheck();
    await recapture();
  };
  T.setMicGain = async function (g) {
    var wasUnity = unity();
    cfg.micGain = Math.max(0, Math.min(3, +g || 0));
    pageGains.forEach(function (n) { n.gain.value = cfg.micGain; });
    if (vm.gain) vm.gain.gain.value = cfg.micGain;
    // Moving to or from 100% switches the live page capture between the plain and the gain path.
    if (!vm.on && wasUnity !== unity()) await recapture();
  };
  T.setProcessing = async function (on) {
    cfg.aec = !!on;
    if (!vm.on) await recapture();   // with the virtual mic the app applies it natively
  };

  // ---- volume: gain on the other person's voice ----------------------------------------------------
  var routed = [];
  function remoteTracks() {
    var out = [];
    livePcs().forEach(function (p) {
      p.getReceivers().forEach(function (r) {
        if (r.track && r.track.kind === 'audio' && r.track.readyState === 'live') out.push(r.track);
      });
    });
    return out;
  }
  function isRouted(t) { return routed.some(function (r) { return r.track === t; }); }
  function sweep() {
    // Only silence the page's own playback once our path is actually running, so a stuck audio
    // context can never make the call inaudible.
    if (!ctx || ctx.state !== 'running') return;
    document.querySelectorAll('audio,video').forEach(function (el) {
      var s = el.srcObject;
      if (!s || !s.getAudioTracks || !s.getAudioTracks().some(isRouted)) return;
      if (!el.__tbMuted) { el.__tbMuted = true; el.__tbWas = el.muted; }
      el.muted = true;
    });
  }
  function unroute() {
    routed.forEach(function (r) { try { r.src.disconnect(); } catch (e) { /* gone */ } });
    routed = [];
    document.querySelectorAll('audio,video').forEach(function (el) {
      if (el.__tbMuted) { el.muted = el.__tbWas; el.__tbMuted = false; }
    });
  }
  function applyVolume() {
    if (Math.abs(cfg.volume - 1) < 0.01) { if (routed.length) unroute(); return; }
    ensureCtx();
    gain.gain.value = cfg.volume;
    remoteTracks().forEach(function (t) {
      if (isRouted(t)) return;
      var src = ctx.createMediaStreamSource(new MediaStream([t]));
      src.connect(gain);
      routed.push({ track: t, src: src });
    });
    sweep();
  }
  setInterval(applyVolume, 1000);
  T.setVolume = function (v) { cfg.volume = Math.max(0, Math.min(2, +v || 0)); applyVolume(); };

  // ---- level meters -------------------------------------------------------------------------------
  function startMeter(kind, getTrack) {
    var m = { timer: null, track: null, src: null, an: null, buf: null };
    m.timer = setInterval(function () {
      var t = getTrack();
      if (t !== m.track) {
        if (m.src) { try { m.src.disconnect(); } catch (e) { /* gone */ } m.src = null; }
        m.track = t;
        if (t) {
          var c = ensureCtx();
          m.src = c.createMediaStreamSource(new MediaStream([t]));
          m.an = c.createAnalyser();
          m.an.fftSize = 4096; // ~85 ms of samples per reading, so nothing falls between ticks
          m.buf = new Float32Array(4096);
          m.src.connect(m.an);
        }
      }
      var rms = 0;
      if (t && m.an) {
        m.an.getFloatTimeDomainData(m.buf);
        var sum = 0;
        for (var i = 0; i < m.buf.length; i++) sum += m.buf[i] * m.buf[i];
        rms = Math.sqrt(sum / m.buf.length);
      }
      post(kind, rms.toFixed(4));
    }, 100);
    m.stop = function () {
      clearInterval(m.timer);
      if (m.src) { try { m.src.disconnect(); } catch (e) { /* gone */ } }
    };
    return m;
  }
  var callMeter = null;

  // The outgoing level of the call itself (muted shows as silence).
  T.setMeter = function (on) {
    if (callMeter) { callMeter.stop(); callMeter = null; }
    if (on) callMeter = startMeter('level', function () {
      return tracks.find(function (t) { return t.readyState === 'live'; });
    });
  };

  // ---- playback test: record the way the call captures (including the mic volume), play it back, rate it ----
  var lb = null;
  function rate(peak) {
    var db = peak > 0 ? 20 * Math.log10(peak) : -120;
    if (db >= -3) return 'clipping';
    if (db >= -21) return 'good';
    if (db >= -32) return 'quiet';
    return 'silent';
  }
  T.loopback = async function (sec) {
    if (lb) return;
    lb = true;
    var stream = null, sp = null, src = null, quiet = null;
    try {
      if (!orig) throw new Error('no microphone API');
      stream = await md.getUserMedia({ audio: true });
      var tr = stream.getAudioTracks()[0];
      tracks = tracks.filter(function (x) { return x !== tr; });
      unlock(tr); // a test is never part of the call, so never muted with it
      var c = ensureCtx(), chunks = [];
      src = c.createMediaStreamSource(stream);
      sp = c.createScriptProcessor(4096, 1, 1);
      quiet = c.createGain();
      quiet.gain.value = 0;
      src.connect(sp); sp.connect(quiet); quiet.connect(c.destination);
      sp.onaudioprocess = function (e) { chunks.push(new Float32Array(e.inputBuffer.getChannelData(0))); };
      post('loopback', { state: 'recording', sec: sec });
      await new Promise(function (r) { setTimeout(r, sec * 1000); });
      sp.onaudioprocess = null;
      try { src.disconnect(); sp.disconnect(); quiet.disconnect(); } catch (e) { /* gone */ }
      stream.getTracks().forEach(function (t) { t.stop(); });
      var total = 0, peak = 0, sum = 0, i, j;
      chunks.forEach(function (k) { total += k.length; });
      var buf = c.createBuffer(1, Math.max(1, total), c.sampleRate), ch = buf.getChannelData(0), at = 0;
      chunks.forEach(function (k) {
        for (j = 0; j < k.length; j++) { var v = k[j]; ch[at++] = v; var a = Math.abs(v); if (a > peak) peak = a; sum += v * v; }
      });
      var rms = total ? Math.sqrt(sum / total) : 0, result = { peak: peak, rms: rms, verdict: rate(peak) };
      var play = c.createBufferSource();
      play.buffer = buf;
      play.connect(c.destination);
      play.onended = function () { lb = null; post('loopback', Object.assign({ state: 'done' }, result)); };
      post('loopback', Object.assign({ state: 'playing' }, result));
      play.start();
    } catch (e) {
      lb = null;
      if (stream) stream.getTracks().forEach(function (t) { t.stop(); });
      post('loopback', { state: 'error', message: (e && e.name ? e.name + ': ' : '') + (e && e.message || e) });
    }
  };


  // ---- audio tap: what the page plays, for the recording buffer ----------------------------------------
  // The other side of a call (the receivers' tracks) and every <audio>/<video> element that plays a normal
  // source are mixed and sent to the app as 24 kHz mono 16-bit PCM. Nothing is routed to the speaker by this
  // path (the processor is wired to a silent gain), so playback is not touched. Not covered: audio that a page
  // draws itself with Web Audio, and protected (DRM) media, which the browser will not hand out.
  var media; // every media element that has played (filled by the local mute code below)
  var tap = { on: false, sp: null, quiet: null, nodes: [], pend: [], pendLen: 0, timer: 0 };
  function tapKnown(t) { return tap.nodes.some(function (n) { return n.track === t; }); }
  // What the page makes audible: an element it muted or turned to volume 0 (Twitch keeps an ad's element silent while the
  // stream plays, and captureStream / a media source node ignore that) is not part of what the listener hears.
  function elAudible(el) {
    // what the page set: our own copy while local mute holds the real property at true, the real property otherwise
    var muted = cfg.localMute && el.__tbWant !== undefined ? el.__tbWant : mutedDesc.get.call(el);
    return !muted && el.volume > 0;
  }
  // el: the element a track came from (null for a call's track)
  function tapAdd(track, el) {
    try {
      var c = ensureCtx(), src = c.createMediaStreamSource(new MediaStream([track])), g = c.createGain();
      src.connect(g); g.connect(tap.sp);
      tap.nodes.push({ track: track, src: src, g: g, el: el });
    } catch (e) { /* this track cannot be captured */ }
  }
  function tapSweep() {
    if (!tap.on || !tap.sp) return;
    tap.nodes = tap.nodes.filter(function (n) {
      if (n.track.readyState !== 'ended') return true;
      try { n.src.disconnect(); n.g.disconnect(); } catch (e) { /* gone */ }
      return false;
    });
    tap.nodes.forEach(function (n) { if (n.el) n.g.gain.value = elAudible(n.el) ? 1 : 0; });
    var rt = remoteTracks();
    rt.forEach(function (t) { if (!tapKnown(t)) tapAdd(t); });
    var els = Array.prototype.slice.call(document.querySelectorAll('audio,video'));
    (media || []).forEach(function (el) { if (els.indexOf(el) < 0) els.push(el); });  // also ones never added to the page
    els.forEach(function (el) {
      if (el.__tbSrc) { // routed through Web Audio by local mute: tapped from its source node, not from the element
        if (el.__tbTapSp !== tap.sp) {
          try {
            el.__tbTapG = ensureCtx().createGain();
            el.__tbSrc.connect(el.__tbTapG); el.__tbTapG.connect(tap.sp); el.__tbTapSp = tap.sp;
          } catch (e) { /* already connected */ }
        }
        if (el.__tbTapG) el.__tbTapG.gain.value = elAudible(el) ? 1 : 0;
        return;
      }
      if (el.srcObject && typeof MediaStream !== 'undefined' && el.srcObject instanceof MediaStream) {
        // a call's stream is known already (its tracks were added above); any other stream (Twitch plays its live stream
        // like this) is tapped from its own tracks
        el.srcObject.getAudioTracks().forEach(function (t) { if (t.readyState === 'live' && !tapKnown(t)) tapAdd(t, el); });
        return;
      }
      var cap = el.__tbCap, capKey = (el.srcObject ? 'o' : '') + (el.currentSrc || '');
      // a source that changed under the same element (an ad, a quality switch) gets a fresh capture
      if (cap && (el.__tbCapKey !== capKey || (cap.getAudioTracks().length && !cap.getAudioTracks().some(function (t) { return t.readyState === 'live'; })))) cap = el.__tbCap = null;
      if (!cap) {
        el.__tbCapKey = capKey;
        try { cap = el.__tbCap = (el.captureStream || el.mozCaptureStream).call(el); } catch (e) { cap = el.__tbCap = null; }
      }
      if (!cap) return;
      cap.getAudioTracks().forEach(function (t) { if (t.readyState === 'live' && !tapKnown(t)) tapAdd(t, el); });
    });
  }
  function tapFlush(force) {
    if (!tap.pendLen || (!force && tap.pendLen < 2048)) return;
    var out = new Uint8Array(tap.pendLen * 2), at = 0;
    tap.pend.forEach(function (i16) {
      for (var i = 0; i < i16.length; i++, at += 2) { out[at] = i16[i] & 255; out[at + 1] = (i16[i] >> 8) & 255; }
    });
    tap.pend = []; tap.pendLen = 0;
    var bin = '';
    for (var k = 0; k < out.length; k += 8192) bin += String.fromCharCode.apply(null, out.subarray(k, k + 8192));
    post('audio', btoa(bin));
  }
  // Two ways to hear the page, never both at once (that would play everything twice in the recording):
  //  A  tracks and media elements (above): exact, but blind to sound a page makes with its own Web Audio graph (a call
  //     app that plays the other side through Web Audio, a player that routes its video through a processor).
  //  B  the page's own audio contexts, tapped just before their output (and before local mute).
  // A is used while it has sound; when A has been silent for a few seconds and B has sound, B is used.
  var liveCtxs = [], loud = { A: 0, B: 0 };
  function deliver(which, i16, rms) {
    var now = Date.now();
    if (rms > 0.003) loud[which] = now;
    var useB = now - loud.A > 3000 && now - loud.B < 3000;
    if ((which === 'B') !== useB) return;
    tap.pend.push(i16); tap.pendLen += i16.length;
    tapFlush(false);
  }
  function attachPageTap(c) {
    if (!tap.on || c.__tbOwn || c.__tbB || !c.__tbPre) return;
    try {
      var sp = c.createScriptProcessor(4096, 1, 1), q = c.createGain(), st = { pos: 0 };
      q.gain.value = 0;
      origConnect.call(c.__tbPre, sp);
      origConnect.call(sp, q);
      origConnect.call(q, c.destination);
      sp.onaudioprocess = function (e) {
        var inp = e.inputBuffer.getChannelData(0), ratio = c.sampleRate / 24000, out = [], pos = st.pos, sq = 0;
        while (pos < inp.length - 1) {
          var k = Math.max(0, Math.floor(pos)), f = Math.max(0, pos - k), v = inp[k] * (1 - f) + inp[k + 1] * f;
          v = Math.max(-1, Math.min(1, v));
          sq += v * v;
          out.push(v < 0 ? v * 32768 : v * 32767);
          pos += ratio;
        }
        st.pos = pos - inp.length;
        deliver('B', Int16Array.from(out), Math.sqrt(sq / Math.max(1, out.length)));
      };
      c.__tbB = { sp: sp, q: q };
    } catch (e) { /* a closed context */ }
  }
  function detachPageTap(c) {
    var b = c.__tbB;
    if (!b) return;
    c.__tbB = null;
    b.sp.onaudioprocess = null;
    try { origDisconnect.call(c.__tbPre, b.sp); origDisconnect.call(b.sp); origDisconnect.call(b.q); } catch (e) { /* gone */ }
  }
  T.setTap = function (on) {
    on = !!on;
    if (on === tap.on) return;
    tap.on = on;
    if (on) {
      var c = ensureCtx();
      tap.sp = c.createScriptProcessor(4096, 1, 1);
      tap.quiet = c.createGain();
      tap.quiet.gain.value = 0;
      tap.sp.connect(tap.quiet);
      tap.quiet.connect(c.destination);
      var step = Math.max(1, Math.round(c.sampleRate / 24000));
      tap.sp.onaudioprocess = function (e) {
        var inp = e.inputBuffer.getChannelData(0), n = Math.floor(inp.length / step), i16 = new Int16Array(n), sq = 0;
        for (var i = 0; i < n; i++) {
          var sum = 0;
          for (var j = 0; j < step; j++) sum += inp[i * step + j];
          var v = Math.max(-1, Math.min(1, sum / step));
          sq += v * v;
          i16[i] = v < 0 ? v * 32768 : v * 32767;
        }
        deliver('A', i16, Math.sqrt(sq / Math.max(1, n)));
      };
      liveCtxs.forEach(attachPageTap);
      tapSweep();
      tap.timer = setInterval(tapSweep, 1000);
    } else {
      clearInterval(tap.timer);
      tapFlush(true);
      tap.nodes.forEach(function (n) { try { n.src.disconnect(); n.g.disconnect(); } catch (e) { /* gone */ } });
      tap.nodes = [];
      if (tap.sp) { tap.sp.onaudioprocess = null; try { tap.sp.disconnect(); tap.quiet.disconnect(); } catch (e) { /* gone */ } }
      tap.sp = null; tap.quiet = null;
      liveCtxs.forEach(detachPageTap);
    }
  };

  // ---- automation: workflow steps ----------------------------------------------------------------------
  // The app calls T.step({type, target, text, number}) and retries it until it answers {ok:true}. A target is
  // "css:SELECTOR" or "text:WORDS" (visible text, aria-label, title, placeholder or button value). Same-origin
  // frames and open shadow roots are searched too. Clicks are the usual pointer, mouse and click events.
  function docsOf(root, out) {
    out.push(root);
    try {
      root.querySelectorAll('iframe,frame').forEach(function (f) { try { if (f.contentDocument) docsOf(f.contentDocument, out); } catch (e) { /* other origin */ } });
    } catch (e) { /* detached */ }
    return out;
  }
  function deepQuery(root, sel, out) {
    try { root.querySelectorAll(sel).forEach(function (n) { out.push(n); }); } catch (e) { /* bad selector */ }
    root.querySelectorAll('*').forEach(function (n) { if (n.shadowRoot) deepQuery(n.shadowRoot, sel, out); });
    return out;
  }
  function isVisible(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    var w = el.ownerDocument.defaultView || window, cs = w.getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none';
  }
  var TEXT_SEL = 'button,a,[role=button],[role=link],[role=menuitem],[role=tab],[role=option],input,textarea,select,label,summary,li,h1,h2,h3,h4,td,th,span,div,p';
  function labelsOf(el) {
    var out = [];
    ['aria-label', 'title', 'placeholder', 'alt'].forEach(function (a) { var v = el.getAttribute && el.getAttribute(a); if (v) out.push(v); });
    if (el.tagName === 'INPUT' && /^(button|submit|reset)$/i.test(el.type) && el.value) out.push(el.value);
    var t = (el.innerText || el.textContent || '').trim();
    if (t) out.push(t);
    return out.map(function (x) { return x.replace(/\s+/g, ' ').trim().toLowerCase(); });
  }
  function find(target) {
    var m = /^(css|text):([\s\S]*)$/.exec(target || '');
    if (!m) return null;
    // A visible match always beats a hidden one; within each group the lowest score wins.
    var best = null, bestScore = Infinity, bestVisible = false;
    function offer(el, score, vis) {
      if ((vis && !bestVisible) || (vis === bestVisible && score < bestScore)) { best = el; bestScore = score; bestVisible = vis; }
    }
    docsOf(document, []).forEach(function (d) {
      if (m[1] === 'css') {
        deepQuery(d, m[2], []).forEach(function (el) { offer(el, 0, isVisible(el)); });
        return;
      }
      var want = m[2].trim().replace(/\s+/g, ' ').toLowerCase();
      if (!want) return;
      deepQuery(d, TEXT_SEL, []).forEach(function (el) {
        var labels = labelsOf(el), score = Infinity;
        labels.forEach(function (l) {
          if (l === want) score = Math.min(score, 0);
          else if (l.length <= want.length + 40 && l.indexOf(want) >= 0) score = Math.min(score, 1 + (l.length - want.length));
        });
        if (score === Infinity) return;
        if (/^(BUTTON|A|INPUT|SELECT|TEXTAREA)$/.test(el.tagName) || /^(button|link|menuitem|tab|option)$/.test(el.getAttribute('role') || '')) score -= 0.5;
        offer(el, score, isVisible(el));
      });
    });
    return best ? { el: best, visible: bestVisible } : null;
  }
  function clickEl(el) {
    if (el.disabled) return 'the target is disabled';
    try { el.scrollIntoView({ block: 'center', inline: 'center' }); } catch (e) { /* ignore */ }
    var r = el.getBoundingClientRect(), view = el.ownerDocument.defaultView || window;
    var o = { bubbles: true, cancelable: true, composed: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, view: view, button: 0 };
    var P = view.PointerEvent;
    if (P) el.dispatchEvent(new P('pointerdown', Object.assign({ pointerType: 'mouse', isPrimary: true }, o)));
    el.dispatchEvent(new MouseEvent('mousedown', o));
    if (el.focus) el.focus();
    if (P) el.dispatchEvent(new P('pointerup', Object.assign({ pointerType: 'mouse', isPrimary: true }, o)));
    el.dispatchEvent(new MouseEvent('mouseup', o));
    el.click();
    return '';
  }
  function setValue(el, text) {
    var tag = el.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA') {
      var proto = tag === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      var d = Object.getOwnPropertyDescriptor(proto, 'value');
      el.focus();
      if (d && d.set) d.set.call(el, text); else el.value = text;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return '';
    }
    if (tag === 'SELECT') {
      var want = text.toLowerCase(), hit = null;
      Array.prototype.forEach.call(el.options, function (op) {
        if (!hit && (op.value.toLowerCase() === want || op.text.trim().toLowerCase() === want)) hit = op;
      });
      if (!hit) return 'no option called "' + text + '"';
      el.value = hit.value;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return '';
    }
    if (el.isContentEditable) {
      el.focus();
      el.textContent = text;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      return '';
    }
    return 'the target is not something you can type in';
  }
  var KEY_CODES = { Enter: 13, Escape: 27, Tab: 9, Backspace: 8, ' ': 32, ArrowLeft: 37, ArrowUp: 38, ArrowRight: 39, ArrowDown: 40, Delete: 46, Home: 36, End: 35, PageUp: 33, PageDown: 34 };
  function pressKey(name) {
    var el = document.activeElement || document.body, kc = KEY_CODES[name] || (name.length === 1 ? name.toUpperCase().charCodeAt(0) : 0);
    var o = { key: name, code: name.length === 1 ? 'Key' + name.toUpperCase() : name, keyCode: kc, which: kc, bubbles: true, cancelable: true, composed: true };
    var go = el.dispatchEvent(new KeyboardEvent('keydown', o));
    el.dispatchEvent(new KeyboardEvent('keypress', o));
    el.dispatchEvent(new KeyboardEvent('keyup', o));
    // A synthetic Enter does not submit a form by itself.
    if (go && name === 'Enter' && el.form && el.tagName === 'INPUT') {
      try { if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); } catch (e) { /* ignore */ }
    }
  }
  T.step = function (s) {
    function ok(msg) { return { ok: true, msg: msg || '' }; }
    function no(msg) { return { ok: false, msg: msg }; }
    try {
      switch (s.type) {
        case 'waitfor': { var w = find(s.target); return w && w.visible ? ok('found') : no('not on the page yet: ' + s.target); }
        case 'click': {
          var c = find(s.target);
          if (!c) return no('not found: ' + s.target);
          if (!c.visible) return no('found but not visible: ' + s.target);
          var why = clickEl(c.el);
          return why ? no(why) : ok('clicked');
        }
        case 'type': {
          var t = find(s.target);
          if (!t) return no('not found: ' + s.target);
          var bad = setValue(t.el, s.text);
          return bad ? no(bad) : ok('typed');
        }
        case 'key': pressKey(s.text); return ok('pressed ' + s.text);
        case 'scroll': {
          var se = document.scrollingElement || document.documentElement, by = s.number || 600;
          if (s.text === 'top') window.scrollTo(0, 0);
          else if (s.text === 'bottom') window.scrollTo(0, se.scrollHeight);
          else window.scrollBy(0, s.text === 'up' ? -by : by);
          return ok('scrolled');
        }
        case 'js': { var r = (0, eval)(s.text); return ok(r === undefined ? '' : String(r)); }
        default: return no('unknown step ' + s.type);
      }
    } catch (e) { return no((e && e.name ? e.name + ': ' : '') + (e && e.message || e)); }
  };

  // ---- local mute: stepping away -----------------------------------------------------------------------
  // The phone stops playing the page while the recording (the audio tap above) goes on. Three ways sound leaves a
  // page, all closed here, and none of them is what the tap reads (it reads tracks and captured streams):
  //  1. media elements, in the page, in shadow roots, in same-origin frames, and ones that were never added to the page
  //     (new Audio()). Their `muted` is a lock like the call mute: the page may read and write it, the real value is
  //     "what the page set OR local mute", so a page that un-mutes itself (call apps do) cannot get through.
  //  2. Web Audio: every connection to a context's destination goes through one master gain, which local mute sets to 0.
  //  3. other frames (a call can live in an iframe): the state is passed to them with postMessage.
  var mutedDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'muted');
  media = [];
  function noteMedia(el) {
    if (media.indexOf(el) < 0) {
      media.push(el);
      if (media.length > 300) media = media.filter(function (e) { return e.isConnected || !e.paused; });
    }
    if (el.__tbWant === undefined) el.__tbWant = mutedDesc.get.call(el);
  }
  if (mutedDesc && mutedDesc.set) {
    Object.defineProperty(HTMLMediaElement.prototype, 'muted', {
      configurable: true,
      get: function () { return this.__tbWant === undefined ? mutedDesc.get.call(this) : this.__tbWant; },
      set: function (v) { noteMedia(this); this.__tbWant = !!v; if (cfg.localMute) applyLocalMute(this); else mutedDesc.set.call(this, !!v); }
    });
    hook(HTMLMediaElement.prototype, 'play', function () { noteMedia(this); if (cfg.localMute) applyLocalMute(this); });
  }
  var masters = [], origConnect = AudioNode.prototype.connect, origDisconnect = AudioNode.prototype.disconnect;
  var LiveContext = window.AudioContext || window.webkitAudioContext;
  function isLiveDestination(d) { return window.AudioDestinationNode && d instanceof AudioDestinationNode && LiveContext && d.context instanceof LiveContext; }
  // Everything a page sends to a context's destination goes: page nodes -> pre (the recorder listens here) -> master
  // (local mute closes it) -> destination.
  function preFor(ctx) {
    if (!ctx.__tbPre) {
      var m = ctx.createGain();
      m.gain.value = cfg.localMute ? 0 : 1;
      origConnect.call(m, ctx.destination);
      var pre = ctx.createGain();
      origConnect.call(pre, m);
      ctx.__tbMaster = m;
      ctx.__tbPre = pre;
      masters.push(m);
      liveCtxs.push(ctx);
      attachPageTap(ctx);
    }
    return ctx.__tbPre;
  }
  AudioNode.prototype.connect = function (dest) {
    if (!isLiveDestination(dest)) return origConnect.apply(this, arguments);
    origConnect.apply(this, [preFor(dest.context)].concat(Array.prototype.slice.call(arguments, 1)));
    return dest;
  };
  AudioNode.prototype.disconnect = function (dest) {
    if (!isLiveDestination(dest)) return origDisconnect.apply(this, arguments);
    return origDisconnect.apply(this, [preFor(dest.context)].concat(Array.prototype.slice.call(arguments, 1)));
  };
  // Why elements are not simply muted: in some browser versions an element's muted state also silences what
  // captureStream() hands the recorder. So an element that can be, is moved into Web Audio once (createMediaElementSource):
  // its sound then reaches the speaker only through the context's master gain (0 while away) and the recorder reads the
  // source node itself, before the gain. Elements that cannot be moved (a call's MediaStream, cross-origin media without
  // CORS, which Web Audio would play as silence) are muted with the lock above; for a call's stream the recorder reads
  // the track, which a muted element does not affect.
  function canRoute(el) {
    if (el.srcObject) return false;
    var src = el.currentSrc || el.src || '';
    if (!src) return false;
    if (/^(blob|data):/.test(src)) return true;
    try { return new URL(src, location.href).origin === location.origin || !!el.crossOrigin; } catch (e) { return false; }
  }
  function routeElement(el) {
    if (el.__tbSrc !== undefined) return;
    el.__tbSrc = null;
    if (!canRoute(el)) return;
    try {
      var c = ensureCtx();
      if (c.state !== 'running') return;     // a stuck context would make the page inaudible for good: do not
      var node = c.createMediaElementSource(el);
      node.connect(c.destination);           // goes through the master gain
      el.__tbSrc = node;
      if (tap.on && tap.sp) {
        el.__tbTapG = c.createGain(); el.__tbTapG.gain.value = elAudible(el) ? 1 : 0;
        node.connect(el.__tbTapG); el.__tbTapG.connect(tap.sp); el.__tbTapSp = tap.sp;
      }
    } catch (e) { el.__tbSrc = null; el.__tbPageRouted = true; }  // the page already routed it through its own Web Audio graph (which ends at our master gain), or it is not allowed
  }
  function applyLocalMute(el) {
    noteMedia(el);
    if (cfg.localMute) routeElement(el);
    // Routed by us, or by the page into a graph that ends at our master gain: the speaker is silent without muting the element,
    // which (in some browser versions) would also silence what the recorder hears.
    var silentByRoute = !!el.__tbSrc || !!el.__tbPageRouted;
    mutedDesc.set.call(el, silentByRoute ? !!el.__tbWant : (cfg.localMute ? true : !!el.__tbWant));
  }
  T.isSilenced = function (el) { return !!el.__tbSrc || !!el.__tbPageRouted || mutedDesc.get.call(el); }; // for the tests
  function localMuteSweep() {
    var all = media.slice();
    docsOf(document, []).forEach(function (d) {
      deepQuery(d, 'audio,video', []).forEach(function (el) { if (all.indexOf(el) < 0) all.push(el); });
    });
    all.forEach(applyLocalMute);
    masters.forEach(function (g) { g.gain.value = cfg.localMute ? 0 : 1; });
  }
  var lmTimer = 0;
  function relayToFrames(msg) {
    for (var i = 0; i < window.frames.length; i++) { try { window.frames[i].postMessage(msg, '*'); } catch (e) { /* gone */ } }
  }
  T.setLocalMute = function (on) {
    cfg.localMute = !!on;
    localMuteSweep();
    clearInterval(lmTimer);
    if (cfg.localMute) lmTimer = setInterval(localMuteSweep, 500);
    relayToFrames({ __tb: 'localMute', on: cfg.localMute });
  };
  // Frames: take the state from the parent, and ask for it when they appear.
  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || typeof d !== 'object' || !d.__tb) return;
    if (e.source === window.parent && d.__tb === 'localMute') T.setLocalMute(d.on);
    else if (e.source === window.parent && d.__tb === 'cfg') T.configure(d.cfg);
    else if (d.__tb === 'hello' && e.source) {
      try { e.source.postMessage({ __tb: 'localMute', on: cfg.localMute }, '*'); e.source.postMessage({ __tb: 'cfg', cfg: { muted: cfg.muted, micAllowed: cfg.micAllowed, cameraAllowed: cfg.cameraAllowed } }, '*'); } catch (x) { /* gone */ }
    }
  });
  if (window.parent !== window) { try { window.parent.postMessage({ __tb: 'hello' }, '*'); } catch (e) { /* no parent */ } }

  // ---- permission signalling ----------------------------------------------------------------------------
  // Call apps read the Permissions API and wait for it to change; after a refusal they usually keep a "blocked" state
  // until it says otherwise. So the microphone and camera report what the privacy switches say, and a change fires.
  var permStatuses = [];
  function permState(name) { return (name === 'microphone' ? cfg.micAllowed : cfg.cameraAllowed) ? 'granted' : 'denied'; }
  if (navigator.permissions && navigator.permissions.query) {
    var origQuery = navigator.permissions.query.bind(navigator.permissions);
    navigator.permissions.query = function (desc) {
      var n = desc && desc.name;
      if (n !== 'microphone' && n !== 'camera') return origQuery(desc);
      var st = new EventTarget();
      st.name = n; st.state = permState(n); st.onchange = null;
      permStatuses.push(st);
      return Promise.resolve(st);
    };
  }
  function permissionsChanged() {
    permStatuses.forEach(function (st) {
      var now = permState(st.name);
      if (now === st.state) return;
      st.state = now;
      var ev = new Event('change');
      try { if (typeof st.onchange === 'function') st.onchange(ev); } catch (e) { /* the page's handler */ }
      st.dispatchEvent(ev);
    });
  }
  T.realMuted = function (el) { return mutedDesc.get.call(el); }; // for the tests

  // ---- misc ----------------------------------------------------------------------------------------
  T.configure = function (o) {
    if (!o) return;
    if ('aec' in o) cfg.aec = !!o.aec;
    if ('zoom' in o) T.setZoom(o.zoom);
    if ('micGain' in o) { cfg.micGain = Math.max(0, Math.min(3, +o.micGain || 0)); if (vm.gain) vm.gain.gain.value = cfg.micGain; }
    if ('micLabel' in o) vm.label = String(o.micLabel || '');
    if ('virtual' in o) { vm.on = !!o.virtual; vmCheck(); }
    if ('volume' in o) T.setVolume(o.volume);
    if ('muted' in o) T.setMute(o.muted);
    if ('micAllowed' in o) cfg.micAllowed = !!o.micAllowed;
    if ('cameraAllowed' in o) cfg.cameraAllowed = !!o.cameraAllowed;
    if ('micAllowed' in o || 'cameraAllowed' in o) { permissionsChanged(); relayToFrames({ __tb: 'cfg', cfg: { micAllowed: cfg.micAllowed, cameraAllowed: cfg.cameraAllowed } }); }
    if ('muted' in o) relayToFrames({ __tb: 'cfg', cfg: { muted: !!o.muted } });
    if ('viewWidth' in o) T.setViewWidth(o.viewWidth);
    if ('localMute' in o) T.setLocalMute(o.localMute);
    if ('tap' in o) T.setTap(o.tap);
    if ('keepVisible' in o) cfg.keepVisible = !!o.keepVisible;
  };
  // For "Page info": who is making sound on this page and which tap hears it (found by looking, not guessing)
  function audioReport() {
    var els = Array.prototype.slice.call(document.querySelectorAll('audio,video'));
    (media || []).forEach(function (el) { if (els.indexOf(el) < 0) els.push(el); });
    var now = Date.now(), lines = [];
    els.slice(0, 6).forEach(function (el, i) {
      var cap = null;
      try { cap = el.__tbCap ? el.__tbCap.getAudioTracks().map(function (t) { return t.readyState; }).join('/') || 'none' : '-'; } catch (e) { cap = 'err'; }
      var src = el.srcObject ? 'stream' : /^blob:/.test(el.currentSrc || '') ? 'blob' : (el.currentSrc || el.src) ? 'url' : 'none';
      lines.push('  ' + el.tagName.toLowerCase() + ' ' + i + ': ' + (el.paused ? 'paused' : 'playing') + ', ' + (mutedDesc.get.call(el) ? 'muted' : 'unmuted')
        + ', volume ' + Math.round(el.volume * 100) + ', ' + src + ', ready ' + el.readyState
        + ', routed ' + (el.__tbSrc ? 'by us' : el.__tbPageRouted ? 'by page' : 'no') + ', captured tracks ' + cap
        + ', counted ' + (elAudible(el) ? 'yes' : 'no'));
    });
    if (els.length > 6) lines.push('  ... ' + (els.length - 6) + ' more');
    return (tap.on ? 'recording tap on' : 'recording tap off') + ', page audio contexts ' + liveCtxs.length
      + '\n  heard by elements/calls ' + (loud.A ? Math.round((now - loud.A) / 1000) + ' s ago' : 'never')
      + ', by page audio ' + (loud.B ? Math.round((now - loud.B) / 1000) + ' s ago' : 'never')
      + ', call tracks ' + remoteTracks().length + '\n' + (lines.join('\n') || '  no media elements');
  }
  T.info = function () {
    var vp = document.querySelector('meta[name=viewport]');
    var uad = navigator.userAgentData;
    post('info', {
      ua: navigator.userAgent, platform: navigator.platform, width: window.innerWidth, dpr: window.devicePixelRatio,
      viewport: vp ? vp.content : null, uaMobile: uad ? uad.mobile : null, host: location.host,
      mediaDevices: !!md, calls: livePcs().length, audio: audioReport()
    });
  };
  T.state = function () {
    return { masters: masters.map(function (g) { return g.gain.value; }), volume: cfg.volume, muted: cfg.muted, aec: cfg.aec, zoom: cfg.zoom, micGain: cfg.micGain, virtual: vm.on, routed: routed.length, ctx: ctx ? ctx.state : null, tracks: tracks.length };
  };
})();
