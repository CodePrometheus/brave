/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.propagation;

import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.internal.Nullable;
import java.util.ArrayDeque;

/**
 * This type allows you to place a span in scope in one method and access it in another without
 * using an explicit request parameter.
 * 这个类型允许你在一个方法中放置一个span并在另一个方法中访问它，而不使用显式的请求参数。
 *
 * <p>Many libraries expose a callback model as opposed to an interceptor one. When creating new
 * instrumentation, you may find places where you need to place a span in scope in one callback
 * (like `onStart()`) and end the scope in another callback (like `onFinish()`).
 * 许多库暴露了一个回调模型，而不是一个拦截器模型。
 * 在创建新的仪器时，您可能会发现需要在一个回调中放置一个span（如`onStart()`）并在另一个回调中结束该作用域（如`onFinish()`）。
 *
 * <p>Provided the library guarantees these run on the same thread, you can simply propagate the
 * result of {@link Tracer#startScopedSpan(String)} or {@link Tracer#withSpanInScope(Span)} from the
 * starting callback to the closing one. This is typically done with a request-scoped attribute.
 * 假设库保证这些在同一个线程上运行，你可以简单地传播{@link Tracer#startScopedSpan(String)}或{@link Tracer#withSpanInScope(Span)}的结果从开始回调到结束回调。
 * 这通常是通过一个请求范围的属性来完成的。
 *
 * Here's an example:
 * <pre>{@code
 * class MyFilter extends Filter {
 *   public void onStart(Request request, Attributes attributes) {
 *     // Assume you have code to start the span and add relevant tags...
 *
 *     // We now set the span in scope so that any code between here and
 *     // the end of the request can see it with Tracer.currentSpan()
 *     SpanInScope spanInScope = tracer.withSpanInScope(span);
 *
 *     // We don't want to leak the scope, so we place it somewhere we can
 *     // lookup later
 *     attributes.put(SpanInScope.class, spanInScope);
 *   }
 *
 *   public void onFinish(Response response, Attributes attributes) {
 *     // as long as we are on the same thread, we can read the span started above
 *     Span span = tracer.currentSpan();
 *
 *     // Assume you have code to complete the span
 *
 *     // We now remove the scope (which implicitly detaches it from the span)
 *     attributes.remove(SpanInScope.class).close();
 *   }
 * }
 * }</pre>
 *
 * <p>Sometimes you have to instrument a library where There's no attribute namespace shared across
 * request and response. For this scenario, you can use {@link ThreadLocalSpan} to temporarily store
 * the span between callbacks.
 * 有时您必须在没有跨请求和响应共享的属性命名空间的库中进行插桩。
 * 对于这种情况，您可以使用{@link ThreadLocalSpan}在回调之间临时存储span。
 *
 * Here's an example:
 * <pre>{@code
 * class MyFilter extends Filter {
 *   final ThreadLocalSpan threadLocalSpan;
 *
 *   public void onStart(Request request) {
 *     // Allocates a span and places it in scope so that code between here and onFinish can see it
 *     Span span = threadLocalSpan.next();
 *     if (span == null || span.isNoop()) return; // skip below logic on noop
 *
 *     // Assume you have code to start the span and add relevant tags...
 *   }
 *
 *   public void onFinish(Response response, Attributes attributes) {
 *     // as long as we are on the same thread, we can read the span started above
 *     Span span = threadLocalSpan.remove();
 *     if (span == null || span.isNoop()) return; // skip below logic on noop
 *
 *     // Assume you have code to complete the span
 *   }
 * }
 * }</pre>
 */
public class ThreadLocalSpan {
  /**
   * This uses the {@link Tracing#currentTracer()}, which means calls to {@link #next()} may return
   * null. Use this when you have no other means to get a reference to the tracer. For example, JDBC
   * connections, as they often initialize prior to the tracing component.
   * 这使用了{@link Tracing#currentTracer()}，这意味着调用{@link #next()}可能返回null。
   * 当你没有其他方法获取跟踪器的引用时，请使用这个。例如，JDBC连接，因为它们通常在跟踪组件之前初始化。
   */
  public static final ThreadLocalSpan CURRENT_TRACER = new ThreadLocalSpan(null);

  public static ThreadLocalSpan create(Tracer tracer) {
    if (tracer == null) throw new NullPointerException("tracer == null");
    return new ThreadLocalSpan(tracer);
  }

  @Nullable final Tracer tracer;

  ThreadLocalSpan(Tracer tracer) {
    this.tracer = tracer;
  }

  Tracer tracer() {
    return tracer != null ? tracer : Tracing.currentTracer();
  }

  /**
   * Returns the {@link Tracer#nextSpan(TraceContextOrSamplingFlags)} or null if {@link
   * #CURRENT_TRACER} and tracing isn't available.
   */
  @Nullable public Span next(TraceContextOrSamplingFlags extracted) {
    Tracer tracer = tracer();
    if (tracer == null) return null;
    Span next = tracer.nextSpan(extracted);
    SpanAndScope spanAndScope = new SpanAndScope(next, tracer.withSpanInScope(next));
    getCurrentSpanInScopeStack().addFirst(spanAndScope);
    return next;
  }

  /**
   * Returns the {@link Tracer#nextSpan()} or null if {@link #CURRENT_TRACER} and tracing isn't
   * available.
   * 返回{@link Tracer#nextSpan()}或null，如果{@link #CURRENT_TRACER}和跟踪不可用。
   */
  @Nullable public Span next() {
    Tracer tracer = tracer();
    if (tracer == null) return null;
    Span next = tracer.nextSpan();
    SpanAndScope spanAndScope = new SpanAndScope(next, tracer.withSpanInScope(next));
    getCurrentSpanInScopeStack().addFirst(spanAndScope);
    return next;
  }

  static final class SpanAndScope {
    final Span span;
    final SpanInScope spanInScope;

    SpanAndScope(Span span, SpanInScope spanInScope) {
      this.span = span;
      this.spanInScope = spanInScope;
    }
  }

  /**
   * Returns the span set in scope via {@link #next()} or null if there was none.
   * 返回通过{@link #next()}设置的span，如果没有则返回null。
   *
   * <p>When assertions are on, this will throw an assertion error if the span returned was not the
   * one currently in context. This could happen if someone called {@link
   * Tracer#withSpanInScope(Span)} or {@link CurrentTraceContext#newScope(TraceContext)} outside a
   * try/finally block.
   * 当断言打开时，如果返回的span不是当前上下文中的span，则会抛出一个断言错误。
   * 如果有人在try/finally块之外调用{@link Tracer#withSpanInScope(Span)}或{@link CurrentTraceContext#newScope(TraceContext)}，就会发生这种情况。
   */
  @Nullable public Span remove() {
    Tracer tracer = tracer();
    Span currentSpan = tracer != null ? tracer.currentSpan() : null;
    SpanAndScope spanAndScope = getCurrentSpanInScopeStack().pollFirst();
    if (spanAndScope == null) return currentSpan;

    Span span = spanAndScope.span;
    spanAndScope.spanInScope.close();
    assert span.equals(currentSpan) :
      "Misalignment: scoped span " + span + " !=  current span " + currentSpan;
    return currentSpan;
  }

  /**
   * This keeps track of a stack with a normal array dequeue. Redundant stacking of the same span is
   * not possible because there is no api to place an arbitrary span in scope using this api.
   * 这使用一个普通的数组队列来跟踪堆栈。 由于没有api可以使用这个api将任意span放在作用域中，因此不可能重复堆叠相同的span。
   */
  @SuppressWarnings("ThreadLocalUsage") // intentional: to support multiple Tracer instances
  final ThreadLocal<ArrayDeque<SpanAndScope>> currentSpanInScopeStack =
    new ThreadLocal<ArrayDeque<SpanAndScope>>();

  ArrayDeque<SpanAndScope> getCurrentSpanInScopeStack() {
    ArrayDeque<SpanAndScope> stack = currentSpanInScopeStack.get();
    if (stack == null) {
      stack = new ArrayDeque<SpanAndScope>();
      currentSpanInScopeStack.set(stack);
    }
    return stack;
  }
}
