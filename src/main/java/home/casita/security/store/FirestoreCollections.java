package home.casita.security.store;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;

/**
 * The security collections' names and document IDs (docs/FIRESTORE.md). Casita never creates the database or
 * {@code meta/schema}: provisioning does, reviewed separately. {@link #verify} is the start-up check.
 *
 * <p>Every ID carries a short prefix: Firestore reserves IDs matching {@code __.*__}, and a base64url credential
 * ID could be one. No ID holds a secret: an invitation is found by the SHA-256 of its token, never the token.
 */
public record FirestoreCollections(String members, String userHandles, String passkeys, String devices, String invitations,
                                   String securityEvents, String meta) {

    /** The schema this code reads and writes. Provisioning writes it to {@code meta/schema}. */
    public static final long SCHEMA_VERSION = 1;

    /** Only the {@code (default)} database gets Firestore's free quota, so Casita uses no other. */
    public static final String DATABASE_ID = "(default)";

    /** What a raw identifier may be before it becomes part of a document ID: no '/', nothing to escape. */
    private static final Pattern RAW_ID = Pattern.compile("[A-Za-z0-9._-]{1,700}");

    public static FirestoreCollections standard() {
        return new FirestoreCollections("members", "userHandles", "passkeys", "devices", "invitations", "securityEvents", "meta");
    }

    DocumentReference member(Firestore db, String memberId) {
        return db.collection(members).document("m_" + checked(memberId));
    }

    DocumentReference userHandle(Firestore db, String handle) {
        return db.collection(userHandles).document("u_" + checked(handle));
    }

    DocumentReference passkey(Firestore db, String credentialId) {
        return db.collection(passkeys).document("c_" + checked(credentialId));
    }

    DocumentReference device(Firestore db, String nodeId) {
        return db.collection(devices).document("n_" + checked(nodeId));
    }

    DocumentReference invitation(Firestore db, String tokenHash) {
        return db.collection(invitations).document("h_" + checked(tokenHash));
    }

    DocumentReference securityEvent(Firestore db, String eventId) {
        return db.collection(securityEvents).document(checked(eventId));
    }

    DocumentReference schema(Firestore db) {
        return db.collection(meta).document("schema");
    }

    /** Whether a raw identifier can be part of a document ID. One that can't was never stored. */
    static boolean valid(String raw) {
        return raw != null && RAW_ID.matcher(raw).matches();
    }

    private static String checked(String raw) {
        if (!valid(raw)) throw new IllegalArgumentException("not a storable identifier");
        return raw;
    }

    /** At start-up in Firestore mode: {@code meta/schema} must be readable and this version, or Casita doesn't start. */
    public void verify(Firestore db) {
        DocumentSnapshot schema;
        try {
            schema = schema(db).get().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Firestore isn't reachable at start-up; Casita doesn't start without its store", e);
        }
        Long version = schema.exists() ? schema.getLong("schemaVersion") : null;
        if (version == null) {
            throw new IllegalStateException(meta + "/schema doesn't exist; Casita doesn't provision Firestore (docs/FIRESTORE.md)");
        }
        if (version != SCHEMA_VERSION) {
            throw new IllegalStateException(meta + "/schema is version " + version + ", this Casita reads version " + SCHEMA_VERSION);
        }
    }
}
