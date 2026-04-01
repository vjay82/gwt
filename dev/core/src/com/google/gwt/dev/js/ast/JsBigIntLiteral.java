/*
 * Copyright 2025 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.dev.js.ast;

import com.google.gwt.dev.jjs.SourceInfo;

/**
 * Represents a JavaScript BigInt literal expression, e.g. {@code 123n}, {@code -456n},
 * {@code 9223372036854775807n}.
 *
 * <p>BigInt literals are used to represent Java {@code long} values in the emitted JavaScript,
 * replacing the previous three-component {@code {l, m, h}} object representation.
 */
public final class JsBigIntLiteral extends JsValueLiteral {

  private final long value;

  public JsBigIntLiteral(SourceInfo sourceInfo, long value) {
    super(sourceInfo);
    this.value = value;
  }

  @Override
  public boolean equals(Object that) {
    if (that == null || this.getClass() != that.getClass()) {
      return false;
    }
    return value == ((JsBigIntLiteral) that).value;
  }

  @Override
  public NodeKind getKind() {
    return NodeKind.BIGINT;
  }

  public long getValue() {
    return value;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(value);
  }

  @Override
  public boolean isBooleanFalse() {
    return value == 0;
  }

  @Override
  public boolean isBooleanTrue() {
    return value != 0;
  }

  @Override
  public boolean isDefinitelyNull() {
    return false;
  }

  @Override
  public void traverse(JsVisitor v, JsContext ctx) {
    v.visit(this, ctx);
    v.endVisit(this, ctx);
  }

  @Override
  public boolean isInternable() {
    return true;
  }
}
