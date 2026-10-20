package home.casita.security.store;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

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
    /** Indexes that haven't caught up: every index query answers "nothing", as a lagging GSI can. */
    private final AtomicBoolean indexesLag = new AtomicBoolean();
    /** What DynamoDB itself answered with an error (not the injected failures), by operation: "op: ExceptionType". */
    private final List<String> realErrors = new CopyOnWriteArrayList<>();

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
                    if (op.equals("query") && indexesLag.get() && indexName(args[0]) != null) {
                        return QueryResponse.builder().items(List.of()).count(0).scannedCount(0).build();
                    }
                    Object result;
                    try {
                        result = method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        realErrors.add(op + ": " + e.getCause().getClass().getSimpleName());
                        throw e.getCause();
                    }
                    if (take(loseResponse, op)) throw SdkClientException.create("Response to " + op + " lost (test)");
                    return result;
                });
    }

    @SuppressWarnings("unchecked")
    private static String indexName(Object request) {
        if (request instanceof QueryRequest q) return q.indexName();
        QueryRequest.Builder builder = QueryRequest.builder();
        ((Consumer<QueryRequest.Builder>) request).accept(builder);
        return builder.build().indexName();
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

    public List<String> realErrors() {
        return List.copyOf(realErrors);
    }

    public void indexesLag(boolean lag) {
        indexesLag.set(lag);
    }

    public void down(boolean isDown) {
        down.set(isDown);
    }

    public void reset() {
        failBefore.clear();
        loseResponse.clear();
        down.set(false);
        indexesLag.set(false);
        realErrors.clear();
    }
}
