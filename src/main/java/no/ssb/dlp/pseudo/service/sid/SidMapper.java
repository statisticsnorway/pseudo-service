package no.ssb.dlp.pseudo.service.sid;

import com.google.auto.service.AutoService;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import lombok.extern.slf4j.Slf4j;
import no.ssb.dapla.dlp.pseudo.func.PseudoFuncInput;
import no.ssb.dapla.dlp.pseudo.func.PseudoFuncOutput;
import no.ssb.dapla.dlp.pseudo.func.map.MapFailureStrategy;
import no.ssb.dapla.dlp.pseudo.func.map.MapFuncConfig;
import no.ssb.dapla.dlp.pseudo.func.map.Mapper;
import no.ssb.dlp.pseudo.service.Application;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Service Provider class that implements the {@link Mapper} pseudo function. This class will be invoked by the JDK's
 * Service Loader mechanism for {@link no.ssb.dlp.pseudo.core.func.PseudoFuncNames#MAP_SID} pseudo functions.
 */
@AutoService(Mapper.class)
@Slf4j
public class SidMapper implements Mapper {

    /**
     * If mapping fails, the SidMapper will be instructed to return a value dependent on a given
     * {@link MapFailureStrategy}. When doing so, the SidMapper will also write to the {@link PseudoFuncOutput}'s
     * metadata using this key to signal which {@link MapFailureStrategy} was used.
     */
    public static final String MAP_FAILURE_METADATA = "map-failure-metadata";
    private final SidService sidService;

    private static final int DEFAULT_PARTITION_SIZE = 50000;
    private final int partitionSize;
    private Map<String, Object> config = Collections.emptyMap();

    static final String NO_MATCHING_FNR = "No SID-mapping found for fnr %s";
    static final String NO_MATCHING_SNR = "No SID-mapping found for snr %s";
    static final String INCORRECT_MATCHING_FNR = "Incorrect SID-mapping for fnr %s. Mapping returned the original fnr!";
    static final String INCORRECT_MATCHING_SNR = "Incorrect SID-mapping for snr %s. Mapping returned the original snr!";
    static final String CORRECT_MATCHED_FNR = "Successfully mapped fnr starting with %s";
    static final String CORRECT_MATCHED_SNR = "Successfully mapped snr starting with %s";

    public SidMapper() {
        sidService = Application.getContext().getBean(SidService.class);
        partitionSize = Application.getContext().getProperty("sid.mapper.partition.size", Integer.class,
                DEFAULT_PARTITION_SIZE);
    }

    private final Set<String> identifiers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, ObservableSubscriber<Map<String, SidInfo>>> bulkRequest = new ConcurrentHashMap<>();

    @Override
    public void init(PseudoFuncInput input) {
        identifiers.add(input.value());
    }

    @Override
    public PseudoFuncOutput map(PseudoFuncInput input) {
        return mapTo(input.value(), true);
    }

    @Override
    public PseudoFuncOutput restore(PseudoFuncInput input) {
        return mapTo(input.value(), false);
    }

    private PseudoFuncOutput mapTo(String identifier, boolean isFnr) {
        if (identifier == null) {
            return PseudoFuncOutput.of(null);
        }
        ensureBulkRequestContains(identifier, isFnr);
        ObservableSubscriber<Map<String, SidInfo>> subscriber = bulkRequest.get(identifier);
        if (subscriber == null) {
            throw new RuntimeException("SID subscriber not found for identifier");
        }
        SidInfo result = subscriber.awaitResult()
                .orElseThrow(() -> new RuntimeException("SID service did not respond"))
                .get(identifier);

        return createMappingLogsAndOutput(result, isFnr, identifier);


    }

    private void ensureBulkRequestContains(String identifier, boolean isFnr) {
        if (bulkRequest.containsKey(identifier)) {
            return;
        }

        synchronized (this) {
            if (bulkRequest.containsKey(identifier)) {
                return;
            }

            // Defensive fallback: include the current identifier even if init() was not called
            identifiers.add(identifier);

            // Initial request: use pre-collected identifiers for efficient bulk lookup.
            if (bulkRequest.isEmpty()) {
                for (List<String> bulkIdentifiers : Lists.partition(List.copyOf(identifiers), partitionSize)) {
                    log.info("Execute SID-mapping bulk request");
                    final ObservableSubscriber<Map<String, SidInfo>> subscriber;

                    if (isFnr) {
                        subscriber = ObservableSubscriber.subscribe(
                                sidService.lookupFnr(bulkIdentifiers, getSnapshot()));
                    } else {
                        subscriber = ObservableSubscriber.subscribe(
                                sidService.lookupSnr(bulkIdentifiers, getSnapshot()));
                    }

                    for (String id : bulkIdentifiers) {
                        bulkRequest.put(id, subscriber);
                    }
                }
            } else {
                // Late/missing identifier: fallback to a tiny lookup to avoid NullPointerException.
                log.info("Execute SID-mapping fallback request for missing identifier");
                ObservableSubscriber<Map<String, SidInfo>> subscriber = isFnr
                        ? ObservableSubscriber.subscribe(sidService.lookupFnr(List.of(identifier), getSnapshot()))
                        : ObservableSubscriber.subscribe(sidService.lookupSnr(List.of(identifier), getSnapshot()));
                bulkRequest.put(identifier, subscriber);
            }
        }
    }


    private PseudoFuncOutput createMappingLogsAndOutput(SidInfo sidInfo, boolean isFnr, String identifier) {
        //Mapping for fnr
        if (isFnr) {
            if (sidInfo == null || sidInfo.snr() == null) {
                String message = String.format(NO_MATCHING_FNR, Redactor.redactSnr(identifier));
                log.warn(message);
                final MapFailureStrategy mapFailureStrategy = getMapFailureStrategy();
                PseudoFuncOutput output = PseudoFuncOutput.of(
                        mapFailureStrategy == MapFailureStrategy.RETURN_NULL ? null : identifier);
                output.addWarning(message);
                output.addMetadata(MAP_FAILURE_METADATA, mapFailureStrategy.name());
                return output;
            } else if (identifier.equals(sidInfo.snr())) {
                String message = String.format(INCORRECT_MATCHING_FNR, Redactor.redactFnr(identifier));
                log.warn(message);
                final MapFailureStrategy mapFailureStrategy = getMapFailureStrategy();
                PseudoFuncOutput output = PseudoFuncOutput.of(
                        mapFailureStrategy == MapFailureStrategy.RETURN_NULL ? null : identifier);
                output.addWarning(message);
                output.addMetadata(MAP_FAILURE_METADATA, mapFailureStrategy.name());
                return output;
            } else {
                String message = String.format(CORRECT_MATCHED_FNR, Redactor.redactFnr(identifier));
                log.debug(message);
                PseudoFuncOutput output = PseudoFuncOutput.of(sidInfo.snr());
                output.addMetadata(MapFuncConfig.Param.SNAPSHOT_DATE, sidInfo.datasetExtractionSnapshotTime());
                return output;
            }
        }
        //Mapping for snr
        else {
            if (sidInfo == null || sidInfo.fnr() == null) {
                String message = String.format(NO_MATCHING_SNR, Redactor.redactSnr(identifier));
                log.warn(message);
                final MapFailureStrategy mapFailureStrategy = getMapFailureStrategy();
                PseudoFuncOutput output = PseudoFuncOutput.of(
                        mapFailureStrategy == MapFailureStrategy.RETURN_NULL ? null : identifier);
                output.addWarning(message);
                output.addMetadata(MAP_FAILURE_METADATA, mapFailureStrategy.name());
                return output;
            } else if (identifier.equals(sidInfo.fnr())) {
                String message = String.format(INCORRECT_MATCHING_SNR, Redactor.redactSnr(identifier));
                log.warn(message);
                final MapFailureStrategy mapFailureStrategy = getMapFailureStrategy();
                PseudoFuncOutput output = PseudoFuncOutput.of(
                        mapFailureStrategy == MapFailureStrategy.RETURN_NULL ? null : identifier);
                output.addWarning(message);
                output.addMetadata(MAP_FAILURE_METADATA, mapFailureStrategy.name());
                return output;
            } else {
                String message = String.format(CORRECT_MATCHED_SNR, Redactor.redactFnr(identifier));
                log.debug(message);
                PseudoFuncOutput output = PseudoFuncOutput.of(sidInfo.fnr());
                output.addMetadata(MapFuncConfig.Param.SNAPSHOT_DATE, sidInfo.datasetExtractionSnapshotTime());
                return output;
            }
        }
    }

    private Optional<String> getSnapshot() {
        return Optional.ofNullable(
                this.config.getOrDefault(MapFuncConfig.Param.SNAPSHOT_DATE, null)
        ).map(String::valueOf);
    }

    private MapFailureStrategy getMapFailureStrategy() {
        return Optional.ofNullable(
                this.config.getOrDefault(MapFuncConfig.Param.MAP_FAILURE_STRATEGY, null)
        ).map(String::valueOf).map(MapFailureStrategy::valueOf).orElse(MapFailureStrategy.RETURN_ORIGINAL);
    }

    @Override
    public void setConfig(Map<String, Object> config) {
        if (config.containsKey(MapFuncConfig.Param.SNAPSHOT_DATE)) {
            SnapshotInfo availableSnapshots = ObservableSubscriber.subscribe(this.sidService.getSnapshots()).awaitResult()
                    .orElseThrow(() -> new RuntimeException("SID service did not respond"));
            DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE;
            LocalDate requestedSnapshotDate;
            try { // Convert the SID snapshot in the request to Date format
                requestedSnapshotDate = LocalDate.from(formatter.
                        parse(config.get(MapFuncConfig.Param.SNAPSHOT_DATE).toString()));
            } catch (DateTimeParseException e) {
                throw new InvalidSidSnapshotDateException(String.format("Invalid snapshot date format. Valid dates are: %s",
                        String.join(", ", availableSnapshots.items())));
            }
            List<LocalDate> availableSnapshotDates = availableSnapshots.items().stream()
                    .map(snapshot -> {
                        try {
                            return LocalDate.from(formatter.parse(snapshot));
                        } catch (DateTimeParseException e) {
                            throw new RuntimeException("Invalid date format from SID service");
                        }
                    }).toList();
            if (availableSnapshotDates.stream().allMatch(requestedSnapshotDate::isBefore)) {
                throw new InvalidSidSnapshotDateException(String.format("Requested date is of an earlier date than all available SID dates. Valid dates are: %s",
                        String.join(", ", availableSnapshots.items())));
            }
        }
        this.config = config;
    }

    /**
     * A Subscriber that stores the publishers results and provides a latch so can block on completion.
     *
     * @param <T> The publishers result type
     */
    static class ObservableSubscriber<T> implements Subscriber<T> {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final Stopwatch stopwatch = Stopwatch.createUnstarted();

        private volatile T result;
        private volatile Subscription subscription;

        public static <T> ObservableSubscriber<T> subscribe(Publisher<T> publisher) {
            ObservableSubscriber<T> instance = new ObservableSubscriber<T>();
            publisher.subscribe(instance);
            return instance;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            this.stopwatch.start();
            this.subscription = subscription;
        }

        @Override
        public void onNext(T sidInfo) {
            result = sidInfo;
        }

        @Override
        public void onError(Throwable throwable) {
            if (throwable instanceof HttpClientResponseException exception) {
                if (exception.getStatus() == HttpStatus.NOT_FOUND) {
                    // This may happen more frequently, so log at debug level
                    log.debug("Error was", exception);
                } else {
                    log.warn("Unexpected error", exception);
                }
            } else {
                log.warn("Unexpected error", throwable);
            }
            onComplete();
        }

        @Override
        public void onComplete() {
            latch.countDown();
            log.info("Thread completed after {} seconds", stopwatch.stop().elapsed(TimeUnit.SECONDS));
        }

        public Optional<T> awaitResult() {
            return Optional.ofNullable(await().result);
        }

        private ObservableSubscriber<T> await() {
            subscription.request(1);
            try {
                if (!latch.await(120, TimeUnit.SECONDS)) {
                    log.error("Publisher onComplete timed out");
                    throw new RuntimeException("Publisher onComplete timed out");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return this;
        }
    }
}
