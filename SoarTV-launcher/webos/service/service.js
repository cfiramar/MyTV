/*
 * SoarTV's JS service on LG webOS:
 *  1. `ssap`: calls LG's local remote-control API (127.0.0.1:3000) for listLaunchPoints on newer webOS
 *     where file://<app-id>-webos origins receive WebSocket close code 1008.
 *  2. `wizDiscover` & `wizSetPilot`: direct local-LAN WiZ V2 smart-bulb discovery and control over
 *     UDP port 38899, zero PC dependency.
 *
 * Strict ES5 with Node built-ins (net, dgram, os) only so older webOS 3-4 Node runtimes run cleanly.
 */
var net = require('net');
var dgram = require('dgram');
var os = require('os');

var WIZ_PORT = 38899;

function buffer(x) {   // Buffer.from is missing before Node 4.5
  return Buffer.from ? Buffer.from(x) : new Buffer(x);
}

/** A client's text frame: masked, as the protocol asks. */
function frame(text) {
  var data = buffer(text), n = data.length;
  var mask = buffer([Math.random() * 256 | 0, Math.random() * 256 | 0, Math.random() * 256 | 0, Math.random() * 256 | 0]);
  for (var i = 0; i < n; i++) data[i] ^= mask[i % 4];
  return Buffer.concat([buffer(n < 126 ? [0x81, 0x80 | n] : [0x81, 0xfe, n >> 8, n & 0xff]), mask, data]);
}

/** done({clientKey, payload}), {refused: true} when the person said no on the TV, or null. */
function call(uri, clientKey, done) {
  var socket = net.connect(3000, '127.0.0.1'), buf = buffer([]), open = false, text = '', finished = false;
  var timer = setTimeout(function () { finish(null); }, 90000);   // time to answer the TV's prompt
  function finish(result) {
    if (finished) return;
    finished = true;
    clearTimeout(timer);
    socket.destroy();
    done(result);
  }
  function send(obj) {
    socket.write(frame(JSON.stringify(obj)));
  }
  function onMessage(m) {
    if (m.type === 'registered') {
      clientKey = m.payload['client-key'];
      send({type: 'request', id: 'call', uri: 'ssap://' + uri});
    } else if (m.id === 'call') {
      finish(m.type === 'error' ? null : {clientKey: clientKey, payload: m.payload});
    } else if (m.type === 'error') {
      finish(m.id === 'register' ? {refused: true} : null);
    }
  }
  socket.on('connect', function () {
    socket.write('GET / HTTP/1.1\r\nHost: 127.0.0.1:3000\r\nOrigin: file://\r\nUpgrade: websocket\r\n' +
        'Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: bXl0di13ZWJvcy1hcGktMQ==\r\n\r\n');
  });
  socket.on('data', function (chunk) {
    buf = Buffer.concat([buf, chunk]);
    if (!open) {
      var end = buf.toString('binary').indexOf('\r\n\r\n');
      if (end < 0) return;
      if (!/^HTTP\/1\.1 101/.test(buf.toString('utf8', 0, 12))) { finish(null); return; }
      open = true;
      buf = buf.slice(end + 4);
      var payload = {forcePairing: false, pairingType: 'PROMPT',
        manifest: {manifestVersion: 1, appVersion: '1.0', permissions: ['READ_INSTALLED_APPS']}};
      if (clientKey) payload['client-key'] = clientKey;
      send({type: 'register', id: 'register', payload: payload});
    }
    while (buf.length >= 2) {
      var fin = buf[0] & 0x80, op = buf[0] & 0x0f, n = buf[1] & 0x7f, off = 2;
      if (n === 126) {
        if (buf.length < 4) return;
        n = buf.readUInt16BE(2);
        off = 4;
      } else if (n === 127) {
        if (buf.length < 10) return;
        n = buf.readUInt32BE(6);
        off = 10;
      }
      if (buf.length < off + n) return;
      var data = buf.slice(off, off + n);
      buf = buf.slice(off + n);
      if (op === 8) { finish(null); return; }
      if (op !== 1 && op !== 0) continue;
      text += data.toString('utf8');
      if (!fin) continue;
      var m = null;
      try { m = JSON.parse(text); } catch (e) {}
      text = '';
      if (m) onMessage(m);
    }
  });
  socket.on('error', function () { finish(null); });
  socket.on('close', function () { finish(null); });
}

// MARK: - WiZ V2 Local LAN UDP (Port 38899)

function isValidIpv4(ip) {
  return typeof ip === 'string' && /^(\d{1,3}\.){3}\d{1,3}$/.test(ip.trim());
}

/** Collects active non-loopback IPv4 /24 prefixes (e.g. "192.168.1.") from os.networkInterfaces(). */
function collectLanSubnets(interfacesObj) {
  var ifaces = interfacesObj || os.networkInterfaces() || {};
  var subnets = [], names = Object.keys(ifaces), i, j, addrs, a, dot, prefix;
  for (i = 0; i < names.length; i++) {
    addrs = ifaces[names[i]] || [];
    for (j = 0; j < addrs.length; j++) {
      a = addrs[j];
      if (!a || a.internal) continue;
      if (a.family !== 'IPv4' && a.family !== 4) continue;
      if (!isValidIpv4(a.address)) continue;
      dot = a.address.lastIndexOf('.');
      if (dot > 0) {
        prefix = a.address.slice(0, dot + 1);
        if (subnets.indexOf(prefix) < 0) subnets.push(prefix);
      }
    }
  }
  if (!subnets.length) subnets.push('192.168.1.');
  return subnets;
}

/** Builds deduplicated Phase 1 UDP targets: seed IPs + active subnet broadcasts + 255.255.255.255. */
function buildDiscoveryTargets(seedIps, subnets) {
  var targets = [], i, ip;
  if (seedIps && seedIps.length) {
    for (i = 0; i < seedIps.length; i++) {
      ip = String(seedIps[i] || '').trim();
      if (isValidIpv4(ip) && targets.indexOf(ip) < 0) targets.push(ip);
    }
  }
  for (i = 0; subnets && i < subnets.length; i++) {
    ip = subnets[i] + '255';
    if (targets.indexOf(ip) < 0) targets.push(ip);
  }
  if (targets.indexOf('255.255.255.255') < 0) targets.push('255.255.255.255');
  return targets;
}

/** Builds a WiZ V2 setPilot JSON-RPC payload string. */
function buildSetPilotPayload(opts) {
  var p = opts || {};
  var on = p.on !== false;
  var params = {state: !!on};
  if (on) {
    var dim = parseInt(p.dimming, 10);
    if (!isNaN(dim)) params.dimming = Math.max(10, Math.min(100, dim));
    var scene = parseInt(p.sceneId, 10);
    var temp = parseInt(p.temp, 10);
    if (!isNaN(scene) && scene > 0) params.sceneId = scene;
    else if (!isNaN(temp) && temp >= 2200) params.temp = Math.max(2200, Math.min(6500, temp));
  }
  return JSON.stringify({id: 1, method: 'setPilot', params: params});
}

/** Parses a WiZ V2 getPilot/setPilot UDP JSON reply into a normalized bulb object, or null. */
function parseWizResponse(msgText, remoteIp) {
  var obj = null;
  try { obj = JSON.parse(msgText); } catch (e) { return null; }
  if (!obj || !obj.result || typeof obj.result !== 'object') return null;
  var res = obj.result;
  var mac = typeof res.mac === 'string' ? res.mac.trim().toLowerCase() : '';
  return {
    mac: mac,
    ip: remoteIp || '',
    on: res.state !== false,
    dimming: typeof res.dimming === 'number' ? Math.max(10, Math.min(100, Math.round(res.dimming))) : 100,
    temp: typeof res.temp === 'number' ? Math.max(2200, Math.min(6500, Math.round(res.temp))) : 2700,
    sceneId: typeof res.sceneId === 'number' ? res.sceneId : 0,
    success: res.success !== false
  };
}

/** Discovers WiZ V2 bulbs on the TV's local LAN via UDP port 38899. */
function wizDiscover(opts, done) {
  var p = opts || {};
  var port = p.port || WIZ_PORT;
  var subnets = collectLanSubnets();
  var phase1 = buildDiscoveryTargets(p.seedIps || [], subnets);
  var probe = buffer('{"id":1,"method":"getPilot","params":{}}');
  var bulbsByKey = {}, list = [], finished = false;
  var sock = dgram.createSocket('udp4');
  var phase1Timer = null, phase2Timer = null;

  function finish() {
    if (finished) return;
    finished = true;
    if (phase1Timer) clearTimeout(phase1Timer);
    if (phase2Timer) clearTimeout(phase2Timer);
    try { sock.close(); } catch (e) {}
    done({returnValue: true, bulbs: list, subnets: subnets});
  }

  sock.on('message', function (msg, rinfo) {
    var parsed = parseWizResponse(msg.toString('utf8'), rinfo && rinfo.address);
    if (!parsed) return;
    var key = parsed.mac || parsed.ip;
    if (!key) return;
    if (!bulbsByKey[key]) {
      bulbsByKey[key] = parsed;
      list.push(parsed);
    } else {
      bulbsByKey[key].ip = parsed.ip || bulbsByKey[key].ip;
      bulbsByKey[key].on = parsed.on;
      bulbsByKey[key].dimming = parsed.dimming;
      bulbsByKey[key].temp = parsed.temp;
    }
  });

  sock.on('error', function () { finish(); });

  sock.bind(0, function () {
    try { sock.setBroadcast(true); } catch (e) {}
    var i;
    for (i = 0; i < phase1.length; i++) {
      try { sock.send(probe, 0, probe.length, port, phase1[i]); } catch (e) {}
    }
    phase1Timer = setTimeout(function () {
      if (list.length > 0 || p.deepScan === false) {
        finish();
        return;
      }
      // Fallback unicast sweep (.2..60) only when broadcast/cached probes got 0 bulbs
      var s, host;
      for (s = 0; s < subnets.length; s++) {
        for (host = 2; host <= 60; host++) {
          try { sock.send(probe, 0, probe.length, port, subnets[s] + host); } catch (e) {}
        }
      }
      phase2Timer = setTimeout(finish, p.phase2TimeoutMs || 700);
    }, p.phase1TimeoutMs || 450);
  });
}

/** Sends a WiZ V2 setPilot UDP JSON-RPC datagram to target bulb IPs (or subnet broadcast). */
function wizSetPilot(opts, done) {
  var p = opts || {};
  var port = p.port || WIZ_PORT;
  var targets = [];
  var i, raw = p.targets || [];
  for (i = 0; i < raw.length; i++) {
    var ip = String(raw[i] || '').trim();
    if (isValidIpv4(ip) && targets.indexOf(ip) < 0) targets.push(ip);
  }
  if (!targets.length) {
    targets = buildDiscoveryTargets([], collectLanSubnets());
  }
  var payload = buffer(buildSetPilotPayload(p));
  var responded = 0, finished = false;
  var sock = dgram.createSocket('udp4');
  var timer = null;

  function finish() {
    if (finished) return;
    finished = true;
    if (timer) clearTimeout(timer);
    try { sock.close(); } catch (e) {}
    done({returnValue: true, ok: responded > 0, respondedCount: responded, targetCount: targets.length});
  }

  sock.on('message', function (msg, rinfo) {
    var parsed = parseWizResponse(msg.toString('utf8'), rinfo && rinfo.address);
    if (parsed && parsed.success) {
      responded++;
      if (responded >= targets.length) finish();
    }
  });
  sock.on('error', function () { finish(); });

  sock.bind(0, function () {
    try { sock.setBroadcast(true); } catch (e) {}
    for (var t = 0; t < targets.length; t++) {
      try { sock.send(payload, 0, payload.length, port, targets[t]); } catch (e) {}
    }
    timer = setTimeout(finish, p.timeoutMs || 750);
  });
}

// Register Luna methods when running inside webOS JS service runtime
if (typeof require === 'function') {
  var Service = null;
  try { Service = require('webos-service'); } catch (e) {}
  if (Service) {
    var pkgName = 'io.soartv.service';
    try { pkgName = require('./package.json').name; } catch (e) {}
    var service = new Service(pkgName);

    service.register('ssap', function (message) {
      var p = message.payload || {};
      if (p.uri !== 'com.webos.applicationManager/listLaunchPoints') {
        message.respond({returnValue: false, errorText: 'Only listLaunchPoints'});
        return;
      }
      call(p.uri, p.clientKey, function (result) {
        if (!result) message.respond({returnValue: false, errorText: 'No answer from the remote control API'});
        else {
          result.returnValue = true;
          message.respond(result);
        }
      });
    });

    service.register('wizDiscover', function (message) {
      wizDiscover(message.payload || {}, function (result) {
        message.respond(result);
      });
    });

    service.register('wizSetPilot', function (message) {
      wizSetPilot(message.payload || {}, function (result) {
        message.respond(result);
      });
    });
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    WIZ_PORT: WIZ_PORT,
    isValidIpv4: isValidIpv4,
    collectLanSubnets: collectLanSubnets,
    buildDiscoveryTargets: buildDiscoveryTargets,
    buildSetPilotPayload: buildSetPilotPayload,
    parseWizResponse: parseWizResponse,
    wizDiscover: wizDiscover,
    wizSetPilot: wizSetPilot
  };
}
