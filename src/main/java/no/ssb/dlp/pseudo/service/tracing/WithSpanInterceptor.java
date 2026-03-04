package no.ssb.dlp.pseudo.service.tracing;

import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.reactivex.Flowable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

public final class WithSpanInterceptor implements MethodInterceptor<Object, Object> {

    private static final Class<Context> OTEL_CONTEXT_KEY = Context.class;
    private final Tracer tracer;

    public WithSpanInterceptor(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("pseudo-service");
    }

    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        String spanNameOverride = context.getAnnotationMetadata()
                .stringValue(WithSpan.class)
                .orElse("");
        String spanName = !spanNameOverride.isEmpty()
                ? spanNameOverride
                : context.getDeclaringType().getSimpleName() + "." + context.getMethodName();

        Object result;
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            result = context.proceed();
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            span.end();
            throw e;
        }

        if (result instanceof Mono<?> mono) {
            return mono
                    .doOnError(span::recordException)
                    .doOnError(e -> span.setStatus(StatusCode.ERROR))
                    .doFinally(signalType -> span.end())
                    .contextWrite(ctx -> ctx.put(OTEL_CONTEXT_KEY, Context.current().with(span)));
        }

        if (result instanceof Flux<?> flux) {
            return flux
                    .doOnError(span::recordException)
                    .doOnError(e -> span.setStatus(StatusCode.ERROR))
                    .doFinally(signalType -> span.end())
                    .contextWrite(ctx -> ctx.put(OTEL_CONTEXT_KEY, Context.current().with(span)));
        }

        if (result instanceof Flowable<?> flowable) {
            return flowable
                    .doOnError(span::recordException)
                    .doOnError(e -> span.setStatus(StatusCode.ERROR))
                    .doFinally(span::end);
        }

        if (result instanceof Publisher<?> publisher) {
            return Flux.from(publisher)
                    .doOnError(span::recordException)
                    .doOnError(e -> span.setStatus(StatusCode.ERROR))
                    .doFinally(signalType -> span.end())
                    .contextWrite(ctx -> ctx.put(OTEL_CONTEXT_KEY, Context.current().with(span)));
        }

        span.end();
        return result;
    }
}
