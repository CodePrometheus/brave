/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.propagation;

import brave.Tracer;
import brave.Tracing;
import brave.internal.Nullable;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * This makes a given span the current span by placing it in scope (usually but not always a thread
 * local scope).
 * 通过将给定的 span 放入作用域（通常但并非总是线程本地作用域）来使其成为当前 span。
 *
 * <p>This type is an SPI, and intended to be used by implementors looking to change thread-local
 * storage, or integrate with other contexts such as logging (MDC).
 * 这种类型是 SPI，旨在供实现者使用，以更改线程本地存储，或与其他上下文集成，例如日志记录（MDC）。
 *
 * <h3>Design</h3>
 *
 * This design was inspired by com.google.instrumentation.trace.ContextUtils,
 * com.google.inject.servlet.RequestScoper and com.github.kristofa.brave.CurrentSpan
 */
public abstract class CurrentTraceContext {
  static {
    // ensure a reference to InternalPropagation exists
    String unused = SamplingFlags.DEBUG.toString();
  }

  /** Implementations of this allow standardized configuration, for example scope decoration. */
  public abstract static class Builder {
    ArrayList<ScopeDecorator> scopeDecorators = new ArrayList<ScopeDecorator>();

    /**
     * Implementations call decorators in order to add features like log correlation to a scope.
     *
     * @since 5.2
     */
    public Builder addScopeDecorator(ScopeDecorator scopeDecorator) {
      if (scopeDecorator == null) throw new NullPointerException("scopeDecorator == null");
      if (scopeDecorator == ScopeDecorator.NOOP) return this;
      this.scopeDecorators.add(scopeDecorator);
      return this;
    }

    public abstract CurrentTraceContext build();
  }

  /** Returns the current span in scope or null if there isn't one. */
  public abstract @Nullable TraceContext get();

  /**
   * Sets the current span in scope until the returned object is closed. It is a programming error
   * to drop or never close the result. Using try-with-resources is preferred for this reason.
   *
   * @param context span to place into scope or null to clear the scope
   */
  public abstract Scope newScope(@Nullable TraceContext context);

  final ScopeDecorator[] scopeDecorators;

  protected CurrentTraceContext() {
    this.scopeDecorators = new ScopeDecorator[0];
  }

  protected CurrentTraceContext(Builder builder) {
    this.scopeDecorators = builder.scopeDecorators.toArray(new ScopeDecorator[0]);
  }

  /**
   * When implementing {@linkplain #newScope(TraceContext)}, decorate the result before returning
   * it.
   *
   * <p>Ex.
   * <pre>{@code
   *   @Override public Scope newScope(@Nullable TraceContext currentSpan) {
   *     final TraceContext previous = local.get();
   *     local.set(currentSpan);
   *     class ThreadLocalScope implements Scope {
   *       @Override public void close() {
   *         local.set(previous);
   *       }
   *     }
   *     Scope result = new ThreadLocalScope();
   *     // ensure scope hooks are attached to the result
   *     return decorateScope(currentSpan, result);
   *   }
   * }</pre>
   *
   * @param scope {@link Scope#NOOP} if the prior context was equal to the {@code context}
   * parameter.
   */
  protected Scope decorateScope(@Nullable TraceContext context, Scope scope) {
    for (ScopeDecorator scopeDecorator : scopeDecorators) {
      scope = scopeDecorator.decorateScope(context, scope);
    }
    return scope;
  }

  /**
   * Like {@link #newScope(TraceContext)}, except returns {@link Scope#NOOP} if the given context is
   * already in scope. This can reduce overhead when scoping callbacks. However, this will not apply
   * any changes, notably in {@link TraceContext#extra()}. As such, it should be used carefully and
   * only in conditions where redundancy is possible and the intent is primarily to facilitate
   * {@link Tracer#currentSpan}. Most often, this is used to eliminate redundant scopes by
   * wrappers.
   * 与{@link #newScope(TraceContext)}类似，但如果给定的上下文已经在作用域中，则返回{@link Scope#NOOP}。
   * 这可以减少在作用域回调时的开销。 但是，这不会应用任何更改，特别是在{@link TraceContext#extra()}中。
   * 因此，应谨慎使用，并且仅在可能存在冗余的情况下使用，主要目的是促进{@link Tracer#currentSpan}。
   * 最常见的情况是通过包装器消除冗余作用域。
   *
   * <p>For example, RxJava includes hooks to wrap types that represent an asynchronous functional
   * composition. For example, {@code flowable.parallel().flatMap(Y).sequential()} Assembly hooks
   * can ensure each stage of this operation can see the initial trace context. However, other tools
   * can also instrument the stages, including vert.x or even agent instrumentation. When wrapping
   * callbacks, it can reduce overhead to use {@code maybeScope} as opposed to {@code newScope}.
   * 例如，RxJava包含用于包装表示异步功能组合的类型的钩子。 例如，{@code flowable.parallel().flatMap(Y).sequential()}。
   * 组装钩子可以确保此操作的每个阶段都可以看到初始跟踪上下文。 但是，其他工具也可以对阶段进行仪器化，包括vert.x甚至代理仪器化。
   * 在包装回调时，使用{@code maybeScope}而不是{@code newScope}可以减少开销。
   *
   * <p>Generally speaking, this is best used for wrappers, such as executor services or lifecycle
   * hooks, which usually have no current trace context when invoked.
   * 一般来说，这最适合用于包装器，例如执行程序服务或生命周期钩子，在调用时通常没有当前跟踪上下文。
   *
   * <h3>Implementors note</h3>
   * <p>For those overriding this method, you must compare {@link TraceContext#traceIdHigh()},
   * {@link TraceContext#traceId()} and {@link TraceContext#spanId()} to decide if the contexts are
   * equivalent. Due to details of propagation, other data like parent ID are not considered in
   * equivalence checks.
   * 对于那些重写此方法的人，您必须比较{@link TraceContext#traceIdHigh()}，{@link TraceContext#traceId()}和{@link TraceContext#spanId()}，
   * 以决定上下文是否等效。 由于传播的细节，其他数据（如父ID）不会在等效性检查中考虑在内。
   *
   * @param context span to place into scope or null to clear the scope
   * @return a new scope object or {@link Scope#NOOP} if the input is already the case
   */
  public Scope maybeScope(@Nullable TraceContext context) {
    TraceContext current = get();
    if (equals(current, context)) return decorateScope(context, Scope.NOOP);
    return newScope(context);
  }

  /** 
   * A span remains in the scope it was bound to until close is called.
   * 区别在于 Scope 会一直保持到 close 被调用。
   */
  public interface Scope extends Closeable {
    /**
     * Returned when {@link CurrentTraceContext#maybeScope(TraceContext)} detected scope
     * redundancy.
     */
    Scope NOOP = new Scope() {
      @Override public void close() {
      }

      @Override public String toString() {
        return "NoopScope";
      }
    };

    /** No exceptions are thrown when unbinding a span scope. */
    @Override void close();
  }

  /**
   * Use this to add features such as thread checks or log correlation when a scope is created or
   * closed.
   * 使用此功能可在创建或关闭作用域时添加功能，例如线程检查或日志相关性。
   *
   * <p>While decoration technically occurs with {@link #newScope(TraceContext)} or
   * {@link #maybeScope(TraceContext)}, many tools use these underneath. For example, {@link
   * brave.Tracer#startScopedSpan(String)} and {@link brave.Tracer#withSpanInScope(brave.Span)} set
   * a span in scope. An executor wrapped with {@link #executor(Executor)} would decorate each
   * runnable.
   * 虽然装饰技术上发生在{@link #newScope(TraceContext)}或{@link #maybeScope(TraceContext)}中，
   * 但许多工具在这些工具下使用这些工具。 例如，{@link brave.Tracer#startScopedSpan(String)}和{@link brave.Tracer#withSpanInScope(brave.Span)}设置了一个跟踪范围。
   * 使用{@link #executor(Executor)}包装的执行程序将装饰每个可运行项。
   *
   * @since 5.2
   */
  public interface ScopeDecorator {
    /**
     * Use this when configuration results in no decoration needed.
     *
     * @since 5.11
     */
    ScopeDecorator NOOP = new ScopeDecorator() {
      @Override public Scope decorateScope(TraceContext context, Scope scope) {
        return scope;
      }

      @Override public String toString() {
        return "NoopScopeDecorator";
      }
    };

    /**
     * @param context null implies the scope should be cleared
     * @param scope {@link Scope#NOOP} if the former decoration resulted in no change.
     */
    Scope decorateScope(@Nullable TraceContext context, Scope scope);
  }

  /**
   * Default implementation which is backed by a static thread local.
   * 默认实现，由静态线程本地支持。
   *
   * <p>A static thread local ensures we have one context per thread, as opposed to one per thread-
   * tracer. This means all tracer instances will be able to see any tracer's contexts.
   * 静态线程本地确保我们每个线程有一个上下文，而不是每个线程-跟踪器。 这意味着所有跟踪器实例都可以看到任何跟踪器的上下文。
   *
   * <p>The trade-off of this (instance-based reference) vs the reverse: trace contexts are not
   * separated by tracer by default. For example, to make a trace invisible to another tracer, you
   * have to use a non-default implementation.
   * 这种（基于实例的引用）与反向之间的权衡：跟踪上下文默认情况下不会按跟踪器分开。 
   * 例如，要使跟踪对另一个跟踪器不可见，您必须使用非默认实现。
   *
   * <p>Sometimes people make different instances of the tracer just to change configuration like
   * the local service name. If we used a thread-instance approach, none of these would be able to
   * see eachother's scopes. This would break {@link Tracing#currentTracer()} scope visibility in a
   * way few would want to debug. It might be phrased as "MySQL always starts a new trace and I
   * don't know why."
   * 有时，人们只是为了更改本地服务名称等配置而制作跟踪器的不同实例。
   * 如果我们使用线程实例方法，那么这些实例将无法看到彼此的作用域。 这将以一种很少有人想要调试的方式破坏{@link Tracing#currentTracer()}作用域可见性。
   * 它可能被表述为“MySQL总是开始新的跟踪，我不知道为什么。”
   *
   * <p>If you want a different behavior, use a different subtype of {@link CurrentTraceContext},
   * possibly your own, or raise an issue and explain what your use case is.
   */
  public static final class Default extends ThreadLocalCurrentTraceContext {
    // Inheritable as Brave 3's ThreadLocalServerClientAndLocalSpanState was inheritable
    static final InheritableThreadLocal<TraceContext> INHERITABLE =
      new InheritableThreadLocal<TraceContext>();

    /** Uses a non-inheritable static thread local */
    public static CurrentTraceContext create() {
      return ThreadLocalCurrentTraceContext.create();
    }

    /**
     * Uses an inheritable static thread local which allows arbitrary calls to {@link
     * Thread#start()} to automatically inherit this context. This feature is available as it is was
     * the default in Brave 3, because some users couldn't control threads in their applications.
     *
     * <p>This can be a problem in scenarios such as thread pool expansion, leading to data being
     * recorded in the wrong span, or spans with the wrong parent. If you are impacted by this,
     * switch to {@link #create()}.
     */
    public static CurrentTraceContext inheritable() {
      return new Default();
    }

    Default() {
      super(new Builder(INHERITABLE));
    }
  }

  /** Wraps the input so that it executes with the same context as now. */
  public <C> Callable<C> wrap(final Callable<C> task) {
    final TraceContext invocationContext = get();
    class CurrentTraceContextCallable implements Callable<C> {
      @Override public C call() throws Exception {
        Scope scope = maybeScope(invocationContext);
        try {
          return task.call();
        } finally {
          scope.close();
        }
      }
    }
    return new CurrentTraceContextCallable();
  }

  /** Wraps the input so that it executes with the same context as now. */
  public Runnable wrap(final Runnable task) {
    final TraceContext invocationContext = get();
    class CurrentTraceContextRunnable implements Runnable {
      @Override public void run() {
        Scope scope = maybeScope(invocationContext);
        try {
          task.run();
        } finally {
          scope.close();
        }
      }
    }
    return new CurrentTraceContextRunnable();
  }

  /**
   * Decorates the input such that the {@link #get() current trace context} at the time a task is
   * scheduled is made current when the task is executed.
   */
  public Executor executor(final Executor delegate) {
    class CurrentTraceContextExecutor implements Executor {
      @Override public void execute(Runnable task) {
        delegate.execute(CurrentTraceContext.this.wrap(task));
      }
    }
    return new CurrentTraceContextExecutor();
  }

  /**
   * Decorates the input such that the {@link #get() current trace context} at the time a task is
   * scheduled is made current when the task is executed.
   */
  public ExecutorService executorService(final ExecutorService delegate) {
    class CurrentTraceContextExecutorService extends brave.internal.WrappingExecutorService {

      @Override protected ExecutorService delegate() {
        return delegate;
      }

      @Override protected <C> Callable<C> wrap(Callable<C> task) {
        return CurrentTraceContext.this.wrap(task);
      }

      @Override protected Runnable wrap(Runnable task) {
        return CurrentTraceContext.this.wrap(task);
      }
    }
    return new CurrentTraceContextExecutorService();
  }

  static boolean equals(@Nullable TraceContext a, @Nullable TraceContext b) {
    return a == null ? b == null : a.equals(b); // Java 6 can't use Objects.equals()
  }
}
