package vn.quang.trinhduyet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Tải video dạng HLS (.m3u8): tải từng đoạn rồi ghép lại thành một tệp. */
public class DlService extends Service {

    static final String CH_RUN = "dl_run";
    static final String CH_DONE = "dl_done";
    static final String ACT_CANCEL = "vn.quang.trinhduyet.CANCEL";
    static final int ID_RUN = 7001;

    static final class Job {
        String url, name, ref, ua;
    }

    static final class Seg {
        String url;
        String keyUrl;
        byte[] iv;
        long seq;
        long len = -1, off = -1;
    }

    private final LinkedBlockingQueue<Job> queue = new LinkedBlockingQueue<>();
    private volatile boolean cancel;
    private volatile Job running;
    private Thread worker;
    private NotificationManager nm;
    private final AtomicInteger doneId = new AtomicInteger(7100);

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_RUN, "Đang tải video", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, "Tải video xong", NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && ACT_CANCEL.equals(i.getAction())) {
            cancel = true;
            queue.clear();
            return START_NOT_STICKY;
        }
        Notification n = progressNote("Chuẩn bị tải…", 0, 0);
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID_RUN, n);
        if (i != null && i.getStringExtra("url") != null) {
            Job j = new Job();
            j.url = i.getStringExtra("url");
            j.name = i.getStringExtra("name");
            j.ref = i.getStringExtra("ref");
            j.ua = i.getStringExtra("ua");
            queue.add(j);
        }
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::loop, "hls");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    private void loop() {
        Job j;
        while ((j = queue.poll()) != null) {
            cancel = false;
            running = j;
            try {
                Uri out = runJob(j);
                doneNote(j.name, "Đã tải xong — chạm để xem", out);
            } catch (Exception e) {
                doneNote(j.name, cancel ? "Đã huỷ tải" : "Tải lỗi: " + e.getMessage(), null);
            }
            running = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ------------------------------------------------------------------ thông báo

    private Notification progressNote(String text, int done, int total) {
        Intent ci = new Intent(this, DlService.class).setAction(ACT_CANCEL);
        PendingIntent cp = PendingIntent.getService(this, 1, ci, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CH_RUN)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(running != null ? running.name : "Tải video")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Huỷ", cp).build());
        if (total > 0) b.setProgress(total, done, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void doneNote(String name, String text, Uri file) {
        Notification.Builder b = new Notification.Builder(this, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(name)
                .setContentText(text)
                .setAutoCancel(true);
        if (file != null) {
            Intent v = new Intent(Intent.ACTION_VIEW);
            v.setDataAndType(file, name.endsWith(".mp4") ? "video/mp4" : "video/mp2t");
            v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            b.setContentIntent(PendingIntent.getActivity(this, doneId.get(), v,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        try { nm.notify(doneId.incrementAndGet(), b.build()); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ tải HLS

    private Uri runJob(Job j) throws Exception {
        String url = j.url;
        String pl = Net.text(url, j.ref, j.ua);
        if (!pl.contains("#EXTM3U")) throw new IOException("Không phải danh sách phát video");
        if (pl.contains("#EXT-X-STREAM-INF")) {
            List<Net.Variant> vs = Net.variants(pl, url);
            if (vs.isEmpty()) throw new IOException("Không tìm thấy chất lượng video");
            url = vs.get(0).url;
            pl = Net.text(url, j.ref, j.ua);
        }
        if (pl.contains("METHOD=SAMPLE-AES") || pl.contains("KEYFORMAT=\"com.") || pl.contains("urn:uuid:")) {
            throw new IOException("Video có bảo vệ bản quyền (DRM), không tải được");
        }
        if (!pl.contains("#EXT-X-ENDLIST") && !pl.contains("#EXT-X-PLAYLIST-TYPE:VOD")) {
            throw new IOException("Đây là video phát trực tiếp, không tải được");
        }

        // phân tích các đoạn
        List<Seg> segs = new ArrayList<>();
        String mapUrl = null;
        long mapLen = -1, mapOff = -1;
        String keyUrl = null;
        byte[] keyIv = null;
        long seq = 0;
        long nextOff = 0;
        long pendLen = -1, pendOff = -1;
        boolean pendingSeg = false;
        for (String raw : pl.split("\r?\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try { seq = Long.parseLong(l.substring(22).trim()); } catch (Exception ignored) {}
            } else if (l.startsWith("#EXT-X-KEY:")) {
                String m = Net.attr(l, "METHOD");
                if (m == null || m.equals("NONE")) { keyUrl = null; keyIv = null; }
                else if (m.equals("AES-128")) {
                    keyUrl = Net.resolve(url, Net.attr(l, "URI"));
                    String iv = Net.attr(l, "IV");
                    keyIv = iv == null ? null : hex(iv);
                } else throw new IOException("Kiểu mã hoá " + m + " không hỗ trợ");
            } else if (l.startsWith("#EXT-X-MAP:")) {
                mapUrl = Net.resolve(url, Net.attr(l, "URI"));
                String br = Net.attr(l, "BYTERANGE");
                if (br != null) {
                    long[] r = byteRange(br, 0);
                    mapLen = r[0];
                    mapOff = r[1];
                }
            } else if (l.startsWith("#EXT-X-BYTERANGE:")) {
                long[] r = byteRange(l.substring(17), nextOff);
                pendLen = r[0];
                pendOff = r[1];
                nextOff = r[0] + r[1];
            } else if (l.startsWith("#EXTINF")) {
                pendingSeg = true;
            } else if (!l.startsWith("#") && pendingSeg) {
                Seg s = new Seg();
                s.url = Net.resolve(url, l);
                s.keyUrl = keyUrl;
                s.iv = keyIv;
                s.seq = seq++;
                s.len = pendLen;
                s.off = pendOff;
                segs.add(s);
                pendLen = -1;
                pendOff = -1;
                pendingSeg = false;
            }
        }
        if (segs.isEmpty()) throw new IOException("Danh sách phát trống");
        boolean fmp4 = mapUrl != null || segs.get(0).url.toLowerCase().matches(".*\\.(m4s|mp4)(\\?.*)?$");
        if (!j.name.contains(".")) j.name += fmp4 ? ".mp4" : ".ts";
        else if (fmp4 && j.name.endsWith(".ts")) j.name = j.name.substring(0, j.name.length() - 3) + ".mp4";

        // tải các đoạn song song vào thư mục tạm
        File dir = new File(getCacheDir(), "hls_" + System.currentTimeMillis());
        if (!dir.mkdirs()) throw new IOException("Không tạo được thư mục tạm");
        final Map<String, byte[]> keys = new HashMap<>();
        final int total = segs.size();
        final AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                final int idx = i;
                final Seg s = segs.get(i);
                final boolean isTs = !fmp4;
                fs.add(pool.submit(() -> {
                    if (cancel) throw new IOException("huỷ");
                    byte[] data = fetchSeg(s, j, keys);
                    if (isTs) data = stripToTs(data);
                    try (FileOutputStream fo = new FileOutputStream(new File(dir, idx + ".seg"))) {
                        fo.write(data);
                    }
                    int d = done.incrementAndGet();
                    if (d % 3 == 0 || d == total) {
                        nm.notify(ID_RUN, progressNote("Đã tải " + d + "/" + total + " đoạn (" + (d * 100 / total) + "%)", d, total));
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                if (cancel) break;
                f.get();
            }
            if (cancel) throw new IOException("huỷ");

            nm.notify(ID_RUN, progressNote("Đang ghép tệp…", total, total));
            byte[] init = null;
            if (mapUrl != null) {
                Seg ms = new Seg();
                ms.url = mapUrl;
                ms.len = mapLen;
                ms.off = mapOff;
                init = fetchSeg(ms, j, keys);
            }
            return writeOut(j.name, fmp4, init, dir, total);
        } finally {
            pool.shutdownNow();
            File[] fl = dir.listFiles();
            if (fl != null) for (File f : fl) //noinspection ResultOfMethodCallIgnored
                f.delete();
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        }
    }

    private byte[] fetchSeg(Seg s, Job j, Map<String, byte[]> keys) throws Exception {
        String range = s.len > 0 ? "bytes=" + s.off + "-" + (s.off + s.len - 1) : null;
        byte[] data = null;
        Exception last = null;
        for (int a = 0; a < 4 && data == null; a++) {
            if (cancel) throw new IOException("huỷ");
            try {
                data = Net.get(s.url, j.ref, j.ua, range);
            } catch (Exception e) {
                last = e;
                Thread.sleep(800L * (a + 1));
            }
        }
        if (data == null) throw last != null ? last : new IOException("Lỗi tải đoạn");
        if (s.keyUrl != null) {
            byte[] key;
            synchronized (keys) {
                key = keys.get(s.keyUrl);
                if (key == null) {
                    key = Net.get(s.keyUrl, j.ref, j.ua, null);
                    keys.put(s.keyUrl, key);
                }
            }
            if (key.length != 16) throw new IOException("Khoá giải mã không hợp lệ");
            byte[] iv = s.iv;
            if (iv == null) {
                iv = new byte[16];
                long q = s.seq;
                for (int i = 15; i >= 8; i--) { iv[i] = (byte) (q & 0xFF); q >>= 8; }
            }
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            data = c.doFinal(data);
        }
        return data;
    }

    /** Một số trang giấu đoạn video sau phần đầu giả (ảnh PNG...). Cắt tới gói TS đầu tiên. */
    static byte[] stripToTs(byte[] d) {
        if (d.length > 376 && d[0] == 0x47 && d[188] == 0x47) return d;
        int lim = Math.min(d.length - 377, 1 << 20);
        for (int i = 0; i < lim; i++) {
            if (d[i] == 0x47 && d[i + 188] == 0x47 && d[i + 376] == 0x47) {
                byte[] o = new byte[d.length - i];
                System.arraycopy(d, i, o, 0, o.length);
                return o;
            }
        }
        return d;
    }

    private Uri writeOut(String name, boolean fmp4, byte[] init, File dir, int total) throws IOException {
        String mime = fmp4 ? "video/mp4" : "video/mp2t";
        Uri uri;
        OutputStream os;
        ContentResolver cr = getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TrinhDuyet");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("Không tạo được tệp trong Download");
            os = cr.openOutputStream(uri);
        } else {
            File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TrinhDuyet");
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            File f = new File(d, name);
            os = new FileOutputStream(f);
            uri = Uri.fromFile(f);
        }
        if (os == null) throw new IOException("Không ghi được tệp");
        byte[] buf = new byte[65536];
        try (OutputStream o = os) {
            if (init != null) o.write(init);
            for (int i = 0; i < total; i++) {
                if (cancel) throw new IOException("huỷ");
                try (InputStream in = new FileInputStream(new File(dir, i + ".seg"))) {
                    int r;
                    while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
                }
            }
        } catch (IOException e) {
            if (Build.VERSION.SDK_INT >= 29) try { cr.delete(uri, null, null); } catch (Exception ignored) {}
            throw e;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, cv, null, null);
        }
        return uri;
    }

    static long[] byteRange(String s, long defOff) {
        s = s.trim();
        int at = s.indexOf('@');
        long len = Long.parseLong(at < 0 ? s : s.substring(0, at));
        long off = at < 0 ? defOff : Long.parseLong(s.substring(at + 1));
        return new long[]{len, off};
    }

    static byte[] hex(String s) {
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        while (s.length() < 32) s = "0" + s;
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
