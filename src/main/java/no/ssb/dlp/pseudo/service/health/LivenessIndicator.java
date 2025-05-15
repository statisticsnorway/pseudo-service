package no.ssb.dlp.pseudo.service.health;


import org.reactivestreams.Publisher;

import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import io.micronaut.management.health.indicator.annotation.Liveness;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

@Singleton
@Liveness
public class LivenessIndicator implements HealthIndicator {
    private static final String LIVENESS_NAME = "liveness";

    @Override
    public Publisher<HealthResult> getResult() {
        return Mono.just(HealthResult.builder(LIVENESS_NAME)
                .status(HealthStatus.UP)
                .build());
    }
}
