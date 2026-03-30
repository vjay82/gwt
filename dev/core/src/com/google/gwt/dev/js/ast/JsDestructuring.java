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
 * Represents a JavaScript destructuring pattern. Covers both array and object destructuring:
 * <pre>
 *   let [a, b, ...rest] = arr;
 *   let {x, y: alias, ...rest} = obj;
 * </pre>
 *
 * <p>A destructuring pattern is used as an assignment target (lvalue). It contains a list of
 * individual elements or properties that are destructured from a source expression.
 */
public final class JsDestructuring extends JsExpression {

  /**
   * The kind of destructuring pattern.
   */
  public enum Kind {
    ARRAY,
    OBJECT
  }

  /**
   * Represents one element in a destructuring pattern.
   * For array destructuring: the element itself (possibly with a default value).
   * For object destructuring: a property key and a target (possibly with a default value).
   */
  public static class JsDestructuringElement extends JsNode {
    private JsExpression key;       // For object destructuring: property key; null for array
    private JsExpression target;    // The variable name or nested pattern
    private JsExpression defaultValue;
    private boolean isRest;         // True if this is a rest element: ...x

    public JsDestructuringElement(SourceInfo sourceInfo) {
      super(sourceInfo);
    }

    public JsExpression getKey() {
      return key;
    }

    public void setKey(JsExpression key) {
      this.key = key;
    }

    public JsExpression getTarget() {
      return target;
    }

    public void setTarget(JsExpression target) {
      this.target = target;
    }

    public JsExpression getDefaultValue() {
      return defaultValue;
    }

    public void setDefaultValue(JsExpression defaultValue) {
      this.defaultValue = defaultValue;
    }

    public boolean isRest() {
      return isRest;
    }

    public void setRest(boolean rest) {
      this.isRest = rest;
    }

    @Override
    public NodeKind getKind() {
      return NodeKind.DESTRUCTURING_ELEMENT;
    }

    @Override
    public void traverse(JsVisitor v, JsContext ctx) {
      if (v.visit(this, ctx)) {
        if (key != null) {
          key = v.accept(key);
        }
        if (target != null) {
          target = v.accept(target);
        }
        if (defaultValue != null) {
          defaultValue = v.accept(defaultValue);
        }
      }
      v.endVisit(this, ctx);
    }
  }

  private final Kind kind;
  private final List<JsDestructuringElement> elements = new ArrayList<JsDestructuringElement>();

  public JsDestructuring(SourceInfo sourceInfo, Kind kind) {
    super(sourceInfo);
    this.kind = kind;
  }

  public Kind getDestructuringKind() {
    return kind;
  }

  public List<JsDestructuringElement> getElements() {
    return elements;
  }

  @Override
  public NodeKind getKind() {
    return NodeKind.DESTRUCTURING;
  }

  @Override
  public boolean hasSideEffects() {
    return true; // destructuring is always an assignment target
  }

  @Override
  public boolean isDefinitelyNull() {
    return false;
  }

  @Override
  public void traverse(JsVisitor v, JsContext ctx) {
    if (v.visit(this, ctx)) {
      v.acceptWithInsertRemove(elements);
    }
    v.endVisit(this, ctx);
  }
}
