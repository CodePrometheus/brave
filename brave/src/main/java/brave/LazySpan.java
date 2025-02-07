/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave;

import brave.handler.MutableSpan;
import brave.propagation.TraceContext;

/**
 * This defers creation of a span until first public method call.
 * 直到第一次调用公共方法之前，才会创建 span。
 *
 * <p>This type was created to reduce overhead for code that calls {@link Tracer#currentSpan()},
 * but without ever using the result.
 * 该类型是为了减少调用 {@link Tracer#currentSpan()} 的代码的开销，但是从来没有使用结果。
 */
final class LazySpan extends Span {
  final Tracer tracer;
  TraceContext context;
  Span delegate;

  LazySpan(Tracer tracer, TraceContext context) {
    this.tracer = tracer;
    this.context = context;
  }

  @Override public boolean isNoop() {
    return span().isNoop();
  }

  @Override public TraceContext context() {
    return span().context();
  }

  @Override public SpanCustomizer customizer() {
    return new SpanCustomizerShield(this);
  }

  @Override public Span start() {
    return span().start();
  }

  @Override public Span start(long timestamp) {
    return span().start(timestamp);
  }

  @Override public Span name(String name) {
    return span().name(name);
  }

  @Override public Span kind(Kind kind) {
    return span().kind(kind);
  }

  @Override public Span annotate(String value) {
    return span().annotate(value);
  }

  @Override public Span annotate(long timestamp, String value) {
    return span().annotate(timestamp, value);
  }

  @Override public Span tag(String key, String value) {
    return span().tag(key, value);
  }

  @Override public Span error(Throwable throwable) {
    return span().error(throwable);
  }

  @Override public Span remoteServiceName(String remoteServiceName) {
    return span().remoteServiceName(remoteServiceName);
  }

  @Override public boolean remoteIpAndPort(String remoteIp, int remotePort) {
    return span().remoteIpAndPort(remoteIp, remotePort);
  }

  @Override public void finish() {
    span().finish();
  }

  @Override public void finish(long timestamp) {
    span().finish(timestamp);
  }

  @Override public void abandon() {
    if (delegate == null) return; // prevent resurrection
    span().abandon();
  }

  @Override public void flush() {
    if (delegate == null) return; // prevent resurrection
    span().flush();
  }

  @Override public String toString() {
    return "LazySpan(" + context + ")";
  }

  /**
   * This also matches equals against an actual span. The rationale is least surprise to the user,
   * as code should not act differently given an instance of lazy {@link NoopSpan} or {@link
   * RealSpan}.
   */
  @Override public boolean equals(Object o) {
    if (o == this) return true;
    if (o instanceof LazySpan) {
      return context.equals(((LazySpan) o).context);
    } else if (o instanceof RealSpan) {
      return context.equals(((RealSpan) o).context);
    } else if (o instanceof NoopSpan) {
      return context.equals(((NoopSpan) o).context);
    }
    return false;
  }

  @Override public int hashCode() {
    return context.hashCode();
  }

  /**
   * This does not guard on all concurrent edge cases assigning the delegate field. That's because
   * this type is only used when a user calls {@link Tracer#currentSpan()}, which is unlikley to be
   * exposed in such a way that multiple threads end up in a race assigning the field. Finally,
   * there is no state risk if {@link Tracer#toSpan(TraceContext)} is called concurrently. Duplicate
   * instances of span may occur, but they would share the same {@link MutableSpan} instance
   * internally.
   * 这不会在所有并发边缘情况下保护分配委托字段。这是因为这种类型仅在用户调用 {@link Tracer#currentSpan()} 时使用，
   * 这种情况不太可能以多线程的方式暴露出来，以便多个线程在分配字段时发生竞争。最后，如果同时调用 {@link Tracer#toSpan(TraceContext)}，
   * 则没有状态风险。可能会出现 span 的重复实例，但它们在内部共享相同的 {@link MutableSpan} 实例。
   */
  Span span() {
    Span result = delegate;
    if (result != null) return result;
    delegate = tracer.toSpan(context);
    context = delegate.context();
    return delegate;
  }
}
