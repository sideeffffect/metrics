package com.avast.metrics.grpc;

import com.avast.metrics.api.Monitor;
import io.grpc.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class GrpcClientMonitoringInterceptor implements ClientInterceptor {
    private final MetricsCache cache;
    private final Clock clock;

    public GrpcClientMonitoringInterceptor(final Monitor monitor, final Clock clock) {
        this.clock = clock;
        cache = new MetricsCache(monitor);
    }

    public GrpcClientMonitoringInterceptor(final Monitor monitor) {
        this(monitor, Clock.systemUTC());
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(final MethodDescriptor<ReqT, RespT> method, final CallOptions callOptions, final Channel next) {
        final AtomicInteger currentCalls = cache.getGaugedValue(method, "Current");

        final Instant start = clock.instant();
        currentCalls.incrementAndGet();
        final ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);

        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(call) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                delegate().start(
                        new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                            @Override
                            public void onClose(io.grpc.Status status, Metadata trailers) {
                                final Duration duration = Duration.between(start, clock.instant());
                                currentCalls.decrementAndGet();

                                if (ErrorCategory.fatal.contains(status.getCode())) {
                                    cache.getTimer(method, "FatalServerFailures")
                                            .update(duration);
                                } else if (ErrorCategory.client.contains(status.getCode())) {
                                    cache.getTimer(method, "ClientFailures")
                                            .update(duration);
                                } else if (status.isOk()) {
                                    cache.getTimer(method, "Successes")
                                            .update(duration);
                                } else {
                                    cache.getTimer(method, "ServerFailures")
                                            .update(duration);
                                }

                                super.onClose(status, trailers);
                            }
                        },
                        headers);
            }
        };
    }
}
