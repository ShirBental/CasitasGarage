package home.casita.meross;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import home.casita.garage.DoorCommandRejectedException;
import home.casita.garage.DoorException;
import home.casita.meross.MerossException.Kind;
import tools.jackson.databind.json.JsonMapper;

/**
 * The opener on the home network ({@code casita.meross.transport=local}): each message is one {@code POST <url>/config}
 * with Meross's signed header, answered on the HTTP response, as meross_lan talks to MSG100/MSG200 openers. The account
 * is signed in once (lazily), for the key that signs messages and for the device list; after that only the opener is
 * contacted. Replies must be signed with the key and carry the request's message id.
 *
 * <p>Commands follow the same rules as over the cloud: sent once, never retried (the JDK client doesn't retry a POST),
 * and only a failure that guarantees nothing left Casita (no key, no connection) is a rejection. Anything after the
 * request may have gone out (a timeout waiting for the answer, a reset, an unreadable or unsigned answer) is an unknown
 * outcome, and GarageService checks the sensor.
 */
final class MerossLocalTransport implements MerossTransport {

    private static final Logger log = LoggerFactory.getLogger(MerossLocalTransport.class);

    private final MerossCloudApi api;
    private final String email;
    private final String password;
    private final URI config;
    private final Duration timeout;
    private final Clock clock;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String appId = MerossCrypto.newAppId();
    private volatile MerossCredentials creds;

    MerossLocalTransport(MerossCloudApi api, String email, String password, URI deviceUrl, Duration timeout, Clock clock) {
        this(api, email, password, checked(deviceUrl), timeout, clock, HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build());
    }

    /** For tests: {@code deviceUrl} isn't checked, so a fake opener can listen on 127.0.0.1. */
    MerossLocalTransport(MerossCloudApi api, String email, String password, URI deviceUrl, Duration timeout, Clock clock,
                         HttpClient http) {
        this.api = api;
        this.email = email;
        this.password = password;
        this.config = URI.create("http://" + deviceUrl.getRawAuthority() + "/config");
        this.timeout = timeout;
        this.clock = clock;
        this.http = http;
    }

    private static URI checked(URI deviceUrl) {
        MerossLinkProperties.checkLocalUrl(deviceUrl);
        return deviceUrl;
    }

    /** The account's key and user id: one sign-in, kept. Refused credentials are thrown and the next call tries again. */
    private MerossCredentials signedIn() throws MerossException {
        MerossCredentials c = creds;
        if (c != null) return c;
        synchronized (this) {
            if (creds == null) {
                creds = api.login(email, password);
                log.info("meross event=sign_in outcome=ok transport=local (for the key and device list only)");
            }
            return creds;
        }
    }

    @Override
    public List<MerossDeviceInfo> devices() throws DoorException {
        return api.listDevices(signedIn());
    }

    @Override
    public Map<String, Object> get(String deviceUuid, String namespace) throws DoorException {
        return exchange(signedIn(), deviceUuid, "GET", namespace, Map.of(namespace.substring(namespace.lastIndexOf('.') + 1)
                .toLowerCase(java.util.Locale.ROOT), Map.of()), false);
    }

    @Override
    public Map<String, Object> setOnce(String deviceUuid, String namespace, Map<String, Object> payload) throws DoorException {
        MerossCredentials c;
        try {
            c = signedIn();
        } catch (MerossException e) {
            throw new DoorCommandRejectedException("Couldn't get the account key, so nothing was sent: " + e.getMessage(), e);
        }
        return exchange(c, deviceUuid, "SET", namespace, payload, true);
    }

    @Override
    public String dryRunSet(String deviceUuid, String namespace, Map<String, Object> payload) throws DoorException {
        MerossCredentials c;
        try {
            c = signedIn();
        } catch (MerossException e) {
            throw new DoorCommandRejectedException("Couldn't get the account key: " + e.getMessage(), e);
        }
        byte[] body = json.writeValueAsBytes(message(c, deviceUuid, "SET", namespace, payload, MerossCrypto.newMessageId()));
        log.info("meross event=command outcome=dry_run transport=local namespace={} bytes={} (built and signed, not sent)", namespace, body.length);
        return "SET " + namespace + " to " + MerossDeviceInfo.masked(deviceUuid) + " over the home network, payload keys "
                + new TreeSet<>(payload.keySet()) + ", " + body.length + " bytes, signed";
    }

    private Map<String, Object> message(MerossCredentials c, String deviceUuid, String method, String namespace,
                                        Map<String, Object> payload, String messageId) {
        return MerossMessages.message(messageId, clock.instant().getEpochSecond(), "/app/" + c.userId() + "-" + appId + "/subscribe",
                c.key(), deviceUuid, method, namespace, payload);
    }

    private Map<String, Object> exchange(MerossCredentials c, String deviceUuid, String method, String namespace,
                                         Map<String, Object> payload, boolean command) throws DoorException {
        String messageId = MerossCrypto.newMessageId();
        byte[] body = json.writeValueAsBytes(message(c, deviceUuid, method, namespace, payload, messageId));
        HttpRequest request = HttpRequest.newBuilder(config).timeout(timeout)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            // No connection: the request never left Casita.
            if (command) {
                log.warn("meross event=command outcome=rejected transport=local reason=no_connection");
                throw new DoorCommandRejectedException("Couldn't connect to the opener on the home network; nothing was sent", e);
            }
            throw new MerossException(Kind.UNAVAILABLE, "Couldn't connect to the opener on the home network", e);
        } catch (HttpTimeoutException e) {
            if (command) {
                log.warn("meross event=command outcome=unknown transport=local reason=no_answer");
                throw new MerossException(Kind.TIMEOUT, "No answer from the opener within " + timeout.toSeconds()
                        + " s; the command may or may not have been carried out", e);
            }
            throw new MerossException(Kind.TIMEOUT, "The opener didn't answer within " + timeout.toSeconds() + " s", e);
        } catch (IOException e) {
            if (command) {
                log.warn("meross event=command outcome=unknown transport=local reason=io error={}", e.getClass().getSimpleName());
                throw new MerossException(Kind.UNAVAILABLE, "The connection to the opener failed during the command; it may or may "
                        + "not have been carried out", e);
            }
            throw new MerossException(Kind.UNAVAILABLE, "The connection to the opener failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (command) throw new MerossException(Kind.UNAVAILABLE, "Interrupted during the command; it may or may not have been sent", e);
            throw new MerossException(Kind.UNAVAILABLE, "Interrupted while reading the opener", e);
        }

        // From here the request reached the opener: for a command, every failure is an unknown outcome (a MerossException).
        if (response.statusCode() != 200) {
            throw new MerossException(Kind.PROTOCOL, "The opener answered HTTP " + response.statusCode()
                    + (command ? "; the command may or may not have been carried out" : ""));
        }
        Map<String, Object> answer, header;
        try {
            answer = MerossCloudApi.map(json.readValue(response.body(), Map.class));
            header = MerossCloudApi.map(answer.get("header"));
        } catch (RuntimeException e) {
            throw new MerossException(Kind.PROTOCOL, "The opener's answer isn't readable" + (command ? "; the command's outcome is unknown" : ""), e);
        }
        if (!MerossMessages.signedWith(header, c.key())) {
            log.warn("meross event=message_dropped transport=local reason=signature method={} namespace={}", header.get("method"), namespace);
            throw new MerossException(Kind.PROTOCOL, "The opener's answer isn't signed with the account key"
                    + (command ? "; the command's outcome is unknown" : ""));
        }
        if (!messageId.equals(header.get("messageId"))) {
            throw new MerossException(Kind.PROTOCOL, "The opener answered another message" + (command ? "; the command's outcome is unknown" : ""));
        }
        Map<String, Object> answerPayload = MerossCloudApi.map(answer.get("payload"));
        String answerMethod = String.valueOf(header.get("method"));
        if (!answerMethod.equals(method + "ACK")) {
            Object code = answerPayload.get("error") instanceof Map<?, ?> error ? error.get("code") : null;
            log.warn("meross event={} outcome=device_error transport=local method={} namespace={} error_code={}",
                    command ? "command" : "read", answerMethod, namespace, code);
            throw new MerossException(Kind.DEVICE_ERROR, "The opener answered " + answerMethod + " (error code " + code + ")");
        }
        if (command) log.info("meross event=command outcome=acknowledged transport=local namespace={}", namespace);
        return answerPayload;
    }
}
