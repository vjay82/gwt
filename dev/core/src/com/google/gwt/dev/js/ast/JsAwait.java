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
 * Represents a JavaScript {@code await} expression:
 * <pre>
 *   async function foo() {
 *     let result = await fetchData();
 *   }
 * </pre>
 */
public final class JsAwait extends JsExpression {

  private JsExpression expression;

  public JsAwait(SourceInfo sourceInfo, JsExpression expression) {
    super(sourceInfo);
    this.expression = expression;
  }

  public JsExpression getExpression() {
    return expression;
  }

  public void setExpression(JsExpression expression) {
    this.expression = expression;
  }

  @Override
  public NodeKind getKind() {
    return NodeKind.AWAIT;
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
      expression = v.accept(expression);
    }
    v.endVisit(this, ctx);
  }
}
