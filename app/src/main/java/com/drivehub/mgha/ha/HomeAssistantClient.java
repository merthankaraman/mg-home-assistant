package com.drivehub.mgha.ha;

import android.content.Context;
import android.util.Log;

import com.drivehub.mgha.R;
import com.drivehub.mgha.util.MghaLog;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Home Assistant REST: token ile {@code /api/states} ve servis çağrıları.
 */
public class HomeAssistantClient {
    private static final String TAG = "MGHA_HA";
    private static final int CONNECT_MS = 15000;
    private static final int READ_MS = 15000;

    public static class Result {
        public final boolean ok;
        public final int httpCode;
        public final String body;
        public final String error;

        Result(boolean ok, int httpCode, String body, String error) {
            this.ok = ok;
            this.httpCode = httpCode;
            this.body = body;
            this.error = error;
        }

        public static Result success(int code, String body) {
            return new Result(true, code, body, null);
        }

        public static Result fail(String error) {
            return new Result(false, -1, null, error);
        }

        public static Result fail(int code, String body) {
            String detail = body == null || body.isEmpty() ? "" : (" " + body);
            return new Result(false, code, body, "HTTP " + code + detail);
        }
    }

    private final Context appCtx;
    private final String baseUrl;
    private final String token;
    private final boolean allowInsecureSsl;

    public HomeAssistantClient(Context ctx, String baseUrl, String token, boolean allowInsecureSsl) {
        this.appCtx = ctx.getApplicationContext();
        this.baseUrl = normalizeBase(baseUrl);
        this.token = token == null ? "" : token.trim();
        this.allowInsecureSsl = allowInsecureSsl;
    }

    public static String normalizeBase(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    public Result testConnection() {
        return request("GET", "/api/", null);
    }

    public Result postState(String entityId, String state, JSONObject attributes) {
        try {
            JSONObject body = new JSONObject();
            body.put("state", state);
            if (attributes != null) body.put("attributes", attributes);
            return request("POST", "/api/states/" + entityId, body.toString());
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    public Result callService(String domain, String service, JSONObject data) {
        String body = data == null ? "{}" : data.toString();
        return request("POST", "/api/services/" + domain + "/" + service, body);
    }

    /** {@code /api/states/{entity_id}} — switch on/off veya unavailable. */
    public Boolean getSwitchState(String entityId) {
        String state = getEntityState(entityId);
        if (state == null) return null;
        if ("on".equals(state)) return true;
        if ("off".equals(state)) return false;
        return null;
    }

    /** {@code /api/states/{entity_id}} — number değeri; geçersizse null. */
    public Integer getNumberState(String entityId) {
        String state = getEntityState(entityId);
        if (state == null) return null;
        try {
            return (int) Math.round(Double.parseDouble(state));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String getEntityState(String entityId) {
        if (entityId == null || entityId.trim().isEmpty()) return null;
        Result r = request("GET", "/api/states/" + entityId.trim(), null);
        if (!r.ok || r.body == null || r.body.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(r.body);
            return o.optString("state", "").trim().toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    private Result request(String method, String path, String jsonBody) {
        if (baseUrl.isEmpty()) return Result.fail(appCtx.getString(R.string.err_ha_url_empty));
        if (token.isEmpty()) return Result.fail(appCtx.getString(R.string.err_ha_token_empty));
        HttpURLConnection conn = null;
        long t0 = System.currentTimeMillis();
        try {
            URL url = new URL(baseUrl + path);
            MghaLog.i(TAG, "→ " + method + " " + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_MS);
            conn.setReadTimeout(READ_MS);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoInput(true);
            if (allowInsecureSsl && conn instanceof HttpsURLConnection) {
                trustAll((HttpsURLConnection) conn);
            }
            if (jsonBody != null) {
                conn.setDoOutput(true);
                byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readStream(is);
            long ms = System.currentTimeMillis() - t0;
            if (code >= 200 && code < 300) {
                MghaLog.i(TAG, "← " + code + " " + path + " (" + ms + "ms)");
                return Result.success(code, body);
            }
            Log.e(TAG, "← " + code + " " + path + " (" + ms + "ms) " + body);
            return Result.fail(code, body);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            Log.e(TAG, "✗ " + method + " " + path + " (" + ms + "ms) " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return Result.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @android.annotation.SuppressLint({"CustomX509TrustManager", "BadHostnameVerifier"})
    private static void trustAll(HttpsURLConnection conn) throws Exception {
        TrustManager[] tm = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    @android.annotation.SuppressLint("TrustAllX509TrustManager")
                    public void checkClientTrusted(X509Certificate[] c, String a) {}

                    @Override
                    @android.annotation.SuppressLint("TrustAllX509TrustManager")
                    public void checkServerTrusted(X509Certificate[] c, String a) {}

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, tm, new SecureRandom());
        conn.setSSLSocketFactory(sc.getSocketFactory());
        conn.setHostnameVerifier((hostname, session) -> true);
    }
}
