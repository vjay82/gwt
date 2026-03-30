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
 * Represents a JavaScript class declaration or expression:
 * <pre>
 *   class Foo extends Bar {
 *     constructor(x) { super(x); this.x = x; }
 *     method() { return this.x; }
 *     get prop() { return this._prop; }
 *     static factory() { return new Foo(1); }
 *   }
 * </pre>
 */
public final class JsClass extends JsStatement {

  /**
   * Represents a single member of a class (method, getter, setter, or field).
   */
  public static class JsClassMember extends JsNode {
    private JsExpression nameExpr;
    private JsExpression valueExpr;
    private boolean isStatic;
    private boolean isGetter;
    private boolean isSetter;
    private boolean isComputed;

    public JsClassMember(SourceInfo sourceInfo) {
      super(sourceInfo);
    }

    public JsExpression getNameExpr() {
      return nameExpr;
    }

    public void setNameExpr(JsExpression nameExpr) {
      this.nameExpr = nameExpr;
    }

    public JsExpression getValueExpr() {
      return valueExpr;
    }

    public void setValueExpr(JsExpression valueExpr) {
      this.valueExpr = valueExpr;
    }

    public boolean isStatic() {
      return isStatic;
    }

    public void setStatic(boolean isStatic) {
      this.isStatic = isStatic;
    }

    public boolean isGetter() {
      return isGetter;
    }

    public void setGetter(boolean getter) {
      this.isGetter = getter;
    }

    public boolean isSetter() {
      return isSetter;
    }

    public void setSetter(boolean setter) {
      this.isSetter = setter;
    }

    public boolean isComputed() {
      return isComputed;
    }

    public void setComputed(boolean computed) {
      this.isComputed = computed;
    }

    @Override
    public NodeKind getKind() {
      return NodeKind.CLASS_MEMBER;
    }

    @Override
    public void traverse(JsVisitor v, JsContext ctx) {
      if (v.visit(this, ctx)) {
        nameExpr = v.accept(nameExpr);
        valueExpr = v.accept(valueExpr);
      }
      v.endVisit(this, ctx);
    }
  }

  private JsName name;
  private JsExpression superExpr;
  private JsFunction constructor;
  private final List<JsClassMember> members = new ArrayList<JsClassMember>();

  public JsClass(SourceInfo sourceInfo) {
    super(sourceInfo);
  }

  public JsName getName() {
    return name;
  }

  public void setName(JsName name) {
    this.name = name;
  }

  public JsExpression getSuperExpr() {
    return superExpr;
  }

  public void setSuperExpr(JsExpression superExpr) {
    this.superExpr = superExpr;
  }

  public JsFunction getConstructor() {
    return constructor;
  }

  public void setConstructor(JsFunction constructor) {
    this.constructor = constructor;
  }

  public List<JsClassMember> getMembers() {
    return members;
  }

  @Override
  public NodeKind getKind() {
    return NodeKind.CLASS;
  }

  @Override
  public void traverse(JsVisitor v, JsContext ctx) {
    if (v.visit(this, ctx)) {
      if (superExpr != null) {
        superExpr = v.accept(superExpr);
      }
      if (constructor != null) {
        constructor = (JsFunction) v.accept(constructor);
      }
      v.acceptWithInsertRemove(members);
    }
    v.endVisit(this, ctx);
  }
}
