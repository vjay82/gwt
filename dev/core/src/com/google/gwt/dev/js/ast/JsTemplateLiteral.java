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
 * Represents a JavaScript template literal: <code>`Hello ${name}, you are ${age} years old`</code>.
 *
 * <p>Template literals are modeled as a sequence of alternating string parts and expression parts.
 * For example, <code>`a${b}c${d}e`</code> has string parts ["a", "c", "e"] and
 * expression parts [b, d]. There is always one more string part than expression parts.
 *
 * <p>If a tag expression is present, this represents a tagged template literal:
 * <code>tag`Hello ${name}`</code>.
 */
public final class JsTemplateLiteral extends JsExpression {

  private JsExpression tag;
  private final List<String> stringParts = new ArrayList<String>();
  private final List<JsExpression> expressionParts = new ArrayList<JsExpression>();

  public JsTemplateLiteral(SourceInfo sourceInfo) {
    super(sourceInfo);
  }

  public JsExpression getTag() {
    return tag;
  }

  public void setTag(JsExpression tag) {
    this.tag = tag;
  }

  public List<String> getStringParts() {
    return stringParts;
  }

  public List<JsExpression> getExpressionParts() {
    return expressionParts;
  }

  @Override
  public NodeKind getKind() {
    return NodeKind.TEMPLATE_LITERAL;
  }

  @Override
  public boolean hasSideEffects() {
    if (tag != null && tag.hasSideEffects()) {
      return true;
    }
    for (JsExpression expr : expressionParts) {
      if (expr.hasSideEffects()) {
        return true;
      }
    }
    return tag != null; // tagged templates always call the tag function
  }

  @Override
  public boolean isDefinitelyNull() {
    return false;
  }

  @Override
  public void traverse(JsVisitor v, JsContext ctx) {
    if (v.visit(this, ctx)) {
      if (tag != null) {
        tag = v.accept(tag);
      }
      v.acceptWithInsertRemove(expressionParts);
    }
    v.endVisit(this, ctx);
  }
}
