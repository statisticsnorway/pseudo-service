package no.ssb.dlp.pseudo.service.pseudo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.crypto.tink.Aead;
import io.micronaut.tracing.annotation.ContinueSpan;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.ssb.dapla.dlp.pseudo.func.PseudoFuncInput;
import no.ssb.dapla.dlp.pseudo.func.PseudoFuncOutput;
import no.ssb.dapla.dlp.pseudo.func.PseudoFunc;
import no.ssb.dapla.dlp.pseudo.func.TransformDirection;
import no.ssb.dapla.dlp.pseudo.func.fpe.FpeFunc;
import no.ssb.dapla.dlp.pseudo.func.map.MapFailureStrategy;
import no.ssb.dapla.dlp.pseudo.func.map.MapFunc;
import no.ssb.dapla.dlp.pseudo.func.map.MapFuncConfig;
import no.ssb.dapla.dlp.pseudo.func.tink.fpe.TinkFpeFunc;
import no.ssb.dlp.pseudo.core.PseudoException;
import no.ssb.dlp.pseudo.core.PseudoKeyset;
import no.ssb.dlp.pseudo.core.PseudoOperation;
import no.ssb.dlp.pseudo.core.field.FieldDescriptor;
import no.ssb.dlp.pseudo.core.field.ValueInterceptorChain;
import no.ssb.dlp.pseudo.core.func.PseudoFuncDeclaration;
import no.ssb.dlp.pseudo.core.func.PseudoFuncNames;
import no.ssb.dlp.pseudo.core.func.PseudoFuncRule;
import no.ssb.dlp.pseudo.core.func.PseudoFuncRuleMatch;
import no.ssb.dlp.pseudo.core.func.PseudoFuncs;
import no.ssb.dlp.pseudo.core.map.RecordMapProcessor;
import no.ssb.dlp.pseudo.core.tink.model.EncryptedKeysetWrapper;
import no.ssb.dlp.pseudo.service.pseudo.metadata.FieldMetadata;
import no.ssb.dlp.pseudo.service.pseudo.metadata.FieldMetric;
import no.ssb.dlp.pseudo.service.pseudo.metadata.PseudoMetadataProcessor;

import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static no.ssb.dlp.pseudo.core.PseudoOperation.DEPSEUDONYMIZE;
import static no.ssb.dlp.pseudo.core.PseudoOperation.PSEUDONYMIZE;
import static no.ssb.dlp.pseudo.core.func.PseudoFuncDeclaration.*;
import static no.ssb.dlp.pseudo.service.sid.SidMapper.*;

@RequiredArgsConstructor
@Singleton
@Slf4j
public class RecordMapProcessorFactory {
    private final PseudoSecrets pseudoSecrets;
    private final LoadingCache<String, Aead> aeadCache;
    private final Cache<PseudoFuncsCacheKey, PseudoFuncs> pseudoFuncsCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .build();

    public SingleFieldProcessor newPseudonymizeSingleFieldProcessor(List<PseudoConfig> pseudoConfigs,
                                                                     String fieldName,
                                                                     String correlationId,
                                                                     boolean minimalMetricsMode) {
        PseudoMetadataProcessor metadataProcessor = new PseudoMetadataProcessor(correlationId);
        Set<String> metadataAdded = new HashSet<>();
        FieldDescriptor fieldDescriptor = FieldDescriptor.from(fieldName);

        List<PseudoFuncContext> contexts = pseudoConfigs.stream().map(config -> new PseudoFuncContext(
                newPseudoFuncs(config.getRules(), pseudoKeysetsOf(config.getKeysets())),
                pseudoFuncDeclarationsOf(config.getRules()),
                new HashMap<>())
        ).toList();

        return new SingleFieldProcessor(fieldDescriptor, contexts, metadataProcessor, metadataAdded, minimalMetricsMode);
    }

    @ContinueSpan
    public RecordMapProcessor<PseudoMetadataProcessor> newPseudonymizeRecordProcessor(List<PseudoConfig> pseudoConfigs, String correlationId) {
        ValueInterceptorChain chain = new ValueInterceptorChain();
        PseudoMetadataProcessor metadataProcessor = new PseudoMetadataProcessor(correlationId);
        Set<String> metadataAdded = new HashSet<>();

        for (PseudoConfig config : pseudoConfigs) {
            Map<String, Optional<PseudoFuncRuleMatch>> fieldMatchCache = new HashMap<>();
            for (PseudoKeyset keyset : config.getKeysets()) {
                if (log.isDebugEnabled()) {
                    log.debug("Using keyset KEK URI: {}", keyset.getKekUri());
                }
            }
            final PseudoFuncs fieldPseudonymizer = newPseudoFuncs(config.getRules(),
                    pseudoKeysetsOf(config.getKeysets()));
            final Map<PseudoFuncRule, PseudoFuncDeclaration> funcDeclarations = pseudoFuncDeclarationsOf(config.getRules());
            chain.preprocessor((f, v) -> init(fieldPseudonymizer, fieldMatchCache, TransformDirection.APPLY, f, v));
            chain.register((f, v) -> process(PSEUDONYMIZE, fieldPseudonymizer, fieldMatchCache, funcDeclarations, metadataAdded, f, v, metadataProcessor));
        }
        return new RecordMapProcessor<>(chain, metadataProcessor);
    }

    public RecordMapProcessor<PseudoMetadataProcessor> newDepseudonymizeRecordProcessor(List<PseudoConfig> pseudoConfigs, String correlationId) {
        ValueInterceptorChain chain = new ValueInterceptorChain();
        PseudoMetadataProcessor metadataProcessor = new PseudoMetadataProcessor(correlationId);

        for (PseudoConfig config : pseudoConfigs) {
            Map<String, Optional<PseudoFuncRuleMatch>> fieldMatchCache = new HashMap<>();
            final PseudoFuncs fieldDepseudonymizer = newPseudoFuncs(config.getRules(),
                    pseudoKeysetsOf(config.getKeysets()));
            final Map<PseudoFuncRule, PseudoFuncDeclaration> funcDeclarations = pseudoFuncDeclarationsOf(config.getRules());
            chain.preprocessor((f, v) -> init(fieldDepseudonymizer, fieldMatchCache, TransformDirection.RESTORE, f, v));
            chain.register((f, v) -> process(DEPSEUDONYMIZE, fieldDepseudonymizer, fieldMatchCache, funcDeclarations, null, f, v, metadataProcessor));
        }

        return new RecordMapProcessor<>(chain, metadataProcessor);
    }

    public RecordMapProcessor<PseudoMetadataProcessor> newRepseudonymizeRecordProcessor(PseudoConfig sourcePseudoConfig,
                                                               PseudoConfig targetPseudoConfig, String correlationId) {
        final PseudoFuncs fieldDepseudonymizer = newPseudoFuncs(sourcePseudoConfig.getRules(),
                pseudoKeysetsOf(sourcePseudoConfig.getKeysets()));
        final Map<PseudoFuncRule, PseudoFuncDeclaration> sourceDeclarations = pseudoFuncDeclarationsOf(sourcePseudoConfig.getRules());
        final PseudoFuncs fieldPseudonymizer = newPseudoFuncs(targetPseudoConfig.getRules(),
                pseudoKeysetsOf(targetPseudoConfig.getKeysets()));
        final Map<PseudoFuncRule, PseudoFuncDeclaration> targetDeclarations = pseudoFuncDeclarationsOf(targetPseudoConfig.getRules());
        PseudoMetadataProcessor metadataProcessor = new PseudoMetadataProcessor(correlationId);
        Set<String> metadataAdded = new HashSet<>();
        Map<String, Optional<PseudoFuncRuleMatch>> sourceFieldMatchCache = new HashMap<>();
        Map<String, Optional<PseudoFuncRuleMatch>> targetFieldMatchCache = new HashMap<>();
        return new RecordMapProcessor<>(
                new ValueInterceptorChain()
                        .preprocessor((f, v) -> init(fieldDepseudonymizer, sourceFieldMatchCache, TransformDirection.RESTORE, f, v))
                        .register((f, v) -> process(DEPSEUDONYMIZE, fieldDepseudonymizer, sourceFieldMatchCache, sourceDeclarations, null, f, v, metadataProcessor))
                        .register((f, v) -> process(PSEUDONYMIZE, fieldPseudonymizer, targetFieldMatchCache, targetDeclarations, metadataAdded, f, v, metadataProcessor)),
                metadataProcessor);
    }

    protected PseudoFuncs newPseudoFuncs(Collection<PseudoFuncRule> rules,
                                         Collection<PseudoKeyset> keysets) {
        if (containsStatefulFunc(rules)) {
            return new PseudoFuncs(rules, pseudoSecrets.resolve(), keysets, aeadCache);
        }

        PseudoFuncsCacheKey cacheKey = new PseudoFuncsCacheKey(
                rules.stream().map(PseudoFuncRule::getFunc).toList(),
                keysets.stream().map(this::pseudoKeysetSignature).toList()
        );

        return pseudoFuncsCache.get(cacheKey,
                key -> new PseudoFuncs(rules, pseudoSecrets.resolve(), keysets, aeadCache));
    }

    private static boolean containsStatefulFunc(Collection<PseudoFuncRule> rules) {
        return rules.stream()
                .map(PseudoFuncRule::getFunc)
                .map(PseudoFuncDeclaration::fromString)
                .map(PseudoFuncDeclaration::getFuncName)
                .anyMatch(funcName -> funcName.equals(PseudoFuncNames.MAP_SID)
                        || funcName.equals(PseudoFuncNames.MAP_SID_FF31)
                        || funcName.equals(PseudoFuncNames.MAP_SID_DAEAD));
    }

    private String pseudoKeysetSignature(PseudoKeyset keyset) {
        return String.join("|",
                String.valueOf(keyset.primaryKeyId()),
                String.valueOf(keyset.getKekUri()),
                String.valueOf(keyset.toJson())
        );
    }

    private String init(PseudoFuncs pseudoFuncs,
                        Map<String, Optional<PseudoFuncRuleMatch>> fieldMatchCache,
                        TransformDirection direction,
                        FieldDescriptor field,
                        String varValue) {
        if (varValue != null) {
            findPseudoFunc(pseudoFuncs, fieldMatchCache, field).ifPresent(pseudoFunc ->
                    pseudoFunc.getFunc().init(PseudoFuncInput.of(varValue), direction));
        }
        return varValue;
    }

    private String process(PseudoOperation operation,
                           PseudoFuncs func,
                           Map<String, Optional<PseudoFuncRuleMatch>> fieldMatchCache,
                           Map<PseudoFuncRule, PseudoFuncDeclaration> funcDeclarations,
                           Set<String> metadataAdded,
                           FieldDescriptor field,
                           String varValue,
                           PseudoMetadataProcessor metadataProcessor) {
        PseudoFuncRuleMatch match = findPseudoFunc(func, fieldMatchCache, field).orElse(null);

        if (match == null) {
            return varValue;
        }
        if (varValue == null) {
            // Avoid counting null values to map-sid twice (since map-sid consists of 2 functions)
            if (!(match.getFunc() instanceof MapFunc)) {
                metadataProcessor.addMetric(FieldMetric.NULL_VALUE);
            }
            return varValue;
        }
        try {
            PseudoFuncDeclaration funcDeclaration = funcDeclarations.get(match.getRule());

            // FPE requires minimum two bytes/chars to perform encryption and minimum four bytes in case of Unicode.
            if (varValue.length() < 4 && (
                    match.getFunc() instanceof FpeFunc ||
                            match.getFunc() instanceof TinkFpeFunc ||
                            funcDeclaration.getFuncName().equals(PseudoFuncNames.MAP_SID) ||
                            funcDeclaration.getFuncName().equals(PseudoFuncNames.MAP_SID_FF31)
            )) {
                metadataProcessor.addMetric(FieldMetric.FPE_LIMITATION);
                return getMapFailureStrategy(funcDeclaration.getArgs()) == MapFailureStrategy.RETURN_ORIGINAL ? varValue : null;
            }

            final boolean isSidMapping = funcDeclaration.getFuncName().equals(PseudoFuncNames.MAP_SID)
                    || funcDeclaration.getFuncName().equals(PseudoFuncNames.MAP_SID_FF31)
                    || funcDeclaration.getFuncName().equals(PseudoFuncNames.MAP_SID_DAEAD);

            if (operation == PSEUDONYMIZE) {
                PseudoFuncOutput output = match.getFunc().apply(PseudoFuncInput.of(varValue));
                output.getWarnings().forEach(metadataProcessor::addLog);
                final String sidSnapshotDate = output.getMetadata().getOrDefault(MapFuncConfig.Param.SNAPSHOT_DATE, null);
                final String mapFailureMetadata = output.getMetadata().getOrDefault(MAP_FAILURE_METADATA, null);
                final String mappedValue = output.getValue();
                if (isSidMapping && mapFailureMetadata != null) {
                    // There has been an unsuccessful SID-mapping
                    metadataProcessor.addMetric(FieldMetric.MISSING_SID);
                } else if (isSidMapping) {
                    metadataProcessor.addMetric(FieldMetric.MAPPED_SID);
                }
                String path = normalizePath(field.getPath());
                String metadataKey = path + "|" + match.getRule().getFunc() + "|" + sidSnapshotDate;
                if (metadataAdded == null || metadataAdded.add(metadataKey)) {
                    metadataProcessor.addMetadata(FieldMetadata.builder()
                            .shortName(field.getName())
                            .dataElementPath(path)
                            .encryptionKeyReference(funcDeclaration.getArgs().getOrDefault(KEY_REFERENCE, null))
                            .encryptionAlgorithm(match.getFunc().getAlgorithm())
                            .stableIdentifierVersion(sidSnapshotDate)
                            .stableIdentifierType(isSidMapping)
                            .encryptionAlgorithmParameters(funcDeclaration.getArgs())
                            .build());
                }
                return mappedValue;

            } else if (operation == DEPSEUDONYMIZE) {
                PseudoFuncOutput output = match.getFunc().restore(PseudoFuncInput.of(varValue));
                output.getWarnings().forEach(metadataProcessor::addLog);
                final String mappedValue = output.getValue();
                final String mapFailureMetadata = output.getMetadata().getOrDefault(MAP_FAILURE_METADATA, null);
                if (isSidMapping && mapFailureMetadata != null) {
                    // There has been an unsuccessful SID-mapping
                    metadataProcessor.addMetric(FieldMetric.MISSING_SID);
                } else if (isSidMapping) {
                    metadataProcessor.addMetric(FieldMetric.MAPPED_SID);
                }
                return mappedValue;
            } else {
                PseudoFuncOutput output = match.getFunc().restore(PseudoFuncInput.of(varValue));
                return output.getValue();
            }
        } catch (Exception e) {
            throw new PseudoException(String.format("pseudonymize error - field='%s', originalValue='%s'",
                    field.getPath(), varValue), e);
        }
    }

    private static String normalizePath(String path) {
        // Normalize the path by skipping leading '/' and use dot as separator
        return path.substring(1).replace('/', '.')
               // Also replace the [] separator in nested structs
               .replaceAll("\\[\\d*]", "");
    }


    // TODO: This should not be needed
    protected static List<PseudoKeyset> pseudoKeysetsOf(List<EncryptedKeysetWrapper> encryptedKeysets) {
        return encryptedKeysets.stream()
                .map(e -> (PseudoKeyset) e)
                .toList();
    }

    private static Map<PseudoFuncRule, PseudoFuncDeclaration> pseudoFuncDeclarationsOf(Collection<PseudoFuncRule> rules) {
        return rules.stream().collect(java.util.stream.Collectors.toMap(
                rule -> rule,
                rule -> PseudoFuncDeclaration.fromString(rule.getFunc())
        ));
    }

    private static Optional<PseudoFuncRuleMatch> findPseudoFunc(PseudoFuncs pseudoFuncs,
                                                                Map<String, Optional<PseudoFuncRuleMatch>> fieldMatchCache,
                                                                FieldDescriptor field) {
        return fieldMatchCache.computeIfAbsent(field.getPath(), p -> pseudoFuncs.findPseudoFunc(field));
    }

    private record PseudoFuncsCacheKey(List<String> funcs, List<String> keysetSignatures) {}

    private record PseudoFuncContext(PseudoFuncs funcs,
                                     Map<PseudoFuncRule, PseudoFuncDeclaration> declarations,
                                     Map<String, Optional<PseudoFuncRuleMatch>> fieldMatchCache) {}

    public class SingleFieldProcessor {
        private final FieldDescriptor fieldDescriptor;
        private final List<SingleFieldStep> steps;
        private final PseudoMetadataProcessor metadataProcessor;
        private final Set<String> metadataAdded;
        private final String normalizedPath;
        private final boolean minimalMetricsMode;

        private SingleFieldProcessor(FieldDescriptor fieldDescriptor,
                                     List<PseudoFuncContext> contexts,
                                     PseudoMetadataProcessor metadataProcessor,
                                     Set<String> metadataAdded,
                                     boolean minimalMetricsMode) {
            this.fieldDescriptor = fieldDescriptor;
            this.steps = contexts.stream()
                    .map(context -> findPseudoFunc(context.funcs, context.fieldMatchCache, fieldDescriptor)
                            .map(match -> new SingleFieldStep(match, context.declarations.get(match.getRule())))
                            .orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            this.metadataProcessor = metadataProcessor;
            this.metadataAdded = metadataAdded;
            this.normalizedPath = normalizePath(fieldDescriptor.getPath());
            this.minimalMetricsMode = minimalMetricsMode;
        }

        public String init(String varValue) {
            String current = varValue;
            for (SingleFieldStep step : steps) {
                if (current != null) {
                    step.func.init(PseudoFuncInput.of(current), TransformDirection.APPLY);
                }
            }
            return current;
        }

        public String pseudonymize(String varValue) {
            if (varValue == null) {
                if (!minimalMetricsMode) {
                    metadataProcessor.addMetric(FieldMetric.NULL_VALUE);
                }
                return null;
            }
            String current = varValue;
            for (SingleFieldStep step : steps) {
                if (current == null) {
                    if (!minimalMetricsMode && !(step.func instanceof MapFunc)) {
                        metadataProcessor.addMetric(FieldMetric.NULL_VALUE);
                    }
                    continue;
                }

                if (current.length() < 4 && step.fpeLimited) {
                    if (!minimalMetricsMode) {
                        metadataProcessor.addMetric(FieldMetric.FPE_LIMITATION);
                    }
                    current = step.mapFailureStrategy == MapFailureStrategy.RETURN_ORIGINAL ? current : null;
                    continue;
                }

                PseudoFuncOutput output = step.func.apply(PseudoFuncInput.of(current));

                if (!minimalMetricsMode) {
                    output.getWarnings().forEach(metadataProcessor::addLog);
                }
                String sidSnapshotDate = output.getMetadata().getOrDefault(MapFuncConfig.Param.SNAPSHOT_DATE, null);
                String mapFailureMetadata = output.getMetadata().getOrDefault(MAP_FAILURE_METADATA, null);
                current = output.getValue();

                if (!minimalMetricsMode) {
                    if (step.isSidMapping && mapFailureMetadata != null) {
                        metadataProcessor.addMetric(FieldMetric.MISSING_SID);
                    } else if (step.isSidMapping) {
                        metadataProcessor.addMetric(FieldMetric.MAPPED_SID);
                    }

                    if (step.isSidMapping) {
                        addMetadata(step, sidSnapshotDate);
                    } else if (!step.metadataAddedOnce) {
                        addMetadata(step, null);
                        step.metadataAddedOnce = true;
                    }
                }
            }
            return current;
        }

        private void addMetadata(SingleFieldStep step, String sidSnapshotDate) {
            String metadataKey = normalizedPath + "|" + step.declaration.getFuncName() + "|" + sidSnapshotDate;
            if (metadataAdded.add(metadataKey)) {
                metadataProcessor.addMetadata(FieldMetadata.builder()
                        .shortName(fieldDescriptor.getName())
                        .dataElementPath(normalizedPath)
                        .encryptionKeyReference(step.declaration.getArgs().getOrDefault(KEY_REFERENCE, null))
                        .encryptionAlgorithm(step.func.getAlgorithm())
                        .stableIdentifierVersion(sidSnapshotDate)
                        .stableIdentifierType(step.isSidMapping)
                        .encryptionAlgorithmParameters(step.declaration.getArgs())
                        .build());
            }
        }

        public PseudoMetadataProcessor metadataProcessor() {
            return metadataProcessor;
        }
    }

    private class SingleFieldStep {
        private final PseudoFunc func;
        private final PseudoFuncDeclaration declaration;
        private final boolean isSidMapping;
        private final boolean fpeLimited;
        private final MapFailureStrategy mapFailureStrategy;
        private boolean metadataAddedOnce;

        private SingleFieldStep(PseudoFuncRuleMatch match, PseudoFuncDeclaration declaration) {
            this.func = match.getFunc();
            this.declaration = declaration;
            this.isSidMapping = declaration.getFuncName().equals(PseudoFuncNames.MAP_SID)
                    || declaration.getFuncName().equals(PseudoFuncNames.MAP_SID_FF31)
                    || declaration.getFuncName().equals(PseudoFuncNames.MAP_SID_DAEAD);
            this.fpeLimited = func instanceof FpeFunc
                    || func instanceof TinkFpeFunc
                    || declaration.getFuncName().equals(PseudoFuncNames.MAP_SID)
                    || declaration.getFuncName().equals(PseudoFuncNames.MAP_SID_FF31);
            this.mapFailureStrategy = getMapFailureStrategy(declaration.getArgs());
            this.metadataAddedOnce = false;
        }
    }

    private MapFailureStrategy getMapFailureStrategy(Map<String, String> config) {
        return Optional.ofNullable(
                config.getOrDefault(MapFuncConfig.Param.MAP_FAILURE_STRATEGY, null)
        ).map(String::valueOf).map(MapFailureStrategy::valueOf).orElse(MapFailureStrategy.RETURN_ORIGINAL);
    }
}
