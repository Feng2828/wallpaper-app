package com.example.randomwallpaper;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * 设置界面：只保留 3 个核心功能 + 2 个必要入口。
 *   ① 相册文件夹选择（SAF 目录树，一次导入整个分类相册）
 *   ② 手动同步（唯一会扫描相册的入口）
 *   ③ 切换间隔（60 / 120 秒）
 *   必要入口：设为动态壁纸、后台保活/权限设置
 */
public class MainActivity extends Activity {

    private static final int REQ_FOLDER = 1001;
    private static final int REQ_PERMISSION = 1002;

    private TextView tvFolder;
    private TextView tvPool;
    private TextView tvSync;
    private RadioButton rb60;
    private RadioButton rb120;
    private Button btnSync;
    private boolean syncing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvFolder = findViewById(R.id.tv_folder);
        tvPool = findViewById(R.id.tv_pool);
        tvSync = findViewById(R.id.tv_sync);
        rb60 = findViewById(R.id.rb_60);
        rb120 = findViewById(R.id.rb_120);
        btnSync = findViewById(R.id.btn_sync);
        RadioGroup rgInterval = findViewById(R.id.rg_interval);
        Button btnPick = findViewById(R.id.btn_pick);
        Button btnSetWallpaper = findViewById(R.id.btn_set_wallpaper);
        Button btnKeepAlive = findViewById(R.id.btn_keep_alive);

        btnPick.setOnClickListener(v -> pickFolder());
        btnSync.setOnClickListener(v -> doSync());
        rgInterval.setOnCheckedChangeListener((group, checkedId) -> {
            long value = (checkedId == R.id.rb_120)
                    ? Prefs.INTERVAL_120 : Prefs.INTERVAL_60;
            if (value != Prefs.getInterval(this)) {
                Prefs.setInterval(this, value);
                WallpaperPool.notifySettingsChanged(getApplicationContext());
            }
        });
        btnSetWallpaper.setOnClickListener(v -> openWallpaperChooser());
        btnKeepAlive.setOnClickListener(v -> openKeepAliveSettings());

        requestMediaPermissionIfNeeded();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
        checkFolderPermission();
    }

    /* ---------------- 权限 ---------------- */

    private void requestMediaPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14 增加了"仅允许部分照片"，两个权限一起申请才能弹出完整选择框
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                }, REQ_PERMISSION);
            }
        } else if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                        REQ_PERMISSION);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        REQ_PERMISSION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            // 即使拒绝也不影响主流程：读图片走的是 SAF 目录授权，不依赖媒体权限
            toast("提示：读取指定文件夹主要依靠系统文件夹授权，媒体权限被拒也可正常使用");
        }
    }

    /**
     * 动态壁纸权限无法由第三方 APP 静默授予，系统只允许跳转壁纸选择页由用户点选，
     * 这是 Android 的硬性限制，任何 ROM 都一样。
     */
    private void openWallpaperChooser() {
        try {
            Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new ComponentName(this, RandomWallpaperService.class));
            startActivity(intent);
            toast("若未自动选中，请在动态壁纸列表里点「随机相册壁纸」→ 设置壁纸");
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_SET_WALLPAPER));
                toast("请在动态壁纸里选择「随机相册壁纸」");
            } catch (Exception e2) {
                toast("请手动打开：设置 → 壁纸与个性化 → 动态壁纸 → 随机相册壁纸");
            }
        }
    }

    /** 优先申请加入电池优化白名单；已在白名单则打开应用详情页改自启动 / 省电策略。 */
    private void openKeepAliveSettings() {
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    toast("请在弹窗选「允许」；之后还需在应用详情里开启自启动、省电设为无限制");
                    return;
                } catch (Exception ignored) {
                    // 部分 HyperOS 版本不弹这个系统框，直接走应用详情页
                }
            }
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            toast("请开启：自启动、省电策略「无限制」、照片权限");
        } catch (Exception e) {
            toast("请手动打开：设置 → 应用设置 → 应用管理 → 随机相册壁纸");
        }
    }

    /* ---------------- ① 选择相册文件夹 ---------------- */

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        // 让系统文件选择器显示完整目录树（含内部存储根目录），HyperOS 上很有用
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        try {
            startActivityForResult(intent, REQ_FOLDER);
        } catch (Exception e) {
            toast("无法打开文件夹选择器，请用系统「文件管理」App 后重试");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri tree = data.getData();
        if (tree == null) {
            return;
        }

        // 持久化目录读权限：否则重启 / 一段时间后 URI 会失效（HyperOS 常见）
        try {
            getContentResolver().takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // 个别第三方文件管理器不支持持久授权，本次会话仍可读
        }
        releaseOtherFolderPermissions(tree);

        Prefs.setTreeUri(this, tree.toString(), queryFolderName(tree));
        Prefs.setPool(this, new ArrayList<String>());   // 换文件夹先清空旧池
        refreshUi();

        // 需求 1：选中文件夹后一次性导入全部图片。
        // 这是"用户主动选择"触发的一次导入，不是后台扫描；此后只有点【手动同步】才会再读相册。
        doSync();
    }

    private void releaseOtherFolderPermissions(Uri newTree) {
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (!permission.getUri().toString().equals(newTree.toString())) {
                try {
                    getContentResolver().releasePersistableUriPermission(
                            permission.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String queryFolderName(Uri tree) {
        Cursor cursor = null;
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
            cursor = getContentResolver().query(document,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception ignored) {
                }
            }
        }
        String last = tree.getLastPathSegment();
        return last == null ? "已选择的文件夹" : last;
    }

    /* ---------------- ② 手动同步 ---------------- */

    private void doSync() {
        if (syncing) {
            return;
        }
        syncing = true;
        btnSync.setEnabled(false);
        tvSync.setText("正在同步…");

        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            WallpaperPool.Result result = WallpaperPool.sync(app);
            runOnUiThread(() -> {
                syncing = false;
                btnSync.setEnabled(true);
                tvSync.setText(result.message);
                if (result.ok) {
                    // 通知壁纸服务重新读取壁纸池并立即换一张，让同步结果马上可见
                    WallpaperPool.notifyPoolChanged(app);
                }
                toast(result.message);
                refreshUi();
            });
        }, "sync-wallpaper-pool").start();
    }

    /* ---------------- 界面状态 ---------------- */

    private void refreshUi() {
        String folder = Prefs.getFolderName(this);
        tvFolder.setText(folder == null ? "未选择" : "已选择：" + folder);

        int count = Prefs.getPool(this).size();
        long last = Prefs.getLastSync(this);
        String time = last <= 0 ? "尚未同步"
                : new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(last));
        tvPool.setText("壁纸池：" + count + " 张   ·   上次同步：" + time);

        if (Prefs.getInterval(this) == Prefs.INTERVAL_120) {
            rb120.setChecked(true);
        } else {
            rb60.setChecked(true);
        }
    }

    /** HyperOS 清理授权后给出提示，避免用户以为同步坏了。 */
    private void checkFolderPermission() {
        String tree = Prefs.getTreeUri(this);
        if (tree == null || Prefs.getPool(this).isEmpty()) {
            return;
        }
        boolean valid = false;
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().toString().equals(tree)
                    && permission.isReadPermission()) {
                valid = true;
                break;
            }
        }
        if (!valid) {
            tvSync.setText("注意：目录授权可能已失效，若同步失败请重新选择一次文件夹。");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
