/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.lang;

/**
 * Implements a Java {@code long} in a way that can be translated to JavaScript.
 *
 * <p>This implementation uses native JavaScript {@code BigInt} for all long operations.
 * At runtime, long values are represented as BigInt primitives, providing exact 64-bit
 * signed integer semantics with native performance. The {@code LongEmul} type is a
 * compile-time placeholder that maps to BigInt at runtime.
 *
 * <p>A JVM fallback mode ({@code RUN_IN_JVM = true}) is retained so that unit tests
 * for this class can still execute on a standard JVM.
 */
public class LongLib {

  /**
   * Opaque wrapper for a long value. In JavaScript this is a BigInt primitive.
   * In JVM test mode it wraps a Java {@code long}.
   */
  static class LongEmul {
    long value; // used only in JVM mode
  }

  /**
   * Allow standalone Java tests to run this code on a standard JVM
   * (where BigInt is not available).
   */
  protected static boolean RUN_IN_JVM = false;

  // ---- Arithmetic operations ----

  public static LongEmul add(LongEmul a, LongEmul b) {
    if (RUN_IN_JVM) {
      return jvmWrap(jvmUnwrap(a) + jvmUnwrap(b));
    }
    return add0(a, b);
  }

  private static native LongEmul add0(LongEmul a, LongEmul b) /*-{
    return BigInt.asIntN(64, a + b);
  }-*/;

  public static LongEmul sub(LongEmul a, LongEmul b) {
    if (RUN_IN_JVM) {
      return jvmWrap(jvmUnwrap(a) - jvmUnwrap(b));
    }
    return sub0(a, b);
  }

  private static native LongEmul sub0(LongEmul a, LongEmul b) /*-{
    return BigInt.asIntN(64, a - b);
  }-*/;

  public static LongEmul mul(LongEmul a, LongEmul b) {
    if (RUN_IN_JVM) {
      return jvmWrap(jvmUnwrap(a) * jvmUnwrap(b));
    }
    return mul0(a, b);
  }

  private static native LongEmul mul0(LongEmul a, LongEmul b) /*-{
    return BigInt.asIntN(64, a * b);
  }-*/;

  public static LongEmul div(LongEmul a, LongEmul b) {
    if (RUN_IN_JVM) {
      return jvmWrap(jvmUnwrap(a) / jvmUnwrap(b));
    }
    return div0(a, b);
  }

  private static native LongEmul div0(LongEmul a, LongEmul b) /*-{
    // BigInt division truncates toward zero (matching Java semantics).
    // BigInt.asIntN handles the MIN_VALUE / -1 overflow case.
    return BigInt.asIntN(64, a / b);
  }-*/;

  public static LongEmul mod(LongEmul a, LongEmul b) {
    if (RUN_IN_JVM) {
      return jvmWrap(jvmUnwrap(a) % jvmUnwrap(b));
    }
    return mod0(a, b);
  }

  private static native LongEmul mod0(LongEmul a, LongEmul b) /*-{
    // BigInt % has same sign-of-dividend semantics as Java.
    return a % b;
  }-*/;

  public static LongEmul neg(LongEmul a) {
    if (RUN_IN_JVM) {
      return jvmWrap(-jvmUnwrap(a));
    }
    return neg0(a);
  }

  private static native LongEmul neg0(LongEmul a) /*-{
    return BigInt.asIntN(64, -a);
  }-*/;

  // ---- Bitwise operations ----

  public static LongEmul not(LongEmul a) {
    if (RUN_IN_JVM) {
      return jvmWrap(~jvmUnwrap(a));
    }
    return not0(a);
  }

  private static native LongEmul not0(LongEmul a) /*-{
    // ~x on a 64-bit signed BigInt yields a 64-bit signed result.
    return ~a;
  }-*/;

  public static LongEmul and(LongEmul a, LongEmul b) {
    if (RUN_IN_JVM) {
      return jvmWrap(jvmUnwrap(a) & jvmUnwrap(b));
    }
    return and0(a, b);
  }

  private static native LongEmul and0(LongEmul a, LongEmul b) /*-{
    return a & b;
  }-*/;

  public static LongEmul or(LongEmul a, LongEmul b) {
    if (RUN_IN_JVM) {
      return jvmWrap(jvmUnwrap(a) | jvmUnwrap(b));
    }
    return or0(a, b);
  }

  private static native LongEmul or0(LongEmul a, LongEmul b) /*-{
    return a | b;
  }-*/;

  public static LongEmul xor(LongEmul a, LongEmul b) {
    if (RUN_IN_JVM) {
      return jvmWrap(jvmUnwrap(a) ^ jvmUnwrap(b));
    }
    return xor0(a, b);
  }

  private static native LongEmul xor0(LongEmul a, LongEmul b) /*-{
    return a ^ b;
  }-*/;

  // ---- Shift operations ----
  // Java masks shift count to 0-63 for longs. BigInt shift requires BigInt operand.

  public static LongEmul shl(LongEmul a, int n) {
    if (RUN_IN_JVM) {
      return jvmWrap(jvmUnwrap(a) << (n & 63));
    }
    return shl0(a, n);
  }

  private static native LongEmul shl0(LongEmul a, int n) /*-{
    return BigInt.asIntN(64, a << BigInt(n & 63));
  }-*/;

  public static LongEmul shr(LongEmul a, int n) {
    if (RUN_IN_JVM) {
      return jvmWrap(jvmUnwrap(a) >> (n & 63));
    }
    return shr0(a, n);
  }

  private static native LongEmul shr0(LongEmul a, int n) /*-{
    // Arithmetic right shift; result stays in 64-bit signed range.
    return a >> BigInt(n & 63);
  }-*/;

  public static LongEmul shru(LongEmul a, int n) {
    if (RUN_IN_JVM) {
      return jvmWrap(jvmUnwrap(a) >>> (n & 63));
    }
    return shru0(a, n);
  }

  private static native LongEmul shru0(LongEmul a, int n) /*-{
    // BigInt has no >>> operator. Convert to unsigned, shift, convert back.
    return BigInt.asIntN(64, BigInt.asUintN(64, a) >> BigInt(n & 63));
  }-*/;

  // ---- Comparison operations ----

  public static boolean gt(LongEmul a, LongEmul b) {
    if (RUN_IN_JVM) {
      return jvmUnwrap(a) > jvmUnwrap(b);
    }
    return gt0(a, b);
  }

  private static native boolean gt0(LongEmul a, LongEmul b) /*-{
    return a > b;
  }-*/;

  public static boolean gte(LongEmul a, LongEmul b) {
    if (RUN_IN_JVM) {
      return jvmUnwrap(a) >= jvmUnwrap(b);
    }
    return gte0(a, b);
  }

  private static native boolean gte0(LongEmul a, LongEmul b) /*-{
    return a >= b;
  }-*/;

  public static boolean lt(LongEmul a, LongEmul b) {
    if (RUN_IN_JVM) {
      return jvmUnwrap(a) < jvmUnwrap(b);
    }
    return lt0(a, b);
  }

  private static native boolean lt0(LongEmul a, LongEmul b) /*-{
    return a < b;
  }-*/;

  public static boolean lte(LongEmul a, LongEmul b) {
    if (RUN_IN_JVM) {
      return jvmUnwrap(a) <= jvmUnwrap(b);
    }
    return lte0(a, b);
  }

  private static native boolean lte0(LongEmul a, LongEmul b) /*-{
    return a <= b;
  }-*/;

  public static boolean eq(LongEmul a, LongEmul b) {
    if (RUN_IN_JVM) {
      return jvmUnwrap(a) == jvmUnwrap(b);
    }
    return eq0(a, b);
  }

  private static native boolean eq0(LongEmul a, LongEmul b) /*-{
    return a === b;
  }-*/;

  public static boolean neq(LongEmul a, LongEmul b) {
    if (RUN_IN_JVM) {
      return jvmUnwrap(a) != jvmUnwrap(b);
    }
    return neq0(a, b);
  }

  private static native boolean neq0(LongEmul a, LongEmul b) /*-{
    return a !== b;
  }-*/;

  // ---- Conversion operations ----

  public static LongEmul fromInt(int value) {
    if (RUN_IN_JVM) {
      return jvmWrap(value);
    }
    return fromInt0(value);
  }

  private static native LongEmul fromInt0(int value) /*-{
    return BigInt(value);
  }-*/;

  public static LongEmul fromDouble(double value) {
    if (RUN_IN_JVM) {
      return jvmWrap((long) value);
    }
    return fromDouble0(value);
  }

  private static native LongEmul fromDouble0(double value) /*-{
    // Follow Java semantics for (long) cast:
    // NaN -> 0, too large -> MAX_VALUE, too small -> MIN_VALUE
    if (value !== value) return BigInt(0);                          // NaN
    if (value >= 0x8000000000000000) return BigInt("0x7fffffffffffffff");  // >= 2^63 -> MAX_VALUE
    if (value < -0x8000000000000000) return -BigInt("0x8000000000000000"); // < -2^63 -> MIN_VALUE
    return BigInt(Math.trunc(value));
  }-*/;

  public static int toInt(LongEmul a) {
    if (RUN_IN_JVM) {
      return (int) jvmUnwrap(a);
    }
    return toInt0(a);
  }

  private static native int toInt0(LongEmul a) /*-{
    return Number(BigInt.asIntN(32, a));
  }-*/;

  public static double toDouble(LongEmul a) {
    if (RUN_IN_JVM) {
      return (double) jvmUnwrap(a);
    }
    return toDouble0(a);
  }

  private static native double toDouble0(LongEmul a) /*-{
    return Number(a);
  }-*/;

  public static String toString(LongEmul a) {
    if (RUN_IN_JVM) {
      return Long.toString(jvmUnwrap(a));
    }
    return toString0(a);
  }

  private static native String toString0(LongEmul a) /*-{
    return String(a);
  }-*/;

  // ---- JVM test-mode helpers ----

  private static LongEmul jvmWrap(long value) {
    LongEmul emul = new LongEmul();
    emul.value = value;
    return emul;
  }

  private static long jvmUnwrap(LongEmul emul) {
    return emul.value;
  }

  // ---- Utility methods retained for external callers (e.g. jsinterop InternalJsUtil) ----

  /**
   * Returns whether a double value is in the "safe integer" range, i.e. it can
   * be represented exactly as a JavaScript number. Retained for compatibility
   * with external code that references this method via JSNI.
   */
  static boolean isSafeIntegerRange(double value) {
    return -4398046511104.0 < value && value < 4398046511104.0; // 2^42
  }

  /**
   * Not instantiable.
   */
  private LongLib() {
  }
}
