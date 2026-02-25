package no.ssb.dlp.pseudo.service.performance;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.JsonKeysetWriter;
import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.daead.DeterministicAeadConfig;
import no.ssb.dlp.pseudo.core.util.Json;
import no.ssb.dlp.pseudo.core.tink.model.EncryptedKeysetWrapper;
import no.ssb.dlp.pseudo.service.pseudo.PseudoConfigSplitter;
import no.ssb.dlp.pseudo.service.pseudo.PseudoController;
import no.ssb.dlp.pseudo.service.pseudo.PseudoSecrets;
import no.ssb.dlp.pseudo.service.pseudo.RecordMapProcessorFactory;
import no.ssb.dlp.pseudo.service.pseudo.StreamProcessorFactory;
import no.ssb.dlp.pseudo.service.secrets.MockSecretService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PseudoServiceDaeadPerformanceTest {

    private static final String ENABLED_PROP = "pseudo.performance.enabled";
    private static final String BATCH_SIZE_PROP = "pseudo.performance.batchSize";
    private static final String WARMUP_ROUNDS_PROP = "pseudo.performance.warmupRounds";
    private static final String MEASURE_ROUNDS_PROP = "pseudo.performance.measureRounds";

    private static final URI TEST_KEK_URI = URI.create("test-kek://local/master-key");

    private static PseudoController controller;
    private static EncryptedKeysetWrapper keyset;

    @BeforeAll
    static void setUp() throws Exception {
        AeadConfig.register();
        DeterministicAeadConfig.register();

        Aead masterAead = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
                .getPrimitive(Aead.class);

        keyset = createWrappedDaeadKeyset(masterAead, TEST_KEK_URI);

        LoadingCache<String, Aead> aeadCache = Caffeine.newBuilder()
                .maximumSize(100)
                .build(uri -> {
                    if (!TEST_KEK_URI.toString().equals(uri)) {
                        throw new IllegalArgumentException("Unknown KEK URI in test: " + uri);
                    }
                    return masterAead;
                });

        PseudoSecrets pseudoSecrets = new PseudoSecrets(new MockSecretService(), Map.of());
        RecordMapProcessorFactory recordMapProcessorFactory =
                new RecordMapProcessorFactory(pseudoSecrets, aeadCache);

        controller = new PseudoController(
                new StreamProcessorFactory(),
                recordMapProcessorFactory,
                new PseudoConfigSplitter()
        );
    }

    @Test
    void benchmarkDaeadFieldEndpoints() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLED_PROP),
                () -> "Skipping performance benchmark. Run with -D" + ENABLED_PROP + "=true");

        final int batchSize = Integer.getInteger(BATCH_SIZE_PROP, 10_000);
        final int warmupRounds = Integer.getInteger(WARMUP_ROUNDS_PROP, 2);
        final int measureRounds = Integer.getInteger(MEASURE_ROUNDS_PROP, 8);

        List<String> inputValues = generateValues(batchSize, 42L);
        String daeadFunc = "daead(keyId=" + keyset.primaryKeyId() + ")";

        PseudoController.PseudoFieldRequest pseudoReq = new PseudoController.PseudoFieldRequest();
        pseudoReq.setName("fnr");
        pseudoReq.setPattern("**");
        pseudoReq.setPseudoFunc(daeadFunc);
        pseudoReq.setKeyset(keyset);
        pseudoReq.setValues(inputValues);

        String pseudoReqJson = Json.from(pseudoReq);

        BenchmarkResult pseudonymizeResult = benchmark(
                "pseudonymize_full",
                warmupRounds,
                measureRounds,
                () -> {
                    String responseJson = callPseudonymize(pseudoReqJson, false);
                    List<String> data = extractDataValues(responseJson);
                    assertEquals(inputValues.size(), data.size());
                    return data;
                }
        );

        BenchmarkResult pseudonymizeMinimalResult = benchmark(
                "pseudonymize_minimal_metrics",
                warmupRounds,
                measureRounds,
                () -> {
                    String responseJson = callPseudonymize(pseudoReqJson, true);
                    List<String> data = extractDataValues(responseJson);
                    assertEquals(inputValues.size(), data.size());
                    return data;
                }
        );

        List<String> pseudonymizedValues = callPseudonymizeAndExtractData(pseudoReqJson, false);

        PseudoController.DepseudoFieldRequest depseudoReq = new PseudoController.DepseudoFieldRequest();
        depseudoReq.setName("fnr");
        depseudoReq.setPattern("**");
        depseudoReq.setPseudoFunc(daeadFunc);
        depseudoReq.setKeyset(keyset);
        depseudoReq.setValues(pseudonymizedValues);

        String depseudoReqJson = Json.from(depseudoReq);

        BenchmarkResult depseudonymizeResult = benchmark(
                "depseudonymize",
                warmupRounds,
                measureRounds,
                () -> {
                    String responseJson = callDepseudonymize(depseudoReqJson);
                    List<String> restored = extractDataValues(responseJson);
                    assertEquals(inputValues, restored);
                    return restored;
                }
        );

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", Instant.now().toString());
        report.put("batchSize", batchSize);
        report.put("warmupRounds", warmupRounds);
        report.put("measureRounds", measureRounds);
        report.put("pseudonymizeFull", pseudonymizeResult.toMap());
        report.put("pseudonymizeMinimalMetrics", pseudonymizeMinimalResult.toMap());
        report.put("depseudonymize", depseudonymizeResult.toMap());

        Path reportPath = Path.of("target", "performance", "pseudo-service-daead-field.json");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, Json.prettyFrom(report), StandardCharsets.UTF_8);

        System.out.println("DAEAD benchmark report written to: " + reportPath.toAbsolutePath());
        System.out.println(Json.prettyFrom(report));
    }

    private static BenchmarkResult benchmark(String name,
                                             int warmupRounds,
                                             int measureRounds,
                                             Supplier<List<String>> call) {
        for (int i = 0; i < warmupRounds; i++) {
            call.get();
        }

        List<Double> elapsedMillis = new ArrayList<>(measureRounds);
        int itemCount = -1;
        for (int i = 0; i < measureRounds; i++) {
            long start = System.nanoTime();
            List<String> data = call.get();
            long end = System.nanoTime();
            if (itemCount < 0) {
                itemCount = data.size();
            }
            elapsedMillis.add((end - start) / 1_000_000d);
        }

        return BenchmarkResult.of(name, itemCount, elapsedMillis);
    }

    private static String callPseudonymize(String requestJson, boolean minimalMetricsMode) {
        return collectBody(
                controller.pseudonymizeFieldFast(requestJson, minimalMetricsMode).body()
        );
    }

    private static List<String> callPseudonymizeAndExtractData(String requestJson, boolean minimalMetricsMode) {
        return extractDataValues(callPseudonymize(requestJson, minimalMetricsMode));
    }

    private static String callDepseudonymize(String requestJson) {
        return collectBody(
                controller.depseudonymizeField(requestJson).body()
        );
    }

    private static String collectBody(io.reactivex.Flowable<byte[]> body) {
        StringBuilder sb = new StringBuilder();
        for (byte[] part : body.blockingIterable()) {
            sb.append(new String(part, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static List<String> extractDataValues(String responseJson) {
        Map<String, Object> payload = Json.toGenericMap(responseJson);
        Object dataObj = payload.get("data");
        assertNotNull(dataObj, "Expected response to contain data array");

        List<?> raw = (List<?>) dataObj;
        List<String> values = new ArrayList<>(raw.size());
        for (Object value : raw) {
            values.add(value == null ? null : String.valueOf(value));
        }
        return values;
    }

    private static List<String> generateValues(int size, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        List<String> values = new ArrayList<>(size);
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

        for (int i = 0; i < size; i++) {
            int len = 10 + random.nextInt(11);
            StringBuilder sb = new StringBuilder(len);
            for (int c = 0; c < len; c++) {
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            values.add(sb.toString());
        }

        return values;
    }

    private static EncryptedKeysetWrapper createWrappedDaeadKeyset(Aead masterAead, URI kekUri) throws Exception {
        KeysetHandle dataKeyset = KeysetHandle.generateNew(KeyTemplates.get("AES256_SIV"));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        dataKeyset.write(JsonKeysetWriter.withOutputStream(baos), masterAead);

        EncryptedKeysetWrapper wrapper = Json.toObject(
                EncryptedKeysetWrapper.class,
                baos.toString(StandardCharsets.UTF_8)
        );
        wrapper.setKekUri(kekUri);
        return wrapper;
    }

    private record BenchmarkResult(
            String name,
            int itemCount,
            double minMs,
            double maxMs,
            double avgMs,
            double p50Ms,
            double p95Ms,
            double throughputPerSec
    ) {
        private static BenchmarkResult of(String name, int itemCount, List<Double> elapsedMillis) {
            List<Double> sorted = new ArrayList<>(elapsedMillis);
            Collections.sort(sorted);

            double min = sorted.get(0);
            double max = sorted.get(sorted.size() - 1);
            double sum = sorted.stream().mapToDouble(Double::doubleValue).sum();
            double avg = sum / sorted.size();
            double p50 = percentile(sorted, 0.50);
            double p95 = percentile(sorted, 0.95);
            double throughput = (itemCount * 1000d) / avg;

            return new BenchmarkResult(name, itemCount, min, max, avg, p50, p95, throughput);
        }

        private static double percentile(List<Double> sorted, double p) {
            if (sorted.size() == 1) {
                return sorted.get(0);
            }
            int idx = (int) Math.ceil(p * sorted.size()) - 1;
            idx = Math.max(0, Math.min(idx, sorted.size() - 1));
            return sorted.get(idx);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("items", itemCount);
            map.put("minMs", minMs);
            map.put("maxMs", maxMs);
            map.put("avgMs", avgMs);
            map.put("p50Ms", p50Ms);
            map.put("p95Ms", p95Ms);
            map.put("throughputItemsPerSec", throughputPerSec);
            return map;
        }
    }
}
