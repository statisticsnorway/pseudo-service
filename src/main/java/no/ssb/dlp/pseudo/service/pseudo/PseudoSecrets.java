package no.ssb.dlp.pseudo.service.pseudo;

import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import no.ssb.dlp.pseudo.core.PseudoSecret;
import no.ssb.dlp.pseudo.service.secrets.SecretService;
import no.ssb.dlp.pseudo.service.tracing.WithSpan;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Singleton
public class PseudoSecrets {

    private static final String DEFAULT_PSEUDO_SECRET_TYPE = "AES256";
    private final SecretService secretService;
    private final Map<String, PseudoSecret> configuredPseudoSecrets;

    /**
     * Initialize PseudoSecrets
     *
     * @param configuredPseudoSecrets pseudo secrets coming from the config or environment
     */
    public PseudoSecrets(
            SecretService secretService,
            @Property(name = "pseudo.secrets")
            Map<String, PseudoSecret> configuredPseudoSecrets) {
        this.secretService = secretService;
        this.configuredPseudoSecrets = Optional.ofNullable(configuredPseudoSecrets).orElse(Map.of());
    }

    /**
     * Resolve pseudo secrets - either from secret manager or via environment.
     *
     * @return List of resolved pseudo secrets
     */
    public List<PseudoSecret> resolve() {
        return resolvePseudoSecrets(configuredPseudoSecrets);
    }

    /**
     * <p>Clean up configured pseudo secrets and resolve pseudo secret contents.</p>
     *
     * <p>If content is specified, then this is used. If not, attempt to resolve pseudo secret from SecretService using
     * the PseudoSecret::id and PseudoSecret::version properties.</p>
     *
     * @param configuredPseudoSecrets a Map named pseudo secrets
     * @return a List of cleaned up, resolved pseudo secrets
     */
    @WithSpan
    List<PseudoSecret> resolvePseudoSecrets(Map<String, PseudoSecret> configuredPseudoSecrets) {
        if (configuredPseudoSecrets == null) {
            return List.of();
        }

        return configuredPseudoSecrets.entrySet().stream()
          .map(e -> {
              PseudoSecret secret = e.getValue();

              secret.setName(e.getKey());

              // Resolve secret content if and only if 'id' is specified AND 'content' is not specified
              if (secret.getId() != null && secret.getContent() == null) {
                  secret.setBase64EncodedContent(secretService.getCacheableSecret(secret.getId(), secret.getVersion()));
              }

              if (secret.getContent() == null) {
                  throw new InvalidPseudoSecretException("Invalid pseudo secret '" + e.getKey() + "': Unable to resolve content");
              }

              if (secret.getType() == null) {
                  secret.setType(DEFAULT_PSEUDO_SECRET_TYPE);
              }

              return secret;
          })
        .toList();

    }

    static class InvalidPseudoSecretException extends RuntimeException {
        public InvalidPseudoSecretException(String message) {
            super(message);
        }
    }

}
