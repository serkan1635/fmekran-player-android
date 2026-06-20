package com.fmekran.player;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * fmekran Player — Android Kiosk Activity
 *
 * MİMARİ NOT: Xibo for Android'in incelenmesinden çıkan en önemli ders,
 * cihazın CMS/sunucu ile bağlantısının görünür ve teşhis edilebilir
 * olması gerektiğidir. Bu sürüm, bağlantı sürecinin HER adımını
 * ekranda gösterir (Xibo'nun "Connect to your CMS" ekranına benzer
 * şekilde) böylece bir sorun olduğunda KULLANICI neyin yanlış
 * gittiğini görebilir — sessizce başka bir sayfaya düşmek yerine.
 */
public class PlayerActivity extends AppCompatActivity {

    private static final long RELOAD_DELAY_MS = 10000;
    private static final long SETTINGS_TRIGGER_WINDOW_MS = 3000;
    private static final int SETTINGS_TRIGGER_COUNT = 5;

    private WebView webView;
    private TextView statusText;
    private TextView debugText;
    private FrameLayout loadingOverlay;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int tapCount = 0;
    private long firstTapTime = 0;
    private String expectedHost = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        hideSystemUI();

        setContentView(R.layout.activity_player);

        webView        = findViewById(R.id.webView);
        statusText     = findViewById(R.id.statusText);
        debugText      = findViewById(R.id.debugText);
        loadingOverlay = findViewById(R.id.loadingOverlay);

        setupWebView();
        loadPlayer();

        loadingOverlay.setOnClickListener(v -> handleSecretTap());
        webView.setOnLongClickListener(v -> { handleSecretTap(); return true; });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // Üçüncü taraf çerezlere izin ver — login session'ı yanlışlıkla
        // tetiklenirse görünür olsun (gizli sorun gizlenmesin)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(Color.BLACK);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                checkForUnexpectedRedirect(url);
                // Her zaman WebView içinde kalsın, dışarı çıkmasın
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                showDebug("Yükleniyor: " + shortUrl(url));
                checkForUnexpectedRedirect(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                showStatus("Bağlandı");
                showDebug("Son URL: " + shortUrl(url));
                handler.postDelayed(() -> hideOverlay(), 1500);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showStatus("Bağlantı hatası");
                    showDebug("Hata: " + error.getDescription() + " (" + error.getErrorCode() + ")");
                    scheduleReload();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
    }

    /**
     * Xibo'nun "Connect to your CMS" mantığından ilham alındı: eğer
     * WebView beklenmedik şekilde farklı bir sayfaya (örn. /login,
     * /dashboard) yönlendirilirse, bunu ekranda GÖRÜNÜR şekilde
     * belirt. Bu, geçmişte yaşanan "player login ekranına düştü ama
     * neden olduğu anlaşılamadı" sorununu bir daha yaşatmaz.
     */
    private void checkForUnexpectedRedirect(String url) {
        if (url == null) return;
        Uri uri = Uri.parse(url);
        String path = uri.getPath() != null ? uri.getPath() : "";

        if (path.contains("/login") || path.equals("/dashboard") || path.equals("/")) {
            showStatus("⚠ Beklenmeyen yönlendirme");
            showDebug("Sunucu /player yerine '" + path + "' adresine yönlendirdi. " +
                       "Sunucu tarafında /player/index.html kontrol edilmeli.");
        }
    }

    private String shortUrl(String url) {
        if (url == null) return "";
        return url.length() > 60 ? url.substring(0, 60) + "..." : url;
    }

    private void loadPlayer() {
        SharedPreferences prefs = getSharedPreferences("fmekran_prefs", MODE_PRIVATE);
        String serverUrl  = prefs.getString("server_url", "http://34.78.135.59");
        String screenId   = prefs.getString("screen_id", "");
        String macAddress = getOrCreateMac(prefs);

        // Sondaki slash'leri temizle — çift slash hatalarını önler
        while (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }

        expectedHost = Uri.parse(serverUrl).getHost();

        String playerUrl = serverUrl + "/player/index.html?server=" + serverUrl +
            (screenId.isEmpty() ? "" : "&screen=" + screenId) +
            "&mac=" + macAddress;

        showStatus("Bağlanıyor...");
        showDebug("Hedef: " + playerUrl);
        showOverlay();

        if (!isNetworkAvailable()) {
            showStatus("İnternet bağlantısı yok");
            showDebug("Wi-Fi veya Ethernet bağlantısını kontrol edin.");
            scheduleReload();
            return;
        }

        webView.loadUrl(playerUrl);
    }

    private String getOrCreateMac(SharedPreferences prefs) {
        String mac = prefs.getString("mac_address", "");
        if (!mac.isEmpty()) return mac;

        String androidId = android.provider.Settings.Secure.getString(
            getContentResolver(), android.provider.Settings.Secure.ANDROID_ID
        );
        if (androidId == null) androidId = Build.SERIAL;

        String hex = String.format("%012X", Math.abs(androidId.hashCode()) % 0xFFFFFFFFFFFFL);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(":");
            sb.append(hex, i, i + 2);
        }
        String newMac = sb.toString();
        prefs.edit().putString("mac_address", newMac).apply();
        return newMac;
    }

    private void scheduleReload() {
        handler.postDelayed(() -> {
            if (isNetworkAvailable()) loadPlayer();
            else scheduleReload();
        }, RELOAD_DELAY_MS);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void showOverlay() { loadingOverlay.setVisibility(View.VISIBLE); }

    private void hideOverlay() {
        loadingOverlay.animate().alpha(0f).setDuration(500)
            .withEndAction(() -> loadingOverlay.setVisibility(View.GONE)).start();
    }

    private void showStatus(String msg) {
        runOnUiThread(() -> statusText.setText(msg));
    }

    private void showDebug(String msg) {
        runOnUiThread(() -> debugText.setText(msg));
    }

    private void handleSecretTap() {
        long now = System.currentTimeMillis();
        if (now - firstTapTime > SETTINGS_TRIGGER_WINDOW_MS) {
            tapCount = 0;
            firstTapTime = now;
        }
        tapCount++;
        if (tapCount >= SETTINGS_TRIGGER_COUNT) {
            tapCount = 0;
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    @Override
    public void onBackPressed() {
        // Geri tuşunu engelle (kiosk modu)
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_MENU) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        webView.destroy();
    }
}
