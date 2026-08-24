package com.drivehub.mgha.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.drivehub.mgha.R;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Arabada geçici web sayfası. Telefon aynı WiFi’de tarayıcıyla açıp
 * URL + token yapıştırır; telefona APK gerekmez.
 */
public final class ConfigWebServer {
    public static final int PORT = 18765;
    private static final String TAG = "MGHA_WEB";
    private static final int TIMEOUT_MS = 300_000;

    public interface Listener {
        void onReady(String openUrl);
        void onStatus(String msg);
        void onReceived(JSONObject cfg);
        void onFailed(String reason);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket server;
    private Context appCtx;

    public void stop() {
        running.set(false);
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {}
        server = null;
    }

    public void start(Context context, Listener listener) {
        stop();
        appCtx = context.getApplicationContext();
        running.set(true);
        Thread t = new Thread(() -> serveLoop(listener), "mgha-cfg-web");
        t.setDaemon(true);
        t.start();
    }

    private void serveLoop(Listener listener) {
        try {
            String ip = localIpv4();
            if (ip == null) {
                fail(listener, str(R.string.msg_no_wifi_ip));
                return;
            }
            String openUrl = "http://" + ip + ":" + PORT + "/";
            server = new ServerSocket(PORT);
            server.setSoTimeout(1000);
            main.post(() -> listener.onReady(openUrl));
            status(listener, str(R.string.msg_waiting_phone, openUrl));

            long end = System.currentTimeMillis() + TIMEOUT_MS;
            while (running.get() && System.currentTimeMillis() < end) {
                try {
                    Socket sock = server.accept();
                    handleClient(sock, listener);
                } catch (SocketTimeoutException ignored) {
                }
            }
            if (running.get()) {
                fail(listener, str(R.string.msg_web_timeout));
            }
        } catch (Exception e) {
            fail(listener, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            running.set(false);
            try {
                if (server != null) server.close();
            } catch (Exception ignored) {}
            server = null;
        }
    }

    private void handleClient(Socket sock, Listener listener) {
        try (Socket client = sock) {
            client.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            String headers = readHeaders(in);
            if (headers == null) {
                writeHttp(client, 400, "text/plain", "bad request");
                return;
            }
            String first = headers.split("\n")[0];
            if (first.startsWith("GET /")) {
                writeHttp(client, 200, "text/html; charset=utf-8", htmlPage());
                return;
            }
            if (first.startsWith("POST ")) {
                int len = contentLength(headers);
                if (len < 0 || len > 64_000) {
                    writeHttp(client, 400, "text/plain", "bad length");
                    return;
                }
                byte[] body = readFully(in, len);
                String raw = new String(body, StandardCharsets.UTF_8);
                JSONObject cfg = parseBody(raw);
                if (cfg == null) {
                    writeHttp(client, 400, "text/html; charset=utf-8",
                            resultPage(false, str(R.string.msg_web_need_fields)));
                    return;
                }
                writeHttp(client, 200, "text/html; charset=utf-8",
                        resultPage(true, str(R.string.msg_web_saved)));
                running.set(false);
                main.post(() -> listener.onReceived(cfg));
                return;
            }
            writeHttp(client, 404, "text/plain", "not found");
        } catch (Exception e) {
            Log.w(TAG, "client: " + e.getMessage());
        }
    }

    private String str(int resId) {
        return appCtx.getString(resId);
    }

    private String str(int resId, Object... args) {
        return appCtx.getString(resId, args);
    }

    private static JSONObject parseBody(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String s = raw.trim();
        try {
            if (s.startsWith("{")) {
                JSONObject o = new JSONObject(s);
                if (o.optString("url").trim().isEmpty() || o.optString("token").trim().isEmpty()) {
                    return null;
                }
                return o;
            }
            String url = formValue(s, "url");
            String token = formValue(s, "token");
            if (url.isEmpty() || token.isEmpty()) return null;
            JSONObject o = new JSONObject();
            o.put("url", url);
            o.put("token", token);
            String prefix = formValue(s, "prefix");
            if (!prefix.isEmpty()) o.put("prefix", prefix);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private static String formValue(String body, String key) {
        for (String part : body.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = decode(part.substring(0, eq));
            if (!key.equals(k)) continue;
            return decode(part.substring(eq + 1)).trim();
        }
        return "";
    }

    private static String decode(String v) {
        try {
            return URLDecoder.decode(v.replace("+", "%20"), StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return v;
        }
    }

    private String htmlPage() {
        return "<!DOCTYPE html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(str(R.string.web_title)) + "</title><style>"
                + "body{font-family:system-ui,sans-serif;background:#0D1B2A;color:#E8F4FD;"
                + "margin:0;padding:24px;max-width:480px}"
                + "h1{color:#03A9F4;font-size:22px;margin:0 0 8px}"
                + "p{color:#8BA3B8;font-size:14px;line-height:1.4}"
                + "label{display:block;margin-top:16px;color:#8BA3B8;font-size:13px}"
                + "input{width:100%;box-sizing:border-box;margin-top:6px;padding:14px;"
                + "border-radius:8px;border:1px solid #2A4158;background:#0A1520;color:#E8F4FD;"
                + "font-size:16px}"
                + "button{margin-top:24px;width:100%;padding:16px;border:0;border-radius:8px;"
                + "background:#03A9F4;color:#041018;font-size:17px;font-weight:700}"
                + "</style></head><body>"
                + "<h1>" + esc(str(R.string.web_heading)) + "</h1>"
                + "<p>" + esc(str(R.string.web_intro)) + "</p>"
                + "<form method=POST action=/>"
                + "<label>" + esc(str(R.string.web_label_url))
                + "<input name=url type=url required placeholder=\""
                + esc(str(R.string.hint_ha_url)) + "\" "
                + "autocomplete=url></label>"
                + "<label>" + esc(str(R.string.web_label_token))
                + "<input name=token type=text required placeholder=\""
                + esc(str(R.string.hint_ha_token)) + "\" "
                + "autocomplete=off autocapitalize=off spellcheck=false></label>"
                + "<label>" + esc(str(R.string.web_label_prefix))
                + "<input name=prefix type=text value=\""
                + esc(str(R.string.default_prefix)) + "\" placeholder=\""
                + esc(str(R.string.hint_prefix)) + "\"></label>"
                + "<button type=submit>" + esc(str(R.string.web_btn_save)) + "</button>"
                + "</form></body></html>";
    }

    private String resultPage(boolean ok, String msg) {
        String color = ok ? "#4CAF50" : "#EF5350";
        String title = str(ok ? R.string.web_result_ok : R.string.web_result_fail);
        return "<!DOCTYPE html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(str(R.string.web_title)) + "</title><style>"
                + "body{font-family:system-ui,sans-serif;background:#0D1B2A;color:#E8F4FD;"
                + "padding:32px;text-align:center}"
                + "h1{color:" + color + "}"
                + "p{color:#8BA3B8}"
                + "</style></head><body><h1>" + esc(title) + "</h1>"
                + "<p>" + esc(msg) + "</p></body></html>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String readHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int n;
        while ((n = in.read()) != -1) {
            bos.write(n);
            int s = bos.size();
            if (s >= 4) {
                byte[] a = bos.toByteArray();
                if (a[s - 4] == '\r' && a[s - 3] == '\n' && a[s - 2] == '\r' && a[s - 1] == '\n') {
                    break;
                }
            }
            if (s > 8000) return null;
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
    }

    private static int contentLength(String headers) {
        for (String line : headers.split("\n")) {
            if (line.toLowerCase(Locale.US).startsWith("content-length:")) {
                try {
                    return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                } catch (Exception e) {
                    return -1;
                }
            }
        }
        return 0;
    }

    private static byte[] readFully(InputStream in, int len) throws Exception {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) throw new Exception("short body");
            off += n;
        }
        return b;
    }

    private static void writeHttp(Socket sock, int code, String type, String body) {
        try {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            String h = "HTTP/1.0 " + code + " OK\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + b.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream os = sock.getOutputStream();
            os.write(h.getBytes(StandardCharsets.UTF_8));
            os.write(b);
            os.flush();
        } catch (Exception ignored) {}
    }

    private void status(Listener l, String msg) {
        main.post(() -> l.onStatus(msg));
    }

    private void fail(Listener l, String reason) {
        running.set(false);
        String msg = reason == null ? str(R.string.msg_generic_error) : reason;
        main.post(() -> l.onFailed(msg));
    }

    static String localIpv4() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase(Locale.US);
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.isLoopbackAddress() || !(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.contains("wifi")) {
                        return ip;
                    }
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }
}
