package home.casita.security.store;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * A DynamoDB client that fails on demand, by operation name: before the request is sent (nothing happens), or
 * after it was applied with the response lost (it happened, the caller doesn't know). Or everything fails.
 */
public final class FaultyDynamo {

    private final DynamoDbClient real;
    private final DynamoDbClient client;
    private final Map<String, AtomicInteger> failBefore = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> loseResponse = new ConcurrentHashMap<>();
    private final AtomicBoolean down = new AtomicBoolean();

    public FaultyDynamo(DynamoDbClient real) {
        this.real = real;
        this.client = (DynamoDbClient) Proxy.newProxyInstance(DynamoDbClient.class.getClassLoader(), new Class<?>[] {DynamoDbClient.class},
                (proxy, method, args) -> {
                    String op = method.getName();
                    if (op.equals("close") || op.equals("serviceName") || op.equals("toString") || op.equals("hashCode") || op.equals("equals")) {
                        return method.invoke(real, args);
                    }
                    if (down.get()) throw SdkClientException.create("DynamoDB is down (test)");
                    if (take(failBefore, op)) throw SdkClientException.create("Failed before sending " + op + " (test)");
                    Object result;
                    try {
                        result = method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (take(loseResponse, op)) throw SdkClientException.create("Response to " + op + " lost (test)");
                    return result;
                });
    }

    private static boolean take(Map<String, AtomicInteger> counts, String op) {
        AtomicInteger n = counts.get(op);
        return n != null && n.getAndUpdate(v -> Math.max(0, v - 1)) > 0;
    }

    public DynamoDbClient client() {
        return client;
    }

    public DynamoDbClient real() {
        return real;
    }

    /** The next {@code times} calls of the operation fail before being sent. */
    public void failBefore(String operation, int times) {
        failBefore.put(operation, new AtomicInteger(times));
    }

    /** The next {@code times} calls of the operation are applied, then answer with an error. */
    public void loseResponse(String operation, int times) {
        loseResponse.put(operation, new AtomicInteger(times));
    }

    public void down(boolean isDown) {
        down.set(isDown);
    }

    public void reset() {
        failBefore.clear();
        loseResponse.clear();
        down.set(false);
    }
}
