package no.ssb.dlp.pseudo.service.pseudo;

import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.ssb.dapla.dlp.pseudo.func.PseudoFuncInput;
import no.ssb.dapla.dlp.pseudo.func.PseudoFuncOutput;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static no.ssb.dlp.pseudo.core.PseudoOperation.DEPSEUDONYMIZE;
import static no.ssb.dlp.pseudo.core.PseudoOperation.PSEUDONYMIZE;
import static no.ssb.dlp.pseudo.core.func.PseudoFuncDeclaration.*;
import static no.ssb.dlp.pseudo.service.sid.SidMapper.*;

@RequiredArgsConstructor
@Singleton
@Slf4j
public class RecordMapProcessorFactory {
    private final PseudoSecrets pseudoSecrets;

    public RecordMapProcessor<PseudoMetadataProcessor> newPseudonymizeRecordProcessor(List<PseudoConfig> pseudoConfigs, String correlationId) {
        ValueInterceptorChain chain = new ValueInterceptorChain();
        PseudoMetadataProcessor metadataProcessor = new PseudoMetadataProcessor(correlationId);

        for (PseudoConfig config : pseudoConfigs) {
            final PseudoFuncs fieldPseudonymizer = newPseudoFuncs(config.getRules(),
                    pseudoKeysetsOf(config.getKeysets()));
            chain.preprocessor((f, v) -> init(fieldPseudonymizer, TransformDirection.APPLY, f, v));
            chain.register((f, v) -> process(PSEUDONYMIZE, fieldPseudonymizer, f, v, metadataProcessor));
        }
        return new RecordMapProcessor<>(chain, metadataProcessor);
    }

    public RecordMapProcessor<PseudoMetadataProcessor> newDepseudonymizeRecordProcessor(List<PseudoConfig> pseudoConfigs, String correlationId) {
        ValueInterceptorChain chain = new ValueInterceptorChain();
        PseudoMetadataProcessor metadataProcessor = new PseudoMetadataProcessor(correlationId);

        for (PseudoConfig config : pseudoConfigs) {
            final PseudoFuncs fieldDepseudonymizer = newPseudoFuncs(config.getRules(),
                    pseudoKeysetsOf(config.getKeysets()));
            chain.preprocessor((f, v) -> init(fieldDepseudonymizer, TransformDirection.RESTORE, f, v));
            chain.register((f, v) -> process(DEPSEUDONYMIZE, fieldDepseudonymizer, f, v, metadataProcessor));
        }

        return new RecordMapProcessor<>(chain, metadataProcessor);
    }

    public RecordMapProcessor<PseudoMetadataProcessor> newRepseudonymizeRecordProcessor(PseudoConfig sourcePseudoConfig,
                                                               PseudoConfig targetPseudoConfig, String correlationId) {
        final PseudoFuncs fieldDepseudonymizer = newPseudoFuncs(sourcePseudoConfig.getRules(),
                pseudoKeysetsOf(sourcePseudoConfig.getKeysets()));
        final PseudoFuncs fieldPseudonymizer = newPseudoFuncs(targetPseudoConfig.getRules(),
                pseudoKeysetsOf(targetPseudoConfig.getKeysets()));
        PseudoMetadataProcessor metadataProcessor = new PseudoMetadataProcessor(correlationId);
        return new RecordMapProcessor<>(
                new ValueInterceptorChain()
                        .preprocessor((f, v) -> init(fieldDepseudonymizer, TransformDirection.RESTORE, f, v))
                        .register((f, v) -> process(DEPSEUDONYMIZE, fieldDepseudonymizer, f, v, metadataProcessor))
                        .register((f, v) -> process(PSEUDONYMIZE, fieldPseudonymizer, f, v, metadataProcessor)),
                metadataProcessor);
    }

    protected PseudoFuncs newPseudoFuncs(Collection<PseudoFuncRule> rules,
                                         Collection<PseudoKeyset> keysets) {
        return new PseudoFuncs(rules, pseudoSecrets.resolve(), keysets);
    }

    private String init(PseudoFuncs pseudoFuncs, TransformDirection direction, FieldDescriptor field, String varValue) {
        if (varValue != null) {
            pseudoFuncs.findPseudoFunc(field).ifPresent(pseudoFunc ->
                    pseudoFunc.getFunc().init(PseudoFuncInput.of(varValue), direction));
        }
        return varValue;
    }

    private String process(PseudoOperation operation,
                           PseudoFuncs func,
                           FieldDescriptor field,
                           String varValue,
                           PseudoMetadataProcessor metadataProcessor) {
        PseudoFuncRuleMatch match = func.findPseudoFunc(field).orElse(null);

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
            PseudoFuncDeclaration funcDeclaration = PseudoFuncDeclaration.fromString(match.getRule().getFunc());

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
                metadataProcessor.addMetadata(FieldMetadata.builder()
                        .shortName(field.getName())
                        .dataElementPath(normalizePath(field.getPath())) // Skip leading slash and use dot as separator
                        .dataElementPattern(match.getRule().getPattern())
                        .encryptionKeyReference(funcDeclaration.getArgs().getOrDefault(KEY_REFERENCE, null))
                        .encryptionAlgorithm(match.getFunc().getAlgorithm())
                        .stableIdentifierVersion(sidSnapshotDate)
                        .stableIdentifierType(isSidMapping)
                        .encryptionAlgorithmParameters(funcDeclaration.getArgs())
                        .build());
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

    private MapFailureStrategy getMapFailureStrategy(Map<String, String> config) {
        return Optional.ofNullable(
                config.getOrDefault(MapFuncConfig.Param.MAP_FAILURE_STRATEGY, null)
        ).map(String::valueOf).map(MapFailureStrategy::valueOf).orElse(MapFailureStrategy.RETURN_ORIGINAL);
    }
}
