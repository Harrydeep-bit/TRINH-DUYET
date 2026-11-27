package vn.quang.trinhduyet;

import android.net.Uri;
import android.webkit.CookieManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Tải dữ liệu kèm Referer/Cookie giống trình duyệt. */
final class Net {
    private Net() {}

    static String origin(String ref) {
        try {
            Uri u = Uri.parse(ref);
            if (u.getScheme() == null || u.getHost() == null) return null;
            return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception e) {
            return null;
        }
    }

    static HttpURLConnection open(String url, String ref, String ua, String range) throws IOException {
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            if (ref != null && !ref.startsWith("file:")) {
                c.setRequestProperty("Referer", ref);
                String o = origin(ref);
                if (o != null) c.setRequestProperty("Origin", o);
            }
            c.setRequestProperty("Accept", "*/*");
            try {
                String ck = CookieManager.getInstance().getCookie(cur);
                if (ck != null) c.setRequestProperty("Cookie", ck);
            } catch (Exception ignored) {}
            if (range != null) c.setRequestProperty("Range", range);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Chuyển hướng lỗi");
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            if (code >= 400) {
                c.disconnect();
                throw new IOException("Máy chủ trả lỗi " + code);
            }
            return c;
        }
        throw new IOException("Chuyển hướng quá nhiều lần");
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[32768];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        return out.toByteArray();
    }

    static byte[] get(String url, String ref, String ua, String range) throws IOException {
        HttpURLConnection c = open(url, ref, ua, range);
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    static String text(String url, String ref, String ua) throws IOException {
        return new String(get(url, ref, ua, null), StandardCharsets.UTF_8);
    }

    /** Dung lượng tệp (byte), -1 nếu không biết. */
    static long size(String url, String ref, String ua) {
        HttpURLConnection c = null;
        try {
            c = open(url, ref, ua, "bytes=0-0");
            String cr = c.getHeaderField("Content-Range");
            if (cr != null && cr.contains("/")) {
                String t = cr.substring(cr.lastIndexOf('/') + 1).trim();
                if (!t.equals("*")) return Long.parseLong(t);
            }
            if (c.getResponseCode() == 200) return c.getContentLengthLong();
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return -1;
    }

    static String resolve(String base, String rel) {
        try {
            return new URL(new URL(base), rel.trim()).toString();
        } catch (Exception e) {
            return rel;
        }
    }

    static String attr(String line, String name) {
        String key = name + "=";
        int i = line.indexOf(key);
        while (i > 0 && line.charAt(i - 1) != ',' && line.charAt(i - 1) != ':') i = line.indexOf(key, i + 1);
        if (i < 0) return null;
        i += key.length();
        if (i < line.length() && line.charAt(i) == '"') {
            int e = line.indexOf('"', i + 1);
            return e < 0 ? line.substring(i + 1) : line.substring(i + 1, e);
        }
        int e = line.indexOf(',', i);
        return e < 0 ? line.substring(i).trim() : line.substring(i, e).trim();
    }

    /** Một chất lượng trong danh sách HLS chính. */
    static final class Variant {
        String url;
        long bandwidth;
        String resolution;

        String label() {
            String r = "";
            if (resolution != null && resolution.contains("x")) {
                r = resolution.substring(resolution.indexOf('x') + 1) + "p";
            }
            String bw = bandwidth > 0 ? String.format(Locale.US, "%.1f Mbps", bandwidth / 1e6) : "";
            if (r.isEmpty()) return bw.isEmpty() ? "Chất lượng mặc định" : bw;
            return bw.isEmpty() ? r : r + "  ·  " + bw;
        }
    }

    static List<Variant> variants(String playlist, String base) {
        List<Variant> out = new ArrayList<>();
        String[] lines = playlist.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
            Variant v = new Variant();
            String bw = attr(l, "BANDWIDTH");
            try { v.bandwidth = bw == null ? 0 : Long.parseLong(bw); } catch (Exception ignored) {}
            v.resolution = attr(l, "RESOLUTION");
            for (int j = i + 1; j < lines.length; j++) {
                String n = lines[j].trim();
                if (n.isEmpty() || n.startsWith("#")) continue;
                v.url = resolve(base, n);
                break;
            }
            if (v.url != null) out.add(v);
        }
        out.sort((a, b) -> Long.compare(b.bandwidth, a.bandwidth));
        return out;
    }
}
