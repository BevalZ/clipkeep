package com.clipkeep.android;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import java.io.*;

/** Bounded import: inspect dimensions before decoding; keep originals on disk. */
final class Images {
    static void validate(byte[] png) throws IOException {
        if (png.length > LanProtocol.MAX_IMAGE_BYTES || png.length < 24
            || png[0] != (byte)137 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') throw new IOException("需要不超过 8 MB 的 PNG 图片");
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        check(bounds);
        Bitmap decoded = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (decoded == null) throw new IOException("图片损坏");
        decoded.recycle();
    }
    private static void check(BitmapFactory.Options bounds) throws IOException {
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || (long)bounds.outWidth * bounds.outHeight > LanProtocol.MAX_IMAGE_PIXELS)
            throw new IOException("图片无法读取或超过 1600 万像素");
    }
    static byte[] read(InputStream input) throws IOException {
        if (input == null) throw new IOException("图片不存在");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (out.size() + count > LanProtocol.MAX_IMAGE_BYTES) throw new IOException("图片超过 8 MB");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
    static byte[] fromUri(Context context, Uri uri) throws IOException {
        if (!"content".equals(uri.getScheme())) throw new IOException("请选择系统提供的图片");
        byte[] bytes;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) { bytes = read(input); }
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds); check(bounds);
        if (bytes.length >= 8 && bytes[0] == (byte)137 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return bytes;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) throw new IOException("图片损坏");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            OutputStream bounded = new FilterOutputStream(output) {
                int size;
                @Override public void write(int value) throws IOException { if (++size > LanProtocol.MAX_IMAGE_BYTES) throw new IOException("转换后的 PNG 超过 8 MB"); out.write(value); }
                @Override public void write(byte[] data, int start, int length) throws IOException { if (size + length > LanProtocol.MAX_IMAGE_BYTES) throw new IOException("转换后的 PNG 超过 8 MB"); size += length; out.write(data, start, length); }
            };
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bounded)) throw new IOException("图片编码失败或超过 8 MB");
            return output.toByteArray();
        } finally { bitmap.recycle(); }
    }
    static Bitmap preview(File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        check(bounds);
        int sample = 1; while (Math.max(bounds.outWidth, bounds.outHeight) > 1024L * sample) sample *= 2;
        bounds.inSampleSize = sample; bounds.inJustDecodeBounds = false;
        bounds.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bitmap == null) throw new IOException("图片损坏，无法预览");
        return bitmap;
    }
}
