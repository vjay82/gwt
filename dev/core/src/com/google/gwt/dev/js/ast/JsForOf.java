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
 * Represents a JavaScript {@code for...of} statement:
 * <pre>for (let x of iterable) { ... }</pre>
 */
public class JsForOf extends JsStatement {

  private JsStatement body;
  private JsExpression iterExpr;
  private final JsName iterVarName;
  private JsExpression iterableExpr;

  public JsForOf(SourceInfo sourceInfo) {
    this(sourceInfo, null);
  }

  public JsForOf(SourceInfo sourceInfo, JsName iterVarName) {
    super(sourceInfo);
    this.iterVarName = iterVarName;
  }

  public JsStatement getBody() {
    return body;
  }

  public JsExpression getIterExpr() {
    return iterExpr;
  }

  public JsName getIterVarName() {
    return iterVarName;
  }

  public JsExpression getIterableExpr() {
    return iterableExpr;
  }

  public void setBody(JsStatement body) {
    this.body = body;
  }

  public void setIterExpr(JsExpression iterExpr) {
    this.iterExpr = iterExpr;
  }

  public void setIterableExpr(JsExpression iterableExpr) {
    this.iterableExpr = iterableExpr;
  }

  @Override
  public NodeKind getKind() {
    return NodeKind.FOR_OF;
  }

  @Override
  public void traverse(JsVisitor v, JsContext ctx) {
    if (v.visit(this, ctx)) {
      if (iterExpr != null) {
        iterExpr = v.acceptLvalue(iterExpr);
      }
      iterableExpr = v.accept(iterableExpr);
      body = v.accept(body);
    }
    v.endVisit(this, ctx);
  }
}
