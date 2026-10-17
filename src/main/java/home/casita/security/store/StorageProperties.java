package home.casita.security.store;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the security store lives (docs/PERSISTENCE.md). All four security tables move together: members,
 * passkeys, device authorizations and invitations are never split between files and DynamoDB, because the
 * enrollment transaction spans them.
 *
 * @param type        {@code file} (the default; local development, {@code <data-dir>/security.json}) or {@code dynamodb}
 * @param tablePrefix DynamoDB table names: {@code <prefix>members}, {@code <prefix>passkeys}, …
 * @param region      the AWS region; empty for the SDK's default (on EC2, the instance's region). Credentials always
 *                    come from the SDK's default chain: on EC2, the instance profile. Never configured here.
 */
@ConfigurationProperties("casita.storage")
public record StorageProperties(@DefaultValue("file") Type type, @DefaultValue("casita-") String tablePrefix, String region) {

    public enum Type { FILE, DYNAMODB }
}
