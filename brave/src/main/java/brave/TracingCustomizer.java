/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave;

import brave.baggage.BaggagePropagationCustomizer;
import brave.handler.SpanHandler;
import brave.propagation.CurrentTraceContextCustomizer;

/**
 * This allows configuration plugins to collaborate on building an instance of {@link Tracing}.
 * 这允许配置插件协作构建 {@link Tracing} 实例。
 *
 * <p>For example a customizer can configure {@linkplain Tracing.Builder#addSpanHandler(SpanHandler)
 * span handlers} without having to also configure the {@linkplain Tracing.Builder#localServiceName(String)
 * local service name}.
 * 例如，自定义器可以配置 {@linkplain Tracing.Builder#addSpanHandler(SpanHandler) span 处理器}，
 * 而无需配置 {@linkplain Tracing.Builder#localServiceName(String) local service name}。
 *
 * <h3>Integration examples</h3>
 *
 * <p>In practice, a dependency injection tool applies a collection of these instances prior to
 * {@link Tracing.Builder#build() building the tracing instance}. For example, an injected {@code
 * List<TracingCustomizer>} parameter to a provider of {@link Tracing}.
 * 在实践中，依赖注入工具在 {@link Tracing.Builder#build() 构建跟踪实例} 之前应用这些实例的集合。
 * 例如，一个提供 {@link Tracing} 的提供者的注入 {@code List<TracingCustomizer>} 参数。
 *
 * <p>Here are some examples, in alphabetical order:
 * <pre><ul>
 *   <li><a href="https://dagger.dev/multibindings.html">Dagger Set Multibindings</a></li>
 *   <li><a href="http://google.github.io/guice/api-docs/latest/javadoc/com/google/inject/multibindings/Multibinder.html">Guice Set Multibinder</a></li>
 *   <li><a href="https://docs.spring.io/spring/docs/current/spring-framework-reference/core.html#beans-autowired-annotation">Spring Autowired Collections</a></li>
 * </ul></pre>
 *
 * <p><em>Note</em>: This type is safe to implement as a lambda, or use as a method reference as it
 * is effectively a {@code FunctionalInterface}. It isn't annotated as such because the project has
 * a minimum Java language level 6.
 *
 * @see BaggagePropagationCustomizer
 * @see CurrentTraceContextCustomizer
 * @since 5.7
 */
// @FunctionalInterface, except Java language level 6. Do not add methods as it will break API!
public interface TracingCustomizer {
  /** Use to avoid comparing against null references */
  TracingCustomizer NOOP = new TracingCustomizer() {
    @Override public void customize(Tracing.Builder builder) {
    }

    @Override public String toString() {
      return "NoopTracingCustomizer{}";
    }
  };

  void customize(Tracing.Builder builder);
}
