/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.propagation;

import brave.Request;
import brave.Span.Kind;
import brave.baggage.BaggagePropagation;
import brave.internal.Nullable;
import java.util.List;

/**
 * Injects and extracts {@link TraceContext trace identifiers} as text into requests that travel
 * in-band across process boundaries. Identifiers are often encoded as messaging or RPC request
 * headers.
 * 将 {@link TraceContext trace identifiers} 作为文本注入和提取到跨进程边界传输的请求中。标识通常被编码为消息或 RPC 请求头。
 *
 * <h3>Propagation example: HTTP</h3>
 *
 * <p>When using HTTP, the client (injector) and server (extractor) use request headers. The client
 * {@linkplain TraceContext.Injector#inject injects} the trace context into headers before the
 * request is sent to the server. The server {@linkplain TraceContext.Extractor#extract extracts} a
 * trace context from these headers before processing the request.
 * 使用 HTTP 时，客户端（注入器）和服务器（提取器）使用请求头。 客户端 {@linkplain TraceContext.Injector#inject 在将请求发送到服务器之前将跟踪上下文注入到标头中。
 * 服务器 {@linkplain TraceContext.Extractor#extract 在处理请求之前从这些标头中提取跟踪上下文。
 *
 * @param <K> Retained for compatibility with pre Brave 6.0, but always String.
 * @since 4.0
 */
// The generic type parameter K is always <String>. Even if the deprecated methods are removed in
// Brave 6.0. This is to avoid a compilation break and revlock.
public interface Propagation<K> {
  /**
   * Defaults B3 formats based on {@link Request} type. When not a {@link Request} (e.g. in-process
   * messaging), this uses {@link B3Propagation.Format#SINGLE_NO_PARENT}.
   * 默认的 B3 格式基于 {@link Request} 类型。 当不是 {@link Request}（例如，在进程中的消息传递）时，使用 {@link B3Propagation.Format#SINGLE_NO_PARENT}。
   *
   * @since 4.0
   */
  Propagation<String> B3_STRING = B3Propagation.get();
  /**
   * Implements the propagation format described in {@link B3SingleFormat}.
   */
  Propagation<String> B3_SINGLE_STRING = B3SinglePropagation.FACTORY.get();

  /** @since 4.0 */
  abstract class Factory {
    /**
     * Does the propagation implementation support sharing client and server span IDs. For example,
     * should an RPC server span share the same identifiers extracted from an incoming request?
     * 传播实现是否支持共享客户端和服务器跨度 ID。 例如，RPC 服务器跨度是否应与从传入请求中提取的相同标识符共享？
     *
     * <p>In usual <a href="https://github.com/openzipkin/b3-propagation">B3 Propagation</a>, the
     * parent span ID is sent across the wire so that the client and server can share the same
     * identifiers. Other propagation formats, like <a href="https://github.com/w3c/trace-context">trace-context</a>
     * only propagate the calling trace and span ID, with an assumption that the receiver always
     * starts a new child span. When join is supported, you can assume that when {@link
     * TraceContext#parentId() the parent span ID} is null, you've been propagated a root span. When
     * join is not supported, you must always fork a new child.
     * 在通常的B3 传播中 父 spanID 通过网络发送，以便客户端和服务器可以共享相同的 identifiers
     * 其他传播格式，如 trace-context 仅传播调用跟踪和 spanID，并假设接收方始终启动新的子 span
     * 当支持 join 时，您可以假设当父 spanID 为 null 时，您已传播了一个根 span。 当不支持 join 时，您必须始终 fork 一个新的子 span。
     *
     * @since 4.7
     */
    public boolean supportsJoin() {
      return false;
    }

    /**
     * Returns {@code true} if the implementation cannot use 64-bit trace IDs.
     *
     * @since 4.9
     */
    public boolean requires128BitTraceId() {
      return false;
    }

    /**
     * @deprecated end users and instrumentation should never call this, and instead use
     * {@link #get()}. This will be removed in Brave 7, to allow users to transition without revlock
     * upgrading to Brave 6.
     */
    @Deprecated public <K> Propagation<K> create(KeyFactory<K> unused) {
      // In Brave 5.12, this was abstract, but not used: `get()` dispatched
      // to this. Brave 5.18 implemented this with the below exception to force
      // `get()` to be overridden. Doing so allows us to make `get()` abstract
      // in Brave 6.0. Then, this can be safely removed in Brave 7.0 without a
      // revlock.
      throw new UnsupportedOperationException("This was replaced with PropagationFactory.get() in Brave 5.12");
    }

    /**
     * Returns a possibly cached propagation instance.
     *
     * @since 5.12
     */
    public Propagation<String> get() {
      // In Brave 5.12, this dispatched to the deprecated abstract method
      // `create()`. In Brave 5.18, we throw an exception instead to ensure it
      // is implemented prior to Brave 6.0 making this abstract.
      throw new UnsupportedOperationException("As of Brave 5.18, you must implement PropagationFactory.get()");
    }

    /**
     * Decorates the input such that it can propagate extra state, such as a timestamp or baggage.
     * 修饰输入，使其可以传播额外的状态，例如时间戳或行李。
     *
     * <p>Implementations are responsible for data scoping, if relevant. For example, if only
     * global configuration is present, it could suffice to simply ensure that data is present. If
     * data is span-scoped, an implementation might compare the context to its last span ID, copying
     * on write or otherwise to ensure writes to one context don't affect another.
     * 如果只有全局配置存在，只需确保数据存在即可。 
     * 如果数据是跨度范围的，则实现可能会将上下文与其上一个跨度 ID 进行比较，以确保写入一个上下文不会影响另一个上下文。
     *
     * <p>Implementations should be idempotent, returning the same instance instead of re-applying
     * change.
     *
     * @see TraceContext#extra()
     * @since 4.9
     */
    public TraceContext decorate(TraceContext context) {
      return context;
    }
  }

  /**
   * @since 4.0
   * @deprecated since 5.12 non-string keys are no longer supported. This will be removed in Brave
   * 7, to allow users to transition without revlock upgrading to Brave 6.
   */
  @Deprecated
  interface KeyFactory<K> {
    KeyFactory<String> STRING = new KeyFactory<String>() {
      @Override public String create(String name) {
        return name;
      }

      @Override public String toString() {
        return "StringKeyFactory{}";
      }
    };

    K create(String name);
  }

  /**
   * Replaces a propagated key with the given value.
   *
   * @param <R> Usually, but not always, an instance of {@link Request}.
   * @param <K> Retained for compatibility with pre Brave 6.0, but always String.
   * @see RemoteSetter
   * @since 4.0
   */
  interface Setter<R, K> {
    void put(R request, K key, String value);
  }

  /**
   * Returns the key names used for propagation of the current span. The result can be cached in the
   * same scope as the propagation instance.
   * 返回用于传播当前跨度的键名称。 结果可以在与传播实例相同的范围内缓存。
   *
   * <p>This method exists to support remote propagation of trace IDs:
   * <ul>
   *   <li>To generate constants for all key names. ex. gRPC Metadata.Key</li>
   *   <li>To iterate fields when missing a get field by name function. ex. OpenTracing TextMap</li>
   *   <li>Detection of if a context is likely to be present in a request object</li>
   *   <li>To clear trace ID fields on re-usable requests. ex. JMS message</li>
   * </ul>
   *
   * <h3>Notes</h3>
   * <p>Depending on the format, keys returned may not all be mandatory.
   *
   * <p>If your implementation carries baggage, such as correlation IDs, do not return the names of
   * those fields here. If you do, they will be deleted, which can interfere with user headers.
   * Instead, use {@link BaggagePropagation} which returns those names in {@link BaggagePropagation#allKeyNames(Propagation)}.
   * 如果您的实现携带行李，例如相关 ID，请不要在此处返回这些字段的名称。 如果这样做，它们将被删除，这可能会干扰用户标头。
   * 相反，请使用 {@link BaggagePropagation}，它在 {@link BaggagePropagation#allKeyNames(Propagation)} 中返回这些名称。
   *
   * <h3>Edge-cases</h3>
   * When a request is a single-use or immutable request object, there are no known edge cases to
   * consider. Mutable, retryable objects such as messaging headers should be careful about some
   * edge cases:
   * 当请求是单次使用或不可变请求对象时，没有已知的边缘情况需要考虑。 可变的，可重试的对象，例如消息头，应谨慎考虑一些边缘情况：
   *
   * <p>When multiple headers are used for trace identifiers, ex {@link B3Propagation.Format#MULTI},
   * producers should be careful to clear fields here before calling {@link TraceContext.Injector#inject(TraceContext, Object)}
   * when the input is a new root span. Otherwise, a stale {@link TraceContext#parentIdString()}
   * could be left in the headers and be mistaken for a missing root span.
   * 当使用多个标头用于跟踪标识符时，例如 {@link B3Propagation.Format#MULTI}，生产者在调用 {@link TraceContext.Injector#inject(TraceContext, Object)} 时，
   * 应谨慎在此处清除字段，当输入是新的根 span 时。 否则，旧的 {@link TraceContext#parentIdString()} 可能会留在标头中，并被误认为是缺少的根 span。
   *
   * <p>Headers here should be cleared when invoking listeners in
   * {@linkplain CurrentTraceContext.Scope scope}. Doing so prevents precedence rules that prefer
   * the trace context in message headers from overriding the current span. Doing so would place any
   * follow-up activity in the wrong spot in the trace tree.
   * 在调用 {@linkplain CurrentTraceContext.Scope scope} 中的侦听器时，应清除此处的标头。 
   * 这样做可以防止优先规则，该规则优先于消息标头中的跟踪上下文，从而覆盖当前跨度。 这样做会将任何后续活动放在跟踪树中的错误位置。
   *
   * @see BaggagePropagation#allKeyNames(Propagation)
   * @since 4.0
   */
  List<K> keys();

  /**
   * Replaces a propagated field with the given value. Saved as a constant to avoid runtime
   * allocations.
   * 用给定值替换传播字段。 保存为常量以避免运行时分配。
   *
   * For example, a setter for an {@link java.net.HttpURLConnection} would be the method reference
   * {@link java.net.HttpURLConnection#addRequestProperty(String, String)}
   * 例如，{@link java.net.HttpURLConnection} 的 setter 将是方法引用 {@link java.net.HttpURLConnection#addRequestProperty(String, String)}
   *
   * @param setter invoked for each propagation key to add.
   * @param <R> Usually, but not always, an instance of {@link Request}.
   * @see RemoteSetter
   * @since 4.0
   */
  <R> TraceContext.Injector<R> injector(Setter<R, K> setter);

  /**
   * Gets the first value of the given propagation key or returns {@code null}.
   *
   * @param <R> Usually, but not always, an instance of {@link Request}.
   * @param <K> Retained for compatibility with pre Brave 6.0, but always String.
   * @see RemoteGetter
   * @since 4.0
   */
  interface Getter<R, K> {
    @Nullable String get(R request, K key);
  }

  /**
   * Used as an input to {@link Propagation#injector(Setter)} inject the {@linkplain TraceContext
   * trace context} and any {@linkplain BaggagePropagation baggage} as propagated fields.
   *
   * @param <R> usually {@link Request}, such as an HTTP server request or message
   * @see RemoteGetter
   * @since 5.12
   */
  // this is not `R extends Request` for APIs like OpenTracing that know the remote kind, but don't
  // implement the `Request` abstraction
  interface RemoteSetter<R> extends Setter<R, String> {
    /**
     * The only valid options are {@link Kind#CLIENT}, {@link Kind#PRODUCER}, and {@link
     * Kind#CONSUMER}.
     *
     * @see Request#spanKind()
     * @since 5.12
     */
    Kind spanKind();

    /**
     * Replaces a propagation field with the given value.
     *
     * <p><em>Note</em>: Implementations attempt to overwrite all values. This means that when the
     * caller is encoding multi-value (comma-separated list) HTTP header, they MUST join all values
     * on comma into a single string.
     *
     * @param request see {@link #<R>}
     * @param fieldName typically a header name
     * @param value non-{@code null} value to replace any values with
     * @see RemoteGetter
     * @since 5.12
     */
    @Override void put(R request, String fieldName, String value);
  }

  /**
   * @param getter invoked for each propagation key to get.
   * @param <R> Usually, but not always, an instance of {@link Request}.
   * @see RemoteGetter
   * @since 4.0
   */
  <R> TraceContext.Extractor<R> extractor(Getter<R, K> getter);

  /**
   * Used as an input to {@link Propagation#extractor(Getter)} extract the {@linkplain TraceContext
   * trace context} and any {@linkplain BaggagePropagation baggage} from propagated fields.
   * 用作输入到 {@link Propagation#extractor(Getter)} 从传播字段中提取 {@linkplain TraceContext trace context} 和任何 {@linkplain BaggagePropagation baggage}。
   *
   * @param <R> usually {@link Request}, such as an HTTP server request or message
   * @see RemoteSetter
   * @since 5.12
   */
  // this is not `R extends Request` for APIs like OpenTracing that know the remote kind, but don't
  // implement the `Request` abstraction
  interface RemoteGetter<R> extends Getter<R, String> {
    /**
     * The only valid options are {@link Kind#SERVER}, {@link Kind#PRODUCER}, and {@link
     * Kind#CONSUMER}.
     *
     * @see Request#spanKind()
     * @since 5.12
     */
    Kind spanKind();

    /**
     * Gets the propagation field as a single value.
     *
     * <p><em>Note</em>: HTTP only permits multiple header fields with the same name when the
     * format
     * is a comma-separated list. An HTTP implementation of this method will assume presence of
     * multiple values is valid and join them with a comma. See <a href="https://tools.ietf.org/html/rfc7230#section-3.2.2">RFC
     * 7230</a> for more.
     *
     * @param request see {@link #<R>}
     * @param fieldName typically a header name
     * @return the value of the field or {@code null}
     * @since 5.12
     */
    @Nullable @Override String get(R request, String fieldName);
  }
}
