package no.ssb.dlp.pseudo.service.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;

public final class WithSpanSupport {
    private static final ContextKey<Span> WITH_SPAN_KEY = ContextKey.named("pseudo.withspan.span");

    private WithSpanSupport() {
    }

    public static Span currentWithSpan() {
        Span span = Context.current().get(WITH_SPAN_KEY);
        return span != null ? span : Span.current();
    }

    static Context withSpan(Context context, Span span) {
        return context.with(WITH_SPAN_KEY, span);
    }
}
