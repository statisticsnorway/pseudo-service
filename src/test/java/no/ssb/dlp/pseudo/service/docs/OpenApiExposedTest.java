package no.ssb.dlp.pseudo.service.docs;

import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@MicronautTest
class OpenApiExposedTest {

    @Test
    void openApi(@Client("http://127.0.0.1:10210") HttpClient httpClient) {
        final var client = httpClient.toBlocking();
        assertDoesNotThrow(() -> client.exchange("/api-docs/dapla-pseudo-service-1.0.yml"));
    }
}
