package no.ssb.dlp.pseudo.service.pseudo.metadata;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.AddingSpanAttributes;
import io.reactivex.processors.ReplayProcessor;
import lombok.Value;
import no.ssb.dlp.pseudo.core.util.Json;
import org.reactivestreams.Publisher;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Value
public class PseudoMetadataProcessor {

    String correlationId;
    Map<String, Set<FieldMetadata>> uniqueMetadataPaths = new LinkedHashMap<>();
    ReplayProcessor<FieldMetadata> datadocMetadata = ReplayProcessor.create();
    ReplayProcessor<String> logs = ReplayProcessor.create();
    ReplayProcessor<FieldMetric> metrics = ReplayProcessor.create();

    public PseudoMetadataProcessor(String correlationId) {
        this.correlationId = correlationId;
    }
    public void addMetadata(final FieldMetadata metadata) {
        Set<FieldMetadata> rules = uniqueMetadataPaths.computeIfAbsent(metadata.getDataElementPath(), k -> new HashSet<>());
        if (rules.add(metadata)) {
            datadocMetadata.onNext(metadata);
        }
    }
    public void addLog(String log) {
        logs.onNext(log);
    }
    public void addMetric(FieldMetric fieldMetric) {
        metrics.onNext(fieldMetric);
    }
    @AddingSpanAttributes
    public Publisher<String> getMetadata() {
        Span.current().addEvent("getMetadata", Instant.now());
        return datadocMetadata.map(FieldMetadata::toDatadocVariable).map(Json::from);
    }
    @AddingSpanAttributes
    public Publisher<String> getLogs() {
        Span.current().addEvent("getLogs", Instant.now());
        return logs.map(Json::from);
    }
    @AddingSpanAttributes
    public Publisher<String> getMetrics() {
        Span.current().addEvent("getMetrics", Instant.now());
        return metrics.groupBy(FieldMetric::name)
                .flatMapSingle(group ->
                    group.count().map(c -> Map.of(group.getKey(), c.intValue())
                )).map(Json::from);
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
