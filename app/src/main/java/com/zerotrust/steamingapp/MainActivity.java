package com.zerotrust.steamingapp;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import com.zerotrust.steamingapp.adblock.AdBlocker;
import com.zerotrust.steamingapp.proxy.LocalDnsProxyServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "StreamingPrefs";
    private static final String KEY_LAST_DOMAIN = "last_domain";
    private static final String DEFAULT_URL = "https://streamingunity.win";

    private WebView miaWebView;
    private String dominioDinamico = "";
    private boolean isAndroidTV = false;

    private View mCustomView;
    private WebChromeClient.CustomViewCallback mCustomViewCallback;
    private int mOriginalOrientation;
    private int mOriginalSystemUiVisibility;
    private boolean isFullscreen = false;

    // Variabili per il cursore su TV
    private ImageView immagineCursore;
    private float cursorX = 500f;
    private float cursorY = 500f;
    private final int step = 30; // Velocità del cursore

    // Proxy DoH locale e gestione ExoPlayer
    private LocalDnsProxyServer proxyServer;
    private long lastPlayerLaunchTime = 0;
    private final String fintoPC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private ActivityResultLauncher<Intent> playerLauncher;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Registrazione del launcher per il Player Nativo
        playerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    miaWebView.onResume();
                    long seconds = 0;
                    long durationSeconds = 0;
                    int titleId = 0;
                    int episodeId = 0;

                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        seconds = data.getLongExtra(PlayerActivity.RESULT_POSITION_SECONDS, 0);
                        durationSeconds = data.getLongExtra(PlayerActivity.RESULT_DURATION_SECONDS, 0);
                        titleId = data.getIntExtra(PlayerActivity.EXTRA_TITLE_ID, 0);
                        episodeId = data.getIntExtra(PlayerActivity.EXTRA_EPISODE_ID, 0);
                    }

                    int minute = (int) Math.round(seconds / 60.0);
                    int durationMinute = (int) Math.round(durationSeconds / 60.0);
                    if (durationMinute <= 0 && minute > 0) durationMinute = minute + 1;

                    Log.i(TAG, "Player chiuso: " + seconds + "s (minuto " + minute + " di " + durationMinute + " min, title: " + titleId + ", ep: " + episodeId + ")");

                    if (seconds > 0) {
                        String cacheKey = "pos_" + (titleId > 0 ? titleId + "_" + episodeId : "last");
                        getSharedPreferences("video_progress", MODE_PRIVATE).edit().putLong(cacheKey, seconds * 1000L).apply();
                    }

                    // Sincronizzazione progresso con StreamingCommunity ed uscita pulita dalla pagina del player per evitare errore 232600
                    final int fMinute = minute;
                    final int fDuration = durationMinute;
                    final int fTitleId = titleId;
                    final int fEpId = episodeId;

                    String syncAndBackJs =
                            "(function() {" +
                            "  var min = " + fMinute + ";" +
                            "  var dur = " + fDuration + ";" +
                            "  var tId = " + fTitleId + ";" +
                            "  var eId = " + fEpId + ";" +
                            "  try { window.postMessage('WATCHTIME_UPDATE:' + min + ':' + dur, '*'); } catch(e) {}" +
                            "  try {" +
                            "    if (tId > 0) {" +
                            "      var xsrf = decodeURIComponent((document.cookie.match(/XSRF-TOKEN=([^;]+)/) || [])[1] || '');" +
                            "      var payload = { title_id: tId, minute: min, duration: dur };" +
                            "      if (eId > 0) payload.episode_id = eId;" +
                            "      fetch('/api/watchlist/add', {" +
                            "        method: 'POST'," +
                            "        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf }," +
                            "        body: JSON.stringify(payload)" +
                            "      }).catch(function(err){ console.error(err); });" +
                            "    }" +
                            "  } catch(e) {}" +
                            "  setTimeout(function() {" +
                            "    try { window.postMessage('REFERER_BACK', '*'); } catch(e) {}" +
                            "    setTimeout(function() {" +
                            "      if (window.location.href.indexOf('/watch/') !== -1) {" +
                            "        if (window.history.length > 1) { window.history.back(); } else { window.location.href = '/it'; }" +
                            "      }" +
                            "    }, 250);" +
                            "  }, 250);" +
                            "})();";

                    miaWebView.evaluateJavascript(syncAndBackJs, null);
                }
        );

        // Avvio del Micro-Proxy DoH locale per aggirare il blocco DNS 127.0.0.1
        initDohProxy();

        android.app.UiModeManager uiModeManager = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        isAndroidTV = (uiModeManager.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION);

        miaWebView = findViewById(R.id.miaWebView);
        immagineCursore = findViewById(R.id.immagineCursore);

        if (isAndroidTV) {
            immagineCursore.setVisibility(View.VISIBLE);
            miaWebView.setFocusable(true);
            miaWebView.setFocusableInTouchMode(true);
            miaWebView.requestFocus();
        } else {
            immagineCursore.setVisibility(View.GONE);
            miaWebView.setFocusableInTouchMode(true);
        }

        // Impostazioni WebView
        miaWebView.getSettings().setJavaScriptEnabled(true);
        miaWebView.getSettings().setSupportMultipleWindows(true);
        miaWebView.getSettings().setDomStorageEnabled(true);
        miaWebView.getSettings().setDatabaseEnabled(true);
        miaWebView.getSettings().setUserAgentString(fintoPC);
        WebView.setWebContentsDebuggingEnabled(true);

        // Gestione Popup e Fullscreen Web (WebChromeClient)
        miaWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                Log.i(TAG, "Popup silenziato e bloccato!");
                return false;
            }

            @Override
            public void onShowCustomView(View paramView, CustomViewCallback paramCustomViewCallback) {
                if (mCustomView != null) {
                    onHideCustomView();
                    return;
                }
                mCustomView = paramView;
                mOriginalSystemUiVisibility = getWindow().getDecorView().getSystemUiVisibility();
                mOriginalOrientation = getRequestedOrientation();
                mCustomViewCallback = paramCustomViewCallback;
                ((android.widget.FrameLayout) getWindow().getDecorView()).addView(mCustomView, new android.widget.FrameLayout.LayoutParams(-1, -1));
                getWindow().getDecorView().setSystemUiVisibility(3846 | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

                mCustomView.setFocusable(true);
                mCustomView.setFocusableInTouchMode(true);
                mCustomView.requestFocus();

                isFullscreen = true;
                if (isAndroidTV) immagineCursore.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (mCustomView == null) return;
                ((android.widget.FrameLayout) getWindow().getDecorView()).removeView(mCustomView);
                mCustomView = null;
                getWindow().getDecorView().setSystemUiVisibility(mOriginalSystemUiVisibility);
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

                if (mCustomViewCallback != null) {
                    mCustomViewCallback.onCustomViewHidden();
                    mCustomViewCallback = null;
                }

                isFullscreen = false;
                if (isAndroidTV) immagineCursore.setVisibility(View.VISIBLE);
            }
        });

        // Gestione Navigazione, Ad-Block di rete e Intercettazione Stream Nativo (WebViewClient)
        miaWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String host = request.getUrl().getHost();
                if (host != null && (host.contains("streamingunity") || host.contains("streamingcommunity"))) {
                    dominioDinamico = host;
                    return false;
                }
                if ((!dominioDinamico.isEmpty() && url.contains(dominioDinamico))
                        || url.contains("vixcloud.co")
                        || url.contains("thaculse.net")
                        || url.contains("streamingunity")
                        || url.contains("streamingcommunity")) {
                    return false; // Permetti navigazione legittima
                } else {
                    Log.i(TAG, "Bloccato redirect ad: " + url);
                    return true; // Blocca redirect esterno
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String host = request.getUrl().getHost();

                // 1. Intercettazione stream video (.m3u8 o playlist HLS VixCloud)
                if (url.contains("vixcloud") || url.contains(".m3u8") || url.contains("playlist")) {
                    Log.i(TAG, "Rilevata potenziale risorsa stream: " + url);
                }

                if (url.contains(".m3u8") || (url.contains("vixcloud.co") && url.contains("playlist"))) {
                    long now = System.currentTimeMillis();
                    if (now - lastPlayerLaunchTime > 3000) {
                        lastPlayerLaunchTime = now;
                        String referer = request.getRequestHeaders().get("Referer");
                        if (referer == null || referer.isEmpty()) {
                            referer = "https://vixcloud.co/";
                        }
                        final String finalReferer = referer;
                        Log.i(TAG, "Lancio ExoPlayer per lo stream catturato: " + url + " con Referer: " + finalReferer);
                        runOnUiThread(() -> launchPlayer(url, finalReferer));
                    }
                    // Silenzia la decodifica audio/video dentro la WebView per evitare audio doppio
                    return new WebResourceResponse("application/vnd.apple.mpegurl", "utf-8", new ByteArrayInputStream(new byte[0]));
                }

                // 2. Filtro AdBlocker di rete per bloccare script e banner alla radice
                if (AdBlocker.isAd(url, host)) {
                    Log.d(TAG, "AdBlocker: bloccata risorsa " + url);
                    return AdBlocker.createEmptyResponse();
                }

                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
            }
        });

        // Tasto Indietro (Back)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isFullscreen) {
                    if (miaWebView.getWebChromeClient() != null && mCustomView != null) {
                        // Chiudi il custom view
                        ((android.widget.FrameLayout) getWindow().getDecorView()).removeView(mCustomView);
                        mCustomView = null;
                        if (mCustomViewCallback != null) {
                            mCustomViewCallback.onCustomViewHidden();
                            mCustomViewCallback = null;
                        }
                        isFullscreen = false;
                        if (isAndroidTV) immagineCursore.setVisibility(View.VISIBLE);
                        return;
                    }
                }
                if (miaWebView.canGoBack()) {
                    miaWebView.goBack();
                } else {
                    finish();
                }
            }
        });

        recuperaLinkDaTelegraph();
    }

    private void initDohProxy() {
        try {
            proxyServer = new LocalDnsProxyServer();
            proxyServer.start();
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyConfig proxyConfig = new ProxyConfig.Builder()
                        .addProxyRule("127.0.0.1:" + proxyServer.getPort())
                        .addDirect()
                        .build();
                ProxyController.getInstance().setProxyOverride(proxyConfig, Runnable::run, () -> {
                    Log.i(TAG, "Proxy DoH locale agganciato alla WebView sulla porta " + proxyServer.getPort());
                });
            } else {
                Log.w(TAG, "PROXY_OVERRIDE non supportato su questo dispositivo WebView.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Errore avvio proxy DoH", e);
        }
    }

    private void launchPlayer(String streamUrl, String referer) {
        Log.i(TAG, "Intercettato stream: " + streamUrl + " - estrazione metadati ed avvio Player Nativo...");
        miaWebView.onPause();

        // 1. Estrazione metadati (titolo, episodio, minuto salvato, id) e reset degli iframe per evitare errori in WebView
        String getMetaJs =
                "(function() {" +
                "  var meta = { minute: 0, title: '', episode: '', titleId: 0, episodeId: 0 };" +
                "  try {" +
                "    var app = document.querySelector('#app');" +
                "    var page = app && app.__vue_app__ ? app.__vue_app__.config.globalProperties.$page : null;" +
                "    if (page && page.props) {" +
                "      if (typeof page.props.minute === 'number') {" +
                "        meta.minute = page.props.minute;" +
                "      } else if (page.props.title && page.props.title.user_watchlist && typeof page.props.title.user_watchlist.minute === 'number') {" +
                "        meta.minute = page.props.title.user_watchlist.minute;" +
                "      }" +
                "      if (page.props.title) {" +
                "        meta.title = page.props.title.name || '';" +
                "        meta.titleId = page.props.title.id || 0;" +
                "      }" +
                "      if (page.props.episode) {" +
                "        var sNum = (page.props.episode.season && page.props.episode.season.number) ? page.props.episode.season.number : 1;" +
                "        meta.episode = 'S' + sNum + ':E' + page.props.episode.number + (page.props.episode.name ? ' ' + page.props.episode.name : '');" +
                "        meta.episodeId = page.props.episode.id || 0;" +
                "      }" +
                "    }" +
                "  } catch(e) {}" +
                "  try {" +
                "    document.querySelectorAll('iframe').forEach(function(f){ f.src = 'about:blank'; });" +
                "  } catch(e) {}" +
                "  return JSON.stringify(meta);" +
                "})();";

        miaWebView.evaluateJavascript(getMetaJs, value -> {
            long startPosMs = 0;
            String displayTitle = "";
            int titleId = 0;
            int episodeId = 0;

            if (value != null && !value.equals("null") && !value.isEmpty()) {
                try {
                    String unescaped = value;
                    if (unescaped.startsWith("\"") && unescaped.endsWith("\"")) {
                        unescaped = new org.json.JSONTokener(unescaped).nextValue().toString();
                    }
                    org.json.JSONObject obj = new org.json.JSONObject(unescaped);
                    int minute = obj.optInt("minute", 0);
                    if (minute > 0) {
                        startPosMs = (long) minute * 60 * 1000L;
                    }
                    String t = obj.optString("title", "");
                    String ep = obj.optString("episode", "");
                    if (!t.isEmpty()) {
                        displayTitle = ep.isEmpty() ? t : (t + " - " + ep);
                    }
                    titleId = obj.optInt("titleId", 0);
                    episodeId = obj.optInt("episodeId", 0);
                } catch (Exception e) {
                    Log.w(TAG, "Errore parsing JSON metadati", e);
                }
            }

            // Fallback: estrazione metadati direttamente dal Referer di VixCloud (t = titolo base64, d = episodio base64, minute)
            if (referer != null) {
                try {
                    android.net.Uri uri = android.net.Uri.parse(referer);
                    if (startPosMs == 0) {
                        String minStr = uri.getQueryParameter("minute");
                        if (minStr != null && !minStr.isEmpty()) {
                            int min = Integer.parseInt(minStr);
                            if (min > 0) startPosMs = (long) min * 60 * 1000L;
                        }
                    }
                    if (displayTitle.isEmpty()) {
                        String tB64 = uri.getQueryParameter("t");
                        String dB64 = uri.getQueryParameter("d");
                        String tDec = "";
                        String dDec = "";
                        if (tB64 != null && !tB64.isEmpty()) {
                            tDec = new String(android.util.Base64.decode(tB64, android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
                        }
                        if (dB64 != null && !dB64.isEmpty()) {
                            dDec = new String(android.util.Base64.decode(dB64, android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
                        }
                        if (!tDec.isEmpty()) {
                            displayTitle = dDec.isEmpty() ? tDec : (tDec + " - " + dDec);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Errore parsing fallback referer: " + e.getMessage());
                }
            }

            // Verifica anche nella cache locale SharedPreferences se c'è un minutaggio più recente
            String cacheKey = "pos_" + (titleId > 0 ? titleId + "_" + episodeId : String.valueOf(streamUrl.hashCode()));
            long localPos = getSharedPreferences("video_progress", MODE_PRIVATE).getLong(cacheKey, 0);
            if (localPos > startPosMs) {
                startPosMs = localPos;
            }

            Log.i(TAG, "Avvio PlayerActivity: " + displayTitle + " | Posizione di partenza: " + (startPosMs / 1000) + "s");

            Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
            intent.putExtra(PlayerActivity.EXTRA_URL, streamUrl);
            intent.putExtra(PlayerActivity.EXTRA_REFERER, referer);
            intent.putExtra(PlayerActivity.EXTRA_USER_AGENT, fintoPC);
            intent.putExtra(PlayerActivity.EXTRA_TITLE, displayTitle);
            intent.putExtra(PlayerActivity.EXTRA_POSITION_MS, startPosMs);
            intent.putExtra(PlayerActivity.EXTRA_TITLE_ID, titleId);
            intent.putExtra(PlayerActivity.EXTRA_EPISODE_ID, episodeId);
            playerLauncher.launch(intent);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        miaWebView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        miaWebView.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (proxyServer != null) {
            proxyServer.stop();
        }
        miaWebView.destroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Se non siamo su TV o siamo a schermo intero, lascia fare ad Android normalmente
        if (!isAndroidTV || isFullscreen) {
            return super.dispatchKeyEvent(event);
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            boolean mosso = false;

            int screenWidth = miaWebView.getWidth();
            int screenHeight = miaWebView.getHeight();

            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                cursorY -= step;
                if (cursorY < 0) {
                    cursorY = 0;
                    miaWebView.scrollBy(0, -step - 10);
                }
                mosso = true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                cursorY += step;
                if (cursorY > screenHeight - 50) {
                    cursorY = screenHeight - 50;
                    miaWebView.scrollBy(0, step + 10);
                }
                mosso = true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                cursorX -= step;
                if (cursorX < 0) cursorX = 0;
                mosso = true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                cursorX += step;
                if (cursorX > screenWidth - 30) cursorX = screenWidth - 30;
                mosso = true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                simulaClick(cursorX, cursorY);
                return true;
            }

            if (mosso) {
                immagineCursore.setX(cursorX);
                immagineCursore.setY(cursorY);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void simulaClick(float x, float y) {
        long downTime = android.os.SystemClock.uptimeMillis();
        long eventTime = android.os.SystemClock.uptimeMillis() + 10;

        MotionEvent downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent upEvent = MotionEvent.obtain(downTime, eventTime + 10, MotionEvent.ACTION_UP, x, y, 0);

        miaWebView.dispatchTouchEvent(downEvent);
        miaWebView.dispatchTouchEvent(upEvent);

        downEvent.recycle();
        upEvent.recycle();
    }

    private void recuperaLinkDaTelegraph() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String urlInCache = prefs.getString(KEY_LAST_DOMAIN, DEFAULT_URL);

        new Thread(() -> {
            try {
                URL url = new URL("https://api.telegra.ph/getPage/streamingcommunity-05-22-3?return_content=true");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject jsonObject = new JSONObject(response.toString());
                JSONArray contentArray = jsonObject.getJSONObject("result").getJSONArray("content");

                JSONObject aTagObject = contentArray.getJSONObject(0).getJSONArray("children").getJSONObject(0);
                String urlEstratto = aTagObject.getJSONObject("attrs").getString("href");

                URL parsedUrl = new URL(urlEstratto);
                dominioDinamico = parsedUrl.getHost();

                // Salva nella cache locale per i futuri avvii offline
                prefs.edit().putString(KEY_LAST_DOMAIN, urlEstratto).apply();

                runOnUiThread(() -> miaWebView.loadUrl(urlEstratto));

            } catch (Exception e) {
                Log.w(TAG, "Errore recupero da Telegraph, utilizzo fallback da cache: " + urlInCache, e);
                try {
                    dominioDinamico = new URL(urlInCache).getHost();
                } catch (Exception ignored) {}
                runOnUiThread(() -> miaWebView.loadUrl(urlInCache));
            }
        }).start();
    }
}