package io.mytv;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive Desktop JVM unit test suite for Track C Security & Performance Remediations:
 * <ul>
 *   <li>{@code SEC-01} / {@code SEC-02}: Stripped {@code Config} secrets + AES-256-GCM {@link Secrets} encryption & migration</li>
 *   <li>{@code SEC-05}: {@link RemoteAuthManager} QR / 6-digit / 1-click TV pairing, 30-day {@code GUEST} expiry, RBAC, lost-phone revoke, and Google Admin OAuth whitelist verification</li>
 *   <li>{@code SEC-06}–{@code SEC-08}: Strict CORS Origin allowlist, {@code /api/cast} scheme whitelist, and locked {@code owner/MyTV} OTA repo</li>
 *   <li>{@code PERF-11}: Broadcast + cached-IP Phase 1 WiZ discovery target list</li>
 * </ul>
 */
public final class TrackCRemoteAuthSecretsTests {

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

    public static void main(String[] args) throws Exception {
        testConfigStripsPlaintextSecrets();
        testSecretsAesGcmEncryptionMigrationAndVaultExport();
        testStremioAuthKeyEncryptedPersistence();
        testWizBroadcastFirstPhase1Targets();
        testRemotePairingRolesGuest30DayExpiryAndTvPoll();
        testPairingRateLimitsAndBruteForceProtection();
        testRoleBasedAccessControlAndLostPhoneRevocation();
        testGoogleAdminOAuthLoopbackVerificationAndWhitelist();
        testCorsCastSchemeAndUpdateRepoValidators();
        testTmdbKeyEncryptedVaultPersistenceAndFallback();
        System.out.println("ALL " + passed + " TRACK C SECURITY & AUTH ASSERTIONS PASSED.");
    }

    private static void testConfigStripsPlaintextSecrets() {
        assertEquals("", Config.STREMIO_PASSWORD, "SEC-01: Config.STREMIO_PASSWORD must never be baked into APK");
        assertEquals("", Config.STREMIO_AUTH_KEY, "SEC-01: Config.STREMIO_AUTH_KEY must never be baked into APK");
        assertEquals("", Config.GITHUB_TOKEN, "SEC-01: Config.GITHUB_TOKEN must never be baked into APK");
        assertEquals("", Config.SENSIBO_API_KEY, "SEC-01: Config.SENSIBO_API_KEY must never be baked into APK");
        assertEquals("", Config.TUYA_ACCESS_SECRET, "SEC-01: Config.TUYA_ACCESS_SECRET must never be baked into APK");
    }

    private static void testSecretsAesGcmEncryptionMigrationAndVaultExport() throws Exception {
        File dir1 = Files.createTempDirectory("mytv_secrets_test1_").toFile();
        File dir2 = Files.createTempDirectory("mytv_secrets_test2_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(dir1);

            String sampleSensibo = "sensibo_test_key_alpha_987654";
            String sampleAdb = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A_ADB_PRIVATE";
            assertTrue(Secrets.putSecret(null, Secrets.SENSIBO_API_KEY, sampleSensibo), "putSecret sensibo_api_key");
            assertTrue(Secrets.putSecret(null, Secrets.ADB_KEY, sampleAdb), "putSecret adb_key");

            File onDisk = new File(dir1, Secrets.SENSIBO_API_KEY);
            assertTrue(onDisk.isFile(), "Encrypted secret file created on disk");
            String rawText = new String(Files.readAllBytes(onDisk.toPath()), StandardCharsets.UTF_8);
            assertTrue(rawText.startsWith("ENC1:"), "SEC-02: Secret file uses ENC1: AES-GCM format");
            assertTrue(!rawText.contains(sampleSensibo), "SEC-02: Raw file bytes must never contain plaintext secret");

            assertEquals(sampleSensibo, Secrets.get(null, Secrets.SENSIBO_API_KEY), "AES-GCM round-trip decrypt");

            // Legacy plaintext auto-migration on first read
            File legacyPlain = new File(dir1, Secrets.OMDB_KEY);
            Files.write(legacyPlain.toPath(), "legacy_omdb_12345\n".getBytes(StandardCharsets.UTF_8));
            assertEquals("legacy_omdb_12345", Secrets.getSecret(null, Secrets.OMDB_KEY), "Reads legacy plaintext value");
            String migratedDisk = new String(Files.readAllBytes(legacyPlain.toPath()), StandardCharsets.UTF_8);
            assertTrue(migratedDisk.startsWith("ENC1:"), "Legacy plaintext file auto-migrated to ENC1: on first read");
            assertTrue(!migratedDisk.contains("legacy_omdb_12345"), "Migrated file no longer stores plaintext");
            assertEquals("legacy_omdb_12345", Secrets.getSecret(null, Secrets.OMDB_KEY), "Reads migrated ENC1: value");

            // Vault export excludes device-local adb_key and remote_paired_devices? Wait: verify adb_key is excluded
            Map<String, String> exported = Secrets.exportVaultSecrets(null);
            assertEquals(sampleSensibo, exported.get(Secrets.SENSIBO_API_KEY), "Vault export includes sensibo_api_key");
            assertEquals("legacy_omdb_12345", exported.get(Secrets.OMDB_KEY), "Vault export includes omdb_key");
            assertTrue(!exported.containsKey(Secrets.ADB_KEY), "Vault export never exports device-specific adb_key");

            // Import into clean directory
            Secrets.setJvmTestSecretsDir(dir2);
            Secrets.importVaultSecrets(null, exported);
            assertEquals(sampleSensibo, Secrets.getSecret(null, Secrets.SENSIBO_API_KEY), "Vault import round-trip");

            assertTrue(Secrets.deleteSecret(null, Secrets.SENSIBO_API_KEY), "deleteSecret deletes existing key");
            assertEquals("", Secrets.getSecret(null, Secrets.SENSIBO_API_KEY), "Deleted secret returns empty string");

            assertTrue(Secrets.isValidPlaintextPayload("abcd"), "4-char printable secret valid");
            assertTrue(!Secrets.isValidPlaintextPayload("abc"), "<4-char payload rejected");
            assertTrue(!Secrets.isValidPlaintextPayload("bad\nline"), "Multiline control char payload rejected");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteRecursively(dir1);
            deleteRecursively(dir2);
        }
    }

    private static void testStremioAuthKeyEncryptedPersistence() throws Exception {
        File dir = Files.createTempDirectory("mytv_stremio_sec_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(dir);
            StremioAccountClient.saveAuthKey(null, "stremio_live_auth_token_998877", "user@example.com");
            assertTrue(StremioAccountClient.isConfigured(null), "Stremio configured via encrypted Secrets");
            assertEquals("stremio_live_auth_token_998877", StremioAccountClient.getAuthKey(null), "Stremio authKey decrypted");

            File diskFile = new File(dir, Secrets.STREMIO_AUTH_KEY);
            String raw = new String(Files.readAllBytes(diskFile.toPath()), StandardCharsets.UTF_8);
            assertTrue(raw.startsWith("ENC1:") && !raw.contains("stremio_live_auth_token_998877"),
                    "Stremio auth_key stored encrypted at rest");

            StremioAccountClient.saveAuthKey(null, "", "");
            assertEquals("", StremioAccountClient.getAuthKey(null), "Empty saveAuthKey wipes Stremio authKey");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteRecursively(dir);
        }
    }

    private static void testWizBroadcastFirstPhase1Targets() {
        List<String> targets = SmartHomeManager.buildWizPhase1Targets(
                Arrays.asList("192.168.1.31", "192.168.1.32"),
                "192.168.1.32, 192.168.1.33",
                Collections.singletonList("192.168.1.")
        );
        assertEquals(
                Arrays.asList("192.168.1.31", "192.168.1.32", "192.168.1.33", "192.168.1.255", "255.255.255.255"),
                targets,
                "PERF-11: Phase 1 probes only cached + configured + subnet broadcast + 255.255.255.255"
        );
    }

    @SuppressWarnings("unchecked")
    private static void testRemotePairingRolesGuest30DayExpiryAndTvPoll() throws Exception {
        File dir = Files.createTempDirectory("mytv_remote_auth_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(dir);
            AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
            RemoteAuthManager auth = new RemoteAuthManager(null, nowMs::get);

            assertTrue(!auth.hasAnyAdminDevice(), "No admin before first bootstrap pair");

            // 1. First device pairs via 6-digit code -> bootstrapped as permanent ADMIN
            RemoteAuthManager.IssueResult req1 = auth.createPairRequest("Owner Pixel 9", "cid1", "192.168.1.20", "http://192.168.1.10:8484");
            assertEquals(200, req1.httpStatus, "First pair request HTTP 200");
            RemoteAuthManager.PendingPairRequest pending1 = auth.getActivePendingRequest();
            assertTrue(pending1 != null && pending1.sixDigitCode.length() == 6, "6-digit code generated");

            RemoteAuthManager.IssueResult ver1 = auth.verifyPairing(pending1.sixDigitCode, "", "Owner Pixel 9", "192.168.1.20");
            assertEquals(200, ver1.httpStatus, "6-digit bootstrap verify HTTP 200");
            Map<String, Object> body1 = (Map<String, Object>) RemoteAuthManager.MiniJson.parse(ver1.jsonBody);
            String adminToken = String.valueOf(body1.get("token"));
            assertEquals("ADMIN", body1.get("role"), "First paired device receives ADMIN role");
            assertEquals(0L, ((Number) body1.get("expiresAtMs")).longValue(), "ADMIN token never expires");
            assertTrue(auth.hasAnyAdminDevice(), "hasAnyAdminDevice true after bootstrap");

            // Verify raw bearer token is NEVER stored in Secrets (only SHA-256 hash inside encrypted payload)
            String decryptedVaultJson = Secrets.getSecret(null, Secrets.REMOTE_PAIRED_DEVICES);
            assertTrue(!decryptedVaultJson.contains(adminToken), "SEC-05: Only SHA-256(token) stored, never raw token");

            // 2. Second device pairs via QR secret -> defaults to 30-day GUEST
            auth.createPairRequest("Visitor iPhone", "cid2", "192.168.1.21", "http://192.168.1.10:8484");
            RemoteAuthManager.PendingPairRequest pending2 = auth.getActivePendingRequest();
            assertTrue(pending2.pairUrl.startsWith("http://192.168.1.10:8484/?pair="), "QR pairUrl format");

            RemoteAuthManager.IssueResult ver2 = auth.verifyPairing("", pending2.qrSecret, "Visitor iPhone", "192.168.1.21");
            assertEquals(200, ver2.httpStatus, "QR secret verify HTTP 200");
            Map<String, Object> body2 = (Map<String, Object>) RemoteAuthManager.MiniJson.parse(ver2.jsonBody);
            String guestToken = String.valueOf(body2.get("token"));
            assertEquals("GUEST", body2.get("role"), "Subsequent device defaults to 30-day GUEST");
            assertEquals(nowMs.get() + RemoteAuthManager.GUEST_TTL_MS, ((Number) body2.get("expiresAtMs")).longValue(),
                    "GUEST expiresAtMs is +30 days");

            // 3. Third device approved with 1 click on TV remote as HOUSEHOLD -> delivered once via poll
            RemoteAuthManager.IssueResult req3 = auth.createPairRequest("Partner Phone", "cid3", "192.168.1.22", "http://192.168.1.10:8484");
            Map<String, Object> req3Map = (Map<String, Object>) RemoteAuthManager.MiniJson.parse(req3.jsonBody);
            String req3Id = String.valueOf(req3Map.get("requestId"));

            RemoteAuthManager.IssueResult pollPending = auth.pollPairRequest(req3Id);
            assertTrue(pollPending.jsonBody.contains("\"status\":\"pending\""), "Poll reports pending before TV click");

            auth.approvePendingOnTv(RemoteAuthManager.Role.HOUSEHOLD);
            RemoteAuthManager.IssueResult pollApproved = auth.pollPairRequest(req3Id);
            Map<String, Object> pollMap = (Map<String, Object>) RemoteAuthManager.MiniJson.parse(pollApproved.jsonBody);
            assertEquals("approved", pollMap.get("status"), "Poll delivers approved status after TV click");
            assertEquals("HOUSEHOLD", pollMap.get("role"), "TV-approved role is HOUSEHOLD");
            String householdToken = String.valueOf(pollMap.get("token"));
            assertTrue(householdToken.length() >= 32, "Issued 256-bit household token");

            // Second poll for same requestId must NOT re-emit token
            RemoteAuthManager.IssueResult pollReplay = auth.pollPairRequest(req3Id);
            assertTrue(!pollReplay.jsonBody.contains(householdToken), "Transient token wiped after single delivery");

            // 4. Advance clock +29 days -> Guest still active; +31 days -> Guest expires automatically
            nowMs.addAndGet(29L * 86_400_000L);
            assertEquals(RemoteAuthManager.AuthStatus.AUTHENTICATED, auth.authenticate(guestToken).status,
                    "Guest remains valid at day 29");

            nowMs.addAndGet(2L * 86_400_000L);
            assertEquals(RemoteAuthManager.AuthStatus.GUEST_EXPIRED, auth.authenticate(guestToken).status,
                    "Guest automatically expires after 30 days");
            assertEquals(RemoteAuthManager.AuthStatus.AUTHENTICATED, auth.authenticate(householdToken).status,
                    "Household remains permanent after 31 days");
            assertEquals(RemoteAuthManager.AuthStatus.AUTHENTICATED, auth.authenticate(adminToken).status,
                    "Admin remains permanent after 31 days");

            // 4b. Same-phone Chrome -> Native APK handoff & camera QR finder-pattern verification
            auth.recordSameIpApkHandoff("192.168.1.55", adminToken, RemoteAuthManager.Role.ADMIN);
            String cfgWithHandoff = auth.buildAuthConfigJson("", "SmartTV-4K", "192.168.1.55");
            assertTrue(cfgWithHandoff.contains("\"handoffToken\":\"" + adminToken + "\""),
                    "Same-phone LAN IP receives handoffToken for seamless Chrome->APK transition");
            String cfgDifferentIp = auth.buildAuthConfigJson("", "SmartTV-4K", "192.168.1.99");
            assertTrue(!cfgDifferentIp.contains("handoffToken"),
                    "Different LAN IP never receives handoffToken");

            RemoteAuthManager.IssueResult reqCam = auth.createPairRequest("Camera Phone", "cid_cam_1", "192.168.1.60", "http://192.168.1.8:8484");
            Map<String, Object> reqCamMap = (Map<String, Object>) RemoteAuthManager.MiniJson.parse(reqCam.jsonBody);
            String camReqId = String.valueOf(reqCamMap.get("requestId"));
            RemoteAuthManager.IssueResult verCam = auth.verifyPairing("", "", camReqId, "cid_cam_1", true, "Camera Phone", "192.168.1.60");
            assertEquals(200, verCam.httpStatus, "Camera QR finder-pattern lock verifies active pairing request");

            // 5. Persistence across restart
            RemoteAuthManager reloaded = new RemoteAuthManager(null, nowMs::get);
            assertEquals(4, reloaded.listDevices().size(), "All 4 device records reload from encrypted Secrets");
            assertEquals(RemoteAuthManager.AuthStatus.AUTHENTICATED, reloaded.authenticate(adminToken).status,
                    "Admin token authenticates after reload");
            assertEquals(RemoteAuthManager.AuthStatus.GUEST_EXPIRED, reloaded.authenticate(guestToken).status,
                    "Expired guest remains expired after reload");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteRecursively(dir);
        }
    }

    private static void testPairingRateLimitsAndBruteForceProtection() throws Exception {
        File dir = Files.createTempDirectory("mytv_ratelimit_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(dir);
            AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
            RemoteAuthManager auth = new RemoteAuthManager(null, nowMs::get);

            for (int i = 0; i < 5; i++) {
                assertEquals(200,
                        auth.createPairRequest("Phone " + i, "", "192.168.1.77", "http://192.168.1.10:8484").httpStatus,
                        "Pair request #" + (i + 1) + " within 5/min allowed");
            }
            assertEquals(429,
                    auth.createPairRequest("Phone 6", "", "192.168.1.77", "http://192.168.1.10:8484").httpStatus,
                    "6th pair request within 1 minute rate-limited (HTTP 429)");

            // Brute-force code verification limit (8 failures/min per IP)
            for (int i = 0; i < 8; i++) {
                assertEquals(401, auth.verifyPairing("000000", "", "Attacker", "192.168.1.88").httpStatus,
                        "Wrong code attempt #" + (i + 1) + " returns 401");
            }
            assertEquals(429, auth.verifyPairing("000000", "", "Attacker", "192.168.1.88").httpStatus,
                    "9th wrong code attempt within 1 minute returns 429");

            nowMs.addAndGet(61_000L);
            assertEquals(200,
                    auth.createPairRequest("Phone After Cooldown", "", "192.168.1.77", "http://192.168.1.10:8484").httpStatus,
                    "Rate limit resets after 60s window");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteRecursively(dir);
        }
    }

    @SuppressWarnings("unchecked")
    private static void testRoleBasedAccessControlAndLostPhoneRevocation() throws Exception {
        // RBAC matrix checks
        assertTrue(!RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.GUEST, "/api/gaming", "POST", "unlock", true),
                "GUEST blocked from PC unlock");
        assertTrue(!RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.GUEST, "/api/gaming", "POST", "launch", false),
                "GUEST blocked from launching PC games");
        assertTrue(!RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.GUEST, "/api/update", "POST", "", false),
                "GUEST blocked from OTA update");
        assertTrue(!RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.GUEST, "/api/admin/devices", "GET", "", false),
                "GUEST blocked from admin devices");
        assertTrue(RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.GUEST, "/api/smarthome", "POST", "wiz_house_lights", false),
                "GUEST allowed basic room light toggle");
        assertTrue(!RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.GUEST, "/api/smarthome", "POST", "boiler_timer", false),
                "GUEST blocked from water boiler timer");

        assertTrue(RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.HOUSEHOLD, "/api/gaming", "POST", "launch", false),
                "HOUSEHOLD allowed game launch without PC PIN");
        assertTrue(!RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.HOUSEHOLD, "/api/gaming", "POST", "launch", true),
                "HOUSEHOLD blocked from supplying Windows PC unlock PIN");
        assertTrue(!RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.HOUSEHOLD, "/api/gaming", "POST", "unlock", true),
                "HOUSEHOLD blocked from PC unlock action");
        assertTrue(!RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.HOUSEHOLD, "/api/admin/devices", "POST", "revoke", false),
                "HOUSEHOLD blocked from revoking devices");

        assertTrue(RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.ADMIN, "/api/gaming", "POST", "unlock", true),
                "ADMIN allowed wireless PC unlock");
        assertTrue(RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.ADMIN, "/api/update", "POST", "", false),
                "ADMIN allowed OTA update");
        assertTrue(RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.ADMIN, "/api/admin/devices", "POST", "revoke", false),
                "ADMIN allowed device revoke");

        // Role promotion/demotion and lost-phone revoke
        File dir = Files.createTempDirectory("mytv_revoke_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(dir);
            AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
            RemoteAuthManager auth = new RemoteAuthManager(null, nowMs::get);

            RemoteAuthManager.PendingPairRequest inv = auth.createInvite(RemoteAuthManager.Role.GUEST, "http://192.168.1.10:8484");
            RemoteAuthManager.IssueResult paired = auth.verifyPairing(inv.sixDigitCode, "", "Lost Phone", "192.168.1.30");
            Map<String, Object> map = (Map<String, Object>) RemoteAuthManager.MiniJson.parse(paired.jsonBody);
            String token = String.valueOf(map.get("token"));
            String devId = String.valueOf(map.get("deviceId"));

            assertTrue(auth.setDeviceRole(devId, RemoteAuthManager.Role.HOUSEHOLD), "Promote GUEST -> HOUSEHOLD");
            assertEquals(RemoteAuthManager.Role.HOUSEHOLD, auth.authenticate(token).device.role, "Role updated to HOUSEHOLD");
            assertEquals(0L, auth.authenticate(token).device.expiresAtMs, "HOUSEHOLD clears 30d expiry");

            assertTrue(auth.setDeviceRole(devId, RemoteAuthManager.Role.GUEST), "Demote HOUSEHOLD -> GUEST");
            assertTrue(auth.authenticate(token).device.expiresAtMs > nowMs.get(), "Demoted GUEST receives 30d expiry");

            assertTrue(auth.revokeDevice(devId), "Revoke lost phone");
            assertEquals(RemoteAuthManager.AuthStatus.INVALID_TOKEN, auth.authenticate(token).status,
                    "Revoked phone token immediately rejected");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteRecursively(dir);
        }
    }

    @SuppressWarnings("unchecked")
    private static void testGoogleAdminOAuthLoopbackVerificationAndWhitelist() throws Exception {
        File dir = Files.createTempDirectory("mytv_google_oauth_").toFile();
        try (ServerSocket mockSupabase = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            Secrets.setJvmTestSecretsDir(dir);
            int port = mockSupabase.getLocalPort();
            Thread serverThread = new Thread(() -> {
                for (int i = 0; i < 3; i++) {
                    try (Socket client = mockSupabase.accept()) {
                        client.setSoTimeout(3000);
                        InputStream in = client.getInputStream();
                        OutputStream out = client.getOutputStream();
                        byte[] buf = new byte[4096];
                        int n = in.read(buf);
                        String req = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
                        String jsonBody;
                        int status = 200;
                        if (req.contains("Bearer stranger_google_jwt")) {
                            jsonBody = "{\"email\":\"stranger@gmail.com\",\"email_confirmed_at\":\"2026-01-01T00:00:00Z\",\"app_metadata\":{\"provider\":\"google\"}}";
                        } else if (req.contains("Bearer password_only_jwt")) {
                            jsonBody = "{\"email\":\"owner@example.com\",\"email_confirmed_at\":\"2026-01-01T00:00:00Z\",\"app_metadata\":{\"provider\":\"email\"}}";
                        } else if (req.contains("Bearer owner_google_jwt")) {
                            jsonBody = "{\"email\":\"owner@example.com\",\"email_confirmed_at\":\"2026-01-01T00:00:00Z\",\"app_metadata\":{\"provider\":\"google\"}}";
                        } else {
                            status = 401;
                            jsonBody = "{\"error\":\"invalid_jwt\"}";
                        }
                        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                        String hdr = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Unauthorized") + "\r\n"
                                + "Content-Type: application/json\r\n"
                                + "Content-Length: " + bodyBytes.length + "\r\n"
                                + "Connection: close\r\n\r\n";
                        out.write(hdr.getBytes(StandardCharsets.ISO_8859_1));
                        out.write(bodyBytes);
                        out.flush();
                    } catch (Exception ignored) {
                        break;
                    }
                }
            }, "mock-supabase-auth");
            serverThread.setDaemon(true);
            serverThread.start();

            RemoteAuthManager auth = new RemoteAuthManager(null, System::currentTimeMillis);
            auth.setTestAuthOverrides("http://127.0.0.1:" + port, "public_anon_key", "owner@example.com");

            // 1. Random stranger's valid Gmail MUST be rejected with 403 email_not_whitelisted
            RemoteAuthManager.IssueResult strangerRes = auth.authenticateWithGoogleToken("stranger_google_jwt", "Stranger Phone");
            assertEquals(403, strangerRes.httpStatus, "Random Gmail rejected with HTTP 403");
            assertTrue(strangerRes.jsonBody.contains("email_not_whitelisted"), "Returns email_not_whitelisted error");
            assertTrue(!auth.hasAnyAdminDevice(), "Stranger login never creates an ADMIN device");

            // 2. Non-Google provider rejected even if email matches
            RemoteAuthManager.IssueResult nonGoogleRes = auth.authenticateWithGoogleToken("password_only_jwt", "Spoof Attempt");
            assertEquals(401, nonGoogleRes.httpStatus, "Non-Google provider rejected with HTTP 401");

            // 3. Whitelisted owner Google OAuth succeeds and issues permanent offline LAN ADMIN token
            RemoteAuthManager.IssueResult ownerRes = auth.authenticateWithGoogleToken("owner_google_jwt", "Replacement Pixel");
            assertEquals(200, ownerRes.httpStatus, "Whitelisted Google owner receives HTTP 200");
            Map<String, Object> ownerMap = (Map<String, Object>) RemoteAuthManager.MiniJson.parse(ownerRes.jsonBody);
            assertEquals("ADMIN", ownerMap.get("role"), "Issued role is ADMIN");
            String adminToken = String.valueOf(ownerMap.get("token"));
            assertEquals(RemoteAuthManager.AuthStatus.AUTHENTICATED, auth.authenticate(adminToken).status,
                    "Issued Google Admin token authenticates locally offline");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteRecursively(dir);
        }
    }

    private static void testCorsCastSchemeAndUpdateRepoValidators() {
        // SEC-06: CORS Origin allowlist
        assertEquals("",
                RemoteControlServer.validateCorsOrigin(null, "http://192.168.1.27:8484", "192.168.1.27", 8484),
                "SEC-06: Empty Origin allowed for native non-CORS callers");
        assertEquals("http://remote.mytv.local",
                RemoteControlServer.validateCorsOrigin("http://remote.mytv.local", "http://192.168.1.27:8484", "192.168.1.27", 8484),
                "SEC-06: Hardened MyTV-Remote.apk origin allowed");
        assertEquals("http://192.168.1.27:8484",
                RemoteControlServer.validateCorsOrigin("http://192.168.1.27:8484", "http://192.168.1.27:8484", "192.168.1.27", 8484),
                "SEC-06: Same TV LAN origin allowed");
        assertEquals(RemoteControlServer.FORBIDDEN_ORIGIN_SENTINEL,
                RemoteControlServer.validateCorsOrigin("https://evil-driveby.example", "http://192.168.1.27:8484", "192.168.1.27", 8484),
                "SEC-06: Arbitrary external webpage Origin rejected");

        // SEC-08: /api/cast scheme allowlist
        assertTrue(RemoteControlServer.isAllowedCastUrl("https://www.youtube.com/shorts/abc123"), "HTTPS cast URL allowed");
        assertTrue(RemoteControlServer.isAllowedCastUrl("http://192.168.1.50:8096/stream.mp4"), "HTTP cast URL allowed");
        assertTrue(RemoteControlServer.isAllowedCastUrl("stremio:///detail/movie/tt0816692"), "stremio:// deep link allowed");
        assertTrue(!RemoteControlServer.isAllowedCastUrl("intent://scan/#Intent;scheme=zxing;end"), "SEC-08: intent: URI blocked");
        assertTrue(!RemoteControlServer.isAllowedCastUrl("file:///data/data/io.mytv/files/secrets/adb_key"), "SEC-08: file: URI blocked");
        assertTrue(!RemoteControlServer.isAllowedCastUrl("content://io.mytv.secrets/adb_key"), "SEC-08: content: URI blocked");
        assertTrue(!RemoteControlServer.isAllowedCastUrl("javascript:alert(1)"), "SEC-08: javascript: URI blocked");

        // SEC-07: /api/update locked GitHub repository
        String expectedAllowedRepo = Config.GITHUB_REPO != null && !Config.GITHUB_REPO.trim().isEmpty()
                ? Config.GITHUB_REPO.trim() : "owner/MyTV";
        assertTrue(RemoteControlServer.isAllowedUpdateRepo(""), "Empty repo defaults to configured repo");
        assertTrue(RemoteControlServer.isAllowedUpdateRepo(expectedAllowedRepo), "Configured/official repo allowed");
        assertTrue(!RemoteControlServer.isAllowedUpdateRepo("attacker/MaliciousMyTV"), "SEC-07: Third-party GitHub repo rejected");
        verifyTmdbVaultAssertions();
    }

    private static void verifyTmdbVaultAssertions() {
        // Executed via testTmdbKeyEncryptedVaultPersistenceAndFallback
    }

    private static void testTmdbKeyEncryptedVaultPersistenceAndFallback() throws Exception {
        File dir = Files.createTempDirectory("mytv_tmdb_vault_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(dir);
            assertEquals("", Recs.resolveEffectiveTmdbKey("", null, ""),
                    "Blank Prefs + blank Secrets + blank Config resolves to empty string");

            Recs.seedVaultTmdbKeyIfAbsent(null, "tmdb_build_seeded_key_0123456789ab");
            assertEquals("tmdb_build_seeded_key_0123456789ab", Secrets.getSecret(null, Secrets.TMDB_KEY),
                    "Non-empty Config.TMDB_KEY seeds encrypted Secrets.TMDB_KEY when vault is empty");
            assertEquals("tmdb_build_seeded_key_0123456789ab", Recs.resolveEffectiveTmdbKey("", null, ""),
                    "Seeded Secrets.TMDB_KEY survives a blank Config.TMDB_KEY APK install");

            Recs.seedVaultTmdbKeyIfAbsent(null, "different_build_key_999999999999");
            assertEquals("tmdb_build_seeded_key_0123456789ab", Secrets.getSecret(null, Secrets.TMDB_KEY),
                    "Existing Secrets.TMDB_KEY is preserved across seed calls");

            assertEquals("user_runtime_override_key_abcdef12",
                    Recs.resolveEffectiveTmdbKey("user_runtime_override_key_abcdef12", null, "config_fallback_key"),
                    "Runtime Prefs override takes top priority");

            Map<String, String> exported = Secrets.exportVaultSecrets(null);
            assertEquals("tmdb_build_seeded_key_0123456789ab", exported.get(Secrets.TMDB_KEY),
                    "Secrets.exportVaultSecrets includes tmdb_key");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
