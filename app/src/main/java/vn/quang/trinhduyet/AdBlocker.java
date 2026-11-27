package vn.quang.trinhduyet;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Danh sách tên miền quảng cáo + mẫu đường dẫn quảng cáo. */
public final class AdBlocker {

    public static final String LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";

    /** Mạng quảng cáo hay gặp (kể cả ở Việt Nam), thêm vào danh sách tải về. */
    private static final String[] EXTRA = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
            "adservice.google.com", "adservice.google.com.vn", "pagead2.googlesyndication.com", "imasdk.googleapis.com",
            "ads.youtube.com", "admicro.vn", "adi.admicro.vn", "media1.admicro.vn", "lg1.logging.admicro.vn",
            "eclick.vn", "adtima.vn", "adtimaserver.vn", "yomedia.vn", "ambientplatform.vn", "adnetwork.vn",
            "novanet.vn", "adpia.vn", "mgid.com", "taboola.com", "outbrain.com", "revcontent.com",
            "popads.net", "popcash.net", "propellerads.com", "onclickads.net", "onclkds.com", "adsterra.com",
            "adsterratools.com", "exoclick.com", "exosrv.com", "juicyads.com", "hilltopads.net", "hilltopads.com",
            "a-ads.com", "adcash.com", "clickadu.com", "trafficjunky.com", "trafficstars.com", "tsyndicate.com",
            "pushame.com", "richpush.co", "evadav.com", "monetag.com", "galaksion.com", "admaven.com",
            "ad-maven.com", "highperformanceformat.com", "profitablecpmrate.com", "profitablegatecpm.com",
            "effectiveratecpm.com", "displayvertising.com", "adskeeper.com", "adnxs.com", "adsrvr.org",
            "criteo.com", "criteo.net", "amazon-adsystem.com", "moatads.com", "rubiconproject.com",
            "pubmatic.com", "openx.net", "smartadserver.com", "yieldmo.com", "adform.net", "zedo.com",
            "unityads.unity3d.com", "applovin.com", "vungle.com", "inmobi.com", "startappservice.com"
    };

    /** Không bao giờ chặn những tên miền này (để web chính vẫn chạy). */
    private static final String[] NEVER = {
            "youtube.com", "www.youtube.com", "m.youtube.com", "youtubei.googleapis.com", "googlevideo.com",
            "ytimg.com", "i.ytimg.com", "s.ytimg.com", "ggpht.com", "google.com", "www.google.com",
            "google.com.vn", "www.google.com.vn", "gstatic.com", "www.gstatic.com", "googleapis.com",
            "accounts.google.com", "facebook.com", "m.facebook.com", "www.facebook.com", "fbcdn.net",
            "messenger.com", "zalo.me", "chat.zalo.me", "tiktok.com", "www.tiktok.com", "shopee.vn",
            "bachhoaxanh.com", "www.bachhoaxanh.com", "github.com", "raw.githubusercontent.com"
    };

    /** Mẫu đường dẫn quảng cáo (kể cả quảng cáo video). */
    private static final String[] URL_PATTERNS = {
            "/pagead/", "/api/stats/ads", "/get_midroll_", "/ptracking", "googleads.", "doubleclick.",
            "/vast.xml", "/vast/", "vast?", "/vpaid", "/vmap", "prebid", "/adserver", "/adframe",
            "/ad_banner", "/ads/banner", "/popunder", "/popads", "/afs/ads"
    };

    private static volatile Set<String> hosts = new HashSet<>();
    private static final Set<String> never = new HashSet<>();

    static {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, EXTRA);
        hosts = s;
        Collections.addAll(never, NEVER);
    }

    private AdBlocker() {}

    private static File localFile(Context c) {
        return new File(c.getFilesDir(), "hosts.txt");
    }

    /** Nạp danh sách (gọi ở luồng nền). */
    public static int load(Context c) {
        Set<String> s = new HashSet<>(120000);
        Collections.addAll(s, EXTRA);
        File f = localFile(c);
        try {
            InputStream in = (f.exists() && f.length() > 100000) ? new FileInputStream(f) : c.getAssets().open("hosts.txt");
            readList(in, s);
        } catch (Exception e) {
            try { readList(c.getAssets().open("hosts.txt"), s); } catch (Exception ignored) {}
        }
        hosts = s;
        return s.size();
    }

    private static void readList(InputStream in, Set<String> out) throws Exception {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 65536)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                // chấp nhận cả dạng "0.0.0.0 tenmien" lẫn "tenmien"
                int sp = line.indexOf(' ');
                if (sp < 0) sp = line.indexOf('\t');
                if (sp > 0) {
                    String first = line.substring(0, sp);
                    if (first.equals("0.0.0.0") || first.equals("127.0.0.1")) line = line.substring(sp).trim();
                    else continue;
                    int cm = line.indexOf('#');
                    if (cm >= 0) line = line.substring(0, cm).trim();
                    int sp2 = line.indexOf(' ');
                    if (sp2 > 0) line = line.substring(0, sp2);
                }
                if (line.isEmpty() || line.equals("0.0.0.0") || line.equals("localhost")) continue;
                out.add(line.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** Tải danh sách mới nhất từ mạng (gọi ở luồng nền). Trả về số tên miền, hoặc -1 nếu lỗi. */
    public static int update(Context c) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(LIST_URL).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            if (con.getResponseCode() != 200) return -1;
            Set<String> s = new HashSet<>(120000);
            readList(con.getInputStream(), s);
            if (s.size() < 20000) return -1;
            File tmp = new File(c.getFilesDir(), "hosts.tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                for (String h : s) { w.write(h); w.write('\n'); }
            }
            File dst = localFile(c);
            if (!tmp.renameTo(dst)) return -1;
            Collections.addAll(s, EXTRA);
            hosts = s;
            return s.size();
        } catch (Exception e) {
            return -1;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    public static boolean isAdHost(String host) {
        if (host == null || host.isEmpty()) return false;
        host = host.toLowerCase(Locale.ROOT);
        Set<String> set = hosts;
        String h = host;
        while (true) {
            if (set.contains(h)) return true;
            if (never.contains(h)) return false;
            int dot = h.indexOf('.');
            if (dot < 0 || dot == h.length() - 1) return false;
            h = h.substring(dot + 1);
            if (h.indexOf('.') < 0) return false; // không xét riêng đuôi .com, .vn
        }
    }

    public static boolean isAd(Uri u) {
        if (u == null) return false;
        if (isAdHost(u.getHost())) return true;
        String s = u.toString().toLowerCase(Locale.ROOT);
        for (String p : URL_PATTERNS) {
            if (s.contains(p)) return true;
        }
        return false;
    }
}
