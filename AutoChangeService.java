package com.example.randomwallpaper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AutoChangeService extends Service {

    private static final String CHANNEL_ID = "auto_wallpaper_channel";
    private static final int NOTIFY_ID = 1001;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private ExecutorService decoder;

    private List<String> pool;
    private String currentId = null;
    private boolean destroyed = false;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            switchNext(true);
            scheduleNext();
        }
    };

    private final BroadcastReceiver appReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Prefs.ACTION_POOL_CHANGED.equals(action)) {
                reloadPool();
                switchNext(true);
                scheduleNext();
            } else if (Prefs.ACTION_SETTINGS_CHANGED.equals(action)) {
                scheduleNext();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        decoder = Executors.newSingleThreadExecutor();
        reloadPool();
        startForegroundCompat();
        registerReceiver();
        scheduleNext();
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "壁纸自动切换",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("后台定时更换壁纸");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
                .setContentTitle("壁纸自动切换运行中")
                .setContentText("按设定间隔自动更换桌面壁纸")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFY_ID, notification);
        } else {
            startForeground(NOTIFY_ID, notification);
        }
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Prefs.ACTION_POOL_CHANGED);
        filter.addAction(Prefs.ACTION_SETTINGS_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(appReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(appReceiver, filter);
        }
    }

    private void reloadPool() {
        pool = Prefs.getPool(this);
    }

    private String pick(boolean avoidCurrent) {
        int size = pool.size();
        if (size == 0) return null;
        int start = random.nextInt(size);
        for (int offset = 0; offset < size; offset++) {
            String id = pool.get((start + offset) % size);
            if (!avoidCurrent || !id.equals(currentId)) {
                return id;
            }
        }
        return pool.get(start);
    }

    private void switchNext(boolean avoidCurrent) {
        if (pool == null || pool.isEmpty()) {
            reloadPool();
            if (pool.isEmpty()) return;
        }
        final String next = pick(avoidCurrent);
        if (next == null) return;
        currentId = next;
        final Uri uri = WallpaperPool.uriFor(this, next);
        if (uri == null) return;

        final int targetSize = getScreenMaxSide();
        decoder.execute(() -> {
            try {
                Bitmap bitmap = BitmapDecoder.decode(getContentResolver(), uri, targetSize);
                if (bitmap != null) {
                    WallpaperManager wm = WallpaperManager.getInstance(AutoChangeService.this);
                    wm.setBitmap(bitmap);
                    bitmap.recycle();
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void scheduleNext() {
        mainHandler.removeCallbacks(tickRunnable);
        if (destroyed || pool == null || pool.isEmpty()) return;
        mainHandler.postDelayed(tickRunnable, Prefs.getInterval(this));
    }

    private int getScreenMaxSide() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            if (wm != null) {
                wm.getDefaultDisplay().getRealMetrics(dm);
                return Math.max(dm.widthPixels, dm.heightPixels);
            }
        } catch (Exception ignored) {
        }
        return 1440;
    }

    /** 对外提供：手动触发立即换一张（悬浮球点击调用） */
    public void changeImmediately() {
        mainHandler.removeCallbacks(tickRunnable);
        switchNext(true);
        scheduleNext();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(appReceiver); } catch (Exception ignored) {}
        if (decoder != null) decoder.shutdownNow();
        super.onDestroy();
    }
}
