/*
 * Copyright 2024 Google Inc.
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
 * Represents a JavaScript {@code yield} expression used in generator functions:
 * <pre>
 *   function* gen() {
 *     yield 1;
 *     let x = yield 2;
 *   }
 * </pre>
 *
 * <p>If {@code isDelegating} is true, this represents {@code yield* expr}, which
 * delegates to another iterable.
 */
public final class JsYield extends JsExpression {

  private JsExpression expression;
  private boolean isDelegating;

  public JsYield(SourceInfo sourceInfo) {
    super(sourceInfo);
  }

  public JsYield(SourceInfo sourceInfo, JsExpression expression) {
    super(sourceInfo);
    this.expression = expression;
  }

  public JsExpression getExpression() {
    return expression;
  }

  public void setExpression(JsExpression expression) {
    this.expression = expression;
  }

  public boolean isDelegating() {
    return isDelegating;
  }

  public void setDelegating(boolean delegating) {
    this.isDelegating = delegating;
  }

  @Override
  public NodeKind getKind() {
    return NodeKind.YIELD;
  }

  @Override
  public boolean hasSideEffects() {
    return true;
  }

  @Override
  public boolean isDefinitelyNull() {
    return false;
  }

  @Override
  public void traverse(JsVisitor v, JsContext ctx) {
    if (v.visit(this, ctx)) {
      if (expression != null) {
        expression = v.accept(expression);
      }
    }
    v.endVisit(this, ctx);
  }
}
