package no.ssb.dlp.pseudo.service.pseudo;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.KmsClients;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import no.ssb.dlp.pseudo.core.PseudoException;

import java.security.GeneralSecurityException;
import java.time.Duration;

@Slf4j
@Factory
class AeadCacheFactory {
    @Singleton
    @Named("aeadCache")
    LoadingCache<String, Aead> aeadCache() {
        return Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(Duration.ofMinutes(2))
                .build(k -> {
                    try {
                        return KmsClients.get(k).getAead(k);
                    } catch (GeneralSecurityException e) {
                        throw new PseudoException("Error fetching key from KMS:", e);
                    }
                });
    }
}