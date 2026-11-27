package io.soartv;

import android.content.Context;

import com.limelight.nvstream.jni.MoonBridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Pure-Java Sunshine / NVIDIA GameStream mTLS pairing & RTSP session orchestrator.
 *
 * <p><b>Why:</b> Lets SoarTV pair automatically with the user's Sunshine host (submitting the 4-digit
 * pairing PIN headlessly via {@code :8485/api/gaming/sunshine-pair}) and negotiate {@code /launch},
 * {@code /resume}, and {@code /cancel} directly inside SoarTV with zero external Moonlight UI.
 * <p><b>What:</b> Generates and persists an RFC-5280 self-signed RSA-2048 X.509 client certificate,
 * executes the 5-step AES-128-ECB + SHA-256 GameStream pairing handshake, queries {@code /serverinfo}
 * and {@code /applist}, and returns a {@link LaunchSession} ready for {@link MoonBridge#startConnection}.
 */
public final class SunshineStreamClient {

    public static final int SUNSHINE_HTTP_PORT = 47989;
    public static final int SUNSHINE_HTTPS_PORT = 47984;

    private static final int RSA_KEY_BITS = 2048;
    private static final int UNIQUE_ID_BYTES = 8;
    private static final int PAIRING_SALT_BYTES = 16;
    private static final int PAIRING_CHALLENGE_BYTES = 16;
    private static final int PAIRING_SECRET_BYTES = 16;
    private static final int AES_KEY_BYTES = 16;
    private static final int SHA256_DIGEST_BYTES = 32;
    private static final long CERT_VALIDITY_MS = 20L * 365L * 24L * 60L * 60L * 1000L;
    private static final int HTTP_CONNECT_TIMEOUT_MS = 4000;
    private static final int HTTP_READ_TIMEOUT_MS = 9000;
    private static final int PAIR_HTTP_READ_TIMEOUT_MS = 12000;
    private static final int MAX_HTTP_HEADER_BYTES = 16384;
    private static final int MAX_HTTP_BODY_BYTES = 1048576;

    private static final String FILE_UNIQUE_ID = "client_uniqueid.txt";
    private static final String FILE_PRIVATE_KEY = "client_pkcs8.key";
    private static final String FILE_CERT_DER = "client_cert.der";
    private static final String IDENTITY_SUBDIR = "sunshine_mtls";

    private static final SecureRandom RNG = new SecureRandom();
    private static final java.util.concurrent.atomic.AtomicInteger PAIR_GENERATION =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.ConcurrentHashMap<String, SSLSocketFactory> SSL_FACTORY_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private SunshineStreamClient() {}

    /**
     * Typed HTTP status failure from Sunshine's HTTPS `:47984` API so callers can distinguish
     * pairing/authorization errors (`401` / `403`) from network transport/timeout failures.
     */
    public static final class HttpStatusException extends IOException {
        public final int statusCode;

        public HttpStatusException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    /**
     * Persistent mTLS RSA-2048 client identity presented to Sunshine on port {@code 47984}.
     */
    public static final class ClientIdentity {
        public final String uniqueId;
        public final KeyPair keyPair;
        public final X509Certificate certificate;
        public final byte[] certDer;
        public final String certPem;

        public ClientIdentity(
                String uniqueId,
                KeyPair keyPair,
                X509Certificate certificate,
                byte[] certDer,
                String certPem) {
            this.uniqueId = uniqueId;
            this.keyPair = keyPair;
            this.certificate = certificate;
            this.certDer = certDer;
            this.certPem = certPem;
        }
    }

    /**
     * Immutable entry from Sunshine's {@code /applist} XML catalog.
     */
    public static final class SunshineApp {
        public final int id;
        public final String title;
        public final boolean hdrSupported;

        public SunshineApp(int id, String title, boolean hdrSupported) {
            this.id = id;
            this.title = title != null ? title.trim() : "";
            this.hdrSupported = hdrSupported;
        }
    }

    /**
     * Negotiated RTSP & AES session parameters passed to {@link MoonBridge#startConnection}.
     */
    public static final class LaunchSession {
        public final String host;
        public final String appVersion;
        public final String gfeVersion;
        public final String rtspSessionUrl;
        public final int serverCodecModeSupport;
        public final byte[] riAesKey;
        public final byte[] riAesIv;
        public final int appId;

        public LaunchSession(
                String host,
                String appVersion,
                String gfeVersion,
                String rtspSessionUrl,
                int serverCodecModeSupport,
                byte[] riAesKey,
                byte[] riAesIv,
                int appId) {
            this.host = host;
            this.appVersion = appVersion;
            this.gfeVersion = gfeVersion;
            this.rtspSessionUrl = rtspSessionUrl;
            this.serverCodecModeSupport = serverCodecModeSupport;
            this.riAesKey = riAesKey;
            this.riAesIv = riAesIv;
            this.appId = appId;
        }
    }

    public static ClientIdentity loadOrCreateIdentity(Context context) throws Exception {
        File dir = new File(context.getFilesDir(), IDENTITY_SUBDIR);
        return loadOrCreateIdentity(dir);
    }

    /**
     * Loads the persisted GameStream RSA-2048 identity from {@code storageDir}, or generates a new one.
     */
    public static synchronized ClientIdentity loadOrCreateIdentity(File storageDir) throws Exception {
        if (!storageDir.exists() && !storageDir.mkdirs()) {
            throw new IOException("Unable to create identity directory: " + storageDir);
        }
        File uidFile = new File(storageDir, FILE_UNIQUE_ID);
        File keyFile = new File(storageDir, FILE_PRIVATE_KEY);
        File certFile = new File(storageDir, FILE_CERT_DER);

        if (uidFile.isFile() && keyFile.isFile() && certFile.isFile()) {
            try {
                String uid = new String(Files.readAllBytes(uidFile.toPath()), StandardCharsets.UTF_8).trim();
                byte[] pkcs8 = Files.readAllBytes(keyFile.toPath());
                byte[] der = Files.readAllBytes(certFile.toPath());
                if (uid.length() == 16 && pkcs8.length > 0 && der.length > 0) {
                    KeyFactory kf = KeyFactory.getInstance("RSA");
                    PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
                    KeyPair kp = new KeyPair(cert.getPublicKey(), priv);
                    return new ClientIdentity(uid, kp, cert, der, encodeCertPem(der));
                }
            } catch (Exception e) {
                AppLog.w("SunshineStreamClient regenerating unreadable identity: " + e.getMessage());
            }
        }

        byte[] uidBytes = new byte[UNIQUE_ID_BYTES];
        RNG.nextBytes(uidBytes);
        String uid = bytesToHex(uidBytes).toUpperCase(Locale.ROOT);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(RSA_KEY_BITS, RNG);
        KeyPair kp = kpg.generateKeyPair();

        byte[] der = buildSelfSignedX509Der(kp, "NVIDIA GameStream Client");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        String pem = encodeCertPem(der);

        Files.write(uidFile.toPath(), uid.getBytes(StandardCharsets.UTF_8));
        Files.write(keyFile.toPath(), kp.getPrivate().getEncoded());
        Files.write(certFile.toPath(), der);

        return new ClientIdentity(uid, kp, cert, der, pem);
    }

    public static synchronized ClientIdentity resetIdentity(Context context) throws Exception {
        File dir = new File(context.getFilesDir(), IDENTITY_SUBDIR);
        return resetIdentity(dir);
    }

    /**
     * Clears persisted mTLS identity files in {@code storageDir}, rotates {@code PAIR_GENERATION},
     * clears cached {@link SSLSocketFactory} instances, and generates a fresh identity.
     */
    public static synchronized ClientIdentity resetIdentity(File storageDir) throws Exception {
        if (storageDir != null) {
            new File(storageDir, FILE_UNIQUE_ID).delete();
            new File(storageDir, FILE_PRIVATE_KEY).delete();
            new File(storageDir, FILE_CERT_DER).delete();
        }
        invalidateMtlsCacheForRepair();
        return loadOrCreateIdentity(storageDir);
    }

    private static String encodeCertPem(byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
    }

    /**
     * Pure ASN.1 DER generator for a self-signed RSA-2048 SHA-256 X.509 v3 client certificate.
     * Avoids external BouncyCastle dependencies while remaining 100% RFC-5280 compliant across
     * Desktop JDK 17+ and Android Conscrypt API 26–34.
     */
    private static byte[] buildSelfSignedX509Der(KeyPair kp, String commonName) throws Exception {
        long now = System.currentTimeMillis() - 60_000L;
        long exp = now + CERT_VALIDITY_MS;
        SimpleDateFormat utcFmt = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        utcFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        byte[] notBefore = derTag(0x17, utcFmt.format(new Date(now)).getBytes(StandardCharsets.US_ASCII));
        byte[] notAfter = derTag(0x17, utcFmt.format(new Date(exp)).getBytes(StandardCharsets.US_ASCII));

        // OID 1.2.840.113549.1.1.11 = sha256WithRSAEncryption + NULL
        byte[] sha256WithRsaOid = new byte[] {
                0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B,
                0x05, 0x00
        };
        byte[] algId = derTag(0x30, sha256WithRsaOid);

        // Name: SEQUENCE { SET { SEQUENCE { OID 2.5.4.3 (CN), UTF8String commonName } } }
        byte[] cnOid = new byte[] {0x06, 0x03, 0x55, 0x04, 0x03};
        byte[] cnVal = derTag(0x0C, commonName.getBytes(StandardCharsets.UTF_8));
        byte[] atv = derTag(0x30, concat(cnOid, cnVal));
        byte[] rdn = derTag(0x31, atv);
        byte[] x500Name = derTag(0x30, rdn);

        byte[] versionV3 = derTag(0xA0, new byte[] {0x02, 0x01, 0x02});
        byte[] serialBytes = new byte[8];
        RNG.nextBytes(serialBytes);
        serialBytes[0] &= 0x7F;
        if (serialBytes[0] == 0) serialBytes[0] = 0x01;
        byte[] serial = derTag(0x02, serialBytes);
        byte[] validity = derTag(0x30, concat(notBefore, notAfter));
        byte[] spki = kp.getPublic().getEncoded();

        byte[] tbs = derTag(0x30, concat(versionV3, serial, algId, x500Name, validity, x500Name, spki));

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] sigRaw = sig.sign();
        byte[] bitStringPayload = new byte[sigRaw.length + 1];
        bitStringPayload[0] = 0x00;
        System.arraycopy(sigRaw, 0, bitStringPayload, 1, sigRaw.length);
        byte[] sigBitString = derTag(0x03, bitStringPayload);

        return derTag(0x30, concat(tbs, algId, sigBitString));
    }

    private static byte[] derTag(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 8);
        out.write(tag);
        int len = content.length;
        if (len < 128) {
            out.write(len);
        } else if (len < 256) {
            out.write(0x81);
            out.write(len);
        } else if (len < 65536) {
            out.write(0x82);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x83);
            out.write((len >> 16) & 0xFF);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] res = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, res, pos, p.length);
            pos += p.length;
        }
        return res;
    }

    public static byte[] derivePairingAesKey(byte[] salt16, String fourDigitPin) throws Exception {
        byte[] pinBytes = fourDigitPin.getBytes(StandardCharsets.US_ASCII);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        sha256.update(salt16);
        sha256.update(pinBytes);
        return Arrays.copyOf(sha256.digest(), AES_KEY_BYTES);
    }

    public static byte[] aesEcbEncrypt(byte[] key16, byte[] plaintext) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key16, "AES"));
        return c.doFinal(plaintext);
    }

    public static byte[] aesEcbDecrypt(byte[] key16, byte[] ciphertext) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key16, "AES"));
        return c.doFinal(ciphertext);
    }

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hexChars[v >>> 4];
            out[i * 2 + 1] = hexChars[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] hexToBytes(String hex) {
        String clean = hex == null ? "" : hex.trim();
        int len = clean.length();
        if ((len & 1) != 0) {
            throw new IllegalArgumentException("Odd hex string length: " + len);
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(clean.charAt(i), 16);
            int lo = Character.digit(clean.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex char at index " + i);
            }
            out[i / 2] = (byte) ((hi << 4) + lo);
        }
        return out;
    }

    public static String extractXmlStatusCode(String xml) {
        if (xml == null) return "";
        Matcher m = Pattern.compile("status_code\\s*=\\s*\"([^\"]+)\"").matcher(xml);
        if (m.find()) {
            return m.group(1).trim();
        }
        return extractXmlTag(xml, "status_code");
    }

    public static String extractXmlTag(String xml, String tagName) {
        if (xml == null || tagName == null || tagName.isEmpty()) return "";
        String quoted = Pattern.quote(tagName);
        Matcher m = Pattern.compile("<" + quoted + ">([\\s\\S]*?)</" + quoted + ">").matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    public static List<SunshineApp> parseAppListXml(String xml) {
        if (xml == null || xml.isEmpty()) return Collections.emptyList();
        List<SunshineApp> out = new ArrayList<>();
        Matcher m = Pattern.compile("<App>([\\s\\S]*?)</App>").matcher(xml);
        while (m.find()) {
            String block = m.group(1);
            String idStr = extractXmlTag(block, "ID");
            String title = unescapeXml(extractXmlTag(block, "AppTitle"));
            String hdrStr = extractXmlTag(block, "IsHdrSupported");
            try {
                int id = Integer.parseInt(idStr);
                out.add(new SunshineApp(id, title, "1".equals(hdrStr)));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static int findTargetAppId(List<SunshineApp> apps, String desiredTitle) {
        if (apps == null || apps.isEmpty()) return -1;
        String target = desiredTitle != null ? desiredTitle.trim() : "Desktop";
        for (SunshineApp app : apps) {
            if (app.title.equalsIgnoreCase(target)) {
                return app.id;
            }
        }
        for (SunshineApp app : apps) {
            if ("Desktop".equalsIgnoreCase(app.title)) {
                return app.id;
            }
        }
        return apps.get(0).id;
    }

    private static String unescapeXml(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    private static String certSha256Hex(byte[] certDer) {
        if (certDer == null || certDer.length == 0) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return bytesToHex(md.digest(certDer));
        } catch (Exception e) {
            return Integer.toHexString(Arrays.hashCode(certDer));
        }
    }

    static void invalidateMtlsCacheForRepair() {
        PAIR_GENERATION.incrementAndGet();
        SSL_FACTORY_CACHE.clear();
    }

    static SSLSocketFactory getOrCreateMtlsSocketFactory(ClientIdentity identity) throws Exception {
        String key = (identity != null ? identity.uniqueId : "")
                + ":" + certSha256Hex(identity != null ? identity.certDer : null)
                + ":" + PAIR_GENERATION.get();
        SSLSocketFactory cached = SSL_FACTORY_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        SSLSocketFactory created = buildMtlsSocketFactory(identity);
        SSLSocketFactory existing = SSL_FACTORY_CACHE.putIfAbsent(key, created);
        return existing != null ? existing : created;
    }

    static X509ExtendedKeyManager createMtlsKeyManager(final ClientIdentity identity) {
        return new X509ExtendedKeyManager() {
            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                return "soartv-gamestream";
            }

            @Override
            public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
                return "soartv-gamestream";
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return null;
            }

            @Override
            public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
                return null;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return new X509Certificate[] {identity.certificate};
            }

            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return new String[] {"soartv-gamestream"};
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return identity.keyPair.getPrivate();
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return null;
            }
        };
    }

    private static SSLSocketFactory buildMtlsSocketFactory(final ClientIdentity identity) throws Exception {
        KeyManager km = createMtlsKeyManager(identity);

        TrustManager tm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(new KeyManager[] {km}, new TrustManager[] {tm}, RNG);
        return sc.getSocketFactory();
    }

    /**
     * Validates that a host string is strictly a private RFC-1918 LAN IPv4 address, RFC-3927 link-local
     * address, loopback address, IPv6 link-local/ULA/loopback address, or `.local` mDNS hostname.
     *
     * <p><b>Why:</b> Sunshine's GameStream pairing handshake (`:47989/pair`) speaks cleartext HTTP by protocol
     * specification while Android's global `network_security_config.xml` enforces `cleartextTrafficPermitted="false"`
     * for public internet traffic. Gating raw-socket HTTP strictly to private LAN/loopback endpoints allows any
     * DHCP-assigned home Wi-Fi IP (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`) while blocking public hosts.
     */
    public static boolean isAllowedCleartextLanHost(String host) {
        if (host == null) return false;
        String clean = host.trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) return false;
        if (clean.startsWith("[") && clean.endsWith("]") && clean.length() > 2) {
            clean = clean.substring(1, clean.length() - 1);
        }
        int zoneIdx = clean.indexOf('%');
        if (zoneIdx > 0) {
            clean = clean.substring(0, zoneIdx);
        }
        if ("localhost".equals(clean)) {
            return true;
        }
        if (clean.endsWith(".local") && clean.length() > ".local".length()) {
            String prefix = clean.substring(0, clean.length() - ".local".length());
            return prefix.matches("^[a-z0-9][a-z0-9.-]*$");
        }
        if (clean.contains(":")) {
            return "::1".equals(clean)
                    || "0:0:0:0:0:0:0:1".equals(clean)
                    || clean.startsWith("fe8")
                    || clean.startsWith("fe9")
                    || clean.startsWith("fea")
                    || clean.startsWith("feb")
                    || clean.startsWith("fc")
                    || clean.startsWith("fd");
        }
        String[] parts = clean.split("\\.");
        if (parts.length == 4) {
            int[] octets = new int[4];
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) return false;
                for (int c = 0; c < parts[i].length(); c++) {
                    if (!Character.isDigit(parts[i].charAt(c))) return false;
                }
                int val = Integer.parseInt(parts[i]);
                if (val < 0 || val > 255) return false;
                octets[i] = val;
            }
            int o0 = octets[0];
            int o1 = octets[1];
            if (o0 == 127) return true;
            if (o0 == 10) return true;
            if (o0 == 192 && o1 == 168) return true;
            if (o0 == 172 && o1 >= 16 && o1 <= 31) return true;
            if (o0 == 169 && o1 == 254) return true;
            return false;
        }
        return false;
    }

    /**
     * Executes a minimal HTTP/1.1 GET request over a raw TCP {@link Socket} to a verified LAN host.
     *
     * <p><b>Why:</b> Bypasses Android `NetworkSecurityPolicy` cleartext rejection on dynamic DHCP LAN IPs
     * (e.g. `192.168.1.60`, `192.168.1.x`, `10.0.0.x`) without weakening global HTTPS enforcement, and
     * stops reading immediately once `Content-Length` bytes arrive so Sunshine's HTTP server never stalls.
     */
    public static String rawLanHttpGet(
            String host,
            int port,
            String pathAndQuery,
            int connectTimeoutMs,
            int readTimeoutMs) throws IOException {
        String cleanHost = host != null ? host.trim() : "";
        if (!isAllowedCleartextLanHost(cleanHost)) {
            throw new IOException("Cleartext HTTP blocked for non-LAN host: " + cleanHost);
        }
        String safePath = (pathAndQuery == null || pathAndQuery.isEmpty())
                ? "/"
                : (pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(cleanHost, port), Math.max(150, connectTimeoutMs));
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(Math.max(200, readTimeoutMs));

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String req = "GET " + safePath + " HTTP/1.1\r\n"
                    + "Host: " + cleanHost + ":" + port + "\r\n"
                    + "Accept: application/xml, text/xml, */*\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            ByteArrayOutputStream rawBuf = new ByteArrayOutputStream(4096);
            byte[] chunk = new byte[4096];
            int headerEndIdx = -1;
            int contentLength = -1;
            boolean chunked = false;
            int statusCode = 200;

            while (rawBuf.size() < MAX_HTTP_HEADER_BYTES + MAX_HTTP_BODY_BYTES) {
                int n = in.read(chunk);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }
                rawBuf.write(chunk, 0, n);

                if (headerEndIdx < 0) {
                    byte[] current = rawBuf.toByteArray();
                    headerEndIdx = findHeaderDelimiterIndex(current);
                    if (headerEndIdx < 0 && current.length > MAX_HTTP_HEADER_BYTES) {
                        throw new IOException("Oversized HTTP headers from Sunshine LAN endpoint");
                    }
                    if (headerEndIdx >= 0) {
                        String headerStr = new String(current, 0, headerEndIdx, StandardCharsets.ISO_8859_1);
                        String[] lines = headerStr.split("\r\n");
                        if (lines.length > 0) {
                            String[] statusParts = lines[0].trim().split("\\s+");
                            if (statusParts.length >= 2) {
                                statusCode = parseIntSafe(statusParts[1], 200);
                            }
                        }
                        for (int i = 1; i < lines.length; i++) {
                            int colon = lines[i].indexOf(':');
                            if (colon <= 0) continue;
                            String hk = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                            String hv = lines[i].substring(colon + 1).trim();
                            if ("content-length".equals(hk)) {
                                contentLength = parseIntSafe(hv, -1);
                            } else if ("transfer-encoding".equals(hk)
                                    && hv.toLowerCase(Locale.ROOT).contains("chunked")) {
                                chunked = true;
                            }
                        }
                    }
                }

                if (headerEndIdx >= 0) {
                    if (contentLength >= 0) {
                        int bodyBytesHave = rawBuf.size() - (headerEndIdx + 4);
                        if (bodyBytesHave >= contentLength) {
                            break;
                        }
                    } else if (chunked) {
                        byte[] current = rawBuf.toByteArray();
                        if (isChunkedBodyComplete(current, headerEndIdx + 4, current.length)) {
                            break;
                        }
                    }
                }
            }

            if (headerEndIdx < 0) {
                throw new IOException("Incomplete HTTP response from Sunshine on " + cleanHost + ":" + port);
            }
            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("Sunshine HTTP " + statusCode + " on " + safePath);
            }

            byte[] all = rawBuf.toByteArray();
            int bodyOffset = headerEndIdx + 4;
            int available = Math.max(0, all.length - bodyOffset);
            if (contentLength >= 0) {
                int take = Math.min(available, contentLength);
                return new String(all, bodyOffset, take, StandardCharsets.UTF_8);
            }
            if (chunked) {
                return decodeChunkedBody(all, bodyOffset, available);
            }
            return new String(all, bodyOffset, available, StandardCharsets.UTF_8);
        }
    }

    /**
     * Queries Sunshine's unauthenticated HTTP `:47989/serverinfo` XML with a short timeout so LAN subnet
     * discovery can verify `<uniqueid>` even when the Gaming PC restarted to the Windows Lock Screen.
     */
    public static String fetchServerInfoUnauthenticatedXml(String host, int timeoutMs) throws IOException {
        return fetchServerInfoUnauthenticatedXml(host, SUNSHINE_HTTP_PORT, timeoutMs, timeoutMs);
    }

    public static String fetchServerInfoUnauthenticatedXml(String host, int port, int timeoutMs) throws IOException {
        return fetchServerInfoUnauthenticatedXml(host, port, timeoutMs, timeoutMs);
    }

    public static String fetchServerInfoUnauthenticatedXml(
            String host,
            int port,
            int connectTimeoutMs,
            int readTimeoutMs) throws IOException {
        String path = "/serverinfo?uniqueid=0123456789abcdef&uuid=" + UUID.randomUUID().toString();
        return rawLanHttpGet(host, port > 0 ? port : SUNSHINE_HTTP_PORT, path, connectTimeoutMs, readTimeoutMs);
    }

    private static int findHeaderDelimiterIndex(byte[] buf) {
        for (int i = 0; i <= buf.length - 4; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int[] findHeaderDelimiter(byte[] buf) {
        for (int i = 0; i < buf.length; i++) {
            if (i + 3 < buf.length
                    && buf[i] == '\r'
                    && buf[i + 1] == '\n'
                    && buf[i + 2] == '\r'
                    && buf[i + 3] == '\n') {
                return new int[] {i, 4};
            }
            if (i + 1 < buf.length && buf[i] == '\n' && buf[i + 1] == '\n') {
                return new int[] {i, 2};
            }
        }
        return null;
    }

    private static int findLineEnd(byte[] buf, int from, int limit) {
        for (int i = from; i < limit; i++) {
            if (buf[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    static boolean isChunkedBodyComplete(byte[] buf, int offset, int limit) {
        if (buf == null || offset < 0 || limit > buf.length) {
            return false;
        }
        int pos = offset;
        while (pos < limit) {
            int lf = findLineEnd(buf, pos, limit);
            if (lf < 0) return false;
            int lineEnd = (lf > pos && buf[lf - 1] == '\r') ? lf - 1 : lf;
            String hexLen = new String(buf, pos, lineEnd - pos, StandardCharsets.ISO_8859_1).trim();
            int semi = hexLen.indexOf(';');
            if (semi >= 0) hexLen = hexLen.substring(0, semi).trim();
            if (hexLen.isEmpty()) return false;
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(hexLen, 16);
            } catch (NumberFormatException e) {
                return false;
            }
            if (chunkSize < 0) return false;
            pos = lf + 1;
            if (chunkSize == 0) {
                while (true) {
                    int tlf = findLineEnd(buf, pos, limit);
                    if (tlf < 0) return false;
                    int tEnd = (tlf > pos && buf[tlf - 1] == '\r') ? tlf - 1 : tlf;
                    int trailerLineLen = tEnd - pos;
                    pos = tlf + 1;
                    if (trailerLineLen == 0) {
                        return true;
                    }
                }
            }
            if (chunkSize > limit - pos - 1) {
                return false;
            }
            pos += chunkSize;
            if (buf[pos] == '\r') {
                if (pos + 1 >= limit) {
                    return false;
                }
                pos += (buf[pos + 1] == '\n') ? 2 : 1;
            } else if (buf[pos] == '\n') {
                pos += 1;
            } else {
                return false;
            }
        }
        return false;
    }

    private static String decodeChunkedBody(byte[] buf, int offset, int length) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(Math.max(64, length));
        int pos = offset;
        int limit = offset + length;
        while (pos < limit) {
            int lf = findLineEnd(buf, pos, limit);
            if (lf < 0) break;
            int lineEnd = (lf > pos && buf[lf - 1] == '\r') ? lf - 1 : lf;
            String hexLen = new String(buf, pos, lineEnd - pos, StandardCharsets.ISO_8859_1).trim();
            int semi = hexLen.indexOf(';');
            if (semi >= 0) hexLen = hexLen.substring(0, semi).trim();
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(hexLen, 16);
            } catch (NumberFormatException e) {
                break;
            }
            pos = lf + 1;
            if (chunkSize <= 0) break;
            int copy = Math.min(chunkSize, limit - pos);
            decoded.write(buf, pos, copy);
            pos += copy;
            if (pos < limit && buf[pos] == '\r') pos++;
            if (pos < limit && buf[pos] == '\n') pos++;
        }
        return new String(decoded.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String httpGetXml(
            String scheme,
            String host,
            int port,
            String pathAndQuery,
            SSLSocketFactory sslFactory,
            int readTimeoutMs) throws IOException {
        if ("http".equalsIgnoreCase(scheme)) {
            return rawLanHttpGet(host, port, pathAndQuery, HTTP_CONNECT_TIMEOUT_MS, readTimeoutMs);
        }
        String cleanHost = host != null ? host.trim() : "";
        if (cleanHost.isEmpty()) {
            throw new IOException("Empty Sunshine HTTPS host");
        }
        String safePath = (pathAndQuery == null || pathAndQuery.isEmpty())
                ? "/"
                : (pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery);
        SSLSocketFactory factory = sslFactory != null
                ? sslFactory
                : (SSLSocketFactory) SSLSocketFactory.getDefault();

        try (SSLSocket sslSocket = (SSLSocket) factory.createSocket()) {
            // Pin TLSv1.2 when supported: Android 14 Conscrypt TLSv1.3 mTLS session resumption against
            // Sunshine v2026 (:47984) triggers TLSV1_ALERT_INTERNAL_ERROR on reused SSLSocketFactory instances.
            String[] supported = sslSocket.getSupportedProtocols();
            if (supported != null) {
                for (String proto : supported) {
                    if ("TLSv1.2".equals(proto)) {
                        sslSocket.setEnabledProtocols(new String[] {"TLSv1.2"});
                        break;
                    }
                }
            }
            sslSocket.setTcpNoDelay(true);
            sslSocket.connect(new InetSocketAddress(cleanHost, port), HTTP_CONNECT_TIMEOUT_MS);
            sslSocket.setSoTimeout(Math.max(200, readTimeoutMs));
            sslSocket.startHandshake();

            String hostHeader = cleanHost.contains(":") && !cleanHost.startsWith("[")
                    ? "[" + cleanHost + "]:" + port
                    : cleanHost + ":" + port;
            String req = "GET " + safePath + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "Accept: application/xml, text/xml, */*\r\n"
                    + "Accept-Encoding: identity\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = sslSocket.getOutputStream();
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            InputStream in = sslSocket.getInputStream();
            ByteArrayOutputStream rawBuf = new ByteArrayOutputStream(4096);
            byte[] chunk = new byte[4096];
            int headerEndIdx = -1;
            int headerDelimLen = 4;
            int contentLength = -1;
            boolean chunked = false;
            int statusCode = 200;

            while (rawBuf.size() < MAX_HTTP_HEADER_BYTES + MAX_HTTP_BODY_BYTES) {
                int n = in.read(chunk);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }
                rawBuf.write(chunk, 0, n);

                byte[] current = rawBuf.toByteArray();
                if (headerEndIdx < 0) {
                    int[] delim = findHeaderDelimiter(current);
                    if (delim == null) {
                        if (current.length > MAX_HTTP_HEADER_BYTES) {
                            throw new IOException("Oversized HTTPS headers from Sunshine endpoint");
                        }
                        continue;
                    }
                    headerEndIdx = delim[0];
                    headerDelimLen = delim[1];
                    String headerStr = new String(current, 0, headerEndIdx, StandardCharsets.ISO_8859_1);
                    String[] lines = headerStr.split("\r?\n");
                    if (lines.length > 0) {
                        String[] statusParts = lines[0].trim().split("\\s+");
                        if (statusParts.length >= 2) {
                            statusCode = parseIntSafe(statusParts[1], 200);
                        }
                    }
                    for (int i = 1; i < lines.length; i++) {
                        int colon = lines[i].indexOf(':');
                        if (colon <= 0) continue;
                        String hk = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                        String hv = lines[i].substring(colon + 1).trim();
                        if ("content-length".equals(hk)) {
                            contentLength = parseIntSafe(hv, -1);
                        } else if ("transfer-encoding".equals(hk)
                                && hv.toLowerCase(Locale.ROOT).contains("chunked")) {
                            chunked = true;
                        }
                    }
                }

                int bodyOffset = headerEndIdx + headerDelimLen;
                if (contentLength >= 0) {
                    if (current.length - bodyOffset >= contentLength) {
                        break;
                    }
                } else if (chunked) {
                    if (isChunkedBodyComplete(current, bodyOffset, current.length)) {
                        break;
                    }
                } else if (statusCode == 204 || statusCode == 304) {
                    break;
                }
            }

            if (headerEndIdx < 0) {
                throw new IOException("Incomplete HTTPS response from Sunshine on " + cleanHost + ":" + port);
            }
            if (statusCode < 200 || statusCode >= 300) {
                throw new HttpStatusException(statusCode, "Sunshine HTTPS " + statusCode + " on " + safePath);
            }

            byte[] all = rawBuf.toByteArray();
            int bodyOffset = headerEndIdx + headerDelimLen;
            int available = Math.max(0, all.length - bodyOffset);
            if (contentLength >= 0) {
                int take = Math.min(available, contentLength);
                return new String(all, bodyOffset, take, StandardCharsets.UTF_8);
            }
            if (chunked) {
                return decodeChunkedBody(all, bodyOffset, available);
            }
            return new String(all, bodyOffset, available, StandardCharsets.UTF_8);
        }
    }

    private static String baseQuery(ClientIdentity id) {
        return "uniqueid=" + id.uniqueId + "&uuid=" + UUID.randomUUID().toString();
    }

    public static String fetchPairedServerInfoXml(
            String host,
            ClientIdentity identity,
            SSLSocketFactory sslFactory) throws IOException {
        return fetchPairedServerInfoXml(host, SUNSHINE_HTTPS_PORT, identity, sslFactory);
    }

    static String fetchPairedServerInfoXml(
            String host,
            int httpsPort,
            ClientIdentity identity,
            SSLSocketFactory sslFactory) throws IOException {
        try {
            String xml = httpGetXml(
                    "https",
                    host,
                    httpsPort,
                    "/serverinfo?" + baseQuery(identity),
                    sslFactory,
                    HTTP_READ_TIMEOUT_MS);
            if ("1".equals(extractXmlTag(xml, "PairStatus"))) {
                return xml;
            }
            return null;
        } catch (HttpStatusException hse) {
            if (hse.statusCode == 401 || hse.statusCode == 403) {
                return null;
            }
            throw hse;
        } catch (SSLException sslRejected) {
            return null;
        }
    }

    public static boolean isPairedWithSunshine(String host, ClientIdentity identity, SSLSocketFactory sslFactory) {
        try {
            return fetchPairedServerInfoXml(host, SUNSHINE_HTTPS_PORT, identity, sslFactory) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Ensures SoarTV's client certificate is paired with Sunshine on {@code host}. If unpaired, runs the
     * 5-step GameStream pairing handshake while submitting the 4-digit PIN automatically to the
     * authenticated Laptop Gaming Agent (`POST /api/gaming/sunshine-pair`) so the user never sees a prompt.
     */
    public static boolean ensurePaired(
            final Context context,
            final String host,
            final ClientIdentity identity) throws Exception {
        return ensurePairedWithServerInfo(context, host, identity) != null;
    }

    public static String ensurePairedWithServerInfo(
            final Context context,
            final String host,
            final ClientIdentity identity) throws Exception {
        return ensurePairedWithServerInfo(
                context, host, identity, SUNSHINE_HTTP_PORT, SUNSHINE_HTTPS_PORT, GamingZone.LAPTOP_AGENT_PORT);
    }

    static String ensurePairedWithServerInfo(
            final Context context,
            final String host,
            final ClientIdentity identity,
            final int httpPort,
            final int httpsPort,
            final int agentPinPort) throws Exception {
        SSLSocketFactory sslFactory = getOrCreateMtlsSocketFactory(identity);
        String alreadyPairedXml = fetchPairedServerInfoXml(host, httpsPort, identity, sslFactory);
        if (alreadyPairedXml != null) {
            return alreadyPairedXml;
        }

        final String pin = String.format(Locale.US, "%04d", 1000 + RNG.nextInt(9000));
        byte[] salt = new byte[PAIRING_SALT_BYTES];
        RNG.nextBytes(salt);
        byte[] aesKey = derivePairingAesKey(salt, pin);

        Thread pinSubmitter = new Thread(() -> {
            try {
                Thread.sleep(150L);
                GamingZone.submitSunshinePairingPinSync(context, host, agentPinPort, pin, "SoarTV");
            } catch (InterruptedException ignored) {
            }
        }, "sunshine-auto-pin");
        pinSubmitter.setDaemon(true);
        pinSubmitter.start();

        String clientCertHex = bytesToHex(identity.certPem.getBytes(StandardCharsets.UTF_8));
        String step1Path = "/pair?" + baseQuery(identity)
                + "&devicename=roth&updateState=1&phrase=getservercert"
                + "&salt=" + bytesToHex(salt)
                + "&clientcert=" + clientCertHex;
        String step1Xml;
        try {
            step1Xml = httpGetXml("http", host, httpPort, step1Path, null, PAIR_HTTP_READ_TIMEOUT_MS);
        } catch (IOException connectErr) {
            if (GamingZone.ensureSunshineRunningOnPcSync(context, host, agentPinPort)) {
                String retryPairedXml = fetchPairedServerInfoXml(host, httpsPort, identity, sslFactory);
                if (retryPairedXml != null) {
                    return retryPairedXml;
                }
                Thread retryPinSubmitter = new Thread(() -> {
                    try {
                        Thread.sleep(150L);
                        GamingZone.submitSunshinePairingPinSync(context, host, agentPinPort, pin, "SoarTV");
                    } catch (InterruptedException ignored) {
                    }
                }, "sunshine-auto-pin-retry");
                retryPinSubmitter.setDaemon(true);
                retryPinSubmitter.start();
                step1Xml = httpGetXml("http", host, httpPort, step1Path, null, PAIR_HTTP_READ_TIMEOUT_MS);
            } else {
                throw connectErr;
            }
        }

        if (!"1".equals(extractXmlTag(step1Xml, "paired"))) {
            AppLog.w("SunshineStreamClient pair step1 failed: " + step1Xml);
            return null;
        }
        String plainCertHex = extractXmlTag(step1Xml, "plaincert");
        if (plainCertHex.isEmpty()) {
            AppLog.w("SunshineStreamClient pair step1 missing plaincert");
            return null;
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate serverCert = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(hexToBytes(plainCertHex)));

        byte[] randomChallenge = new byte[PAIRING_CHALLENGE_BYTES];
        RNG.nextBytes(randomChallenge);
        byte[] encChallenge = aesEcbEncrypt(aesKey, randomChallenge);

        String step2Xml = httpGetXml(
                "http",
                host,
                httpPort,
                "/pair?" + baseQuery(identity)
                        + "&devicename=roth&updateState=1&clientchallenge=" + bytesToHex(encChallenge),
                null,
                HTTP_READ_TIMEOUT_MS);
        if (!"1".equals(extractXmlTag(step2Xml, "paired"))) {
            AppLog.w("SunshineStreamClient pair step2 failed: " + step2Xml);
            return null;
        }

        byte[] decChallengeResp = aesEcbDecrypt(aesKey, hexToBytes(extractXmlTag(step2Xml, "challengeresponse")));
        if (decChallengeResp.length < SHA256_DIGEST_BYTES + PAIRING_CHALLENGE_BYTES) {
            AppLog.w("SunshineStreamClient pair step2 short challengeresponse: " + decChallengeResp.length);
            return null;
        }
        byte[] serverResponseHash = Arrays.copyOfRange(decChallengeResp, 0, SHA256_DIGEST_BYTES);
        byte[] serverChallenge = Arrays.copyOfRange(
                decChallengeResp, SHA256_DIGEST_BYTES, SHA256_DIGEST_BYTES + PAIRING_CHALLENGE_BYTES);

        byte[] clientSecret = new byte[PAIRING_SECRET_BYTES];
        RNG.nextBytes(clientSecret);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        sha256.update(serverChallenge);
        sha256.update(identity.certificate.getSignature());
        sha256.update(clientSecret);
        byte[] clientRespHash = sha256.digest();
        byte[] encClientResp = aesEcbEncrypt(aesKey, clientRespHash);

        String step3Xml = httpGetXml(
                "http",
                host,
                httpPort,
                "/pair?" + baseQuery(identity)
                        + "&devicename=roth&updateState=1&serverchallengeresp=" + bytesToHex(encClientResp),
                null,
                HTTP_READ_TIMEOUT_MS);
        if (!"1".equals(extractXmlTag(step3Xml, "paired"))) {
            AppLog.w("SunshineStreamClient pair step3 failed: " + step3Xml);
            return null;
        }

        byte[] serverPairingSecret = hexToBytes(extractXmlTag(step3Xml, "pairingsecret"));
        if (serverPairingSecret.length <= PAIRING_SECRET_BYTES) {
            AppLog.w("SunshineStreamClient pair step3 short pairingsecret");
            return null;
        }
        byte[] serverSecret = Arrays.copyOfRange(serverPairingSecret, 0, PAIRING_SECRET_BYTES);
        byte[] serverSig = Arrays.copyOfRange(serverPairingSecret, PAIRING_SECRET_BYTES, serverPairingSecret.length);

        Signature verifySig = Signature.getInstance("SHA256withRSA");
        verifySig.initVerify(serverCert.getPublicKey());
        verifySig.update(serverSecret);
        if (!verifySig.verify(serverSig)) {
            AppLog.w("SunshineStreamClient pair step3 serverSig verify failed");
            return null;
        }

        MessageDigest verifyHash = MessageDigest.getInstance("SHA-256");
        verifyHash.update(randomChallenge);
        verifyHash.update(serverCert.getSignature());
        verifyHash.update(serverSecret);
        if (!MessageDigest.isEqual(verifyHash.digest(), serverResponseHash)) {
            AppLog.w("SunshineStreamClient pair step3 serverResponseHash mismatch");
            return null;
        }

        Signature signClient = Signature.getInstance("SHA256withRSA");
        signClient.initSign(identity.keyPair.getPrivate());
        signClient.update(clientSecret);
        byte[] clientPairingSecret = concat(clientSecret, signClient.sign());

        String step4Xml = httpGetXml(
                "http",
                host,
                httpPort,
                "/pair?" + baseQuery(identity)
                        + "&devicename=roth&updateState=1&clientpairingsecret=" + bytesToHex(clientPairingSecret),
                null,
                HTTP_READ_TIMEOUT_MS);
        if (!"1".equals(extractXmlTag(step4Xml, "paired"))) {
            AppLog.w("SunshineStreamClient pair step4 failed: " + step4Xml);
            return null;
        }

        // Rotate SSLSocketFactory generation after Step 4 so Step 5 pairchallenge and subsequent calls
        // never reuse a pre-pair unauthenticated TLS session.
        invalidateMtlsCacheForRepair();
        SSLSocketFactory postPairFactory = getOrCreateMtlsSocketFactory(identity);

        httpGetXml(
                "https",
                host,
                httpsPort,
                "/pair?" + baseQuery(identity) + "&devicename=roth&updateState=1&phrase=pairchallenge",
                postPairFactory,
                HTTP_READ_TIMEOUT_MS);

        return fetchPairedServerInfoXml(host, httpsPort, identity, postPairFactory);
    }

    /**
     * Pairs (if needed), resolves the target Sunshine app ID, and executes {@code /launch} or {@code /resume}
     * to obtain the RTSP session URL and AES input keys for {@link MoonBridge#startConnection}.
     */
    public static LaunchSession startSunshineSession(
            Context context,
            File identityDir,
            String host,
            String desiredAppTitle,
            int width,
            int height,
            int fps,
            MoonBridge.AudioConfiguration audioConfig,
            boolean playAudioOnPc,
            boolean optimizeGameSettings,
            boolean enableHdr,
            int httpPort,
            int httpsPort,
            int agentPinPort) throws Exception {
        ClientIdentity identity = loadOrCreateIdentity(identityDir);
        String serverInfoXml = ensurePairedWithServerInfo(
                context, host, identity, httpPort, httpsPort, agentPinPort);
        if (serverInfoXml == null) {
            identity = resetIdentity(identityDir);
            serverInfoXml = ensurePairedWithServerInfo(
                    context, host, identity, httpPort, httpsPort, agentPinPort);
        }
        if (serverInfoXml == null) {
            throw new IOException("Automatic Sunshine pairing did not complete — check Gaming PC connection.");
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            SSLSocketFactory sslFactory = getOrCreateMtlsSocketFactory(identity);
            String appVersion = extractXmlTag(serverInfoXml, "appversion");
            if (appVersion.isEmpty()) appVersion = "7.1.431.-1";
            String gfeVersion = extractXmlTag(serverInfoXml, "GfeVersion");
            if (gfeVersion.isEmpty()) gfeVersion = "3.23.0.74";
            int serverCodecModeSupport = parseIntSafe(extractXmlTag(serverInfoXml, "ServerCodecModeSupport"), 0x0001);
            int currentGame = parseIntSafe(extractXmlTag(serverInfoXml, "currentgame"), 0);

            try {
                String appListXml = httpGetXml(
                        "https",
                        host,
                        httpsPort,
                        "/applist?" + baseQuery(identity),
                        sslFactory,
                        HTTP_READ_TIMEOUT_MS);
                List<SunshineApp> apps = parseAppListXml(appListXml);
                int targetAppId = findTargetAppId(apps, desiredAppTitle);
                if (targetAppId <= 0) {
                    throw new IOException("Sunshine returned an empty application list.");
                }

                if (currentGame != 0 && currentGame != targetAppId) {
                    try {
                        httpGetXml(
                                "https",
                                host,
                                httpsPort,
                                "/cancel?" + baseQuery(identity),
                                sslFactory,
                                HTTP_READ_TIMEOUT_MS);
                    } catch (Exception ignored) {
                    }
                    currentGame = 0;
                }

                byte[] riAesKey = new byte[AES_KEY_BYTES];
                RNG.nextBytes(riAesKey);
                int riKeyId = RNG.nextInt(Integer.MAX_VALUE - 1) + 1;
                byte[] riAesIv = ByteBuffer.allocate(AES_KEY_BYTES).putInt(0, riKeyId).array();

                String verb = (currentGame == targetAppId) ? "resume" : "launch";
                int sops = optimizeGameSettings ? 1 : 0;
                int localAudio = playAudioOnPc ? 1 : 0;
                int surroundInfo = (audioConfig != null ? audioConfig : MoonBridge.AUDIO_CONFIGURATION_STEREO).getSurroundAudioInfo();
                String extraCoreParams = MoonBridge.safeGetLaunchUrlQueryParameters();
                if (!extraCoreParams.isEmpty() && !extraCoreParams.startsWith("&")) {
                    extraCoreParams = "&" + extraCoreParams;
                }
                String hdrParams = enableHdr
                        ? "&hdrMode=1&clientHdrCapVersion=0&clientHdrCapSupportedFlagsInUint32=0"
                                + "&clientHdrCapMetaDataId=NV_STATIC_METADATA_TYPE_1"
                                + "&clientHdrCapDisplayData=0x0x0x0x0x0x0x0x0x0x0"
                        : "";

                String launchPath = "/" + verb + "?" + baseQuery(identity)
                        + "&appid=" + targetAppId
                        + "&mode=" + width + "x" + height + "x" + fps
                        + "&additionalStates=1&sops=" + sops
                        + "&rikey=" + bytesToHex(riAesKey)
                        + "&rikeyid=" + riKeyId
                        + "&localAudioPlayMode=" + localAudio
                        + "&surroundAudioInfo=" + surroundInfo
                        + "&remoteControllersBitmap=1&gcmap=1&gcpersist=0"
                        + extraCoreParams
                        + hdrParams;

                String launchXml = httpGetXml(
                        "https",
                        host,
                        httpsPort,
                        launchPath,
                        sslFactory,
                        PAIR_HTTP_READ_TIMEOUT_MS);

                String statusCode = extractXmlStatusCode(launchXml);
                if (!statusCode.isEmpty() && !"200".equals(statusCode)) {
                    throw new IOException("Sunshine /" + verb + " returned status " + statusCode);
                }
                String rtspUrl = unescapeXml(extractXmlTag(launchXml, "sessionUrl0"));
                if (rtspUrl.isEmpty()) {
                    rtspUrl = "rtsp://" + host + ":48010";
                }

                return new LaunchSession(
                        host,
                        appVersion,
                        gfeVersion,
                        rtspUrl,
                        serverCodecModeSupport,
                        riAesKey,
                        riAesIv,
                        targetAppId);
            } catch (HttpStatusException hse) {
                if (attempt == 0 && (hse.statusCode == 401 || hse.statusCode == 403)) {
                    invalidateMtlsCacheForRepair();
                    serverInfoXml = ensurePairedWithServerInfo(
                            context, host, identity, httpPort, httpsPort, agentPinPort);
                    if (serverInfoXml == null) {
                        identity = resetIdentity(identityDir);
                        serverInfoXml = ensurePairedWithServerInfo(
                                context, host, identity, httpPort, httpsPort, agentPinPort);
                    }
                    if (serverInfoXml != null) {
                        continue;
                    }
                }
                throw hse;
            } catch (SSLException ssle) {
                if (attempt == 0) {
                    invalidateMtlsCacheForRepair();
                    serverInfoXml = ensurePairedWithServerInfo(
                            context, host, identity, httpPort, httpsPort, agentPinPort);
                    if (serverInfoXml == null) {
                        identity = resetIdentity(identityDir);
                        serverInfoXml = ensurePairedWithServerInfo(
                                context, host, identity, httpPort, httpsPort, agentPinPort);
                    }
                    if (serverInfoXml != null) {
                        continue;
                    }
                }
                throw ssle;
            }
        }
        throw new IOException("Automatic Sunshine pairing did not complete — check Gaming PC connection.");
    }

    public static LaunchSession startSunshineSession(
            Context context,
            String host,
            String desiredAppTitle,
            int width,
            int height,
            int fps,
            MoonBridge.AudioConfiguration audioConfig,
            boolean playAudioOnPc,
            boolean optimizeGameSettings,
            boolean enableHdr) throws Exception {
        File identityDir = new File(context.getFilesDir(), IDENTITY_SUBDIR);
        return startSunshineSession(
                context,
                identityDir,
                host,
                desiredAppTitle,
                width,
                height,
                fps,
                audioConfig,
                playAudioOnPc,
                optimizeGameSettings,
                enableHdr,
                SUNSHINE_HTTP_PORT,
                SUNSHINE_HTTPS_PORT,
                GamingZone.LAPTOP_AGENT_PORT);
    }

    public static void cancelSession(Context context, String host) {
        try {
            ClientIdentity identity = loadOrCreateIdentity(context);
            SSLSocketFactory sslFactory = getOrCreateMtlsSocketFactory(identity);
            httpGetXml(
                    "https",
                    host,
                    SUNSHINE_HTTPS_PORT,
                    "/cancel?" + baseQuery(identity),
                    sslFactory,
                    3000);
        } catch (Exception ignored) {
        }
    }

    private static int parseIntSafe(String s, int fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
