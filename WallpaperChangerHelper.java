package com.example.randomwallpaper;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.List;
import java.util.Random;

public class WallpaperChangerHelper {

    private static final Random random = new Random();
    private static String currentId = null;

    public static synchronized void changeNext(Context context) {
        List<String> pool = Prefs.getPool(context);
        if (pool == null || pool.isEmpty()) return;

        int size = pool.size();
        int start = random.nextInt(size);
        String nextId = null;
        for (int offset = 0; offset < size; offset++) {
            String id = pool.get((start + offset) % size);
            if (!id.equals(currentId)) {
                nextId = id;
                break;
            }
        }
        if (nextId == null) nextId = pool.get(start);
        currentId = nextId;

        final Uri uri = WallpaperPool.uriFor(context, nextId);
        if (uri == null) return;

        final int targetSize = getScreenMaxSide(context);
        new Thread(() -> {
            try {
                Bitmap bitmap = BitmapDecoder.decode(
                        context.getContentResolver(), uri, targetSize);
                if (bitmap != null) {
                    WallpaperManager wm = WallpaperManager.getInstance(context);
                    wm.setBitmap(bitmap);
                    bitmap.recycle();
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    private static int getScreenMaxSide(Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            if (wm != null) {
                wm.getDefaultDisplay().getRealMetrics(dm);
                return Math.max(dm.widthPixels, dm.heightPixels);
            }
        } catch (Exception ignored) {
        }
        return 1440;
    }
}
