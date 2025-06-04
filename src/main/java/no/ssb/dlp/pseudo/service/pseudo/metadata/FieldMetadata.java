package no.ssb.dlp.pseudo.service.pseudo.metadata;

import lombok.Builder;
import lombok.Value;
import no.ssb.dapla.metadata.EncryptionAlgorithmParameter;
import no.ssb.dapla.metadata.PseudoVariable;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class FieldMetadata {

    // Type of stable ID identifier that is used prior to pseudonymization. Currently only FREG_SNR is supported.
    private static final String STABLE_IDENTIFIER_TYPE = "FREG_SNR";

    String shortName;
    String dataElementPath;
    String dataElementPattern;
    String encryptionKeyReference;
    String encryptionAlgorithm;
    String stableIdentifierVersion;
    boolean stableIdentifierType;
    Map<String, String> encryptionAlgorithmParameters;

    public PseudoVariable toDatadocPseudoVariable() {
        return PseudoVariable.builder()
                .withShortName(shortName)
                .withDataElementPath(dataElementPath)
                .withDataElementPattern(dataElementPattern)
                .withEncryptionKeyReference(encryptionKeyReference)
                .withEncryptionAlgorithm(encryptionAlgorithm)
                .withEncryptionAlgorithmParameters(
                        toEncryptionAlgorithmParameters())
                .withStableIdentifierVersion(stableIdentifierVersion)
                .withStableIdentifierType(stableIdentifierType ? STABLE_IDENTIFIER_TYPE : null)
                .build();
    }

    private List<EncryptionAlgorithmParameter> toEncryptionAlgorithmParameters() {
        if (encryptionAlgorithmParameters == null) return null;
        return encryptionAlgorithmParameters.entrySet().stream().map(entry ->
            EncryptionAlgorithmParameter.builder()
                    .withAdditionalProperty(entry.getKey(), entry.getValue())
                .build())
        .toList();
    }
}