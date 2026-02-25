package no.ssb.dlp.pseudo.service.security;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.reactivex.Flowable;
import org.reactivestreams.Publisher;

import jakarta.inject.Singleton;

/**
 * This class is an implementation of the HttpServerFilter interface provided by Micronaut framework.
 * It filters incoming HTTP requests and restricts access to all endpoints except /pseudonymize/file.
 * This filter is only applied when the property "endpoints.cloud-run.enabled" is set to "true".
 */
@Filter("/**")
@Singleton
@Requires(property = "endpoints.cloud-run.enabled", value = "true")
public class CloudRunEndpointFilter implements HttpServerFilter {
    /**
     * Filters incoming HTTP requests and restricts access to all endpoints except /pseudonymize/file.
     * This method checks if the path in the HTTP request is "/pseudonymize/file", and if it is, the
     * request is passed on to the next filter in the chain. If it is not, an HTTP not found response
     * is returned.
     *
     * @param request The incoming HTTP request.
     * @param chain   The server filter chain to which this filter belongs.
     * @return A publisher that emits a mutable HTTP response that represents the server's response.
     */
    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String path = request.getUri().getPath();
        if (path.equals("/pseudonymize/file") || path.equals("/pseudonymize/field") || path.equals("/pseudonymize/field-fast")) {
            return chain.proceed(request);
        } else {
            return Flowable.just(HttpResponse.notFound());
        }
    }
}
