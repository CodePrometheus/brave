/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave;

import brave.internal.Nullable;
import brave.propagation.TraceContext;

/**
 * Subtype of {@link SpanCustomizer} which can capture latency and remote context of an operation.
 * {@link SpanCustomizer} 的子类型可以捕获操作的延迟和远程上下文
 * 
 * Here's a typical example of synchronous tracing from perspective of the span:
 * 这是一个典型的同步跟踪示例，从 span 的角度来看：
 * <pre>{@code
 * // Note span methods chain. Explicitly start the span when ready. 注意 span 方法链。准备好后显式启动 span。
 * Span span = tracer.nextSpan().name("encode").start();
 * // A span is not responsible for making itself current (scoped); the tracer is 
 * // 一个 span 不负责使自己成为当前（作用域）；tracer 负责
 * try (SpanInScope scope = tracer.withSpanInScope(span)) {
 *   return encoder.encode();
 * } catch (RuntimeException | Error e) {
 *   span.error(e); // Unless you handle exceptions, you might not know the operation failed!
 *   throw e;
 * } finally {
 *   span.finish(); // finish - start = the duration of the operation in microseconds
 * }
 * }</pre>
 *
 * <p>This captures duration of {@link #start()} until {@link #finish()} is called.
 * 这捕获了 {@link #start()} 到 {@link #finish()} 被调用的持续时间。
 *
 * <h3>Usage notes</h3>
 * All methods return {@linkplain Span} for chaining, but the instance is always the same. Also,
 * when only tracing in-process operations, consider {@link ScopedSpan}: a simpler api.
 * 所有方法返回 {@linkplain Span} 以进行链式调用，但实例始终相同。
 * 此外，当仅跟踪进程内操作时，请考虑 {@link ScopedSpan}：一个更简单的 api。
 */
// Design note: this does not require a builder as the span is mutable anyway. Having a single
// mutation interface is less code to maintain. Those looking to prepare a span before starting it
// can simply call start when they are ready.
// BRAVE6: do not inherit SpanCustomizer, rather just return it. This will prevent accidentally
// leaking lifecycle methods
public abstract class Span implements SpanCustomizer {
  public enum Kind {
    CLIENT,
    SERVER,
    /**
     * When present, {@link #start()} is the moment a producer sent a message to a destination. A
     * duration between {@link #start()} and {@link #finish()} may imply batching delay. {@link
     * #remoteServiceName(String)} indicates the destination, such as a broker.
     * 当存在时，{@link #start()} 是生产者将消息发送到目的地的时刻。
     * 在 {@link #start()} 和 {@link #finish()} 之间的持续时间可能意味着批处理延迟。
     * {@link #remoteServiceName(String)} 表示目的地，例如broker。
     *
     * <p>Unlike {@link #CLIENT}, messaging spans never share a span ID. For example, the {@link
     * #CONSUMER} of the same message has {@link TraceContext#parentId()} set to this span's {@link
     * TraceContext#spanId()}.
     */
    PRODUCER,
    /**
     * When present, {@link #start()} is the moment a consumer received a message from an origin. A
     * duration between {@link #start()} and {@link #finish()} may imply a processing backlog. while
     * {@link #remoteServiceName(String)} indicates the origin, such as a broker.
     * 当存在时，{@link #start()} 是消费者从源接收消息的时刻。
     * 在 {@link #start()} 和 {@link #finish()} 之间的持续时间可能意味着处理积压。
     * 而 {@link #remoteServiceName(String)} 表示源，例如broker。
     *
     * <p>Unlike {@link #SERVER}, messaging spans never share a span ID. For example, the {@link
     * #PRODUCER} of this message is the {@link TraceContext#parentId()} of this span.
     */
    CONSUMER
  }

  /**
   * When true, no recording is done and nothing is reported to zipkin. However, this span should
   * still be injected into outgoing requests. Use this flag to avoid performing expensive
   * computation.
   * 当为 true 时，不进行记录，也不向 zipkin 报告任何内容。但是，此跨度仍应注入到传出请求中。
   * 使用此标志可避免执行昂贵的计算。
   */
  public abstract boolean isNoop();

  public abstract TraceContext context();

  /** Returns a customizer appropriate for the current span. Prefer this when invoking user code */
  public abstract SpanCustomizer customizer();

  /**
   * Starts the span with an implicit timestamp.
   * 使用隐式时间戳启动跨度。
   *
   * <p>Spans can be modified before calling start. For example, you can add tags to the span and
   * set its name without lock contention.
   * 在调用 start 之前，可以修改跨度。例如，您可以向跨度添加标记并设置其名称，而无需锁定争用。
   */
  public abstract Span start();

  /**
   * Like {@link #start()}, except with a given timestamp in microseconds.
   * 与 {@link #start()} 类似，但时间戳为微秒。
   *
   * <p>Take extreme care with this feature as it is easy to have incorrect timestamps. If you must
   * use this, generate the timestamp using {@link Tracing#clock(TraceContext)}.
   * 对此功能要非常小心，因为很容易出现不正确的时间戳。如果必须使用此功能，请使用 {@link Tracing#clock(TraceContext)} 生成时间戳。
   */
  public abstract Span start(long timestamp);

  /** {@inheritDoc} */
  @Override public abstract Span name(String name);

  /**
   * When present, the span is remote. This value clarifies how to interpret {@link
   * #remoteServiceName(String)} and {@link #remoteIpAndPort(String, int)}.
   * 当存在时，跨度是远程的。此值澄清了如何解释 {@link #remoteServiceName(String)} 和 {@link #remoteIpAndPort(String, int)}。
   *
   * <p>Note: This affects Zipkin v1 format even if that format does not have a "kind" field. For
   * example, if kind is {@link Kind#SERVER} and reported in v1 Zipkin format, the span's start
   * timestamp is implicitly annotated as "sr" and that plus its duration as "ss".
   * 注意：即使该格式没有 "kind" 字段，这也会影响 Zipkin v1 格式。例如，如果 kind 是 {@link Kind#SERVER} 并且以 v1 Zipkin 格式报告，
   * 则跨度的开始时间戳将被隐式注释为 "sr"，并且加上其持续时间为 "ss"。
   */
  public abstract Span kind(@Nullable Kind kind);

  /** {@inheritDoc} */
  @Override public abstract Span annotate(String value);

  /**
   * Like {@link #annotate(String)}, except with a given timestamp in microseconds.
   * 与 {@link #annotate(String)} 类似，但时间戳为微秒。
   *
   * <p>Take extreme care with this feature as it is easy to have incorrect timestamps. If you must
   * use this, generate the timestamp using {@link Tracing#clock(TraceContext)}.
   * 对此功能要非常小心，因为很容易出现不正确的时间戳。如果必须使用此功能，请使用 {@link Tracing#clock(TraceContext)} 生成时间戳。
   */
  public abstract Span annotate(long timestamp, String value);

  /** {@inheritDoc} */
  @Override public abstract Span tag(String key, String value);

  /**
   * Records an error that impacted this operation.
   *
   * <p><em>Note:</em> Calling this does not {@linkplain #finish() finish} the span.
   *
   * @since 4.19
   */
  // Design note: <T extends Throwable> T error(T throwable) is tempting but this doesn't work in
  // multi-catch. In practice, you should always at least catch RuntimeException and Error.
  public abstract Span error(Throwable throwable);

  /**
   * Lower-case label of the remote node in the service graph, such as "favstar". Do not set if
   * unknown. Avoid names with variables or unique identifiers embedded.
   *
   * <p>This is a primary label for trace lookup and aggregation, so it should be intuitive and
   * consistent. Many use a name from service discovery.
   *
   * @see #remoteIpAndPort(String, int)
   */
  public abstract Span remoteServiceName(String remoteServiceName);

  /**
   * Sets the IP and port associated with the remote endpoint. For example, the server's listen
   * socket or the connected client socket. This can also be set to forwarded values, such as an
   * advertised IP.
   * 设置与远程端点关联的 IP 和端口。例如，服务器的监听套接字或连接的客户端套接字。也可以设置为转发的值，例如广告 IP。
   *
   * <p>Invalid inputs, such as hostnames, will return false. Port is only set with a valid IP, and
   * zero or negative port values are ignored. For example, to set only the IP address, leave port
   * as zero.
   * 无效的输入，例如主机名，将返回 false。只有在有效的 IP 地址下才设置端口，零或负端口值将被忽略。例如，要仅设置 IP 地址，请将端口保留为零。
   *
   * <p>This returns boolean, not Span as it is often the case strings are malformed. Using this,
   * you can do conditional parsing like so:
   * 这不返回 Span，而是返回布尔值，因为通常字符串格式不正确。使用此功能，您可以执行条件解析，如下所示：
   * <pre>{@code
   * if (span.remoteIpAndPort(address.getHostAddress(), target.getPort())) return;
   * span.remoteIpAndPort(address.getHostName(), target.getPort());
   * }</pre>
   *
   * <p>Note: Comma separated lists are not supported. If you have multiple entries choose the one
   * most indicative of the remote side. For example, the left-most entry in X-Forwarded-For.
   * 注意：不支持逗号分隔的列表。如果有多个条目，请选择最具指示性的远程端。例如，X-Forwarded-For 中的最左边条目。
   *
   * @param remoteIp the IPv4 or IPv6 literal representing the remote service connection
   * @param remotePort the port associated with the IP, or zero if unknown.
   * @see #remoteServiceName(String)
   * @since 5.2
   */
  // NOTE: this is remote (IP, port) vs remote IP:port String as zipkin2.Endpoint separates the two,
  // and IP:port strings are uncommon at runtime (even if they are common at config).
  // Parsing IP:port pairs on each request, including concerns like IPv6 bracketing, would add
  // weight for little benefit. If this changes, we can overload it.
  public abstract boolean remoteIpAndPort(@Nullable String remoteIp, int remotePort);

  /** Reports the span complete, assigning the most precise duration possible. */
  public abstract void finish();

  /** Throws away the current span without reporting it. */
  public abstract void abandon();

  /**
   * Like {@link #finish()}, except with a given timestamp in microseconds.
   *
   * <p>{@link zipkin2.Span#duration() Zipkin's span duration} is derived by subtracting the start
   * timestamp from this, and set when appropriate.
   *
   * <p>Take extreme care with this feature as it is easy to have incorrect timestamps. If you must
   * use this, generate the timestamp using {@link Tracing#clock(TraceContext)}.
   */
  // Design note: This differs from Brave 3's LocalTracer which completes with a given duration.
  // This was changed for a few use cases.
  // * Finishing a one-way span on another host https://github.com/openzipkin/zipkin/issues/1243
  //   * The other host will not be able to read the start timestamp, so can't calculate duration
  // * Consistency in Api: All units and measures are epoch microseconds
  //   * This can reduce accidents where people use duration when they mean a timestamp
  // * Parity with OpenTracing
  //   * OpenTracing close spans like this, and this makes a Brave bridge stateless wrt timestamps
  // Design note: This does not implement Closeable (or AutoCloseable)
  // * the try-with-resources pattern is be reserved for attaching a span to a context.
  public abstract void finish(long timestamp);

  /**
   * Reports the span, even if unfinished. Most users will not call this method.
   * 报告跨度，即使未完成。大多数用户不会调用此方法。
   *
   * <p>This primarily supports two use cases: one-way spans and orphaned spans. For example, a
   * one-way span can be modeled as a span where one tracer calls start and another calls finish. In
   * order to report that span from its origin, flush must be called.
   * 这主要支持两种用例：单向跨度和孤立跨度。例如，可以将单向跨度建模为一个跨度，其中一个跟踪器调用 start，另一个调用 finish。
   * 为了从其源报告该跨度，必须调用 flush。
   *
   * <p>Another example is where a user didn't call finish within a deadline or before a shutdown
   * occurs. By flushing, you can report what was in progress.
   * 另一个例子是用户在截止日期之前或在关闭之前没有调用 finish。通过刷新，您可以报告正在进行的工作。
   */
  // Design note: This does not implement Flushable
  // * a span should not be routinely flushed, only when it has finished, or we don't believe this
  //   tracer will finish it.
  public abstract void flush();

  Span() { // intentionally hidden constructor
  }
}
