package io.mytv;

import com.limelight.nvstream.jni.MoonBridge;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * TDD Unit & Protocol Suite for Native Game Streaming, Zero-Moonlight-UI Settings,
 * Pre-Stream Game Readiness Gate, HTTP Status Validation, and Deterministic PC Exit.
 *
 * <p><b>Why:</b> Guarantees all 5 user-reported PC game streaming defects are resolved with
 * deterministic, offline-verifiable contracts on headless Desktop JVMs before APK deployment.
 * <p><b>What:</b> Tests {@link MoonBridge} JNI bit-packing & headless loading, {@link SunshineStreamClient}
 * X.509 RSA-2048 certificate generation + GameStream AES-128-ECB pairing + XML parsing,
 * {@link Prefs} & {@link SettingsTree} native Gaming Stream settings, {@link HomeApps} Moonlight hiding,
 * {@link GamingZone} HTTP status validation (`401` rejection) + immediate termination, and
 * {@link HomeActivity#EXTRA_OPEN_GAMING} return routing.
 */
public final class NativeGameStreamingTests {

    private static int passed = 0;

    private static void assertEquals(Object expected, Object actual, String message) {
        if ((expected == null && actual != null) || (expected != null && !expected.equals(actual))) {
            throw new AssertionError(message + " | expected=" + expected + ", actual=" + actual);
        }
        passed++;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        passed++;
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
        passed++;
    }

    public static void main(String[] args) throws Exception {
        runAllTests();
        System.out.println("ALL " + passed + " NATIVE GAME STREAMING ASSERTIONS PASSED.");
    }

    public static void runAllTests() throws Exception {
        testMoonBridgeConstantsAndAudioBitPacking();
        testGamingPcTokenSecretsVaultFallback();
        testHttpStatusValidationRejects401AndErrorJson();
        testNativeGamingSettingsInPrefsAndSettingsTree();
        testMoonlightAndSteamLinkHiddenFromAllApps();
        testSunshineStreamClientCryptoCertsAndXmlProtocol();
        testSunshineCleartextLanSocketAndHostGuard();
        testAutomaticLanPcDiscoveryAcrossIpChanges();
        testImmediateTerminationAndReturnToGamingScreenContract();
        testGameStreamResolutionAndNativeSoPackaging();
        testSunshineMtlsConnectionCloseSingleSocketAndSingleServerInfo();
        testSunshineMtls401AndPairStatusZeroRecovery();
        testSunshineMtlsKeepAliveChunkedAndResetIdentityNoSocketLeak();
        testExtractXmlStatusCodeElementFallbackAndScanRejection();
        testGameStreamSecureDecoderExclusion();
        testGamingZoneM2DiscoveryAndFallbackContracts();
        testIsChunkedBodyCompleteOverflowGuard();
        testM3DecoderOperatingRateAndVendorKeyMatching();
        testM3ZeroAllocAccessUnitPackingAndPendingFrameRing();
        testM3FramePacingMonotonicityAndZero2FrameStall();
        testM3TelemetryFormattingAndSecretsCrossProcessRefresh();
        testM3CrossProcessActiveStreamingSessionFileSync();
        testM3ScreensaverCatalogAndRecsBackgroundPauseGuards();
        testSunshineMtlsTls12EngineAliasAndSslExceptionRecovery();
        testSunshineMidSessionSslAlert80RecoveryAndRawLanChunked();
        testScreensaverCatalogPrefetchResumeRaceNoDrop();
        testGamingZoneColdStartPrelaunchWaitHeadroom();
        testMtk4kLowLatencyCrashGuardAndOperatingRate();
        testGameLaunchSpinnerTrueSeamlessLoop();
        testDynamicGamingSettingsTipsNoHardcodedHardware();
        testEpicLauncherStatusAndSelectiveCopy();
        testHeroicLauncherCopyBadgesAndSunshineOnDemandStandby();
        testStreamQualityModalStateSimplifiedThreeColumnsAndColors();
        testSettingsStreamRowsColoredTonesAndNoQuickTuneModal();
    }

    private static void testMoonBridgeConstantsAndAudioBitPacking() {
        assertEquals(0x0001, MoonBridge.VIDEO_FORMAT_H264, "MoonBridge H.264 format mask");
        assertEquals(0x0100, MoonBridge.VIDEO_FORMAT_H265, "MoonBridge HEVC Main format mask");
        assertEquals(0x0200, MoonBridge.VIDEO_FORMAT_H265_MAIN10, "MoonBridge HEVC Main10 HDR format mask");
        assertEquals(0x1000, MoonBridge.VIDEO_FORMAT_AV1_MAIN8, "MoonBridge AV1 Main8 format mask");
        assertEquals(0x2000, MoonBridge.VIDEO_FORMAT_AV1_MAIN10, "MoonBridge AV1 Main10 format mask");

        assertEquals(0x000302CA, MoonBridge.AUDIO_CONFIGURATION_STEREO.toInt(),
                "Stereo AudioConfiguration packs (0x03<<16)|(2<<8)|0xCA");
        assertEquals(0x003F06CA, MoonBridge.AUDIO_CONFIGURATION_51_SURROUND.toInt(),
                "5.1 AudioConfiguration packs (0x3F<<16)|(6<<8)|0xCA");
        assertEquals(0x063F08CA, MoonBridge.AUDIO_CONFIGURATION_71_SURROUND.toInt(),
                "7.1 AudioConfiguration packs (0x63F<<16)|(8<<8)|0xCA");
        assertEquals((0x3F << 16) | 6, MoonBridge.AUDIO_CONFIGURATION_51_SURROUND.getSurroundAudioInfo(),
                "5.1 surroundAudioInfo packs (channelMask<<16)|channelCount for GameStream URL");

        try {
            java.lang.reflect.Method startConn = MoonBridge.class.getDeclaredMethod(
                    "startConnection",
                    String.class, String.class, String.class, String.class,
                    int.class, int.class, int.class, int.class, int.class,
                    int.class, int.class, int.class, int.class, int.class,
                    byte[].class, byte[].class, int.class, int.class, int.class);
            assertEquals(19, startConn.getParameterCount(),
                    "MoonBridge.startConnection matches libmoonlight-core.so 19-parameter JNI signature");
            assertEquals(int.class, startConn.getReturnType(),
                    "MoonBridge.startConnection returns int");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("MoonBridge.startConnection must match 19-param JNI signature: " + e.getMessage());
        }

        java.util.Set<String> nativeMethodNames = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : MoonBridge.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isNative(m.getModifiers())) {
                nativeMethodNames.add(m.getName());
            }
        }
        assertFalse(nativeMethodNames.contains("cleanupBridge"),
                "Unexported native cleanupBridge must not be declared in MoonBridge");
        assertFalse(nativeMethodNames.contains("sendMouseScroll"),
                "Unexported native sendMouseScroll must not be declared in MoonBridge");
        assertFalse(nativeMethodNames.contains("sendMouseHScroll"),
                "Unexported native sendMouseHScroll must not be declared in MoonBridge");
    }

    private static void testGamingPcTokenSecretsVaultFallback() throws Exception {
        File dir = Files.createTempDirectory("mytv_gaming_tok_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(dir);
            String tok = "9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a39281706f5e4d3c2b1a0";
            assertTrue(Secrets.putSecret(null, Secrets.GAMING_PC_TOKEN, tok), "Save gaming_pc_token to Secrets");
            assertEquals(tok, Prefs.gamingPcToken(),
                    "Prefs.gamingPcToken() must fall back to encrypted Secrets.GAMING_PC_TOKEN when SharedPreferences is empty");
            assertEquals(tok, GamingZone.getLaptopAuthToken(null),
                    "GamingZone.getLaptopAuthToken resolves Secrets.GAMING_PC_TOKEN");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteDir(dir);
        }
    }

    private static void testHttpStatusValidationRejects401AndErrorJson() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread worker = new Thread(() -> {
                try (Socket client = server.accept()) {
                    InputStream in = client.getInputStream();
                    byte[] buf = new byte[1024];
                    in.read(buf);
                    String body = "{\"ok\":false,\"error\":\"unauthorized\",\"pairingRequired\":true}";
                    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
                    String resp = "HTTP/1.1 401 Unauthorized\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + bodyBytes.length + "\r\n\r\n";
                    OutputStream out = client.getOutputStream();
                    out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
                    out.write(bodyBytes);
                    out.flush();
                } catch (Exception ignored) {
                }
            });
            worker.setDaemon(true);
            worker.start();

            GamingZone.HttpResult res = GamingZone.rawHttpRequestWithStatus(
                    null, "127.0.0.1", port, "POST", "/api/gaming/launch", "{}", 2000);
            assertEquals(401, res.statusCode, "Parses HTTP 401 status code from raw socket response");
            assertFalse(res.isSuccess(), "HTTP 401 response must NOT be treated as a successful PC launch");
        }
    }

    private static void testNativeGamingSettingsInPrefsAndSettingsTree() {
        // Verify default smooth native streaming settings in headless mode
        assertEquals("1080p", Prefs.gamingResolution(), "Default stream resolution is 1080p for zero-stutter Optimus encode");
        assertEquals(60, Prefs.gamingFps(), "Default stream FPS is 60 locked to TV 60Hz VSYNC");
        assertEquals(30, Prefs.gamingBitrateMbps(), "Default bitrate is 30 Mbps");
        assertEquals("balanced", Prefs.gamingFramePacing(), "Default frame pacing is Balanced (Choreographer VSYNC)");
        assertEquals("auto", Prefs.gamingCodec(), "Default video codec is Auto");
        assertFalse(Prefs.gamingHdr(), "Default HDR is false");
        assertEquals("stereo", Prefs.gamingAudioConfig(), "Default audio configuration is Stereo");
        assertFalse(Prefs.gamingPlayAudioOnPc(), "Default Play Audio on PC is false (PC stays muted)");
        assertTrue(Prefs.gamingRumble(), "Default gamepad rumble is true");
        assertTrue(Prefs.gamingMouseEmulation(), "Default long-press Start mouse emulation is true");
        assertFalse(Prefs.gamingPerfOverlay(), "Default performance overlay is false");
        assertTrue(Prefs.gamingOptimizeSettings(), "Default optimize game settings is true");

        SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
        SettingsTree.Node root = SettingsTree.buildScreensaverTree(snap);
        SettingsTree.Node gaming = root.findById("gaming_settings");
        assertTrue(gaming != null, "Gaming category exists in SettingsTree");
        assertTrue(gaming.findById("gaming_stream_quality") != null, "Stream Quality & Video settings group present in Gaming Settings");
        assertTrue(gaming.findById("gaming_stream_res") != null, "Resolution setting node present");
        assertTrue(gaming.findById("gaming_stream_fps") != null, "Frame Rate setting node present");
        assertTrue(gaming.findById("gaming_stream_bitrate") != null, "Video Bitrate setting node present");
        assertTrue(gaming.findById("gaming_stream_pacing") != null, "Frame Pacing setting node present");
        assertTrue(gaming.findById("gaming_stream_codec") != null, "Video Codec setting node present");
        assertTrue(gaming.findById("gaming_stream_hdr") != null, "HDR toggle node present");
        assertTrue(gaming.findById("gaming_stream_audio_controls") != null, "Audio & Controls settings group present in Gaming Settings");
        assertTrue(gaming.findById("gaming_stream_audio_cfg") != null, "Surround Audio config node present");
        assertTrue(gaming.findById("gaming_stream_pc_audio") != null, "Play Audio on PC toggle present");
        assertTrue(gaming.findById("gaming_stream_rumble") != null, "Gamepad Rumble toggle present");
        assertTrue(gaming.findById("gaming_stream_mouse_emu") != null, "Mouse Emulation toggle present");
        assertTrue(gaming.findById("gaming_stream_perf_overlay") != null, "Performance Overlay toggle present");

        // Ensure zero external "Moonlight" branding is shown in Gaming settings titles
        assertNoExternalMoonlightBranding(gaming);
    }

    private static void assertNoExternalMoonlightBranding(SettingsTree.Node node) {
        if (node == null) return;
        assertFalse(node.title.toLowerCase().contains("moonlight"),
                "Gaming settings node title must never expose Moonlight branding: " + node.title);
        for (SettingsTree.Node child : node.children) {
            assertNoExternalMoonlightBranding(child);
        }
    }

    private static void testMoonlightAndSteamLinkHiddenFromAllApps() throws Exception {
        assertTrue(HomeApps.isHidden("com.limelight"),
                "Moonlight package com.limelight must be hidden from All Apps & Recents so user never sees Moonlight UI");
        assertTrue(HomeApps.isHidden("com.valvesoftware.steamlink"),
                "Steam Link package com.valvesoftware.steamlink hidden from All Apps");
        File rendererFile = new File("app/src/io/mytv/GamingScreenRenderer.java");
        if (rendererFile.exists()) {
            String src = new String(Files.readAllBytes(rendererFile.toPath()), StandardCharsets.UTF_8);
            assertFalse(src.contains("\"app_moonlight\""),
                    "GamingScreenRenderer must not expose an app_moonlight ActionCard");
            assertFalse(src.contains("via Moonlight"),
                    "GamingScreenRenderer subtitles must not expose Moonlight branding");
            assertTrue(src.contains("\"gaming_stream_settings\""),
                    "GamingScreenRenderer exposes native Stream Quality & Settings ActionCard");
        }
    }

    private static void testSunshineStreamClientCryptoCertsAndXmlProtocol() throws Exception {
        File dir = Files.createTempDirectory("mytv_sunshine_id_").toFile();
        try {
            SunshineStreamClient.ClientIdentity id1 = SunshineStreamClient.loadOrCreateIdentity(dir);
            assertTrue(id1.uniqueId.length() == 16, "GameStream uniqueid is 16 hex chars");
            assertTrue(id1.certPem.contains("-----BEGIN CERTIFICATE-----"), "PEM certificate header present");
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate parsed = (X509Certificate) cf.generateCertificate(
                    new java.io.ByteArrayInputStream(id1.certDer));
            parsed.verify(id1.keyPair.getPublic());
            passed++;

            // Persistence round-trip
            SunshineStreamClient.ClientIdentity id2 = SunshineStreamClient.loadOrCreateIdentity(dir);
            assertEquals(id1.uniqueId, id2.uniqueId, "Identity uniqueId persists across loads");
            assertEquals(id1.certPem, id2.certPem, "Identity PEM certificate persists across loads");

            // AES-128-ECB + SHA-256 salted PIN key derivation & round-trip encryption
            byte[] salt = SunshineStreamClient.hexToBytes("0102030405060708090a0b0c0d0e0f10");
            byte[] aesKey = SunshineStreamClient.derivePairingAesKey(salt, "1234");
            assertEquals(16, aesKey.length, "Derived AES-128 key is 16 bytes");
            byte[] plain = "0123456789ABCDEF0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
            byte[] enc = SunshineStreamClient.aesEcbEncrypt(aesKey, plain);
            byte[] dec = SunshineStreamClient.aesEcbDecrypt(aesKey, enc);
            assertEquals(new String(plain, StandardCharsets.UTF_8), new String(dec, StandardCharsets.UTF_8),
                    "AES-128-ECB no-padding round-trip matches GameStream pairing wire format");

            // XML parser for /serverinfo, /applist, and /launch
            String sampleServerInfo = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<root status_code=\"200\"><hostname>GAMING-PC</hostname>"
                    + "<PairStatus>1</PairStatus><currentgame>0</currentgame></root>";
            assertEquals("200", SunshineStreamClient.extractXmlStatusCode(sampleServerInfo), "Extracts XML status_code");
            assertEquals("1", SunshineStreamClient.extractXmlTag(sampleServerInfo, "PairStatus"), "Extracts PairStatus");

            String sampleAppList = "<root status_code=\"200\">"
                    + "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Desktop</AppTitle><ID>881448767</ID></App>"
                    + "<App><IsHdrSupported>0</IsHdrSupported><AppTitle>Overcooked! 2</AppTitle><ID>123456</ID></App>"
                    + "</root>";
            List<SunshineStreamClient.SunshineApp> apps = SunshineStreamClient.parseAppListXml(sampleAppList);
            assertEquals(2, apps.size(), "Parses 2 Sunshine apps from /applist XML");
            assertEquals("Desktop", apps.get(0).title, "First app title is Desktop");
            assertEquals(881448767, apps.get(0).id, "First app ID parsed");
            assertEquals(881448767, SunshineStreamClient.findTargetAppId(apps, "Desktop"), "Finds exact Desktop app ID");
        } finally {
            deleteDir(dir);
        }
    }

    private static void testSunshineCleartextLanSocketAndHostGuard() throws Exception {
        // 1. Private/Loopback LAN host allowlist strictly allows RFC-1918 / loopback / mDNS and blocks public internet
        assertTrue(SunshineStreamClient.isAllowedCleartextLanHost("192.168.1.12"), "Allows 192.168.1.12");
        assertTrue(SunshineStreamClient.isAllowedCleartextLanHost("192.168.1.44"), "Allows 192.168.1.44");
        assertTrue(SunshineStreamClient.isAllowedCleartextLanHost("10.0.0.15"), "Allows 10.0.0.15");
        assertTrue(SunshineStreamClient.isAllowedCleartextLanHost("172.16.5.9"), "Allows 172.16.5.9");
        assertTrue(SunshineStreamClient.isAllowedCleartextLanHost("172.31.255.254"), "Allows 172.31.255.254");
        assertTrue(SunshineStreamClient.isAllowedCleartextLanHost("169.254.10.20"), "Allows 169.254.x.x link-local");
        assertTrue(SunshineStreamClient.isAllowedCleartextLanHost("127.0.0.1"), "Allows 127.0.0.1 loopback");
        assertTrue(SunshineStreamClient.isAllowedCleartextLanHost("gaming-laptop.local"), "Allows .local mDNS");
        assertFalse(SunshineStreamClient.isAllowedCleartextLanHost("172.32.0.1"), "Rejects non-private 172.32.x.x");
        assertFalse(SunshineStreamClient.isAllowedCleartextLanHost("8.8.8.8"), "Rejects public IP 8.8.8.8");
        assertFalse(SunshineStreamClient.isAllowedCleartextLanHost("store.steampowered.com"), "Rejects public domain");

        // 2. Source contract: SunshineStreamClient must NEVER use HttpURLConnection for cleartext http://
        File clientFile = new File("app/src/io/mytv/SunshineStreamClient.java");
        if (clientFile.exists()) {
            String src = new String(Files.readAllBytes(clientFile.toPath()), StandardCharsets.UTF_8);
            assertFalse(src.contains("(HttpURLConnection)"),
                    "SunshineStreamClient must not cast to HttpURLConnection (blocked by cleartextTrafficPermitted=false)");
        }

        // 3. Raw TCP socket HTTP/1.1 GET parses Content-Length XML cleanly without HttpURLConnection
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread worker = new Thread(() -> {
                try (Socket client = server.accept()) {
                    InputStream in = client.getInputStream();
                    byte[] buf = new byte[1024];
                    in.read(buf);
                    String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                            + "<root status_code=\"200\"><paired>1</paired><hostname>Gaming-Laptop</hostname></root>";
                    byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
                    String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + xmlBytes.length + "\r\n\r\n";
                    OutputStream out = client.getOutputStream();
                    out.write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                    out.write(xmlBytes);
                    out.flush();
                } catch (Exception ignored) {
                }
            });
            worker.setDaemon(true);
            worker.start();

            String xml = SunshineStreamClient.rawLanHttpGet("127.0.0.1", port, "/pair?phrase=getservercert", 2000, 2000);
            assertEquals("200", SunshineStreamClient.extractXmlStatusCode(xml), "Raw LAN HTTP GET extracts 200 status");
            assertEquals("1", SunshineStreamClient.extractXmlTag(xml, "paired"), "Raw LAN HTTP GET extracts <paired>1</paired>");
            assertEquals("Gaming-Laptop", SunshineStreamClient.extractXmlTag(xml, "hostname"), "Raw LAN HTTP GET extracts hostname");
        }
    }

    private static void testAutomaticLanPcDiscoveryAcrossIpChanges() throws Exception {
        File secretDir = Files.createTempDirectory("mytv_lan_disc_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(secretDir);
            final String pairedTok = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
            Secrets.putSecret(null, Secrets.GAMING_PC_TOKEN, pairedTok);

            // Server A: Spoofed/wrong LAN HTTP server on portA (returns authenticated=false)
            // Server B: Genuine Gaming PC on portB after DHCP IP/endpoint change (verifies X-MyTV-Token -> authenticated=true)
            try (ServerSocket fakeServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                 ServerSocket realServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                 java.net.DatagramSocket udpServer = new java.net.DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {

                final int fakePort = fakeServer.getLocalPort();
                final int realPort = realServer.getLocalPort();
                final int udpPort = udpServer.getLocalPort();

                Thread fakeWorker = new Thread(() -> {
                    while (!fakeServer.isClosed()) {
                        try (Socket c = fakeServer.accept()) {
                            byte[] b = new byte[1024];
                            c.getInputStream().read(b);
                            String body = "{\"ok\":true,\"agentService\":\"mytv-laptop-gaming-agent\",\"authenticated\":false}";
                            String resp = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
                            c.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception ignored) {
                            break;
                        }
                    }
                });
                fakeWorker.setDaemon(true);
                fakeWorker.start();

                Thread realWorker = new Thread(() -> {
                    while (!realServer.isClosed()) {
                        try (Socket c = realServer.accept()) {
                            byte[] b = new byte[2048];
                            int n = c.getInputStream().read(b);
                            String reqStr = n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8) : "";
                            boolean authed = reqStr.contains("X-MyTV-Token: " + pairedTok);
                            String body = "{\"ok\":true,\"agentService\":\"mytv-laptop-gaming-agent\",\"authenticated\":"
                                    + authed + ",\"sunshineHostUuid\":\"14FEDE7C-47F1-1D07-0185-22930C564015\","
                                    + "\"sunshineHostName\":\"Gaming-Laptop\",\"macAddresses\":[\"AA:BB:CC:11:22:33\"]}";
                            String resp = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
                            c.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception ignored) {
                            break;
                        }
                    }
                });
                realWorker.setDaemon(true);
                realWorker.start();

                Thread udpWorker = new Thread(() -> {
                    try {
                        byte[] buf = new byte[1024];
                        java.net.DatagramPacket pkt = new java.net.DatagramPacket(buf, buf.length);
                        udpServer.receive(pkt);
                        String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                        if (msg.contains("MYTV_GAMING_DISCOVER_V1")) {
                            String reply = "{\"op\":\"MYTV_GAMING_HERE_V1\",\"agentService\":\"mytv-laptop-gaming-agent\","
                                    + "\"port\":" + realPort + ",\"sunshineHostUuid\":\"14FEDE7C-47F1-1D07-0185-22930C564015\"}";
                            byte[] rBytes = reply.getBytes(StandardCharsets.UTF_8);
                            udpServer.send(new java.net.DatagramPacket(rBytes, rBytes.length, pkt.getAddress(), pkt.getPort()));
                        }
                    } catch (Exception ignored) {
                    }
                });
                udpWorker.setDaemon(true);
                udpWorker.start();

                assertFalse(
                        GamingZone.verifyCandidateGamingPcEndpoint(null, "127.0.0.1", fakePort, 1000),
                        "Must reject unauthenticated/imposter :8485 server when TV has a paired token");
                assertTrue(
                        GamingZone.verifyCandidateGamingPcEndpoint(null, "127.0.0.1", realPort, 1000),
                        "Accepts genuine Gaming PC endpoint when X-MyTV-Token authenticates");

                String udpDiscovered = GamingZone.discoverLaptopViaUdpTargetsSync(
                        null,
                        java.util.Collections.singletonList(new java.net.InetSocketAddress("127.0.0.1", udpPort)),
                        realPort,
                        800);
                assertEquals("127.0.0.1", udpDiscovered, "UDP broadcast discovery locates and verifies Gaming PC");

                String subnetDiscovered = GamingZone.scanCandidatesForGamingPcSync(
                        null,
                        java.util.Arrays.asList("127.0.0.1"),
                        realPort,
                        0,
                        "",
                        800);
                assertEquals("127.0.0.1", subnetDiscovered, "Parallel candidate TCP scan locates and verifies Gaming PC");
            }

            // Pre-login Windows Lock Screen reboot scenario: :8485 agent is not yet running,
            // but Sunshine Windows Service (:47989) is up and reports the paired PC's <uniqueid>
            try (ServerSocket sunshineServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
                final int sunPort = sunshineServer.getLocalPort();
                Thread sunWorker = new Thread(() -> {
                    while (!sunshineServer.isClosed()) {
                        try (Socket c = sunshineServer.accept()) {
                            byte[] b = new byte[1024];
                            int n = c.getInputStream().read(b);
                            if (n <= 0) continue;
                            String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                                    + "<root status_code=\"200\"><hostname>Gaming-Laptop</hostname>"
                                    + "<uniqueid>14FEDE7C-47F1-1D07-0185-22930C564015</uniqueid></root>";
                            byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + xmlBytes.length + "\r\n\r\n";
                            c.getOutputStream().write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            c.getOutputStream().write(xmlBytes);
                            c.getOutputStream().flush();
                        } catch (Exception ignored) {
                            if (sunshineServer.isClosed()) break;
                        }
                    }
                });
                sunWorker.setDaemon(true);
                sunWorker.start();

                String wrongUuidScan = GamingZone.scanCandidatesForGamingPcSync(
                        null,
                        java.util.Collections.singletonList("127.0.0.1"),
                        0,
                        sunPort,
                        "00000000-0000-0000-0000-000000000000",
                        800);
                assertEquals("", wrongUuidScan, "Rejects Sunshine host with non-matching uniqueid");

                String rightUuidScan = GamingZone.scanCandidatesForGamingPcSync(
                        null,
                        java.util.Collections.singletonList("127.0.0.1"),
                        0,
                        sunPort,
                        "14FEDE7C-47F1-1D07-0185-22930C564015",
                        800);
                assertEquals("127.0.0.1", rightUuidScan,
                        "Recognizes rebooted Gaming PC at pre-login Windows Lock Screen via Sunshine uniqueid");
            }
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteDir(secretDir);
        }
    }

    private static void testImmediateTerminationAndReturnToGamingScreenContract() throws Exception {
        assertEquals("io.mytv.OPEN_GAMING", HomeActivity.EXTRA_OPEN_GAMING,
                "HomeActivity exposes EXTRA_OPEN_GAMING so exiting any game returns directly to Gaming Screen");

        AtomicInteger terminateCalls = new AtomicInteger(0);
        AtomicReference<String> receivedBody = new AtomicReference<>("");
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread worker = new Thread(() -> {
                try (Socket client = server.accept()) {
                    InputStream in = client.getInputStream();
                    byte[] buf = new byte[2048];
                    int n = in.read(buf);
                    String raw = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
                    int idx = raw.indexOf("\r\n\r\n");
                    if (idx >= 0) receivedBody.set(raw.substring(idx + 4));
                    terminateCalls.incrementAndGet();
                    String body = "{\"ok\":true,\"terminated\":true}";
                    String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + body.length() + "\r\n\r\n" + body;
                    client.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();
                } catch (Exception ignored) {
                }
            });
            worker.setDaemon(true);
            worker.start();

            // Session started just 50ms ago — pressing Back/Home in GameStreamActivity MUST still terminate immediately!
            GamingZone.setActiveStreamingSession(new GamingZone.ActiveStreamingSession(
                    "steam_728880", "Overcooked! 2", "127.0.0.1", port, System.currentTimeMillis() - 50L));
            boolean sent = GamingZone.terminateActivePCGameSync(null, "steam_728880", "tv_remote_back_or_home");
            assertTrue(sent, "Immediate synchronous termination succeeds even <3s after launch");
            assertEquals(1, terminateCalls.get(), "PC agent /api/gaming/terminate called immediately");
            assertTrue(receivedBody.get().contains("steam_728880"), "Terminate body carries gameId");
            assertEquals(null, GamingZone.getActiveStreamingSession(), "Active streaming session cleared");
        }
    }

    private static void testGameStreamResolutionAndNativeSoPackaging() throws Exception {
        assertEquals(1280, GameStreamActivity.parseResolutionWidth("720p"), "720p width");
        assertEquals(720, GameStreamActivity.parseResolutionHeight("720p"), "720p height");
        assertEquals(1920, GameStreamActivity.parseResolutionWidth("1080p"), "1080p width");
        assertEquals(1080, GameStreamActivity.parseResolutionHeight("1080p"), "1080p height");
        assertEquals(2560, GameStreamActivity.parseResolutionWidth("1440p"), "1440p width");
        assertEquals(1440, GameStreamActivity.parseResolutionHeight("1440p"), "1440p height");
        assertEquals(3840, GameStreamActivity.parseResolutionWidth("4k"), "4K width");
        assertEquals(2160, GameStreamActivity.parseResolutionHeight("4k"), "4K height");

        File soArm64 = new File("app/jniLibs/arm64-v8a/libmoonlight-core.so");
        assertTrue(soArm64.isFile() && soArm64.length() > 1_000_000L,
                "Bundled ARM64 libmoonlight-core.so exists in app/jniLibs/arm64-v8a/");
        try (InputStream in = Files.newInputStream(soArm64.toPath())) {
            byte[] hdr = new byte[20];
            assertEquals(20, in.read(hdr), "Reads 20-byte ARM64 ELF header");
            assertEquals(0x7F, hdr[0] & 0xFF, "ARM64 ELF magic byte 0");
            assertEquals((int) 'E', hdr[1] & 0xFF, "ARM64 ELF magic 'E'");
            assertEquals((int) 'L', hdr[2] & 0xFF, "ARM64 ELF magic 'L'");
            assertEquals((int) 'F', hdr[3] & 0xFF, "ARM64 ELF magic 'F'");
            assertEquals(2, hdr[4] & 0xFF, "ELFCLASS64 (64-bit)");
            int eMachine = (hdr[18] & 0xFF) | ((hdr[19] & 0xFF) << 8);
            assertEquals(183, eMachine, "EM_AARCH64 (183) ARM64 architecture");
        }

        File soArm32 = new File("app/jniLibs/armeabi-v7a/libmoonlight-core.so");
        assertTrue(soArm32.isFile() && soArm32.length() > 1_000_000L,
                "Bundled 32-bit ARMv7 libmoonlight-core.so exists in app/jniLibs/armeabi-v7a/ for 32-bit Google TV userspaces (mt5879/S905Y4)");
        try (InputStream in = Files.newInputStream(soArm32.toPath())) {
            byte[] hdr = new byte[20];
            assertEquals(20, in.read(hdr), "Reads 20-byte ARMv7 ELF header");
            assertEquals(0x7F, hdr[0] & 0xFF, "ARMv7 ELF magic byte 0");
            assertEquals((int) 'E', hdr[1] & 0xFF, "ARMv7 ELF magic 'E'");
            assertEquals((int) 'L', hdr[2] & 0xFF, "ARMv7 ELF magic 'L'");
            assertEquals((int) 'F', hdr[3] & 0xFF, "ARMv7 ELF magic 'F'");
            assertEquals(1, hdr[4] & 0xFF, "ELFCLASS32 (32-bit)");
            int eMachine = (hdr[18] & 0xFF) | ((hdr[19] & 0xFF) << 8);
            assertEquals(40, eMachine, "EM_ARM (40) armeabi-v7a architecture");
        }

        File builtApk = new File("MyTV.apk");
        if (builtApk.isFile()) {
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(builtApk)) {
                java.util.zip.ZipEntry entry64 = zf.getEntry("lib/arm64-v8a/libmoonlight-core.so");
                assertTrue(entry64 != null, "MyTV.apk embeds lib/arm64-v8a/libmoonlight-core.so");
                assertEquals(java.util.zip.ZipEntry.STORED, entry64.getMethod(),
                        "arm64-v8a libmoonlight-core.so is stored uncompressed (STORED) inside MyTV.apk");

                java.util.zip.ZipEntry entry32 = zf.getEntry("lib/armeabi-v7a/libmoonlight-core.so");
                assertTrue(entry32 != null, "MyTV.apk embeds lib/armeabi-v7a/libmoonlight-core.so");
                assertEquals(java.util.zip.ZipEntry.STORED, entry32.getMethod(),
                        "armeabi-v7a libmoonlight-core.so is stored uncompressed (STORED) inside MyTV.apk");
            }
        }
    }

    private static SSLServerSocket createMockMtlsServer(File serverIdDir) throws Exception {
        SunshineStreamClient.ClientIdentity srvId = SunshineStreamClient.loadOrCreateIdentity(serverIdDir);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("server", srvId.keyPair.getPrivate(), new char[0], new Certificate[] {srvId.certificate});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), trustAll, null);
        SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory()
                .createServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        ss.setNeedClientAuth(true);
        return ss;
    }

    private static String readHttpRequestHeadersLineSafe(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[1];
        while (sb.length() < 16384) {
            int n = in.read(buf);
            if (n < 0) break;
            sb.append((char) (buf[0] & 0xFF));
            int len = sb.length();
            if (len >= 4
                    && sb.charAt(len - 4) == '\r'
                    && sb.charAt(len - 3) == '\n'
                    && sb.charAt(len - 2) == '\r'
                    && sb.charAt(len - 1) == '\n') {
                break;
            }
        }
        return sb.toString();
    }

    private static void writeHttpsResponse(OutputStream out, int code, String statusText, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String hdr = "HTTP/1.1 " + code + " " + statusText + "\r\n"
                + "Content-Type: application/xml\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static void testSunshineMtlsConnectionCloseSingleSocketAndSingleServerInfo() throws Exception {
        File clientDir = Files.createTempDirectory("mytv_m2_cli_").toFile();
        File serverDir = Files.createTempDirectory("mytv_m2_srv_").toFile();
        try {
            SunshineStreamClient.ClientIdentity id = SunshineStreamClient.loadOrCreateIdentity(clientDir);
            SSLSocketFactory f1 = SunshineStreamClient.getOrCreateMtlsSocketFactory(id);
            SSLSocketFactory f2 = SunshineStreamClient.getOrCreateMtlsSocketFactory(id);
            assertTrue(f1 == f2, "getOrCreateMtlsSocketFactory reuses cached SSLSocketFactory for same identity/generation");
            SunshineStreamClient.invalidateMtlsCacheForRepair();
            SSLSocketFactory f3 = SunshineStreamClient.getOrCreateMtlsSocketFactory(id);
            assertTrue(f1 != f3, "invalidateMtlsCacheForRepair rotates SSLSocketFactory generation");

            final AtomicInteger activeSockets = new AtomicInteger(0);
            final AtomicInteger maxConcurrentSockets = new AtomicInteger(0);
            final AtomicInteger serverInfoCount = new AtomicInteger(0);
            final AtomicInteger applistCount = new AtomicInteger(0);
            final AtomicInteger launchCount = new AtomicInteger(0);
            final AtomicBoolean allHadConnectionClose = new AtomicBoolean(true);
            final AtomicBoolean allClientsClosedPromptly = new AtomicBoolean(true);

            try (SSLServerSocket httpsSrv = createMockMtlsServer(serverDir)) {
                final int httpsPort = httpsSrv.getLocalPort();
                Thread srvThread = new Thread(() -> {
                    while (!httpsSrv.isClosed()) {
                        try (SSLSocket cli = (SSLSocket) httpsSrv.accept()) {
                            int now = activeSockets.incrementAndGet();
                            maxConcurrentSockets.updateAndGet(prev -> Math.max(prev, now));
                            cli.setSoTimeout(1500);
                            try {
                                String req = readHttpRequestHeadersLineSafe(cli.getInputStream());
                                String lower = req.toLowerCase(Locale.ROOT);
                                if (!lower.contains("connection: close")) {
                                    allHadConnectionClose.set(false);
                                }
                                if (req.startsWith("GET /serverinfo")) {
                                    serverInfoCount.incrementAndGet();
                                    writeHttpsResponse(cli.getOutputStream(), 200, "OK",
                                            "<root status_code=\"200\"><PairStatus>1</PairStatus>"
                                                    + "<appversion>7.1.431.-1</appversion>"
                                                    + "<ServerCodecModeSupport>259</ServerCodecModeSupport>"
                                                    + "<currentgame>0</currentgame></root>");
                                } else if (req.startsWith("GET /applist")) {
                                    applistCount.incrementAndGet();
                                    writeHttpsResponse(cli.getOutputStream(), 200, "OK",
                                            "<root status_code=\"200\"><App><ID>881448767</ID>"
                                                    + "<AppTitle>Desktop</AppTitle><IsHdrSupported>0</IsHdrSupported></App></root>");
                                } else if (req.startsWith("GET /launch")) {
                                    launchCount.incrementAndGet();
                                    writeHttpsResponse(cli.getOutputStream(), 200, "OK",
                                            "<root status_code=\"200\"><sessionUrl0>rtsp://127.0.0.1:48010</sessionUrl0></root>");
                                } else {
                                    writeHttpsResponse(cli.getOutputStream(), 404, "Not Found", "<root status_code=\"404\"/>");
                                }
                                // Confirm client closes socket cleanly instead of keeping ConnectionPool alive
                                cli.setSoTimeout(500);
                                try {
                                    if (cli.getInputStream().read() != -1) {
                                        allClientsClosedPromptly.set(false);
                                    }
                                } catch (Exception timeoutOrReset) {
                                    allClientsClosedPromptly.set(false);
                                }
                            } finally {
                                activeSockets.decrementAndGet();
                            }
                        } catch (Exception e) {
                            if (httpsSrv.isClosed()) break;
                            System.err.println("[httpsThread] " + e);
                        }
                    }
                }, "mock-sunshine-47984-seq");
                srvThread.setDaemon(true);
                srvThread.start();

                SunshineStreamClient.LaunchSession session = SunshineStreamClient.startSunshineSession(
                        null,
                        clientDir,
                        "127.0.0.1",
                        "Desktop",
                        1920,
                        1080,
                        60,
                        MoonBridge.AUDIO_CONFIGURATION_STEREO,
                        false,
                        true,
                        false,
                        0,
                        httpsPort,
                        0);
                assertEquals("rtsp://127.0.0.1:48010", session.rtspSessionUrl, "LaunchSession receives RTSP URL");
                assertEquals(1, serverInfoCount.get(), "Paired startup performs exactly 1 /serverinfo request (no duplicate pre-check)");
                assertEquals(1, applistCount.get(), "Paired startup performs 1 /applist request");
                assertEquals(1, launchCount.get(), "Paired startup performs 1 /launch request");
                assertEquals(1, maxConcurrentSockets.get(), "Sequential mTLS requests never leave multiple open sockets concurrently");
                assertTrue(allHadConnectionClose.get(), "Every HTTPS request includes Connection: close");
                assertTrue(allClientsClosedPromptly.get(), "Every HTTPS socket closes cleanly immediately after response");
            }
        } finally {
            deleteDir(clientDir);
            deleteDir(serverDir);
        }
    }

    private static void testSunshineMtls401AndPairStatusZeroRecovery() throws Exception {
        File clientDir = Files.createTempDirectory("mytv_m2_rec_cli_").toFile();
        File serverDir = Files.createTempDirectory("mytv_m2_rec_srv_").toFile();
        try {
            SunshineStreamClient.ClientIdentity srvId = SunshineStreamClient.loadOrCreateIdentity(serverDir);
            final AtomicBoolean paired = new AtomicBoolean(true);
            final AtomicBoolean failFirstApplistWith401 = new AtomicBoolean(true);
            final AtomicReference<String> capturedPin = new AtomicReference<>("");
            final AtomicReference<String> currentDeviceName = new AtomicReference<>("");
            final AtomicInteger pairChallengeHits = new AtomicInteger(0);
            final AtomicReference<byte[]> pendingSalt = new AtomicReference<>(new byte[16]);
            final AtomicReference<byte[]> pendingChallenge = new AtomicReference<>(new byte[16]);
            final AtomicReference<byte[]> serverSecretRef = new AtomicReference<>(new byte[16]);

            try (ServerSocket pinAgent = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                 ServerSocket httpSrv = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                 SSLServerSocket httpsSrv = createMockMtlsServer(serverDir)) {

                final int agentPort = pinAgent.getLocalPort();
                final int httpPort = httpSrv.getLocalPort();
                final int httpsPort = httpsSrv.getLocalPort();

                Thread pinThread = new Thread(() -> {
                    while (!pinAgent.isClosed()) {
                        try (Socket c = pinAgent.accept()) {
                            c.setSoTimeout(1500);
                            String req = readHttpRequestHeadersLineSafe(c.getInputStream());
                            int cl = 0;
                            for (String line : req.split("\r\n")) {
                                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                                    cl = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                                }
                            }
                            byte[] bodyBytes = new byte[cl];
                            int read = 0;
                            while (read < cl) {
                                int n = c.getInputStream().read(bodyBytes, read, cl - read);
                                if (n < 0) break;
                                read += n;
                            }
                            String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
                            Object obj = RemoteAuthManager.MiniJson.parse(bodyStr);
                            if (obj instanceof java.util.Map) {
                                @SuppressWarnings("unchecked")
                                java.util.Map<String, Object> m = (java.util.Map<String, Object>) obj;
                                capturedPin.set(String.valueOf(m.get("pin")));
                                currentDeviceName.set(String.valueOf(m.get("deviceName")));
                            }
                            String json = "{\"ok\":true,\"paired\":true}";
                            byte[] jBytes = json.getBytes(StandardCharsets.UTF_8);
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                    + jBytes.length + "\r\nConnection: close\r\n\r\n";
                            c.getOutputStream().write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            c.getOutputStream().write(jBytes);
                            c.getOutputStream().flush();
                        } catch (Exception e) {
                            if (pinAgent.isClosed()) break;
                            System.err.println("[pinThread] " + e);
                        }
                    }
                }, "mock-agent-sunshine-pair");
                pinThread.setDaemon(true);
                pinThread.start();

                Thread httpThread = new Thread(() -> {
                    while (!httpSrv.isClosed()) {
                        try (Socket c = httpSrv.accept()) {
                            c.setSoTimeout(2000);
                            String req = readHttpRequestHeadersLineSafe(c.getInputStream());
                            String firstLine = req.split("\r\n")[0];
                            String path = firstLine.split(" ")[1];
                            if (path.contains("phrase=getservercert")) {
                                String saltHex = queryParam(path, "salt");
                                pendingSalt.set(SunshineStreamClient.hexToBytes(saltHex));
                                String srvCertHex = SunshineStreamClient.bytesToHex(srvId.certificate.getEncoded());
                                writeHttpsResponse(c.getOutputStream(), 200, "OK",
                                        "<root status_code=\"200\"><paired>1</paired><plaincert>" + srvCertHex + "</plaincert></root>");
                            } else if (path.contains("clientchallenge=")) {
                                long deadline = System.currentTimeMillis() + 2000L;
                                while (capturedPin.get().isEmpty() && System.currentTimeMillis() < deadline) {
                                    Thread.sleep(15L);
                                }
                                byte[] aesKey = SunshineStreamClient.derivePairingAesKey(pendingSalt.get(), capturedPin.get());
                                byte[] encCli = SunshineStreamClient.hexToBytes(queryParam(path, "clientchallenge"));
                                byte[] decCli = SunshineStreamClient.aesEcbDecrypt(aesKey, encCli);
                                pendingChallenge.set(decCli);

                                byte[] srvChallenge = new byte[16];
                                Arrays.fill(srvChallenge, (byte) 0x22);
                                byte[] srvSecret = new byte[16];
                                Arrays.fill(srvSecret, (byte) 0x33);
                                serverSecretRef.set(srvSecret);

                                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                                md.update(decCli);
                                md.update(srvId.certificate.getSignature());
                                md.update(srvSecret);
                                byte[] srvRespHash = md.digest();

                                byte[] plainResp = new byte[48];
                                System.arraycopy(srvRespHash, 0, plainResp, 0, 32);
                                System.arraycopy(srvChallenge, 0, plainResp, 32, 16);
                                byte[] encResp = SunshineStreamClient.aesEcbEncrypt(aesKey, plainResp);
                                writeHttpsResponse(c.getOutputStream(), 200, "OK",
                                        "<root status_code=\"200\"><paired>1</paired><challengeresponse>"
                                                + SunshineStreamClient.bytesToHex(encResp) + "</challengeresponse></root>");
                            } else if (path.contains("serverchallengeresp=")) {
                                byte[] srvSecret = serverSecretRef.get();
                                java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
                                sig.initSign(srvId.keyPair.getPrivate());
                                sig.update(srvSecret);
                                byte[] srvSig = sig.sign();
                                byte[] pairingSecret = new byte[srvSecret.length + srvSig.length];
                                System.arraycopy(srvSecret, 0, pairingSecret, 0, srvSecret.length);
                                System.arraycopy(srvSig, 0, pairingSecret, srvSecret.length, srvSig.length);
                                writeHttpsResponse(c.getOutputStream(), 200, "OK",
                                        "<root status_code=\"200\"><paired>1</paired><pairingsecret>"
                                                + SunshineStreamClient.bytesToHex(pairingSecret) + "</pairingsecret></root>");
                            } else if (path.contains("clientpairingsecret=")) {
                                writeHttpsResponse(c.getOutputStream(), 200, "OK",
                                        "<root status_code=\"200\"><paired>1</paired></root>");
                            } else {
                                writeHttpsResponse(c.getOutputStream(), 404, "Not Found", "<root status_code=\"404\"/>");
                            }
                        } catch (Exception e) {
                            if (httpSrv.isClosed()) break;
                            System.err.println("[httpThread] " + e);
                        }
                    }
                }, "mock-sunshine-47989-pair");
                httpThread.setDaemon(true);
                httpThread.start();

                Thread httpsThread = new Thread(() -> {
                    while (!httpsSrv.isClosed()) {
                        try (SSLSocket cli = (SSLSocket) httpsSrv.accept()) {
                            cli.setSoTimeout(2000);
                            String req = readHttpRequestHeadersLineSafe(cli.getInputStream());
                            if (req.startsWith("GET /serverinfo")) {
                                String ps = paired.get() ? "1" : "0";
                                writeHttpsResponse(cli.getOutputStream(), 200, "OK",
                                        "<root status_code=\"200\"><PairStatus>" + ps + "</PairStatus>"
                                                + "<currentgame>0</currentgame></root>");
                            } else if (req.startsWith("GET /pair") && req.contains("phrase=pairchallenge")) {
                                pairChallengeHits.incrementAndGet();
                                paired.set(true);
                                writeHttpsResponse(cli.getOutputStream(), 200, "OK",
                                        "<root status_code=\"200\"><paired>1</paired></root>");
                            } else if (req.startsWith("GET /applist")) {
                                if (failFirstApplistWith401.compareAndSet(true, false)) {
                                    paired.set(false);
                                    writeHttpsResponse(cli.getOutputStream(), 401, "Unauthorized",
                                            "<root status_code=\"401\"/>");
                                } else {
                                    writeHttpsResponse(cli.getOutputStream(), 200, "OK",
                                            "<root status_code=\"200\"><App><ID>101</ID>"
                                                    + "<AppTitle>Desktop</AppTitle><IsHdrSupported>0</IsHdrSupported></App></root>");
                                }
                            } else if (req.startsWith("GET /launch")) {
                                writeHttpsResponse(cli.getOutputStream(), 200, "OK",
                                        "<root status_code=\"200\"><sessionUrl0>rtsp://127.0.0.1:48010</sessionUrl0></root>");
                            } else {
                                writeHttpsResponse(cli.getOutputStream(), 404, "Not Found", "<root status_code=\"404\"/>");
                            }
                        } catch (Exception e) {
                            if (httpsSrv.isClosed()) break;
                            System.err.println("[httpsThread] " + e);
                        }
                    }
                }, "mock-sunshine-47984-rec");
                httpsThread.setDaemon(true);
                httpsThread.start();

                SunshineStreamClient.LaunchSession session = SunshineStreamClient.startSunshineSession(
                        null,
                        clientDir,
                        "127.0.0.1",
                        "Desktop",
                        1920,
                        1080,
                        60,
                        MoonBridge.AUDIO_CONFIGURATION_STEREO,
                        false,
                        true,
                        false,
                        httpPort,
                        httpsPort,
                        agentPort);
                assertEquals("rtsp://127.0.0.1:48010", session.rtspSessionUrl, "Recovers from 401 /applist via automatic re-pair and launches");
                assertEquals(1, pairChallengeHits.get(), "Completed 5-step pairchallenge recovery");
                assertEquals("MyTV", currentDeviceName.get(), "submitSunshinePairingPinSync forwards deviceName");
            }
        } finally {
            deleteDir(clientDir);
            deleteDir(serverDir);
        }
    }

    private static String queryParam(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return "";
        String[] parts = path.substring(q + 1).split("&");
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq > 0 && key.equals(p.substring(0, eq))) {
                return p.substring(eq + 1);
            }
        }
        return "";
    }

    private static void testGameStreamSecureDecoderExclusion() {
        assertTrue(GameStreamActivity.isSecureDecoderName("c2.mtk.hevc.decoder.secure"),
                "Flags MediaTek .secure HEVC decoder");
        assertTrue(GameStreamActivity.isSecureDecoderName("OMX.MTK.VIDEO.DECODER.AVC.SECURE"),
                "Flags uppercase .SECURE decoder");
        assertFalse(GameStreamActivity.isSecureDecoderName("c2.mtk.hevc.decoder"),
                "Allows standard MediaTek HEVC hardware decoder");

        assertTrue(GameStreamActivity.isAcceptableHardwareDecoder(
                "c2.mtk.hevc.decoder", false, true, false, false, false),
                "Accepts non-secure hardware HEVC decoder");
        assertFalse(GameStreamActivity.isAcceptableHardwareDecoder(
                "c2.mtk.hevc.decoder.secure", false, true, false, false, false),
                "Rejects .secure hardware decoder");
        assertFalse(GameStreamActivity.isAcceptableHardwareDecoder(
                "c2.mtk.hevc.decoder", false, true, false, false, true),
                "Rejects FEATURE_SecurePlayback required decoder");
        assertFalse(GameStreamActivity.isAcceptableHardwareDecoder(
                "c2.mtk.hevc.decoder", false, true, false, true, false),
                "Rejects FEATURE_SecurePlayback supported decoder in strict filter");
        assertFalse(GameStreamActivity.isAcceptableHardwareDecoder(
                "c2.android.hevc.decoder", false, false, true, false, false),
                "Rejects software decoder");
    }

    private static void testGamingZoneM2DiscoveryAndFallbackContracts() throws Exception {
        File secretDir = Files.createTempDirectory("mytv_m2_disc_sec_").toFile();
        File clientDir = Files.createTempDirectory("mytv_m2_disc_cli_").toFile();
        File serverDir = Files.createTempDirectory("mytv_m2_disc_srv_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(secretDir);
            Secrets.putSecret(null, Secrets.GAMING_PC_TOKEN, "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

            try (ServerSocket unpairedSrv = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                 ServerSocket imposterSrv = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
                final int unpairedPort = unpairedSrv.getLocalPort();
                final int imposterPort = imposterSrv.getLocalPort();

                Thread unpairedWorker = new Thread(() -> {
                    while (!unpairedSrv.isClosed()) {
                        try (Socket c = unpairedSrv.accept()) {
                            c.getInputStream().read(new byte[1024]);
                            String body = "{\"ok\":true,\"agentService\":\"mytv-laptop-gaming-agent\","
                                    + "\"authenticated\":false,\"sunshineHostUuid\":\"UUID-1\"}";
                            String resp = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
                            c.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception ignored) {
                            break;
                        }
                    }
                });
                unpairedWorker.setDaemon(true);
                unpairedWorker.start();

                Thread imposterWorker = new Thread(() -> {
                    while (!imposterSrv.isClosed()) {
                        try (Socket c = imposterSrv.accept()) {
                            c.getInputStream().read(new byte[1024]);
                            String body = "{\"ok\":true,\"agentService\":\"other-service\",\"authenticated\":false}";
                            String resp = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
                            c.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception ignored) {
                            break;
                        }
                    }
                });
                imposterWorker.setDaemon(true);
                imposterWorker.start();

                GamingZone.ProbeResult unpairedRes = GamingZone.probeCandidateGamingPcEndpoint(
                        null, "127.0.0.1", unpairedPort, 1000);
                assertEquals(GamingZone.EndpointProbeStatus.REACHABLE_UNPAIRED, unpairedRes.status,
                        "probeCandidateGamingPcEndpoint classifies reachable unpaired agent as REACHABLE_UNPAIRED");
                assertEquals("127.0.0.1", GamingZone.getReachableUnpairedPcIp(null),
                        "Records reachable unpaired PC IP for pairing UX");
                assertFalse(GamingZone.verifyCandidateGamingPcEndpoint(null, "127.0.0.1", unpairedPort, 1000),
                        "verifyCandidateGamingPcEndpoint still returns false for unpaired endpoint");

                GamingZone.ProbeResult imposterRes = GamingZone.probeCandidateGamingPcEndpoint(
                        null, "127.0.0.1", imposterPort, 1000);
                assertEquals(GamingZone.EndpointProbeStatus.UNREACHABLE, imposterRes.status,
                        "probeCandidateGamingPcEndpoint classifies non-agent imposter as UNREACHABLE");
            }

            // Direct Sunshine fallback when :8485 is down while :47989 + paired :47984 respond
            try (ServerSocket httpSrv = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                 SSLServerSocket httpsSrv = createMockMtlsServer(serverDir)) {
                final int httpPort = httpSrv.getLocalPort();
                final int httpsPort = httpsSrv.getLocalPort();

                Thread httpThread = new Thread(() -> {
                    while (!httpSrv.isClosed()) {
                        try (Socket c = httpSrv.accept()) {
                            readHttpRequestHeadersLineSafe(c.getInputStream());
                            writeHttpsResponse(c.getOutputStream(), 200, "OK",
                                    "<root status_code=\"200\"><hostname>Gaming-Laptop</hostname>"
                                            + "<uniqueid>14FEDE7C-47F1-1D07-0185-22930C564015</uniqueid></root>");
                        } catch (Exception e) {
                            if (httpSrv.isClosed()) break;
                            System.err.println("[httpThread] " + e);
                        }
                    }
                });
                httpThread.setDaemon(true);
                httpThread.start();

                Thread httpsThread = new Thread(() -> {
                    while (!httpsSrv.isClosed()) {
                        try (SSLSocket c = (SSLSocket) httpsSrv.accept()) {
                            readHttpRequestHeadersLineSafe(c.getInputStream());
                            writeHttpsResponse(c.getOutputStream(), 200, "OK",
                                    "<root status_code=\"200\"><PairStatus>1</PairStatus></root>");
                        } catch (Exception e) {
                            if (httpsSrv.isClosed()) break;
                            System.err.println("[httpsThread] " + e);
                        }
                    }
                });
                httpsThread.setDaemon(true);
                httpsThread.start();

                assertTrue(GamingZone.checkSunshineDirectFallbackSync(null, clientDir, "127.0.0.1", httpPort, httpsPort),
                        "checkSunshineDirectFallbackSync allows direct GameStream launch when Sunshine is paired and :8485 is down");
                assertEquals("127.0.0.1",
                        GamingZone.scanCandidatesForGamingPcSync(
                                null, Collections.singletonList("127.0.0.1"), 0, httpPort, "", 800),
                        "scanCandidatesForGamingPcSync locates Sunshine even when expectedSunshineUuid is empty");
            }
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteDir(secretDir);
            deleteDir(clientDir);
            deleteDir(serverDir);
        }
    }

    private static void writeHttpsResponseCustom(
            OutputStream out,
            int code,
            String statusText,
            String connectionHeader,
            boolean chunked,
            boolean lfOnlyDelim,
            String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder hdr = new StringBuilder();
        String eol = lfOnlyDelim ? "\n" : "\r\n";
        hdr.append("HTTP/1.1 ").append(code).append(" ").append(statusText).append(eol);
        hdr.append("Content-Type: application/xml").append(eol);
        if (connectionHeader != null) {
            hdr.append("Connection: ").append(connectionHeader).append(eol);
        }
        if (chunked) {
            hdr.append("Transfer-Encoding: chunked").append(eol);
        } else {
            hdr.append("Content-Length: ").append(bytes.length).append(eol);
        }
        hdr.append(eol);
        out.write(hdr.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (chunked) {
            int mid = Math.max(1, bytes.length / 2);
            if (bytes.length > 0) {
                int firstLen = Math.min(mid, bytes.length);
                out.write((Integer.toHexString(firstLen) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(bytes, 0, firstLen);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                if (firstLen < bytes.length) {
                    int rem = bytes.length - firstLen;
                    out.write((Integer.toHexString(rem) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(bytes, firstLen, rem);
                    out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                }
            }
            out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        } else {
            out.write(bytes);
        }
        out.flush();
    }

    private static void testSunshineMtlsKeepAliveChunkedAndResetIdentityNoSocketLeak() throws Exception {
        File clientDir = Files.createTempDirectory("mytv_m2_ka_cli_").toFile();
        File serverDir = Files.createTempDirectory("mytv_m2_ka_srv_").toFile();
        try {
            SunshineStreamClient.ClientIdentity srvId = SunshineStreamClient.loadOrCreateIdentity(serverDir);
            final AtomicInteger activeSockets = new AtomicInteger(0);
            final AtomicInteger maxConcurrentSockets = new AtomicInteger(0);
            final AtomicInteger concurrencyViolations = new AtomicInteger(0);
            final AtomicInteger leakedSockets = new AtomicInteger(0);
            final AtomicInteger missingConnClose = new AtomicInteger(0);
            final AtomicInteger cycleRef = new AtomicInteger(0);
            final AtomicReference<String> rejectedOldUid = new AtomicReference<>("");
            final AtomicReference<String> capturedPin = new AtomicReference<>("");
            final AtomicReference<byte[]> pendingSalt = new AtomicReference<>(new byte[16]);
            final AtomicReference<byte[]> serverSecretRef = new AtomicReference<>(new byte[16]);
            final AtomicBoolean paired = new AtomicBoolean(true);
            final AtomicBoolean failApplist401Once = new AtomicBoolean(false);
            final AtomicBoolean failLaunch403Once = new AtomicBoolean(false);
            final AtomicBoolean failApplist500 = new AtomicBoolean(false);

            try (ServerSocket pinAgent = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                 ServerSocket httpSrv = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                 SSLServerSocket httpsSrv = createMockMtlsServer(serverDir)) {

                final int agentPort = pinAgent.getLocalPort();
                final int httpPort = httpSrv.getLocalPort();
                final int httpsPort = httpsSrv.getLocalPort();

                Thread pinThread = new Thread(() -> {
                    while (!pinAgent.isClosed()) {
                        try (Socket c = pinAgent.accept()) {
                            c.setSoTimeout(1500);
                            String req = readHttpRequestHeadersLineSafe(c.getInputStream());
                            int cl = 0;
                            for (String line : req.split("\r\n")) {
                                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                                    cl = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                                }
                            }
                            byte[] bodyBytes = new byte[cl];
                            int read = 0;
                            while (read < cl) {
                                int n = c.getInputStream().read(bodyBytes, read, cl - read);
                                if (n < 0) break;
                                read += n;
                            }
                            Object obj = RemoteAuthManager.MiniJson.parse(new String(bodyBytes, StandardCharsets.UTF_8));
                            if (obj instanceof java.util.Map) {
                                @SuppressWarnings("unchecked")
                                java.util.Map<String, Object> m = (java.util.Map<String, Object>) obj;
                                capturedPin.set(String.valueOf(m.get("pin")));
                            }
                            String json = "{\"ok\":true,\"paired\":true}";
                            byte[] jBytes = json.getBytes(StandardCharsets.UTF_8);
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + jBytes.length + "\r\n\r\n";
                            c.getOutputStream().write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            c.getOutputStream().write(jBytes);
                            c.getOutputStream().flush();
                        } catch (Exception ignored) {
                            if (pinAgent.isClosed()) break;
                        }
                    }
                }, "mock-ka-pin");
                pinThread.setDaemon(true);
                pinThread.start();

                Thread httpThread = new Thread(() -> {
                    while (!httpSrv.isClosed()) {
                        try (Socket c = httpSrv.accept()) {
                            c.setSoTimeout(2000);
                            String req = readHttpRequestHeadersLineSafe(c.getInputStream());
                            String path = req.split("\r\n")[0].split(" ")[1];
                            String uid = queryParam(path, "uniqueid");
                            if (path.contains("phrase=getservercert")) {
                                if (!rejectedOldUid.get().isEmpty() && rejectedOldUid.get().equals(uid)) {
                                    writeHttpsResponseCustom(c.getOutputStream(), 200, "OK", null, false, false,
                                            "<root status_code=\"200\"><paired>0</paired></root>");
                                } else {
                                    pendingSalt.set(SunshineStreamClient.hexToBytes(queryParam(path, "salt")));
                                    String srvCertHex = SunshineStreamClient.bytesToHex(srvId.certificate.getEncoded());
                                    writeHttpsResponseCustom(c.getOutputStream(), 200, "OK", null, false, false,
                                            "<root status_code=\"200\"><paired>1</paired><plaincert>"
                                                    + srvCertHex + "</plaincert></root>");
                                }
                            } else if (path.contains("clientchallenge=")) {
                                long deadline = System.currentTimeMillis() + 2000L;
                                while (capturedPin.get().isEmpty() && System.currentTimeMillis() < deadline) {
                                    Thread.sleep(10L);
                                }
                                byte[] aesKey = SunshineStreamClient.derivePairingAesKey(pendingSalt.get(), capturedPin.get());
                                byte[] decCli = SunshineStreamClient.aesEcbDecrypt(
                                        aesKey, SunshineStreamClient.hexToBytes(queryParam(path, "clientchallenge")));
                                byte[] srvChallenge = new byte[16];
                                Arrays.fill(srvChallenge, (byte) 0x44);
                                byte[] srvSecret = new byte[16];
                                Arrays.fill(srvSecret, (byte) 0x55);
                                serverSecretRef.set(srvSecret);
                                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                                md.update(decCli);
                                md.update(srvId.certificate.getSignature());
                                md.update(srvSecret);
                                byte[] srvRespHash = md.digest();
                                byte[] plainResp = new byte[48];
                                System.arraycopy(srvRespHash, 0, plainResp, 0, 32);
                                System.arraycopy(srvChallenge, 0, plainResp, 32, 16);
                                byte[] encResp = SunshineStreamClient.aesEcbEncrypt(aesKey, plainResp);
                                writeHttpsResponseCustom(c.getOutputStream(), 200, "OK", null, false, false,
                                        "<root status_code=\"200\"><paired>1</paired><challengeresponse>"
                                                + SunshineStreamClient.bytesToHex(encResp) + "</challengeresponse></root>");
                            } else if (path.contains("serverchallengeresp=")) {
                                byte[] srvSecret = serverSecretRef.get();
                                java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
                                sig.initSign(srvId.keyPair.getPrivate());
                                sig.update(srvSecret);
                                byte[] srvSig = sig.sign();
                                byte[] pairingSecret = new byte[srvSecret.length + srvSig.length];
                                System.arraycopy(srvSecret, 0, pairingSecret, 0, srvSecret.length);
                                System.arraycopy(srvSig, 0, pairingSecret, srvSecret.length, srvSig.length);
                                writeHttpsResponseCustom(c.getOutputStream(), 200, "OK", null, false, false,
                                        "<root status_code=\"200\"><paired>1</paired><pairingsecret>"
                                                + SunshineStreamClient.bytesToHex(pairingSecret) + "</pairingsecret></root>");
                            } else if (path.contains("clientpairingsecret=")) {
                                writeHttpsResponseCustom(c.getOutputStream(), 200, "OK", null, false, false,
                                        "<root status_code=\"200\"><paired>1</paired></root>");
                            } else {
                                writeHttpsResponseCustom(c.getOutputStream(), 404, "Not Found", null, false, false,
                                        "<root status_code=\"404\"/>");
                            }
                        } catch (Exception ignored) {
                            if (httpSrv.isClosed()) break;
                        }
                    }
                }, "mock-ka-http");
                httpThread.setDaemon(true);
                httpThread.start();

                Thread httpsThread = new Thread(() -> {
                    while (!httpsSrv.isClosed()) {
                        try (SSLSocket cli = (SSLSocket) httpsSrv.accept()) {
                            int now = activeSockets.incrementAndGet();
                            maxConcurrentSockets.updateAndGet(prev -> Math.max(prev, now));
                            if (now > 1) {
                                concurrencyViolations.incrementAndGet();
                            }
                            cli.setSoTimeout(1500);
                            try {
                                String req = readHttpRequestHeadersLineSafe(cli.getInputStream());
                                if (!req.toLowerCase(Locale.ROOT).contains("connection: close")) {
                                    missingConnClose.incrementAndGet();
                                }
                                int cIdx = cycleRef.get();
                                String connHdr = (cIdx % 2 == 0) ? "keep-alive" : null;
                                boolean useChunked = (cIdx == 1 || cIdx == 4);
                                boolean useLfOnly = (cIdx == 5);
                                String path = req.split("\r\n")[0].split(" ")[1];
                                String uid = queryParam(path, "uniqueid");

                                if (req.startsWith("GET /serverinfo")) {
                                    String ps = (paired.get() && !uid.equals(rejectedOldUid.get())) ? "1" : "0";
                                    writeHttpsResponseCustom(cli.getOutputStream(), 200, "OK", connHdr, useChunked, useLfOnly,
                                            "<root status_code=\"200\"><PairStatus>" + ps + "</PairStatus>"
                                                    + "<currentgame>0</currentgame></root>");
                                } else if (req.startsWith("GET /pair") && req.contains("phrase=pairchallenge")) {
                                    paired.set(true);
                                    capturedPin.set("");
                                    writeHttpsResponseCustom(cli.getOutputStream(), 200, "OK", connHdr, false, false,
                                            "<root status_code=\"200\"><paired>1</paired></root>");
                                } else if (req.startsWith("GET /applist")) {
                                    if (failApplist401Once.compareAndSet(true, false)) {
                                        paired.set(false);
                                        String pad = String.join("", Collections.nCopies(1024, "A"));
                                        writeHttpsResponseCustom(cli.getOutputStream(), 401, "Unauthorized", connHdr, false, false,
                                                "<root status_code=\"401\" pad=\"" + pad + "\"/>");
                                    } else if (failApplist500.get()) {
                                        String pad = String.join("", Collections.nCopies(4096, "E"));
                                        writeHttpsResponseCustom(cli.getOutputStream(), 500, "Internal Server Error", connHdr, true, false,
                                                "<root status_code=\"500\" pad=\"" + pad + "\"/>");
                                    } else {
                                        writeHttpsResponseCustom(cli.getOutputStream(), 200, "OK", connHdr, useChunked, useLfOnly,
                                                "<root status_code=\"200\"><App><ID>777</ID>"
                                                        + "<AppTitle>Desktop</AppTitle><IsHdrSupported>0</IsHdrSupported></App></root>");
                                    }
                                } else if (req.startsWith("GET /launch")) {
                                    if (failLaunch403Once.compareAndSet(true, false)) {
                                        paired.set(false);
                                        String pad = String.join("", Collections.nCopies(2048, "F"));
                                        writeHttpsResponseCustom(cli.getOutputStream(), 403, "Forbidden", connHdr, false, false,
                                                "<root status_code=\"403\" pad=\"" + pad + "\"/>");
                                    } else {
                                        writeHttpsResponseCustom(cli.getOutputStream(), 200, "OK", connHdr, useChunked, useLfOnly,
                                                "<root status_code=\"200\"><sessionUrl0>rtsp://127.0.0.1:48010</sessionUrl0></root>");
                                    }
                                } else {
                                    writeHttpsResponseCustom(cli.getOutputStream(), 404, "Not Found", connHdr, false, false,
                                            "<root status_code=\"404\"/>");
                                }

                                // Server keeps socket open (no server close yet); client MUST close immediately
                                cli.setSoTimeout(500);
                                try {
                                    if (cli.getInputStream().read() != -1) {
                                        leakedSockets.incrementAndGet();
                                    }
                                } catch (Exception timeoutOrReset) {
                                    leakedSockets.incrementAndGet();
                                }
                            } finally {
                                activeSockets.decrementAndGet();
                            }
                        } catch (Exception ignored) {
                            if (httpsSrv.isClosed()) break;
                        }
                    }
                }, "mock-ka-https");
                httpsThread.setDaemon(true);
                httpsThread.start();

                // Cycle 0: Connection: keep-alive + Content-Length
                cycleRef.set(0);
                SunshineStreamClient.LaunchSession s0 = SunshineStreamClient.startSunshineSession(
                        null, clientDir, "127.0.0.1", "Desktop", 1920, 1080, 60,
                        MoonBridge.AUDIO_CONFIGURATION_STEREO, false, true, false, httpPort, httpsPort, agentPort);
                assertEquals(777, s0.appId, "Cycle 0 keep-alive response parses appId");

                // Cycle 1: Omitted Connection header + Transfer-Encoding: chunked
                cycleRef.set(1);
                SunshineStreamClient.LaunchSession s1 = SunshineStreamClient.startSunshineSession(
                        null, clientDir, "127.0.0.1", "Desktop", 1920, 1080, 60,
                        MoonBridge.AUDIO_CONFIGURATION_STEREO, false, true, false, httpPort, httpsPort, agentPort);
                assertEquals(777, s1.appId, "Cycle 1 chunked + omitted Connection header parses appId");

                // Cycle 2: 401 with 1KB body + Connection: keep-alive -> re-pair recovery
                cycleRef.set(2);
                failApplist401Once.set(true);
                SunshineStreamClient.LaunchSession s2 = SunshineStreamClient.startSunshineSession(
                        null, clientDir, "127.0.0.1", "Desktop", 1920, 1080, 60,
                        MoonBridge.AUDIO_CONFIGURATION_STEREO, false, true, false, httpPort, httpsPort, agentPort);
                assertEquals(777, s2.appId, "Cycle 2 recovers from 401 + keep-alive");

                // Cycle 3: 403 with 2KB body + omitted Connection header -> re-pair recovery
                cycleRef.set(3);
                failLaunch403Once.set(true);
                SunshineStreamClient.LaunchSession s3 = SunshineStreamClient.startSunshineSession(
                        null, clientDir, "127.0.0.1", "Desktop", 1920, 1080, 60,
                        MoonBridge.AUDIO_CONFIGURATION_STEREO, false, true, false, httpPort, httpsPort, agentPort);
                assertEquals(777, s3.appId, "Cycle 3 recovers from 403 + omitted Connection header");

                // Cycle 4: 500 chunked 4KB body -> throws HttpStatusException(500) without leaking socket
                cycleRef.set(4);
                failApplist500.set(true);
                int gotStatus = 0;
                try {
                    SunshineStreamClient.startSunshineSession(
                            null, clientDir, "127.0.0.1", "Desktop", 1920, 1080, 60,
                            MoonBridge.AUDIO_CONFIGURATION_STEREO, false, true, false, httpPort, httpsPort, agentPort);
                } catch (SunshineStreamClient.HttpStatusException hse) {
                    gotStatus = hse.statusCode;
                }
                failApplist500.set(false);
                assertEquals(500, gotStatus, "Cycle 4 throws HttpStatusException(500) on chunked 500 error body");

                // Cycle 5: Stale identity rejected -> resetIdentity() + LF-only header delimiter -> succeeds
                cycleRef.set(5);
                SunshineStreamClient.ClientIdentity oldId = SunshineStreamClient.loadOrCreateIdentity(clientDir);
                rejectedOldUid.set(oldId.uniqueId);
                SunshineStreamClient.LaunchSession s5 = SunshineStreamClient.startSunshineSession(
                        null, clientDir, "127.0.0.1", "Desktop", 1920, 1080, 60,
                        MoonBridge.AUDIO_CONFIGURATION_STEREO, false, true, false, httpPort, httpsPort, agentPort);
                assertEquals(777, s5.appId, "Cycle 5 resets identity and recovers cleanly");
                SunshineStreamClient.ClientIdentity newId = SunshineStreamClient.loadOrCreateIdentity(clientDir);
                assertFalse(oldId.uniqueId.equals(newId.uniqueId), "Cycle 5 rotated client uniqueId");

                assertEquals(0, missingConnClose.get(), "Every HTTPS request includes Connection: close");
                assertEquals(1, maxConcurrentSockets.get(), "Direct SSLSocket never exceeds 1 concurrent open socket");
                assertEquals(0, concurrencyViolations.get(), "Zero concurrent socket collisions across re-pair and resetIdentity");
                assertEquals(0, leakedSockets.get(), "Zero leaked sockets under keep-alive, omitted Connection, chunked, and error bodies");
            }
        } finally {
            deleteDir(clientDir);
            deleteDir(serverDir);
        }
    }

    private static void testExtractXmlStatusCodeElementFallbackAndScanRejection() throws Exception {
        assertEquals("503",
                SunshineStreamClient.extractXmlStatusCode("<root><status_code>503</status_code></root>"),
                "extractXmlStatusCode falls back to <status_code> element tag");
        assertEquals("200",
                SunshineStreamClient.extractXmlStatusCode("<root status_code=\"200\"><status_code>503</status_code></root>"),
                "extractXmlStatusCode prefers status_code attribute when present");

        try (ServerSocket sunSrv = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            final int sunPort = sunSrv.getLocalPort();
            Thread t = new Thread(() -> {
                while (!sunSrv.isClosed()) {
                    try (Socket c = sunSrv.accept()) {
                        readHttpRequestHeadersLineSafe(c.getInputStream());
                        writeHttpsResponse(c.getOutputStream(), 200, "OK",
                                "<root><status_code>503</status_code><hostname>PC</hostname>"
                                        + "<uniqueid>REAL-UUID-1</uniqueid></root>");
                    } catch (Exception ignored) {
                        if (sunSrv.isClosed()) break;
                    }
                }
            }, "mock-sunshine-elem-503");
            t.setDaemon(true);
            t.start();

            String scanned = GamingZone.scanCandidatesForGamingPcSync(
                    null, Collections.singletonList("127.0.0.1"), 0, sunPort, "", 800);
            assertEquals("", scanned,
                    "scanCandidatesForGamingPcSync rejects <status_code>503</status_code> element response");
        }
    }

    private static void testIsChunkedBodyCompleteOverflowGuard() throws Exception {
        byte[] huge = "7fffffff\r\nhello\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        assertFalse(SunshineStreamClient.isChunkedBodyComplete(huge, 0, huge.length),
                "isChunkedBodyComplete safely rejects 7fffffff chunk size without int overflow");

        byte[] unsignedOverflow = "80000000\r\nhello\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        assertFalse(SunshineStreamClient.isChunkedBodyComplete(unsignedOverflow, 0, unsignedOverflow.length),
                "isChunkedBodyComplete safely rejects 80000000 chunk size");

        byte[] complete = "5\r\nhello\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(SunshineStreamClient.isChunkedBodyComplete(complete, 0, complete.length),
                "isChunkedBodyComplete accepts valid complete chunked body");

        byte[] incomplete = "5\r\nhel".getBytes(StandardCharsets.ISO_8859_1);
        assertFalse(SunshineStreamClient.isChunkedBodyComplete(incomplete, 0, incomplete.length),
                "isChunkedBodyComplete returns false for truncated chunk data");
    }

    private static void testM3DecoderOperatingRateAndVendorKeyMatching() {
        assertEquals(120, GameStreamActivity.computeBoundedOperatingRate(30),
                "30fps bounded operating rate floors at 120");
        assertEquals(120, GameStreamActivity.computeBoundedOperatingRate(60),
                "60fps bounded operating rate is 120 (never 32767)");
        assertEquals(180, GameStreamActivity.computeBoundedOperatingRate(90),
                "90fps bounded operating rate is 180");
        assertEquals(240, GameStreamActivity.computeBoundedOperatingRate(120),
                "120fps bounded operating rate caps at 240");
        assertTrue(GameStreamActivity.computeBoundedOperatingRate(240) <= 240,
                "Operating rate must never approach Short.MAX_VALUE (32767)");

        assertEquals("vendor.mtk.vdec.lowlatency",
                GameStreamActivity.resolveVendorLowLatencyKey("c2.mtk.hevc.decoder", "mt5879"),
                "MediaTek decoder resolves vendor.mtk.vdec.lowlatency");
        assertEquals("vendor.aml.vdec.lowlatency",
                GameStreamActivity.resolveVendorLowLatencyKey("OMX.amlogic.hevc.decoder.awesome", "amlogic"),
                "Amlogic decoder resolves vendor.aml.vdec.lowlatency");
        assertEquals("vendor.qti-ext-dec-low-latency.enable",
                GameStreamActivity.resolveVendorLowLatencyKey("c2.qti.avc.decoder", "qcom"),
                "Qualcomm decoder resolves vendor.qti-ext-dec-low-latency.enable");
        assertEquals("vendor.rtk.vdec.lowlatency",
                GameStreamActivity.resolveVendorLowLatencyKey("c2.rtk.avc.decoder", "rtk"),
                "Realtek decoder resolves vendor.rtk.vdec.lowlatency");
        assertEquals("vendor.qti-ext-dec-low-latency.enable",
                GameStreamActivity.resolveVendorLowLatencyKey("c2.qti.avc.decoder", "mt5879"),
                "Explicit Qualcomm codec overrides mt5879 hardware fallback");
        assertEquals("vendor.aml.vdec.lowlatency",
                GameStreamActivity.resolveVendorLowLatencyKey("c2.amlogic.hevc.decoder", "mt5879"),
                "Explicit Amlogic codec overrides mt5879 hardware fallback");
        assertEquals("vendor.rtk.vdec.lowlatency",
                GameStreamActivity.resolveVendorLowLatencyKey("c2.rtk.avc.decoder", "mt5879"),
                "Explicit Realtek codec overrides mt5879 hardware fallback");
        assertEquals(null,
                GameStreamActivity.resolveVendorLowLatencyKey("c2.exynos.avc.decoder", "mt5879"),
                "Explicit Exynos codec does not fall back to mt5879 MediaTek key");
        assertEquals(null,
                GameStreamActivity.resolveVendorLowLatencyKey("c2.brcm.hevc.decoder", "mt5879"),
                "Explicit Broadcom codec does not fall back to mt5879 MediaTek key");
        assertEquals("vendor.mtk.vdec.lowlatency",
                GameStreamActivity.resolveVendorLowLatencyKey("c2.generic.avc.decoder", "mt5879"),
                "Generic codec falls back to mt5879 MediaTek low-latency key");
        assertEquals(null,
                GameStreamActivity.resolveVendorLowLatencyKey("c2.generic.avc.decoder", "generic"),
                "Unknown SoC returns null instead of shotgunning cross-vendor keys");
    }

    private static void testM3ZeroAllocAccessUnitPackingAndPendingFrameRing() {
        byte[] vps = new byte[] {0, 0, 0, 1, 0x40};
        byte[] sps = new byte[] {0, 0, 0, 1, 0x42};
        byte[] pps = new byte[] {0, 0, 0, 1, 0x44};
        byte[] idr = new byte[] {0, 0, 0, 1, 0x26, 0x01, 0x02};

        ByteBuffer directBuf = ByteBuffer.allocateDirect(64);
        int written = GameStreamActivity.writeAccessUnitToInputBuffer(
                directBuf, vps, sps, pps, idr, idr.length);
        assertEquals(vps.length + sps.length + pps.length + idr.length, written,
                "writeAccessUnitToInputBuffer writes exact concatenated length directly into ByteBuffer");
        directBuf.flip();
        byte[] out = new byte[written];
        directBuf.get(out);
        assertEquals((byte) 0x40, out[4], "First NAL is VPS");
        assertEquals((byte) 0x42, out[9], "Second NAL is SPS");
        assertEquals((byte) 0x44, out[14], "Third NAL is PPS");
        assertEquals((byte) 0x26, out[19], "Fourth NAL is IDR slice");

        ByteBuffer tinyBuf = ByteBuffer.allocateDirect(8);
        assertEquals(-1,
                GameStreamActivity.writeAccessUnitToInputBuffer(tinyBuf, vps, sps, pps, idr, idr.length),
                "Insufficient ByteBuffer capacity returns -1 safely without BufferOverflowException");

        GameStreamActivity.PendingFrameRing ring = new GameStreamActivity.PendingFrameRing(3, 1024);
        assertTrue(ring.isEmpty(), "Ring starts empty");
        assertTrue(ring.offer(vps, sps, pps, idr, idr.length, 100L, 1), "Slot 1 accepted");
        assertTrue(ring.offer(null, null, null, idr, idr.length, 200L, 0), "Slot 2 accepted");
        assertTrue(ring.offer(null, null, null, idr, idr.length, 300L, 0), "Slot 3 accepted");
        assertFalse(ring.offer(null, null, null, idr, idr.length, 400L, 0),
                "4th offer rejected cleanly when capacity=3");

        GameStreamActivity.PendingFrameRing boundsRing = new GameStreamActivity.PendingFrameRing(2, 1024);
        assertFalse(boundsRing.offer(null, null, null, new byte[10], 50, 100L, 0),
                "PendingFrameRing.offer rejects frameLen > frameData.length without ArrayIndexOutOfBoundsException");
        assertFalse(boundsRing.offer(new byte[4], null, null, new byte[10], Integer.MAX_VALUE, 100L, 0),
                "PendingFrameRing.offer rejects integer-overflow frameLen safely");
        assertFalse(GameStreamActivity.isValidDecodeUnit(null, 4),
                "isValidDecodeUnit rejects null buffer");
        assertFalse(GameStreamActivity.isValidDecodeUnit(new byte[10], 0),
                "isValidDecodeUnit rejects non-positive length");
        assertFalse(GameStreamActivity.isValidDecodeUnit(new byte[10], 50),
                "isValidDecodeUnit rejects decodeUnitLength > decodeUnitData.length");
        assertTrue(GameStreamActivity.isValidDecodeUnit(new byte[10], 10),
                "isValidDecodeUnit accepts exact buffer length");
        assertEquals(3, ring.size(), "Ring reports exact queued count");
        try {
            GameStreamActivity.PendingFrameRing.FrameSlot head = ring.awaitHead(0L);
            assertTrue(head != null, "Head slot non-null");
            assertEquals(100L, head.ptsUs, "FIFO head pts matches first frame");
            assertEquals(written, head.length, "Head slot contains prepended VPS/SPS/PPS + IDR");
            ring.popHead();
            assertEquals(2, ring.size(), "Size decrements after popHead");
        } catch (InterruptedException ie) {
            throw new AssertionError("Unexpected interrupt", ie);
        }

        GameStreamActivity.IdrCooldownTracker cooldown = new GameStreamActivity.IdrCooldownTracker(500L);
        assertTrue(cooldown.tryConsumeIdrRequest(1000L), "Initial drop allows IDR request");
        assertFalse(cooldown.tryConsumeIdrRequest(1200L), "Duplicate drop at +200ms suppresses IDR storm");
        assertFalse(cooldown.tryConsumeIdrRequest(1499L), "Drop at +499ms still suppressed");
        assertTrue(cooldown.tryConsumeIdrRequest(1500L), "Drop at +500ms allows recovery IDR");
    }

    private static void testM3FramePacingMonotonicityAndZero2FrameStall() {
        long periodNs = 16_666_667L;
        long anchorNs = 1_000_000_000L;
        long prevBalanced = 0L;
        long prevSmooth = 0L;

        for (int frame = 0; frame < 240; frame++) {
            long nowNs = anchorNs + frame * periodNs + ((frame % 5) * 1_500_000L);
            long bTarget = GameStreamActivity.computeNextPacedRenderNs(
                    nowNs, anchorNs, periodNs, prevBalanced, false);
            assertTrue(bTarget >= prevBalanced,
                    "Balanced pacing must stay monotonic at frame " + frame);
            assertTrue(bTarget <= nowNs + periodNs + 2_000_000L,
                    "Balanced pacing must never stall 2+ frames into the future at frame " + frame);
            prevBalanced = bTarget;

            long sTarget = GameStreamActivity.computeNextPacedRenderNs(
                    nowNs, anchorNs, periodNs, prevSmooth, true);
            assertTrue(sTarget >= prevSmooth,
                    "Smoothness pacing must stay monotonic at frame " + frame);
            assertTrue(sTarget <= nowNs + periodNs * 2L,
                    "Smoothness pacing stays within 2-frame cap at frame " + frame);
            prevSmooth = sTarget;
        }
    }

    private static void testM3TelemetryFormattingAndSecretsCrossProcessRefresh() throws Exception {
        String line = GameStreamActivity.formatStreamTelemetryLine(
                "c2.mtk.avc.decoder", "H.264", true, 1920, 1080, "balanced", 60,
                59.8, 59.5, 3.25, 6.10, 0, 1, 0, 28.4, 30, 4L);
        assertTrue(line.contains("telemetry") && line.contains("inFps=59.8") && line.contains("outFps=59.5")
                        && line.contains("decodeMs=3.25") && line.contains("dropIn=0")
                        && line.contains("dropOut=1") && line.contains("lowLatency=true"),
                "Telemetry string formats all required streaming diagnostics");

        File dir = Files.createTempDirectory("mytv_m3_sec_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(dir);
            assertTrue(Secrets.putSecret(null, Secrets.GAMING_PC_TOKEN, "tok_v1"), "Write initial token");
            assertEquals("tok_v1", Secrets.getSecret(null, Secrets.GAMING_PC_TOKEN), "Read initial token");
            // Simulate external :home write followed by immediate :stream refresh (<5s window)
            Secrets.clearMemoryCacheForTest();
            assertTrue(Secrets.putSecret(null, Secrets.GAMING_PC_TOKEN, "tok_v2"), "Write updated token");
            Secrets.refreshIfDirChangedForDir(dir);
            assertEquals("tok_v2", Secrets.getSecret(null, Secrets.GAMING_PC_TOKEN),
                    "refreshIfDirChangedForDir observes updated token immediately");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteDir(dir);
        }
    }

    private static void testM3CrossProcessActiveStreamingSessionFileSync() throws Exception {
        File sessionDir = Files.createTempDirectory("mytv_m3_sess_").toFile();
        try {
            GamingZone.setTestActiveSessionDir(sessionDir);
            GamingZone.ActiveStreamingSession s = new GamingZone.ActiveStreamingSession(
                    "steam_728880", "Overcooked! 2", "192.168.1.12", 8485, 123456789L);
            GamingZone.setActiveStreamingSession(null, s);

            File persisted = new File(sessionDir, "active_stream_session.txt");
            assertTrue(persisted.isFile(), "setActiveStreamingSession persists cross-process session file");

            GamingZone.ActiveStreamingSession loaded = GamingZone.getActiveStreamingSession(null);
            assertTrue(loaded != null, "getActiveStreamingSession reads persisted session");
            assertEquals("steam_728880", loaded.gameId, "Persisted gameId matches");
            assertEquals("192.168.1.12", loaded.hostIp, "Persisted hostIp matches");

            // Simulate :stream clearing the session file on normal exit
            assertTrue(persisted.delete(), "Simulate :stream clearing active_stream_session.txt");
            assertEquals(null, GamingZone.getActiveStreamingSession(null),
                    ":home sees null ActiveStreamingSession when :stream cleared the file (no duplicate /terminate)");

            // Clear API also removes file cleanly
            GamingZone.setActiveStreamingSession(null, s);
            assertTrue(persisted.isFile(), "Re-written session file exists");
            GamingZone.ActiveStreamingSession cleared = GamingZone.clearActiveStreamingSession(null);
            assertTrue(cleared != null && "steam_728880".equals(cleared.gameId),
                    "clearActiveStreamingSession returns prior session");
            assertFalse(persisted.exists(), "clearActiveStreamingSession deletes session file");
        } finally {
            GamingZone.setTestActiveSessionDir(null);
            GamingZone.clearActiveStreamingSession(null);
            deleteDir(sessionDir);
        }
    }

    private static void testM3ScreensaverCatalogAndRecsBackgroundPauseGuards() {
        try {
            ScreensaverCatalog.consumePausedPrefetchNeedsResume();
            ScreensaverCatalog.setBackgroundActivityPaused(true);
            assertTrue(ScreensaverCatalog.isBackgroundActivityPaused(),
                    "ScreensaverCatalog background pause flag activates");

            Recs.setBackgroundActivityPaused(true);
            assertTrue(Recs.isBackgroundActivityPaused(), "Recs background pause flag activates");
            assertTrue(Recs.shouldSkipBackgroundWork(Recs.isBackgroundActivityPaused(), false),
                    "Background Recs work skipped when paused without explicit focus callback");
        } finally {
            ScreensaverCatalog.setBackgroundActivityPaused(false);
            ScreensaverCatalog.consumePausedPrefetchNeedsResume();
            Recs.setBackgroundActivityPaused(false);
        }
    }

    private static void testSunshineMtlsTls12EngineAliasAndSslExceptionRecovery() throws Exception {
        File clientDir = Files.createTempDirectory("mytv_m4_tls_cli_").toFile();
        File serverDir = Files.createTempDirectory("mytv_m4_tls_srv_").toFile();
        try {
            SunshineStreamClient.ClientIdentity id = SunshineStreamClient.loadOrCreateIdentity(clientDir);
            X509ExtendedKeyManager km = SunshineStreamClient.createMtlsKeyManager(id);
            assertEquals("mytv-gamestream",
                    km.chooseEngineClientAlias(new String[] {"RSA"}, null, null),
                    "X509ExtendedKeyManager overrides chooseEngineClientAlias for ConscryptEngineSocket");

            final List<String> negotiatedProtocols = Collections.synchronizedList(new ArrayList<>());
            try (SSLServerSocket httpsSrv = createMockMtlsServer(serverDir)) {
                final int httpsPort = httpsSrv.getLocalPort();
                Thread srvThread = new Thread(() -> {
                    while (!httpsSrv.isClosed()) {
                        try (SSLSocket cli = (SSLSocket) httpsSrv.accept()) {
                            cli.setSoTimeout(1500);
                            String req = readHttpRequestHeadersLineSafe(cli.getInputStream());
                            negotiatedProtocols.add(cli.getSession().getProtocol());
                            if (req.startsWith("GET /serverinfo")) {
                                writeHttpsResponse(cli.getOutputStream(), 200, "OK",
                                        "<root status_code=\"200\"><PairStatus>1</PairStatus>"
                                                + "<appversion>7.1.431.-1</appversion>"
                                                + "<ServerCodecModeSupport>259</ServerCodecModeSupport>"
                                                + "<currentgame>0</currentgame></root>");
                            } else if (req.startsWith("GET /applist")) {
                                writeHttpsResponse(cli.getOutputStream(), 200, "OK",
                                        "<root status_code=\"200\"><App><ID>1</ID>"
                                                + "<AppTitle>Desktop</AppTitle><IsHdrSupported>0</IsHdrSupported></App></root>");
                            } else {
                                writeHttpsResponse(cli.getOutputStream(), 200, "OK",
                                        "<root status_code=\"200\"><sessionUrl0>rtsp://127.0.0.1:48010</sessionUrl0></root>");
                            }
                        } catch (Exception e) {
                            if (httpsSrv.isClosed()) break;
                        }
                    }
                }, "mock-sunshine-tls12");
                srvThread.setDaemon(true);
                srvThread.start();

                SunshineStreamClient.LaunchSession sess = SunshineStreamClient.startSunshineSession(
                        null, clientDir, "127.0.0.1", "Desktop", 1920, 1080, 60,
                        MoonBridge.AUDIO_CONFIGURATION_STEREO, false, true, false, 0, httpsPort, 0);
                assertEquals("rtsp://127.0.0.1:48010", sess.rtspSessionUrl, "TLSv1.2 session succeeds");
                assertTrue(negotiatedProtocols.size() >= 3, "Server observed all 3 mTLS requests");
                for (String proto : negotiatedProtocols) {
                    assertEquals("TLSv1.2", proto, "Sunshine :47984 mTLS pins TLSv1.2 to avoid Conscrypt TLSv1.3 alert");
                }
            }

            // Simulate a server sending TLS fatal alert 80 (internal_error -> SSLException) after ClientHello
            try (ServerSocket badTlsSrv = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
                final int badPort = badTlsSrv.getLocalPort();
                Thread badThread = new Thread(() -> {
                    try (Socket s = badTlsSrv.accept()) {
                        s.setSoTimeout(1000);
                        byte[] ch = new byte[512];
                        s.getInputStream().read(ch);
                        s.getOutputStream().write(new byte[] {0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x50});
                        s.getOutputStream().flush();
                        Thread.sleep(150);
                    } catch (Exception ignored) {
                    }
                }, "mock-tls-alert80");
                badThread.setDaemon(true);
                badThread.start();

                SSLSocketFactory f = SunshineStreamClient.getOrCreateMtlsSocketFactory(id);
                String xml = SunshineStreamClient.fetchPairedServerInfoXml("127.0.0.1", badPort, id, f);
                assertEquals(null, xml, "fetchPairedServerInfoXml returns null on TLS fatal alert 80 (SSLException) instead of aborting");
            }
        } finally {
            deleteDir(clientDir);
            deleteDir(serverDir);
        }
    }

    private static void testSunshineMidSessionSslAlert80RecoveryAndRawLanChunked() throws Exception {
        // 1. Verify rawLanHttpGet completes chunked responses immediately without waiting for socket close
        try (ServerSocket chunkedHttpSrv = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            final int cPort = chunkedHttpSrv.getLocalPort();
            Thread ct = new Thread(() -> {
                try (Socket s = chunkedHttpSrv.accept()) {
                    readHttpRequestHeadersLineSafe(s.getInputStream());
                    String xml = "<root status_code=\"200\"><hostname>ChunkHost</hostname></root>";
                    byte[] body = xml.getBytes(StandardCharsets.UTF_8);
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                            + Integer.toHexString(body.length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(body);
                    out.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    // Keep socket open longer than readTimeoutMs (600ms) to prove early chunk completion
                    Thread.sleep(1500L);
                } catch (Exception ignored) {
                }
            }, "mock-rawlan-chunked");
            ct.setDaemon(true);
            ct.start();

            long t0 = System.currentTimeMillis();
            String resp = SunshineStreamClient.rawLanHttpGet("127.0.0.1", cPort, "/serverinfo", 1000, 600);
            long elapsed = System.currentTimeMillis() - t0;
            assertTrue(resp.contains("<hostname>ChunkHost</hostname>"),
                    "rawLanHttpGet decodes chunked body cleanly");
            assertTrue(elapsed < 1000L,
                    "rawLanHttpGet returns promptly on 0\\r\\n\\r\\n without waiting for socket close (elapsed=" + elapsed + "ms)");
        }

        // 2. Verify startSunshineSession recovers when /serverinfo succeeds (PairStatus=1) then /applist triggers TLS fatal alert 80
        File clientDir = Files.createTempDirectory("mytv_m4_alert80_cli_").toFile();
        File serverDir = Files.createTempDirectory("mytv_m4_alert80_srv_").toFile();
        try {
            SunshineStreamClient.ClientIdentity srvId = SunshineStreamClient.loadOrCreateIdentity(serverDir);
            final SSLContext srvSslCtx = createMockMtlsSslContext(serverDir);
            final AtomicBoolean failNextApplistWithAlert80 = new AtomicBoolean(true);
            final AtomicBoolean paired = new AtomicBoolean(true);
            final AtomicInteger pairChallengeHits = new AtomicInteger(0);
            final AtomicReference<String> capturedPin = new AtomicReference<>("");
            final AtomicReference<byte[]> pendingSalt = new AtomicReference<>(new byte[16]);
            final AtomicReference<byte[]> serverSecretRef = new AtomicReference<>(new byte[16]);

            try (ServerSocket pinAgent = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                 ServerSocket httpSrv = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
                 ServerSocket rawHttpsSrv = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
                final int agentPort = pinAgent.getLocalPort();
                final int httpPort = httpSrv.getLocalPort();
                final int httpsPort = rawHttpsSrv.getLocalPort();

                Thread pinThread = new Thread(() -> {
                    while (!pinAgent.isClosed()) {
                        try (Socket c = pinAgent.accept()) {
                            c.setSoTimeout(1500);
                            String req = readHttpRequestHeadersLineSafe(c.getInputStream());
                            int cl = 0;
                            for (String line : req.split("\r\n")) {
                                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                                    cl = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                                }
                            }
                            byte[] bodyBytes = new byte[cl];
                            int read = 0;
                            while (read < cl) {
                                int n = c.getInputStream().read(bodyBytes, read, cl - read);
                                if (n < 0) break;
                                read += n;
                            }
                            Object obj = RemoteAuthManager.MiniJson.parse(new String(bodyBytes, StandardCharsets.UTF_8));
                            if (obj instanceof java.util.Map) {
                                @SuppressWarnings("unchecked")
                                java.util.Map<String, Object> m = (java.util.Map<String, Object>) obj;
                                capturedPin.set(String.valueOf(m.get("pin")));
                            }
                            byte[] jBytes = "{\"ok\":true,\"paired\":true}".getBytes(StandardCharsets.UTF_8);
                            String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + jBytes.length + "\r\n\r\n";
                            c.getOutputStream().write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                            c.getOutputStream().write(jBytes);
                            c.getOutputStream().flush();
                        } catch (Exception ignored) {
                            if (pinAgent.isClosed()) break;
                        }
                    }
                }, "mock-alert80-pin");
                pinThread.setDaemon(true);
                pinThread.start();

                Thread httpThread = new Thread(() -> {
                    while (!httpSrv.isClosed()) {
                        try (Socket c = httpSrv.accept()) {
                            c.setSoTimeout(2000);
                            String req = readHttpRequestHeadersLineSafe(c.getInputStream());
                            String path = req.split("\r\n")[0].split(" ")[1];
                            if (path.contains("phrase=getservercert")) {
                                pendingSalt.set(SunshineStreamClient.hexToBytes(queryParam(path, "salt")));
                                String srvCertHex = SunshineStreamClient.bytesToHex(srvId.certificate.getEncoded());
                                writeHttpsResponseCustom(c.getOutputStream(), 200, "OK", null, false, false,
                                        "<root status_code=\"200\"><paired>1</paired><plaincert>"
                                                + srvCertHex + "</plaincert></root>");
                            } else if (path.contains("clientchallenge=")) {
                                long deadline = System.currentTimeMillis() + 2000L;
                                while (capturedPin.get().isEmpty() && System.currentTimeMillis() < deadline) {
                                    Thread.sleep(10L);
                                }
                                byte[] aesKey = SunshineStreamClient.derivePairingAesKey(pendingSalt.get(), capturedPin.get());
                                byte[] decCli = SunshineStreamClient.aesEcbDecrypt(
                                        aesKey, SunshineStreamClient.hexToBytes(queryParam(path, "clientchallenge")));
                                byte[] srvChallenge = new byte[16];
                                Arrays.fill(srvChallenge, (byte) 0x33);
                                byte[] srvSecret = new byte[16];
                                Arrays.fill(srvSecret, (byte) 0x66);
                                serverSecretRef.set(srvSecret);
                                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                                md.update(decCli);
                                md.update(srvId.certificate.getSignature());
                                md.update(srvSecret);
                                byte[] srvRespHash = md.digest();
                                byte[] plainResp = new byte[48];
                                System.arraycopy(srvRespHash, 0, plainResp, 0, 32);
                                System.arraycopy(srvChallenge, 0, plainResp, 32, 16);
                                byte[] encResp = SunshineStreamClient.aesEcbEncrypt(aesKey, plainResp);
                                writeHttpsResponseCustom(c.getOutputStream(), 200, "OK", null, false, false,
                                        "<root status_code=\"200\"><paired>1</paired><challengeresponse>"
                                                + SunshineStreamClient.bytesToHex(encResp) + "</challengeresponse></root>");
                            } else if (path.contains("serverchallengeresp=")) {
                                byte[] srvSecret = serverSecretRef.get();
                                java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
                                sig.initSign(srvId.keyPair.getPrivate());
                                sig.update(srvSecret);
                                byte[] srvSig = sig.sign();
                                byte[] pairingSecret = new byte[srvSecret.length + srvSig.length];
                                System.arraycopy(srvSecret, 0, pairingSecret, 0, srvSecret.length);
                                System.arraycopy(srvSig, 0, pairingSecret, srvSecret.length, srvSig.length);
                                writeHttpsResponseCustom(c.getOutputStream(), 200, "OK", null, false, false,
                                        "<root status_code=\"200\"><paired>1</paired><pairingsecret>"
                                                + SunshineStreamClient.bytesToHex(pairingSecret) + "</pairingsecret></root>");
                            } else if (path.contains("clientpairingsecret=")) {
                                writeHttpsResponseCustom(c.getOutputStream(), 200, "OK", null, false, false,
                                        "<root status_code=\"200\"><paired>1</paired></root>");
                            } else {
                                writeHttpsResponseCustom(c.getOutputStream(), 404, "Not Found", null, false, false,
                                        "<root status_code=\"404\"/>");
                            }
                        } catch (Exception ignored) {
                            if (httpSrv.isClosed()) break;
                        }
                    }
                }, "mock-alert80-http");
                httpThread.setDaemon(true);
                httpThread.start();

                final AtomicInteger httpsConnSeq = new AtomicInteger(0);
                Thread httpsThread = new Thread(() -> {
                    while (!rawHttpsSrv.isClosed()) {
                        try (Socket raw = rawHttpsSrv.accept()) {
                            raw.setSoTimeout(2000);
                            int seq = httpsConnSeq.getAndIncrement();
                            if (seq == 1 && failNextApplistWithAlert80.compareAndSet(true, false)) {
                                // Conn 0 was /serverinfo (PairStatus=1); Conn 1 (/applist) triggers fatal TLS alert 80
                                paired.set(false);
                                raw.getInputStream().read(new byte[512]);
                                raw.getOutputStream().write(new byte[] {0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x50});
                                raw.getOutputStream().flush();
                                Thread.sleep(100L);
                                continue;
                            }
                            try (SSLSocket cli = (SSLSocket) srvSslCtx.getSocketFactory()
                                    .createSocket(raw, "127.0.0.1", raw.getPort(), true)) {
                                cli.setUseClientMode(false);
                                cli.setNeedClientAuth(true);
                                cli.startHandshake();
                                String req = readHttpRequestHeadersLineSafe(cli.getInputStream());
                                if (req.startsWith("GET /serverinfo")) {
                                    String ps = paired.get() ? "1" : "0";
                                    writeHttpsResponse(cli.getOutputStream(), 200, "OK",
                                            "<root status_code=\"200\"><PairStatus>" + ps + "</PairStatus>"
                                                    + "<currentgame>0</currentgame></root>");
                                } else if (req.startsWith("GET /pair") && req.contains("phrase=pairchallenge")) {
                                    pairChallengeHits.incrementAndGet();
                                    paired.set(true);
                                    writeHttpsResponse(cli.getOutputStream(), 200, "OK",
                                            "<root status_code=\"200\"><paired>1</paired></root>");
                                } else if (req.startsWith("GET /applist")) {
                                    writeHttpsResponse(cli.getOutputStream(), 200, "OK",
                                            "<root status_code=\"200\"><App><ID>202</ID>"
                                                    + "<AppTitle>Desktop</AppTitle><IsHdrSupported>0</IsHdrSupported></App></root>");
                                } else if (req.startsWith("GET /launch")) {
                                    writeHttpsResponse(cli.getOutputStream(), 200, "OK",
                                            "<root status_code=\"200\"><sessionUrl0>rtsp://127.0.0.1:48010</sessionUrl0></root>");
                                } else {
                                    writeHttpsResponse(cli.getOutputStream(), 404, "Not Found", "<root status_code=\"404\"/>");
                                }
                            }
                        } catch (Exception ignored) {
                            if (rawHttpsSrv.isClosed()) break;
                        }
                    }
                }, "mock-alert80-https");
                httpsThread.setDaemon(true);
                httpsThread.start();

                SunshineStreamClient.LaunchSession sess = SunshineStreamClient.startSunshineSession(
                        null, clientDir, "127.0.0.1", "Desktop", 1920, 1080, 60,
                        MoonBridge.AUDIO_CONFIGURATION_STEREO, false, true, false,
                        httpPort, httpsPort, agentPort);
                assertEquals("rtsp://127.0.0.1:48010", sess.rtspSessionUrl,
                        "startSunshineSession recovers from mid-session SSLException on /applist via re-pair");
                assertEquals(202, sess.appId, "Recovered session launches Desktop app");
                assertEquals(1, pairChallengeHits.get(), "Completed automatic re-pair after mid-session alert 80");
            }
        } finally {
            deleteDir(clientDir);
            deleteDir(serverDir);
        }
    }

    private static SSLContext createMockMtlsSslContext(File dir) throws Exception {
        SunshineStreamClient.ClientIdentity srvId = SunshineStreamClient.loadOrCreateIdentity(dir);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("srv", srvId.keyPair.getPrivate(), new char[0], new Certificate[] {srvId.certificate});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                    @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), trustAll, null);
        return ctx;
    }

    private static void testScreensaverCatalogPrefetchResumeRaceNoDrop() {
        try {
            // Reproduce exact GAP-7 interleaving:
            // 1. Worker paused -> sets PAUSED_PREFETCH_NEEDS_RESUME=true, breaks loop (PREFETCH_RUNNING still true)
            ScreensaverCatalog.setPrefetchRunningForTest(true);
            ScreensaverCatalog.setBackgroundActivityPaused(true);
            assertFalse(ScreensaverCatalog.tryBeginPrefetchOrRecordPendingResume(),
                    "Paused state prevents immediate start and flags PAUSED_PREFETCH_NEEDS_RESUME");

            // 2. HomeView.resume() unpauses, consumes PAUSED_PREFETCH_NEEDS_RESUME, and calls prefetchAsync()
            //    BEFORE worker reaches finally
            ScreensaverCatalog.setBackgroundActivityPaused(false);
            assertTrue(ScreensaverCatalog.consumePausedPrefetchNeedsResume(),
                    "HomeView.resume() consumes PAUSED_PREFETCH_NEEDS_RESUME");
            assertFalse(ScreensaverCatalog.tryBeginPrefetchOrRecordPendingResume(),
                    "Worker still in finally; tryBeginPrefetchOrRecordPendingResume returns false and arms pending resume");

            // 3. Worker reaches finally: MUST observe pending resume and signal restart=true
            assertTrue(ScreensaverCatalog.completePrefetchRunAndShouldRestart(),
                    "Worker finally must restart instead of dropping HomeView.resume() prefetch");
            assertFalse(ScreensaverCatalog.isPrefetchRunningForTest(),
                    "PREFETCH_RUNNING cleared cleanly on worker exit");

            // 4. Subsequent clean idle completion does not spuriously restart
            assertFalse(ScreensaverCatalog.completePrefetchRunAndShouldRestart(),
                    "Idle completion without pending flags does not restart");

            // 5. If worker exits while STILL paused, PAUSED_PREFETCH_NEEDS_RESUME must stay preserved
            ScreensaverCatalog.setPrefetchRunningForTest(true);
            ScreensaverCatalog.setBackgroundActivityPaused(true);
            ScreensaverCatalog.tryBeginPrefetchOrRecordPendingResume();
            assertFalse(ScreensaverCatalog.completePrefetchRunAndShouldRestart(),
                    "Worker exiting while paused does not restart");
            ScreensaverCatalog.setBackgroundActivityPaused(false);
            assertTrue(ScreensaverCatalog.consumePausedPrefetchNeedsResume(),
                    "PAUSED_PREFETCH_NEEDS_RESUME remains preserved when worker exits while paused");
        } finally {
            ScreensaverCatalog.setBackgroundActivityPaused(false);
            ScreensaverCatalog.consumePausedPrefetchNeedsResume();
            ScreensaverCatalog.completePrefetchRunAndShouldRestart();
            ScreensaverCatalog.setPrefetchRunningForTest(false);
        }
    }

    private static void testGamingZoneColdStartPrelaunchWaitHeadroom() {
        assertTrue(GamingZone.PRELAUNCH_WINDOW_WAIT_MAX_MS >= 30_000L,
                "Cold-start Steam (~12s) + game window init requires >=30s prelaunch wait headroom");
        assertTrue(GamingZone.PRELAUNCH_WINDOW_WAIT_MAX_MS <= 45_000L,
                "Prelaunch wait cap stays bounded to <=45s");
        assertTrue(GamingZone.PRELAUNCH_WINDOW_POLL_MS >= 200L && GamingZone.PRELAUNCH_WINDOW_POLL_MS <= 500L,
                "Prelaunch status poll interval stays responsive (200-500ms)");
    }

    private static void testMtk4kLowLatencyCrashGuardAndOperatingRate() {
        // MediaTek libcodec2.MTK.so crashes in VCodecVideo::EnableRacingDisplay -> MsVdecSetSliceCount
        // when low-latency racing display is enabled at 4K UHD (> 2560x1440).
        assertTrue(GameStreamActivity.isMediaTekDecoderOrHardware("c2.mtk.avc.decoder", "mt5879"),
                "Detects c2.mtk.* MediaTek decoder");
        assertTrue(GameStreamActivity.isMediaTekDecoderOrHardware("omx.mtk.video.decoder.hevc", "generic"),
                "Detects omx.mtk.* MediaTek decoder");
        assertFalse(GameStreamActivity.isMediaTekDecoderOrHardware("c2.qti.avc.decoder", "qcom"),
                "Non-MediaTek Qualcomm decoder is not classified as MediaTek");

        assertTrue(GameStreamActivity.shouldEnableLowLatencyKeys("c2.mtk.avc.decoder", "mt5879", 1920, 1080),
                "MediaTek 1080p keeps full low-latency decoder flags");
        assertTrue(GameStreamActivity.shouldEnableLowLatencyKeys("c2.mtk.avc.decoder", "mt5879", 2560, 1440),
                "MediaTek 1440p keeps full low-latency decoder flags");
        assertFalse(GameStreamActivity.shouldEnableLowLatencyKeys("c2.mtk.avc.decoder", "mt5879", 3840, 2160),
                "MediaTek 4K disables racing low-latency flags to avoid libcodec2.MTK.so SIGSEGV");
        assertTrue(GameStreamActivity.shouldEnableLowLatencyKeys("c2.qti.hevc.decoder", "qcom", 3840, 2160),
                "Non-MediaTek 4K decoders keep low-latency flags enabled");

        assertEquals(120, GameStreamActivity.computeBoundedOperatingRateForSize(60, 1920, 1080),
                "1080p@60 uses 120 operating rate");
        assertEquals(120, GameStreamActivity.computeBoundedOperatingRateForSize(60, 2560, 1440),
                "1440p@60 uses 120 operating rate");
        assertEquals(60, GameStreamActivity.computeBoundedOperatingRateForSize(60, 3840, 2160),
                "4K@60 caps operating rate to 60 so 4K60 VDECs are not overdriven to 4K120");

        int safe4kInput = GameStreamActivity.computeSafeMaxInputSize(3840, 2160);
        assertTrue(safe4kInput >= 2 * 1024 * 1024 && safe4kInput <= 4 * 1024 * 1024,
                "4K KEY_MAX_INPUT_SIZE stays safely capped between 2MB and 4MB (got " + safe4kInput + ")");
    }

    private static void testGameLaunchSpinnerTrueSeamlessLoop() {
        float at0 = ModalOverlayRenderer.computeInnerSpinnerAngleDeg(0f);
        float at360 = ModalOverlayRenderer.computeInnerSpinnerAngleDeg(360f);
        assertTrue(Math.abs(at0 - at360) < 0.001f,
                "Inner spinner angle must wrap seamlessly at 0 vs 360 deg (at0=" + at0 + ", at360=" + at360 + ")");

        // Check every 1-degree step around the full 0..360 cycle for zero discontinuity
        float prev = ModalOverlayRenderer.computeInnerSpinnerAngleDeg(0f);
        for (int deg = 1; deg <= 360; deg++) {
            float cur = ModalOverlayRenderer.computeInnerSpinnerAngleDeg((float) deg);
            float rawDiff = Math.abs(cur - prev);
            float circularDiff = Math.min(rawDiff, 360f - rawDiff);
            assertTrue(circularDiff <= 3.01f,
                    "Inner spinner must progress smoothly with zero jump at deg=" + deg
                            + " (prev=" + prev + ", cur=" + cur + ", diff=" + circularDiff + ")");
            prev = cur;
        }
    }

    private static void testDynamicGamingSettingsTipsNoHardcodedHardware() {
        // 1. 60Hz TV + 1080p 60Hz PC -> dynamic 60Hz & 1080p recommendations, zero hardcoded GPU assumptions
        SettingsTree.StateSnapshot s60 = new SettingsTree.StateSnapshot();
        s60.tvDisplayWidth = 3840;
        s60.tvDisplayHeight = 2160;
        s60.tvRefreshRateHz = 60;
        s60.pcDisplayWidth = 1920;
        s60.pcDisplayHeight = 1080;
        s60.pcDisplayRefreshHz = 60;
        s60.gamingResolution = "1080p";
        s60.gamingFps = 60;
        s60.networkType = "Wi-Fi";
        s60.wifiLinkSpeedMbps = 433;

        SettingsTree.Node root60 = SettingsTree.buildRoot(s60);
        SettingsTree.Node quality60 = root60.findById("gaming_stream_quality");
        assertTrue(quality60 != null, "gaming_stream_quality present");

        // Zero hardcoded laptop/RTX assumptions anywhere in Stream Quality subtree
        String allQualityText60 = collectSubtreeText(quality60).toLowerCase(java.util.Locale.ROOT);
        assertFalse(allQualityText60.contains("hybrid laptop"),
                "Settings must not hardcode 'hybrid laptop' assumptions");
        assertFalse(allQualityText60.contains("desktop rtx"),
                "Settings must not hardcode 'desktop RTX' assumptions");
        assertFalse(allQualityText60.contains("cross-gpu"),
                "Settings must not hardcode 'cross-GPU' assumptions");

        SettingsTree.Node fps60Node = root60.findById("gaming_set_fps:60");
        SettingsTree.Node fps120Node = root60.findById("gaming_set_fps:120");
        assertTrue(fps60Node != null && fps60Node.subtitle.contains("60Hz"),
                "60 FPS dynamically references detected 60Hz TV refresh rate: " + (fps60Node != null ? fps60Node.subtitle : "null"));
        assertTrue(fps120Node != null && fps120Node.subtitle.toLowerCase(java.util.Locale.ROOT).contains("exceeds"),
                "120 FPS honestly notes exceeding detected 60Hz TV: " + (fps120Node != null ? fps120Node.subtitle : "null"));

        SettingsTree.Node res1080Node = root60.findById("gaming_set_res:1080p");
        assertTrue(res1080Node != null && res1080Node.subtitle.contains("1920") && res1080Node.subtitle.contains("Recommended"),
                "1080p dynamically matches detected 1920x1080 PC display: " + (res1080Node != null ? res1080Node.subtitle : "null"));

        // 2. 120Hz TV + 4K 120Hz PC -> 120 FPS & 4K dynamically recommended (NOT hardcoded 60Hz / 1080p!)
        SettingsTree.StateSnapshot s120 = new SettingsTree.StateSnapshot();
        s120.tvDisplayWidth = 3840;
        s120.tvDisplayHeight = 2160;
        s120.tvRefreshRateHz = 120;
        s120.pcDisplayWidth = 3840;
        s120.pcDisplayHeight = 2160;
        s120.pcDisplayRefreshHz = 120;
        s120.gamingResolution = "4k";
        s120.gamingFps = 120;
        s120.networkType = "Ethernet";

        SettingsTree.Node root120 = SettingsTree.buildRoot(s120);
        SettingsTree.Node fps120On120Tv = root120.findById("gaming_set_fps:120");
        SettingsTree.Node fps60On120Tv = root120.findById("gaming_set_fps:60");
        SettingsTree.Node res4kOn4kPc = root120.findById("gaming_set_res:4k");
        SettingsTree.Node res1080On4kPc = root120.findById("gaming_set_res:1080p");
        assertTrue(fps120On120Tv != null && fps120On120Tv.subtitle.contains("120Hz")
                        && fps120On120Tv.subtitle.contains("Recommended"),
                "120Hz TV dynamically recommends 120 FPS: " + (fps120On120Tv != null ? fps120On120Tv.subtitle : "null"));
        assertTrue(fps60On120Tv != null && !fps60On120Tv.subtitle.contains("60Hz TV"),
                "120Hz TV must NOT claim 60Hz TV VSYNC: " + (fps60On120Tv != null ? fps60On120Tv.subtitle : "null"));
        assertTrue(res4kOn4kPc != null && res4kOn4kPc.subtitle.contains("Recommended"),
                "4K PC + 4K TV dynamically recommends 4K: " + (res4kOn4kPc != null ? res4kOn4kPc.subtitle : "null"));
        assertTrue(res1080On4kPc != null && !res1080On4kPc.subtitle.contains("Recommended"),
                "4K PC does not hardcode Recommended on 1080p: " + (res1080On4kPc != null ? res1080On4kPc.subtitle : "null"));

        // 3. Dynamic bitrate recommendation follows selected resolution + Wi-Fi link speed headroom
        SettingsTree.StateSnapshot sSlowWifi = new SettingsTree.StateSnapshot();
        sSlowWifi.gamingResolution = "1440p";
        sSlowWifi.gamingFps = 60;
        sSlowWifi.networkType = "Wi-Fi";
        sSlowWifi.wifiLinkSpeedMbps = 120;
        SettingsTree.Node rootWifi = SettingsTree.buildRoot(sSlowWifi);
        SettingsTree.Node bit40 = rootWifi.findById("gaming_set_bitrate:40");
        SettingsTree.Node bit100 = rootWifi.findById("gaming_set_bitrate:100");
        assertTrue(bit40 != null && bit40.subtitle.contains("Recommended"),
                "1440p@60 dynamically recommends 40 Mbps: " + (bit40 != null ? bit40.subtitle : "null"));
        assertTrue(bit100 != null && bit100.subtitle.contains("120 Mbps"),
                "Bitrate exceeding Wi-Fi link headroom warns dynamically: " + (bit100 != null ? bit100.subtitle : "null"));

        // 4. Unknown / offline hardware defaults to neutral descriptions with zero hardcoded 60Hz/4K claims
        SettingsTree.StateSnapshot sUnknown = new SettingsTree.StateSnapshot();
        sUnknown.tvDisplayWidth = 0;
        sUnknown.tvDisplayHeight = 0;
        sUnknown.tvRefreshRateHz = 0;
        sUnknown.pcDisplayWidth = 0;
        sUnknown.pcDisplayHeight = 0;
        sUnknown.pcDisplayRefreshHz = 0;
        SettingsTree.Node rootUnknown = SettingsTree.buildRoot(sUnknown);
        SettingsTree.Node fps60Unknown = rootUnknown.findById("gaming_set_fps:60");
        assertTrue(fps60Unknown != null && !fps60Unknown.subtitle.contains("60Hz TV"),
                "Unknown TV refresh rate must not assume 60Hz TV: " + (fps60Unknown != null ? fps60Unknown.subtitle : "null"));
    }

    private static void testEpicLauncherStatusAndSelectiveCopy() {
        // 1. LaptopStatus exposes steamRunning and epicRunning while preserving legacy constructors
        GamingZone.LaptopStatus legacy = new GamingZone.LaptopStatus(true, false, false, true, java.util.Collections.emptyList());
        assertFalse(legacy.steamRunning, "Legacy constructor defaults steamRunning to false");
        assertFalse(legacy.epicRunning, "Legacy constructor defaults epicRunning to false");

        GamingZone.LaptopStatus full = new GamingZone.LaptopStatus(
                true, false, false, true, java.util.Collections.emptyList(),
                88, true, false, "uuid-1", "PC", 1920, 1080, 60, false, true);
        assertFalse(full.steamRunning, "steamRunning reports false when only Epic is open");
        assertTrue(full.epicRunning, "epicRunning reports true when Epic Games Launcher is open");

        // 2. LaunchStage copy reflects strictly the game's owning launcher (Epic vs Steam vs Standalone)
        GamingZone.GameItem epicGame = new GamingZone.GameItem(
                "epic_fa4240", "Hogwarts Legacy", "epic",
                "com.epicgames.launcher://apps/ns%3Aitem%3Afa4240?action=launch&silent=true", false);
        GamingZone.GameItem steamGame = new GamingZone.GameItem(
                "steam_965680", "Boomerang Fu", "steam", "steam://rungameid/965680", false);
        GamingZone.GameItem standaloneGame = new GamingZone.GameItem(
                "exe_worms", "Worms Armageddon", "standalone", "C:\\Games\\Worms Armageddon\\WA.exe", false);

        GamingZone.LaunchModalStep epicStep = GamingZone.LaunchStage.PREPARING_STEAM.buildStep(epicGame, null);
        assertTrue(epicStep.headline.toLowerCase(java.util.Locale.ROOT).contains("epic"),
                "Epic game launch step headline mentions Epic: " + epicStep.headline);
        assertFalse(epicStep.headline.toLowerCase(java.util.Locale.ROOT).contains("steam"),
                "Epic game launch step headline never mentions Steam: " + epicStep.headline);

        GamingZone.LaunchModalStep steamStep = GamingZone.LaunchStage.PREPARING_STEAM.buildStep(steamGame, null);
        assertTrue(steamStep.headline.toLowerCase(java.util.Locale.ROOT).contains("steam"),
                "Steam game launch step headline mentions Steam: " + steamStep.headline);
        assertFalse(steamStep.headline.toLowerCase(java.util.Locale.ROOT).contains("epic"),
                "Steam game launch step headline never mentions Epic: " + steamStep.headline);

        GamingZone.LaunchModalStep standaloneStep = GamingZone.LaunchStage.PREPARING_STEAM.buildStep(standaloneGame, null);
        assertFalse(standaloneStep.headline.toLowerCase(java.util.Locale.ROOT).contains("steam"),
                "Standalone game launch step never mentions Steam: " + standaloneStep.headline);
        assertFalse(standaloneStep.headline.toLowerCase(java.util.Locale.ROOT).contains("epic"),
                "Standalone game launch step never mentions Epic: " + standaloneStep.headline);

        // 3. GamingScreenRenderer labels Epic, Steam, and standalone cards distinctly
        assertEquals("Epic Games", GamingScreenRenderer.formatGameSubtitle(epicGame, "Hidden", "Recently Played"),
                "Epic game subtitle displays 'Epic Games'");
        assertEquals("Steam", GamingScreenRenderer.formatGameSubtitle(steamGame, "Hidden", "Recently Played"),
                "Steam game subtitle displays 'Steam'");
        assertEquals("PC Game", GamingScreenRenderer.formatGameSubtitle(standaloneGame, "Hidden", "Recently Played"),
                "Standalone game subtitle displays 'PC Game'");
    }

    private static void testHeroicLauncherCopyBadgesAndSunshineOnDemandStandby() throws Exception {
        // 1. LaptopStatus exposes heroicRunning and sunshinePowerControlReady cleanly
        GamingZone.LaptopStatus st = new GamingZone.LaptopStatus(
                true, false, false, false, Collections.emptyList(),
                84, false, false, "uuid-h", "LAPTOP", 1920, 1080, 60, false, false, true, true);
        assertTrue(st.heroicRunning, "LaptopStatus preserves heroicRunning=true");
        assertTrue(st.sunshinePowerControlReady, "LaptopStatus preserves sunshinePowerControlReady=true");
        assertFalse(st.sunshineRunning, "LaptopStatus preserves sunshineRunning=false in standby");

        // 2. Heroic & Legendary games never show Steam copy during launch
        GamingZone.GameItem heroicGame = new GamingZone.GameItem(
                "heroic_legendary_hw",
                "Hogwarts Legacy",
                "heroic",
                "heroic://launch?appName=fa4240e57a3c46b39f169041b7811293&runner=legendary",
                false);
        GamingZone.GameItem legendaryGame = new GamingZone.GameItem(
                "legendary_ctrl",
                "Control",
                "legendary",
                "legendary://launch/control_app",
                false);

        GamingZone.LaunchModalStep heroicStep = GamingZone.LaunchStage.PREPARING_STEAM.buildStep(heroicGame, null);
        assertTrue(heroicStep.headline.toLowerCase(Locale.ROOT).contains("heroic"),
                "Heroic launch step mentions Heroic: " + heroicStep.headline);
        assertFalse(heroicStep.headline.toLowerCase(Locale.ROOT).contains("steam"),
                "Heroic launch step never mentions Steam: " + heroicStep.headline);

        GamingZone.LaunchModalStep legendaryStep = GamingZone.LaunchStage.PREPARING_STEAM.buildStep(legendaryGame, null);
        assertTrue(legendaryStep.headline.toLowerCase(Locale.ROOT).contains("epic"),
                "Legendary launch step mentions Epic: " + legendaryStep.headline);
        assertFalse(legendaryStep.headline.toLowerCase(Locale.ROOT).contains("steam"),
                "Legendary launch step never mentions Steam: " + legendaryStep.headline);

        // 3. Card subtitle labels Heroic vs Epic/Legendary distinctly
        assertEquals("Heroic Games", GamingScreenRenderer.formatGameSubtitle(heroicGame, "Hidden", "Recently Played"),
                "Heroic card subtitle displays 'Heroic Games'");
        assertEquals("Epic Games", GamingScreenRenderer.formatGameSubtitle(legendaryGame, "Hidden", "Recently Played"),
                "Legendary card subtitle displays 'Epic Games'");

        // 4. Standby Sunshine indicator shows reassuring Wireless (Standby) instead of error when power control ready
        assertEquals("PC Online · Wireless (Standby) · 84%",
                GamingZone.formatPcConnectionStatus(true, false, 84, false, false, false, true),
                "Stopped Sunshine with power control shows Wireless (Standby)");
        assertEquals("PC Online · Wireless · 84%",
                GamingZone.formatPcConnectionStatus(true, false, 84, false, false, true, true),
                "Running Sunshine shows normal Wireless status");
        assertEquals("PC Online · Cable · 84%",
                GamingZone.formatPcConnectionStatus(true, true, 84, false, false, false, true),
                "HDMI Cable mode ignores Sunshine standby state");

        // 5. ensureSunshineRunningOnPcSync calls POST /api/gaming/sunshine/start over loopback
        final AtomicReference<String> seenRequestLine = new AtomicReference<>("");
        final AtomicBoolean returnOk = new AtomicBoolean(true);
        try (ServerSocket srv = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            int port = srv.getLocalPort();
            Thread t = new Thread(() -> {
                for (int i = 0; i < 2; i++) {
                    try (Socket s = srv.accept()) {
                        InputStream in = s.getInputStream();
                        byte[] buf = new byte[2048];
                        int n = in.read(buf);
                        if (n > 0) {
                            String raw = new String(buf, 0, n, StandardCharsets.UTF_8);
                            int eol = raw.indexOf("\r\n");
                            seenRequestLine.set(eol > 0 ? raw.substring(0, eol) : raw);
                        }
                        String body = returnOk.get()
                                ? "{\"ok\":true,\"sunshineRunning\":true}"
                                : "{\"ok\":false,\"sunshineRunning\":false}";
                        String statusLine = returnOk.get() ? "HTTP/1.1 200 OK" : "HTTP/1.1 503 Service Unavailable";
                        String resp = statusLine + "\r\nContent-Type: application/json\r\nContent-Length: "
                                + body.length() + "\r\nConnection: close\r\n\r\n" + body;
                        OutputStream out = s.getOutputStream();
                        out.write(resp.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (Exception ignored) {
                    }
                }
            }, "test-sunshine-start-rpc");
            t.setDaemon(true);
            t.start();

            assertTrue(GamingZone.ensureSunshineRunningOnPcSync(null, "127.0.0.1", port),
                    "ensureSunshineRunningOnPcSync returns true on 200 OK");
            assertTrue(seenRequestLine.get().startsWith("POST /api/gaming/sunshine/start "),
                    "RPC hit POST /api/gaming/sunshine/start: " + seenRequestLine.get());

            returnOk.set(false);
            assertFalse(GamingZone.ensureSunshineRunningOnPcSync(null, "127.0.0.1", port),
                    "ensureSunshineRunningOnPcSync returns false on 503");
        }
    }

    private static void testStreamQualityModalStateSimplifiedThreeColumnsAndColors() throws Exception {
        // 1. 4K 60Hz TV + 1080p 60Hz PC + fast Wi-Fi (433 Mbps)
        ModalOverlayRenderer.StreamQualityModalState st = new ModalOverlayRenderer.StreamQualityModalState(
                "1080p", 60, 30, 3840, 2160, 60, 1920, 1080, 60, "Wi-Fi", 433);
        assertEquals(ModalOverlayRenderer.StreamQualityModalState.SLOT_RES, st.getFocusSlot(),
                "Modal starts focused on Resolution column");
        assertEquals("1080p", st.getResolution(), "Initial resolution preserved");
        assertEquals(60, st.getFps(), "Initial FPS preserved");
        assertEquals(30, st.getBitrateMbps(), "Initial bitrate preserved");

        // Recommended tones + short "why" hints
        assertEquals(ModalOverlayRenderer.StreamQualityModalState.TONE_RECOMMENDED, st.getResolutionTone(),
                "1080p on 1080p PC is Recommended");
        assertTrue(st.getResolutionHint().contains("PC"),
                "Resolution hint explains why: " + st.getResolutionHint());
        assertEquals(ModalOverlayRenderer.StreamQualityModalState.TONE_RECOMMENDED, st.getFpsTone(),
                "60 FPS on 60Hz TV+PC is Recommended");
        assertTrue(st.getFpsHint().contains("60Hz"),
                "FPS hint explains why: " + st.getFpsHint());
        assertEquals(ModalOverlayRenderer.StreamQualityModalState.TONE_RECOMMENDED, st.getBitrateTone(),
                "30 Mbps for 1080p60 is Recommended");

        // Up/Down steps values on focused column; Left/Right switches only across the 3 columns
        assertFalse(st.moveFocus(-1, 0), "Left from Resolution clamps cleanly at column 0");
        assertEquals(ModalOverlayRenderer.StreamQualityModalState.SLOT_RES, st.getFocusSlot(),
                "Focus remains on Resolution column");

        st.moveFocus(0, -1); // 1080p -> 1440p
        assertEquals("1440p", st.getResolution(), "Up steps resolution 1080p -> 1440p");
        st.moveFocus(0, -1); // 1440p -> 4k
        assertEquals("4k", st.getResolution(), "Up steps resolution 1440p -> 4k");
        st.moveFocus(0, -1); // clamped at 4k
        assertEquals("4k", st.getResolution(), "Up clamps cleanly at 4k");

        assertTrue(st.moveFocus(1, 0), "Right moves from Resolution to FPS");
        assertEquals(ModalOverlayRenderer.StreamQualityModalState.SLOT_FPS, st.getFocusSlot(),
                "Right moves to FPS column");
        st.moveFocus(0, -1); // 60 -> 90
        assertEquals(90, st.getFps(), "Up steps FPS 60 -> 90");
        assertEquals(ModalOverlayRenderer.StreamQualityModalState.TONE_CAUTION, st.getFpsTone(),
                "90 FPS on 60Hz TV uses CAUTION tone");
        st.moveFocus(0, 1); // 90 -> 60
        assertEquals(60, st.getFps(), "Down steps FPS 90 -> 60");

        assertTrue(st.moveFocus(1, 0), "Right moves from FPS to Bitrate");
        assertEquals(ModalOverlayRenderer.StreamQualityModalState.SLOT_BITRATE, st.getFocusSlot(),
                "Right moves to Bitrate column");
        st.moveFocus(0, -1); // 30 -> 40
        assertEquals(40, st.getBitrateMbps(), "Up steps bitrate 30 -> 40");
        st.moveFocus(0, -1); // 40 -> 60
        st.moveFocus(0, -1); // 60 -> 80
        assertEquals(80, st.getBitrateMbps(), "Bitrate jumps cleanly through preset ranges to 80 Mbps");
        assertEquals(ModalOverlayRenderer.StreamQualityModalState.TONE_RECOMMENDED, st.getBitrateTone(),
                "80 Mbps for 4K60 is Recommended");

        // No preset bar or bottom action buttons exist past Bitrate column
        assertFalse(st.moveFocus(1, 0), "Right past Bitrate clamps cleanly (no preset boxes or bottom buttons)");
        assertEquals(ModalOverlayRenderer.StreamQualityModalState.SLOT_BITRATE, st.getFocusSlot(),
                "Focus remains on Bitrate column");

        // 2. Dynamic adaptation on 4K 120Hz TV + 4K 120Hz PC + Ethernet (no hardcoded 60Hz/1080p!)
        ModalOverlayRenderer.StreamQualityModalState st120 = new ModalOverlayRenderer.StreamQualityModalState(
                "4k", 120, 120, 3840, 2160, 120, 3840, 2160, 120, "Ethernet", 0);
        assertEquals(ModalOverlayRenderer.StreamQualityModalState.TONE_RECOMMENDED, st120.getResolutionTone(),
                "4K on 4K PC + 4K TV is Recommended");
        assertEquals(ModalOverlayRenderer.StreamQualityModalState.TONE_RECOMMENDED, st120.getFpsTone(),
                "120 FPS on 120Hz TV + 120Hz PC is Recommended");

        // 3. Slow Wi-Fi (120 Mbps link) warns on excessive bitrate
        ModalOverlayRenderer.StreamQualityModalState stWifi = new ModalOverlayRenderer.StreamQualityModalState(
                "4k", 60, 100, 3840, 2160, 60, 1920, 1080, 60, "Wi-Fi", 120);
        assertEquals(ModalOverlayRenderer.StreamQualityModalState.TONE_CAUTION, stWifi.getBitrateTone(),
                "100 Mbps on 120 Mbps Wi-Fi link triggers CAUTION tone");
        assertTrue(stWifi.getBitrateHint().toLowerCase(java.util.Locale.ROOT).contains("wi-fi"),
                "Bitrate hint explains Wi-Fi ceiling: " + stWifi.getBitrateHint());

        // 4. Gamepad analog stick & hat axis direction mapping
        assertEquals(0, ModalOverlayRenderer.resolveGamepadAxisDirection(0.20f, 0f),
                "Small stick drift inside deadzone returns 0");
        assertEquals(-1, ModalOverlayRenderer.resolveGamepadAxisDirection(-0.85f, 0f),
                "Left stick negative axis maps to -1");
        assertEquals(1, ModalOverlayRenderer.resolveGamepadAxisDirection(0.85f, 0f),
                "Left stick positive axis maps to +1");
        assertEquals(-1, ModalOverlayRenderer.resolveGamepadAxisDirection(0f, -1.0f),
                "Gamepad D-pad hat negative axis maps to -1");

        // 5. Simplified Liquid-Glass modal omits preset boxes, Save/Cancel/More buttons, and key-legend hints
        File modalFile = new File("app/src/io/mytv/ModalOverlayRenderer.java");
        if (modalFile.exists()) {
            String modalSrc = new String(Files.readAllBytes(modalFile.toPath()), StandardCharsets.UTF_8);
            assertFalse(modalSrc.contains("Save & Apply"), "Stream Quality modal omits Save & Apply button");
            assertFalse(modalSrc.contains("More Settings"), "Stream Quality modal omits More Settings button");
            assertFalse(modalSrc.contains("Max Clarity"), "Stream Quality modal omits Max Clarity preset box");
            assertFalse(modalSrc.contains("Low Latency"), "Stream Quality modal omits Low Latency preset box");
            assertFalse(modalSrc.contains("OK / A  Save"), "Stream Quality modal omits OK/A/Back/B legend hint");
            assertFalse(modalSrc.contains("Left / Right  Switch setting"),
                    "Stream Quality modal omits Left/Right/Up/Down legend hint");
        }
    }

    private static void testSettingsStreamRowsColoredTonesAndNoQuickTuneModal() throws Exception {
        SettingsTree.StateSnapshot s = new SettingsTree.StateSnapshot();
        s.tvDisplayWidth = 3840;
        s.tvDisplayHeight = 2160;
        s.tvRefreshRateHz = 60;
        s.pcDisplayWidth = 1920;
        s.pcDisplayHeight = 1080;
        s.pcDisplayRefreshHz = 60;
        s.gamingResolution = "4k";
        s.gamingFps = 60;
        s.gamingBitrateMbps = 80;
        s.gamingFramePacing = "balanced";
        s.gamingCodec = "auto";
        s.networkType = "Wi-Fi";
        s.wifiLinkSpeedMbps = 433;

        SettingsTree.Node root = SettingsTree.buildRoot(s);
        SettingsTree.Node gaming = root.findById("gaming_settings");
        assertTrue(gaming != null, "gaming_settings present");
        assertEquals(5, gaming.children.size(), "Gaming keeps exact 5 clean top groups");
        SettingsTree.Node quality = gaming.findById("gaming_stream_quality");
        assertTrue(quality != null && !quality.children.isEmpty(), "gaming_stream_quality has children");

        // Quick Stream Tune modal row is removed from Settings
        assertTrue(quality.findById("gaming_stream_quick_tune") == null,
                "Quick Stream Tune option is removed from Settings screen");
        assertEquals("gaming_stream_res", quality.children.get(0).id,
                "Stream Resolution is child 0 of Stream Quality & Video");

        // Individual stream rows carry dynamic semantic colors & short hints directly
        SettingsTree.Node resRow = quality.findById("gaming_stream_res");
        SettingsTree.Node fpsRow = quality.findById("gaming_stream_fps");
        SettingsTree.Node bitrateRow = quality.findById("gaming_stream_bitrate");
        SettingsTree.Node pacingRow = quality.findById("gaming_stream_pacing");
        SettingsTree.Node codecRow = quality.findById("gaming_stream_codec");
        assertTrue(resRow != null && fpsRow != null && bitrateRow != null && pacingRow != null && codecRow != null,
                "All 5 stream rows exist inside Stream Quality & Video");
        assertEquals(SettingsTree.StatusTone.POSITIVE, fpsRow.statusTone,
                "60 FPS on 60Hz TV carries POSITIVE emerald tone on Stream Frame Rate row");
        assertEquals(SettingsTree.StatusTone.POSITIVE, bitrateRow.statusTone,
                "80 Mbps for 4K60 carries POSITIVE emerald tone on Video Bitrate row");
        assertEquals(SettingsTree.StatusTone.POSITIVE, pacingRow.statusTone,
                "Balanced VSYNC pacing carries POSITIVE emerald tone on Frame Pacing row");
        assertEquals(SettingsTree.StatusTone.POSITIVE, codecRow.statusTone,
                "Automatic codec carries POSITIVE emerald tone on Video Codec row");
        assertTrue(!resRow.hint.isEmpty() && !fpsRow.hint.isEmpty() && !bitrateRow.hint.isEmpty()
                        && !pacingRow.hint.isEmpty() && !codecRow.hint.isEmpty(),
                "Every stream setting group provides a concise color/recommendation hint");
        assertEquals(SettingsTree.StatusTone.POSITIVE, resRow.findById("gaming_set_res:1080p").statusTone,
                "1080p choice highlights POSITIVE for 1080p PC display");
        assertEquals(SettingsTree.StatusTone.POSITIVE, fpsRow.findById("gaming_set_fps:60").statusTone,
                "60 FPS choice highlights POSITIVE for 60Hz TV");
        assertEquals(SettingsTree.StatusTone.WARNING, fpsRow.findById("gaming_set_fps:120").statusTone,
                "120 FPS choice highlights WARNING for 60Hz TV");
        assertEquals(SettingsTree.StatusTone.POSITIVE, pacingRow.findById("gaming_set_pacing:balanced").statusTone,
                "Balanced Frame Pacing option highlights POSITIVE");
        assertEquals(SettingsTree.StatusTone.POSITIVE, codecRow.findById("gaming_set_codec:auto").statusTone,
                "Automatic Video Codec option highlights POSITIVE");

        // Exceeding TV Hz or Wi-Fi headroom surfaces WARNING tone directly on Stream Frame Rate & Video Bitrate rows
        SettingsTree.StateSnapshot sWarn = new SettingsTree.StateSnapshot();
        sWarn.tvDisplayWidth = 3840;
        sWarn.tvDisplayHeight = 2160;
        sWarn.tvRefreshRateHz = 60;
        sWarn.gamingResolution = "4k";
        sWarn.gamingFps = 120;
        sWarn.gamingBitrateMbps = 100;
        sWarn.networkType = "Wi-Fi";
        sWarn.wifiLinkSpeedMbps = 120;
        SettingsTree.Node warnRoot = SettingsTree.buildRoot(sWarn);
        SettingsTree.Node warnQuality = warnRoot.findById("gaming_stream_quality");
        SettingsTree.Node warnFps = warnRoot.findById("gaming_stream_fps");
        SettingsTree.Node warnBitrate = warnRoot.findById("gaming_stream_bitrate");
        assertEquals(SettingsTree.StatusTone.WARNING, warnQuality.statusTone,
                "Exceeding TV Hz / Wi-Fi surfaces WARNING tone on Stream Quality & Video group");
        assertEquals(SettingsTree.StatusTone.WARNING, warnFps.statusTone,
                "120 FPS on 60Hz TV surfaces WARNING tone directly on Stream Frame Rate row");
        assertEquals(SettingsTree.StatusTone.WARNING, warnBitrate.statusTone,
                "100 Mbps on 120 Mbps Wi-Fi surfaces WARNING tone directly on Video Bitrate row");
        assertTrue(warnFps.subtitle.contains("60Hz"),
                "Stream Frame Rate subtitle explains 60Hz mismatch: " + warnFps.subtitle);
        assertTrue(warnBitrate.subtitle.toLowerCase(Locale.ROOT).contains("wi-fi"),
                "Video Bitrate subtitle explains Wi-Fi headroom: " + warnBitrate.subtitle);

        // HomeView opens simplified auto-saving modal ONLY from Gaming screen & removes Quick Tune from Settings
        File hvFile = new File("app/src/io/mytv/HomeView.java");
        if (hvFile.exists()) {
            String hvSrc = new String(Files.readAllBytes(hvFile.toPath()), StandardCharsets.UTF_8);
            assertTrue(hvSrc.contains("openStreamQualityModal()"),
                    "Gaming screen Stream Quality card opens simplified 3-column Liquid-Glass modal");
            assertTrue(hvSrc.contains("persistStreamQualityModalValues()"),
                    "Stream Quality modal auto-saves immediately on Up/Down adjustments");
            assertFalse(hvSrc.contains("gaming_stream_quick_tune"),
                    "Settings screen no longer references gaming_stream_quick_tune");
            assertFalse(hvSrc.contains("openFullGamingSettingsFromModal"),
                    "Stream Quality modal no longer has More Settings jump");
            assertTrue(hvSrc.contains("KEYCODE_BUTTON_A") && hvSrc.contains("KEYCODE_BUTTON_B"),
                    "Stream Quality modal handles gaming controller A/B buttons");
            assertTrue(hvSrc.contains("KEYCODE_BUTTON_L1") && hvSrc.contains("KEYCODE_BUTTON_R1"),
                    "Stream Quality modal handles gaming controller L1/R1 column switching");
            assertTrue(hvSrc.contains("AXIS_HAT_X") && hvSrc.contains("AXIS_X"),
                    "HomeView handles gamepad left stick and D-pad hat motion events");
        }
    }

    private static String collectSubtreeText(SettingsTree.Node node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        if (node.title != null) sb.append(node.title).append(' ');
        if (node.subtitle != null) sb.append(node.subtitle).append(' ');
        if (node.hint != null) sb.append(node.hint).append(' ');
        if (node.children != null) {
            for (SettingsTree.Node child : node.children) {
                sb.append(collectSubtreeText(child));
            }
        }
        return sb.toString();
    }

    private static void deleteDir(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteDir(c);
        }
        f.delete();
    }
}
