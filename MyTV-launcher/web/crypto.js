/*
 * Pure synchronous ES5 cryptographic and encoding utilities for LG webOS 3-4 and Samsung Tizen 2.3-4.
 *
 * Why: Older smart TV WebKit/Chromium engines lack SubtleCrypto on file:// origins and cannot use
 * modern async crypto APIs. Trakt PKCE (S256) and Tuya OpenAPI v2.0 (HMAC-SHA256) require RFC-compliant
 * SHA-256, HMAC-SHA256, and Base64URL encoding synchronously in strict ES5.
 */
var MyTvCrypto = (function () {
  'use strict';

  var K = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ];

  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';

  /** Encodes a JavaScript string into an array of UTF-8 byte values (0..255). */
  function utf8Bytes(str) {
    var s = String(str === undefined || str === null ? '' : str);
    var out = [], i, c, c2, cp;
    for (i = 0; i < s.length; i++) {
      c = s.charCodeAt(i);
      if (c < 0x80) {
        out.push(c);
      } else if (c < 0x800) {
        out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
      } else if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
        c2 = s.charCodeAt(++i);
        cp = 0x10000 + (((c & 0x3ff) << 10) | (c2 & 0x3ff));
        out.push(
          0xf0 | (cp >> 18),
          0x80 | ((cp >> 12) & 0x3f),
          0x80 | ((cp >> 6) & 0x3f),
          0x80 | (cp & 0x3f)
        );
      } else {
        out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
      }
    }
    return out;
  }

  function rotr(x, n) {
    return (x >>> n) | (x << (32 - n));
  }

  /** Computes SHA-256 digest of a byte array (0..255) and returns a 32-byte array. */
  function sha256BytesFromArray(input) {
    var msg = input.slice(0);
    var bitLen = msg.length * 8;
    msg.push(0x80);
    while ((msg.length % 64) !== 56) msg.push(0);

    var highBits = Math.floor(bitLen / 4294967296);
    var lowBits = bitLen >>> 0;
    msg.push(
      (highBits >>> 24) & 0xff, (highBits >>> 16) & 0xff, (highBits >>> 8) & 0xff, highBits & 0xff,
      (lowBits >>> 24) & 0xff, (lowBits >>> 16) & 0xff, (lowBits >>> 8) & 0xff, lowBits & 0xff
    );

    var h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
    var h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
    var w = new Array(64);
    var offset, i, a, b, c, d, e, f, g, h, s0, s1, ch, maj, t1, t2;

    for (offset = 0; offset < msg.length; offset += 64) {
      for (i = 0; i < 16; i++) {
        w[i] = ((msg[offset + i * 4] << 24) |
                (msg[offset + i * 4 + 1] << 16) |
                (msg[offset + i * 4 + 2] << 8) |
                msg[offset + i * 4 + 3]) >>> 0;
      }
      for (i = 16; i < 64; i++) {
        s0 = (rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)) >>> 0;
        s1 = (rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)) >>> 0;
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
      }
      a = h0; b = h1; c = h2; d = h3; e = h4; f = h5; g = h6; h = h7;
      for (i = 0; i < 64; i++) {
        s1 = (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) >>> 0;
        ch = ((e & f) ^ ((~e) & g)) >>> 0;
        t1 = (h + s1 + ch + K[i] + w[i]) >>> 0;
        s0 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) >>> 0;
        maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
        t2 = (s0 + maj) >>> 0;
        h = g; g = f; f = e; e = (d + t1) >>> 0;
        d = c; c = b; b = a; a = (t1 + t2) >>> 0;
      }
      h0 = (h0 + a) >>> 0; h1 = (h1 + b) >>> 0; h2 = (h2 + c) >>> 0; h3 = (h3 + d) >>> 0;
      h4 = (h4 + e) >>> 0; h5 = (h5 + f) >>> 0; h6 = (h6 + g) >>> 0; h7 = (h7 + h) >>> 0;
    }

    var words = [h0, h1, h2, h3, h4, h5, h6, h7];
    var digest = [];
    for (i = 0; i < 8; i++) {
      digest.push((words[i] >>> 24) & 0xff, (words[i] >>> 16) & 0xff, (words[i] >>> 8) & 0xff, words[i] & 0xff);
    }
    return digest;
  }

  function bytesToHex(bytes, upper) {
    var hex = '', i, b, s;
    for (i = 0; i < bytes.length; i++) {
      b = bytes[i] & 0xff;
      s = b.toString(16);
      if (s.length < 2) s = '0' + s;
      hex += s;
    }
    return upper ? hex.toUpperCase() : hex;
  }

  /** Computes SHA-256 digest of a UTF-8 string as a 32-byte array. */
  function sha256Bytes(str) {
    return sha256BytesFromArray(utf8Bytes(str));
  }

  /** Computes lowercase SHA-256 hex digest of a UTF-8 string. */
  function sha256Hex(str) {
    return bytesToHex(sha256Bytes(str), false);
  }

  /** Computes HMAC-SHA256 hex digest of `message` with `secret` (uppercase when `upper` is true). */
  function hmacSha256Hex(message, secret, upper) {
    var keyBytes = utf8Bytes(secret);
    if (keyBytes.length > 64) keyBytes = sha256BytesFromArray(keyBytes);
    var oKey = [], iKey = [], i;
    for (i = 0; i < 64; i++) {
      var kb = i < keyBytes.length ? keyBytes[i] : 0;
      oKey.push(kb ^ 0x5c);
      iKey.push(kb ^ 0x36);
    }
    var inner = sha256BytesFromArray(iKey.concat(utf8Bytes(message)));
    var outer = sha256BytesFromArray(oKey.concat(inner));
    return bytesToHex(outer, !!upper);
  }

  /** Encodes a byte array into unpadded URL-safe Base64 (RFC 4648 Section 5). */
  function base64Url(bytes) {
    var out = '', i, n, b0, b1, b2;
    for (i = 0; i < bytes.length; i += 3) {
      b0 = bytes[i] & 0xff;
      b1 = i + 1 < bytes.length ? bytes[i + 1] & 0xff : 0;
      b2 = i + 2 < bytes.length ? bytes[i + 2] & 0xff : 0;
      n = (b0 << 16) | (b1 << 8) | b2;
      out += B64.charAt((n >>> 18) & 63) + B64.charAt((n >>> 12) & 63);
      if (i + 1 < bytes.length) out += B64.charAt((n >>> 6) & 63);
      if (i + 2 < bytes.length) out += B64.charAt(n & 63);
    }
    return out;
  }

  /** Generates `len` cryptographically strong random bytes when available, with Math.random fallback. */
  function randomBytes(len) {
    var out = new Array(len), i;
    if (typeof window !== 'undefined' && window.crypto && window.crypto.getRandomValues && typeof Uint8Array !== 'undefined') {
      var buf = new Uint8Array(len);
      window.crypto.getRandomValues(buf);
      for (i = 0; i < len; i++) out[i] = buf[i];
      return out;
    }
    for (i = 0; i < len; i++) out[i] = Math.floor(Math.random() * 256) & 0xff;
    return out;
  }

  /** Generates an RFC 4122 Version 4 UUID string. */
  function uuidV4() {
    var b = randomBytes(16);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    var h = bytesToHex(b, false);
    return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20, 32);
  }

  return {
    utf8Bytes: utf8Bytes,
    sha256Bytes: sha256Bytes,
    sha256Hex: sha256Hex,
    hmacSha256Hex: hmacSha256Hex,
    base64Url: base64Url,
    randomBytes: randomBytes,
    uuidV4: uuidV4
  };
})();
