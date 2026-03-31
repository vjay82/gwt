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
import com.google.gwt.dev.js.ast.JsNameOf;
import com.google.gwt.dev.js.ast.JsNameRef;
import com.google.gwt.dev.js.ast.JsParameter;
import com.google.gwt.dev.js.ast.JsProgram;
import com.google.gwt.dev.js.ast.JsProgramFragment;
import com.google.gwt.dev.js.ast.JsReturn;
import com.google.gwt.dev.js.ast.JsStatement;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gwt.dev.util.collect.IdentityHashSet;

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
 * <p>The pass replaces all unqualified name references and {@link JsNameOf}
 * nodes pointing to the trampoline with references to the target. Qualified
 * references (property accesses) are left untouched because the property name
 * is an independent symbol managed by the polymorphic dispatch system.
 *
 * <p>Trampoline function declarations are <em>not</em> removed by this pass;
 * after reference replacement they become unreferenced and are cleaned up by
 * {@link JsUnusedFunctionRemover} which runs immediately after in the
 * optimization loop. This separation ensures that a trampoline is never removed
 * while a reference to it still exists.
 *
 * <p>Chains of trampolines (f &gt; g &gt; h) are resolved transitively.
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

      // Phase 2: Replace all unqualified name references and JsNameOf nodes.
      // Trampoline declarations are NOT removed here; they become unreferenced
      // and are cleaned up by JsUnusedFunctionRemover.
      int replaced = replaceReferences(trampolineToTarget);

      stats.recordModified(replaced);
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

    // The target function must have the same arity as the trampoline to
    // guarantee call-site compatibility.
    JsFunction targetFunc = (JsFunction) targetName.getStaticRef();
    List<JsParameter> params = func.getParameters();
    if (targetFunc.getParameters().size() != params.size()) {
      return null;
    }

    // Arguments must exactly match parameters in order.
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
   * Replace only JsNameRef nodes that are the direct callee of a
   * {@link JsInvocation} (unqualified) and JsNameOf nodes.
   *
   * <p>Value-position references (vtable assignments like {@code _.m = f},
   * variable initializers, etc.) are deliberately left untouched. The
   * trampoline function stays alive for those uses and continues to work
   * correctly as a thin forwarding wrapper. Only actual call sites
   * ({@code f(a,b)}) benefit from the direct-call optimization.
   *
   * <p>This conservative strategy avoids replacing references at positions
   * where the target function may not be accessible in JavaScript's lexical
   * scope (e.g. when the target is a named function expression that was not
   * hoisted to a standalone declaration).
   */
  private int replaceReferences(final Map<JsName, JsName> trampolineToTarget) {
    class RefReplacer extends JsModVisitor {
      int count = 0;

      /**
       * Tracks JsNameRef nodes that are the callee (qualifier) of a
       * JsInvocation encountered during traversal.
       */
      private final Set<JsNameRef> invocationCallees = new IdentityHashSet<JsNameRef>();

      @Override
      public boolean visit(JsInvocation x, JsContext ctx) {
        JsExpression qualifier = x.getQualifier();
        if (qualifier instanceof JsNameRef) {
          invocationCallees.add((JsNameRef) qualifier);
        }
        return true;
      }

      @Override
      public void endVisit(JsNameRef x, JsContext ctx) {
        // Only replace unqualified references that are direct call targets.
        if (x.getQualifier() != null) {
          return;
        }
        if (!invocationCallees.remove(x)) {
          return;
        }
        JsName name = x.getName();
        if (name != null && trampolineToTarget.containsKey(name)) {
          JsName target = trampolineToTarget.get(name);
          ctx.replaceMe(target.makeRef(x.getSourceInfo()));
          count++;
        }
      }

      @Override
      public void endVisit(JsNameOf x, JsContext ctx) {
        JsName name = x.getName();
        if (name != null && trampolineToTarget.containsKey(name)) {
          JsName target = trampolineToTarget.get(name);
          // JsNameOf.name is final, so we must replace the entire node.
          ctx.replaceMe(new JsNameOf(x.getSourceInfo(), target));
          count++;
        }
      }
    }
    RefReplacer replacer = new RefReplacer();
    replacer.accept(program);
    return replacer.count;
  }

}
