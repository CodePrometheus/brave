/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave;

import brave.baggage.BaggageField;
import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.internal.Nullable;
import brave.internal.Platform;
import brave.internal.codec.IpLiteral;
import brave.internal.handler.NoopAwareSpanHandler;
import brave.internal.handler.OrphanTracker;
import brave.internal.recorder.PendingSpans;
import brave.propagation.B3Propagation;
import brave.propagation.CurrentTraceContext;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import brave.sampler.Sampler;
import brave.sampler.SamplerFunction;
import java.io.Closeable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This provides utilities needed for trace instrumentation. For example, a {@link Tracer}.
 * 提供跟踪仪器所需的实用程序。例如，{@link Tracer}。
 *
 * <p>Instances built via {@link #newBuilder()} are registered automatically such that statically
 * configured instrumentation like JDBC drivers can use {@link #current()}.
 * 通过 {@link #newBuilder()} 构建的实例会自动注册，这样静态配置的仪器（如 JDBC 驱动程序）就可以使用 {@link #current()}。
 *
 * <p>This type can be extended so that the object graph can be built differently or overridden,
 * for example via spring or when mocking.
 * 这种类型可以被扩展，以便可以以不同的方式或被覆盖构建对象图，例如通过 spring 或在 mock。
 */
public abstract class Tracing implements Closeable {
  static final AtomicReference<Tracing> CURRENT = new AtomicReference<Tracing>();

  public static Builder newBuilder() {
    return new Builder();
  }

  /** All tracing commands start with a {@link Span}. Use a tracer to create spans. 
   * 所有跟踪命令都以 {@link Span} 开头。使用跟踪器创建跟踪。
   */
  abstract public Tracer tracer();

  /**
   * When a trace leaves the process, it needs to be propagated, usually via headers. This utility
   * is used to inject or extract a trace context from remote requests.
   * 当跟踪离开进程时，它需要通过传播，通常是通过headers。此实用程序用于从远程请求中注入或提取跟踪上下文。
   */
  public abstract Propagation<String> propagation();

  /**
   * Sampler is responsible for deciding if a particular trace should be "sampled", i.e. whether the
   * overhead of tracing will occur and/or if a trace will be reported to Zipkin.
   * Sampler 负责决定是否应该对特定跟踪进行“采样”，即是否会发生跟踪的开销和/或是否会将跟踪报告给 Zipkin。
   *
   * @see Tracer#nextSpan(SamplerFunction, Object) for temporary overrides
   */
  abstract public Sampler sampler();

  /**
   * This supports in-process propagation, typically across thread boundaries. This includes
   * utilities for concurrent types like {@linkplain java.util.concurrent.ExecutorService}.
   * 这支持进程内传播，通常跨线程边界。这包括并发类型的实用程序，如 {@linkplain java.util.concurrent.ExecutorService}。
   */
  abstract public CurrentTraceContext currentTraceContext();

  /**
   * This exposes the microsecond clock used by operations such as {@link Span#finish()}. This is
   * helpful when you want to time things manually. Notably, this clock will be coherent for all
   * child spans in this trace (that use this tracing component). For example, NTP or system clock
   * changes will not affect the result.
   * 这会暴露用于操作的微秒时钟，例如 {@link Span#finish()}。当您想手动计时时，这很有帮助。
   * 值得注意的是，此时钟将对此跟踪中的所有子跟踪（使用此跟踪组件）保持一致。例如，NTP 或系统时钟更改不会影响结果。
   *
   * @param context references a potentially unstarted span you'd like a clock correlated with
   */
  public final Clock clock(TraceContext context) {
    return tracer().pendingSpans.getOrCreate(null, context, false).clock();
  }

  /**
   * Returns the most recently created tracing component iff it hasn't been closed. null otherwise.
   * 返回最近创建的跟踪组件（如果尚未关闭）。否则为 null。
   *
   * <p>This object should not be cached.
   */
  @Nullable public static Tracing current() {
    return CURRENT.get();
  }

  /**
   * Returns the most recently created tracer if its component hasn't been closed. null otherwise.
   * 返回最近创建的 tracer（如果其组件尚未关闭）。否则为 null。
   *
   * <p>This object should not be cached.
   */
  @Nullable public static Tracer currentTracer() {
    Tracing tracing = current();
    return tracing != null ? tracing.tracer() : null;
  }

  /**
   * When true, no recording is done and nothing is reported to zipkin. However, trace context is
   * still injected into outgoing requests.
   * 当为 true 时，不会进行记录，也不会将任何内容报告给 zipkin。但是，跟踪上下文仍然会被注入到传出请求中。
   *
   * @see Span#isNoop()
   */
  public abstract boolean isNoop();

  /**
   * Set true to drop data and only return {@link Span#isNoop() noop spans} regardless of sampling
   * policy. This allows operators to stop tracing in risk scenarios.
   * 设置为 true 以丢弃数据，并且无论采样策略如何，只返回 {@link Span#isNoop() noop spans}。这允许运营商在风险场景中停止跟踪。
   *
   * @see #isNoop()
   */
  public abstract void setNoop(boolean noop);

  /** Ensures this component can be garbage collected, by making it not {@link #current()} */
  @Override abstract public void close();

  public static final class Builder {
    final MutableSpan defaultSpan = new MutableSpan();
    Clock clock;
    Sampler sampler = Sampler.ALWAYS_SAMPLE;
    CurrentTraceContext currentTraceContext = CurrentTraceContext.Default.inheritable();
    boolean traceId128Bit = false, supportsJoin = true;
    boolean alwaysSampleLocal = false, trackOrphans = false;
    Propagation.Factory propagationFactory = B3Propagation.FACTORY;
    Set<SpanHandler> spanHandlers = new LinkedHashSet<SpanHandler>(); // dupes not ok

    Builder() {
      defaultSpan.localServiceName("unknown");
    }

    /**
     * Returns an immutable copy of the current {@linkplain #addSpanHandler(SpanHandler) span
     * handlers}. This allows those who can't create the builder to reconfigure or re-order them.
     * 返回当前 {@linkplain #addSpanHandler(SpanHandler) span 处理程序} 的不可变副本。这允许那些无法创建构建器的人重新配置或重新排序它们。
     *
     * @see #clearSpanHandlers()
     * @since 5.12
     */
    public Set<SpanHandler> spanHandlers() {
      return Collections.unmodifiableSet(new LinkedHashSet<SpanHandler>(spanHandlers));
    }

    /**
     * Clears all {@linkplain SpanHandler span handlers}. This allows those who can't create the
     * builder to reconfigure or re-order them.
     * 清除所有 {@linkplain SpanHandler span 处理程序}。这允许那些无法创建构建器的人重新配置或重新排序它们。
     *
     * @see #spanHandlers()
     * @see TracingCustomizer
     * @since 5.12
     */
    public Builder clearSpanHandlers() {
      spanHandlers.clear();
      return this;
    }

    /**
     * Label of the remote node in the service graph, such as "favstar". Avoid names with variables
     * or unique identifiers embedded. Defaults to "unknown".
     * 服务图中远程节点的标签，例如 "favstar"。避免嵌入变量或唯一标识符的名称。默认为 "unknown"。
     *
     * <p>This is a primary label for trace lookup and aggregation, so it should be intuitive and
     * consistent. Many use a name from service discovery.
     * 这是跟踪查找和聚合的主要标签，因此它应该直观且一致。许多人使用服务发现中的名称。
     *
     * @see #localIp(String)
     */
    public Builder localServiceName(String localServiceName) {
      if (localServiceName == null || localServiceName.isEmpty()) {
        throw new IllegalArgumentException(localServiceName + " is not a valid serviceName");
      }
      this.defaultSpan.localServiceName(localServiceName);
      return this;
    }

    /**
     * The text representation of the primary IP address associated with this service. Ex.
     * 192.168.99.100 or 2001:db8::c001. Defaults to a link local IP.
     *
     * @see #localServiceName(String)
     * @see #localPort(int)
     * @since 5.2
     */
    public Builder localIp(String localIp) {
      String maybeIp = IpLiteral.ipOrNull(localIp);
      if (maybeIp == null) throw new IllegalArgumentException(localIp + " is not a valid IP");
      this.defaultSpan.localIp(maybeIp);
      return this;
    }

    /**
     * The primary listen port associated with this service. No default.
     *
     * @see #localIp(String)
     * @since 5.2
     */
    public Builder localPort(int localPort) {
      if (localPort > 0xffff) throw new IllegalArgumentException("invalid localPort " + localPort);
      if (localPort < 0) localPort = 0;
      this.defaultSpan.localPort(localPort);
      return this;
    }

    /**
     * Assigns microsecond-resolution timestamp source for operations like {@link Span#start()}.
     * Defaults to JRE-specific platform time.
     * 为 {@link Span#start()} 等操作分配微秒分辨率时间戳源。默认为 JRE 特定的平台时间。
     *
     * <p>Note: timestamps are read once per trace, then {@link System#nanoTime() ticks}
     * thereafter. This ensures there's no clock skew problems inside a single trace.
     * 注意：时间戳每个跟踪读取一次，然后 {@link System#nanoTime() ticks}。这可以确保单个跟踪内没有时钟偏移问题。
     * See {@link Tracing#clock(TraceContext)}
     */
    public Builder clock(Clock clock) {
      if (clock == null) throw new NullPointerException("clock == null");
      this.clock = clock;
      return this;
    }

    /**
     * Sampler is responsible for deciding if a particular trace should be "sampled", i.e. whether
     * the overhead of tracing will occur and/or if a trace will be reported to Zipkin.
     * Sampler 负责决定是否应该对特定跟踪进行“采样”，即是否会发生跟踪的开销和/或是否会将跟踪报告给 Zipkin
     *
     * @see Tracer#nextSpan(SamplerFunction, Object) for temporary overrides
     */
    public Builder sampler(Sampler sampler) {
      if (sampler == null) throw new NullPointerException("sampler == null");
      this.sampler = sampler;
      return this;
    }

    /**
     * Responsible for implementing {@link Tracer#startScopedSpan(String)}, {@link
     * Tracer#currentSpanCustomizer()}, {@link Tracer#currentSpan()} and {@link
     * Tracer#withSpanInScope(Span)}.
     * 负责实现 {@link Tracer#startScopedSpan(String)}、{@link Tracer#currentSpanCustomizer()}、{@link Tracer#currentSpan()} 和 {@link Tracer#withSpanInScope(Span)}。
     *
     * <p>By default a simple thread-local is used. Override to support other mechanisms or to
     * synchronize with other mechanisms such as SLF4J's MDC.
     * 默认情况下，使用简单的线程本地。覆盖以支持其他机制或与其他机制同步，例如 SLF4J 的 MDC。
     */
    public Builder currentTraceContext(CurrentTraceContext currentTraceContext) {
      if (currentTraceContext == null) {
        throw new NullPointerException("currentTraceContext == null");
      }
      this.currentTraceContext = currentTraceContext;
      return this;
    }

    /**
     * Controls how trace contexts are injected or extracted from remote requests, such as from http
     * headers. Defaults to {@link B3Propagation#FACTORY}
     * 控制如何从远程请求（例如从 http headers）中注入或提取跟踪上下文。默认为 {@link B3Propagation#FACTORY}
     */
    public Builder propagationFactory(Propagation.Factory propagationFactory) {
      if (propagationFactory == null) throw new NullPointerException("propagationFactory == null");
      this.propagationFactory = propagationFactory;
      return this;
    }

    /** When true, new root spans will have 128-bit trace IDs. Defaults to false (64-bit) */
    public Builder traceId128Bit(boolean traceId128Bit) {
      this.traceId128Bit = traceId128Bit;
      return this;
    }

    /**
     * True means the tracing system supports sharing a span ID between a {@link Span.Kind#CLIENT}
     * and {@link Span.Kind#SERVER} span. Defaults to true.
     * True 表示跟踪系统支持在 {@link Span.Kind#CLIENT} 和 {@link Span.Kind#SERVER} 跨度之间共享跨度 ID。默认为 true。
     *
     * <p>Set this to false when the tracing system requires the opposite. For example, if
     * ultimately spans are sent to Amazon X-Ray or Google Stackdriver Trace, you should set this to
     * false.
     * 当跟踪系统需要相反的情况时，请将其设置为 false。例如，如果最终跨度将发送到 Amazon X-Ray 或 Google Stackdriver Trace，则应将其设置为 false。
     *
     * <p>This is implicitly set to false when {@link Propagation.Factory#supportsJoin()} is false,
     * as in that case, sharing IDs isn't possible anyway.
     * 当 {@link Propagation.Factory#supportsJoin()} 为 false 时，它会被隐式设置为 false，因为在这种情况下，共享 ID 无论如何都是不可能的。
     *
     * @see Propagation.Factory#supportsJoin()
     */
    public Builder supportsJoin(boolean supportsJoin) {
      this.supportsJoin = supportsJoin;
      return this;
    }

    /**
     * Inputs receive {code (context, span)} pairs for every {@linkplain TraceContext#sampledLocal()
     * locally sampled} span. The span is mutable for customization or redaction purposes. Span
     * handlers execute in order: If any handler returns {code false}, the next will not see the
     * span.
     * 输入为每个 {@linkplain TraceContext#sampledLocal() 本地采样} 跨度接收 {code (context, span)} 对。
     * 跨度是可变的，用于定制或编辑目的。跨度处理程序按顺序执行：如果任何处理程序返回 {code false}，则下一个将不会看到跨度。
     *
     * @param spanHandler skipped if {@link SpanHandler#NOOP} or already added
     * @since 5.12
     */
    public Builder addSpanHandler(SpanHandler spanHandler) {
      if (spanHandler == null) throw new NullPointerException("spanHandler == null");

      // Some configuration can coerce to no-op, ignore in this case.
      if (spanHandler == SpanHandler.NOOP) return this;

      if (!spanHandlers.add(spanHandler)) {
        Platform.get().log("Please check configuration as %s was added twice", spanHandler, null);
      }
      return this;
    }

    /**
     * When true, all spans become real spans even if they aren't sampled remotely. This allows
     * span handlers (such as metrics) to consider attributes that are not always visible
     * before-the-fact, such as http paths. Defaults to false and affects {@link
     * TraceContext#sampledLocal()}.
     * 当为 true 时，即使远程未对所有跨度进行采样，所有跨度也会变为 real spans
     * 这允许 span 处理程序（例如指标）考虑在事先不总是可见的属性，例如 http 路径。默认为 false，并影响 {@link TraceContext#sampledLocal()}。
     *
     * <h3>Advanced example: Secondary Sampling</h3>
     * Besides metrics, another primary use case is to implement a <a href="https://github.com/openzipkin-contrib/zipkin-secondary-sampling">sampling
     * overlay</a>, such as boosting the sample rate for a subset of the network depending on the
     * value of a {@link BaggageField baggage field}. A handler like this will report when either
     * the trace is normally sampled, or secondarily sampled via a custom header. This assumes your
     * backend can properly process the partial traces implied when using conditional sampling. For
     * example, if your sampling condition is not consistent on a call tree, the resulting data
     * could appear broken.
     * 除了指标，另一个主要用例是实现 zipkin-secondary-sampling 采样覆盖
     * 例如，根据 {@link BaggageField baggage field} 的值提高网络子集的采样率。像这样的处理程序将在跟踪通常被采样或通过自定义标头进行二次采样时报告
     * 这假定您的后端可以正确处理使用条件采样时暗示的部分跟踪。例如，如果您的采样条件在调用树上不一致，则生成的数据可能会出现错误。
     *
     * @see #addSpanHandler(SpanHandler)
     * @see TraceContext#sampledLocal()
     * @since 5.12
     */
    public Builder alwaysSampleLocal() {
      this.alwaysSampleLocal = true;
      return this;
    }

    /**
     * When true, a {@link SpanHandler} is added that  logs the caller which orphaned a span to the
     * category "brave.Tracer" at {@link Level#FINE}. Defaults to false.
     * 当为 true 时，将添加一个 {@link SpanHandler}，该处理程序将调用者记录到类别 "brave.Tracer" 的 {@link Level#FINE}。默认 false
     *
     * <p>If you see data with the annotation "brave.flush", you may have an instrumentation bug.
     * To see which code was involved, set this and ensure the logger {@link Tracing} is at {@link
     * Level#FINE}. Do not do this in production as tracking orphaned data incurs higher overhead.
     * 如果看到带有注释 "brave.flush" 的数据，则可能存在插桩错误。要查看涉及的代码，请设置此选项，并确保记录器 {@link Tracing} 为 {@link Level#FINE}。
     * 请勿在生产环境中执行此操作，因为跟踪孤立数据会产生更高的开销。
     *
     * @since 5.9
     */
    public Builder trackOrphans() {
      this.trackOrphans = true;
      return this;
    }

    public Tracing build() {
      return new Default(this);
    }
  }

  static final class LogSpanHandler extends SpanHandler {
    final Logger logger = Logger.getLogger(Tracer.class.getName());

    @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
      if (!logger.isLoggable(Level.INFO)) return false;
      logger.info(span.toString());
      return true;
    }

    @Override public String toString() {
      return "LogSpanHandler{name=" + logger.getName() + "}";
    }
  }

  static final class Default extends Tracing {
    final Tracer tracer;
    final Propagation.Factory propagationFactory;
    final Propagation<String> stringPropagation;
    final CurrentTraceContext currentTraceContext;
    final Sampler sampler;
    final Clock clock;
    final AtomicBoolean noop;

    Default(Builder builder) {
      this.clock = builder.clock != null ? builder.clock : Platform.get().clock();
      this.propagationFactory = builder.propagationFactory;
      this.stringPropagation = builder.propagationFactory.get();
      this.currentTraceContext = builder.currentTraceContext;
      this.sampler = builder.sampler;
      this.noop = new AtomicBoolean();

      MutableSpan defaultSpan = new MutableSpan(builder.defaultSpan); // safe copy
      // Lazy add localEndpoint.ip if not yet set
      if (defaultSpan.localIp() == null) {
        defaultSpan.localIp(Platform.get().linkLocalIp());
      }

      Set<SpanHandler> spanHandlers = new LinkedHashSet<SpanHandler>(builder.spanHandlers);
      if (spanHandlers.isEmpty()) spanHandlers.add(new LogSpanHandler());
      if (builder.trackOrphans) {
        spanHandlers.add(OrphanTracker.newBuilder().defaultSpan(defaultSpan).clock(clock).build());
      }

      // Make sure any exceptions caused by span handlers don't crash callers
      SpanHandler spanHandler =
        NoopAwareSpanHandler.create(spanHandlers.toArray(new SpanHandler[0]), noop);

      this.tracer = new Tracer(
        builder.propagationFactory,
        spanHandler,
        new PendingSpans(defaultSpan, clock, spanHandler, noop),
        builder.sampler,
        builder.currentTraceContext,
        builder.traceId128Bit || propagationFactory.requires128BitTraceId(),
        builder.supportsJoin && propagationFactory.supportsJoin(),
        builder.alwaysSampleLocal,
        noop
      );
      // assign current IFF there's no instance already current
      CURRENT.compareAndSet(null, this);
    }

    @Override public Tracer tracer() {
      return tracer;
    }

    @Override public Propagation<String> propagation() {
      return stringPropagation;
    }

    @Override public Sampler sampler() {
      return sampler;
    }

    @Override public CurrentTraceContext currentTraceContext() {
      return currentTraceContext;
    }

    @Override public boolean isNoop() {
      return noop.get();
    }

    @Override public void setNoop(boolean noop) {
      this.noop.set(noop);
    }

    @Override public String toString() {
      return tracer.toString();
    }

    @Override public void close() {
      // only set null if we are the outermost instance
      CURRENT.compareAndSet(this, null);
    }
  }

  Tracing() { // intentionally hidden constructor
  }
}
