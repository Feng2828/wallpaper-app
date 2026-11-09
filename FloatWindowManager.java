package com.example.randomwallpaper;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import java.util.List;

public class FloatWindowManager {

    private static FloatWindowManager instance;
    private final Context appContext;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View floatBall;
    private WindowManager.LayoutParams params;
    private boolean isShowing = false;

    private int screenWidth;
    private float downX, downY;
    private float paramX, paramY;
    private boolean isDragging = false;

    private final Runnable checkForeground = new Runnable() {
        @Override
        public void run() {
            if (!isShowing) return;
            boolean isLauncher = isCurrentForegroundLauncher();
            floatBall.setVisibility(isLauncher  View.VISIBLE  View.GONE);
            handler.postDelayed(this, 1500);
        }
    };

    private FloatWindowManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        screenWidth = wm.getDefaultDisplay().getWidth();
    }

    public static synchronized FloatWindowManager getInstance(Context context) {
        if (instance == null) {
            instance = new FloatWindowManager(context);
        }
        return instance;
    }

    public void show() {
        if (isShowing) return;
        createFloatBall();
        try {
            wm.addView(floatBall, params);
            isShowing = true;
            handler.post(checkForeground);
        } catch (Exception ignored) {
        }
    }

    public void hide() {
        if (!isShowing) return;
        handler.removeCallbacks(checkForeground);
        try {
            wm.removeView(floatBall);
        } catch (Exception ignored) {
        }
        isShowing = false;
    }

    private void createFloatBall() {
        floatBall = new ImageView(appContext);
        ((ImageView) floatBall).setImageResource(android.R.drawable.ic_menu_slideshow);
        floatBall.setBackgroundColor(0x88000000);
        floatBall.setPadding(dp2px(6), dp2px(6), dp2px(6), dp2px(6));

        int size = dp2px(24);
        params = new WindowManager.LayoutParams();
        if (Build.VERSION.SDK_INT = Build.VERSION_CODES.O) {
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            params.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        params.format = PixelFormat.RGBA_8888;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                 WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        params.gravity = Gravity.START  Gravity.TOP;
        params.width = size;
        params.height = size;
        params.x = screenWidth - size - dp2px(16);
        params.y = dp2px(200);

        floatBall.setOnTouchListener((v, event) - {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN
                    downX = event.getRawX();
                    downY = event.getRawY();
                    paramX = params.x;
                    paramY = params.y;
                    isDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (Math.abs(dx)  5  Math.abs(dy)  5) {
                        isDragging = true;
                        params.x = (int) (paramX + dx);
                        params.y = (int) (paramY + dy);
                        wm.updateViewLayout(floatBall, params);
                    }
                    return true;

                case MotionEvent.ACTION_UP
                    if (!isDragging) {
                         点击：立即换一张壁纸
                        changeWallpaperNow();
                    }
                    return true;
            }
            return false;
        });
    }

    private void changeWallpaperNow() {
         通过广播通知后台服务立即换图
        Intent intent = new Intent(Prefs.ACTION_SETTINGS_CHANGED);
        intent.setPackage(appContext.getPackageName());
        appContext.sendBroadcast(intent);
        
         同时直接触发一次换图
        new Thread(() - {
            try {
                AutoChangeService service = null;
                 简化处理：发送广播由服务处理
            } catch (Exception ignored) {
            }
        }).start();
        
         更稳妥：直接调用工具类换图
        WallpaperChangerHelper.changeNext(appContext);
    }

    private boolean isCurrentForegroundLauncher() {
        try {
            ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            ListActivityManager.RunningAppProcessInfo processes = am.getRunningAppProcesses();
            if (processes == null  processes.isEmpty()) return false;

            String foregroundPkg = null;
            for (ActivityManager.RunningAppProcessInfo info  processes) {
                if (info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    foregroundPkg = info.processName;
                    break;
                }
            }
            if (foregroundPkg == null) return false;

             判断是否为桌面
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            String homePkg = appContext.getPackageManager()
                    .resolveActivity(homeIntent, 0).activityInfo.packageName;
            return foregroundPkg.equals(homePkg);
        } catch (Exception e) {
            return false;
        }
    }

    private int dp2px(int dp) {
        float density = appContext.getResources().getDisplayMetrics().density;
        return (int) (dp  density + 0.5f);
    }

    public boolean isShowing() {
        return isShowing;
    }
}
