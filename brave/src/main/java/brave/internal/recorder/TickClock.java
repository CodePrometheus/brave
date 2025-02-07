/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.internal.recorder;

import brave.Clock;
import brave.internal.Platform;

final class TickClock implements Clock {
  final Platform platform;
  final long baseEpochMicros;
  final long baseTickNanos;

  TickClock(Platform platform, long baseEpochMicros, long baseTickNanos) {
    this.platform = platform;
    this.baseEpochMicros = baseEpochMicros; // 基准绝对时间，单位us
    this.baseTickNanos = baseTickNanos; // 基准相对时间，单位ns
  }

  @Override public long currentTimeMicroseconds() {
    // 在计算当前时间的时候，会通过当前的nanoTime() - 基准相对时间，
    // 再加上 基准绝对时间 就可以计算得到当前的时间，其中流逝的时间精度为纳秒
    return ((platform.nanoTime() - baseTickNanos) / 1000) + baseEpochMicros;
  }

  @Override public String toString() {
    return "TickClock{"
      + "baseEpochMicros=" + baseEpochMicros + ", "
      + "baseTickNanos=" + baseTickNanos
      + "}";
  }
}
