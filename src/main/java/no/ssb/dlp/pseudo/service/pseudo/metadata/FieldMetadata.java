package no.ssb.dlp.pseudo.service.pseudo.metadata;

import lombok.Builder;
import lombok.Value;
import no.ssb.dapla.metadata.datadoc.EncryptionAlgorithmParameter;
import no.ssb.dapla.metadata.datadoc.Pseudonymization;
import no.ssb.dapla.metadata.datadoc.Variable;

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

    public Variable toDatadocPseudoVariable() {
        final var pseudonymization =
                Pseudonymization.builder()
                .withEncryptionAlgorithm(encryptionAlgorithm)
                .withEncryptionKeyReference(encryptionKeyReference)
                .withEncryptionAlgorithmParameters(
                 toEncryptionAlgorithmParameters()
                )
                .withStableIdentifierVersion(stableIdentifierVersion)
                .withStableIdentifierType(stableIdentifierType ? STABLE_IDENTIFIER_TYPE : null)
                .build();
        return Variable.builder()
                .withShortName(shortName)
                .withDataElementPath(dataElementPath)
                .withPseudonymization(pseudonymization)
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