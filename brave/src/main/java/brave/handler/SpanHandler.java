/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.handler;

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.internal.Nullable;
import brave.propagation.TraceContext;
import java.lang.ref.WeakReference;

/**
 * This tracks one recording of a {@link TraceContext}. Common implementations include span
 * reporting (ex to Zipkin) and data manipulation, such as redaction for security purposes.
 * 这跟踪了一个 {@link TraceContext} 的记录。常见的实现包括 span 报告（例如 Zipkin）和数据操作，例如出于安全目的的数据消除。
 *
 * <h3>Relationship to Span lifecycle</h3>
 * The pair of {@link #begin} and {@link #end} seems the same as the span lifecycle. In most cases
 * it will be the same, but you cannot assume this.
 * {@link #begin} 和 {@link #end} 的配对似乎与 span 生命周期相同。在大多数情况下，它将是相同的，但您不能假设这一点。
 *
 * <p>A {@link TraceContext} could be recorded twice, for example, if a long operation
 * began, called {@link Span#flush()} (recording 1) and later called {@link Span#finish()}
 * (recording 2). A {@link TraceContext} could be disrupted by garbage collection resulting in a
 * {@link Cause#ABANDONED}. A user could even {@linkplain Cause#ABANDONED abandon} a span without
 * recording anything!
 * 例如，如果开始了一个长时间的操作，调用了 {@link Span#flush()}（记录 1），然后稍后调用了 {@link Span#finish()}（记录 2），则可能两次记录一个 {@link TraceContext}。
 * {@link TraceContext} 可能会被垃圾回收打断，导致 {@link Cause#ABANDONED}。用户甚至可以 {@linkplain Cause#ABANDONED 放弃}一个 span 而不记录任何内容！
 *
 * <p>Collectors that process finished spans will need to look at the {@link Cause} and {@link
 * MutableSpan} collected. For example, {@link Cause#FINISHED} is usually a good enough heuristic to
 * find complete spans.
 * 处理完成的 span 的收集器将需要查看收集的 {@link Cause} 和 {@link MutableSpan}。
 * 例如，{@link Cause#FINISHED} 通常是一个足够好的启发式方法来查找完整的 span。
 *
 * <h3>Advanced Notes</h3>
 * <p>It is important to do work quickly as callbacks are run on the same thread as application
 * code. However, do not mutate {@link MutableSpan} between callbacks, as it is not thread safe.
 * 快速执行工作很重要，因为回调在与应用程序代码相同的线程上运行。但是，在回调之间不要更改 {@link MutableSpan}，因为它不是线程安全的。
 *
 * <p>The {@link TraceContext} and {@link MutableSpan} parameter from {@link #begin} will be
 * the same reference for {@link #end}.
 * {@link #begin} 中的 {@link TraceContext} 和 {@link MutableSpan} 参数将是 {@link #end} 的相同引用。
 *
 * <p>If caching the context or span parameters between callbacks, consider a {@link WeakReference}
 * to avoid holding up garbage collection.
 * 如果在回调之间缓存上下文或 span 参数，请考虑使用 {@link WeakReference}，以避免阻止垃圾回收。
 *
 * <p>The {@link #begin} callback primarily supports tracking of children, or partitioning of
 * data for backend that needs to see an entire {@linkplain TraceContext#localRootId() local root}.
 * {@link #begin} 回调主要支持跟踪子级，或者为需要查看整个 {@linkplain TraceContext#localRootId() local root} 的后端分区数据。
 *
 * @since 5.12
 */
public abstract class SpanHandler {
  /**
   * What ended the data collection?
   * 什么结束了数据收集？
   *
   * @since 5.12
   */
  public enum Cause {
    /**
     * Called on {@link Span#abandon()}.
     *
     * <p>This is useful when counting children. Decrement your counter when this occurs as the
     * span will not be reported.
     * 在计算子集时候很有用，当这种情况发生时，减少计数器，因为 span 将不会被报告。
     *
     * <p><em>Note:</em>Abandoned spans should be ignored as they aren't indicative of an error.
     * Some instrumentation speculatively create a span for possible outcomes such as retry.
     */
    ABANDONED,
    /**
     * Called on {@link Span#finish()} and is the simplest cause to reason with. When {@link
     * MutableSpan#startTimestamp()} is present, you can assume with high confidence you have all
     * recorded data for this span.
     * 在 {@link Span#finish()} 上调用，是最简单的原因。
     * 当 {@link MutableSpan#startTimestamp()} 存在时，您可以有很高的信心，您已经记录了这个 span 的所有数据。
     */
    FINISHED,
    /**
     * Called on {@link Span#flush()}.
     *
     * <p>Even though the span here is incomplete (missing {@link MutableSpan#finishTimestamp()},
     * it is reported to the tracing system unless a {@link SpanHandler} returns false.
     * 即使这里的 span 是不完整的（缺少 {@link MutableSpan#finishTimestamp()}），它也会被报告给跟踪系统，除非 {@link SpanHandler} 返回 false。
     */
    FLUSHED,
    /**
     * Called when the trace context was garbage collected prior to completion.
     * 在完成之前，当跟踪上下文被垃圾回收时调用。
     *
     * <p>Normally, {@link #end(TraceContext, MutableSpan, Cause)} is only called upon explicit
     * termination of a span: {@link Span#finish()}, {@link Span#finish(long)} or {@link
     * Span#flush()}. Upon this cause, the callback will also receive data orphaned due to spans
     * being never terminated or data added after termination.
     * 通常，只有在显式终止 span 时才会调用 {@link #end(TraceContext, MutableSpan, Cause)}：{@link Span#finish()}、{@link Span#finish(long)} 或 {@link Span#flush()}。
     * 在这种情况下，回调还将接收到由于 span 从未终止或在终止后添加数据而产生的孤立数据。
     *
     * <p><em>Note</em>: If you are doing redaction, you should redact for all causes, not just
     * {@link #FINISHED}, as orphans may have sensitive data also.
     * 如果您正在执行消除操作，您应该对所有原因进行消除，而不仅仅是 {@link #FINISHED}，因为孤立数据可能也包含敏感数据。
     *
     * <h3>What is an orphaned span?</h3>
     *
     * <p>An orphan is  when data remains associated with a span when it is garbage collected. This
     * is almost always a bug. For example, calling {@link Span#tag(String, String)} after calling
     * {@link Span#finish()}, or calling {@link Tracer#nextSpan()} yet never using the result. To
     * track down bugs like this, set the logger {@link brave.Tracer} to FINE level.
     * 孤儿是指当 span 被垃圾回收时，数据仍与跨度相关联。这几乎总是一个 bug。
     * 例如，在调用 {@link Span#finish()} 后调用 {@link Span#tag(String, String)}，或者调用 {@link Tracer#nextSpan()}，但从未使用结果。
     * 要跟踪此类 bug，将日志记录器 {@link brave.Tracer} 设置为 FINE 级别。
     *
     * <h3>Why handle orphaned spans?</h3>
     *
     * <p>Use cases for handling orphans logging a different way than default, or incrementing bug
     * counters. For example, you could use the same credit card cleaner here as you do on the
     * success path.
     * 处理孤立的 span 的用例记录方式与默认方式不同，或者增加 bug 计数器。例如，您可以在此处使用与成功路径相同的card清理器。
     *
     * <h3>What shouldn't handle orphaned spans?</h3>
     *
     * <p>As this is related to bugs, no assumptions can be made about span count etc. For example,
     * one span context can result in many calls to this handler, unrelated to the actual operation
     * performed. Handlers that redact or clean data work for normal spans and orphans. However,
     * aggregation handlers, such as dependency linkers or success/fail counters, can create
     * problems if used against orphaned spans.
     * 由于这与 bug 相关，因此不能对 span 计数等进行任何假设。例如，一个 span 上下文可能会导致对此处理程序的多次调用，与实际执行的操作无关。
     * 消除或清理数据的处理程序适用于正常 span 和孤立 span。但是，聚合处理程序，例如依赖链接器或成功/失败计数器，如果用于孤立 span，则可能会产生问题。
     *
     * <h2>Implementation</h2>
     * <p>The {@link MutableSpan} parameter to {@link #end(TraceContext, MutableSpan, Cause)}
     * includes data configured by default and any state that was was orphaned (ex a tag). You
     * cannot assume the span has a {@link MutableSpan#startTimestamp()} for example.
     * {@link #end(TraceContext, MutableSpan, Cause)} 中的 {@link MutableSpan} 参数包括默认配置的数据和任何孤立的状态（例如标签）。例如，您不能假设 span 有 {@link MutableSpan#startTimestamp()}。
     */
    ORPHANED
  }

  /**
   * Use to avoid comparing against {@code null} references.
   *
   * @since 5.12
   */
  public static final SpanHandler NOOP = new SpanHandler() {
    @Override public String toString() {
      return "NoopSpanHandler{}";
    }
  };

  /**
   * This is called when a span is sampled, but before it is started.
   * 当 span 被采样时调用，但在开始之前调用。
   *
   * @param context the trace context which is  {@link TraceContext#sampledLocal()}. This includes
   * identifiers and potentially {@link TraceContext#extra() extra propagated data} such as baggage
   * or extended sampling configuration.
   * @param span a mutable object that stores data recorded with span apis. Modifications are
   * visible to later collectors.
   * @param parent can be {@code null} only when the new context is a {@linkplain
   * TraceContext#isLocalRoot() local root}.
   * @return {@code true} retains the span, and should almost always be used. {@code false} makes it
   * invisible to later handlers such as Zipkin.
   * @see Tracing.Builder#alwaysSampleLocal()
   * @see #end(TraceContext, MutableSpan, Cause)
   * @since 5.12
   */
  public boolean begin(TraceContext context, MutableSpan span, @Nullable TraceContext parent) {
    return true;
  }

  /**
   * Called when data collection complete.
   * 数据收集完成时调用。
   *
   * <h3>Advanced Note</h3>
   * By default, this only receives callbacks when data is intended to be recorded. If you are
   * implementing tracking between {@link #begin} and here, you should consider overriding {@link
   * #handlesAbandoned()} so that you have parity for all cases.
   * 默认情况下，只有在打算记录数据时才会收到回调。如果您正在实现 {@link #begin} 和此处之间的跟踪，您应该考虑覆盖 {@link #handlesAbandoned()}，以便在所有情况下都有相同的情况。
   *
   * @param context same instance as passed to {@link #begin}
   * @param span same instance as passed to {@link #begin}
   * @param cause why the data collection stopped.
   * @return {@code true} retains the span, and should almost always be used. {@code false} drops
   * the span, making it invisible to later handlers such as Zipkin.
   * @see #begin(TraceContext, MutableSpan, TraceContext)
   * @see Cause
   * @since 5.12
   */
  public boolean end(TraceContext context, MutableSpan span, Cause cause) {
    return true;
  }

  /**
   * {@link Span#abandon()} means the data is not intended to be recorded. It results in an
   * {@linkplain #end(TraceContext, MutableSpan, Cause) end callback} with {@link Cause#ABANDONED}.
   * {@link Span#abandon()} 意味着不打算记录数据。它导致 {@linkplain #end(TraceContext, MutableSpan, Cause) end 回调} 与 {@link Cause#ABANDONED}。
   *
   *
   * <p><em>Note</em>: {@link Cause#ABANDONED} means the data is not intended to be recorded!
   */
  public boolean handlesAbandoned() {
    return false;
  }
}
