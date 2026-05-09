package com.zerotrust.steamingapp;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private WebView miaWebView;
    private String dominioDinamico = "";
    private boolean isAndroidTV = false;

    // Variabili per il Fullscreen
    private View mCustomView;
    private WebChromeClient.CustomViewCallback mCustomViewCallback;
    private int mOriginalOrientation;
    private int mOriginalSystemUiVisibility;
    private boolean isFullscreen = false;

    // Variabili per il cursore su TV
    private ImageView immagineCursore;
    private float cursorX = 500f;
    private float cursorY = 500f;
    private final int step = 40; // Velocità del cursore

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Rileva se è una TV
        android.app.UiModeManager uiModeManager = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        isAndroidTV = (uiModeManager.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION);

        miaWebView = findViewById(R.id.miaWebView);
        immagineCursore = findViewById(R.id.immagineCursore);

        // 2. Configurazione Cursore e Focus
        if (isAndroidTV) {
            immagineCursore.setVisibility(View.VISIBLE);
            // Disattiviamo il focus nativo della WebView, ora comandiamo noi col cursore!
            miaWebView.setFocusable(false);
            miaWebView.setFocusableInTouchMode(false);
        } else {
            immagineCursore.setVisibility(View.GONE);
            miaWebView.setFocusableInTouchMode(true);
        }

        // 3. Impostazioni WebView
        miaWebView.getSettings().setJavaScriptEnabled(true);
        miaWebView.getSettings().setSupportMultipleWindows(true);
        String fintoPC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/99.0.9999.99 Safari/537.36";
        miaWebView.getSettings().setUserAgentString(fintoPC);

        // 4. Gestione Popup e Fullscreen (WebChromeClient)
        miaWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                Log.i("Video", "Popup silenziato e bloccato!");
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

                // Riattiviamo i controlli del telecomando per il player fullscreen
                mCustomView.setFocusable(true);
                mCustomView.setFocusableInTouchMode(true);
                mCustomView.requestFocus();

                // Nascondiamo il cursore e aggiorniamo lo stato
                isFullscreen = true;
                if (isAndroidTV) immagineCursore.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                ((android.widget.FrameLayout) getWindow().getDecorView()).removeView(mCustomView);
                mCustomView = null;
                getWindow().getDecorView().setSystemUiVisibility(mOriginalSystemUiVisibility);
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

                if(mCustomViewCallback != null) {
                    mCustomViewCallback.onCustomViewHidden();
                    mCustomViewCallback = null;
                }

                // Rimettiamo il cursore visibile
                isFullscreen = false;
                if (isAndroidTV) immagineCursore.setVisibility(View.VISIBLE);
            }
        });

        // 5. Gestione Navigazione e Ad-Block (WebViewClient)
        miaWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains(dominioDinamico) || url.contains("vixcloud.co") || url.contains("thaculse.net") || url.contains("streaminunity")) {
                    return false; // Permetti la navigazione
                } else {
                    Log.i("Video", "Bloccato redirect a: " + url);
                    return true; // Blocca la pubblicità
                }
            }
        });

        // 6. Tasto Indietro (Back)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (miaWebView.canGoBack()) {
                    miaWebView.goBack();
                } else {
                    finish();
                }
            }
        });

        // 7. Infine recuperiamo il link
        recuperaLinkDaTelegraph();
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

    // --- GESTIONE TELECOMANDO E CURSORE INTELLIGENTE ---
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        // Se non siamo su TV o siamo a schermo intero (player video), lascia fare ad Android normalmente
        if (!isAndroidTV || isFullscreen) {
            return super.dispatchKeyEvent(event);
        }

        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            boolean mosso = false;

            // Larghezza e altezza attuali dello schermo
            int screenWidth = miaWebView.getWidth();
            int screenHeight = miaWebView.getHeight();

            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                cursorY -= step;
                if (cursorY < 0) {
                    cursorY = 0;
                    miaWebView.scrollBy(0, -step - 10); // Scroll in su
                }
                mosso = true;
            } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                cursorY += step;
                if (cursorY > screenHeight - 50) {
                    cursorY = screenHeight - 50;
                    miaWebView.scrollBy(0, step + 10); // Scroll in giù
                }
                mosso = true;
            } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                cursorX -= step;
                if (cursorX < 0) cursorX = 0;
                mosso = true;
            } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                cursorX += step;
                if (cursorX > screenWidth - 30) cursorX = screenWidth - 30;
                mosso = true;
            } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                simulaClick(cursorX, cursorY);
                return true;
            }

            // Se abbiamo usato una freccia, aggiorniamo la grafica
            if (mosso) {
                immagineCursore.setX(cursorX);
                immagineCursore.setY(cursorY);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // Metodo per il Finto Click del Cursore
    private void simulaClick(float x, float y) {
        long downTime = android.os.SystemClock.uptimeMillis();
        long eventTime = android.os.SystemClock.uptimeMillis() + 10;

        android.view.MotionEvent downEvent = android.view.MotionEvent.obtain(
                downTime, eventTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent upEvent = android.view.MotionEvent.obtain(
                downTime, eventTime + 10, android.view.MotionEvent.ACTION_UP, x, y, 0);

        miaWebView.dispatchTouchEvent(downEvent);
        miaWebView.dispatchTouchEvent(upEvent);

        downEvent.recycle();
        upEvent.recycle();
    }

    // --- RECUPERO LINK DINAMICO ---
    private void recuperaLinkDaTelegraph() {
        new Thread(() -> {
            try {
                URL url = new URL("https://api.telegra.ph/getPage/streamingcommunity-05-22-3?return_content=true");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

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

                runOnUiThread(() -> {
                    miaWebView.loadUrl(urlEstratto);
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}