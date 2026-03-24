package no.ssb.dlp.pseudo.service.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;

/**
 * Used to fetch the current span when called from inside a method annotated with
 * {@link no.ssb.dlp.pseudo.service.tracing.WithSpan}.
 * Instead of using {@link io.opentelemetry.api.trace.Span#current()}, use
 * {@link WithSpanContext#currentSpan()}.
 */
public final class WithSpanContext {
    private static final ContextKey<Span> WITH_SPAN_KEY = ContextKey.named("pseudo.withspan.span");

    private WithSpanContext(){ }

    public static Span currentSpan() {
        Span span = Context.current().get(WITH_SPAN_KEY);
        return span != null ? span : Span.current();
    }

    static Context withSpan(Context context, Span span) {
        return context.with(WITH_SPAN_KEY, span);
    }
}
