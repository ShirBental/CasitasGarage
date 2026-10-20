import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;

import home.casita.security.access.TestTailnet;
import home.casita.security.store.FirestoreCollections;
import home.casita.security.store.FirestoreSecurityStore;
import home.casita.security.store.SecurityStore;
import home.casita.security.store.SecurityStore.EnrollmentClaim;
import home.casita.security.store.SecurityStore.Invitation;

/**
 * Benchmark only: the Firestore store's own latency, call by call, against the emulator (a local, non-production
 * estimate: no network, no Google lock manager). args: emulatorHost project iterations out.csv
 */
public class FirestoreLatency {
    static final Map<String, List<Double>> samples = new LinkedHashMap<>();

    static <T> T time(String op, Supplier<T> call) {
        long t0 = System.nanoTime();
        T r = call.get();
        samples.computeIfAbsent(op, k -> new ArrayList<>()).add((System.nanoTime() - t0) / 1e6);
        return r;
    }

    public static void main(String[] args) throws Exception {
        String host = args[0], project = args[1];
        int n = Integer.parseInt(args[2]);
        Firestore db = FirestoreOptions.newBuilder().setEmulatorHost(host).setProjectId(project)
                .setCredentialsProvider(FixedCredentialsProvider.create(new FirestoreOptions.EmulatorCredentials())).build().getService();
        db.collection("meta").document("schema").set(Map.of("schemaVersion", 1L)).get();
        FirestoreSecurityStore store = new FirestoreSecurityStore(db, FirestoreCollections.standard(), Clock.systemUTC(), Duration.ofSeconds(5));
        store.seedMembers(List.of("shir", "hubby"));
        var user = TestTailnet.user(store, "shir");

        // Warm-up: class loading, channel, JIT.
        for (int i = 0; i < 50; i++) store.member("shir");
        samples.clear();

        String node = "nBenchNode0", credential = null;
        for (int i = 0; i < n; i++) {
            Instant now = Instant.now();
            String hash = HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes()).substring(0, 64);
            Invitation inv = new Invitation(hash, "i-" + Integer.toHexString(i), "shir", "bench", SecurityStore.InvitationStatus.PENDING,
                    now, now.plus(Duration.ofMinutes(5)), null, null, null, null, null, null, null);
            time("createInvitation (batch: doc + event)", () -> { store.createInvitation(inv); return null; });
            Invitation c = time("consume (CAS batch)", () -> store.consume(hash, node, "bench", "r" + inv.inviteId(), "e" + inv.inviteId())).orElseThrow();
            EnrollmentClaim claim = new EnrollmentClaim(c.tokenHash(), c.inviteId(), c.enrollmentId(), "shir", "bench", node, "bench");
            String id = Base64.getUrlEncoder().withoutPadding().encodeToString(("bench-" + i).getBytes());
            CredentialRecord record = TestTailnet.credential(user, id);
            boolean ok = time("enroll (CAS batch: 4 reads, 5 writes)", () -> store.enroll(claim, record, null));
            if (!ok) throw new IllegalStateException("enroll refused at " + i);
            credential = id;
            String cred = id;
            time("accessView (1 BatchGet, 3 docs)", () -> store.accessView("shir", Bytes.fromBase64(cred), node));
            time("recordUse (sign-in CAS batch)", () -> store.recordUse(record, "shir"));
            time("member (1 get)", () -> store.member("shir"));
            time("revokeDevice (CAS batch)", () -> store.revokeDevice(node));
        }
        try (var out = new java.io.PrintWriter(args[3])) {
            out.println("op,n,p50_ms,p95_ms,p99_ms,max_ms");
            for (var e : samples.entrySet()) {
                List<Double> v = new ArrayList<>(e.getValue());
                Collections.sort(v);
                String row = String.format(Locale.ROOT, "\"%s\",%d,%.2f,%.2f,%.2f,%.2f", e.getKey(), v.size(), pct(v, 50), pct(v, 95), pct(v, 99), v.getLast());
                out.println(row);
                System.out.println(row);
            }
        }
        db.close();
    }

    static double pct(List<Double> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
