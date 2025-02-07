/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.baggage;

import brave.Tracing;
import brave.internal.Nullable;
import brave.internal.baggage.BaggageContext;
import brave.internal.baggage.ExtraBaggageContext;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * Defines a trace context scoped field, usually but not always analogous to an HTTP header. Fields
 * will be no-op unless {@link BaggagePropagation} is configured.
 * 定义了一个跟踪上下文作用域字段，通常但不总是类似于 HTTP 标头。字段将是无操作的，除非配置了 {@link BaggagePropagation}。
 *
 * <p>For example, if you have a need to know a specific request's country code in a downstream
 * service, you can propagate it through the trace:
 * 例如，如果您需要在下游服务中知道特定请求的国家代码，您可以通过跟踪传播它：
 * <pre>{@code
 * // Configure your baggage field
 * COUNTRY_CODE = BaggageField.create("country-code");
 * }</pre>
 *
 * <h3>Usage</h3>
 * As long as a field is configured with {@link BaggagePropagation}, local reads and updates are
 * possible in-process.
 * 只要使用 {@link BaggagePropagation} 配置了字段，就可以在进程中进行本地读取和更新。
 *
 * <p>Ex. once added to `BaggagePropagation`, you can call below to affect the country code
 * of the current trace context:
 * 例如。一旦添加到 `BaggagePropagation`，您可以调用下面的方法来影响当前跟踪上下文的国家代码：
 * <pre>{@code
 * COUNTRY_CODE.updateValue("FO");
 * String countryCode = COUNTRY_CODE.get();
 * }</pre>
 *
 * <p>Or, if you have a reference to a trace context, it is more efficient to use it explicitly:
 * 或者，如果您有一个跟踪上下文的引用，最好显式使用它：
 * <pre>{@code
 * COUNTRY_CODE.updateValue(span.context(), "FO");
 * String countryCode = COUNTRY_CODE.get(span.context());
 * Tags.BAGGAGE_FIELD.tag(COUNTRY_CODE, span);
 * }</pre>
 *
 * <p>Correlation</p>
 *
 * <p>You can also integrate baggage with other correlated contexts such as logging:
 * 您还可以将 baggage 与其他相关上下文集成，例如日志记录：
 * <pre>{@code
 * import brave.baggage.BaggagePropagationConfig.SingleBaggageField;
 * import brave.baggage.CorrelationScopeConfig.SingleCorrelationField;
 *
 * AMZN_TRACE_ID = BaggageField.create("x-amzn-trace-id");
 *
 * // Allow logging patterns like %X{traceId} %X{x-amzn-trace-id}
 * decorator = MDCScopeDecorator.newBuilder()
 *                              .add(SingleCorrelationField.create(AMZN_TRACE_ID)).build()
 *
 * tracingBuilder.propagationFactory(BaggagePropagation.newFactoryBuilder(B3Propagation.FACTORY)
 *                                                     .add(SingleBaggageField.remote(AMZN_TRACE_ID))
 *                                                     .build())
 *               .currentTraceContext(ThreadLocalCurrentTraceContext.newBuilder()
 *                                                                  .addScopeDecorator(decorator)
 *                                                                  .build())
 * }</pre>
 *
 * <h3>Appropriate usage</h3>
 * It is generally not a good idea to use the tracing system for application logic or critical code
 * such as security context propagation.
 * 通常不建议将跟踪系统用于应用程序逻辑或关键代码，例如安全上下文传播。
 *
 * <p>Brave is an infrastructure library: you will create lock-in if you expose its apis into
 * business code. Prefer exposing your own types for utility functions that use this class as this
 * will insulate you from lock-in.
 * Brave 是一个基础设施库：如果将其 API 暴露到业务代码中，将会导致锁定。最好为使用此类的实用程序函数公开自己的类型，因为这将使您免受锁定。
 *
 * <p>While it may seem convenient, do not use this for security context propagation as it was not
 * designed for this use case. For example, anything placed in here can be accessed by any code in
 * the same classloader!
 * 尽管这可能看起来很方便，但不要将其用于安全上下文传播，因为它不是为此用例而设计的。例如，放在这里的任何内容都可以被同一类加载器中的任何代码访问！
 *
 * <h3>Background</h3>
 * The name Baggage was first introduced by Brown University in <a href="https://people.mpi-sws.org/~jcmace/papers/mace2015pivot.pdf">Pivot
 * Tracing</a> as maps, sets and tuples. They then spun baggage out as a standalone component, <a
 * href="https://people.mpi-sws.org/~jcmace/papers/mace2018universal.pdf">BaggageContext</a> and
 * considered some of the nuances of making it general purpose. The implementations proposed in
 * these papers are different to the implementation here, but conceptually the goal is the same: to
 * propagate "arbitrary stuff" with a request.
 * 首次引入 Baggage 名称是由布朗大学在 <a href="https://people.mpi-sws.org/~jcmace/papers/mace2015pivot.pdf">Pivot Tracing 当时它被用作映射、集合和元组
 * 然后，他们将 baggage 拆分为一个独立的组件 BaggageContext 并考虑了使其成为通用组件的一些细微差别
 * 这些论文中提出的实现与此处的实现不同，但在概念上目标是相同的：使用请求传播“任意内容”
 *
 * @see BaggagePropagation
 * @see CorrelationScopeConfig
 * @since 5.11
 */
public final class BaggageField {
  /**
   * Used to decouple baggage value updates from {@link TraceContext} or {@link
   * TraceContextOrSamplingFlags} storage.
   * 用于将 baggage 值更新与 {@link TraceContext} 或 {@link TraceContextOrSamplingFlags} 存储解耦。
   *
   * <p><em>Note</em>: This type is safe to implement as a lambda, or use as a method reference as
   * it is effectively a {@code FunctionalInterface}. It isn't annotated as such because the project
   * has a minimum Java language level 6.
   *
   * @since 5.12
   */
  // @FunctionalInterface, except Java language level 6. Do not add methods as it will break API!
  public interface ValueUpdater {
    /** @since 5.12 */
    ValueUpdater NOOP = new ValueUpdater() {
      @Override public boolean updateValue(BaggageField field, String value) {
        return false;
      }

      @Override public String toString() {
        return "NoopValueUpdater{}";
      }
    };

    /**
     * Updates the value of the field, or ignores if read-only or not configured.
     * 更新字段的值，如果是只读的或未配置，则忽略。
     *
     * @param value {@code null} is an attempt to remove the value
     * @return {@code true} if the underlying state changed
     * @see #updateValue(TraceContext, String)
     * @see #updateValue(TraceContextOrSamplingFlags, String)
     * @since 5.12
     */
    boolean updateValue(BaggageField field, @Nullable String value);
  }

  /**
   * @param name See {@link #name()}
   * @since 5.11
   */
  public static BaggageField create(String name) {
    return new BaggageField(name, ExtraBaggageContext.get());
  }

  /**
   * Returns a map of all {@linkplain BaggageField#name() name} to {@linkplain
   * BaggageField#getValue(TraceContext) non-{@code null} value} pairs in the {@linkplain
   * TraceContext.Extractor#extract(Object) extracted result}.
   * 返回 {@linkplain TraceContext.Extractor#extract(Object) 提取结果} 中所有 {@linkplain BaggageField#name() 名称} 到 {@linkplain BaggageField#getValue(TraceContext) 非 {@code null} 值} 对的映射。
   *
   * @see #getAllValues(TraceContextOrSamplingFlags)
   * @since 5.12
   */
  public static Map<String, String> getAllValues(@Nullable TraceContext context) {
    if (context == null) return Collections.emptyMap();
    return ExtraBaggageContext.getAllValues(context);
  }

  /**
   * Returns a map of all {@linkplain BaggageField#name() name} to {@linkplain
   * BaggageField#getValue(TraceContextOrSamplingFlags) non-{@code null} value} pairs in the
   * {@linkplain TraceContext.Extractor#extract(Object) extracted result}.
   * 返回 {@linkplain TraceContext.Extractor#extract(Object) 提取结果} 中所有 {@linkplain BaggageField#name() 名称} 到 {@linkplain BaggageField#getValue(TraceContextOrSamplingFlags) 非 {@code null} 值} 对的映射。
   *
   * @see #getAllValues(TraceContext)
   * @since 5.12
   */
  public static Map<String, String> getAllValues(TraceContextOrSamplingFlags extracted) {
    if (extracted == null) throw new NullPointerException("extracted == null");
    return ExtraBaggageContext.getAllValues(extracted);
  }

  /**
   * Like {@link #getAllValues(TraceContext)} except against the current trace context.
   * 像 {@link #getAllValues(TraceContext)} 一样，除了针对当前跟踪上下文
   *
   * <p>Prefer {@link #getAllValues(TraceContext)} if you have a reference to the trace context.
   *
   * @since 5.12
   */
  @Nullable public static Map<String, String> getAllValues() {
    return getAllValues(currentTraceContext());
  }

  /**
   * Looks up the field by {@code name}, useful for when you do not have a reference to it. In
   * general, {@link BaggageField}s should be referenced directly as constants where possible.
   * 通过 {@code name} 查找字段，当您没有对其的引用时很有用。通常，应尽可能直接引用 {@link BaggageField} 作为常量。
   *
   * @since 5.11
   */
  @Nullable public static BaggageField getByName(@Nullable TraceContext context, String name) {
    if (context == null) return null;
    return ExtraBaggageContext.getFieldByName(context, validateName(name));
  }

  /**
   * Looks up the field by {@code name}, useful for when you do not have a reference to it. In
   * general, {@link BaggageField}s should be referenced directly as constants where possible.
   * 通过 {@code name} 查找字段，当您没有对其的引用时很有用。通常，应尽可能直接引用 {@link BaggageField} 作为常量。
   *
   * @since 5.11
   */
  @Nullable public static BaggageField getByName(TraceContextOrSamplingFlags extracted,
      String name) {
    if (extracted == null) throw new NullPointerException("extracted == null");
    return ExtraBaggageContext.getFieldByName(extracted, validateName(name));
  }

  /**
   * Like {@link #getByName(TraceContext, String)} except against the current trace context.
   * 像 {@link #getByName(TraceContext, String)} 一样，除了针对当前跟踪上下文。
   *
   * <p>Prefer {@link #getByName(TraceContext, String)} if you have a reference to the trace
   * context.
   *
   * @since 5.11
   */
  @Nullable public static BaggageField getByName(String name) {
    return getByName(currentTraceContext(), name);
  }

  final String name, lcName;
  final BaggageContext context;

  BaggageField(String name, BaggageContext context) { // sealed to this package
    this.name = validateName(name);
    this.lcName = name.toLowerCase(Locale.ROOT);
    this.context = context;
  }

  /**
   * The non-empty name of the field. Ex "userId".
   * 字段的非空名称。例如 "userId"。
   *
   * <p>For example, if using log correlation and with field named "userId", the {@linkplain
   * #getValue(TraceContext) value} becomes the log variable {@code %{userId}} when the span is next
   * made current.
   * 例如，如果使用日志相关性，并且字段名为 "userId"，则在下次使 span 成为当前 span 时，{@linkplain #getValue(TraceContext) 值} 将成为日志变量 {@code %{userId}}。
   *
   * @see #getByName(TraceContext, String)
   * @see CorrelationScopeConfig.SingleCorrelationField#name()
   * @since 5.11
   */
  public String name() {
    return name;
  }

  /**
   * Returns the most recent value for this field in the context or null if unavailable.
   * 返回上下文中此字段的最新值，如果不可用则返回 {@code null}。
   *
   * <p>The result may not be the same as the one {@link TraceContext.Extractor#extract(Object)
   * extracted} from the incoming context because {@link #updateValue(String)} can override it.
   * 结果可能与从传入上下文 {@link TraceContext.Extractor#extract(Object) 提取} 的结果不同，因为 {@link #updateValue(String)} 可以覆盖它。
   *
   * @since 5.11
   */
  @Nullable public String getValue(@Nullable TraceContext context) {
    if (context == null) return null;
    return this.context.getValue(this, context);
  }

  /**
   * Like {@link #getValue(TraceContext)} except against the current trace context.
   * 像 {@link #getValue(TraceContext)} 一样，除了针对当前跟踪上下文。
   *
   * <p>Prefer {@link #getValue(TraceContext)} if you have a reference to the trace context.
   * 如果您有对跟踪上下文的引用，请使用 {@link #getValue(TraceContext)}。
   *
   * @since 5.11
   */
  @Nullable public String getValue() {
    return getValue(currentTraceContext());
  }

  /**
   * Like {@link #getValue(TraceContext)} except for use cases that precede a span. For example, a
   * {@linkplain TraceContextOrSamplingFlags#traceIdContext() trace ID context}.
   * 像 {@link #getValue(TraceContext)} 一样，除了用于先于 span 的用例
   * 例如，{@linkplain TraceContextOrSamplingFlags#traceIdContext() trace ID context}。
   *
   * @since 5.11
   */
  @Nullable public String getValue(TraceContextOrSamplingFlags extracted) {
    if (extracted == null) throw new NullPointerException("extracted == null");
    return context.getValue(this, extracted);
  }

  /**
   * Updates the value of this field, or ignores if read-only or not configured.
   * 更新此字段的值，如果是只读的或未配置，则忽略。
   *
   * @since 5.11
   */
  public boolean updateValue(@Nullable TraceContext context, @Nullable String value) {
    if (context == null) return false;
    if (this.context.updateValue(this, context, value)) {
      CorrelationFlushScope.flush(this, value);
      return true;
    }
    return false;
  }

  /**
   * Like {@link #updateValue(TraceContext, String)} except for use cases that precede a span. For
   * example, a {@linkplain TraceContextOrSamplingFlags#traceIdContext() trace ID context}.
   * 像 {@link #updateValue(TraceContext, String)} 一样，除了用于先于 span 的用例
   *
   * @since 5.11
   */
  public boolean updateValue(TraceContextOrSamplingFlags extracted, @Nullable String value) {
    if (extracted == null) throw new NullPointerException("extracted == null");
    if (context.updateValue(this, extracted, value)) {
      CorrelationFlushScope.flush(this, value);
      return true;
    }
    return false;
  }

  /**
   * Like {@link #updateValue(TraceContext, String)} except against the current trace context.
   * 像 {@link #updateValue(TraceContext, String)} 一样，除了针对当前跟踪上下文。
   *
   * <p>Prefer {@link #updateValue(TraceContext, String)} if you have a reference to the trace
   * context.
   *
   * @since 5.11
   */
  public boolean updateValue(String value) {
    return updateValue(currentTraceContext(), value);
  }

  @Override public String toString() {
    return "BaggageField{" + name + "}";
  }

  /** Returns true for any baggage field with the same name (case insensitive). */
  @Override public final boolean equals(Object o) {
    if (o == this) return true;
    if (!(o instanceof BaggageField)) return false;
    return lcName.equals(((BaggageField) o).lcName);
  }

  /** Returns the same value for any baggage field with the same name (case insensitive). */
  @Override public final int hashCode() {
    return lcName.hashCode();
  }

  static String validateName(String name) {
    if (name == null) throw new NullPointerException("name == null");
    name = name.trim();
    if (name.isEmpty()) throw new IllegalArgumentException("name is empty");
    return name;
  }

  @Nullable static TraceContext currentTraceContext() {
    Tracing tracing = Tracing.current();
    return tracing != null ? tracing.currentTraceContext().get() : null;
  }
}
