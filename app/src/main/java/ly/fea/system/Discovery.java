package ly.fea.system;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** البحث عن سيرفر المنظومة على شبكة الـ Wi-Fi: العنوان المحفوظ ← نداء بث UDP ← فحص أجهزة الشبكة. */
final class Discovery {
    static final int UDP_PORT = 8767;
    static final int DEFAULT_HTTP = 8765;

    static final class Server {
        String host; int http; int https; String name;
        String baseUrl() { return https > 0 ? "https://" + host + ":" + https + "/" : "http://" + host + ":" + http + "/"; }
    }

    /** شبكة الـ Wi-Fi الحالية (أو null إن لم يكن الجهاز متصلاً بـ Wi-Fi). */
    static Network wifiNetwork(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return n;
        }
        return null;
    }

    /** عنوان الجهاز على الـ Wi-Fi وطول البادئة (مثل 192.168.1.23/24). */
    static int[] localAddress(Context ctx, Network n) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties lp = cm.getLinkProperties(n);
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                byte[] b = a.getAddress();
                int ip = ((b[0] & 255) << 24) | ((b[1] & 255) << 16) | ((b[2] & 255) << 8) | (b[3] & 255);
                return new int[]{ip, la.getPrefixLength()};
            }
        }
        return null;
    }

    static String ipString(int ip) { return ((ip >>> 24) & 255) + "." + ((ip >>> 16) & 255) + "." + ((ip >>> 8) & 255) + "." + (ip & 255); }

    static Server parse(String host, String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"FEA".equals(o.optString("app"))) return null;
            Server s = new Server();
            s.host = host; s.http = o.optInt("http", DEFAULT_HTTP); s.https = o.optInt("https", 0); s.name = o.optString("name", "");
            return s;
        } catch (Exception e) { return null; }
    }

    static String httpGet(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs); c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return new String(readAll(in, 64 * 1024), StandardCharsets.UTF_8); }
        } finally { c.disconnect(); }
    }

    static byte[] httpGetBytes(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs);
        try {
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return readAll(in, 256 * 1024); }
        } finally { c.disconnect(); }
    }

    static byte[] readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int r;
        while ((r = in.read(buf)) > 0) { o.write(buf, 0, r); if (o.size() > max) break; }
        return o.toByteArray();
    }

    /** التحقق من سيرفر بعنوان معروف. */
    static Server hello(String host, int port, int timeoutMs) {
        try { String j = httpGet("http://" + host + ":" + port + "/api/hello", timeoutMs); return j == null ? null : parse(host, j); }
        catch (Exception e) { return null; }
    }

    /** نداء بث UDP: السيرفر يرد بعنوانه ومنافذه. */
    static Server udp(Context ctx, Network n, int timeoutMs) {
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        try {
            if (wm != null) { lock = wm.createMulticastLock("fea-discovery"); lock.setReferenceCounted(false); lock.acquire(); }
            try (DatagramSocket s = new DatagramSocket()) {
                if (n != null) n.bindSocket(s);
                s.setBroadcast(true); s.setSoTimeout(400);
                byte[] msg = "FEA_DISCOVER".getBytes(StandardCharsets.US_ASCII);
                List<InetAddress> targets = new ArrayList<>();
                targets.add(InetAddress.getByName("255.255.255.255"));
                int[] la = localAddress(ctx, n);
                if (la != null && la[1] > 0 && la[1] < 31) {
                    int mask = la[1] == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - la[1]));
                    targets.add(InetAddress.getByName(ipString(la[0] | ~mask)));
                }
                long end = System.currentTimeMillis() + timeoutMs;
                byte[] buf = new byte[1024];
                int round = 0;
                while (System.currentTimeMillis() < end) {
                    if (round++ % 3 == 0) for (InetAddress t : targets) {
                        try { s.send(new DatagramPacket(msg, msg.length, t, UDP_PORT)); } catch (Exception ignored) { }
                    }
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        Server sv = parse(p.getAddress().getHostAddress(), new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                        if (sv != null) return sv;
                    } catch (java.net.SocketTimeoutException ignored) { }
                }
            }
        } catch (Exception ignored) {
        } finally { if (lock != null && lock.isHeld()) lock.release(); }
        return null;
    }

    /** فحص كل أجهزة الشبكة (حتى /22) على منفذ المنظومة. */
    static Server scan(Context ctx, Network n, int port) {
        int[] la = localAddress(ctx, n);
        if (la == null) return null;
        int prefix = Math.max(la[1], 22);
        int mask = (int) (0xFFFFFFFFL << (32 - prefix));
        int net = la[0] & mask, count = (1 << (32 - prefix)) - 2;
        ExecutorService pool = Executors.newFixedThreadPool(48);
        ExecutorCompletionService<Server> ecs = new ExecutorCompletionService<>(pool);
        List<Future<Server>> fs = new ArrayList<>();
        try {
            for (int i = 1; i <= count; i++) {
                final String host = ipString(net + i);
                if (net + i == la[0]) continue;
                fs.add(ecs.submit(() -> hello(host, port, 900)));
            }
            for (int i = 0; i < fs.size(); i++) {
                Future<Server> f = ecs.poll(20, TimeUnit.SECONDS);
                if (f == null) break;
                Server s = f.get();
                if (s != null) return s;
            }
        } catch (Exception ignored) {
        } finally { pool.shutdownNow(); }
        return null;
    }
}
