package no.ssb.dlp.pseudo.service.tink;

import com.google.crypto.tink.daead.DeterministicAeadConfig;
import com.google.crypto.tink.integration.gcpkms.GcpKmsClient;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import lombok.extern.slf4j.Slf4j;
import no.ssb.crypto.tink.fpe.FpeConfig;
import no.ssb.dlp.pseudo.core.PseudoException;

import jakarta.inject.Singleton;
import java.net.URI;
import java.util.Optional;

@Context
@Singleton
@Slf4j
@Requires(notEnv = Environment.TEST)
public class TinkInitizializer {

    public TinkInitizializer(KmsConfig kmsConfig) {
        try {
            // Register DAEAD config
            log.info("Initialize Tink DAEAD");
            DeterministicAeadConfig.register();

            // Register FPE config
            log.info("Initialize Tink FPE");
            FpeConfig.register();

            // Register Key Encryption Keys
            for (URI keyUri : kmsConfig.getKeyUris()) {
                log.info("Register KMS key {}", keyUri);
                GcpKmsClient.register(Optional.of(keyUri.toString()), Optional.ofNullable(kmsConfig.getCredentialsPath()));
            }
        }
        catch (Exception e) {
            throw new PseudoException("Error initializing Tink", e);
        }
    }

}
