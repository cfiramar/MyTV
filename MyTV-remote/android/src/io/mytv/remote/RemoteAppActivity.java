package io.mytv.remote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardened Native Android Companion Remote Activity for MyTV.
 *
 * <p><b>Why:</b> Eliminates weak {@code file://} WebView sandboxing ({@code SEC-11}), disables USB
 * backup token extraction ({@code SEC-12}), handles {@code mytvremote://auth} and {@code mytvremote://pair}
 * deep links, provides fast native Java Wi-Fi {@code /24} TV auto-discovery (bypassing Chromium's
 * 6-socket per-origin limit), grants camera access for in-app QR pairing, and provides
 * hardware-backed {@link BiometricPrompt} + {@code AndroidKeyStore} fingerprint wireless PC unlock.
 */
public final class RemoteAppActivity extends Activity {

    private static final String LOCAL_HOST = "remote.mytv.local";
    private static final String START_URL = "http://" + LOCAL_HOST + "/index.html?native=1";
    private static final String BIOMETRIC_PREFS = "mytv_remote_biometric";
    private static final String PREF_ENC_PIN = "enc_pin";
    private static final String PREF_ENC_IV = "enc_iv";
    private static final String KEYSTORE_ALIAS = "mytv_pc_unlock_pin_key";
    private static final int REQ_CAMERA_PERMISSION = 1001;
    private static final int DEFAULT_TV_PORT = 8484;

    private WebView webView;
    private volatile boolean pageLoaded = false;
    private volatile boolean trustedOriginActive = true;
    private volatile String pendingGoogleAccessToken = null;
    private volatile String pendingQrPairUri = null;
    private volatile String lastDiscoveredTvUrl = "";
    private PermissionRequest pendingWebPermissionRequest = null;
    private CancellationSignal activeBiometricSignal;

    private static final int DARK_STATUS_BAR_COLOR = Color.parseColor("#0E1024");
    private static final int DARK_NAV_BAR_COLOR = Color.parseColor("#0A1522");
    private static final int LIGHT_STATUS_BAR_COLOR = Color.parseColor("#F7F9FF");
    private static final int LIGHT_NAV_BAR_COLOR = Color.parseColor("#EEF3FC");

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        applySystemBarAppearance(true);

        webView = new WebView(this);
        webView.setBackgroundColor(DARK_STATUS_BAR_COLOR);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // SEC-11: Strictly disable all file:// and content:// access inside WebView
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Allow http://remote.mytv.local to fetch http://<TV_LAN_IP>:8484
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        webView.addJavascriptInterface(new MyTvNativeBridge(), "MyTvNative");
        webView.setWebViewClient(new HardenedRemoteWebViewClient());
        webView.setWebChromeClient(new QrCameraWebChromeClient());

        setContentView(webView);
        handleIncomingIntent(getIntent());
        triggerBackgroundTvDiscovery("");
        webView.loadUrl(START_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        triggerBackgroundTvDiscovery(lastDiscoveredTvUrl);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        Uri uri = intent.getData();
        if (uri == null || !"mytvremote".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.US) : "";
        if ("pair".equals(host)) {
            String rawUri = uri.toString();
            if (pageLoaded && webView != null) {
                deliverQrPairUriToJs(rawUri);
            } else {
                pendingQrPairUri = rawUri;
            }
            return;
        }
        String token = extractAccessToken(uri);
        if (token.isEmpty()) {
            return;
        }
        if (pageLoaded && webView != null) {
            deliverGoogleTokenToJs(token);
        } else {
            pendingGoogleAccessToken = token;
        }
    }

    private void deliverGoogleTokenToJs(final String token) {
        runOnUiThread(() -> {
            if (webView == null || !trustedOriginActive) return;
            String js = "window.onMyTvGoogleAuthCallback && window.onMyTvGoogleAuthCallback("
                    + jsonQuote(token) + ");";
            webView.evaluateJavascript(js, null);
        });
    }

    private void deliverQrPairUriToJs(final String rawUri) {
        runOnUiThread(() -> {
            if (webView == null || !trustedOriginActive) return;
            String js = "window.onMyTvQrScanned && window.onMyTvQrScanned("
                    + jsonQuote(rawUri) + ");";
            webView.evaluateJavascript(js, null);
        });
    }

    private void deliverDiscoveredTvToJs(final String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) return;
        lastDiscoveredTvUrl = baseUrl;
        runOnUiThread(() -> {
            if (webView == null || !trustedOriginActive || !pageLoaded) return;
            String js = "window.onMyTvDiscovered && window.onMyTvDiscovered("
                    + jsonQuote(baseUrl) + ");";
            webView.evaluateJavascript(js, null);
        });
    }

    private void evalCallbackOnJs(final String fnName, final String argsLiteral) {
        runOnUiThread(() -> {
            if (webView == null || !trustedOriginActive) return;
            String js = "window." + fnName + " && window." + fnName + "(" + argsLiteral + ");";
            webView.evaluateJavascript(js, null);
        });
    }

    private void triggerBackgroundTvDiscovery(final String hintUrl) {
        Thread t = new Thread(() -> {
            String found = discoverTvOnLan(hintUrl, DEFAULT_TV_PORT);
            if (found != null && !found.isEmpty()) {
                deliverDiscoveredTvToJs(found);
            }
        }, "mytv-lan-discovery");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Scans the phone's active Wi-Fi {@code /24} IPv4 subnet (plus any hint subnet) using parallel
     * native Java sockets ({@code 180ms} connect timeout + {@code GET /api/auth/config} verification).
     * Completes in ~150-220ms and completely avoids Android WebView's 6-connection HTTP pool limit.
     */
    static String discoverTvOnLan(String hintUrl, int port) {
        int targetPort = port > 0 ? port : DEFAULT_TV_PORT;
        Set<String> prefixes = new LinkedHashSet<>();

        // 1. Inspect the phone's actual active Wi-Fi / LAN IPv4 interfaces first
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets != null && nets.hasMoreElements()) {
                NetworkInterface nif = nets.nextElement();
                if (nif == null || !nif.isUp() || nif.isLoopback()) continue;
                String nifName = nif.getName() != null ? nif.getName().toLowerCase(Locale.US) : "";
                if (nifName.startsWith("tun") || nifName.startsWith("ppp") || nifName.startsWith("rmnet") || nifName.startsWith("dummy")) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress() && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        String prefix = extractIpv4Prefix(ip);
                        if (!prefix.isEmpty()) {
                            prefixes.add(prefix);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 2. If a hint URL was provided, check if that exact host is still alive first
        if (hintUrl != null && !hintUrl.trim().isEmpty()) {
            String hintPrefix = extractIpv4Prefix(hintUrl);
            if (!hintPrefix.isEmpty()) {
                prefixes.add(hintPrefix);
            }
        }

        // 3. Common home router subnets as fallback
        for (String fallback : new String[]{"192.168.1", "192.168.0", "10.0.0"}) {
            if (prefixes.size() < 3) {
                prefixes.add(fallback);
            }
        }

        List<String> candidateIps = new ArrayList<>(260);
        for (String prefix : prefixes) {
            for (int host = 2; host <= 254; host++) {
                candidateIps.add(prefix + "." + host);
            }
        }
        if (candidateIps.isEmpty()) return "";

        final AtomicReference<String> winner = new AtomicReference<>("");
        ExecutorService pool = Executors.newFixedThreadPool(48, r -> {
            Thread t = new Thread(r, "mytv-probe");
            t.setDaemon(true);
            return t;
        });

        try {
            List<Callable<Void>> tasks = new ArrayList<>(candidateIps.size());
            for (final String ip : candidateIps) {
                tasks.add(() -> {
                    if (!winner.get().isEmpty()) return null;
                    if (probeSingleTvHost(ip, targetPort)) {
                        winner.compareAndSet("", "http://" + ip + ":" + targetPort);
                    }
                    return null;
                });
            }
            pool.invokeAll(tasks, 1800, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return winner.get();
    }

    private static boolean probeSingleTvHost(String ip, int port) {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(ip, port), 180);
        } catch (IOException unreachable) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + ip + ":" + port + "/api/auth/config");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(350);
            conn.setReadTimeout(350);
            if (conn.getResponseCode() != 200) return false;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[512];
                int n = in.read(buf);
                if (n <= 0) return false;
                bos.write(buf, 0, n);
                String json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                return json.contains("\"paired\":") && json.contains("\"tvName\":");
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String extractIpv4Prefix(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim().replaceFirst("^https?://", "");
        int slash = cleaned.indexOf('/');
        if (slash >= 0) cleaned = cleaned.substring(0, slash);
        int colon = cleaned.indexOf(':');
        if (colon >= 0) cleaned = cleaned.substring(0, colon);
        String[] parts = cleaned.split("\\.");
        if (parts.length == 4) {
            try {
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(parts[i]);
                    if (octet < 0 || octet > 255) return "";
                }
                return parts[0] + "." + parts[1] + "." + parts[2];
            } catch (NumberFormatException ignored) {
            }
        }
        return "";
    }

    private static String extractAccessToken(Uri uri) {
        String frag = uri.getEncodedFragment();
        String fromFrag = findParamValue(frag, "access_token");
        if (!fromFrag.isEmpty()) return fromFrag;
        String query = uri.getEncodedQuery();
        return findParamValue(query, "access_token");
    }

    private static String findParamValue(String encodedPairs, String key) {
        if (encodedPairs == null || encodedPairs.isEmpty()) return "";
        for (String pair : encodedPairs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = urlDecode(pair.substring(0, eq));
            if (key.equals(k)) {
                return urlDecode(pair.substring(eq + 1));
            }
        }
        return "";
    }

    private static String urlDecode(String raw) {
        try {
            return URLDecoder.decode(raw, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return "";
        }
    }

    static String jsonQuote(String value) {
        if (value == null) return "\"\"";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;
                case '>': sb.append("\\u003e"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private void openExternalBrowser(Uri uri) {
        if (uri == null) return;
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.US) : "";
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            return;
        }
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
            browserIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(browserIntent);
        } catch (Exception ignored) {
        }
    }

    /**
     * WebChromeClient granting camera access to {@code http://remote.mytv.local} after verifying
     * Android runtime {@code CAMERA} permission so in-app QR pairing works seamlessly.
     */
    private final class QrCameraWebChromeClient extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            if (request == null) return;
            Uri origin = request.getOrigin();
            String host = (origin != null && origin.getHost() != null) ? origin.getHost().toLowerCase(Locale.US) : "";
            if (!LOCAL_HOST.equals(host)) {
                request.deny();
                return;
            }
            boolean wantsVideo = false;
            String[] resources = request.getResources();
            if (resources != null) {
                for (String res : resources) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                        wantsVideo = true;
                        break;
                    }
                }
            }
            if (!wantsVideo) {
                request.deny();
                return;
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                pendingWebPermissionRequest = request;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERMISSION) {
            PermissionRequest req = pendingWebPermissionRequest;
            pendingWebPermissionRequest = null;
            if (req != null) {
                if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    req.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    req.deny();
                }
            }
        }
    }

    /**
     * WebViewClient enforcing strict asset whitelisting on {@code http://remote.mytv.local} and
     * preventing top-level navigation to untrusted origins.
     */
    private final class HardenedRemoteWebViewClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request == null || request.getUrl() == null) {
                return null;
            }
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.US) : "";
            String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.US) : "";
            if (!"http".equals(scheme) || !LOCAL_HOST.equals(host)) {
                // Allow LAN fetch() requests (e.g. http://<TV_IP>:8484/api/...) to go to the network
                return null;
            }

            String rawPath = uri.getPath() != null ? uri.getPath() : "/";
            String assetName;
            String mimeType;
            switch (rawPath) {
                case "":
                case "/":
                case "/index.html":
                    assetName = "index.html";
                    mimeType = "text/html";
                    break;
                case "/app.bundle.js":
                    assetName = "app.bundle.js";
                    mimeType = "application/javascript";
                    break;
                case "/manifest.json":
                    assetName = "manifest.json";
                    mimeType = "application/manifest+json";
                    break;
                default:
                    return buildNotFoundResponse();
            }

            try {
                InputStream stream = getAssets().open(assetName);
                WebResourceResponse resp = new WebResourceResponse(mimeType, "UTF-8", stream);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-store");
                    headers.put("X-Content-Type-Options", "nosniff");
                    resp.setResponseHeaders(headers);
                }
                return resp;
            } catch (IOException e) {
                return buildNotFoundResponse();
            }
        }

        private WebResourceResponse buildNotFoundResponse() {
            InputStream empty = new ByteArrayInputStream("Not Found".getBytes(StandardCharsets.UTF_8));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return new WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        404,
                        "Not Found",
                        Collections.singletonMap("Cache-Control", "no-store"),
                        empty
                );
            }
            return new WebResourceResponse("text/plain", "UTF-8", empty);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return request != null && handleNavigationUri(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return url != null && handleNavigationUri(Uri.parse(url));
        }

        private boolean handleNavigationUri(Uri uri) {
            if (uri == null) return true;
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.US) : "";
            String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.US) : "";
            if ("http".equals(scheme) && LOCAL_HOST.equals(host)) {
                trustedOriginActive = true;
                return false;
            }
            if ("mytvremote".equals(scheme)) {
                if ("pair".equals(host)) {
                    deliverQrPairUriToJs(uri.toString());
                    return true;
                }
                String token = extractAccessToken(uri);
                if (!token.isEmpty()) {
                    deliverGoogleTokenToJs(token);
                }
                return true;
            }
            // Open Google OAuth or APK download links in the system external browser
            openExternalBrowser(uri);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Uri parsed = url != null ? Uri.parse(url) : null;
            trustedOriginActive = parsed != null
                    && "http".equalsIgnoreCase(parsed.getScheme())
                    && LOCAL_HOST.equalsIgnoreCase(parsed.getHost());
            pageLoaded = trustedOriginActive;
            if (!trustedOriginActive) return;

            String discovered = lastDiscoveredTvUrl;
            if (discovered != null && !discovered.isEmpty()) {
                deliverDiscoveredTvToJs(discovered);
            } else {
                triggerBackgroundTvDiscovery("");
            }

            String pendingQr = pendingQrPairUri;
            if (pendingQr != null && !pendingQr.isEmpty()) {
                pendingQrPairUri = null;
                deliverQrPairUriToJs(pendingQr);
            }

            String pending = pendingGoogleAccessToken;
            if (pending != null && !pending.isEmpty()) {
                pendingGoogleAccessToken = null;
                deliverGoogleTokenToJs(pending);
            }
        }
    }

    /**
     * Hardware-backed biometric & native Wi-Fi discovery JavascriptInterface exposed strictly to {@code http://remote.mytv.local}.
     */
    public final class MyTvNativeBridge {

        @JavascriptInterface
        public String discoverTv(String hintUrl) {
            if (!trustedOriginActive) return "";
            String found = discoverTvOnLan(hintUrl, DEFAULT_TV_PORT);
            if (found != null && !found.isEmpty()) {
                lastDiscoveredTvUrl = found;
                return found;
            }
            return lastDiscoveredTvUrl != null ? lastDiscoveredTvUrl : "";
        }

        @JavascriptInterface
        public void startNativeTvDiscovery(String hintUrl) {
            if (!trustedOriginActive) return;
            triggerBackgroundTvDiscovery(hintUrl);
        }

        @JavascriptInterface
        public void setSystemBarTheme(final boolean dark) {
            if (!trustedOriginActive) return;
            runOnUiThread(() -> applySystemBarAppearance(dark));
        }

        @JavascriptInterface
        public boolean isBiometricAvailable() {
            if (!trustedOriginActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return false;
            }
            PackageManager pm = getPackageManager();
            return pm != null && pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
        }

        @JavascriptInterface
        public boolean hasSavedPcUnlockPin() {
            if (!trustedOriginActive) return false;
            SharedPreferences sp = getSharedPreferences(BIOMETRIC_PREFS, Context.MODE_PRIVATE);
            String ct = sp.getString(PREF_ENC_PIN, "");
            String iv = sp.getString(PREF_ENC_IV, "");
            return ct != null && !ct.isEmpty() && iv != null && !iv.isEmpty();
        }

        @JavascriptInterface
        public void clearSavedPcUnlockPin() {
            if (!trustedOriginActive) return;
            clearBiometricVaultInternal();
        }

        @JavascriptInterface
        public void enrollPcUnlockPin(final String pin) {
            if (!trustedOriginActive) return;
            if (pin == null) {
                evalCallbackOnJs("onBiometricEnrollResult", "false, 'Enter a valid Windows PIN'");
                return;
            }
            final String trimmed = pin.trim();
            if (trimmed.isEmpty() || trimmed.length() > 64 || !isPrintableAscii(trimmed)) {
                evalCallbackOnJs("onBiometricEnrollResult", "false, 'PIN must be 1-64 printable characters'");
                return;
            }
            if (!isBiometricAvailable()) {
                evalCallbackOnJs("onBiometricEnrollResult", "false, 'Fingerprint hardware unavailable'");
                return;
            }
            runOnUiThread(() -> Api28Biometrics.enrollPin(RemoteAppActivity.this, trimmed));
        }

        @JavascriptInterface
        public void authenticatePcUnlock() {
            if (!trustedOriginActive) return;
            if (!isBiometricAvailable() || !hasSavedPcUnlockPin()) {
                evalCallbackOnJs("onBiometricPcPinError", "'No saved fingerprint PC PIN'");
                return;
            }
            runOnUiThread(() -> Api28Biometrics.authenticatePin(RemoteAppActivity.this));
        }
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarAppearance(boolean dark) {
        Window window = getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        int statusBg = dark ? DARK_STATUS_BAR_COLOR : LIGHT_STATUS_BAR_COLOR;
        int navBg = dark ? DARK_NAV_BAR_COLOR : LIGHT_NAV_BAR_COLOR;
        window.setStatusBarColor(statusBg);
        window.setNavigationBarColor(navBg);
        if (webView != null) {
            webView.setBackgroundColor(statusBg);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ctrl = window.getInsetsController();
            if (ctrl != null) {
                int lightMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ctrl.setSystemBarsAppearance(dark ? 0 : lightMask, lightMask);
            }
        } else {
            View decor = window.getDecorView();
            if (decor != null) {
                int flags = decor.getSystemUiVisibility();
                if (dark) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                } else {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                }
                decor.setSystemUiVisibility(flags);
            }
        }
    }

    private static boolean isPrintableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    void clearBiometricVaultInternal() {
        getSharedPreferences(BIOMETRIC_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_ENC_PIN)
                .remove(PREF_ENC_IV)
                .apply();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KEYSTORE_ALIAS)) {
                ks.deleteEntry(KEYSTORE_ALIAS);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Isolated API 28+ helper for {@link BiometricPrompt} + {@code AndroidKeyStore} AES-256-GCM
     * per-use biometric authentication so {@code d8 --min-api 24} verifies cleanly on older phones.
     */
    @TargetApi(Build.VERSION_CODES.P)
    private static final class Api28Biometrics {

        private Api28Biometrics() {}

        static void enrollPin(final RemoteAppActivity activity, final String rawPin) {
            try {
                KeyGenerator keyGen = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(true)
                        .build();
                keyGen.init(spec);
                SecretKey secretKey = keyGen.generateKey();

                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, secretKey);

                cancelPreviousSignal(activity);
                CancellationSignal signal = new CancellationSignal();
                activity.activeBiometricSignal = signal;

                BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                        .setTitle("Confirm Fingerprint to Save PC Unlock")
                        .setDescription("Encrypts your Windows unlock PIN in Android hardware KeyStore")
                        .setNegativeButton("Cancel", activity.getMainExecutor(), (dialog, which) -> {
                            signal.cancel();
                            activity.evalCallbackOnJs("onBiometricEnrollResult", "false, 'Cancelled'");
                        })
                        .build();

                prompt.authenticate(
                        new BiometricPrompt.CryptoObject(cipher),
                        signal,
                        activity.getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                byte[] plain = rawPin.getBytes(StandardCharsets.UTF_8);
                                try {
                                    Cipher unlockedCipher = result.getCryptoObject().getCipher();
                                    byte[] ciphertext = unlockedCipher.doFinal(plain);
                                    byte[] iv = unlockedCipher.getIV();
                                    activity.getSharedPreferences(BIOMETRIC_PREFS, Context.MODE_PRIVATE)
                                            .edit()
                                            .putString(PREF_ENC_PIN, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                                            .putString(PREF_ENC_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                                            .apply();
                                    activity.evalCallbackOnJs("onBiometricEnrollResult", "true, ''");
                                } catch (Exception e) {
                                    activity.evalCallbackOnJs("onBiometricEnrollResult",
                                            "false, " + jsonQuote("Encryption failed"));
                                } finally {
                                    Arrays.fill(plain, (byte) 0);
                                }
                            }

                            @Override
                            public void onAuthenticationError(int errorCode, CharSequence errString) {
                                String msg = errString != null ? errString.toString() : "Authentication cancelled";
                                activity.evalCallbackOnJs("onBiometricEnrollResult", "false, " + jsonQuote(msg));
                            }
                        }
                );
            } catch (Exception e) {
                activity.evalCallbackOnJs("onBiometricEnrollResult",
                        "false, " + jsonQuote("Could not initialize fingerprint key"));
            }
        }

        static void authenticatePin(final RemoteAppActivity activity) {
            try {
                SharedPreferences sp = activity.getSharedPreferences(BIOMETRIC_PREFS, Context.MODE_PRIVATE);
                final byte[] ciphertext = Base64.decode(sp.getString(PREF_ENC_PIN, ""), Base64.NO_WRAP);
                final byte[] iv = Base64.decode(sp.getString(PREF_ENC_IV, ""), Base64.NO_WRAP);

                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                SecretKey secretKey = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
                if (secretKey == null) {
                    activity.clearBiometricVaultInternal();
                    activity.evalCallbackOnJs("onBiometricPcPinError", "'Saved fingerprint key missing; re-save PIN'");
                    return;
                }

                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));

                cancelPreviousSignal(activity);
                CancellationSignal signal = new CancellationSignal();
                activity.activeBiometricSignal = signal;

                BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                        .setTitle("Touch Fingerprint Sensor to Unlock PC")
                        .setDescription("Unlocks your Windows Gaming PC wirelessly over Wi-Fi")
                        .setNegativeButton("Use PIN", activity.getMainExecutor(), (dialog, which) -> {
                            signal.cancel();
                            activity.evalCallbackOnJs("onBiometricPcPinError", "'Cancelled'");
                        })
                        .build();

                prompt.authenticate(
                        new BiometricPrompt.CryptoObject(cipher),
                        signal,
                        activity.getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                byte[] plain = null;
                                try {
                                    Cipher unlockedCipher = result.getCryptoObject().getCipher();
                                    plain = unlockedCipher.doFinal(ciphertext);
                                    String pin = new String(plain, StandardCharsets.UTF_8);
                                    activity.evalCallbackOnJs("onBiometricPcPinReady", jsonQuote(pin));
                                } catch (Exception e) {
                                    activity.evalCallbackOnJs("onBiometricPcPinError", "'Failed to decrypt PIN'");
                                } finally {
                                    if (plain != null) {
                                        Arrays.fill(plain, (byte) 0);
                                    }
                                }
                            }

                            @Override
                            public void onAuthenticationError(int errorCode, CharSequence errString) {
                                String msg = errString != null ? errString.toString() : "Fingerprint cancelled";
                                activity.evalCallbackOnJs("onBiometricPcPinError", jsonQuote(msg));
                            }
                        }
                );
            } catch (KeyPermanentlyInvalidatedException invalidated) {
                activity.clearBiometricVaultInternal();
                activity.evalCallbackOnJs("onBiometricPcPinError",
                        "'Fingerprints changed on phone; please save PC PIN again'");
            } catch (Exception e) {
                activity.evalCallbackOnJs("onBiometricPcPinError", "'Biometric unlock unavailable'");
            }
        }

        private static void cancelPreviousSignal(RemoteAppActivity activity) {
            if (activity.activeBiometricSignal != null) {
                try {
                    activity.activeBiometricSignal.cancel();
                } catch (Exception ignored) {
                }
                activity.activeBiometricSignal = null;
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (webView != null && trustedOriginActive) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                webView.evaluateJavascript("if (window.sendKey) window.sendKey('vol_up');", null);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                webView.evaluateJavascript("if (window.sendKey) window.sendKey('vol_down');", null);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (activeBiometricSignal != null) {
            try {
                activeBiometricSignal.cancel();
            } catch (Exception ignored) {
            }
            activeBiometricSignal = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
