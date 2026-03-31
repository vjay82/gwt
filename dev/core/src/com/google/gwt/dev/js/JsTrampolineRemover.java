/*
 * Copyright 2026 Google Inc.
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
package com.google.gwt.dev.js;

import com.google.gwt.dev.jjs.impl.OptimizerStats;
import com.google.gwt.dev.js.ast.JsContext;
import com.google.gwt.dev.js.ast.JsExprStmt;
import com.google.gwt.dev.js.ast.JsExpression;
import com.google.gwt.dev.js.ast.JsFunction;
import com.google.gwt.dev.js.ast.JsInvocation;
import com.google.gwt.dev.js.ast.JsModVisitor;
import com.google.gwt.dev.js.ast.JsName;
import com.google.gwt.dev.js.ast.JsNameRef;
import com.google.gwt.dev.js.ast.JsParameter;
import com.google.gwt.dev.js.ast.JsProgram;
import com.google.gwt.dev.js.ast.JsProgramFragment;
import com.google.gwt.dev.js.ast.JsReturn;
import com.google.gwt.dev.js.ast.JsStatement;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Eliminates trampoline functions whose entire body simply delegates to another
 * named function with the same arguments passed through in order.
 *
 * <p>A trampoline is a top-level function declaration of the form:
 * <pre>
 *   function f(a, b, c) { return g(a, b, c); }
 *   // or void variant:
 *   function f(a, b) { g(a, b); }
 * </pre>
 *
 * <p>The pass replaces all references to the trampoline name with references to
 * the target name, then removes the now-dead trampoline declarations. Chains
 * of trampolines (f -&gt; g -&gt; h) are resolved transitively.
 *
 * <p>Clinit functions and functions marked as DO_NOT_INLINE are never treated
 * as trampolines.
 */
public class JsTrampolineRemover {

  public static final String NAME = JsTrampolineRemover.class.getSimpleName();

  public static int exec(JsProgram program) {
    return new JsTrampolineRemover(program).execImpl();
  }

  private final JsProgram program;

  private JsTrampolineRemover(JsProgram program) {
    this.program = program;
  }

  private int execImpl() {
    try (OptimizerStats stats = OptimizerStats.optimization(NAME)) {

      // Phase 1: Identify trampolines.
      Map<JsName, JsName> trampolineToTarget = new IdentityHashMap<JsName, JsName>();
      for (JsProgramFragment fragment : program.getFragments()) {
        for (JsStatement stmt : fragment.getGlobalBlock().getStatements()) {
          identifyTrampoline(stmt, trampolineToTarget);
        }
      }

      if (trampolineToTarget.isEmpty()) {
        return 0;
      }

      // Phase 1b: Resolve transitive chains (f -> g -> h => f -> h).
      resolveChains(trampolineToTarget);

      // Phase 2: Replace all name references from trampoline to target.
      int replaced = replaceReferences(trampolineToTarget);

      // Phase 3: Remove dead trampoline declarations.
      int removed = removeTrampolineDeclarations(trampolineToTarget);

      stats.recordModified(replaced + removed);
      return stats.getNumMods();
    }
  }

  private void identifyTrampoline(JsStatement stmt, Map<JsName, JsName> trampolineToTarget) {
    JsFunction func = JsUtils.isFunctionDeclaration(stmt);
    if (func == null) {
      return;
    }

    // Don't touch clinits or DO_NOT_INLINE functions.
    if (func.isClinit() || !func.isInliningAllowed()) {
      return;
    }

    JsName funcName = func.getName();
    if (funcName == null) {
      return;
    }

    JsName targetName = getTrampolineTarget(func);
    if (targetName == null || targetName == funcName) {
      return;
    }

    trampolineToTarget.put(funcName, targetName);
  }

  /**
   * Check if a function is a trampoline and return the target's JsName, or null.
   *
   * <p>Matches:
   * <ul>
   *   <li>{@code function f(a,b) { return g(a,b); }}</li>
   *   <li>{@code function f(a,b) { g(a,b); }} (void)</li>
   * </ul>
   *
   * <p>The call must be unqualified, to a named function, with all parameters
   * forwarded in the same order and no extras.
   */
  private JsName getTrampolineTarget(JsFunction func) {
    List<JsStatement> stmts = func.getBody().getStatements();
    if (stmts.size() != 1) {
      return null;
    }

    JsStatement only = stmts.get(0);
    JsInvocation invocation;

    if (only instanceof JsReturn) {
      JsExpression returnExpr = ((JsReturn) only).getExpr();
      if (!(returnExpr instanceof JsInvocation)) {
        return null;
      }
      invocation = (JsInvocation) returnExpr;
    } else if (only instanceof JsExprStmt) {
      JsExpression expr = ((JsExprStmt) only).getExpression();
      if (!(expr instanceof JsInvocation)) {
        return null;
      }
      invocation = (JsInvocation) expr;
    } else {
      return null;
    }

    // The call target must be a simple unqualified name reference.
    JsExpression qualifier = invocation.getQualifier();
    if (!(qualifier instanceof JsNameRef)) {
      return null;
    }
    JsNameRef targetRef = (JsNameRef) qualifier;
    if (targetRef.getQualifier() != null) {
      return null;
    }
    JsName targetName = targetRef.getName();
    if (targetName == null) {
      return null;
    }

    // The target must resolve to an actual function.
    if (!(targetName.getStaticRef() instanceof JsFunction)) {
      return null;
    }

    // Arguments must exactly match parameters in order.
    List<JsParameter> params = func.getParameters();
    List<JsExpression> args = invocation.getArguments();
    if (params.size() != args.size()) {
      return null;
    }
    for (int i = 0; i < params.size(); i++) {
      JsExpression arg = args.get(i);
      if (!(arg instanceof JsNameRef)) {
        return null;
      }
      JsNameRef argRef = (JsNameRef) arg;
      if (argRef.getQualifier() != null) {
        return null;
      }
      if (argRef.getName() != params.get(i).getName()) {
        return null;
      }
    }

    return targetName;
  }

  /**
   * Resolve transitive chains: if f -&gt; g and g -&gt; h, make f -&gt; h.
   */
  private void resolveChains(Map<JsName, JsName> trampolineToTarget) {
    for (Map.Entry<JsName, JsName> entry : trampolineToTarget.entrySet()) {
      JsName target = entry.getValue();
      int depth = 0;
      while (trampolineToTarget.containsKey(target) && depth < trampolineToTarget.size()) {
        target = trampolineToTarget.get(target);
        depth++;
      }
      entry.setValue(target);
    }
  }

  /**
   * Replace all JsNameRef nodes pointing to a trampoline with a reference to
   * the ultimate target.
   */
  private int replaceReferences(final Map<JsName, JsName> trampolineToTarget) {
    class RefReplacer extends JsModVisitor {
      int count = 0;

      @Override
      public void endVisit(JsNameRef x, JsContext ctx) {
        JsName name = x.getName();
        if (name != null && trampolineToTarget.containsKey(name)) {
          JsName target = trampolineToTarget.get(name);
          JsNameRef replacement = target.makeRef(x.getSourceInfo());
          if (x.getQualifier() != null) {
            replacement.setQualifier(x.getQualifier());
          }
          ctx.replaceMe(replacement);
          count++;
        }
      }
    }
    RefReplacer replacer = new RefReplacer();
    replacer.accept(program);
    return replacer.count;
  }

  /**
   * Remove top-level trampoline function declarations.
   */
  private int removeTrampolineDeclarations(Map<JsName, JsName> trampolineToTarget) {
    int removed = 0;
    for (JsProgramFragment fragment : program.getFragments()) {
      Iterator<JsStatement> it = fragment.getGlobalBlock().getStatements().iterator();
      while (it.hasNext()) {
        JsStatement stmt = it.next();
        JsFunction func = JsUtils.isFunctionDeclaration(stmt);
        if (func != null && func.getName() != null
            && trampolineToTarget.containsKey(func.getName())) {
          it.remove();
          removed++;
        }
      }
    }
    return removed;
  }
}
