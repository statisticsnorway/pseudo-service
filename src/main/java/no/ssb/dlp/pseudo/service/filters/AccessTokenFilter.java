package no.ssb.dlp.pseudo.service.filters;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.filter.ClientFilterChain;
import io.micronaut.http.filter.HttpClientFilter;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.annotation.Nullable;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import no.ssb.dlp.pseudo.service.tracing.WithSpan;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.FileInputStream;
import java.net.URI;
import java.util.Optional;

/**
 * This filter will obtain an {@link AccessToken} and add it to the request. It can use credentials from either
 * Google's default Application Default Credentials or a custom Service Account (as opposed to the
 * {@see io.micronaut.gcp.http.client.GoogleAuthFilter} which only uses the Compute metadata server).
 */
@AccessTokenFilterMatcher
@Singleton
@Data
@Slf4j
public class AccessTokenFilter implements HttpClientFilter {

    @Inject
    private ApplicationContext applicationContext;
    @Nullable
    @Value("${gcp.http.client.filter.project-id}")
    private String projectId;
    private final GoogleCredentials credentials;

    @SneakyThrows
    public AccessTokenFilter(@Nullable @Value("${gcp.http.client.filter.credentials-path}") String credentialsPath) {
        if (credentialsPath == null) {
            log.info("Using Application Default Credentials");
            this.credentials = GoogleCredentials.getApplicationDefault();
        } else {
            log.info("Using Credentials from Service Account file: {}", credentialsPath);
            this.credentials = GoogleCredentials.fromStream(
                    new FileInputStream(credentialsPath));
        }
    }

    @SneakyThrows
    @WithSpan
    public Publisher<? extends HttpResponse<?>> doFilter(MutableHttpRequest<?> request, ClientFilterChain chain) {
        Optional<AccessTokenFilterConfig> config = getConfig(request);
        if (config.isPresent()) {
            request.bearerAuth(getAccessToken(config.get().getAudience()));
            setProjectIdHeader(request);
        } else {
            request.bearerAuth(getAccessToken(getAudienceFromRequest(request)));
            setProjectIdHeader(request);
        }
        return chain.proceed(request);
    }

    private void setProjectIdHeader(MutableHttpRequest<?> request) {
        if (projectId != null) {
            log.debug("Using projectId {} from config to override qoutaProjectId", projectId);
            request.getHeaders().add("x-goog-user-project", projectId);
        }
    }

    @SneakyThrows
    @WithSpan
    protected String getAccessToken(String audience) {
        return credentials.createScoped(audience).refreshAccessToken().getTokenValue();
    }

    private Optional<AccessTokenFilterConfig> getConfig(MutableHttpRequest<?> request) {
        final Optional<Object> serviceId = request.getAttribute("micronaut.http.serviceId");

        if (applicationContext != null && serviceId.isPresent()) {
            return applicationContext.findBean(AccessTokenFilterConfig.class, Qualifiers.byName(serviceId.get().toString()));
        }
        return Optional.empty();
    }

    private String getAudienceFromRequest(final MutableHttpRequest<?> request) {
        URI fullURI = request.getUri();
        return fullURI.getScheme() + "://" + fullURI.getHost();
    }

}
