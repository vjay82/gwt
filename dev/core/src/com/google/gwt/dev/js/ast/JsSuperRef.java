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
 * Represents a {@code super} expression used in class constructors and methods:
 * <pre>
 *   class Foo extends Bar {
 *     constructor() { super(); }
 *     method() { super.method(); }
 *   }
 * </pre>
 */
public final class JsSuperRef extends JsExpression {

  public JsSuperRef(SourceInfo sourceInfo) {
    super(sourceInfo);
  }

  @Override
  public NodeKind getKind() {
    return NodeKind.SUPER;
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
  public boolean isLeaf() {
    return true;
  }

  @Override
  public void traverse(JsVisitor v, JsContext ctx) {
    if (v.visit(this, ctx)) {
    }
    v.endVisit(this, ctx);
  }
}
