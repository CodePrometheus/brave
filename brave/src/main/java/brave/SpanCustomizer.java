/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave;

/**
 * Simple interface users can customize a span with. For example, this can add custom tags useful in
 * looking up spans.
 * 用户可以使用的简单接口，用于自定义跨度。例如，这可以添加在查找跨度时有用的自定义标记。
 *
 * <h3>Usage notes</h3>
 * This type is safer to expose directly to users than {@link Span}, as it has no hooks that can
 * affect the span lifecycle.
 * 与{@link Span}相比，这种类型更安全地直接暴露给用户，因为它没有可以影响跨度生命周期的钩子。
 *
 * @see Tag
 */
// Java language level 6. Do not add methods as it will break API!
public interface SpanCustomizer {
  /**
   * Sets the string name for the logical operation this span represents.
   * 设置此跨度表示的逻辑操作的字符串名称。
   */
  SpanCustomizer name(String name);

  /**
   * Tags give your span context for search, viewing and analysis. For example, a key
   * "your_app.version" would let you lookup spans by version. A tag "sql.query" isn't searchable,
   * but it can help in debugging when viewing a trace.
   * 标记为您的跨度提供搜索、查看和分析的上下文。例如，一个键"your_app.version"可以让您按版本查找跨度。标记"sql.query"不可搜索，但在查看跟踪时可以帮助调试。
   *
   * <p><em>Note:</em>To guard potentially expensive parsing, implement {@link Tag} instead, which
   * avoids parsing into a no-op span.
   * <em>注意:</em>为了保护可能昂贵的解析，实现{@link Tag}，而不是解析为无操作跨度。
   *
   * <p>Ex.
   * <pre>{@code
   * SUMMARY_TAG = new Tag<Summarizer>("summary") {
   *   @Override protected String parseValue(Summarizer input, TraceContext context) {
   *     return input.computeSummary();
   *   }
   * }
   * SUMMARY_TAG.tag(span);
   * }</pre>
   *
   * @param key Name used to lookup spans, such as "your_app.version".
   * @param value String value, cannot be <code>null</code>.
   * @see Tag#tag(Object, SpanCustomizer)
   */
  SpanCustomizer tag(String key, String value);

  /**
   * Associates an event that explains latency with the current system time.
   * 将解释延迟的事件与当前系统时间关联。
   *
   * @param value A short tag indicating the event, like "finagle.retry"
   */
  SpanCustomizer annotate(String value);
}
