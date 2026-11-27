package io.soartv;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit test suite for Track B: Zero-Knowledge Encrypted Cloud Key Backup Vault ({@link CloudVaultCrypto}),
 * Dual-Path Household/OAuth headers, explicit PostgREST column projections, {@code shared_reels} alignment,
 * and canonical {@code snake_case} DTO serialization with legacy {@code camelCase} read fallbacks.
 *
 * <p><b>Why:</b> Guarantees cryptographic confidentiality/integrity of user API keys in Supabase
 * ({@code public.encrypted_vault}) and prevents schema drift or {@code select=*} regressions across
 * {@link SupabaseClient} and {@link UserProfileManager}.
 *
 * <p><b>What:</b> Exercises PBKDF2-HMAC-SHA256 (120,000 iterations) + AES-256-GCM encryption/decryption,
 * tamper/wrong-passphrase authentication failures, PostgREST URL builders, and DTO JSON mappers.
 */
public final class TrackBSupabaseVaultTests {

    private static int passed = 0;

    private TrackBSupabaseVaultTests() {
    }

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

    private static void assertThrowsSecurityException(ThrowingRunnable action, String message) {
        try {
            action.run();
        } catch (GeneralSecurityException expected) {
            passed++;
            return;
        } catch (Exception unexpected) {
            throw new AssertionError(message + " | expected GeneralSecurityException, got " + unexpected.getClass().getName(), unexpected);
        }
        throw new AssertionError(message + " | expected GeneralSecurityException, but call succeeded");
    }

    private static void assertThrowsIllegalArgument(ThrowingRunnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            passed++;
            return;
        } catch (Exception unexpected) {
            throw new AssertionError(message + " | expected IllegalArgumentException, got " + unexpected.getClass().getName(), unexpected);
        }
        throw new AssertionError(message + " | expected IllegalArgumentException, but call succeeded");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Executes all Track B unit test cases and returns the total number of passing assertions.
     *
     * @return Total assertion count verified.
     * @throws Exception If any unexpected error occurs during execution.
     */
    public static int runAll() throws Exception {
        passed = 0;
        testVaultRoundTripAndZeroPlaintextLeakage();
        testVaultWrongPassphraseAndTamperFailures();
        testVaultEnvelopeJsonSerializationAndArrayParsing();
        testExplicitSelectProjectionsAndAuthHeaders();
        testSnakeCaseSerializationAndLegacyCamelCaseParsing();
        return passed;
    }

    /**
     * Command-line entry point for standalone JVM test execution against {@code android.jar}.
     *
     * @param args Unused CLI arguments.
     * @throws Exception If any assertion or cryptographic operation fails.
     */
    public static void main(String[] args) throws Exception {
        int total = runAll();
        System.out.println("ALL " + total + " TRACK B SUPABASE & VAULT ASSERTIONS PASSED.");
    }

    private static void testVaultRoundTripAndZeroPlaintextLeakage() throws Exception {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("tmdb_key", "tmdb_live_secret_value_998877");
        secrets.put("omdb_key", "omdb_val_1122");
        secrets.put("gemini_api_key", "AIzaSyTestFakeKeyForUnitTestingOnly12345");
        secrets.put("tuya_access_secret", "quote:\"slash:\\newline:\nunicode:\u05E9\u05DC\u05D5\u05DD");
        secrets.put("household_key", "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");

        char[] passphrase = "correct-horse-battery-staple-soartv!".toCharArray();

        CloudVaultCrypto.VaultEnvelope env1 = CloudVaultCrypto.encryptMap(secrets, passphrase);
        CloudVaultCrypto.VaultEnvelope env2 = CloudVaultCrypto.encryptMap(secrets, passphrase);

        assertEquals("primary", env1.vaultId, "Default vault_id is primary");
        assertEquals("PBKDF2-HMAC-SHA256", env1.kdfAlgorithm, "Default KDF label is PBKDF2-HMAC-SHA256");
        assertEquals(120_000, env1.kdfIterations, "Default PBKDF2 iterations is 120,000");
        assertEquals(secrets.size(), env1.keyCount, "keyCount matches input map size");

        byte[] salt1 = Base64.getDecoder().decode(env1.kdfSalt);
        byte[] iv1 = Base64.getDecoder().decode(env1.cipherIv);
        byte[] ct1 = Base64.getDecoder().decode(env1.ciphertext);
        assertEquals(16, salt1.length, "PBKDF2 salt is 16 random bytes");
        assertEquals(12, iv1.length, "AES-GCM IV is 12 random bytes");
        assertTrue(ct1.length > 32, "AES-GCM ciphertext includes encrypted JSON + 16-byte tag");

        // Semantic security: encrypting identical map twice yields distinct salt, IV, and ciphertext
        assertTrue(!env1.kdfSalt.equals(env2.kdfSalt), "Each encryption generates a fresh random salt");
        assertTrue(!env1.cipherIv.equals(env2.cipherIv), "Each encryption generates a fresh random 12-byte IV");
        assertTrue(!env1.ciphertext.equals(env2.ciphertext), "Each encryption produces distinct ciphertext");

        // Zero plaintext leakage in serialized envelope
        String serialized = env1.toJsonString();
        assertTrue(!serialized.contains("tmdb_live_secret_value_998877"), "Envelope JSON never leaks TMDB value");
        assertTrue(!serialized.contains("AIzaSyTestFakeKeyForUnitTestingOnly12345"), "Envelope JSON never leaks Gemini value");
        assertTrue(!serialized.contains("tmdb_key"), "Envelope JSON never leaks secret key names");

        Map<String, String> decrypted = CloudVaultCrypto.decryptMap(env1, passphrase);
        assertEquals(secrets, decrypted, "Decrypted map matches original map including escapes and Unicode");

        // Empty map round-trip
        CloudVaultCrypto.VaultEnvelope emptyEnv = CloudVaultCrypto.encryptMap(Collections.emptyMap(), passphrase);
        assertEquals(0, emptyEnv.keyCount, "Empty secrets map produces keyCount=0");
        Map<String, String> emptyDecrypted = CloudVaultCrypto.decryptMap(emptyEnv, passphrase);
        assertTrue(emptyDecrypted.isEmpty(), "Empty vault envelope decrypts back to empty map");
    }

    private static void testVaultWrongPassphraseAndTamperFailures() throws Exception {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("stremio_auth_key", "stremio_token_abc123xyz");
        char[] goodPass = "MyHouseholdMasterPass#2026".toCharArray();
        char[] wrongPass = "WrongHouseholdMasterPass#2026".toCharArray();

        CloudVaultCrypto.VaultEnvelope valid = CloudVaultCrypto.encryptMap(secrets, goodPass);

        // 1. Wrong passphrase fails GCM authentication tag check
        assertThrowsSecurityException(
                () -> CloudVaultCrypto.decryptMap(valid, wrongPass),
                "Wrong master passphrase must throw GeneralSecurityException"
        );

        // 2. Flip 1 bit in ciphertext body
        byte[] tamperedCt = Base64.getDecoder().decode(valid.ciphertext);
        tamperedCt[0] ^= 0x01;
        CloudVaultCrypto.VaultEnvelope badBodyEnv = new CloudVaultCrypto.VaultEnvelope(
                valid.vaultId, valid.kdfAlgorithm, valid.kdfSalt, valid.kdfIterations,
                valid.cipherIv, Base64.getEncoder().encodeToString(tamperedCt), valid.keyCount
        );
        assertThrowsSecurityException(
                () -> CloudVaultCrypto.decryptMap(badBodyEnv, goodPass),
                "Bit-flip in ciphertext body must fail AES-GCM authentication"
        );

        // 3. Flip 1 bit in trailing 128-bit GCM auth tag
        byte[] tamperedTag = Base64.getDecoder().decode(valid.ciphertext);
        tamperedTag[tamperedTag.length - 1] = (byte) (tamperedTag[tamperedTag.length - 1] ^ 0x80);
        CloudVaultCrypto.VaultEnvelope badTagEnv = new CloudVaultCrypto.VaultEnvelope(
                valid.vaultId, valid.kdfAlgorithm, valid.kdfSalt, valid.kdfIterations,
                valid.cipherIv, Base64.getEncoder().encodeToString(tamperedTag), valid.keyCount
        );
        assertThrowsSecurityException(
                () -> CloudVaultCrypto.decryptMap(badTagEnv, goodPass),
                "Bit-flip in 128-bit GCM auth tag must fail AES-GCM authentication"
        );

        // 4. Flip 1 bit in 12-byte IV
        byte[] tamperedIv = Base64.getDecoder().decode(valid.cipherIv);
        tamperedIv[3] ^= 0x04;
        CloudVaultCrypto.VaultEnvelope badIvEnv = new CloudVaultCrypto.VaultEnvelope(
                valid.vaultId, valid.kdfAlgorithm, valid.kdfSalt, valid.kdfIterations,
                Base64.getEncoder().encodeToString(tamperedIv), valid.ciphertext, valid.keyCount
        );
        assertThrowsSecurityException(
                () -> CloudVaultCrypto.decryptMap(badIvEnv, goodPass),
                "Tampered GCM IV must fail AES-GCM authentication"
        );

        // 5. Invalid IV length (8 bytes instead of 12)
        CloudVaultCrypto.VaultEnvelope shortIvEnv = new CloudVaultCrypto.VaultEnvelope(
                valid.vaultId, valid.kdfAlgorithm, valid.kdfSalt, valid.kdfIterations,
                Base64.getEncoder().encodeToString(new byte[8]), valid.ciphertext, valid.keyCount
        );
        assertThrowsSecurityException(
                () -> CloudVaultCrypto.decryptMap(shortIvEnv, goodPass),
                "Short GCM IV must be rejected"
        );

        // 6. Tampered salt
        byte[] tamperedSalt = Base64.getDecoder().decode(valid.kdfSalt);
        tamperedSalt[0] ^= 0x20;
        CloudVaultCrypto.VaultEnvelope badSaltEnv = new CloudVaultCrypto.VaultEnvelope(
                valid.vaultId, valid.kdfAlgorithm, Base64.getEncoder().encodeToString(tamperedSalt),
                valid.kdfIterations, valid.cipherIv, valid.ciphertext, valid.keyCount
        );
        assertThrowsSecurityException(
                () -> CloudVaultCrypto.decryptMap(badSaltEnv, goodPass),
                "Tampered PBKDF2 salt must fail decryption"
        );

        // 7. Downgraded KDF iterations (< 100,000)
        CloudVaultCrypto.VaultEnvelope weakIterEnv = new CloudVaultCrypto.VaultEnvelope(
                valid.vaultId, valid.kdfAlgorithm, valid.kdfSalt, 10_000,
                valid.cipherIv, valid.ciphertext, valid.keyCount
        );
        assertThrowsSecurityException(
                () -> CloudVaultCrypto.decryptMap(weakIterEnv, goodPass),
                "KDF iterations below 100,000 minimum must be rejected"
        );

        // 8. Modified KDF iterations (120,001)
        CloudVaultCrypto.VaultEnvelope alteredIterEnv = new CloudVaultCrypto.VaultEnvelope(
                valid.vaultId, valid.kdfAlgorithm, valid.kdfSalt, 120_001,
                valid.cipherIv, valid.ciphertext, valid.keyCount
        );
        assertThrowsSecurityException(
                () -> CloudVaultCrypto.decryptMap(alteredIterEnv, goodPass),
                "Modified KDF iteration count must fail decryption"
        );

        // 9. Malformed Base64
        CloudVaultCrypto.VaultEnvelope badB64Env = new CloudVaultCrypto.VaultEnvelope(
                valid.vaultId, valid.kdfAlgorithm, valid.kdfSalt, valid.kdfIterations,
                valid.cipherIv, "!!!not-valid-base64!!!", valid.keyCount
        );
        assertThrowsSecurityException(
                () -> CloudVaultCrypto.decryptMap(badB64Env, goodPass),
                "Malformed Base64 in envelope must throw GeneralSecurityException"
        );

        // 10. Empty passphrase rejected on encrypt
        assertThrowsIllegalArgument(
                () -> CloudVaultCrypto.encryptMap(secrets, new char[0]),
                "Empty passphrase must be rejected on encryptMap"
        );
    }

    private static void testVaultEnvelopeJsonSerializationAndArrayParsing() throws Exception {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("mdblist_key", "mdb_987654");
        secrets.put("sensibo_api_key", "sensibo_abc");
        char[] pass = "VaultPassphrase!99".toCharArray();

        CloudVaultCrypto.VaultEnvelope original = CloudVaultCrypto.encryptMap(secrets, pass);
        String jsonObject = original.toJsonString();

        assertTrue(jsonObject.contains("\"vault_id\":\"primary\""), "Envelope JSON uses snake_case vault_id");
        assertTrue(jsonObject.contains("\"kdf_algorithm\":\"PBKDF2-HMAC-SHA256\""), "Envelope JSON uses snake_case kdf_algorithm");
        assertTrue(jsonObject.contains("\"kdf_salt\":\""), "Envelope JSON uses snake_case kdf_salt");
        assertTrue(jsonObject.contains("\"kdf_iterations\":120000"), "Envelope JSON uses snake_case kdf_iterations");
        assertTrue(jsonObject.contains("\"cipher_iv\":\""), "Envelope JSON uses snake_case cipher_iv");
        assertTrue(jsonObject.contains("\"ciphertext\":\""), "Envelope JSON uses snake_case ciphertext");
        assertTrue(jsonObject.contains("\"key_count\":2"), "Envelope JSON uses snake_case key_count");

        CloudVaultCrypto.VaultEnvelope fromObj = CloudVaultCrypto.VaultEnvelope.fromJsonString(jsonObject);
        assertEquals(original.vaultId, fromObj.vaultId, "Parsed vaultId from object");
        assertEquals(original.kdfIterations, fromObj.kdfIterations, "Parsed kdfIterations from object");
        assertEquals(secrets, CloudVaultCrypto.decryptMap(fromObj, pass), "Decrypted from single JSON object");

        // PostgREST returns an array of rows on GET /rest/v1/encrypted_vault?...
        String postgrestArray = "[" + jsonObject + "]";
        CloudVaultCrypto.VaultEnvelope fromArr = CloudVaultCrypto.VaultEnvelope.fromJsonString(postgrestArray);
        assertEquals(secrets, CloudVaultCrypto.decryptMap(fromArr, pass), "Decrypted from PostgREST JSON array");
        assertEquals(null, CloudVaultCrypto.VaultEnvelope.fromJsonString("[]"), "Empty PostgREST array returns null");
    }

    private static void testExplicitSelectProjectionsAndAuthHeaders() {
        String userId = "824cf01b-96f3-4a6b-a554-621938e5a280";

        String profilesPath = SupabaseClient.buildProfilesQueryPath();
        String watchlistPath = SupabaseClient.buildWatchlistQueryPath(userId);
        String historyPath = SupabaseClient.buildWatchHistoryQueryPath(userId);
        String votesPath = SupabaseClient.buildVotesQueryPath(userId);
        String reelsAllPath = SupabaseClient.buildSharedReelsQueryPath(false, 24);
        String reelsUnwatchedPath = SupabaseClient.buildSharedReelsQueryPath(true, 12);
        String vaultPath = SupabaseClient.buildEncryptedVaultQueryPath("primary");

        String[] allPaths = {
                profilesPath, watchlistPath, historyPath, votesPath, reelsAllPath, reelsUnwatchedPath, vaultPath
        };
        for (String p : allPaths) {
            assertTrue(!p.contains("select=*"), "Zero select=* wildcard queries allowed: " + p);
            assertTrue(p.contains("select="), "Explicit select= column list present: " + p);
        }

        assertTrue(profilesPath.contains("slug") && profilesPath.contains("order=is_default.desc,created_at.asc"),
                "Profiles query projects explicit columns and orders by is_default DESC, created_at ASC");
        assertTrue(watchlistPath.contains("profile_id=eq." + userId) && watchlistPath.contains("order=created_at.desc"),
                "Watchlist query filters by profile_id and orders by created_at DESC");
        assertTrue(historyPath.contains("profile_id=eq." + userId) && historyPath.contains("order=watched_at.desc"),
                "Watch history query filters by profile_id and orders by watched_at DESC");
        assertTrue(votesPath.contains("select=media_key,vote") && votesPath.contains("profile_id=eq." + userId),
                "Votes query projects media_key,vote");
        assertTrue(reelsAllPath.contains("url,platform") && reelsAllPath.contains("watched") && !reelsAllPath.contains("watched=eq.false"),
                "All shared reels query projects canonical url, platform, watched");
        assertTrue(reelsUnwatchedPath.contains("watched=eq.false") && reelsUnwatchedPath.contains("order=created_at.desc&limit=12"),
                "Unwatched shared reels query uses composite index (watched, created_at DESC)");
        assertTrue(vaultPath.contains("vault_id=eq.primary") && vaultPath.contains("ciphertext"),
                "Encrypted vault query filters by vault_id=eq.primary");

        // Dual-Path Auth Header Verification
        Map<String, String> tvHeaders = SupabaseClient.buildAuthHeaders(
                "anon_jwt_token", "household_secret_64_chars_minimum_test_value", "", "return=minimal");
        assertEquals("anon_jwt_token", tvHeaders.get("apikey"), "TV headers include Supabase apikey");
        assertEquals("Bearer anon_jwt_token", tvHeaders.get("Authorization"), "TV headers default Bearer to anon key");
        assertEquals("household_secret_64_chars_minimum_test_value", tvHeaders.get("x-soartv-household-key"),
                "TV headers attach x-soartv-household-key for 24/7 non-expiring RLS access");
        assertEquals("return=minimal", tvHeaders.get("Prefer"), "Prefer header preserved");

        Map<String, String> oauthHeaders = SupabaseClient.buildAuthHeaders(
                "anon_jwt_token", "", "google_oauth_user_jwt_token", null);
        assertEquals("anon_jwt_token", oauthHeaders.get("apikey"), "Google OAuth headers still carry project apikey");
        assertEquals("Bearer google_oauth_user_jwt_token", oauthHeaders.get("Authorization"),
                "Google OAuth user JWT overrides Authorization Bearer token");
        assertEquals(false, oauthHeaders.containsKey("x-soartv-household-key"),
                "Empty household key is omitted cleanly when authenticating via Google OAuth JWT");
    }

    private static void testSnakeCaseSerializationAndLegacyCamelCaseParsing() {
        // 1. shared_reels canonical snake_case payload builder
        String reelPayload = SupabaseClient.buildSharedReelPayloadJson(
                "Alex", "  https://www.tiktok.com/@creator/video/778899  ", "");
        assertTrue(reelPayload.contains("\"sender_name\":\"Alex\""), "Shared reel payload uses snake_case sender_name");
        assertTrue(reelPayload.contains("\"url\":\"https://www.tiktok.com/@creator/video/778899\""),
                "Shared reel payload uses canonical url column");
        assertTrue(reelPayload.contains("\"platform\":\"tiktok\""), "Shared reel auto-detects tiktok platform");
        assertTrue(reelPayload.contains("\"watched\":false"), "Shared reel payload uses canonical watched boolean");
        assertTrue(!reelPayload.contains("\"videoUrl\"") && !reelPayload.contains("\"senderName\""),
                "Shared reel payload never emits camelCase keys");

        // 2. shared_reels parser handles BOTH canonical snake_case and legacy camelCase / video_url / is_watched
        String mixedReelsJson = "["
                + "{\"id\":\"r1\",\"sender_name\":\"Alex\",\"url\":\"https://youtu.be/abc\",\"platform\":\"youtube_shorts\",\"title\":\"Short 1\",\"thumbnail_url\":\"https://img/1.jpg\",\"watched\":true},"
                + "{\"id\":\"r2\",\"senderName\":\"Phone\",\"videoUrl\":\"https://www.instagram.com/reel/xyz/\",\"title\":\"Short 2\",\"thumbnailUrl\":\"https://img/2.jpg\",\"isWatched\":false},"
                + "{\"id\":\"r3\",\"sender\":\"Partner\",\"video_url\":\"https://www.tiktok.com/@a/video/1\",\"is_watched\":true}"
                + "]";
        List<SupabaseClient.SharedReel> reels = SupabaseClient.parseReels(mixedReelsJson);
        assertEquals(3, reels.size(), "Parsed all 3 canonical and legacy shared_reels entries");
        assertEquals("https://youtu.be/abc", reels.get(0).videoUrl, "Canonical url parsed");
        assertEquals(true, reels.get(0).watched, "Canonical watched=true parsed");
        assertEquals("Phone", reels.get(1).senderName, "Legacy camelCase senderName fallback parsed");
        assertEquals("https://www.instagram.com/reel/xyz/", reels.get(1).videoUrl, "Legacy camelCase videoUrl fallback parsed");
        assertEquals("instagram", reels.get(1).platform, "Missing platform inferred from URL");
        assertEquals("https://img/2.jpg", reels.get(1).thumbnailUrl, "Legacy camelCase thumbnailUrl fallback parsed");
        assertEquals(false, reels.get(1).watched, "Legacy camelCase isWatched fallback parsed");
        assertEquals("https://www.tiktok.com/@a/video/1", reels.get(2).videoUrl, "Legacy video_url fallback parsed");
        assertEquals(true, reels.get(2).watched, "Legacy is_watched fallback parsed");

        // 3. watchlist_items canonical snake_case payload + snake_case/camelCase parser
        String wlPayload = SupabaseClient.buildWatchlistPayloadJson(
                "824cf01b-96f3-4a6b-a554-621938e5a280",
                "tt11280740",
                "series",
                "Severance",
                "https://images.metahub.space/poster/medium/tt11280740/img",
                "https://images.metahub.space/background/large/tt11280740/img",
                "stremio",
                "stremio:///detail/series/tt11280740"
        );
        assertTrue(wlPayload.contains("\"profile_id\":\"824cf01b-96f3-4a6b-a554-621938e5a280\""), "Watchlist payload includes profile_id");
        assertTrue(wlPayload.contains("\"media_key\":\"tt11280740\""), "Watchlist payload includes snake_case media_key");
        assertTrue(wlPayload.contains("\"imdb_id\":\"tt11280740\""), "Watchlist payload includes imdb_id for tt* keys");
        assertTrue(wlPayload.contains("\"kind\":\"tv\""), "Watchlist payload normalizes series -> tv kind");
        assertTrue(wlPayload.contains("\"deep_link\":\"stremio:///detail/series/tt11280740\""), "Watchlist payload includes snake_case deep_link");

        String mixedWlJson = "["
                + wlPayload + ","
                + "{\"id\":\"w2\",\"mediaKey\":\"movie:603\",\"mediaType\":\"movie\",\"title\":\"The Matrix\",\"posterUrl\":\"https://p\",\"backdropUrl\":\"https://b\",\"sourceApp\":\"trakt\",\"deepLink\":\"stremio:///detail/movie/tt0133093\"}"
                + "]";
        List<SupabaseClient.WatchlistEntry> wlEntries = SupabaseClient.parseWatchlist(mixedWlJson);
        assertEquals(2, wlEntries.size(), "Parsed canonical and legacy camelCase watchlist items");
        assertEquals("tt11280740", wlEntries.get(0).mediaKey, "Canonical media_key parsed");
        assertEquals("tv", wlEntries.get(0).mediaType, "Canonical kind parsed");
        assertEquals("stremio:///detail/series/tt11280740", wlEntries.get(0).deepLink, "Canonical deep_link parsed");
        assertEquals("movie:603", wlEntries.get(1).mediaKey, "Legacy camelCase mediaKey parsed");
        assertEquals("trakt", wlEntries.get(1).sourceApp, "Legacy camelCase sourceApp parsed");
        assertEquals("stremio:///detail/movie/tt0133093", wlEntries.get(1).deepLink, "Legacy camelCase deepLink parsed");

        // 4. media_votes snake_case + camelCase parser & constraint filtering
        String votesJson = "[{\"media_key\":\"tv:119051\",\"vote\":1},{\"mediaKey\":\"movie:550\",\"vote\":-1},{\"media_key\":\"bad\",\"vote\":0}]";
        Map<String, Integer> votes = SupabaseClient.parseVotes(votesJson);
        assertEquals(2, votes.size(), "Only valid +1/-1 votes retained");
        assertEquals(Integer.valueOf(1), votes.get("tv:119051"), "Canonical snake_case vote parsed");
        assertEquals(Integer.valueOf(-1), votes.get("movie:550"), "Legacy camelCase mediaKey vote parsed");

        // 5. UserProfileManager.Profile canonical snake_case serialization + camelCase fallback
        UserProfileManager.Profile prof = new UserProfileManager.Profile(
                "824cf01b-96f3-4a6b-a554-621938e5a280", "alex", "Alex", "#38BDF8", "alex_tv", true);
        String dbJson = prof.toSupabasePayloadJson();
        assertTrue(dbJson.contains("\"slug\":\"alex\"") && dbJson.contains("\"display_name\":\"Alex\"")
                        && dbJson.contains("\"avatar_color\":\"#38BDF8\"")
                        && dbJson.contains("\"trakt_username\":\"alex_tv\"")
                        && dbJson.contains("\"is_default\":true"),
                "Profile Supabase payload uses strictly canonical snake_case columns");
        assertTrue(!dbJson.contains("\"displayName\"") && !dbJson.contains("\"traktUsername\""),
                "Profile Supabase payload omits camelCase fields");

        UserProfileManager.Profile parsedSnake = UserProfileManager.Profile.fromJsonString(dbJson);
        assertEquals("alex", parsedSnake.username, "Parsed snake_case slug");
        assertEquals("Alex", parsedSnake.displayName, "Parsed snake_case display_name");
        assertEquals("alex_tv", parsedSnake.traktUsername, "Parsed snake_case trakt_username");
        assertEquals(true, parsedSnake.isDefault, "Parsed snake_case is_default");

        String legacyCamelProfile = "{\"id\":\"11111111-2222-3333-4444-555555555555\",\"username\":\"dana\",\"displayName\":\"Dana\",\"avatarColor\":\"#EC4899\",\"traktUsername\":\"danatrakt\",\"isDefault\":false}";
        UserProfileManager.Profile parsedCamel = UserProfileManager.Profile.fromJsonString(legacyCamelProfile);
        assertEquals("dana", parsedCamel.username, "Fallback parsed legacy username");
        assertEquals("Dana", parsedCamel.displayName, "Fallback parsed legacy camelCase displayName");
        assertEquals("#EC4899", parsedCamel.avatarColor, "Fallback parsed legacy camelCase avatarColor");
        assertEquals("danatrakt", parsedCamel.traktUsername, "Fallback parsed legacy camelCase traktUsername");
        assertEquals(false, parsedCamel.isDefault, "Fallback parsed legacy camelCase isDefault");
    }
}
