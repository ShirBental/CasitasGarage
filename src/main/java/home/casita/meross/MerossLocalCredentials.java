package home.casita.meross;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Meross account credentials for local development and the read-only live check.
 *
 * <p>Each value comes from the environment when it is set, otherwise from
 * {@code config/meross.local.json} (git-ignored; see {@code config/meross.local.example.json}).
 * Email and password are required; the device uuid is optional.
 *
 * <p>Error messages name the missing fields and where they were looked for, never a value.
 * The JSON parser's own messages are not passed on, because they can quote the file.
 *
 * @param email      Meross account email
 * @param password   Meross account password
 * @param deviceUuid the garage opener's uuid, or null to find it automatically
 */
record MerossLocalCredentials(String email, String password, String deviceUuid) {

    static final Path FILE = Path.of("config", "meross.local.json");

    private static final Set<String> KEYS = Set.of("email", "password", "deviceUuid");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Resolves the credentials. The first three arguments are the environment's values (null or
     * blank when unset); {@code envNames} says which variables those were, for the error message.
     *
     * @throws IllegalStateException if email or password is in neither place, or the file is unreadable
     */
    static MerossLocalCredentials resolve(String envEmail, String envPassword, String envDeviceUuid, Path file, String envNames) {
        Map<String, String> fromFile = Files.exists(file) ? readFile(file) : Map.of();
        String email = firstSet(envEmail, fromFile.get("email"));
        String password = firstSet(envPassword, fromFile.get("password"));
        String deviceUuid = firstSet(envDeviceUuid, fromFile.get("deviceUuid"));

        List<String> missing = new ArrayList<>();
        if (email == null) {
            missing.add("email");
        }
        if (password == null) {
            missing.add("password");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Meross credentials missing: " + String.join(", ", missing) + ". Set " + envNames
                    + ", or put them in " + file + " (copy config/meross.local.example.json; see docs/MEROSS.md).");
        }
        return new MerossLocalCredentials(email, password, deviceUuid);
    }

    private static Map<String, String> readFile(Path file) {
        Map<?, ?> raw;
        try {
            raw = JSON.readValue(Files.readString(file), Map.class);
        } catch (JacksonException e) {
            // Location only: the parser's message may quote the file's contents.
            String where = e.getLocation() == null ? ""
                    : " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
            throw new IllegalStateException(file + " is not a valid JSON object" + where + ".");
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file + " (" + e.getClass().getSimpleName() + ").");
        }
        if (raw == null) {
            throw new IllegalStateException(file + " must contain a JSON object.");
        }
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!KEYS.contains(key)) {
                throw new IllegalStateException(file + " has an unknown key \"" + key + "\"; expected " + KEYS + ".");
            }
            if (entry.getValue() != null && !(entry.getValue() instanceof String)) {
                throw new IllegalStateException(file + ": \"" + key + "\" must be a string.");
            }
            values.put(key, (String) entry.getValue());
        }
        return values;
    }

    /** Blank counts as unset. Values are kept exactly as given: a password may start or end with a space. */
    private static String firstSet(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback != null && !fallback.isBlank() ? fallback : null;
    }

    @Override
    public String toString() {
        return "MerossLocalCredentials[email=" + (email == null ? "unset" : "set") + ", password=" + (password == null ? "unset" : "set")
                + ", deviceUuid=" + deviceUuid + "]";
    }
}
