package home.casita.security.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

/**
 * Firestore failing on demand, at the gRPC layer under the real client library, by RPC name ({@code Commit},
 * {@code BatchGetDocuments}, {@code RunQuery}, {@code BeginTransaction}, …): before the request is sent (nothing
 * happens), after it was applied with the response lost (it happened, the caller doesn't know), refused as contended
 * ({@code ABORTED}, as Firestore answers a transaction that lost a lock), or everything down. The client library's
 * own retries and transaction reruns then do exactly what they would against Google.
 */
public final class FaultyFirestore {

    private final Map<String, AtomicInteger> failBefore = new ConcurrentHashMap<>();
    /** Calls let through before {@link #failBefore} starts failing (the same RPC serves different reads). */
    private final Map<String, AtomicInteger> skip = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> loseResponse = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> abort = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final AtomicBoolean down = new AtomicBoolean();
    /** After the next response lost on this RPC, everything goes down: the caller's retries and read-back fail too. */
    private final Map<String, AtomicBoolean> downAfterLoss = new ConcurrentHashMap<>();

    /** A call held at the door: {@link #reached} once it arrives, sent only after {@link #release}. */
    public static final class Hold {
        private final java.util.concurrent.CountDownLatch arrived = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);

        /** Waits until the held call has been made (its decision is read and made, its write not yet sent). */
        public void reached() throws InterruptedException {
            if (!arrived.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("the held call never came");
        }

        public void release() {
            released.countDown();
        }
    }

    private final Map<String, Hold> holds = new ConcurrentHashMap<>();
    private volatile long connectionReadyAtNanos;

    /**
     * A slow first connection, as on an e2-micro busy starting the JVM: every call made in the next {@code delay} waits
     * until then before it is sent. Its deadline, already set by the client library, keeps running meanwhile, so each
     * attempt shorter than the wait runs out, and so do the retries made during it.
     */
    public void connectionReadyAfter(java.time.Duration delay) {
        connectionReadyAtNanos = System.nanoTime() + delay.toNanos();
    }

    /** Holds the next call of the RPC until released, so a test can change Firestore between a decision and its write. */
    public Hold holdNext(String rpc) {
        Hold hold = new Hold();
        holds.put(rpc, hold);
        return hold;
    }

    public ClientInterceptor interceptor() {
        return new ClientInterceptor() {
            @Override
            public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
                String op = method.getBareMethodName();
                calls.computeIfAbsent(op, k -> new AtomicInteger()).incrementAndGet();
                long wait = connectionReadyAtNanos - System.nanoTime();
                if (wait > 0) {
                    try {
                        java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                Hold hold = holds.remove(op);
                if (hold != null) {
                    hold.arrived.countDown();
                    try {
                        hold.released.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (down.get()) return failing(Status.UNAVAILABLE.withDescription("Firestore is down (test)"));
                if (!take(skip, op) && take(failBefore, op)) return failing(Status.UNAVAILABLE.withDescription("Failed before sending " + op + " (test)"));
                if (take(abort, op)) return failing(Status.ABORTED.withDescription("Contention on " + op + " (test)"));
                ClientCall<Q, R> call = next.newCall(method, options);
                if (!take(loseResponse, op)) return call;
                AtomicBoolean thenDown = downAfterLoss.remove(op);
                return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
                    @Override
                    public void start(Listener<R> listener, Metadata headers) {
                        super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
                            @Override
                            public void onMessage(R message) {
                                // Applied by the server; the answer never arrives.
                            }

                            @Override
                            public void onClose(Status status, Metadata trailers) {
                                if (thenDown != null) down.set(true);
                                super.onClose(Status.UNAVAILABLE.withDescription("Response to " + op + " lost (test)"), trailers);
                            }
                        }, headers);
                    }
                };
            }
        };
    }

    private static <Q, R> ClientCall<Q, R> failing(Status status) {
        return new ClientCall<>() {
            @Override
            public void start(Listener<R> listener, Metadata headers) {
                listener.onClose(status, new Metadata());
            }

            @Override
            public void request(int numMessages) {}

            @Override
            public void cancel(String message, Throwable cause) {}

            @Override
            public void halfClose() {}

            @Override
            public void sendMessage(Q message) {}
        };
    }

    private static boolean take(Map<String, AtomicInteger> counts, String op) {
        AtomicInteger n = counts.get(op);
        return n != null && n.getAndUpdate(v -> Math.max(0, v - 1)) > 0;
    }

    /** The next {@code times} calls of the RPC fail before being sent. */
    public void failBefore(String rpc, int times) {
        failBefore.put(rpc, new AtomicInteger(times));
    }

    /** After letting {@code through} calls pass, the next {@code times} calls of the RPC fail before being sent. */
    public void failBefore(String rpc, int times, int through) {
        skip.put(rpc, new AtomicInteger(through));
        failBefore(rpc, times);
    }

    /** The next {@code times} calls of the RPC are applied, then answer with an error. */
    public void loseResponse(String rpc, int times) {
        loseResponse.put(rpc, new AtomicInteger(times));
    }

    /** The next call of the RPC is applied and its answer lost; from then on, Firestore is down. */
    public void loseResponseThenDown(String rpc) {
        downAfterLoss.put(rpc, new AtomicBoolean(true));
        loseResponse(rpc, 1);
    }

    /** The next {@code times} calls of the RPC are refused as contended (ABORTED), as a transaction that lost a lock. */
    public void abort(String rpc, int times) {
        abort.put(rpc, new AtomicInteger(times));
    }

    public void down(boolean isDown) {
        down.set(isDown);
    }

    /** How many times the RPC was attempted since the last reset. */
    public int calls(String rpc) {
        AtomicInteger n = calls.get(rpc);
        return n == null ? 0 : n.get();
    }

    public void reset() {
        failBefore.clear();
        skip.clear();
        loseResponse.clear();
        abort.clear();
        downAfterLoss.clear();
        holds.values().forEach(Hold::release);
        holds.clear();
        connectionReadyAtNanos = 0;
        calls.clear();
        down.set(false);
    }
}
