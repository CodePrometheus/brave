/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.propagation;

import brave.Tracer;
import brave.baggage.BaggagePropagation;
import brave.internal.InternalPropagation;
import brave.internal.Nullable;
import brave.propagation.TraceContext.Extractor;
import brave.sampler.SamplerFunction;
import java.util.List;

import static brave.internal.InternalPropagation.FLAG_SAMPLED_LOCAL;
import static brave.internal.collect.Lists.ensureImmutable;
import static brave.propagation.TraceContext.ensureExtraAdded;
import static java.util.Collections.emptyList;

/**
 * Union type that contains only one of trace context, trace ID context or sampling flags. This type
 * is designed for use with {@link Tracer#nextSpan(TraceContextOrSamplingFlags)}.
 * 联合类型，只包含跟踪上下文、跟踪 ID 上下文或采样标志中的一个。
 * 此类型设计用于与 {@link Tracer#nextSpan(TraceContextOrSamplingFlags)} 一起使用。
 * TraceContext: 对应完整的上下文信息, type=1
 * TraceIdContext: 仅包含TraceID, type=2
 * SamplingFlags: 仅包含flags, type=3
 *
 * <p>Users should not create instances of this, rather use {@link Extractor} provided
 * by a {@link Propagation} implementation such as {@link Propagation#B3_STRING}.
 * 用户不应创建此类的实例，而应使用 {@link Propagation} 实现提供的 {@link Extractor}，例如 {@link Propagation#B3_STRING}。
 *
 * <p>Those implementing {@link Propagation} should use the following advice:
 * <pre><ul>
 *   <li>If you have the trace and span ID, use {@link #create(TraceContext)}</li>
 *   <li>If you have only a trace ID, use {@link #create(TraceIdContext)}</li>
 *   <li>Otherwise, use {@link #create(SamplingFlags)}</li>
 * </ul></pre>
 * <p>If your propagation implementation needs additional state, append it via {@link
 * Builder#addExtra(Object)}.
 *
 *
 * <p>This started as a port of {@code com.github.kristofa.brave.TraceData}, which served the same
 * purpose.
 *
 * @see Extractor
 * @since 4.0
 */
//@Immutable
public final class TraceContextOrSamplingFlags {
  public static final TraceContextOrSamplingFlags
    EMPTY = new TraceContextOrSamplingFlags(3, SamplingFlags.EMPTY, emptyList()),
    NOT_SAMPLED = new TraceContextOrSamplingFlags(3, SamplingFlags.NOT_SAMPLED, emptyList()),
    SAMPLED = new TraceContextOrSamplingFlags(3, SamplingFlags.SAMPLED, emptyList()),
    DEBUG = new TraceContextOrSamplingFlags(3, SamplingFlags.DEBUG, emptyList());

  /**
   * Used to implement {@link Extractor#extract(Object)} for a format that can extract a complete
   * {@link TraceContext}, including a {@linkplain TraceContext#traceIdString() trace ID} and
   * {@linkplain TraceContext#spanIdString() span ID}.
   * 用于实现 {@link Extractor#extract(Object)}，用于可以提取完整的 {@link TraceContext} 的格式，
   * 包括 {@linkplain TraceContext#traceIdString() trace ID} 和 {@linkplain TraceContext#spanIdString() span ID}。
   *
   * @see #context()
   * @see #newBuilder(TraceContext)
   * @see Extractor#extract(Object)
   * @since 4.3
   */
  public static TraceContextOrSamplingFlags create(TraceContext context) {
    return new TraceContextOrSamplingFlags(1, context, emptyList());
  }

  /**
   * Used to implement {@link Extractor#extract(Object)} when the format allows extracting a
   * {@linkplain TraceContext#traceIdString() trace ID} without a {@linkplain
   * TraceContext#spanIdString() span ID}
   * 用于实现 {@link Extractor#extract(Object)}
   * 当格式允许提取 {@linkplain TraceContext#traceIdString() trace ID} 而不包含 {@linkplain TraceContext#spanIdString() span ID} 时
   *
   * @see #traceIdContext()
   * @see #newBuilder(TraceIdContext)
   * @see Extractor#extract(Object)
   * @since 4.9
   */
  public static TraceContextOrSamplingFlags create(TraceIdContext traceIdContext) {
    return new TraceContextOrSamplingFlags(2, traceIdContext, emptyList());
  }

  /**
   * Used to implement {@link Extractor#extract(Object)} when the format allows extracting only
   * {@linkplain SamplingFlags sampling flags}.
   * 用于实现 {@link Extractor#extract(Object)}，当格式允许提取只存在 {@linkplain SamplingFlags sampling flags} 时。
   *
   * @see #samplingFlags()
   * @see #newBuilder(TraceIdContext)
   * @see Extractor#extract(Object)
   * @since 4.3
   */
  public static TraceContextOrSamplingFlags create(SamplingFlags flags) {
    // reuses constants to avoid excess allocation
    if (flags == SamplingFlags.SAMPLED) return SAMPLED;
    if (flags == SamplingFlags.EMPTY) return EMPTY;
    if (flags == SamplingFlags.NOT_SAMPLED) return NOT_SAMPLED;
    if (flags == SamplingFlags.DEBUG) return DEBUG;
    return new TraceContextOrSamplingFlags(3, flags, emptyList());
  }

  /**
   * Use when implementing {@link Extractor#extract(Object)} requires {@link Builder#sampledLocal()}
   * or {@link Builder#addExtra(Object)}. Otherwise, use {@link #create(TraceContext)} as it is more
   * efficient.
   *
   * @see #create(TraceContext)
   * @see Extractor#extract(Object)
   * @since 5.12
   */
  public static Builder newBuilder(TraceContext context) {
    if (context == null) throw new NullPointerException("context == null");
    return new Builder(1, context, context.extra());
  }

  /**
   * Use when implementing {@link Extractor#extract(Object)} requires {@link Builder#sampledLocal()}
   * or {@link Builder#addExtra(Object)}. Otherwise, use {@link #create(TraceIdContext)} as it is
   * more efficient.
   *
   * @see #create(TraceIdContext)
   * @see Extractor#extract(Object)
   * @since 5.12
   */
  public static Builder newBuilder(TraceIdContext traceIdContext) {
    if (traceIdContext == null) throw new NullPointerException("traceIdContext == null");
    return new Builder(2, traceIdContext, emptyList());
  }

  /**
   * Use when implementing {@link Extractor#extract(Object)} requires {@link Builder#sampledLocal()}
   * or {@link Builder#addExtra(Object)}. Otherwise, use {@link #create(SamplingFlags)} as it is
   * more efficient.
   *
   * @see #create(SamplingFlags)
   * @see Extractor#extract(Object)
   * @since 5.12
   */
  public static Builder newBuilder(SamplingFlags flags) {
    if (flags == null) throw new NullPointerException("flags == null");
    return new Builder(3, flags, emptyList());
  }

  /** Returns {@link SamplingFlags#sampled()}, regardless of subtype. */
  @Nullable public Boolean sampled() {
    return value.sampled();
  }

  /** Returns {@link SamplingFlags#sampledLocal()}, regardless of subtype. */
  public boolean sampledLocal() {
    return (value.flags & FLAG_SAMPLED_LOCAL) == FLAG_SAMPLED_LOCAL;
  }

  /**
   * This is used to apply a {@link SamplerFunction} decision with least overhead.
   *
   * Ex.
   * <pre>{@code
   * Boolean sampled = extracted.sampled();
   * // only recreate the context if the messaging sampler made a decision
   * if (sampled == null && (sampled = sampler.trySample(request)) != null) {
   *   extracted = extracted.sampled(sampled.booleanValue());
   * }
   * }</pre>
   *
   * @param sampled decision to apply
   * @return {@code this} unless {@link #sampled()} differs from the input.
   * @since 5.2
   */
  public TraceContextOrSamplingFlags sampled(boolean sampled) {
    Boolean thisSampled = sampled();
    if (thisSampled != null && thisSampled.equals(sampled)) return this;
    int flags = InternalPropagation.sampled(sampled, value.flags);
    if (flags == value.flags) return this; // save effort if no change
    return withFlags(flags);
  }

  /**
   * Returns non-{@code null} when both a {@linkplain TraceContext#traceIdString() trace ID} and
   * {@linkplain TraceContext#spanIdString() span ID} were  {@link Extractor#extract(Object)
   * extracted} from a request.
   * 当从请求中提取了 {@linkplain TraceContext#traceIdString() trace ID} 和 {@linkplain TraceContext#spanIdString() span ID} 时，返回非 null。
   *
   * <p>For example, given the header "b3: 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-1",
   * {@link B3Propagation} extracts the following:
   * <ul>
   *   <li>{@link TraceContext#traceIdString()}: "80f198ee56343ba864fe8b2a57d3eff7"</li>
   *   <li>{@link TraceContext#spanIdString()}: "e457b5a2e4d86bd1"</li>
   *   <li>{@link TraceContext#sampled()}: {@code true}</li>
   * </ul>
   *
   * @return the trace context when {@link #traceIdContext()} ()} and {@link #samplingFlags()} are
   * not {@code null}
   * @see #create(TraceContext)
   * @see #newBuilder(TraceContext)
   * @since 4.0
   */
  @Nullable public TraceContext context() {
    // 判断是不是完整的 TraceContext 也就是包含 TraceId 和 SpanId
    // 否则返回 null
    return type == 1 ? (TraceContext) value : null;
  }

  /**
   * Returns non-{@code null} when a {@linkplain TraceIdContext#traceIdString() trace ID} was {@link
   * Extractor#extract(Object) extracted} from a request, but a {@linkplain
   * TraceContext#spanIdString() span ID} was not.
   * 当从请求中提取了 {@linkplain TraceIdContext#traceIdString() trace ID}
   * \但没有提取到 {@linkplain TraceContext#spanIdString() span ID} 时，返回非 null。
   *
   * <p>For example, given the header "x-amzn-trace-id: Root=1-5759e988-bd862e3fe1be46a994272793",
   * <a href="https://github.com/openzipkin/zipkin-aws/tree/master/brave-propagation-aws">AWSPropagation</a>
   * extracts the following:
   * <ul>
   *   <li>{@link TraceIdContext#traceIdString()}: "15759e988bd862e3fe1be46a994272793"</li>
   *   <li>{@link TraceIdContext#sampled()}: {@code null}</li>
   * </ul>
   *
   * @return the trace ID context when {@link #context()} and {@link #samplingFlags()} are not
   * {@code null}
   * @see #create(TraceIdContext)
   * @see #newBuilder(TraceIdContext)
   * @since 4.9
   */
  @Nullable public TraceIdContext traceIdContext() {
    return type == 2 ? (TraceIdContext) value : null;
  }

  /**
   * Returns non-{@code null} when a {@linkplain TraceContext#traceIdString() trace ID} was not
   * {@link Extractor#extract(Object) extracted} from a request.
   * 当从请求中没有提取到 {@linkplain TraceContext#traceIdString() trace ID} 时，返回非 null。
   *
   * <p>For example, given the header "b3: 1", {@link B3Propagation} extracts {@link #SAMPLED}.
   *
   * @return sampling flags when {@link #context()} and {@link #traceIdContext()} are not {@code
   * null}
   * @see #create(SamplingFlags)
   * @see #newBuilder(SamplingFlags)
   * @since 4.9
   */
  @Nullable public SamplingFlags samplingFlags() {
    return type == 3 ? value : null;
  }

  /**
   * Returns a list of additional state extracted from the request. Will be non-empty when {@link
   * #context()} is {@code null}.
   *
   * @see TraceContext#extra()
   * @see Builder#addExtra(Object) for notes on extra values.
   * @since 4.9
   */
  public List<Object> extra() {
    return extraList;
  }

  /**
   * Use to decorate an {@link Extractor#extract extraction result} with {@link #sampledLocal()} or
   * additional {@link #extra()}.
   *
   * @since 4.9
   */
  public Builder toBuilder() {
    return new Builder(type, value, effectiveExtra());
  }

  @Override public String toString() {
    List<Object> extra = effectiveExtra();
    StringBuilder result = new StringBuilder("Extracted{");
    String valueClass = value.getClass().getSimpleName();
    // Lowercase first char of class name
    result.append(Character.toLowerCase(valueClass.charAt(0)));
    result.append(valueClass, 1, valueClass.length()).append('=').append(value);
    if (type != 3) {
      String flagsString = SamplingFlags.toString(value.flags);
      if (!flagsString.isEmpty()) result.append(", samplingFlags=").append(flagsString);
    }
    if (!extra.isEmpty()) result.append(", extra=").append(extra);
    // NOTE: it would be nice to rename this type, but it would cause a major Api break:
    // This is the result of Extractor::extract which is used in a lot of 3rd party code.
    return result.append('}').toString();
  }

  final int type;
  final SamplingFlags value;
  final List<Object> extraList;

  TraceContextOrSamplingFlags(int type, SamplingFlags value, List<Object> extraList) {
    if (value == null) throw new NullPointerException("value == null");
    if (extraList == null) throw new NullPointerException("extra == null");
    this.type = type;
    this.value = value;
    this.extraList = extraList;
  }

  public static final class Builder {
    int type;
    SamplingFlags value;
    List<Object> extraList;
    boolean sampledLocal = false;

    Builder(int type, SamplingFlags value, List<Object> extraList) {
      this.type = type;
      this.value = value;
      this.extraList = extraList;
    }

    /** @see TraceContext#sampledLocal() */
    public Builder sampledLocal() {
      this.sampledLocal = true;
      return this;
    }

    /**
     * This is an advanced function used for {@link Propagation} plugins, such as
     * {@link BaggagePropagation}, to add an internal object to hold state before extracting a
     * remote request.
     * 这是一个高级函数，用于 {@link Propagation} 插件，例如 {@link BaggagePropagation}，
     * 用于在提取远程请求之前添加一个内部对象来保存状态。
     *
     * <p>Implications of data are the same as {@link TraceContext.Builder#addExtra(Object)}. The
     * main difference here is that {@link Extractor#extract(Object)} may not result in a trace
     * context. For example, baggage fields can exist without an incoming trace.
     * 数据的影响与 {@link TraceContext.Builder#addExtra(Object)} 相同。
     * 这里的主要区别在于 {@link Extractor#extract(Object)} 可能不会导致跟踪上下文。
     * 例如，行李物品字段可以存在而没有传入的跟踪。
     *
     * @see TraceContextOrSamplingFlags#extra()
     * @see TraceContext.Builder#addExtra(Object)
     * @since 4.9
     */
    public Builder addExtra(Object extra) {
      extraList = ensureExtraAdded(extraList, extra);
      return this;
    }

    /** Returns an immutable result from the values currently in the builder */
    public TraceContextOrSamplingFlags build() {
      final TraceContextOrSamplingFlags result;
      if (!extraList.isEmpty() && type == 1) { // move extra to the trace context
        TraceContext context = (TraceContext) value;
        context = InternalPropagation.instance.withExtra(context, ensureImmutable(extraList));
        result = new TraceContextOrSamplingFlags(type, context, emptyList());
      } else {
        // make sure the extra state is immutable and unmodifiable
        result = new TraceContextOrSamplingFlags(type, value, ensureImmutable(extraList));
      }

      if (!sampledLocal) return result; // save effort if no change
      return result.withFlags(value.flags | FLAG_SAMPLED_LOCAL);
    }
  }

  @Override public boolean equals(Object o) {
    if (o == this) return true;
    if (!(o instanceof TraceContextOrSamplingFlags)) return false;
    TraceContextOrSamplingFlags that = (TraceContextOrSamplingFlags) o;
    return type == that.type && value.equals(that.value)
        && effectiveExtra().equals(that.effectiveExtra());
  }

  List<Object> effectiveExtra() {
    return type == 1 ? ((TraceContext) value).extra() : extraList;
  }

  @Override public int hashCode() {
    int h = 1;
    h *= 1000003;
    h ^= type;
    h *= 1000003;
    h ^= value.hashCode();
    h *= 1000003;
    h ^= effectiveExtra().hashCode();
    return h;
  }

  TraceContextOrSamplingFlags withFlags(int flags) {
    switch (type) {
      case 1:
        TraceContext context = InternalPropagation.instance.withFlags((TraceContext) value, flags);
        return new TraceContextOrSamplingFlags(type, context, extraList);
      case 2:
        TraceIdContext traceIdContext = idContextWithFlags(flags);
        return new TraceContextOrSamplingFlags(type, traceIdContext, extraList);
      case 3:
        SamplingFlags samplingFlags = SamplingFlags.toSamplingFlags(flags);
        if (extraList.isEmpty()) return create(samplingFlags);
        return new TraceContextOrSamplingFlags(type, samplingFlags, extraList);
    }
    throw new AssertionError("programming error");
  }

  TraceIdContext idContextWithFlags(int flags) {
    TraceIdContext traceIdContext = (TraceIdContext) value;
    return new TraceIdContext(flags, traceIdContext.traceIdHigh, traceIdContext.traceId);
  }
}
