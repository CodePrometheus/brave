/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave;

import brave.propagation.TraceContext;
import brave.sampler.SamplerFunction;

/**
 * Abstract request type used for parsing and sampling. When implemented, it will be the parameter
 * of {@link SamplerFunction} or {@link TraceContext.Extractor}.
 * 用于解析和采样的抽象请求类型。 当实现时，它将是{@link SamplerFunction}或{@link TraceContext.Extractor}的参数。
 *
 * <h3>No extensions outside Brave</h3>
 * While this is an abstract type, it should not be subclassed outside the Brave repository. In
 * other words, subtypes are sealed within this source tree.
 * 虽然这是一个抽象类型，但它不应在Brave存储库之外进行子类化。 换句话说，子类型在此源树中被封闭。
 *
 * @see SamplerFunction
 * @see TraceContext.Extractor
 * @see TraceContext.Injector
 * @see Response
 * @since 5.9
 */
public abstract class Request {
  /** The remote {@link Span.Kind} describing the direction and type of the request. */
  public abstract Span.Kind spanKind();

  /**
   * Returns the underlying request object or {@code null} if there is none. Here are some request
   * objects: {@code org.apache.http.HttpRequest}, {@code org.apache.dubbo.rpc.Invocation}, {@code
   * org.apache.kafka.clients.consumer.ConsumerRecord}.
   * 返回基础请求对象或{@code null}（如果没有）。 这里有一些请求对象：{@code org.apache.http.HttpRequest}，
   * {@code org.apache.dubbo.rpc.Invocation}，{@code org.apache.kafka.clients.consumer.ConsumerRecord}。
   *
   * <p>Note: Some implementations are composed of multiple types, such as a request and a socket
   * address of the client. Moreover, an implementation may change the type returned due to
   * refactoring. Unless you control the implementation, cast carefully (ex using {@code
   * instanceof}) instead of presuming a specific type will always be returned.
   * 注意：某些实现由多种类型组成，例如客户端的请求和套接字地址。 此外，由于重构，实现可能会更改返回的类型。
   * 除非您控制实现，否则请小心转换（例如使用{@code instanceof}），而不是假定始终会返回特定类型。
   *
   * @since 5.9
   */
  public abstract Object unwrap();

  @Override public String toString() {
    Object unwrapped = unwrap();
    // handles case where unwrap() returning this or null: don't NPE or stack overflow!
    if (unwrapped == null || unwrapped == this) return getClass().getSimpleName();
    return getClass().getSimpleName() + "{" + unwrapped + "}";
  }

  protected Request() { // no instances of this type: only subtypes
  }
}
