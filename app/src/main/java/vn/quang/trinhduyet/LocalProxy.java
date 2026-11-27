package vn.quang.trinhduyet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Máy chủ proxy chạy ngay trong điện thoại (127.0.0.1).
 * Tra tên miền bằng DNS mã hoá (Cloudflare/Google) thay vì DNS của nhà mạng,
 * nhờ đó vào được các trang bị nhà mạng chặn bằng DNS.
 */
final class LocalProxy {

    private static final String[] DOH = {
            "https://1.1.1.1/dns-query?type=A&name=",
            "https://1.0.0.1/dns-query?type=A&name=",
            "https://8.8.8.8/resolve?type=A&name=",
            "https://8.8.4.4/resolve?type=A&name="
    };

    private static final class Entry {
        final InetAddress addr;
        final long until;
        Entry(InetAddress a, long u) { addr = a; until = u; }
    }

    private static final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private static ServerSocket server;
    private static ExecutorService pool;

    private LocalProxy() {}

    /** Khởi động (nếu chưa chạy) và trả về cổng. */
    static synchronized int start() throws IOException {
        if (server != null && !server.isClosed()) return server.getLocalPort();
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 128);
        pool = Executors.newCachedThreadPool();
        final ServerSocket ss = server;
        final ExecutorService p = pool;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket c = ss.accept();
                    p.execute(() -> handle(c));
                } catch (IOException e) {
                    if (ss.isClosed()) break;
                }
            }
        }, "local-proxy");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    static synchronized void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        server = null;
        if (pool != null) pool.shutdownNow();
        pool = null;
    }

    static synchronized int port() {
        return server == null || server.isClosed() ? -1 : server.getLocalPort();
    }

    // ------------------------------------------------------------------ DNS mã hoá

    static boolean isIp(String h) {
        return h.matches("\\d{1,3}(\\.\\d{1,3}){3}") || h.contains(":");
    }

    static InetAddress resolve(String host) throws IOException {
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (isIp(host) || host.equals("localhost")) return InetAddress.getByName(host);
        Entry e = cache.get(host);
        long now = System.currentTimeMillis();
        if (e != null && e.until > now) return e.addr;
        String name = host;
        for (String base : DOH) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base + URLEncoder.encode(name, "UTF-8")).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("Accept", "application/dns-json");
                if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                String body;
                try (InputStream in = c.getInputStream()) { body = new String(Net.readAll(in), StandardCharsets.UTF_8); }
                c.disconnect();
                JSONArray ans = new JSONObject(body).optJSONArray("Answer");
                if (ans == null) continue;
                for (int i = 0; i < ans.length(); i++) {
                    JSONObject a = ans.getJSONObject(i);
                    if (a.optInt("type") == 1) {
                        InetAddress ip = InetAddress.getByName(a.getString("data"));
                        long ttl = Math.max(60, Math.min(a.optLong("TTL", 300), 3600));
                        cache.put(host, new Entry(ip, now + ttl * 1000));
                        return ip;
                    }
                }
            } catch (Exception ignored) {}
        }
        // không tra được bằng DNS mã hoá thì dùng DNS thường
        InetAddress ip = InetAddress.getByName(host);
        cache.put(host, new Entry(ip, now + 120000));
        return ip;
    }

    // ------------------------------------------------------------------ xử lý kết nối

    private static void handle(Socket client) {
        Socket remote = null;
        try {
            client.setSoTimeout(60000);
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();
            byte[] head = readHead(cin);
            if (head == null) { client.close(); return; }
            String h = new String(head, StandardCharsets.ISO_8859_1);
            int eol = h.indexOf("\r\n");
            String first = eol < 0 ? h : h.substring(0, eol);
            String[] parts = first.split(" ");
            if (parts.length < 3) { client.close(); return; }
            String method = parts[0].toUpperCase(Locale.ROOT);

            if (method.equals("CONNECT")) {
                String hp = parts[1];
                int colon = hp.lastIndexOf(':');
                String host = colon > 0 ? hp.substring(0, colon) : hp;
                int port = colon > 0 ? Integer.parseInt(hp.substring(colon + 1)) : 443;
                remote = connect(host, port);
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                cout.flush();
                sendFirstTls(cin, remote.getOutputStream(), host);
                pipe(client, remote);
                return;
            }

            // HTTP thường: "GET http://host:port/path HTTP/1.1"
            URL u = new URL(parts[1]);
            int port = u.getPort() > 0 ? u.getPort() : 80;
            String path = u.getFile().isEmpty() ? "/" : u.getFile();
            StringBuilder sb = new StringBuilder();
            sb.append(parts[0]).append(' ').append(path).append(' ').append(parts[2]).append("\r\n");
            String[] lines = h.substring(eol + 2).split("\r\n");
            for (String l : lines) {
                if (l.isEmpty()) continue;
                String low = l.toLowerCase(Locale.ROOT);
                if (low.startsWith("proxy-connection:") || low.startsWith("connection:") || low.startsWith("keep-alive:")) continue;
                sb.append(l).append("\r\n");
            }
            sb.append("Connection: close\r\n\r\n");
            remote = connect(u.getHost(), port);
            OutputStream rout = remote.getOutputStream();
            byte[] req = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
            int hi = sb.indexOf("\r\nHost:");
            int cut = hi < 0 ? 2 : hi + 9;
            rout.write(req, 0, cut);           // tách dòng Host để bộ lọc không đọc được
            rout.flush();
            sleep(5);
            rout.write(req, cut, req.length - cut);
            rout.flush();
            pipe(client, remote);
        } catch (Exception e) {
            try { client.close(); } catch (IOException ignored) {}
            try { if (remote != null) remote.close(); } catch (IOException ignored) {}
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) return false;
            off += r;
        }
        return true;
    }

    /**
     * Gói mở kết nối HTTPS (ClientHello) chứa tên trang ở dạng chữ thường.
     * Nhà mạng đọc tên này để chặn. Ta cắt gói thành 2 bản ghi TLS và gửi
     * thành nhiều đoạn TCP nhỏ, cắt ngay giữa tên trang, để bộ lọc không nhận ra.
     */
    private static void sendFirstTls(InputStream cin, OutputStream rout, String host) throws IOException {
        byte[] hdr = new byte[5];
        if (!readFully(cin, hdr)) throw new IOException("đóng");
        if (hdr[0] != 0x16 || hdr[1] != 0x03) {
            rout.write(hdr);
            rout.flush();
            return;
        }
        int len = ((hdr[3] & 0xff) << 8) | (hdr[4] & 0xff);
        byte[] body = new byte[len];
        if (!readFully(cin, body)) throw new IOException("đóng");
        byte[] hb = host.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.ISO_8859_1);
        int at = indexOf(body, hb);
        int cut = at > 0 ? at + Math.max(1, hb.length / 2) : Math.min(len - 1, 32);
        if (cut <= 0 || cut >= len) {
            rout.write(hdr);
            rout.write(body);
            rout.flush();
            return;
        }
        byte[] r1 = new byte[5 + cut];
        r1[0] = 0x16; r1[1] = hdr[1]; r1[2] = hdr[2];
        r1[3] = (byte) (cut >> 8); r1[4] = (byte) cut;
        System.arraycopy(body, 0, r1, 5, cut);
        int rest = len - cut;
        byte[] r2 = new byte[5 + rest];
        r2[0] = 0x16; r2[1] = hdr[1]; r2[2] = hdr[2];
        r2[3] = (byte) (rest >> 8); r2[4] = (byte) rest;
        System.arraycopy(body, cut, r2, 5, rest);

        rout.write(r1, 0, 3);
        rout.flush();
        sleep(5);
        rout.write(r1, 3, r1.length - 3);
        rout.flush();
        sleep(5);
        rout.write(r2);
        rout.flush();
    }

    private static int indexOf(byte[] a, byte[] b) {
        outer:
        for (int i = 0; i <= a.length - b.length; i++) {
            for (int j = 0; j < b.length; j++) if (a[i + j] != b[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static Socket connect(String host, int port) throws IOException {
        InetAddress ip = resolve(host);
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(ip, port), 15000);
        return s;
    }

    /** Đọc phần đầu yêu cầu đến hết dòng trống. Không đọc lố sang phần thân. */
    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(1024);
        int state = 0;
        while (b.size() < 65536) {
            int c = in.read();
            if (c < 0) return null;
            b.write(c);
            if (c == '\r' && (state == 0 || state == 2)) state++;
            else if (c == '\n' && (state == 1 || state == 3)) state++;
            else state = 0;
            if (state == 4) return b.toByteArray();
        }
        return null;
    }

    private static void pipe(Socket a, Socket b) throws IOException {
        a.setSoTimeout(0);
        b.setSoTimeout(0);
        Thread t = new Thread(() -> copy(b, a), "proxy-down");
        t.setDaemon(true);
        t.start();
        copy(a, b);
    }

    private static void copy(Socket from, Socket to) {
        byte[] buf = new byte[32768];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try { from.close(); } catch (IOException ignored) {}
            try { to.close(); } catch (IOException ignored) {}
        }
    }
}
