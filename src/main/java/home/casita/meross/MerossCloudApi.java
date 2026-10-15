package home.casita.meross;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import home.casita.garage.DoorException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Meross's HTTP API: sign in and list devices. Unofficial and reverse-engineered by the
 * community (MerossIot), so every failure surfaces as a clear {@link DoorException}.
 *
 * <p>Every request is {@code {"params": base64(json), "timestamp": ms, "nonce": 16 chars,
 * "sign": md5(secret + timestamp + nonce + params)}}, and every reply is
 * {@code {"apiStatus": 0, "data": ..., "info": ...}}.
 */
class MerossCloudApi {

    static final String LOGIN_PATH = "/v1/Auth/signIn";
    static final String DEVICES_PATH = "/v1/Device/devList";

    private static final String APP_VERSION = "0.1.0";

    private final String defaultBaseUrl;
    private final Duration timeout;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String phoneUuid = UUID.randomUUID().toString();

    MerossCloudApi(String defaultBaseUrl, Duration timeout) {
        this.defaultBaseUrl = stripSlash(defaultBaseUrl);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    MerossCredentials login(String email, String password) throws DoorException {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new DoorException("Meross email and password aren't configured");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("email", email);
        params.put("password", MerossCrypto.md5Hex(password));
        params.put("encryption", 1);
        params.put("accountCountryCode", "us");
        params.put("agree", 0);
        params.put("mobileInfo", Map.of(
                "deviceModel", "Casita",
                "mobileOsVersion", "1",
                "mobileOs", "Linux",
                "uuid", phoneUuid,
                "carrier", "",
                "resolution", "0*0"));

        String baseUrl = defaultBaseUrl;
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> reply = post(baseUrl, LOGIN_PATH, params, null);
            int status = number(reply.get("apiStatus"));
            Map<String, Object> data = map(reply.get("data"));
            if (status == 0) {
                return new MerossCredentials(
                        string(data.get("userid")),
                        string(data.get("key")),
                        string(data.get("token")),
                        stripSlash(data.get("domain") == null ? baseUrl : string(data.get("domain"))),
                        string(data.get("mqttDomain")));
            }
            // Wrong region: Meross says which server to use. Retry there once.
            Object domain = data.get("domain");
            if (status == 1030 && domain != null && attempt == 0) {
                baseUrl = stripSlash(string(domain));
                continue;
            }
            if (status == 1033) {
                throw new DoorException("The Meross account asks for a two-factor code, which Casita doesn't support");
            }
            throw new DoorException("Meross sign-in failed (apiStatus " + status + "): " + reply.get("info"));
        }
        throw new DoorException("Meross kept redirecting the sign-in");
    }

    List<MerossDeviceInfo> listDevices(MerossCredentials creds) throws DoorException {
        Map<String, Object> reply = post(creds.apiBaseUrl(), DEVICES_PATH, Map.of(), creds.token());
        int status = number(reply.get("apiStatus"));
        if (status != 0) {
            throw new DoorException("Meross device list failed (apiStatus " + status + "): " + reply.get("info"));
        }
        List<MerossDeviceInfo> devices = new ArrayList<>();
        if (reply.get("data") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> d = map(o);
                devices.add(new MerossDeviceInfo(string(d.get("uuid")), string(d.get("devName")),
                        string(d.get("deviceType")), d.get("onlineStatus") != null && number(d.get("onlineStatus")) == 1));
            }
        }
        return devices;
    }

    private Map<String, Object> post(String baseUrl, String path, Map<String, Object> params, String token)
            throws DoorException {
        String encodedParams = Base64.getEncoder().encodeToString(json.writeValueAsBytes(params));
        long timestamp = System.currentTimeMillis();
        String nonce = MerossCrypto.newNonce();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", encodedParams);
        body.put("sign", MerossCrypto.httpSign(timestamp, nonce, encodedParams));
        body.put("timestamp", timestamp);
        body.put("nonce", nonce);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", token == null ? "Basic" : "Basic " + token)
                .header("vender", "meross")
                .header("AppVersion", APP_VERSION)
                .header("AppType", "Casita")
                .header("AppLanguage", "EN")
                .header("User-Agent", "Casita/" + APP_VERSION)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new DoorException("Can't reach Meross: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DoorException("Interrupted while calling Meross", e);
        }
        if (response.statusCode() != 200) {
            throw new DoorException("Meross answered HTTP " + response.statusCode() + " for " + path);
        }
        try {
            return map(json.readValue(response.body(), Map.class));
        } catch (RuntimeException e) {
            throw new DoorException("Meross sent something that isn't JSON for " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    static int number(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s && !s.isBlank()) return Integer.parseInt(s.trim());
        return -1;
    }

    static String string(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
