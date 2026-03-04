package no.ssb.dlp.pseudo.service.pseudo.metadata;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.reactivex.processors.ReplayProcessor;
import lombok.Value;
import no.ssb.dlp.pseudo.core.util.Json;
import org.reactivestreams.Publisher;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Value
public class PseudoMetadataProcessor {
    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("pseudo-service");

    String correlationId;
    Map<String, Set<FieldMetadata>> uniqueMetadataPaths = new LinkedHashMap<>();
    ReplayProcessor<FieldMetadata> datadocMetadata = ReplayProcessor.create();
    ReplayProcessor<String> logs = ReplayProcessor.create();
    ReplayProcessor<FieldMetric> metrics = ReplayProcessor.create();

    public PseudoMetadataProcessor(String correlationId) {
        this.correlationId = correlationId;
    }
    public void addMetadata(final FieldMetadata metadata) {
        final var span = tracer.spanBuilder("PseudoMetadataProcessor.addMetadata").startSpan();
        try (Scope scope = span.makeCurrent()) {

            Set<FieldMetadata> rules = uniqueMetadataPaths.computeIfAbsent(metadata.getDataElementPath(), k -> new HashSet<>());
            if (rules.add(metadata)) {
                datadocMetadata.onNext(metadata);
            }
        } finally {
            span.end();
        }
    }
    public void addLog(String log) {
        logs.onNext(log);
    }
    public void addMetric(FieldMetric fieldMetric) {
        metrics.onNext(fieldMetric);
    }
    public Publisher<String> getMetadata() {
        final var span = tracer.spanBuilder("PseudoMetadataProcessor.getMetadata").startSpan();
        try (Scope scope = span.makeCurrent()) {
            return datadocMetadata.map(FieldMetadata::toDatadocVariable).map(Json::from);
        } finally {
            span.end();
        }
    }
    public Publisher<String> getLogs() {
        final var span = tracer.spanBuilder("PseudoMetadataProcessor.getLogs").startSpan();
        try (Scope scope = span.makeCurrent()) {
            return logs.map(Json::from);
        } finally {
            span.end();
        }
    }
    public Publisher<String> getMetrics() {
        final var span = tracer.spanBuilder("PseudoMetadataProcessor.getMetrics").startSpan();
        try (Scope scope = span.makeCurrent()) {
            return metrics
                    .groupBy(FieldMetric::name)
                    .flatMapSingle(group ->
                            group.count().map(c -> Map.of(group.getKey(), c.intValue())
                            ))
                    .map(Json::from);
        } finally {
            span.end();
        }
    }
    public void onCompleteAll() {
        datadocMetadata.onComplete();
        logs.onComplete();
        metrics.onComplete();
    }
    public void onErrorAll(Throwable t) {
        datadocMetadata.onError(t);
        logs.onError(t);
        metrics.onError(t);
    }
}
