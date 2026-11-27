/*
 * Monochrome stroke-based inline SVG icon library and pure-ES5 ISO/IEC 18004 QR SVG encoder.
 *
 * Why: MyTV's design system enforces zero Unicode emojis across all TV screens and uses crisp 1.9px
 * monochrome vectors for Top-Bar tabs, critic rating badges (MC/RT), Smart-Home tactile tiles,
 * Two-Panel Settings rows, and scannable Trakt OAuth / Cloud Remote QR codes.
 */
var MyTvIcons = (function () {
  'use strict';

  var PATHS = {
    search: '<circle cx="11" cy="11" r="7"/><line x1="20" y1="20" x2="16.2" y2="16.2"/>',
    home: '<path d="M3 10.5L12 3l9 7.5"/><path d="M5.5 9.5V20a1 1 0 0 0 1 1h4v-5.5h3V21h4a1 1 0 0 0 1-1V9.5"/>',
    apps: '<rect x="3.5" y="3.5" width="4.5" height="4.5" rx="1"/><rect x="9.75" y="3.5" width="4.5" height="4.5" rx="1"/><rect x="16" y="3.5" width="4.5" height="4.5" rx="1"/><rect x="3.5" y="9.75" width="4.5" height="4.5" rx="1"/><rect x="9.75" y="9.75" width="4.5" height="4.5" rx="1"/><rect x="16" y="9.75" width="4.5" height="4.5" rx="1"/><rect x="3.5" y="16" width="4.5" height="4.5" rx="1"/><rect x="9.75" y="16" width="4.5" height="4.5" rx="1"/><rect x="16" y="16" width="4.5" height="4.5" rx="1"/>',
    gaming: '<rect x="2.5" y="6.5" width="19" height="11" rx="4.5"/><line x1="8" y1="10" x2="8" y2="14"/><line x1="6" y1="12" x2="10" y2="12"/><circle cx="15.5" cy="11" r="0.9" fill="currentColor"/><circle cx="17.5" cy="13" r="0.9" fill="currentColor"/>',
    smarthome: '<path d="M9 18h6"/><path d="M10 21h4"/><path d="M12 3a6 6 0 0 0-3.7 10.7c.7.6 1.2 1.4 1.3 2.3h4.8c.1-.9.6-1.7 1.3-2.3A6 6 0 0 0 12 3z"/>',
    lightbulb: '<path d="M9 18h6"/><path d="M10 21h4"/><path d="M12 3a6 6 0 0 0-3.7 10.7c.7.6 1.2 1.4 1.3 2.3h4.8c.1-.9.6-1.7 1.3-2.3A6 6 0 0 0 12 3z"/>',
    settings: '<circle cx="12" cy="12" r="3.2"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.08a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.08a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
    ac: '<rect x="2.5" y="4" width="19" height="8.5" rx="2"/><line x1="6" y1="9.5" x2="18" y2="9.5"/><path d="M7.5 16c-.8 1.2.8 2.4 0 3.6"/><path d="M12 16c-.8 1.2.8 2.4 0 3.6"/><path d="M16.5 16c-.8 1.2.8 2.4 0 3.6"/>',
    shower: '<path d="M5 12h14"/><path d="M12 4v8"/><path d="M7 12a5 5 0 0 1 10 0"/><line x1="8" y1="16" x2="8" y2="19.5"/><line x1="12" y1="16" x2="12" y2="20.5"/><line x1="16" y1="16" x2="16" y2="19.5"/>',
    fan: '<circle cx="12" cy="12" r="2.2"/><path d="M12 9.8c0-3.2 2.4-5.8 4.4-4.8 1.8.9.7 4.4-2.4 5.2"/><path d="M14.2 12c3.2 0 5.8 2.4 4.8 4.4-.9 1.8-4.4.7-5.2-2.4"/><path d="M12 14.2c0 3.2-2.4 5.8-4.4 4.8-1.8-.9-.7-4.4 2.4-5.2"/><path d="M9.8 12c-3.2 0-5.8-2.4-4.8-4.4.9-1.8 4.4-.7 5.2 2.4"/>',
    thermometer: '<path d="M14 14.76V5a2 2 0 0 0-4 0v9.76a4 4 0 1 0 4 0z"/><circle cx="12" cy="17.5" r="1.4" fill="currentColor"/>',
    sliders: '<line x1="4" y1="6" x2="20" y2="6"/><circle cx="9" cy="6" r="2" fill="currentColor"/><line x1="4" y1="12" x2="20" y2="12"/><circle cx="15" cy="12" r="2" fill="currentColor"/><line x1="4" y1="18" x2="20" y2="18"/><circle cx="10" cy="18" r="2" fill="currentColor"/>',
    power: '<path d="M18.36 6.64a9 9 0 1 1-12.73 0"/><line x1="12" y1="2.5" x2="12" y2="11.5"/>',
    wifi: '<path d="M5 12.55a11 11 0 0 1 14.08 0"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><circle cx="12" cy="19.5" r="1.1" fill="currentColor"/>',
    display: '<rect x="2.5" y="4" width="19" height="13" rx="2"/><line x1="8" y1="20.5" x2="16" y2="20.5"/><line x1="12" y1="17" x2="12" y2="20.5"/>',
    hdmi: '<path d="M4 7h16v6l-2.5 3h-11L4 13V7z"/><line x1="8" y1="10.5" x2="16" y2="10.5"/>',
    tv: '<rect x="2.5" y="5" width="19" height="13" rx="2"/><polyline points="8.5 2.5 12 5 15.5 2.5"/>',
    live_tv: '<rect x="2.5" y="5" width="19" height="13" rx="2"/><polygon points="10 9 15.5 11.5 10 14" fill="currentColor"/>',
    remote: '<rect x="7" y="2.5" width="10" height="19" rx="3"/><circle cx="12" cy="8.5" r="2"/><circle cx="10" cy="14" r="0.8" fill="currentColor"/><circle cx="14" cy="14" r="0.8" fill="currentColor"/><circle cx="12" cy="17.5" r="0.8" fill="currentColor"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4.5 20.5a7.5 7.5 0 0 1 15 0"/>',
    cloud: '<path d="M6.5 19h11a4.5 4.5 0 0 0 .9-8.9A6 6 0 0 0 6.8 8.3 4.2 4.2 0 0 0 6.5 19z"/>',
    sync: '<polyline points="21 4 21 10 15 10"/><polyline points="3 20 3 14 9 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L21 10"/><path d="M20.49 15a9 9 0 0 1-14.85 3.36L3 14"/>',
    key: '<circle cx="8" cy="14" r="4"/><path d="M11 11l8.5-8.5"/><path d="M16 6l2.5 2.5"/><path d="M13.5 8.5l2 2"/>',
    sparkles: '<path d="M12 3l1.7 5.3L19 10l-5.3 1.7L12 17l-1.7-5.3L5 10l5.3-1.7L12 3z"/><path d="M19 15l.7 2.3L22 18l-2.3.7L19 21l-.7-2.3L16 18l2.3-.7L19 15z"/>',
    moonlight: '<path d="M20.5 14.5A8.5 8.5 0 1 1 9.5 3.5a6.8 6.8 0 0 0 11 11z"/>',
    play: '<polygon points="6.5 4.5 19.5 12 6.5 19.5" fill="currentColor"/>',
    check: '<polyline points="4.5 12.5 9.5 17.5 19.5 6.5"/>',
    close: '<line x1="6" y1="6" x2="18" y2="18"/><line x1="18" y1="6" x2="6" y2="18"/>',
    chevron_left: '<polyline points="15 5 8 12 15 19"/>',
    chevron_right: '<polyline points="9 5 16 12 9 19"/>',
    chevron_up: '<polyline points="5 15 12 8 19 15"/>',
    chevron_down: '<polyline points="5 9 12 16 19 9"/>',
    external: '<path d="M14 4h6v6"/><line x1="10" y1="14" x2="20" y2="4"/><path d="M19 13v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h6"/>',
    bookmark: '<path d="M6 4h12a1 1 0 0 1 1 1v16l-7-4.5L5 21V5a1 1 0 0 1 1-1z"/>',
    thumb_up: '<path d="M7 10v10"/><path d="M15 5.5l-1 4.5h5.2a1.8 1.8 0 0 1 1.7 2.3l-1.5 5.5A2 2 0 0 1 17.5 19.5H7V10l4.2-6.5a1.6 1.6 0 0 1 2.9 1.1L15 5.5z"/><rect x="3" y="10" width="4" height="10" rx="1"/>',
    thumb_down: '<path d="M17 14V4"/><path d="M9 18.5l1-4.5H4.8a1.8 1.8 0 0 1-1.7-2.3l1.5-5.5A2 2 0 0 1 6.5 4.5H17V14l-4.2 6.5a1.6 1.6 0 0 1-2.9-1.1L9 18.5z"/><rect x="17" y="4" width="4" height="10" rx="1"/>',
    clock: '<circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15.5 14"/>',
    trash: '<polyline points="4 6 20 6"/><path d="M9 6V4h6v2"/><path d="M6.5 6l1 14h9l1-14"/>',
    reorder: '<polyline points="7 8 12 3 17 8"/><polyline points="7 16 12 21 17 16"/>',
    info: '<circle cx="12" cy="12" r="9"/><line x1="12" y1="11" x2="12" y2="16.5"/><circle cx="12" cy="7.8" r="0.8" fill="currentColor"/>',
    globe: '<circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="4" ry="9"/><line x1="3.5" y1="9" x2="20.5" y2="9"/><line x1="3.5" y1="15" x2="20.5" y2="15"/>',
    palette: '<circle cx="12" cy="12" r="9"/><path d="M12 3a9 9 0 0 1 0 18z" fill="currentColor"/>',
    bolt: '<polygon points="13 2.5 5 13.5 11.5 13.5 10.5 21.5 19 10.5 12.5 10.5" fill="currentColor"/>',
    sunny: '<circle cx="12" cy="12" r="4"/><line x1="12" y1="2.5" x2="12" y2="5"/><line x1="12" y1="19" x2="12" y2="21.5"/><line x1="2.5" y1="12" x2="5" y2="12"/><line x1="19" y1="12" x2="21.5" y2="12"/><line x1="5.3" y1="5.3" x2="7.1" y2="7.1"/><line x1="16.9" y1="16.9" x2="18.7" y2="18.7"/><line x1="18.7" y1="5.3" x2="16.9" y2="7.1"/><line x1="7.1" y1="16.9" x2="5.3" y2="18.7"/>',
    mc: '<rect x="3" y="3" width="18" height="18" rx="4.5" fill="currentColor" stroke="none"/><path class="mc-cutout" d="M7 16V9.2c0-.4.3-.7.7-.7h.8c.4 0 .7.2.9.6L12 13.4l2.6-4.3c.2-.4.5-.6.9-.6h.8c.4 0 .7.3.7.7V16" stroke-width="2.2" fill="none"/>',
    rt: '<path d="M12 7.2c-3.9 0-6.8 2.7-6.8 6.4 0 3.7 2.9 6.6 6.8 6.6s6.8-2.9 6.8-6.6c0-3.7-2.9-6.4-6.8-6.4z" fill="currentColor" stroke="none"/><path d="M12 7.5L10 4.5M12 7.5l2-3M12 7.5l-3.4-1.2M12 7.5l3.4-1.2" stroke="currentColor" stroke-width="1.8"/>'
  };

  /** Returns an inline SVG markup string for the named monochrome vector icon. */
  function svg(name, size, cls) {
    var d = PATHS[name] || PATHS.info;
    var s = size || 24;
    var c = cls ? ' class="svg-ico ' + cls + '"' : ' class="svg-ico"';
    return '<svg' + c + ' width="' + s + '" height="' + s + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">' + d + '</svg>';
  }

  /** Creates a DOM span containing the inline SVG icon and appends it to `parent` if given. */
  function el(name, parent, size, cls) {
    var span = document.createElement('span');
    span.className = 'ico' + (cls ? ' ' + cls : '');
    span.innerHTML = svg(name, size);
    if (parent) parent.appendChild(span);
    return span;
  }

  // MARK: - Pure ES5 QR Code Matrix Encoder (ISO/IEC 18004 Versions 1..4, Byte Mode, EC Level L)

  var GF_EXP = new Array(512), GF_LOG = new Array(256);
  (function () {
    var x = 1, i;
    for (i = 0; i < 255; i++) {
      GF_EXP[i] = x;
      GF_LOG[x] = i;
      x <<= 1;
      if (x & 0x100) x ^= 0x11d;
    }
    for (i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255];
  })();

  function gfMul(a, b) {
    return a === 0 || b === 0 ? 0 : GF_EXP[GF_LOG[a] + GF_LOG[b]];
  }

  function rsGenerator(degree) {
    var poly = [1], i, j, next;
    for (i = 0; i < degree; i++) {
      next = new Array(poly.length + 1);
      for (j = 0; j < next.length; j++) next[j] = 0;
      for (j = 0; j < poly.length; j++) {
        next[j] ^= poly[j];
        next[j + 1] ^= gfMul(poly[j], GF_EXP[i]);
      }
      poly = next;
    }
    return poly;
  }

  function rsRemainder(data, ecCount) {
    var gen = rsGenerator(ecCount);
    var res = data.slice(0), i, j, factor;
    for (i = 0; i < ecCount; i++) res.push(0);
    for (i = 0; i < data.length; i++) {
      factor = res[i];
      if (factor !== 0) {
        for (j = 0; j < gen.length; j++) res[i + j] ^= gfMul(gen[j], factor);
      }
    }
    return res.slice(data.length);
  }

  // Version configs for Error Correction Level L (1 block each): [version, size, dataBytes, ecBytes, alignPos]
  var QR_VERSIONS = [
    [1, 21, 19, 7, []],
    [2, 25, 34, 10, [6, 18]],
    [3, 29, 55, 15, [6, 22]],
    [4, 33, 80, 20, [6, 26]]
  ];

  // Format information bits for EC Level L (01) and Mask 0..7 (precomputed BCH(15,5) ^ 0x5412)
  var FORMAT_BITS_L = [0x77c4, 0x72f3, 0x7daa, 0x789d, 0x662f, 0x6318, 0x6c41, 0x6976];

  /** Encodes an ASCII/UTF-8 string (up to 78 bytes) into a boolean 2D QR module matrix, or null if too long. */
  function qrMatrix(text) {
    var bytes = typeof MyTvCrypto !== 'undefined' ? MyTvCrypto.utf8Bytes(text) : [];
    if (!bytes.length) {
      var s = String(text || '');
      for (var k = 0; k < s.length; k++) bytes.push(s.charCodeAt(k) & 0xff);
    }
    var cfg = null, vi;
    for (vi = 0; vi < QR_VERSIONS.length; vi++) {
      if (bytes.length + 2 <= QR_VERSIONS[vi][2]) { cfg = QR_VERSIONS[vi]; break; }
    }
    if (!cfg) return null;

    var size = cfg[1], dataCap = cfg[2], ecCount = cfg[3], align = cfg[4];
    var bits = [], i, j, b;
    function pushBits(val, len) {
      for (var bi = len - 1; bi >= 0; bi--) bits.push((val >>> bi) & 1);
    }
    pushBits(4, 4); // Byte mode
    pushBits(bytes.length, 8);
    for (i = 0; i < bytes.length; i++) pushBits(bytes[i], 8);
    var maxBits = dataCap * 8;
    for (i = 0; i < 4 && bits.length < maxBits; i++) bits.push(0);
    while (bits.length % 8 !== 0) bits.push(0);
    var codewords = [];
    for (i = 0; i < bits.length; i += 8) {
      b = 0;
      for (j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
      codewords.push(b);
    }
    var padIdx = 0;
    while (codewords.length < dataCap) codewords.push((padIdx++ % 2 === 0) ? 0xec : 0x11);
    var full = codewords.concat(rsRemainder(codewords, ecCount));

    var grid = [], isFunc = [];
    for (i = 0; i < size; i++) {
      grid[i] = new Array(size);
      isFunc[i] = new Array(size);
      for (j = 0; j < size; j++) { grid[i][j] = 0; isFunc[i][j] = false; }
    }
    function setFn(r, c, v) {
      if (r < 0 || r >= size || c < 0 || c >= size) return;
      grid[r][c] = v ? 1 : 0;
      isFunc[r][c] = true;
    }
    function finder(r0, c0) {
      var dr, dc, dist;
      for (dr = -1; dr <= 7; dr++) {
        for (dc = -1; dc <= 7; dc++) {
          if (r0 + dr < 0 || r0 + dr >= size || c0 + dc < 0 || c0 + dc >= size) continue;
          dist = Math.max(Math.abs(dr - 3), Math.abs(dc - 3));
          setFn(r0 + dr, c0 + dc, dist !== 2 && dist !== 4);
        }
      }
    }
    finder(0, 0);
    finder(0, size - 7);
    finder(size - 7, 0);
    for (i = 8; i < size - 8; i++) {
      setFn(6, i, i % 2 === 0);
      setFn(i, 6, i % 2 === 0);
    }
    if (align.length === 2) {
      var ar = align[1], ac = align[1], dr2, dc2;
      for (dr2 = -2; dr2 <= 2; dr2++) {
        for (dc2 = -2; dc2 <= 2; dc2++) {
          setFn(ar + dr2, ac + dc2, Math.max(Math.abs(dr2), Math.abs(dc2)) !== 1);
        }
      }
    }
    // Reserve format areas + dark module
    for (i = 0; i <= 8; i++) {
      if (i !== 6) { setFn(8, i, 0); setFn(i, 8, 0); }
    }
    for (i = 0; i < 8; i++) setFn(8, size - 1 - i, 0);
    for (i = 0; i < 7; i++) setFn(size - 1 - i, 8, 0);
    setFn(size - 8, 8, 1);

    // Place data + EC bits in standard upward/downward zigzag with mask 0 ((r + c) % 2 === 0)
    var bitIdx = 0, totalBits = full.length * 8, up = true, col, row, dx;
    for (col = size - 1; col >= 1; col -= 2) {
      if (col === 6) col--;
      for (i = 0; i < size; i++) {
        row = up ? size - 1 - i : i;
        for (dx = 0; dx < 2; dx++) {
          var cc = col - dx;
          if (!isFunc[row][cc]) {
            var bit = 0;
            if (bitIdx < totalBits) {
              bit = (full[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1;
              bitIdx++;
            }
            if (((row + cc) % 2) === 0) bit ^= 1;
            grid[row][cc] = bit;
          }
        }
      }
      up = !up;
    }

    // Write format info for EC L + Mask 0
    var fmt = FORMAT_BITS_L[0];
    for (i = 0; i <= 5; i++) setFn(8, i, (fmt >>> i) & 1);
    setFn(8, 7, (fmt >>> 6) & 1);
    setFn(8, 8, (fmt >>> 7) & 1);
    setFn(7, 8, (fmt >>> 8) & 1);
    for (i = 9; i < 15; i++) setFn(14 - i, 8, (fmt >>> i) & 1);
    for (i = 0; i < 8; i++) setFn(size - 1 - i, 8, (fmt >>> i) & 1);
    for (i = 8; i < 15; i++) setFn(8, size - 15 + i, (fmt >>> i) & 1);
    setFn(size - 8, 8, 1);

    return grid;
  }

  /** Renders an ISO/IEC 18004 QR code as a crisp inline SVG with quiet zone. */
  function qrSvg(text, px) {
    var m = qrMatrix(text);
    if (!m) return '';
    var n = m.length, q = 2, vb = n + q * 2, s = px || 180, r, c, rects = [];
    for (r = 0; r < n; r++) {
      for (c = 0; c < n; c++) {
        if (m[r][c]) rects.push('<rect x="' + (c + q) + '" y="' + (r + q) + '" width="1" height="1"/>');
      }
    }
    return '<svg width="' + s + '" height="' + s + '" viewBox="0 0 ' + vb + ' ' + vb + '" shape-rendering="crispEdges">' +
      '<rect width="' + vb + '" height="' + vb + '" rx="2" fill="#FFFFFF"/>' +
      '<g fill="#121622">' + rects.join('') + '</g></svg>';
  }

  return {
    svg: svg,
    el: el,
    qrMatrix: qrMatrix,
    qrSvg: qrSvg,
    has: function (name) { return Object.prototype.hasOwnProperty.call(PATHS, name); }
  };
})();
