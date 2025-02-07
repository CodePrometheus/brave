/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave;

import brave.propagation.CurrentTraceContext;
import brave.propagation.TraceContext;

/**
 * Used to model the latency of an operation within a method block.
 * 用于模拟方法块内操作的延迟
 *
 * Here's a typical example of synchronous tracing from perspective of the scoped span:
 * 这是一个典型的同步跟踪示例，从作用域跨度的角度来看：
 * <pre>{@code
 * // Note span methods chain. Explicitly start the span when ready.
 * // 注意span方法链。准备好后显式启动span。
 * ScopedSpan span = tracer.startScopedSpan("encode");
 * try {
 *   return encoder.encode();
 * } catch (RuntimeException | Error e) {
 *   span.error(e); // Unless you handle exceptions, you might not know the operation failed!
 *   throw e;
 * } finally {
 *   span.finish(); // finish - start = the duration of the operation in microseconds
 * }
 * }</pre>
 *
 * <h3>Usage notes</h3>
 * All methods return {@linkplain ScopedSpan} for chaining, but the instance is always the same.
 * Also, this type is intended for in-process synchronous code. Do not leak this onto another
 * thread: it is not thread-safe. For advanced features or remote commands, use {@link Span}
 * instead.
 * 所有方法都返回{@linkplain ScopedSpan}以进行链接，但实例始终相同。此外，此类型适用于进程内同步代码。
 * 不要将其泄漏到另一个线程：它不是线程安全的。对于高级功能或远程命令，请改用{@link Span}。
 *
 * @since 4.19
 */
public abstract class ScopedSpan implements SpanCustomizer {
  /**
   * When true, no recording will take place, so no data is reported on finish. However, the trace
   * context is in scope until {@link #finish()} is called.
   * 当为true时，不会进行记录，因此在完成时不会报告任何数据。但是，跟踪上下文在调用{@link #finish()}之前一直处于范围内。
   *
   * @since 4.19
   */
  public abstract boolean isNoop();

  /**
   * Returns the trace context associated with this span
   * 返回与此跨度关联的跟踪上下文
   *
   * @since 4.19
   */
  // This api is exposed as there's always a context in scope by definition, and the context is
  // needed for methods like BaggageField.updateValue
  public abstract TraceContext context();

  /**
   * {@inheritDoc}
   *
   * @since 5.11
   */
  @Override public abstract ScopedSpan name(String name);

  /**
   * {@inheritDoc}
   *
   * @since 4.19
   */
  @Override public abstract ScopedSpan tag(String key, String value);

  /**
   * {@inheritDoc}
   *
   * @since 4.19
   */
  @Override public abstract ScopedSpan annotate(String value);

  /**
   * Records an error that impacted this operation.
   *
   * <p><em>Note:</em> Calling this does not {@linkplain #finish() finish} the span.
   *
   * @since 4.19
   */
  public abstract ScopedSpan error(Throwable throwable);

  /**
   * Closes the {@link CurrentTraceContext#newScope(TraceContext) scope} associated with this span,
   * then reports the span complete, assigning the most precise duration possible.
   *
   * @since 4.19
   */
  public abstract void finish();

  ScopedSpan() {
  }
}
