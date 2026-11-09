package com.example.randomwallpaper;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.util.Size;

import java.io.InputStream;

/**
 * 图片解码：按屏幕尺寸降采样，避免 4000x3000 大图直接解码导致 OOM / 壁纸服务被杀。
 * 优先用 ImageDecoder（Android 9+，可正确解码 HEIC/HEIF、WebP 动图首帧），
 * 失败时回退 BitmapFactory。
 */
public final class BitmapDecoder {

    private BitmapDecoder() {
    }

    public static Bitmap decode(ContentResolver resolver, Uri uri, int maxSide) {
        if (uri == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    Size size = info.getSize();
                    decoder.setTargetSampleSize(
                            computeSample(size.getWidth(), size.getHeight(), maxSide));
                    // 用软件内存，避免硬件位图在部分 HyperOS 机型上绘制出黑图
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } catch (Exception ignored) {
                // 落到下面的兜底解码
            }
        }
        return decodeLegacy(resolver, uri, maxSide);
    }

    private static Bitmap decodeLegacy(ContentResolver resolver, Uri uri, int maxSide) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream first = resolver.openInputStream(uri);
            if (first == null) {
                return null;
            }
            try {
                BitmapFactory.decodeStream(first, null, bounds);
            } finally {
                first.close();
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = computeSample(bounds.outWidth, bounds.outHeight, maxSide);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            InputStream second = resolver.openInputStream(uri);
            if (second == null) {
                return null;
            }
            try {
                return BitmapFactory.decodeStream(second, null, options);
            } finally {
                second.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 计算 2 的幂降采样倍率，保证解码结果不显著大于屏幕长边，兼顾清晰度与内存。 */
    private static int computeSample(int width, int height, int maxSide) {
        if (width <= 0 || height <= 0 || maxSide <= 0) {
            return 1;
        }
        int sample = 1;
        while (width / (sample * 2) >= maxSide && height / (sample * 2) >= maxSide) {
            sample *= 2;
        }
        return sample;
    }
}
