package no.ssb.dlp.pseudo.service.tracing;

import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.core.type.Argument;
import io.reactivex.Flowable;
import jakarta.inject.Singleton;
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

@Singleton
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

        try (Scope scope = WithSpanContext.withSpan(Context.current(), span).makeCurrent()) {
            addSpanAttributes(span, context);
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

    private static void addSpanAttributes(Span span, MethodInvocationContext<Object, Object> context) {
        Argument<?>[] arguments = context.getArguments();
        Object[] values = context.getParameterValues();
        for (int i = 0; i < arguments.length; i++) {
            Argument<?> argument = arguments[i];
            if (!argument.getAnnotationMetadata().hasAnnotation(SpanAttribute.class)) {
                continue;
            }

            String name = argument.getAnnotationMetadata()
                    .stringValue(SpanAttribute.class)
                    .orElse("");
            if (name.isEmpty()) {
                name = argument.getName();
            }
            if (name == null || name.isEmpty()) {
                continue;
            }

            Object value = i < values.length ? values[i] : null;
            setAttribute(span, name, value);
        }
    }

    private static void setAttribute(Span span, String attributeName, Object value) {
        switch (value) {
            case String stringValue -> span.setAttribute(attributeName, stringValue);
            case Boolean booleanValue -> span.setAttribute(attributeName, booleanValue);
            case Long longValue -> span.setAttribute(attributeName, longValue);
            case Integer intValue -> span.setAttribute(attributeName, intValue.longValue());
            case Short shortValue -> span.setAttribute(attributeName, shortValue.longValue());
            case Byte byteValue -> span.setAttribute(attributeName, byteValue.longValue());
            case Double doubleValue -> span.setAttribute(attributeName, doubleValue);
            case Float floatValue -> span.setAttribute(attributeName, floatValue.doubleValue());
            case Character charValue -> span.setAttribute(attributeName, String.valueOf(charValue));
            case null, default -> span.setAttribute(attributeName, String.valueOf(value));
        }
    }
}
