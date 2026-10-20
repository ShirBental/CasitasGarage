import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;

/** Benchmark only: what provisioning does once, in an emulator project. args: emulatorHost project */
public class Provision {
    public static void main(String[] args) throws Exception {
        try (Firestore db = FirestoreOptions.newBuilder().setEmulatorHost(args[0]).setProjectId(args[1])
                .setCredentialsProvider(FixedCredentialsProvider.create(new FirestoreOptions.EmulatorCredentials())).build().getService()) {
            db.collection("meta").document("schema").set(Map.of("schemaVersion", 1L)).get(10, TimeUnit.SECONDS);
        }
        System.out.println("provisioned " + args[1] + " on " + args[0]);
    }
}
