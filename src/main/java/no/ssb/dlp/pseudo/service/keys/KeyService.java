package no.ssb.dlp.pseudo.service.keys;

import lombok.RequiredArgsConstructor;
import no.ssb.dlp.pseudo.core.tink.model.EncryptedKeysetWrapper;
import no.ssb.dlp.pseudo.core.util.Json;
import no.ssb.dlp.pseudo.core.util.TinkUtil;
import no.ssb.dlp.pseudo.service.tink.KmsConfig;

import jakarta.inject.Singleton;
import java.net.URI;

@Singleton
@RequiredArgsConstructor
public class KeyService {

    private final KmsConfig kmsConfig;

    public EncryptedKeysetWrapper createNewDataEncryptionKey(URI kekUri, String keyTemplateName) {
        kekUri = validateOrGetDefaultKekUri(kekUri);

        try {
            String keysetJson = TinkUtil.newWrappedKeyJson(kekUri.toString(), keyTemplateName);
            EncryptedKeysetWrapper keyset = Json.toObject(EncryptedKeysetWrapper.class, keysetJson);
            keyset.setKekUri(kekUri);
            return keyset;
        }
        catch (Exception e) {
            throw new KeyGenerationException("Error generating data encryption key (encrypted by %s)".formatted(kekUri), e);
        }
    }

    private URI validateOrGetDefaultKekUri(final URI kekUri) {
        if (kekUri == null) {
            return getDefaultKekUri();
        }

        if (! kmsConfig.getKeyUris().stream()
                .anyMatch(u -> u.equals(kekUri))) {
            throw new UnsupportedKeyException("'%s' is not a configured key encryption key".formatted(kekUri));
        }

        return kekUri;
    }

    private URI getDefaultKekUri() {
        return kmsConfig.getKeyUris().get(0);
    }

    public static class UnsupportedKeyException extends RuntimeException {
        public UnsupportedKeyException(String message) {
            super(message);
        }
    }

    public static class KeyGenerationException extends RuntimeException {
        public KeyGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
