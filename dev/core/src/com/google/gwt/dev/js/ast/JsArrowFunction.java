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

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a JavaScript arrow function expression: {@code (a, b) => a + b} or
 * {@code (a) => { return a; }}.
 *
 * <p>Arrow functions differ from regular functions in that they do not have their own
 * {@code this} binding and cannot be used as constructors.
 *
 * <p>The body can be either:
 * <ul>
 *   <li>A {@link JsBlock} for block-bodied arrows: {@code (a) => { return a; }}</li>
 *   <li>A concise body represented as a single-statement block wrapping a
 *       {@link JsExprStmt} for expression-bodied arrows: {@code (a) => a + 1}</li>
 * </ul>
 */
public final class JsArrowFunction extends JsExpression {

  private JsBlock body;
  private final List<JsParameter> params = new ArrayList<JsParameter>();
  private boolean isAsync;

  public JsArrowFunction(SourceInfo sourceInfo) {
    super(sourceInfo);
  }

  public JsBlock getBody() {
    return body;
  }

  public List<JsParameter> getParameters() {
    return params;
  }

  public boolean isAsync() {
    return isAsync;
  }

  public void setBody(JsBlock body) {
    this.body = body;
  }

  public void setAsync(boolean async) {
    this.isAsync = async;
  }

  /**
   * Returns true if this is a concise-body arrow (expression body, no braces).
   * By convention, a concise body is stored as a block containing a single JsExprStmt.
   */
  public boolean isConciseBody() {
    if (body == null) {
      return false;
    }
    List<JsStatement> stmts = body.getStatements();
    return stmts.size() == 1 && stmts.get(0) instanceof JsExprStmt;
  }

  @Override
  public NodeKind getKind() {
    return NodeKind.ARROW_FUNCTION;
  }

  @Override
  public boolean hasSideEffects() {
    return false;
  }

  @Override
  public boolean isDefinitelyNull() {
    return false;
  }

  @Override
  public void traverse(JsVisitor v, JsContext ctx) {
    if (v.visit(this, ctx)) {
      v.acceptWithInsertRemove(params);
      body = v.accept(body);
    }
    v.endVisit(this, ctx);
  }
}
