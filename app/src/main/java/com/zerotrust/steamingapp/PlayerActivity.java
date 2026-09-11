package com.zerotrust.steamingapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TrackSelectionDialogBuilder;

import java.util.HashMap;
import java.util.Map;

public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "extra_video_url";
    public static final String EXTRA_REFERER = "extra_referer";
    public static final String EXTRA_USER_AGENT = "extra_user_agent";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_POSITION_MS = "extra_position_ms";
    public static final String EXTRA_TITLE_ID = "extra_title_id";
    public static final String EXTRA_EPISODE_ID = "extra_episode_id";

    public static final String RESULT_POSITION_SECONDS = "result_position_seconds";
    public static final String RESULT_DURATION_SECONDS = "result_duration_seconds";

    private static final String TAG = "PlayerActivity";

    private PlayerView playerView;
    private LinearLayout playerTopBar;
    private ImageButton btnBack;
    private TextView txtPlayerTitle;
    private ImageButton btnAudio;
    private ImageButton btnSubtitles;
    private ImageButton btnAspectRatio;
    private TextView txtAspectRatioStatus;
    private ProgressBar loadingSpinner;
    private TextView textError;

    private ExoPlayer exoPlayer;
    private String videoUrl;
    private String referer;
    private String userAgent;
    private String mediaTitle;
    private long startPositionMs = 0;
    private int titleId = 0;
    private int episodeId = 0;

    private int currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
    private boolean isControlsVisible = true;
    private final Handler autoHideHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideControlsRunnable = this::hideControls;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Handler toastHideHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        hideSystemUI();

        playerView = findViewById(R.id.playerView);
        playerTopBar = findViewById(R.id.playerTopBar);
        btnBack = findViewById(R.id.btnBack);
        txtPlayerTitle = findViewById(R.id.txtPlayerTitle);
        btnAudio = findViewById(R.id.btnAudio);
        btnSubtitles = findViewById(R.id.btnSubtitles);
        btnAspectRatio = findViewById(R.id.btnAspectRatio);
        txtAspectRatioStatus = findViewById(R.id.txtAspectRatioStatus);
        loadingSpinner = findViewById(R.id.loadingSpinner);
        textError = findViewById(R.id.textError);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishWithResult();
            }
        });

        Intent intent = getIntent();
        if (intent != null) {
            videoUrl = intent.getStringExtra(EXTRA_URL);
            referer = intent.getStringExtra(EXTRA_REFERER);
            userAgent = intent.getStringExtra(EXTRA_USER_AGENT);
            mediaTitle = intent.getStringExtra(EXTRA_TITLE);
            startPositionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0);
            titleId = intent.getIntExtra(EXTRA_TITLE_ID, 0);
            episodeId = intent.getIntExtra(EXTRA_EPISODE_ID, 0);
        }

        if (mediaTitle != null && !mediaTitle.isEmpty()) {
            txtPlayerTitle.setText(mediaTitle);
        } else {
            txtPlayerTitle.setText(R.string.app_name);
        }

        btnBack.setOnClickListener(v -> finishWithResult());
        btnAudio.setOnClickListener(v -> { resetControlsTimeout(); showAudioTrackDialog(); });
        btnSubtitles.setOnClickListener(v -> { resetControlsTimeout(); showSubtitleTrackDialog(); });
        btnAspectRatio.setOnClickListener(v -> { resetControlsTimeout(); cycleAspectRatio(); });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                Log.i(TAG, "onSingleTapUp: toggleControls");
                toggleControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                Log.i(TAG, "onDoubleTap: cycleAspectRatio");
                cycleAspectRatio();
                return true;
            }
        });

        if (videoUrl == null || videoUrl.isEmpty()) {
            showError("URL video non valido.");
            return;
        }

        initPlayer();
        showControls();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (gestureDetector != null) {
            gestureDetector.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    private void showControls() {
        isControlsVisible = true;
        playerTopBar.setVisibility(View.VISIBLE);
        playerView.showController();
        resetControlsTimeout();
    }

    private void hideControls() {
        isControlsVisible = false;
        playerTopBar.setVisibility(View.GONE);
        playerView.hideController();
        autoHideHandler.removeCallbacks(hideControlsRunnable);
    }

    private void toggleControls() {
        if (isControlsVisible) {
            hideControls();
        } else {
            showControls();
        }
    }

    private void resetControlsTimeout() {
        autoHideHandler.removeCallbacks(hideControlsRunnable);
        autoHideHandler.postDelayed(hideControlsRunnable, 4500);
    }

    private void initPlayer() {
        loadingSpinner.setVisibility(View.VISIBLE);

        Map<String, String> headers = new HashMap<>();
        if (referer != null && !referer.isEmpty()) {
            headers.put("Referer", referer);
            headers.put("Origin", referer);
        }

        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent != null ? userAgent : "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setDefaultRequestProperties(headers);

        MediaItem.Builder mediaItemBuilder = new MediaItem.Builder().setUri(videoUrl);
        if (videoUrl.contains("playlist") || videoUrl.contains(".m3u8") || videoUrl.contains("vixcloud")) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        MediaItem mediaItem = mediaItemBuilder.build();

        MediaSource mediaSource;
        if (videoUrl.contains("playlist") || videoUrl.contains(".m3u8") || videoUrl.contains("vixcloud")) {
            HlsMediaSource.Factory hlsMediaSourceFactory = new HlsMediaSource.Factory(httpDataSourceFactory)
                    .setAllowChunklessPreparation(true);
            mediaSource = hlsMediaSourceFactory.createMediaSource(mediaItem);
        } else {
            mediaSource = new DefaultMediaSourceFactory(httpDataSourceFactory).createMediaSource(mediaItem);
        }

        exoPlayer = new ExoPlayer.Builder(this).build();

        // Disabilita i sottotitoli di default (si attivano con il pulsante dedicato)
        exoPlayer.setTrackSelectionParameters(
                exoPlayer.getTrackSelectionParameters()
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
        );

        playerView.setPlayer(exoPlayer);

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_BUFFERING) {
                    loadingSpinner.setVisibility(View.VISIBLE);
                } else if (playbackState == Player.STATE_READY) {
                    loadingSpinner.setVisibility(View.GONE);
                } else if (playbackState == Player.STATE_ENDED) {
                    finishWithResult();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "Errore riproduzione ExoPlayer", error);
                loadingSpinner.setVisibility(View.GONE);
                showError("Errore durante la riproduzione del flusso video: " + error.getMessage());
            }
        });

        exoPlayer.setMediaSource(mediaSource);

        if (startPositionMs > 0) {
            Log.i(TAG, "Ripresa riproduzione dalla posizione: " + (startPositionMs / 1000) + "s (" + (startPositionMs / 60000) + " min)");
            exoPlayer.seekTo(startPositionMs);
        }

        exoPlayer.prepare();
        exoPlayer.play();

        startProgressTracking();
    }

    private void cycleAspectRatio() {
        if (currentResizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
            showStatusBadge("Schermo intero (Zoom senza barre)");
        } else if (currentResizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
            showStatusBadge("Riempi schermo");
        } else {
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
            showStatusBadge("Originale (Adatta allo schermo)");
        }
        playerView.setResizeMode(currentResizeMode);
    }

    private void showStatusBadge(String message) {
        toastHideHandler.removeCallbacksAndMessages(null);
        txtAspectRatioStatus.setText(message);
        txtAspectRatioStatus.setVisibility(View.VISIBLE);
        toastHideHandler.postDelayed(() -> txtAspectRatioStatus.setVisibility(View.GONE), 1600);
    }

    private void showSubtitleTrackDialog() {
        if (exoPlayer == null) return;
        new TrackSelectionDialogBuilder(this, "Sottotitoli", exoPlayer, C.TRACK_TYPE_TEXT)
                .setShowDisableOption(true)
                .setAllowAdaptiveSelections(false)
                .build()
                .show();
    }

    private void showAudioTrackDialog() {
        if (exoPlayer == null) return;
        new TrackSelectionDialogBuilder(this, "Lingua Audio", exoPlayer, C.TRACK_TYPE_AUDIO)
                .setShowDisableOption(false)
                .setAllowAdaptiveSelections(false)
                .build()
                .show();
    }

    private void startProgressTracking() {
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (exoPlayer != null && exoPlayer.isPlaying()) {
                    long currentMs = exoPlayer.getCurrentPosition();
                    saveLocalProgress(currentMs);
                }
                progressHandler.postDelayed(this, 10000);
            }
        };
        progressHandler.postDelayed(progressRunnable, 10000);
    }

    private void saveLocalProgress(long positionMs) {
        if (positionMs <= 0) return;
        SharedPreferences prefs = getSharedPreferences("video_progress", MODE_PRIVATE);
        String key = "pos_" + (titleId > 0 ? titleId + "_" + episodeId : videoUrl.hashCode());
        prefs.edit().putLong(key, positionMs).apply();
    }

    private void showError(String msg) {
        textError.setText(msg);
        textError.setVisibility(View.VISIBLE);
    }

    private void hideSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (exoPlayer == null) return super.dispatchKeyEvent(event);

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                long newPos = Math.max(0, exoPlayer.getCurrentPosition() - 10000);
                exoPlayer.seekTo(newPos);
                showControls();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                long newPos = Math.min(exoPlayer.getDuration(), exoPlayer.getCurrentPosition() + 10000);
                exoPlayer.seekTo(newPos);
                showControls();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (exoPlayer.isPlaying()) {
                    exoPlayer.pause();
                } else {
                    exoPlayer.play();
                }
                showControls();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                toggleControls();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void finishWithResult() {
        Intent resultIntent = new Intent();
        if (exoPlayer != null) {
            long currentPosSeconds = exoPlayer.getCurrentPosition() / 1000;
            long durationSeconds = exoPlayer.getDuration() / 1000;
            saveLocalProgress(exoPlayer.getCurrentPosition());
            resultIntent.putExtra(RESULT_POSITION_SECONDS, currentPosSeconds);
            resultIntent.putExtra(RESULT_DURATION_SECONDS, durationSeconds);
            resultIntent.putExtra(EXTRA_TITLE_ID, titleId);
            resultIntent.putExtra(EXTRA_EPISODE_ID, episodeId);
            Log.i(TAG, "finishWithResult: pos=" + currentPosSeconds + "s, dur=" + durationSeconds + "s, titleId=" + titleId + ", epId=" + episodeId);
        }
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) {
            exoPlayer.pause();
            saveLocalProgress(exoPlayer.getCurrentPosition());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        autoHideHandler.removeCallbacksAndMessages(null);
        progressHandler.removeCallbacksAndMessages(null);
        toastHideHandler.removeCallbacksAndMessages(null);
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
    }
}
