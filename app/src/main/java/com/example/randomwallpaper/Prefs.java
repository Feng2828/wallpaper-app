package com.example.randomwallpaper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 全部配置都放在 SharedPreferences 里。
 * 壁纸服务被系统重启后直接读这里恢复，不需要任何后台扫描或开机广播。
 */
public final class Prefs {

    private static final String FILE = "random_wallpaper_prefs";

    public static final String ACTION_POOL_CHANGED = "com.example.randomwallpaper.POOL_CHANGED";
    public static final String ACTION_SETTINGS_CHANGED = "com.example.randomwallpaper.SETTINGS_CHANGED";

    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_FOLDER_NAME = "folder_name";
    private static final String KEY_POOL = "pool";
    private static final String KEY_INTERVAL = "interval_ms";
    private static final String KEY_LAST_SYNC = "last_sync";

    public static final long INTERVAL_60 = 60_000L;
    public static final long INTERVAL_120 = 120_000L;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /* ---------- 选中的相册文件夹（SAF 目录树 URI） ---------- */

    public static String getTreeUri(Context c) {
        return sp(c).getString(KEY_TREE_URI, null);
    }

    public static void setTreeUri(Context c, String uri, String name) {
        sp(c).edit()
                .putString(KEY_TREE_URI, uri)
                .putString(KEY_FOLDER_NAME, name)
                .apply();
    }

    public static String getFolderName(Context c) {
        return sp(c).getString(KEY_FOLDER_NAME, null);
    }

    /* ---------- 壁纸池：存 documentId 列表（不存完整 URI，授权失效重选后可重建） ---------- */

    public static List<String> getPool(Context c) {
        ArrayList<String> out = new ArrayList<>();
        String raw = sp(c).getString(KEY_POOL, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                out.add(array.getString(i));
            }
        } catch (Exception ignored) {
            // 数据损坏时按空池处理，下次同步会重建
        }
        return out;
    }

    public static void setPool(Context c, List<String> pool) {
        JSONArray array = new JSONArray();
        if (pool != null) {
            for (String id : pool) {
                array.put(id);
            }
        }
        sp(c).edit().putString(KEY_POOL, array.toString()).apply();
    }

    /* ---------- 切换间隔：只提供 60 / 120 秒两档 ---------- */

    public static long getInterval(Context c) {
        return sp(c).getLong(KEY_INTERVAL, INTERVAL_60) == INTERVAL_120
                ? INTERVAL_120 : INTERVAL_60;
    }

    public static void setInterval(Context c, long value) {
        sp(c).edit().putLong(KEY_INTERVAL,
                value == INTERVAL_120 ? INTERVAL_120 : INTERVAL_60).apply();
    }

    /* ---------- 上次同步时间 ---------- */

    public static long getLastSync(Context c) {
        return sp(c).getLong(KEY_LAST_SYNC, 0L);
    }

    public static void setLastSync(Context c, long time) {
        sp(c).edit().putLong(KEY_LAST_SYNC, time).apply();
    }

}
