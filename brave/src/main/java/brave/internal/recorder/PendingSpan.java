/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.internal.recorder;

import brave.Clock;
import brave.handler.MutableSpan;
import brave.internal.InternalPropagation;
import brave.internal.Nullable;
import brave.propagation.TraceContext;

import java.lang.ref.WeakReference;

/**
 * This is the value of a map entry in {@link PendingSpans}, whose key is a weak reference to {@link
 * #context()}.
 *
 * <p>{@link #context()} is cached so that externalized forms of a trace context to be swapped for
 * the one in use. It is a weak reference as otherwise it would prevent the corresponding map key
 * from being garbage collected.
 * <p>
 * /
 * * 这是 {@link PendingSpans} 中一个映射条目的值，其键是对 {@link #context()} 的弱引用。
 * *
 * * <p>{@link #context()} 被缓存，以便可以将外部化的跟踪上下文形式替换为正在使用的上下文。它是一个弱引用，否则它会阻止相应的映射键被垃圾回收。
 * PendingSpan 类在 brave 中的作用是管理和跟踪一个特定的 span（跨度）的状态和上下文。
 * 它使用弱引用来引用 TraceContext，以便在垃圾回收时不会阻止相应的映射键被回收。
 * 这个类还提供了获取 span 状态和时钟的方法，以确保在整个跟踪过程中时间戳的一致性。
 */
public final class PendingSpan extends WeakReference<TraceContext> {
  final MutableSpan span;
  final TickClock clock;
  final TraceContext handlerContext;

  PendingSpan(TraceContext context, MutableSpan span, TickClock clock) {
    super(context);
    this.span = span;
    this.clock = clock;
    this.handlerContext = InternalPropagation.instance.shallowCopy(context);
  }

  /** Returns the context for this span unless it was cleared due to GC. */
  @Nullable public TraceContext context() {
    return get();
  }

  /** Returns the state currently accumulated for this trace ID and span ID */
  public MutableSpan state() {
    return span;
  }

  /** Returns a clock that ensures startTimestamp consistency across the trace */
  public Clock clock() {
    return clock;
  }
}
