/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.propagation;

import brave.Tracing;
import brave.internal.Nullable;

/**
 * In-process trace context propagation backed by a static thread local.
 * 基于静态线程本地的进程内跟踪上下文传播。
 *
 * <h3>Design notes</h3>
 *
 * <p>A static thread local ensures we have one context per thread, as opposed to one per thread-
 * tracer. This means all tracer instances will be able to see any tracer's contexts.
 * 静态线程本地确保我们每个线程有一个上下文，而不是每个线程-跟踪器。
 * 这意味着所有跟踪器实例都能看到任何跟踪器的上下文。
 *
 * <p>The trade-off of this (instance-based reference) vs the reverse: trace contexts are not
 * separated by tracer by default. For example, to make a trace invisible to another tracer, you
 * have to use a non-default implementation.
 * 这种（基于实例的引用）与反向之间的权衡：跟踪上下文默认情况下不会按跟踪器分开。
 * 例如，要使跟踪对另一个跟踪器不可见，您必须使用非默认实现。
 *
 * <p>Sometimes people make different instances of the tracer just to change configuration like
 * the local service name. If we used a thread-instance approach, none of these would be able to see
 * eachother's scopes. This would break {@link Tracing#currentTracer()} scope visibility in a way
 * few would want to debug. It might be phrased as "MySQL always starts a new trace and I don't know
 * why."
 * 有时人们只是为了更改本地服务名称等配置而制作不同的跟踪器实例。
 * 如果我们使用线程实例方法，那么这些实例将无法看到彼此的范围。
 * 这将以一种很少有人想要调试的方式破坏 {@link Tracing#currentTracer()} 范围可见性。
 * 它可能被表述为“MySQL 总是启动新跟踪，我不知道为什么。”
 *
 * <p>If you want a different behavior, use a different subtype of {@link CurrentTraceContext},
 * possibly your own, or raise an issue and explain what your use case is.
 */
public class ThreadLocalCurrentTraceContext extends CurrentTraceContext { // not final for backport
  public static CurrentTraceContext create() {
    return new Builder(DEFAULT).build();
  }

  public static Builder newBuilder() {
    return new Builder(DEFAULT);
  }

  /**
   * This component is backed by a possibly static shared thread local. Call this to clear the
   * reference when you are sure any residual state is due to a leak. This is generally only useful
   * in tests.
   *
   * @since 5.11
   */
  public void clear() {
    local.remove();
  }

  /** @since 5.11 */ // overridden for covariance
  public static final class Builder extends CurrentTraceContext.Builder {
    final ThreadLocal<TraceContext> local;

    Builder(ThreadLocal<TraceContext> local) {
      this.local = local;
    }

    @Override public Builder addScopeDecorator(ScopeDecorator scopeDecorator) {
      return (Builder) super.addScopeDecorator(scopeDecorator);
    }

    @Override public ThreadLocalCurrentTraceContext build() {
      return new ThreadLocalCurrentTraceContext(this);
    }
  }

  static final ThreadLocal<TraceContext> DEFAULT = new ThreadLocal<TraceContext>();

  @SuppressWarnings("ThreadLocalUsage") // intentional: to support multiple Tracer instances
  final ThreadLocal<TraceContext> local;
  final RevertToNullScope revertToNull;

  ThreadLocalCurrentTraceContext(Builder builder) {
    super(builder);
    if (builder.local == null) throw new NullPointerException("local == null");
    local = builder.local;
    revertToNull = new RevertToNullScope(local);
  }

  @Override public TraceContext get() {
    return local.get();
  }

  @Override public Scope newScope(@Nullable TraceContext currentSpan) {
    final TraceContext previous = local.get();
    local.set(currentSpan);
    Scope result = previous != null ? new RevertToPreviousScope(local, previous) : revertToNull;
    return decorateScope(currentSpan, result);
  }

  static final class RevertToNullScope implements Scope {
    final ThreadLocal<TraceContext> local;

    RevertToNullScope(ThreadLocal<TraceContext> local) {
      this.local = local;
    }

    @Override public void close() {
      local.set(null);
    }
  }

  static final class RevertToPreviousScope implements Scope {
    final ThreadLocal<TraceContext> local;
    final TraceContext previous;

    RevertToPreviousScope(ThreadLocal<TraceContext> local, TraceContext previous) {
      this.local = local;
      this.previous = previous;
    }

    @Override public void close() {
      local.set(previous);
    }
  }
}
