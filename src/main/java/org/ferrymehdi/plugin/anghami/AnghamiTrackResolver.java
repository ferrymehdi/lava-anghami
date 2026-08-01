package org.ferrymehdi.plugin.anghami;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class AnghamiTrackResolver {
    private final String sessionId;
    private final String reqKey;
    private final String resKey;
    private final String userAgent;
    private final LazySodiumJava sodium;
    private final OkHttpClient httpClient;

    public AnghamiTrackResolver(String sessionId, String reqKey, String resKey, String userAgent) {
        this.sessionId = sessionId;
        this.reqKey = reqKey;
        this.resKey = resKey;
        this.userAgent = userAgent;
        this.sodium = new LazySodiumJava(new SodiumJava());
        this.httpClient = new OkHttpClient();
    }

    private String md5(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 Hash failed", e);
        }
    }

    private String signRequest(String tsMillis) {
        String uaBase64 = Base64.getEncoder().encodeToString(this.userAgent.getBytes(StandardCharsets.UTF_8));
        return md5(tsMillis + uaBase64 + "ferry");
    }

    private byte[] encryptRequest(Map<String, String> params) throws Exception {
        StringBuilder bodyStr = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (bodyStr.length() > 0) bodyStr.append("&");
            bodyStr.append(entry.getKey()).append("=").append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(bodyStr.toString().getBytes(StandardCharsets.UTF_8));
        }
        byte[] gzipped = bos.toByteArray();
        byte[] keyBytes = reqKey.getBytes(StandardCharsets.UTF_8);

        byte[] secretNonce = sodium.randomBytesBuf(8);
        byte[] publicNonce = sodium.randomBytesBuf(12);

        byte[] ciphertext = new byte[gzipped.length + 16];
        long[] cipherLen = new long[1];

        boolean success = sodium.cryptoAeadChaCha20Poly1305Encrypt(
                ciphertext, cipherLen,
                gzipped, (long) gzipped.length,
                publicNonce, (long) publicNonce.length,
                (byte[]) null, secretNonce, keyBytes
        );

        if (!success) throw new RuntimeException("Encryption failed");

        ByteBuffer buffer = ByteBuffer.allocate(2 + secretNonce.length + publicNonce.length + (int) cipherLen[0]);
        buffer.put((byte) 35).put((byte) 35);
        buffer.put(secretNonce);
        buffer.put(publicNonce);
        buffer.put(ciphertext, 0, (int) cipherLen[0]);

        return buffer.array();
    }

    private JSONObject decryptResponse(String base64Str) throws Exception {
        String cleanStr = base64Str.replace("\"", "").trim();
        byte[] data = Base64.getDecoder().decode(cleanStr);

        if (data[0] != 35 || data[1] != 35) {
            throw new RuntimeException("NOT_ENCRYPTED_RESPONSE");
        }

        byte[] secretNonce = new byte[8];
        System.arraycopy(data, 2, secretNonce, 0, 8);

        byte[] publicNonce = new byte[12];
        System.arraycopy(data, 10, publicNonce, 0, 12);

        int cipherLen = data.length - 22;
        byte[] ciphertext = new byte[cipherLen];
        System.arraycopy(data, 22, ciphertext, 0, cipherLen);

        byte[] keyBytes = resKey.getBytes(StandardCharsets.UTF_8);
        byte[] decrypted = new byte[cipherLen - 16];
        long[] decryptedLen = new long[1];

        boolean success = sodium.cryptoAeadChaCha20Poly1305Decrypt(
                decrypted, decryptedLen,
                (byte[]) null,
                ciphertext, (long) ciphertext.length,
                publicNonce, (long) publicNonce.length,
                secretNonce, keyBytes
        );

        if (!success) throw new RuntimeException("Decryption failed");

        ByteArrayInputStream bis = new ByteArrayInputStream(decrypted, 0, (int) decryptedLen[0]);
        try (GZIPInputStream gis = new GZIPInputStream(bis)) {
            return new JSONObject(new String(gis.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    public String getTrackUrl(String trackId) {
        long now = System.currentTimeMillis();
        String tsMillis = String.valueOf(now);
        String tsSeconds = String.valueOf(now / 1000);
        String tsHashed = signRequest(tsMillis);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("fileid", trackId);
        params.put("HQ", "196");
        params.put("output", "jsonhp");
        params.put("retry", "0");
        params.put("abortControllerKey", trackId);
        params.put("ts", tsMillis);
        params.put("ts_hashed", tsHashed);
        params.put("ngsw-bypass", "true");

        try {
            byte[] encryptedBody = encryptRequest(params);

            String url = String.format("https://coussa.anghami.com/download?language=en&web2=true&lang=en&userlanguageprod=en&HQ=196&fileid=%s&output=jsonhp&retry=0&abortControllerKey=%s&ts=%s&ts_hashed=%s&ngsw-bypass=true&sid=%s",
                    trackId, trackId, tsMillis, tsHashed, sessionId);

            RequestBody body = RequestBody.create(encryptedBody, MediaType.parse("application/x-www-form-urlencoded"));
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("x-angh-encpayload", "5")
                    .addHeader("x-angh-ts", tsSeconds)
                    .addHeader("referer", "https://play.anghami.com/")
                    .addHeader("user-agent", userAgent)
                    .build();

            try (Response res = httpClient.newCall(request).execute()) {
                String responseText = res.body() != null ? res.body().string() : "";
                try {
                    JSONObject result = decryptResponse(responseText);
                    String location = result.optString("location", null);

                    if (location != null && !location.isEmpty() && !location.equals("null")) {
                        return location;
                    } else {
                        JSONArray sections = result.optJSONArray("sections");
                        if (sections != null && sections.length() > 0) {
                            return sections.getJSONObject(0).getJSONArray("data").getJSONObject(0).optString("location", null);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[-] Decryption failed or response is plain text: " + responseText);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
