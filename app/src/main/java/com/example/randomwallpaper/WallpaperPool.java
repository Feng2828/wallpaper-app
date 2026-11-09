package com.example.randomwallpaper;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * 壁纸池管理 + 手动同步。
 *
 * 【重要】整个 APP 里只有本类的 sync() 会遍历相册文件夹，
 * 且它只由「选择文件夹」和「点击手动同步按钮」这两个用户主动操作调用。
 * 代码里没有任何 ContentObserver / WorkManager / AlarmManager / JobScheduler / 开机广播，
 * 所以不点同步就绝不会扫描相册，符合需求 2。
 */
public final class WallpaperPool {

    public static class Result {
        public boolean ok;
        public String message;
        public int added;
        public int removed;
        public int total;
    }

    private static final String[] PROJECTION = new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
    };

    private WallpaperPool() {
    }

    /**
     * 扫描一次选中文件夹，做增量同步：
     * 文件夹新增的图片加入壁纸池，已删除的图片从壁纸池移除。
     */
    public static Result sync(Context ctx) {
        Result result = new Result();
        String treeString = Prefs.getTreeUri(ctx);
        if (TextUtils.isEmpty(treeString)) {
            result.message = "请先选择相册文件夹";
            return result;
        }

        Uri tree = Uri.parse(treeString);

        // HyperOS 重启 / 清理授权后，持久目录权限偶尔会失效，先尝试补一次
        if (!hasPersistedPermission(ctx, tree)) {
            try {
                ctx.getContentResolver().takePersistableUriPermission(
                        tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // 部分第三方文件管理器不支持持久化授权，仍可继续用本次会话的临时授权读取
            }
        }

        // documentId -> 文件名（LinkedHashMap 保持遍历顺序）
        LinkedHashMap<String, String> current = new LinkedHashMap<>();
        Cursor cursor = null;
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
            cursor = ctx.getContentResolver().query(children, PROJECTION, null, null, null);
            if (cursor == null) {
                result.message = "无法读取该文件夹，请重新选择一次相册文件夹（可能是授权已失效）";
                return result;
            }

            int idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            if (idIdx < 0) {
                result.message = "该目录不提供图片列表，请在系统「文件管理」里换一个真实文件夹";
                return result;
            }

            while (cursor.moveToNext()) {
                String mime = mimeIdx >= 0 ? cursor.getString(mimeIdx) : null;
                if (mime == null || !mime.toLowerCase().startsWith("image/")) {
                    continue;   // 只导入图片，跳过视频/文档/隐藏文件
                }
                String id = cursor.getString(idIdx);
                if (TextUtils.isEmpty(id)) {
                    continue;
                }
                String name = nameIdx >= 0 ? cursor.getString(nameIdx) : id;
                current.put(id, name == null ? id : name);
            }
        } catch (Exception e) {
            result.message = "读取失败（" + e.getClass().getSimpleName()
                    + "），请重新选择一次相册文件夹";
            return result;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception ignored) {
                }
            }
        }

        List<String> oldList = Prefs.getPool(ctx);
        Set<String> oldSet = new HashSet<>(oldList);
        Set<String> newSet = current.keySet();

        int added = 0;
        int removed = 0;
        for (String id : newSet) {
            if (!oldSet.contains(id)) {
                added++;
            }
        }
        for (String id : oldSet) {
            if (!newSet.contains(id)) {
                removed++;
            }
        }

        // 同步后的壁纸池 = 当前文件夹里的全部图片（全量镜像，天然实现"新增加入/删除移除"）
        Prefs.setPool(ctx, new ArrayList<>(newSet));
        Prefs.setLastSync(ctx, System.currentTimeMillis());

        result.ok = true;
        result.added = added;
        result.removed = removed;
        result.total = newSet.size();
        result.message = "同步完成：新增 " + added + " 张，移除 " + removed
                + " 张，当前壁纸池 " + result.total + " 张";
        return result;
    }

    private static boolean hasPersistedPermission(Context ctx, Uri tree) {
        for (UriPermission permission : ctx.getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().toString().equals(tree.toString())
                    && permission.isReadPermission()) {
                return true;
            }
        }
        return false;
    }

    /** 由 documentId 还原成可以 openInputStream 的图片 URI。 */
    public static Uri uriFor(Context ctx, String documentId) {
        String treeString = Prefs.getTreeUri(ctx);
        if (treeString == null || documentId == null) {
            return null;
        }
        try {
            return DocumentsContract.buildDocumentUriUsingTree(
                    Uri.parse(treeString), documentId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 通知正在运行的壁纸服务：池已变化，重新加载并立即换一张。 */
    public static void notifyPoolChanged(Context ctx) {
        Intent intent = new Intent(Prefs.ACTION_POOL_CHANGED).setPackage(ctx.getPackageName());
        ctx.sendBroadcast(intent);
    }

    /** 通知正在运行的壁纸服务：切换间隔已变化，重置定时器。 */
    public static void notifySettingsChanged(Context ctx) {
        Intent intent = new Intent(Prefs.ACTION_SETTINGS_CHANGED).setPackage(ctx.getPackageName());
        ctx.sendBroadcast(intent);
    }
}
