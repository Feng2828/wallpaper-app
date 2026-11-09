package com.example.randomwallpaper;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.wallpaper.WallpaperService;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 动态壁纸服务（本项目核心）。
 *
 * 架构说明：定时器、图片池、广播接收器全部放在 Service 层统一管理，
 * Engine 只负责绘制。这样即使系统同时创建「预览 Engine」和「桌面 Engine」，
 * 也不会出现两份定时器各自切图、重复解码的问题。
 *
 * 关于 HyperOS 保活：
 * 1、WallpaperService 由系统 WallpaperManagerService 通过 BIND_WALLPAPER 长期绑定，
 *    属于系统级绑定服务，比普通 startService 的后台服务优先级高，本身就不容易被杀；
 * 2、服务被回收后系统会重新绑定，onCreate 直接从 SharedPreferences 恢复壁纸池，
 *    不依赖任何开机广播 / 后台任务（同时保证"不后台扫描相册"）；
 * 3、屏幕关闭时主动停掉定时器省电，亮屏解锁再恢复并换图，减少被省电策略盯上的概率；
 * 4、仍需在系统设置里开启自启动、省电无限制、电池优化白名单，详见 README.md。
 */
public class RandomWallpaperService extends WallpaperService {

    private final CopyOnWriteArrayList<MyEngine> engines = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private ExecutorService decoder;
    private List<String> pool = new ArrayList<>();
    private String currentId = null;
    private Bitmap currentBitmap = null;

    private boolean hasVisibleEngine = false;
    private boolean screenOn = true;
    private boolean screenWasOff = false;
    private boolean registered = false;
    private boolean destroyed = false;

    /** 定时自动切换：到点换一张随机图，然后重新排下一轮。 */
    private final Runnable tick = () -> {
        switchNext(true);
        scheduleNext();
    };

    /**
     * 兜底：部分机型无锁屏 / 使用极简解锁，亮屏后不一定发 ACTION_USER_PRESENT，
     * 亮屏 2.5 秒后检查若已不在锁屏界面，就按解锁处理。
     */
    private final Runnable unlockFallback = () -> {
        if (destroyed || !screenOn || !screenWasOff) {
            return;
        }
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardLocked()) {
            onUnlocked();
        }
    };

    /** 系统亮灭屏 / 解锁广播：SCREEN_ON、SCREEN_OFF、USER_PRESENT 只能动态注册。 */
    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOn = false;
                screenWasOff = true;
                cancelTick();
                main.removeCallbacks(unlockFallback);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOn = true;
                // 能收到亮屏广播就说明之前是灭屏；即使服务在灭屏期间被重建也能覆盖到
                screenWasOff = true;
                main.removeCallbacks(unlockFallback);
                main.postDelayed(unlockFallback, 2500);
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                main.removeCallbacks(unlockFallback);
                onUnlocked();
            }
        }
    };

    /** APP 内部广播：手动同步完成 / 切换间隔改变时通知服务。 */
    private final BroadcastReceiver appReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Prefs.ACTION_POOL_CHANGED.equals(action)) {
                reloadPool();
                switchNext(true);      // 同步后立即刷新一张，让结果马上可见
                scheduleNext();
            } else if (Prefs.ACTION_SETTINGS_CHANGED.equals(action)) {
                scheduleNext();        // 用新的 60/120 秒间隔重排定时器
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        decoder = Executors.newSingleThreadExecutor();
        reloadPool();
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            screenOn = pm == null || pm.isInteractive();
        } catch (Exception ignored) {
            screenOn = true;
        }
        registerReceivers();
    }

    private void registerReceivers() {
        if (registered) {
            return;
        }
        IntentFilter systemFilter = new IntentFilter();
        systemFilter.addAction(Intent.ACTION_SCREEN_ON);
        systemFilter.addAction(Intent.ACTION_SCREEN_OFF);
        systemFilter.addAction(Intent.ACTION_USER_PRESENT);

        IntentFilter appFilter = new IntentFilter();
        appFilter.addAction(Prefs.ACTION_POOL_CHANGED);
        appFilter.addAction(Prefs.ACTION_SETTINGS_CHANGED);

        // Android 13+ 动态注册必须显式声明是否导出：
        // 系统广播来源在系统进程，需 RECEIVER_EXPORTED；APP 内部广播用 NOT_EXPORTED 更安全。
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(systemReceiver, systemFilter, Context.RECEIVER_EXPORTED);
            registerReceiver(appReceiver, appFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(systemReceiver, systemFilter);
            registerReceiver(appReceiver, appFilter);
        }
        registered = true;
    }

    @Override
    public Engine onCreateEngine() {
        return new MyEngine();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        cancelTick();
        main.removeCallbacks(unlockFallback);
        if (registered) {
            try {
                unregisterReceiver(systemReceiver);
                unregisterReceiver(appReceiver);
            } catch (Exception ignored) {
            }
            registered = false;
        }
        if (decoder != null) {
            decoder.shutdownNow();
        }
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
        }
        currentBitmap = null;
        engines.clear();
        super.onDestroy();
    }

    /* ---------------- 切换逻辑 ---------------- */

    /** 随机取下一张；avoidCurrent=true 时保证与当前不同（亮屏解锁、双击、定时都走这里）。 */
    private String pick(boolean avoidCurrent) {
        int size = pool.size();
        if (size == 0) {
            return null;
        }
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
        if (destroyed) {
            return;
        }
        if (pool.isEmpty()) {
            reloadPool();
        }
        final String next = pick(avoidCurrent);
        if (next == null) {
            return;
        }
        currentId = next;
        final Uri uri = WallpaperPool.uriFor(this, next);
        if (uri == null) {
            return;
        }
        final int side = targetSide();
        try {
            decoder.execute(() -> {
                Bitmap bitmap = BitmapDecoder.decode(getContentResolver(), uri, side);
                main.post(() -> applyDecoded(next, bitmap));
            });
        } catch (RejectedExecutionException ignored) {
            // 服务正在销毁
        }
    }

    private void applyDecoded(String id, Bitmap bitmap) {
        if (destroyed) {
            if (bitmap != null) {
                bitmap.recycle();
            }
            return;
        }
        // 用户连续双击时，丢弃过期的解码结果，只显示最后一次选中的图
        if (!id.equals(currentId)) {
            if (bitmap != null) {
                bitmap.recycle();
            }
            return;
        }
        if (bitmap == null || bitmap.isRecycled()) {
            // 损坏 / 不支持的图片：本次运行内跳过（不改磁盘上的壁纸池，下次同步会恢复）
            pool.remove(id);
            if (!pool.isEmpty()) {
                switchNext(false);
            }
            return;
        }
        Bitmap old = currentBitmap;
        currentBitmap = bitmap;
        if (old != null && old != bitmap && !old.isRecycled()) {
            old.recycle();
        }
        drawAll();
    }

    private void reloadPool() {
        pool = new ArrayList<>(Prefs.getPool(this));   // 只读本地配置，不扫描相册
    }

    private void onUnlocked() {
        screenOn = true;
        if (screenWasOff) {
            screenWasOff = false;
            reloadPool();
            switchNext(true);       // 需求 6：息屏后亮屏解锁，自动随机换一张新图
        }
        scheduleNext();
    }

    private void scheduleNext() {
        cancelTick();
        if (destroyed || !hasVisibleEngine || !screenOn || pool.isEmpty()) {
            return;
        }
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                return;             // 锁屏期间不切图，省电；解锁时会主动切
            }
        } catch (Exception ignored) {
        }
        main.postDelayed(tick, Prefs.getInterval(this));
    }

    private void cancelTick() {
        main.removeCallbacks(tick);
    }

    private void refreshVisibility() {
        boolean any = false;
        for (MyEngine engine : engines) {
            if (engine.isVisible()) {
                any = true;
                break;
            }
        }
        hasVisibleEngine = any;
    }

    private void drawAll() {
        for (MyEngine engine : engines) {
            engine.draw(currentBitmap);
        }
    }

    private int targetSide() {
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

    /** 居中裁剪铺满（center-crop），不变形、不留黑边。 */
    private void drawCover(Canvas canvas, Bitmap bitmap, int width, int height) {
        if (bitmap == null || bitmap.isRecycled()) {
            canvas.drawColor(Color.parseColor("#101826"));
            return;
        }
        float scale = Math.max((float) width / bitmap.getWidth(),
                (float) height / bitmap.getHeight());
        float drawWidth = bitmap.getWidth() * scale;
        float drawHeight = bitmap.getHeight() * scale;
        float left = (width - drawWidth) / 2f;
        float top = (height - drawHeight) / 2f;
        RectF dst = new RectF(left, top, left + drawWidth, top + drawHeight);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(bitmap, null, dst, paint);
    }

    /* ---------------- 壁纸绘制引擎 ---------------- */

    private class MyEngine extends Engine {

        private final GestureDetector detector;

        MyEngine() {
            detector = new GestureDetector(RandomWallpaperService.this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(MotionEvent e) {
                            return true;
                        }

                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            // 需求 5：双击壁纸区域，立刻随机切换到下一张
                            cancelTick();
                            switchNext(true);
                            scheduleNext();
                            return true;
                        }
                    });
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            // 关键：默认动态壁纸不接收触摸事件，不开这个双击功能完全失效
            setTouchEventsEnabled(true);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            engines.add(this);
            draw(currentBitmap);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            draw(currentBitmap);   // 横竖屏切换 / 分屏后重绘
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            engines.remove(this);
            refreshVisibility();
            if (!hasVisibleEngine) {
                cancelTick();
            }
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            refreshVisibility();
            if (visible && currentBitmap == null) {
                switchNext(false);     // 服务重启 / 首次启用：先显示一张
            }
            if (hasVisibleEngine) {
                scheduleNext();
            } else {
                cancelTick();          // 壁纸不可见时停掉定时器，省电、也降低被杀概率
            }
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            super.onTouchEvent(event);
            detector.onTouchEvent(event);
        }

        void draw(Bitmap bitmap) {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    drawCover(canvas, bitmap, canvas.getWidth(), canvas.getHeight());
                }
            } catch (Exception ignored) {
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }
}
