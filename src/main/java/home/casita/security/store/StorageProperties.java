package home.casita.security.store;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the security store lives (docs/PERSISTENCE.md, docs/FIRESTORE.md). All the security records move together:
 * members, passkeys, device authorizations and invitations are never split between stores, because the
 * enrollment transaction spans them.
 *
 * @param type        {@code file} (the default; local development, {@code <data-dir>/security.json}), {@code dynamodb}
 *                    or {@code firestore}
 * @param tablePrefix DynamoDB table names: {@code <prefix>members}, {@code <prefix>passkeys}, …
 * @param region      the AWS region; empty for the SDK's default (on EC2, the instance's region). Credentials always
 *                    come from the SDK's default chain: on EC2, the instance profile. Never configured here.
 * @param firestore   Firestore settings (type {@code firestore})
 */
@ConfigurationProperties("casita.storage")
public record StorageProperties(@DefaultValue("file") Type type, @DefaultValue("casita-") String tablePrefix, String region,
                                @DefaultValue Firestore firestore) {

    public enum Type { FILE, DYNAMODB, FIRESTORE }

    /**
     * Firestore, always the {@code (default)} database (the only one with a free quota). Credentials always come from
     * Application Default Credentials: on Compute Engine, the VM's attached service account. Never configured here,
     * and never a key file.
     *
     * @param projectId    the Google Cloud project; empty for ADC's own (on Compute Engine, the VM's project)
     * @param timeout      the longest Casita waits for any Firestore call, retries included, before refusing
     * @param emulatorHost tests and local benchmarks only: a Firestore emulator on this machine, e.g. 127.0.0.1:18085
     */
    public record Firestore(String projectId, @DefaultValue("5s") Duration timeout, String emulatorHost) {}
}
