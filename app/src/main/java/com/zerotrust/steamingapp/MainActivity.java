package com.zerotrust.steamingapp;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private WebView miaWebView;
    private View mCustomView;
    private WebChromeClient.CustomViewCallback mCustomViewCallback;
    private int mOriginalOrientation;
    private int mOriginalSystemUiVisibility;
    private ImageView immagineCursore;
    private float cursorX = 500; // Posizione iniziale X
    private float cursorY = 500; // Posizione iniziale Y
    private final int step = 20; // Velocità del cursore

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        immagineCursore = findViewById(R.id.immagineCursore);
// Nel tuo onCreate, dopo findViewById:
        immagineCursore.setX(cursorX);
        immagineCursore.setY(cursorY);
        miaWebView = findViewById(R.id.miaWebView);

        miaWebView.getSettings().setJavaScriptEnabled(true);
        String fintoPC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/99.0.9999.99 Safari/537.36";
        miaWebView.getSettings().setUserAgentString(fintoPC);
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
            }
        });
        miaWebView.setWebViewClient(new WebViewClient(){});
        miaWebView.loadUrl("https://streamingcommunityz.moe/");
        miaWebView.getSettings().setSupportMultipleWindows(true);
        miaWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String site = "streamingcommunityz";
                if (url.contains("streamingcommunity") || url.contains("vixcloud.co") || url.contains("thaculse.net") || url.contains(site)) {
                    return false;
                } else {
                    Log.i("Video", "Bloccato redirect a: " + url);
                    return true;
                }
            }

        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (miaWebView.canGoBack()) {

                    miaWebView.goBack();

                }else {

                    finish();

                }
            }
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
    private void muoviCursoreVisivo() {
        // Controllo dei bordi (esempio per il bordo sinistro e superiore)
        if (cursorX < 0) cursorX = 0;
        if (cursorY < 0) cursorY = 0;

        // Applichiamo le coordinate all'immagine
        immagineCursore.setX(cursorX);
        immagineCursore.setY(cursorY);

        // Portiamo l'immagine in primo piano per sicurezza
        immagineCursore.bringToFront();
    }
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                    cursorY -= step;
                    muoviCursoreVisivo();
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                    cursorY += step;
                    muoviCursoreVisivo();
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                    cursorX -= step;
                    muoviCursoreVisivo();
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    cursorX += step;
                    muoviCursoreVisivo();
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                    simulaClick(cursorX, cursorY);
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    private void simulaClick(float x, float y) {
        long downTime = android.os.SystemClock.uptimeMillis();
        long eventTime = android.os.SystemClock.uptimeMillis() + 100;
        int metaState = 0;

        // Evento di pressione (Giù)
        android.view.MotionEvent downEvent = android.view.MotionEvent.obtain(
                downTime, eventTime, android.view.MotionEvent.ACTION_DOWN, x, y, metaState);

        // Evento di rilascio (Su)
        android.view.MotionEvent upEvent = android.view.MotionEvent.obtain(
                downTime, eventTime + 10, android.view.MotionEvent.ACTION_UP, x, y, metaState);

        // Invio alla WebView
        miaWebView.dispatchTouchEvent(downEvent);
        miaWebView.dispatchTouchEvent(upEvent);
    }
}