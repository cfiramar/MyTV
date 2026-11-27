package io.soartv;

import com.limelight.nvstream.jni.MoonBridge;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Opaque-Box E2E Requirements Suite for SoarTV Gaming Discovery, Stream Startup,
 * Streaming Performance/Telemetry, and Settings Isolation (Java 8 headless JVM).
 *
 * <p><b>Why:</b> Enforces protocol, crypto, discovery, startup, streaming-config,
 * decoder, GC/process-isolation, and R3 Settings isolation requirements without
 * external test libraries or Android device dependencies.
 *
 * <p><b>Modes:</b>
 * <ul>
 *   <li>Default mode (no flags): verifies all structural &amp; protocol invariants and
 *       logs {@code [PRE-FIX BASELINE GAP]} for M2/M3 gaps so {@code build.sh} compiles
 *       and runs cleanly pre-fix.</li>
 *   <li>Strict mode ({@code -Dsoartv.e2e.strict=true} or {@code --strict}): enforces all
 *       M2/M3 post-fix requirements and throws {@link AssertionError} if any gap remains.</li>
 * </ul>
 */
public final class GamingE2ERequirementsTests {

    private static boolean strictMode = false;
    private static int alwaysPassed = 0;
    private static int strictPassed = 0;
    private static final List<String> strictFailures = new ArrayList<String>();

    private static void assertAlways(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("[ALWAYS FAIL] " + message);
        }
        alwaysPassed++;
    }

    private static void assertEqualsAlways(Object expected, Object actual, String message) {
        boolean eq = (expected == null && actual == null) || (expected != null && expected.equals(actual));
        if (!eq) {
            throw new AssertionError("[ALWAYS FAIL] " + message + " | expected=" + expected + ", actual=" + actual);
        }
        alwaysPassed++;
    }

    private static void assertStrict(boolean condition, String message) {
        if (condition) {
            strictPassed++;
            return;
        }
        if (strictMode) {
            strictFailures.add(message);
            System.out.println("[STRICT FAIL] " + message);
        } else {
            System.out.println("[PRE-FIX BASELINE GAP] " + message);
        }
    }

    public static void main(String[] args) throws Exception {
        strictMode = Boolean.getBoolean("soartv.e2e.strict")
                || (args != null && Arrays.asList(args).contains("--strict"));
        alwaysPassed = 0;
        strictPassed = 0;
        strictFailures.clear();

        runTier1And2DiscoveryAndAuth();
        runTier1And2StreamStartupAndPairing();
        runTier1And2PerformanceConfigAndTelemetry();
        runTier3CrossFeatureContracts();
        runTier4EndToEndAndR3Isolation();

        System.out.println("GamingE2ERequirementsTests summary: mode="
                + (strictMode ? "STRICT" : "BASELINE")
                + ", alwaysPassed=" + alwaysPassed
                + ", strictPassed=" + strictPassed
                + ", strictFailures=" + strictFailures.size());

        if (strictMode && !strictFailures.isEmpty()) {
            StringBuilder sb = new StringBuilder("Strict M2/M3 E2E requirements failed (")
                    .append(strictFailures.size()).append("):");
            for (String f : strictFailures) {
                sb.append("\n - ").append(f);
            }
            throw new AssertionError(sb.toString());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static File resolveLauncherFile(String relativeToLauncher) {
        File direct = new File(relativeToLauncher);
        if (direct.exists()) return direct;
        File nested = new File("SoarTV-launcher", relativeToLauncher);
        if (nested.exists()) return nested;
        return direct;
    }

    private static String readSourceText(String relativeToLauncher) throws IOException {
        File f = resolveLauncherFile(relativeToLauncher);
        assertAlways(f.isFile(), "Required source/manifest file exists: " + f.getPath());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String stripJavaComments(String src) {
        if (src == null || src.isEmpty()) return "";
        StringBuilder out = new StringBuilder(src.length());
        boolean inStr = false;
        boolean inChar = false;
        boolean escape = false;
        boolean inLine = false;
        boolean inBlock = false;
        int n = src.length();
        for (int i = 0; i < n; i++) {
            char c = src.charAt(i);
            char next = (i + 1 < n) ? src.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append('\n');
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    out.append(' ');
                    i++;
                } else if (c == '\n') {
                    out.append('\n');
                }
                continue;
            }
            if (inStr || inChar) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (inStr && c == '"') {
                    inStr = false;
                } else if (inChar && c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '\'') {
                inChar = true;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int findMatchingDelim(String src, int openIdx, char openChar, char closeChar) {
        boolean inStr = false;
        boolean inChar = false;
        boolean escape = false;
        int depth = 0;
        for (int i = openIdx; i < src.length(); i++) {
            char c = src.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && (inStr || inChar)) {
                escape = true;
                continue;
            }
            if (c == '"' && !inChar) {
                inStr = !inStr;
                continue;
            }
            if (c == '\'' && !inStr) {
                inChar = !inChar;
                continue;
            }
            if (inStr || inChar) continue;
            if (c == openChar) {
                depth++;
            } else if (c == closeChar) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> extractAllMethodBlocks(String strippedSrc, String methodName) {
        List<String> blocks = new ArrayList<String>();
        if (strippedSrc == null || strippedSrc.isEmpty() || methodName == null || methodName.isEmpty()) {
            return blocks;
        }
        Set<String> nonReturnKeywords = new HashSet<String>(Arrays.asList(
                "return", "new", "else", "throw", "case", "if", "while", "for", "switch", "catch", "do"));
        Pattern headerPat = Pattern.compile(
                "(?m)^[ \\t]*(?:@[A-Za-z0-9_$.]+(?:\\([^()\\n]*\\))?[ \\t]*\\n?[ \\t]*)*"
                        + "(?:(?:public|private|protected|static|final|synchronized|native|strictfp|abstract|default)[ \\t]+)*"
                        + "([A-Za-z0-9_$.<>\\[\\]?]+(?:[ \\t]*<[^;{}()=\\n]*>)?(?:\\[\\])*)[ \\t\\n]+"
                        + Pattern.quote(methodName) + "\\s*\\(");
        Matcher m = headerPat.matcher(strippedSrc);
        int searchFrom = 0;
        while (searchFrom < strippedSrc.length() && m.find(searchFrom)) {
            String retToken = m.group(1);
            int openParen = m.end() - 1;
            if (retToken == null || nonReturnKeywords.contains(retToken)) {
                searchFrom = openParen + 1;
                continue;
            }
            int closeParen = findMatchingDelim(strippedSrc, openParen, '(', ')');
            if (closeParen < 0) {
                break;
            }
            int cursor = closeParen + 1;
            while (cursor < strippedSrc.length() && Character.isWhitespace(strippedSrc.charAt(cursor))) {
                cursor++;
            }
            if (cursor + 6 <= strippedSrc.length()
                    && strippedSrc.startsWith("throws", cursor)
                    && (cursor + 6 == strippedSrc.length() || Character.isWhitespace(strippedSrc.charAt(cursor + 6)))) {
                cursor += 6;
                while (cursor < strippedSrc.length()) {
                    char ch = strippedSrc.charAt(cursor);
                    if (ch == '{' || ch == ';' || ch == '=' || ch == '(' || ch == '}') {
                        break;
                    }
                    cursor++;
                }
            }
            if (cursor >= strippedSrc.length() || strippedSrc.charAt(cursor) != '{') {
                searchFrom = closeParen + 1;
                continue;
            }
            int closeBrace = findMatchingDelim(strippedSrc, cursor, '{', '}');
            if (closeBrace < 0) {
                blocks.add(strippedSrc.substring(m.start()));
                break;
            }
            blocks.add(strippedSrc.substring(m.start(), closeBrace + 1));
            searchFrom = closeBrace + 1;
        }
        return blocks;
    }

    private static String extractAllMethodsJoined(String strippedSrc, String methodName) {
        List<String> blocks = extractAllMethodBlocks(strippedSrc, methodName);
        if (blocks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(blocks.get(i));
        }
        return sb.toString();
    }

    private static SSLContext createLoopbackServerSslContext(String commonName) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair srvKp = kpg.generateKeyPair();
        Method buildDer = SunshineStreamClient.class.getDeclaredMethod(
                "buildSelfSignedX509Der", KeyPair.class, String.class);
        buildDer.setAccessible(true);
        byte[] srvDer = (byte[]) buildDer.invoke(null, srvKp, commonName);
        Certificate srvCert = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(srvDer));
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("srv", srvKp.getPrivate(), "pw".toCharArray(), new Certificate[] {srvCert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "pw".toCharArray());
        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(), new TrustManager[] {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        return sslCtx;
    }

    private static int countTryCatchHelperCalls(String callerBlock, String strippedSrc, String callerName) {
        if (callerBlock.isEmpty() || strippedSrc.isEmpty()) return 0;
        Set<String> ignored = new HashSet<String>(Arrays.asList(
                callerName, "if", "for", "while", "switch", "catch", "try",
                "createVideoFormat", "setInteger", "configure", "reset", "getInteger"));
        Set<String> tryCatchHelpers = new HashSet<String>();
        Matcher callMatcher = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").matcher(callerBlock);
        int totalCalls = 0;
        while (callMatcher.find()) {
            String fn = callMatcher.group(1);
            if (ignored.contains(fn)) continue;
            if (tryCatchHelpers.contains(fn)) {
                totalCalls++;
                continue;
            }
            String helperBody = extractAllMethodsJoined(strippedSrc, fn);
            if (!helperBody.isEmpty()
                    && Pattern.compile("\\btry\\b").matcher(helperBody).find()
                    && Pattern.compile("\\bcatch\\s*\\(").matcher(helperBody).find()) {
                tryCatchHelpers.add(fn);
                totalCalls++;
            }
        }
        return totalCalls;
    }

    private static void deleteDir(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteDir(c);
        }
        f.delete();
    }

    private static String readHttpRequestHeaders(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        client.setSoTimeout(10000);
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n <= 0) break;
            total += n;
            String cur = new String(buf, 0, total, StandardCharsets.ISO_8859_1);
            if (cur.contains("\r\n\r\n")) break;
        }
        return new String(buf, 0, total, StandardCharsets.UTF_8);
    }

    // =========================================================================
    // Feature 1: Discovery & Auth (Tier 1 & Tier 2)
    // =========================================================================

    private static void runTier1And2DiscoveryAndAuth() throws Exception {
        File secretDir = Files.createTempDirectory("soartv_e2e_disc_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(secretDir);
            final String token = "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff";
            assertAlways(Secrets.putSecret(null, Secrets.GAMING_PC_TOKEN, token),
                    "Secrets.putSecret stores gaming_pc_token in JVM test dir");
            assertEqualsAlways(token, GamingZone.getLaptopAuthToken(null),
                    "GamingZone.getLaptopAuthToken(null) resolves gaming_pc_token");

            final String expectedUuid = "14FEDE7C-47F1-1D07-0185-22930C564015";
            final AtomicInteger authedStatusHits = new AtomicInteger(0);
            final AtomicReference<String> lastTvPortHeader = new AtomicReference<String>("");

            try (ServerSocket agentServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                 ServerSocket unpairedAgentServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                 ServerSocket imposterServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                 DatagramSocket udpResponder = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {

                final int agentPort = agentServer.getLocalPort();
                final int unpairedPort = unpairedAgentServer.getLocalPort();
                final int imposterPort = imposterServer.getLocalPort();
                final int udpPort = udpResponder.getLocalPort();

                Thread agentWorker = new Thread(() -> {
                    while (!agentServer.isClosed()) {
                        try (Socket c = agentServer.accept()) {
                            String req = readHttpRequestHeaders(c);
                            boolean okTok = req.contains("X-SoarTV-Token: " + token);
                            if (req.contains("X-SoarTV-Tv-Port:")) {
                                Matcher m = Pattern.compile("X-SoarTV-Tv-Port:\\s*(\\d+)").matcher(req);
                                if (m.find()) lastTvPortHeader.set(m.group(1));
                            }
                            if (okTok) authedStatusHits.incrementAndGet();
                            String body = "{\"ok\":true,\"agentService\":\"soartv-laptop-gaming-agent\","
                                    + "\"authenticated\":" + okTok + ",\"paired\":" + okTok
                                    + ",\"sunshineHostUuid\":\"" + expectedUuid + "\","
                                    + "\"sunshineHostName\":\"Gaming-Laptop\"}";
                            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                    + bytes.length + "\r\n\r\n";
                            OutputStream out = c.getOutputStream();
                            out.write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            out.write(bytes);
                            out.flush();
                        } catch (Exception ignored) {
                            if (agentServer.isClosed()) break;
                        }
                    }
                });
                agentWorker.setDaemon(true);
                agentWorker.start();

                Thread unpairedWorker = new Thread(() -> {
                    while (!unpairedAgentServer.isClosed()) {
                        try (Socket c = unpairedAgentServer.accept()) {
                            readHttpRequestHeaders(c);
                            String body = "{\"ok\":true,\"agentService\":\"soartv-laptop-gaming-agent\","
                                    + "\"authenticated\":false,\"paired\":false,\"pairingRequired\":true,"
                                    + "\"sunshineHostUuid\":\"" + expectedUuid + "\"}";
                            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                    + bytes.length + "\r\n\r\n";
                            OutputStream out = c.getOutputStream();
                            out.write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            out.write(bytes);
                            out.flush();
                        } catch (Exception ignored) {
                            if (unpairedAgentServer.isClosed()) break;
                        }
                    }
                });
                unpairedWorker.setDaemon(true);
                unpairedWorker.start();

                Thread imposterWorker = new Thread(() -> {
                    while (!imposterServer.isClosed()) {
                        try (Socket c = imposterServer.accept()) {
                            readHttpRequestHeaders(c);
                            String body = "{\"ok\":true,\"agentService\":\"not-soartv-service\"}";
                            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + bytes.length + "\r\n\r\n";
                            c.getOutputStream().write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            c.getOutputStream().write(bytes);
                            c.getOutputStream().flush();
                        } catch (Exception ignored) {
                            if (imposterServer.isClosed()) break;
                        }
                    }
                });
                imposterWorker.setDaemon(true);
                imposterWorker.start();

                Thread udpWorker = new Thread(() -> {
                    byte[] buf = new byte[1024];
                    while (!udpResponder.isClosed()) {
                        try {
                            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                            udpResponder.receive(pkt);
                            String payload = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                            Object parsed = RemoteAuthManager.MiniJson.parse(payload);
                            if (parsed instanceof Map && "SOARTV_GAMING_DISCOVER_V1".equals(((Map<?, ?>) parsed).get("op"))) {
                                String reply = "{\"op\":\"SOARTV_GAMING_HERE_V1\","
                                        + "\"agentService\":\"soartv-laptop-gaming-agent\","
                                        + "\"port\":" + agentPort + ","
                                        + "\"sunshineHostUuid\":\"" + expectedUuid + "\","
                                        + "\"sunshineHostName\":\"Gaming-Laptop\"}";
                                byte[] rBytes = reply.getBytes(StandardCharsets.UTF_8);
                                udpResponder.send(new DatagramPacket(rBytes, rBytes.length, pkt.getAddress(), pkt.getPort()));
                            }
                        } catch (Exception ignored) {
                            if (udpResponder.isClosed()) break;
                        }
                    }
                });
                udpWorker.setDaemon(true);
                udpWorker.start();

                // Tier 1: UDP SOARTV_GAMING_DISCOVER_V1 -> SOARTV_GAMING_HERE_V1 -> HTTP :8485 authenticated:true
                String foundViaUdp = GamingZone.discoverLaptopViaUdpTargetsSync(
                        null,
                        Collections.singletonList(new InetSocketAddress("127.0.0.1", udpPort)),
                        agentPort,
                        10000);
                assertEqualsAlways("127.0.0.1", foundViaUdp,
                        "UDP SOARTV_GAMING_DISCOVER_V1 discovers and verifies authenticated agent");
                assertAlways(authedStatusHits.get() >= 1,
                        "UDP discovery verified endpoint via authenticated HTTP GET /api/gaming/status");
                assertAlways(!lastTvPortHeader.get().isEmpty(),
                        "GamingZone HTTP RPC sends X-SoarTV-Tv-Port header");

                // Tier 1: verifyCandidateGamingPcEndpoint accepts authenticated agent & rejects imposter
                assertAlways(GamingZone.verifyCandidateGamingPcEndpoint(null, "127.0.0.1", agentPort, 10000),
                        "verifyCandidateGamingPcEndpoint accepts authenticated agent");
                assertAlways(!GamingZone.verifyCandidateGamingPcEndpoint(null, "127.0.0.1", imposterPort, 10000),
                        "verifyCandidateGamingPcEndpoint rejects non-SoarTV imposter");

                // Tier 2 (Strict M2): probeCandidateGamingPcEndpoint distinguishes
                // REACHABLE_AUTHENTICATED, REACHABLE_UNPAIRED, and UNREACHABLE behaviorally
                GamingZone.recordReachableUnpairedPcIp(null, "");
                GamingZone.ProbeResult authProbe = GamingZone.probeCandidateGamingPcEndpoint(
                        null, "127.0.0.1", agentPort, 10000);
                assertStrict(authProbe.status == GamingZone.EndpointProbeStatus.REACHABLE_AUTHENTICATED
                                && authProbe.isReachable()
                                && authProbe.isAuthenticated()
                                && !authProbe.isUnpaired(),
                        "R2-Discovery: probeCandidateGamingPcEndpoint returns REACHABLE_AUTHENTICATED for authenticated agent");

                GamingZone.ProbeResult unpairedProbe = GamingZone.probeCandidateGamingPcEndpoint(
                        null, "127.0.0.1", unpairedPort, 10000);
                assertStrict(unpairedProbe.status == GamingZone.EndpointProbeStatus.REACHABLE_UNPAIRED
                                && unpairedProbe.isReachable()
                                && unpairedProbe.isUnpaired()
                                && !unpairedProbe.isAuthenticated()
                                && "127.0.0.1".equals(GamingZone.getReachableUnpairedPcIp(null)),
                        "R2-Discovery: probeCandidateGamingPcEndpoint returns REACHABLE_UNPAIRED and records reachable unpaired PC IP");
                assertAlways(!GamingZone.verifyCandidateGamingPcEndpoint(null, "127.0.0.1", unpairedPort, 10000),
                        "verifyCandidateGamingPcEndpoint must not authenticate an unpaired agent");

                GamingZone.ProbeResult imposterProbe = GamingZone.probeCandidateGamingPcEndpoint(
                        null, "127.0.0.1", imposterPort, 10000);
                assertStrict(imposterProbe.status == GamingZone.EndpointProbeStatus.UNREACHABLE
                                && !imposterProbe.isReachable()
                                && !imposterProbe.isUnpaired()
                                && !imposterProbe.isAuthenticated(),
                        "R2-Discovery: probeCandidateGamingPcEndpoint returns UNREACHABLE for non-SoarTV imposter");
                GamingZone.recordReachableUnpairedPcIp(null, "");
            }

            // Tier 2: Sunshine :47989/serverinfo fallback with empty vs matching expectedSunshineUuid
            try (ServerSocket sunServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
                final int sunPort = sunServer.getLocalPort();
                Thread sunWorker = new Thread(() -> {
                    while (!sunServer.isClosed()) {
                        try (Socket c = sunServer.accept()) {
                            readHttpRequestHeaders(c);
                            String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                                    + "<root status_code=\"200\"><hostname>Gaming-Laptop</hostname>"
                                    + "<uniqueid>" + expectedUuid + "</uniqueid><PairStatus>1</PairStatus></root>";
                            byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + bytes.length + "\r\n\r\n";
                            c.getOutputStream().write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            c.getOutputStream().write(bytes);
                            c.getOutputStream().flush();
                        } catch (Exception ignored) {
                            if (sunServer.isClosed()) break;
                        }
                    }
                });
                sunWorker.setDaemon(true);
                sunWorker.start();

                String withUuid = GamingZone.scanCandidatesForGamingPcSync(
                        null, Collections.singletonList("127.0.0.1"), 0, sunPort, expectedUuid, 10000);
                assertEqualsAlways("127.0.0.1", withUuid,
                        "Sunshine :47989/serverinfo scan matches expected uniqueid");

                String withoutUuid = GamingZone.scanCandidatesForGamingPcSync(
                        null, Collections.singletonList("127.0.0.1"), 0, sunPort, "", 10000);
                assertStrict("127.0.0.1".equals(withoutUuid),
                        "R2-Discovery: scanCandidatesForGamingPcSync :47989/serverinfo fallback must still locate Sunshine when expectedSunshineUuid is empty");
            }

            // Tier 2 (Strict M2): Source & reflection invariants in GamingZone.java
            Field crossField = GamingZone.class.getDeclaredField("FAST_CROSS_SUBNET_PROBE_MS");
            crossField.setAccessible(true);
            int fastCrossMs = crossField.getInt(null);
            assertStrict(fastCrossMs >= 450,
                    "R2-Discovery: FAST_CROSS_SUBNET_PROBE_MS must be >= 450ms (was " + fastCrossMs + "ms)");

            String strippedGz = stripJavaComments(readSourceText("app/src/io/soartv/GamingZone.java"));
            String probeBlocks = extractAllMethodsJoined(strippedGz, "probeCandidateGamingPcEndpoint")
                    + "\n" + extractAllMethodsJoined(strippedGz, "verifyCandidateGamingPcEndpoint");
            boolean silentUnauthFalse = Pattern.compile(
                    "if\\s*\\(\\s*!isAuthed\\s*\\)\\s*\\{\\s*return\\s+false\\s*;\\s*\\}").matcher(probeBlocks).find();
            boolean distinguishesUnpaired = !probeBlocks.isEmpty()
                    && !silentUnauthFalse
                    && probeBlocks.toLowerCase(Locale.ROOT).contains("unpaired");
            assertStrict(distinguishesUnpaired,
                    "R2-Discovery: verifyCandidateGamingPcEndpoint / probeCandidateGamingPcEndpoint must distinguish reachable-but-unpaired (agentService='soartv-laptop-gaming-agent', authenticated=false) from unreachable/imposter");

            String launchBlock = extractAllMethodsJoined(strippedGz, "launch");
            assertStrict(launchBlock.contains("resolveReachableLaptopIpSync") || launchBlock.contains("discoverLaptopIpOnLanSync"),
                    "R2-Discovery: GamingZone.launch() must refresh laptop IP before /api/gaming/wake");

            String setHiddenBlock = extractAllMethodsJoined(strippedGz, "setGameHidden");
            assertStrict(setHiddenBlock.contains("resolveReachableLaptopIpSync") || setHiddenBlock.contains("discoverLaptopIpOnLanSync"),
                    "R2-Discovery: GamingZone.setGameHidden() must refresh laptop IP before /api/gaming/hide");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteDir(secretDir);
        }
    }

    // =========================================================================
    // Feature 2: Stream Startup & Pairing (Tier 1 & Tier 2)
    // =========================================================================

    private static void runTier1And2StreamStartupAndPairing() throws Exception {
        File idDir = Files.createTempDirectory("soartv_e2e_sun_id_").toFile();
        try {
            // Tier 1: RSA-2048 X.509 mTLS identity & persistence
            SunshineStreamClient.ClientIdentity id1 = SunshineStreamClient.loadOrCreateIdentity(idDir);
            assertAlways(id1.uniqueId != null && id1.uniqueId.length() == 16,
                    "SunshineStreamClient generates 16-char uniqueId");
            assertAlways(id1.certPem.contains("-----BEGIN CERTIFICATE-----"),
                    "SunshineStreamClient exports PEM certificate");
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(id1.certDer));
            cert.verify(id1.keyPair.getPublic());
            alwaysPassed++;
            SunshineStreamClient.ClientIdentity id2 = SunshineStreamClient.loadOrCreateIdentity(idDir);
            assertEqualsAlways(id1.uniqueId, id2.uniqueId, "Sunshine identity uniqueId persists");
            assertEqualsAlways(id1.certPem, id2.certPem, "Sunshine identity PEM persists");

            // Tier 1: Host allowlist & cleartext LAN HTTP guard
            assertAlways(SunshineStreamClient.isAllowedCleartextLanHost("192.168.1.12"), "Allows RFC-1918 192.168/16");
            assertAlways(SunshineStreamClient.isAllowedCleartextLanHost("10.0.0.5"), "Allows RFC-1918 10/8");
            assertAlways(SunshineStreamClient.isAllowedCleartextLanHost("172.16.0.5"), "Allows RFC-1918 172.16/12");
            assertAlways(SunshineStreamClient.isAllowedCleartextLanHost("127.0.0.1"), "Allows loopback");
            assertAlways(!SunshineStreamClient.isAllowedCleartextLanHost("8.8.8.8"), "Blocks public IPv4");
            assertAlways(!SunshineStreamClient.isAllowedCleartextLanHost("example.com"), "Blocks public hostname");

            // Tier 1: rawLanHttpGet + /serverinfo & /applist XML parsing
            try (ServerSocket sunHttp = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
                int port = sunHttp.getLocalPort();
                Thread worker = new Thread(() -> {
                    while (!sunHttp.isClosed()) {
                        try (Socket c = sunHttp.accept()) {
                            String req = readHttpRequestHeaders(c);
                            String xml;
                            if (req.startsWith("GET /applist")) {
                                xml = "<root status_code=\"200\">"
                                        + "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Desktop</AppTitle><ID>881448767</ID></App>"
                                        + "<App><IsHdrSupported>0</IsHdrSupported><AppTitle>Overcooked! 2</AppTitle><ID>728880</ID></App>"
                                        + "</root>";
                            } else {
                                xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                                        + "<root status_code=\"200\"><hostname>Gaming-Laptop</hostname>"
                                        + "<PairStatus>0</PairStatus><currentgame>0</currentgame></root>";
                            }
                            byte[] b = xml.getBytes(StandardCharsets.UTF_8);
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + b.length + "\r\n\r\n";
                            c.getOutputStream().write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            c.getOutputStream().write(b);
                            c.getOutputStream().flush();
                        } catch (Exception ignored) {
                            if (sunHttp.isClosed()) break;
                        }
                    }
                });
                worker.setDaemon(true);
                worker.start();

                String srvXml = SunshineStreamClient.rawLanHttpGet("127.0.0.1", port, "/serverinfo?uniqueid=" + id1.uniqueId, 10000, 10000);
                assertEqualsAlways("200", SunshineStreamClient.extractXmlStatusCode(srvXml), "rawLanHttpGet parses status_code=200");
                assertEqualsAlways("0", SunshineStreamClient.extractXmlTag(srvXml, "PairStatus"), "Detects PairStatus=0");

                String appXml = SunshineStreamClient.rawLanHttpGet("127.0.0.1", port, "/applist?uniqueid=" + id1.uniqueId, 10000, 10000);
                List<SunshineStreamClient.SunshineApp> apps = SunshineStreamClient.parseAppListXml(appXml);
                assertEqualsAlways(2, apps.size(), "parseAppListXml parses 2 apps");
                assertEqualsAlways(728880, SunshineStreamClient.findTargetAppId(apps, "Overcooked! 2"), "Finds target app ID");
            }

            // Tier 2: PairStatus=0 mTLS + 5-step pairing crypto + loopback :8485 /api/gaming/sunshine-pair + mTLS SSLServerSocket
            byte[] salt = SunshineStreamClient.hexToBytes("00112233445566778899aabbccddeeff");
            byte[] aesKey = SunshineStreamClient.derivePairingAesKey(salt, "4821");
            byte[] challenge = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
            byte[] dec = SunshineStreamClient.aesEcbDecrypt(aesKey, SunshineStreamClient.aesEcbEncrypt(aesKey, challenge));
            assertEqualsAlways("0123456789ABCDEF", new String(dec, StandardCharsets.UTF_8),
                    "AES-128-ECB pairing round-trip succeeds");

            // Verify mTLS client cert presentation over real loopback SSLServerSocket(0)
            Method mtlsFactoryMethod = SunshineStreamClient.class.getDeclaredMethod(
                    "buildMtlsSocketFactory", SunshineStreamClient.ClientIdentity.class);
            mtlsFactoryMethod.setAccessible(true);
            SSLSocketFactory clientSslFactory = (SSLSocketFactory) mtlsFactoryMethod.invoke(null, id1);

            SSLContext srvSslCtx = createLoopbackServerSslContext("Sunshine Test Server");
            SSLServerSocketFactory srvSslFactory = srvSslCtx.getServerSocketFactory();

            try (SSLServerSocket httpsLoopback = (SSLServerSocket) srvSslFactory.createServerSocket(
                    0, 8, InetAddress.getByName("127.0.0.1"));
                 ServerSocket agentPinMock = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
                httpsLoopback.setNeedClientAuth(true);
                final int httpsPort = httpsLoopback.getLocalPort();
                final int agentPinPort = agentPinMock.getLocalPort();
                final AtomicInteger pinSubmitHits = new AtomicInteger(0);
                final AtomicInteger pairChallengeHits = new AtomicInteger(0);

                Thread httpsWorker = new Thread(() -> {
                    int call = 0;
                    while (!httpsLoopback.isClosed()) {
                        try (SSLSocket s = (SSLSocket) httpsLoopback.accept()) {
                            Certificate[] peerCerts = s.getSession().getPeerCertificates();
                            assertAlways(peerCerts != null && peerCerts.length > 0, "Client presented mTLS X.509 cert");
                            String req = readHttpRequestHeaders(s);
                            String xml;
                            if (req.contains("phrase=pairchallenge")) {
                                pairChallengeHits.incrementAndGet();
                                xml = "<root status_code=\"200\"><paired>1</paired></root>";
                            } else if (call++ == 0) {
                                xml = "<root status_code=\"200\"><PairStatus>0</PairStatus></root>";
                            } else {
                                xml = "<root status_code=\"200\"><PairStatus>1</PairStatus></root>";
                            }
                            byte[] b = xml.getBytes(StandardCharsets.UTF_8);
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + b.length + "\r\n\r\n";
                            s.getOutputStream().write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            s.getOutputStream().write(b);
                            s.getOutputStream().flush();
                        } catch (Exception ignored) {
                            if (httpsLoopback.isClosed()) break;
                        }
                    }
                });
                httpsWorker.setDaemon(true);
                httpsWorker.start();

                Thread pinWorker = new Thread(() -> {
                    while (!agentPinMock.isClosed()) {
                        try (Socket c = agentPinMock.accept()) {
                            String req = readHttpRequestHeaders(c);
                            if (req.contains("/api/gaming/sunshine-pair")) {
                                pinSubmitHits.incrementAndGet();
                            }
                            String body = "{\"ok\":true,\"paired\":true}";
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n";
                            c.getOutputStream().write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                            c.getOutputStream().flush();
                        } catch (Exception ignored) {
                            if (agentPinMock.isClosed()) break;
                        }
                    }
                });
                pinWorker.setDaemon(true);
                pinWorker.start();

                Method httpGetXml = SunshineStreamClient.class.getDeclaredMethod(
                        "httpGetXml", String.class, String.class, int.class, String.class, SSLSocketFactory.class, int.class);
                httpGetXml.setAccessible(true);

                String initialServerInfo = (String) httpGetXml.invoke(
                        null, "https", "127.0.0.1", httpsPort,
                        "/serverinfo?uniqueid=" + id1.uniqueId, clientSslFactory, 10000);
                assertEqualsAlways("0", SunshineStreamClient.extractXmlTag(initialServerInfo, "PairStatus"),
                        "mTLS loopback /serverinfo detects PairStatus=0");

                GamingZone.HttpResult pinRes = GamingZone.rawHttpRequestWithStatus(
                        null, "127.0.0.1", agentPinPort, "POST", "/api/gaming/sunshine-pair",
                        "{\"pin\":\"4821\",\"deviceName\":\"SoarTV\"}", 10000);
                assertAlways(pinRes.isSuccess() && pinSubmitHits.get() == 1,
                        "Loopback /api/gaming/sunshine-pair submits 4-digit PIN");

                String challengeXml = (String) httpGetXml.invoke(
                        null, "https", "127.0.0.1", httpsPort,
                        "/pair?uniqueid=" + id1.uniqueId + "&phrase=pairchallenge", clientSslFactory, 10000);
                assertEqualsAlways("1", SunshineStreamClient.extractXmlTag(challengeXml, "paired"),
                        "mTLS /pair?phrase=pairchallenge completes pairing");

                String postPairInfo = (String) httpGetXml.invoke(
                        null, "https", "127.0.0.1", httpsPort,
                        "/serverinfo?uniqueid=" + id1.uniqueId, clientSslFactory, 10000);
                assertEqualsAlways("1", SunshineStreamClient.extractXmlTag(postPairInfo, "PairStatus"),
                        "PairStatus recovers from 0 -> 1 after pairing handshake");
                assertAlways(pairChallengeHits.get() == 1, "pairchallenge executed once");
            }

            // Tier 2 (Strict M2): Behavioral loopback test for checkSunshineDirectFallbackSync + source checks
            final AtomicBoolean fallbackPaired = new AtomicBoolean(true);
            try (ServerSocket fbHttp = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                 SSLServerSocket fbHttps = (SSLServerSocket) srvSslFactory.createServerSocket(
                         0, 8, InetAddress.getByName("127.0.0.1"))) {
                fbHttps.setNeedClientAuth(true);
                final int fbHttpPort = fbHttp.getLocalPort();
                final int fbHttpsPort = fbHttps.getLocalPort();

                Thread fbHttpWorker = new Thread(() -> {
                    while (!fbHttp.isClosed()) {
                        try (Socket c = fbHttp.accept()) {
                            readHttpRequestHeaders(c);
                            String xml = "<root status_code=\"200\"><hostname>Gaming-Laptop</hostname>"
                                    + "<uniqueid>14FEDE7C-47F1-1D07-0185-22930C564015</uniqueid></root>";
                            byte[] b = xml.getBytes(StandardCharsets.UTF_8);
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + b.length + "\r\n\r\n";
                            c.getOutputStream().write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            c.getOutputStream().write(b);
                            c.getOutputStream().flush();
                        } catch (Exception ignored) {
                            if (fbHttp.isClosed()) break;
                        }
                    }
                });
                fbHttpWorker.setDaemon(true);
                fbHttpWorker.start();

                Thread fbHttpsWorker = new Thread(() -> {
                    while (!fbHttps.isClosed()) {
                        try (SSLSocket s = (SSLSocket) fbHttps.accept()) {
                            readHttpRequestHeaders(s);
                            String ps = fallbackPaired.get() ? "1" : "0";
                            String xml = "<root status_code=\"200\"><PairStatus>" + ps + "</PairStatus></root>";
                            byte[] b = xml.getBytes(StandardCharsets.UTF_8);
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + b.length + "\r\n\r\n";
                            s.getOutputStream().write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            s.getOutputStream().write(b);
                            s.getOutputStream().flush();
                        } catch (Exception ignored) {
                            if (fbHttps.isClosed()) break;
                        }
                    }
                });
                fbHttpsWorker.setDaemon(true);
                fbHttpsWorker.start();

                fallbackPaired.set(true);
                boolean fbPairedOk = GamingZone.checkSunshineDirectFallbackSync(
                        null, idDir, "127.0.0.1", fbHttpPort, fbHttpsPort);
                fallbackPaired.set(false);
                boolean fbUnpairedRejected = !GamingZone.checkSunshineDirectFallbackSync(
                        null, idDir, "127.0.0.1", fbHttpPort, fbHttpsPort);
                assertStrict(fbPairedOk && fbUnpairedRejected,
                        "R2-Startup: GamingZone.checkSunshineDirectFallbackSync returns true for PairStatus=1 and false for PairStatus=0");
            }

            String strippedGz = stripJavaComments(readSourceText("app/src/io/soartv/GamingZone.java"));
            String launchGameBlock = extractAllMethodsJoined(strippedGz, "launchGameOnTvAndLaptop");
            boolean hasSunshineFallbackWhenAgentDown = !launchGameBlock.contains("if (!reached)")
                    || launchGameBlock.contains("checkSunshineDirectFallbackSync")
                    || launchGameBlock.contains("SUNSHINE_HTTP_PORT")
                    || launchGameBlock.contains("SUNSHINE_HTTPS_PORT")
                    || launchGameBlock.contains("fetchServerInfoUnauthenticatedXml")
                    || launchGameBlock.contains("sunshineReachable")
                    || launchGameBlock.contains("sunshineFallback");
            assertStrict(hasSunshineFallbackWhenAgentDown,
                    "R2-Startup: launchGameOnTvAndLaptop must fall back cleanly instead of hard-aborting when :8485 agent fails while Sunshine is reachable");

            // Behavioral + multi-method .secure decoder exclusion checks
            assertStrict(GameStreamActivity.isSecureDecoderName("c2.mtk.hevc.decoder.secure")
                            && !GameStreamActivity.isSecureDecoderName("c2.mtk.hevc.decoder")
                            && !GameStreamActivity.isAcceptableHardwareDecoder(
                                    "c2.mtk.hevc.decoder.secure", false, true, false, false, false)
                            && !GameStreamActivity.isAcceptableHardwareDecoder(
                                    "c2.mtk.hevc.decoder", false, true, false, false, true)
                            && !GameStreamActivity.isAcceptableHardwareDecoder(
                                    "c2.mtk.hevc.decoder", false, true, false, true, false)
                            && GameStreamActivity.isAcceptableHardwareDecoder(
                                    "c2.mtk.hevc.decoder", false, true, false, false, false),
                    "R2-Startup: GameStreamActivity decoder filter helpers exclude .secure and FEATURE_SecurePlayback");

            String strippedGs = stripJavaComments(readSourceText("app/src/io/soartv/GameStreamActivity.java"));
            String findBlock = extractAllMethodsJoined(strippedGs, "findHardwareDecoderName");
            String swBlock = extractAllMethodsJoined(strippedGs, "isSoftwareCodec");
            String secBlock = extractAllMethodsJoined(strippedGs, "isSecureDecoderName");
            String decoderChain = findBlock + "\n" + swBlock + "\n" + secBlock;
            boolean delegatesFilter = findBlock.contains("isSoftwareCodec")
                    || findBlock.contains("isSecureDecoderName")
                    || findBlock.contains("\".secure\"");
            boolean excludesSecureDecoders = delegatesFilter
                    && decoderChain.contains("\".secure\"")
                    && decoderChain.contains("FEATURE_SecurePlayback");
            assertStrict(excludesSecureDecoders,
                    "R2-Startup: findHardwareDecoderName must exclude .secure decoder names and FEATURE_SecurePlayback");
        } finally {
            deleteDir(idDir);
        }
    }

    // =========================================================================
    // Feature 3: Streaming Performance, Config Propagation & Telemetry
    // =========================================================================

    private static void runTier1And2PerformanceConfigAndTelemetry() throws Exception {
        // Tier 1: Resolution parser
        assertEqualsAlways(1280, GameStreamActivity.parseResolutionWidth("720p"), "720p width");
        assertEqualsAlways(720, GameStreamActivity.parseResolutionHeight("720p"), "720p height");
        assertEqualsAlways(1920, GameStreamActivity.parseResolutionWidth("1080p"), "1080p width");
        assertEqualsAlways(1080, GameStreamActivity.parseResolutionHeight("1080p"), "1080p height");
        assertEqualsAlways(2560, GameStreamActivity.parseResolutionWidth("1440p"), "1440p width");
        assertEqualsAlways(1440, GameStreamActivity.parseResolutionHeight("1440p"), "1440p height");
        assertEqualsAlways(3840, GameStreamActivity.parseResolutionWidth("4k"), "4k width");
        assertEqualsAlways(2160, GameStreamActivity.parseResolutionHeight("4k"), "4k height");
        assertEqualsAlways(3840, GameStreamActivity.parseResolutionWidth("2160p"), "2160p width");
        assertEqualsAlways(2160, GameStreamActivity.parseResolutionHeight("2160p"), "2160p height");
        assertEqualsAlways(1920, GameStreamActivity.parseResolutionWidth(null), "null resolution defaults to 1920");
        assertEqualsAlways(1080, GameStreamActivity.parseResolutionHeight(null), "null resolution defaults to 1080");

        // Tier 1: All 12 Prefs.gaming*() defaults when app == null
        assertEqualsAlways("1080p", Prefs.gamingResolution(), "Prefs.gamingResolution() default");
        assertEqualsAlways(60, Prefs.gamingFps(), "Prefs.gamingFps() default");
        assertEqualsAlways(30, Prefs.gamingBitrateMbps(), "Prefs.gamingBitrateMbps() default");
        assertEqualsAlways("balanced", Prefs.gamingFramePacing(), "Prefs.gamingFramePacing() default");
        assertEqualsAlways("auto", Prefs.gamingCodec(), "Prefs.gamingCodec() default");
        assertEqualsAlways(false, Prefs.gamingHdr(), "Prefs.gamingHdr() default");
        assertEqualsAlways("stereo", Prefs.gamingAudioConfig(), "Prefs.gamingAudioConfig() default");
        assertEqualsAlways(false, Prefs.gamingPlayAudioOnPc(), "Prefs.gamingPlayAudioOnPc() default");
        assertEqualsAlways(true, Prefs.gamingRumble(), "Prefs.gamingRumble() default");
        assertEqualsAlways(true, Prefs.gamingMouseEmulation(), "Prefs.gamingMouseEmulation() default");
        assertEqualsAlways(false, Prefs.gamingPerfOverlay(), "Prefs.gamingPerfOverlay() default");
        assertEqualsAlways(true, Prefs.gamingOptimizeSettings(), "Prefs.gamingOptimizeSettings() default");

        // Tier 1: Prefs.java setter ranges (FPS {30,60,90,120}, bitrate [5,150])
        String strippedPrefs = stripJavaComments(readSourceText("app/src/io/soartv/Prefs.java"));
        String setFpsBlock = extractAllMethodsJoined(strippedPrefs, "setGamingFps");
        assertAlways(setFpsBlock.contains("30") && setFpsBlock.contains("60")
                        && setFpsBlock.contains("90") && setFpsBlock.contains("120"),
                "Prefs.setGamingFps enforces {30, 60, 90, 120}");
        String setBitrateBlock = extractAllMethodsJoined(strippedPrefs, "setGamingBitrateMbps");
        assertAlways(setBitrateBlock.contains("Math.max(5, Math.min(150"),
                "Prefs.setGamingBitrateMbps clamps [5, 150]");

        // Tier 1: MoonBridge video format masks & AudioConfiguration
        assertEqualsAlways(0x0001, MoonBridge.VIDEO_FORMAT_H264, "MoonBridge H264");
        assertEqualsAlways(0x0100, MoonBridge.VIDEO_FORMAT_H265, "MoonBridge H265");
        assertEqualsAlways(0x0200, MoonBridge.VIDEO_FORMAT_H265_MAIN10, "MoonBridge H265 Main10");
        assertEqualsAlways(0x1000, MoonBridge.VIDEO_FORMAT_AV1_MAIN8, "MoonBridge AV1 Main8");
        assertEqualsAlways(0x2000, MoonBridge.VIDEO_FORMAT_AV1_MAIN10, "MoonBridge AV1 Main10");
        assertEqualsAlways((0x03 << 16) | 2, MoonBridge.AUDIO_CONFIGURATION_STEREO.getSurroundAudioInfo(),
                "Stereo surroundAudioInfo");
        assertEqualsAlways((0x3F << 16) | 6, MoonBridge.AUDIO_CONFIGURATION_51_SURROUND.getSurroundAudioInfo(),
                "5.1 surroundAudioInfo");
        assertEqualsAlways((0x63F << 16) | 8, MoonBridge.AUDIO_CONFIGURATION_71_SURROUND.getSurroundAudioInfo(),
                "7.1 surroundAudioInfo");

        // Tier 2: Overload-aware, comment-stripped check + real SunshineStreamClient.startSunshineSession over mTLS
        String strippedSun = stripJavaComments(readSourceText("app/src/io/soartv/SunshineStreamClient.java"));
        String startSessionBlocks = extractAllMethodsJoined(strippedSun, "startSunshineSession");
        assertAlways(!startSessionBlocks.isEmpty(), "SunshineStreamClient.startSunshineSession exists");
        assertAlways(startSessionBlocks.contains("\"&mode=\" + width + \"x\" + height + \"x\" + fps"),
                "SunshineStreamClient /launch encodes mode=WxHxFPS");
        assertAlways(startSessionBlocks.contains("\"&hdrMode=1"),
                "SunshineStreamClient /launch encodes hdrMode=1 when HDR enabled");
        assertAlways(startSessionBlocks.contains("\"&surroundAudioInfo=\" + surroundInfo"),
                "SunshineStreamClient /launch encodes surroundAudioInfo");

        File tmpIdDir = Files.createTempDirectory("soartv_e2e_launch_").toFile();
        try {
            SSLContext sslCtx = createLoopbackServerSslContext("Sunshine Launch Server");
            final AtomicReference<String> capturedLaunchReq = new AtomicReference<String>("");
            try (SSLServerSocket launchServer = (SSLServerSocket) sslCtx.getServerSocketFactory().createServerSocket(
                    0, 8, InetAddress.getByName("127.0.0.1"))) {
                launchServer.setNeedClientAuth(true);
                int port = launchServer.getLocalPort();
                Thread w = new Thread(() -> {
                    while (!launchServer.isClosed()) {
                        try (SSLSocket s = (SSLSocket) launchServer.accept()) {
                            String req = readHttpRequestHeaders(s);
                            String xml;
                            if (req.startsWith("GET /serverinfo")) {
                                xml = "<root status_code=\"200\"><PairStatus>1</PairStatus><currentgame>0</currentgame></root>";
                            } else if (req.startsWith("GET /applist")) {
                                xml = "<root status_code=\"200\">"
                                        + "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Overcooked! 2</AppTitle><ID>728880</ID></App>"
                                        + "</root>";
                            } else {
                                capturedLaunchReq.set(req);
                                xml = "<root status_code=\"200\"><sessionUrl0>rtsp://127.0.0.1:48010</sessionUrl0></root>";
                            }
                            byte[] b = xml.getBytes(StandardCharsets.UTF_8);
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + b.length + "\r\n\r\n";
                            s.getOutputStream().write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            s.getOutputStream().write(b);
                            s.getOutputStream().flush();
                        } catch (Exception ignored) {
                            if (launchServer.isClosed()) break;
                        }
                    }
                });
                w.setDaemon(true);
                w.start();

                int w4k = GameStreamActivity.parseResolutionWidth("4k");
                int h4k = GameStreamActivity.parseResolutionHeight("4k");
                int surround51 = MoonBridge.AUDIO_CONFIGURATION_51_SURROUND.getSurroundAudioInfo();
                SunshineStreamClient.LaunchSession session = SunshineStreamClient.startSunshineSession(
                        null, tmpIdDir, "127.0.0.1", "Overcooked! 2",
                        w4k, h4k, 120, MoonBridge.AUDIO_CONFIGURATION_51_SURROUND,
                        false, true, true, 0, port, 0);
                assertAlways(session != null, "startSunshineSession returns non-null LaunchSession");
                assertEqualsAlways(728880, session.appId, "startSunshineSession resolves Overcooked! 2 appId");
                assertEqualsAlways("rtsp://127.0.0.1:48010", session.rtspSessionUrl,
                        "startSunshineSession returns rtsp sessionUrl0");
                String wireReq = capturedLaunchReq.get();
                assertAlways(wireReq.contains("appid=728880"), "Wire /launch carries appid=728880");
                assertAlways(wireReq.contains("mode=3840x2160x120"), "Wire /launch carries mode=3840x2160x120");
                assertAlways(wireReq.contains("hdrMode=1"), "Wire /launch carries hdrMode=1");
                assertAlways(wireReq.contains("surroundAudioInfo=" + surround51),
                        "Wire /launch carries 5.1 surroundAudioInfo");
                assertAlways(wireReq.contains("localAudioPlayMode=0"), "Wire /launch carries localAudioPlayMode=0");
                assertAlways(wireReq.contains("sops=1"), "Wire /launch carries sops=1");
            }
        } finally {
            deleteDir(tmpIdDir);
        }

        // Tier 2 (Strict M3): GameStreamActivity decoder config, submit hotpath, telemetry, and process/GC isolation
        String strippedGs = stripJavaComments(readSourceText("app/src/io/soartv/GameStreamActivity.java"));

        String drSetupBlock = extractAllMethodsJoined(strippedGs, "drSetup");
        String configDecoderBlock = extractAllMethodsJoined(strippedGs, "configureLowLatencyDecoder");
        boolean hasBareBufferedFallback = Pattern.compile("MediaFormat\\.createVideoFormat\\s*\\(")
                .matcher(drSetupBlock).find() && !drSetupBlock.contains("KEY_LOW_LATENCY");
        int directCatches = configDecoderBlock.isEmpty()
                ? 0
                : (configDecoderBlock.split("\\bcatch\\s*\\(", -1).length - 1);
        int helperTryCatchCalls = countTryCatchHelperCalls(
                configDecoderBlock, strippedGs, "configureLowLatencyDecoder");
        boolean callsConfigureHelper = drSetupBlock.contains("configureLowLatencyDecoder(");
        boolean stepwiseVendorConfig = !hasBareBufferedFallback
                && callsConfigureHelper
                && (directCatches >= 3 || helperTryCatchCalls >= 3)
                && configDecoderBlock.contains("KEY_LOW_LATENCY")
                && configDecoderBlock.contains("KEY_PRIORITY");
        assertStrict(stepwiseVendorConfig,
                "R2-Perf: configureLowLatencyDecoder must apply low-latency/priority/operating-rate/SoC keys stepwise without falling back to bare MediaFormat.createVideoFormat(mime, width, height)");

        String submitBlock = extractAllMethodsJoined(strippedGs, "drSubmitDecodeUnit");
        boolean avoidsPerFrameToByteArray = !submitBlock.contains("accessUnitScratch.toByteArray()");
        boolean avoids12msCodecLockStall = !strippedGs.contains("DEQUEUE_INPUT_TIMEOUT_US = 12_000L")
                && !(submitBlock.contains("synchronized (codecLock)") && submitBlock.contains("dequeueInputBuffer(DEQUEUE_INPUT_TIMEOUT_US)"));
        assertStrict(avoidsPerFrameToByteArray && avoids12msCodecLockStall,
                "R2-Perf: drSubmitDecodeUnit must avoid per-frame toByteArray() allocations and 12ms codecLock stalls on CAPABILITY_DIRECT_SUBMIT");

        boolean hasLiveTelemetryLogs = Pattern.compile(
                "\\bAppLog\\.(?:i|d|v)\\s*\\((?:[^;\"]|\"(?:\\\\.|[^\"\\\\])*\")*?(?:fps|drop|decode|telemetry)(?:[^;\"]|\"(?:\\\\.|[^\"\\\\])*\")*\\)\\s*;",
                Pattern.CASE_INSENSITIVE).matcher(strippedGs).find();
        assertStrict(hasLiveTelemetryLogs,
                "R2-Perf: GameStreamActivity must log live FPS/drop/decode telemetry via AppLog");

        String manifestSrc = readSourceText("app/AndroidManifest.xml")
                .replaceAll("<!--[\\s\\S]*?-->", "");
        Matcher actMatcher = Pattern.compile(
                "<activity[^>]*android:name=\"\\.GameStreamActivity\"(?:[^>]*/>|[\\s\\S]*?</activity>)")
                .matcher(manifestSrc);
        String gameStreamActivityXml = actMatcher.find() ? actMatcher.group(0) : "";
        boolean isolatedProcess = !gameStreamActivityXml.isEmpty()
                && !gameStreamActivityXml.contains("android:process=\":home\"");
        String screensaverSrc = stripJavaComments(readSourceText("app/src/io/soartv/ScreensaverCatalog.java"));
        boolean pausesHomeGcSources = screensaverSrc.contains("setBackgroundActivityPaused")
                || screensaverSrc.contains("streamingPaused");
        assertStrict(isolatedProcess || pausesHomeGcSources,
                "R2-Perf: AndroidManifest.xml must isolate GameStreamActivity from ':home' OR pause background ':home' GC sources during streaming");
    }

    // =========================================================================
    // Tier 3: Cross-Feature Contracts
    // =========================================================================

    private static void runTier3CrossFeatureContracts() throws Exception {
        // Cross-feature: Prefs defaults + GameStreamActivity resolution parser + 4-profile SunshineStreamClient /launch matrix
        File tmpIdDir = Files.createTempDirectory("soartv_e2e_matrix_").toFile();
        try {
            SSLContext sslCtx = createLoopbackServerSslContext("Sunshine Matrix Server");
            final AtomicReference<String> capturedLaunchReq = new AtomicReference<String>("");
            final AtomicBoolean emptyAppList = new AtomicBoolean(false);
            final AtomicBoolean launchError503 = new AtomicBoolean(false);

            try (SSLServerSocket matrixServer = (SSLServerSocket) sslCtx.getServerSocketFactory().createServerSocket(
                    0, 8, InetAddress.getByName("127.0.0.1"))) {
                matrixServer.setNeedClientAuth(true);
                int port = matrixServer.getLocalPort();
                Thread w = new Thread(() -> {
                    while (!matrixServer.isClosed()) {
                        try (SSLSocket s = (SSLSocket) matrixServer.accept()) {
                            String req = readHttpRequestHeaders(s);
                            String xml;
                            if (req.startsWith("GET /serverinfo")) {
                                xml = "<root status_code=\"200\"><PairStatus>1</PairStatus><currentgame>0</currentgame></root>";
                            } else if (req.startsWith("GET /applist")) {
                                if (emptyAppList.get()) {
                                    xml = "<root status_code=\"200\"></root>";
                                } else {
                                    xml = "<root status_code=\"200\">"
                                            + "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Overcooked! 2</AppTitle><ID>728880</ID></App>"
                                            + "</root>";
                                }
                            } else {
                                capturedLaunchReq.set(req);
                                if (launchError503.get()) {
                                    xml = "<root status_code=\"503\"></root>";
                                } else {
                                    xml = "<root status_code=\"200\"><sessionUrl0>rtsp://127.0.0.1:48010</sessionUrl0></root>";
                                }
                            }
                            byte[] b = xml.getBytes(StandardCharsets.UTF_8);
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + b.length + "\r\n\r\n";
                            s.getOutputStream().write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            s.getOutputStream().write(b);
                            s.getOutputStream().flush();
                        } catch (Exception ignored) {
                            if (matrixServer.isClosed()) break;
                        }
                    }
                });
                w.setDaemon(true);
                w.start();

                // 1. Null-context Prefs defaults -> GameStreamActivity parser -> SunshineStreamClient wire query
                int defW = GameStreamActivity.parseResolutionWidth(Prefs.gamingResolution());
                int defH = GameStreamActivity.parseResolutionHeight(Prefs.gamingResolution());
                int defFps = Prefs.gamingFps();
                MoonBridge.AudioConfiguration defAudio = "7.1".equals(Prefs.gamingAudioConfig())
                        ? MoonBridge.AUDIO_CONFIGURATION_71_SURROUND
                        : ("5.1".equals(Prefs.gamingAudioConfig())
                                ? MoonBridge.AUDIO_CONFIGURATION_51_SURROUND
                                : MoonBridge.AUDIO_CONFIGURATION_STEREO);
                capturedLaunchReq.set("");
                SunshineStreamClient.LaunchSession defSession = SunshineStreamClient.startSunshineSession(
                        null, tmpIdDir, "127.0.0.1", "Overcooked! 2",
                        defW, defH, defFps, defAudio,
                        Prefs.gamingPlayAudioOnPc(), Prefs.gamingOptimizeSettings(), Prefs.gamingHdr(),
                        0, port, 0);
                assertAlways(defSession != null && defSession.appId == 728880,
                        "Default Prefs startSunshineSession resolves Overcooked! 2");
                String defReq = capturedLaunchReq.get();
                assertAlways(defReq.contains("mode=1920x1080x60"),
                        "Default Prefs wire /launch encodes mode=1920x1080x60");
                assertAlways(defReq.contains("sops=1") && defReq.contains("localAudioPlayMode=0"),
                        "Default Prefs wire /launch encodes sops=1 & localAudioPlayMode=0");
                assertAlways(defReq.contains("surroundAudioInfo=" + defAudio.getSurroundAudioInfo()),
                        "Default Prefs wire /launch encodes stereo surroundAudioInfo");
                assertAlways(!defReq.contains("hdrMode=1"),
                        "Default Prefs wire /launch omits hdrMode=1 when HDR=false");

                // 2. 4-profile resolution x FPS x AudioConfig x HDR matrix through real startSunshineSession
                String[] resCases = new String[] {"720p", "1080p", "1440p", "4k"};
                int[] expectedW = new int[] {1280, 1920, 2560, 3840};
                int[] expectedH = new int[] {720, 1080, 1440, 2160};
                int[] fpsCases = new int[] {30, 60, 90, 120};
                MoonBridge.AudioConfiguration[] audioCases = new MoonBridge.AudioConfiguration[] {
                        MoonBridge.AUDIO_CONFIGURATION_STEREO,
                        MoonBridge.AUDIO_CONFIGURATION_51_SURROUND,
                        MoonBridge.AUDIO_CONFIGURATION_51_SURROUND,
                        MoonBridge.AUDIO_CONFIGURATION_71_SURROUND
                };
                boolean[] hdrCases = new boolean[] {false, false, true, true};

                for (int i = 0; i < resCases.length; i++) {
                    int wPx = GameStreamActivity.parseResolutionWidth(resCases[i]);
                    int hPx = GameStreamActivity.parseResolutionHeight(resCases[i]);
                    assertEqualsAlways(expectedW[i], wPx, "Matrix width " + resCases[i]);
                    assertEqualsAlways(expectedH[i], hPx, "Matrix height " + resCases[i]);

                    capturedLaunchReq.set("");
                    SunshineStreamClient.LaunchSession matSession = SunshineStreamClient.startSunshineSession(
                            null, tmpIdDir, "127.0.0.1", "Overcooked! 2",
                            wPx, hPx, fpsCases[i], audioCases[i],
                            false, true, hdrCases[i], 0, port, 0);
                    assertAlways(matSession != null && matSession.appId == 728880,
                            "Matrix startSunshineSession succeeds for " + resCases[i] + "@" + fpsCases[i]);
                    String reqStr = capturedLaunchReq.get();
                    String expectedMode = "mode=" + expectedW[i] + "x" + expectedH[i] + "x" + fpsCases[i];
                    assertAlways(reqStr.contains(expectedMode),
                            "Matrix wire /launch encodes " + expectedMode);
                    assertAlways(reqStr.contains("surroundAudioInfo=" + audioCases[i].getSurroundAudioInfo()),
                            "Matrix wire /launch encodes surroundAudioInfo for " + resCases[i]);
                    assertEqualsAlways(hdrCases[i], reqStr.contains("hdrMode=1"),
                            "Matrix wire /launch hdrMode=1 matches hdr=" + hdrCases[i] + " for " + resCases[i]);
                }

                // 3. Negative checks: invalid resolution fallback + empty /applist + /launch 503 rejection
                assertEqualsAlways(1920, GameStreamActivity.parseResolutionWidth("invalid"),
                        "Invalid resolution width falls back to 1920");
                assertEqualsAlways(1080, GameStreamActivity.parseResolutionHeight("invalid"),
                        "Invalid resolution height falls back to 1080");

                emptyAppList.set(true);
                boolean emptyRejected = false;
                try {
                    SunshineStreamClient.startSunshineSession(
                            null, tmpIdDir, "127.0.0.1", "Overcooked! 2",
                            1920, 1080, 60, MoonBridge.AUDIO_CONFIGURATION_STEREO,
                            false, true, false, 0, port, 0);
                } catch (IOException expected) {
                    emptyRejected = true;
                }
                assertAlways(emptyRejected, "startSunshineSession rejects empty /applist with IOException");

                emptyAppList.set(false);
                launchError503.set(true);
                boolean launch503Rejected = false;
                try {
                    SunshineStreamClient.startSunshineSession(
                            null, tmpIdDir, "127.0.0.1", "Overcooked! 2",
                            1920, 1080, 60, MoonBridge.AUDIO_CONFIGURATION_STEREO,
                            false, true, false, 0, port, 0);
                } catch (IOException expected) {
                    launch503Rejected = true;
                }
                assertAlways(launch503Rejected, "startSunshineSession rejects /launch status_code=503 with IOException");
            }
        } finally {
            deleteDir(tmpIdDir);
        }

        // Cross-feature: Immediate termination clears ActiveStreamingSession even < 3s after launch
        try (ServerSocket termSrv = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            int port = termSrv.getLocalPort();
            AtomicReference<String> termBody = new AtomicReference<String>("");
            Thread t = new Thread(() -> {
                try (Socket c = termSrv.accept()) {
                    InputStream in = c.getInputStream();
                    byte[] buf = new byte[2048];
                    int n = in.read(buf);
                    String raw = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
                    int idx = raw.indexOf("\r\n\r\n");
                    if (idx >= 0) termBody.set(raw.substring(idx + 4));
                    String body = "{\"ok\":true,\"terminated\":true,\"pcRestored\":true}";
                    String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n";
                    c.getOutputStream().write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                    c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                    c.getOutputStream().flush();
                } catch (Exception ignored) {
                }
            });
            t.setDaemon(true);
            t.start();

            GamingZone.setActiveStreamingSession(new GamingZone.ActiveStreamingSession(
                    "steam_728880", "Overcooked! 2", "127.0.0.1", port, System.currentTimeMillis() - 100L));
            boolean terminated = GamingZone.terminateActivePCGameSync(null, "steam_728880", "e2e_test_exit");
            assertAlways(terminated, "terminateActivePCGameSync returns true on 200 OK");
            assertAlways(termBody.get().contains("steam_728880"), "Terminate payload contains gameId");
            assertEqualsAlways(null, GamingZone.getActiveStreamingSession(), "ActiveStreamingSession cleared on terminate");
        }
    }

    // =========================================================================
    // Tier 4: R3 Settings Isolation & End-to-End Structural Verification
    // =========================================================================

    private static void runTier4EndToEndAndR3Isolation() throws Exception {
        SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
        SettingsTree.Node root = SettingsTree.buildScreensaverTree(snap);
        SettingsTree.Node gaming = root.findById("gaming_settings");
        assertAlways(gaming != null, "SettingsTree exposes gaming_settings root");
        assertAlways(gaming.findById("gaming_stream_quality") != null, "gaming_stream_quality present");
        assertAlways(gaming.findById("gaming_stream_res") != null, "gaming_stream_res present");
        assertAlways(gaming.findById("gaming_stream_fps") != null, "gaming_stream_fps present");
        assertAlways(gaming.findById("gaming_stream_bitrate") != null, "gaming_stream_bitrate present");
        assertAlways(gaming.findById("gaming_stream_pacing") != null, "gaming_stream_pacing present");
        assertAlways(gaming.findById("gaming_stream_codec") != null, "gaming_stream_codec present");
        assertNoMoonlightExposure(gaming);

        String strippedSt = stripJavaComments(readSourceText("app/src/io/soartv/SettingsTree.java"));
        String buildGamingBlock = extractAllMethodsJoined(strippedSt, "buildGamingNode");
        assertAlways(!buildGamingBlock.isEmpty(), "SettingsTree.buildGamingNode exists");
        assertAlways(!buildGamingBlock.toLowerCase(Locale.ROOT).contains("moonlight"),
                "R3 Isolation: SettingsTree.buildGamingNode exposes zero Moonlight UI");

        String settingsRendererSrc = stripJavaComments(
                readSourceText("app/src/io/soartv/SettingsScreenRenderer.java"));
        assertAlways(!settingsRendererSrc.toLowerCase(Locale.ROOT).contains("moonlight"),
                "R3 Isolation: SettingsScreenRenderer contains zero Moonlight UI");
    }

    private static void assertNoMoonlightExposure(SettingsTree.Node node) {
        if (node == null) return;
        String lowerTitle = node.title.toLowerCase(Locale.ROOT);
        String lowerSub = node.subtitle.toLowerCase(Locale.ROOT);
        String lowerId = node.id.toLowerCase(Locale.ROOT);
        assertAlways(!lowerTitle.contains("moonlight") && !lowerSub.contains("moonlight") && !lowerId.contains("moonlight"),
                "R3 Isolation: Gaming Settings node must not expose Moonlight UI: " + node.id);
        for (SettingsTree.Node child : node.children) {
            assertNoMoonlightExposure(child);
        }
    }
}
