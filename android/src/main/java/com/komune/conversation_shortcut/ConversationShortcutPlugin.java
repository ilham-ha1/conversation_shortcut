package com.komune.conversation_shortcut;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Paint;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.Person;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Publishes long-lived dynamic "conversation" shortcuts. A notification that is
 * MessagingStyle AND carries a shortcutId pointing at one of these lands in the
 * Android 11+ Conversations section. Runs in the FCM background engine too,
 * because it is registered as a regular plugin.
 */
public class ConversationShortcutPlugin implements FlutterPlugin, MethodChannel.MethodCallHandler {
    private static final String TAG = "ConversationShortcut";
    private static final String CATEGORY_CONVERSATION = "android.shortcut.conversation";

    private MethodChannel channel;
    private Context context;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        channel = new MethodChannel(binding.getBinaryMessenger(), "komune/conversation_shortcut");
        channel.setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        channel = null;
        context = null;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "push":
                result.success(push(
                        call.argument("id"),
                        call.argument("label"),
                        call.argument("personKey"),
                        call.argument("iconColor"),
                        call.argument("iconPath")));
                break;
            case "roundAvatar":
                result.success(roundAvatar(call.argument("path")));
                break;
            case "removeAll":
                removeAll();
                result.success(null);
                break;
            default:
                result.notImplemented();
        }
    }

    private boolean push(String id, String label, String personKey, Number iconColor, String iconPath) {
        if (context == null || id == null || id.isEmpty() || label == null || label.isEmpty()) {
            return false;
        }
        try {
            // Shortcut wajib punya intent; membuka app lewat launcher intent
            // (MainActivity). community_id disertakan untuk routing di masa depan.
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (intent == null) return false;
            intent.setAction(Intent.ACTION_VIEW);
            intent.putExtra("community_id", personKey);

            Person person = new Person.Builder()
                    .setName(label)
                    .setKey(personKey != null && !personKey.isEmpty() ? personKey : id)
                    .build();

            ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(context, id)
                    .setShortLabel(label)
                    .setLongLived(true)
                    .setPerson(person)
                    .setIcon(buildIcon(iconColor, iconPath))
                    .setIntent(intent)
                    .setCategories(Collections.singleton(CATEGORY_CONVERSATION))
                    .build();

            // pushDynamicShortcut menggeser shortcut ber-rank terendah saat batas
            // jumlah shortcut tercapai, jadi aman dipanggil tiap notif masuk.
            return ShortcutManagerCompat.pushDynamicShortcut(context, shortcut);
        } catch (Exception e) {
            Log.e(TAG, "push failed id=" + id, e);
            return false;
        }
    }

    /**
     * Ikon percakapan = ikon notif CUiT ({@code mipmap/ic_notif}) diwarnai putih
     * di atas latar warna brand. Dibuat adaptive (latar penuh, ikon di dalam
     * safe zone) supaya sistem memotongnya bulat, sama seperti avatar
     * percakapan lain. Tanpa warna / resource → jatuh ke ikon launcher.
     */
    private IconCompat buildIcon(Number iconColor, String iconPath) {
        IconCompat photo = buildPhotoIcon(iconPath);
        if (photo != null) return photo;

        int res = context.getResources().getIdentifier("ic_notif", "mipmap", context.getPackageName());
        Drawable glyph = res == 0 ? null : ContextCompat.getDrawable(context, res);
        if (iconColor == null || glyph == null) {
            return IconCompat.createWithResource(context, context.getApplicationInfo().icon);
        }

        // 108dp adaptive: safe zone 66dp di tengah; glyph dibuat ±44% supaya
        // lega di dalam lingkaran, seperti ikon kecil di header notif.
        final int size = 432;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(iconColor.intValue());

        int inset = Math.round(size * 0.28f);
        Drawable white = glyph.mutate();
        white.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        white.setBounds(inset, inset, size - inset, size - inset);
        white.draw(canvas);

        return IconCompat.createWithAdaptiveBitmap(bitmap);
    }

    /**
     * Foto (mis. foto ruang) sebagai avatar percakapan bulat. Adaptive icon
     * hanya menampilkan lingkaran tengah (72 dari 108dp), jadi foto digambar
     * "cover" DUA kali: memenuhi kanvas (untuk mask launcher yang lebih besar)
     * lalu memenuhi kotak 72/108 di tengah — lingkaran yang terlihat memuat
     * seluruh foto, bukan hanya bagian tengahnya. {@code null} bila file tak
     * ada / gagal di-decode.
     */
    private IconCompat buildPhotoIcon(String iconPath) {
        if (iconPath == null || iconPath.isEmpty()) return null;
        try {
            Bitmap source = decodeSampled(iconPath, 432);
            if (source == null) return null;

            final int size = 432;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

            drawCover(canvas, source, new RectF(0, 0, size, size), paint);
            float inset = size * (18f / 108f);
            drawCover(canvas, source, new RectF(inset, inset, size - inset, size - inset), paint);

            source.recycle();
            return IconCompat.createWithAdaptiveBitmap(bitmap);
        } catch (Exception e) {
            Log.e(TAG, "buildPhotoIcon failed path=" + iconPath, e);
            return null;
        }
    }

    /**
     * Decode [path] dengan inSampleSize supaya sisi terpendeknya tak jauh di
     * atas [targetSize]. Foto ruang/profil bisa ribuan piksel (4000px ≈ 64 MB
     * sebagai bitmap penuh) dan proses ini jalan di isolate background FCM
     * yang gampang dibunuh sistem saat kehabisan memori. {@code null} bila
     * file bukan gambar.
     */
    private static Bitmap decodeSampled(String path, int targetSize) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        int shortest = Math.min(bounds.outWidth, bounds.outHeight);
        while (shortest / (sample * 2) >= targetSize) sample *= 2;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, options);
    }

    /** Gambar [source] memenuhi [target] (center-crop, rasio dijaga). */
    private static void drawCover(Canvas canvas, Bitmap source, RectF target, Paint paint) {
        float scale = Math.max(target.width() / source.getWidth(), target.height() / source.getHeight());
        float cropW = target.width() / scale;
        float cropH = target.height() / scale;
        float left = (source.getWidth() - cropW) / 2f;
        float top = (source.getHeight() - cropH) / 2f;
        Rect crop = new Rect(Math.round(left), Math.round(top), Math.round(left + cropW), Math.round(top + cropH));
        canvas.drawBitmap(source, crop, target, paint);
    }

    /**
     * Porsi lingkaran avatar pengirim terhadap slot ikon pesan. Slot ikon
     * Person di MessagingStyle ukurannya sama dengan ikon ruang dan HyperOS
     * tidak memotongnya bulat, jadi avatar dibuat bulat SENDIRI dan dikecilkan
     * di tengah kanvas transparan supaya terbaca lebih kecil dari foto ruang.
     */
    private static final float AVATAR_SCALE = 0.72f;

    /**
     * Foto [path] → PNG bulat (center-crop, sisi transparan) di cache app.
     * Hasil di-cache per file sumber + waktu ubahnya, jadi pesan berikutnya dari
     * pengirim yang sama tidak memproses ulang. {@code null} bila gagal.
     */
    private String roundAvatar(String path) {
        if (context == null || path == null || path.isEmpty()) return null;
        try {
            File source = new File(path);
            if (!source.exists()) return null;

            File dir = new File(context.getCacheDir(), "conversation_avatars");
            if (!dir.exists() && !dir.mkdirs()) return null;
            String name = Integer.toHexString((path + ":" + source.lastModified() + ":" + AVATAR_SCALE).hashCode()) + ".png";
            File out = new File(dir, name);
            if (out.exists() && out.length() > 0) return out.getAbsolutePath();

            Bitmap photo = decodeSampled(path, 192);
            if (photo == null) return null;

            final int size = 192;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

            float diameter = size * AVATAR_SCALE;
            float inset = (size - diameter) / 2f;
            RectF circle = new RectF(inset, inset, inset + diameter, inset + diameter);

            // Topeng lingkaran lalu foto di-SRC_IN ke dalamnya → tepi halus.
            canvas.drawOval(circle, paint);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            drawCover(canvas, photo, circle, paint);
            paint.setXfermode(null);
            photo.recycle();

            try (FileOutputStream stream = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            bitmap.recycle();
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "roundAvatar failed path=" + path, e);
            return null;
        }
    }

    private void removeAll() {
        if (context == null) return;
        try {
            // Id dikumpulkan DULU: shortcut long-lived tetap tersimpan sebagai
            // "cached" walau dynamic-nya sudah dihapus, jadi keduanya dicari
            // sebelum apa pun dihapus.
            List<String> ids = new ArrayList<>();
            for (ShortcutInfoCompat info : ShortcutManagerCompat.getShortcuts(
                    context,
                    ShortcutManagerCompat.FLAG_MATCH_DYNAMIC | ShortcutManagerCompat.FLAG_MATCH_CACHED)) {
                ids.add(info.getId());
            }
            ShortcutManagerCompat.removeAllDynamicShortcuts(context);
            if (!ids.isEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids);
        } catch (Exception e) {
            Log.e(TAG, "removeAll failed", e);
        }
    }
}
