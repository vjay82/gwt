/*
 * Copyright 2024 Google Inc.
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
import com.google.gwt.dev.js.ast.JsBinaryOperation;
import com.google.gwt.dev.js.ast.JsBinaryOperator;
import com.google.gwt.dev.js.ast.JsBlock;
import com.google.gwt.dev.js.ast.JsContext;
import com.google.gwt.dev.js.ast.JsEmpty;
import com.google.gwt.dev.js.ast.JsExprStmt;
import com.google.gwt.dev.js.ast.JsExpression;
import com.google.gwt.dev.js.ast.JsFunction;
import com.google.gwt.dev.js.ast.JsInvocation;
import com.google.gwt.dev.js.ast.JsModVisitor;
import com.google.gwt.dev.js.ast.JsNullLiteral;
import com.google.gwt.dev.js.ast.JsProgram;
import com.google.gwt.dev.js.ast.JsStatement;
import com.google.gwt.dev.js.ast.JsVisitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hoists {@code java.*} class initializer ($clinit) code to fragment/module initialization scope,
 * eliminating the per-access self-replacing clinit pattern entirely for JRE emulation classes.
 *
 * <h3>Background</h3>
 * <p>Every static field or method access in GWT emits a clinit guard:
 * <pre>
 *   (SomeClass.$clinit(), SomeClass.staticField)
 * </pre>
 * Each clinit self-replaces with {@code Runtime.emptyMethod()} on first call, but the call site
 * still dispatches through a function reference on every subsequent access.
 *
 * <h3>Algorithm</h3>
 * <p>For eligible clinits ({@code java.*} packages only):
 * <ol>
 *   <li><b>Collect</b> all {@code java.*} clinit function declarations in each fragment.</li>
 *   <li><b>Dependency analysis:</b> Walk each clinit body to find invocations of other eligible
 *       clinits. Also follow the {@link JsFunction#getSuperClinit()} chain.</li>
 *   <li><b>Topological sort:</b> Order clinits so dependencies are initialized first (Kahn's
 *       algorithm). Cycles are broken arbitrarily.</li>
 *   <li><b>Inline at fragment scope:</b> For each clinit in sorted order, copy its body statements
 *       (excluding the {@code $clinit = emptyMethod} self-replace) directly into the fragment
 *       init scope.</li>
 *   <li><b>Gut the clinit function:</b> Replace the original clinit body with an empty body.
 *       Any call sites that the global removal pass might miss will call a harmless noop.</li>
 *   <li><b>Strip all call sites:</b> Remove every invocation of these clinits from the entire
 *       fragment — in comma expressions, standalone statements, and anywhere else they appear.
 *       No per-access dispatch overhead remains.</li>
 * </ol>
 *
 * <p>Non-{@code java.*} clinits are left completely untouched.
 *
 * <p>For code-split applications ({@code GWT.runAsync}), each fragment is processed independently.
 *
 * <p>This pass runs after code splitting and before {@link DuplicateClinitRemover}.
 */
public class ClinitHoister {

  private static final String NAME = ClinitHoister.class.getSimpleName();

  /**
   * Hoists clinit calls for all fragments in the program.
   *
   * @return the number of modifications made
   */
  public static int exec(JsProgram jsProgram) {
    try (OptimizerStats stats = OptimizerStats.optimization(NAME)) {
      int mods = new ClinitHoister(jsProgram).execImpl();
      stats.recordModified(mods);
      return mods;
    }
  }

  private final JsProgram jsProgram;

  private ClinitHoister(JsProgram jsProgram) {
    this.jsProgram = jsProgram;
  }

  private int execImpl() {
    int totalMods = 0;

    for (int i = 0; i < jsProgram.getFragmentCount(); i++) {
      JsBlock fragmentBlock = jsProgram.getFragmentBlock(i);
      List<JsStatement> statements = fragmentBlock.getStatements();

      // Collect java.* clinit functions declared in this fragment.
      Set<JsFunction> clinitFunctions = collectClinitFunctions(statements);
      if (clinitFunctions.isEmpty()) {
        continue;
      }

      // Phase 1: Build dependency graph.
      Map<JsFunction, Set<JsFunction>> clinitDeps = new LinkedHashMap<>();
      for (JsFunction clinit : clinitFunctions) {
        Set<JsFunction> deps = new LinkedHashSet<>();

        JsFunction superClinit = clinit.getSuperClinit();
        if (superClinit != null && clinitFunctions.contains(superClinit)) {
          deps.add(superClinit);
        }

        findDirectClinitCalls(clinit, clinitFunctions, deps);
        clinitDeps.put(clinit, deps);
      }

      // Phase 2: Topological sort — dependencies before dependents.
      List<JsFunction> sortedClinits = topologicalSort(clinitFunctions, clinitDeps);

      // Phase 3: Inline clinit body statements at fragment init scope (minus self-replace).
      int insertionPoint = findInsertionPoint(statements, i);
      for (JsFunction clinit : sortedClinits) {
        List<JsStatement> bodyStmts = clinit.getBody().getStatements();
        for (JsStatement stmt : bodyStmts) {
          if (isSelfReplaceStatement(stmt, clinit)) {
            continue; // Skip "$clinit_X = emptyMethod"
          }
          statements.add(insertionPoint++, stmt);
          totalMods++;
        }
      }

      // Phase 4: Strip ALL per-access calls to these clinits from the entire fragment.
      // Must run before Phase 5 (unmark) because isExecuteOnce() checks the clinit flag.
      AllClinitCallRemover remover = new AllClinitCallRemover(clinitFunctions);
      remover.accept(fragmentBlock);
      totalMods += remover.getNumMods();

      // Phase 5: Gut the original clinit function bodies and unmark as clinit.
      // The body is emptied since its code was inlined above. The clinit flag is cleared so
      // JsUnusedFunctionRemover can safely remove the now-unreferenced empty function.
      for (JsFunction clinit : clinitFunctions) {
        clinit.getBody().getStatements().clear();
        clinit.unmarkAsClinit();
        totalMods++;
      }
    }

    return totalMods;
  }

  // ---------------------------------------------------------------------------
  // Dependency analysis
  // ---------------------------------------------------------------------------

  /**
   * Walks the entire clinit function body AST to find all invocations of other clinit functions.
   * This captures clinit-to-clinit dependencies regardless of where they appear — standalone
   * statements, comma expressions inside field assignments, constructor arguments, etc.
   *
   * <p>The scope of this scan must match what {@link ClinitBodyGuardRemover} removes: every
   * clinit call that the remover would strip must appear as a dependency edge here, so that
   * the topological sort guarantees the callee ran before the caller.
   */
  private static void findDirectClinitCalls(JsFunction clinitFunction,
      Set<JsFunction> allClinits, Set<JsFunction> deps) {
    new JsVisitor() {
      @Override
      public boolean visit(JsInvocation x, JsContext ctx) {
        JsFunction callee = JsUtils.isExecuteOnce(x);
        if (callee != null && allClinits.contains(callee)) {
          deps.add(callee);
        }
        return true;
      }
    }.accept(clinitFunction.getBody());
  }

  // ---------------------------------------------------------------------------
  // Topological sort
  // ---------------------------------------------------------------------------

  /**
   * Topological sort of clinits: dependencies before dependents (Kahn's algorithm).
   * Cycles are broken by appending remaining clinits in declaration order — the self-replace
   * guard ({@code $clinit = emptyMethod} as first statement) ensures safety for circular
   * initialization, consistent with JVM spec behavior.
   */
  private static List<JsFunction> topologicalSort(
      Set<JsFunction> clinits, Map<JsFunction, Set<JsFunction>> dependencies) {

    // Build in-degree map and reverse adjacency (dependency → list of dependents).
    Map<JsFunction, Integer> inDegree = new LinkedHashMap<>();
    Map<JsFunction, List<JsFunction>> dependents = new LinkedHashMap<>();

    for (JsFunction c : clinits) {
      inDegree.put(c, 0);
      dependents.put(c, new ArrayList<>());
    }

    for (JsFunction c : clinits) {
      for (JsFunction dep : dependencies.getOrDefault(c, Collections.emptySet())) {
        if (clinits.contains(dep)) {
          inDegree.merge(c, 1, Integer::sum);
          dependents.get(dep).add(c);
        }
      }
    }

    // Kahn's algorithm: repeatedly emit clinits with zero in-degree.
    Deque<JsFunction> ready = new ArrayDeque<>();
    for (JsFunction c : clinits) {
      if (inDegree.get(c) == 0) {
        ready.add(c);
      }
    }

    List<JsFunction> result = new ArrayList<>();
    Set<JsFunction> scheduled = new LinkedHashSet<>();

    while (!ready.isEmpty()) {
      JsFunction c = ready.poll();
      result.add(c);
      scheduled.add(c);
      for (JsFunction dependent : dependents.get(c)) {
        int newDeg = inDegree.merge(dependent, -1, Integer::sum);
        if (newDeg == 0) {
          ready.add(dependent);
        }
      }
    }

    // Cycle remnants: append in original declaration order.
    for (JsFunction c : clinits) {
      if (!scheduled.contains(c)) {
        result.add(c);
      }
    }

    return result;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Collects clinit functions declared as top-level statements in the fragment that are eligible
   * for hoisting. Currently restricted to {@code java.*} packages to minimize risk — these are
   * well-understood JRE emulation classes with simple, predictable clinit dependency chains.
   */
  private static Set<JsFunction> collectClinitFunctions(List<JsStatement> statements) {
    Set<JsFunction> clinits = new LinkedHashSet<>();
    for (JsStatement stmt : statements) {
      JsFunction func = JsUtils.isFunctionDeclaration(stmt);
      if (func != null && func.isClinit() && isJavaPackageClinit(func)) {
        clinits.add(func);
      }
    }
    return clinits;
  }

  /**
   * Returns true if the clinit function belongs to a {@code java.*} package.
   * Clinit names follow the pattern {@code java_util_Collections_$clinit__V}.
   */
  private static boolean isJavaPackageClinit(JsFunction clinit) {
    if (clinit.getName() == null) {
      return false;
    }
    return clinit.getName().getIdent().startsWith("java_");
  }

  /**
   * Determines where to insert hoisted clinit code in the fragment.
   *
   * <p>For the base fragment (index 0): at the end of compiled output, before linker bootstrap.
   * <p>For deferred fragments: before the {@code AsyncFragmentLoader.onLoad()} call (last stmt).
   */
  private static int findInsertionPoint(List<JsStatement> statements, int fragmentIndex) {
    if (fragmentIndex > 0 && !statements.isEmpty()) {
      return statements.size() - 1;
    }
    return statements.size();
  }

  /**
   * Returns true if the statement is the self-replace assignment {@code $clinit_X = emptyMethod}.
   * This is always the first statement in a clinit body and is skipped during inlining — the
   * clinit function is gutted separately (Phase 4).
   */
  private static boolean isSelfReplaceStatement(JsStatement stmt, JsFunction clinit) {
    if (!(stmt instanceof JsExprStmt)) {
      return false;
    }
    JsExpression expr = ((JsExprStmt) stmt).getExpression();
    if (!(expr instanceof JsBinaryOperation)) {
      return false;
    }
    JsBinaryOperation binOp = (JsBinaryOperation) expr;
    if (binOp.getOperator() != JsBinaryOperator.ASG) {
      return false;
    }
    // Check if the LHS is a reference to this clinit's own name.
    JsFunction lhsFunc = JsUtils.isFunction(binOp.getArg1());
    return lhsFunc == clinit;
  }

  // ---------------------------------------------------------------------------
  // Global clinit call removal
  // ---------------------------------------------------------------------------

  /**
   * Removes ALL invocations of the specified clinit functions from the entire fragment.
   *
   * <p>Since the clinit code has been inlined at fragment init scope and the clinit functions
   * gutted to empty bodies, every call to them is dead code. This removes them from comma
   * expressions and standalone statements everywhere — not just clinit bodies.
   */
  private static class AllClinitCallRemover extends JsModVisitor {
    private final Set<JsFunction> removedClinits;

    AllClinitCallRemover(Set<JsFunction> removedClinits) {
      this.removedClinits = removedClinits;
    }

    @Override
    public boolean visit(JsBinaryOperation x, JsContext ctx) {
      if (x.getOperator() != JsBinaryOperator.COMMA) {
        return true;
      }

      boolean leftIsClinit = isRemovedClinitCall(x.getArg1());
      boolean rightIsClinit = isRemovedClinitCall(x.getArg2());

      if (leftIsClinit && rightIsClinit) {
        if (ctx.canRemove()) {
          ctx.removeMe();
        } else {
          ctx.replaceMe(JsNullLiteral.INSTANCE);
        }
        return false;
      } else if (leftIsClinit) {
        ctx.replaceMe(accept(x.getArg2()));
        return false;
      } else if (rightIsClinit) {
        ctx.replaceMe(accept(x.getArg1()));
        return false;
      }
      return true;
    }

    @Override
    public boolean visit(JsExprStmt x, JsContext ctx) {
      if (isRemovedClinitCall(x.getExpression())) {
        if (ctx.canRemove()) {
          ctx.removeMe();
        } else {
          ctx.replaceMe(new JsEmpty(x.getSourceInfo()));
        }
        return false;
      }
      return true;
    }

    private boolean isRemovedClinitCall(JsExpression x) {
      if (!(x instanceof JsInvocation)) {
        return false;
      }
      JsFunction func = JsUtils.isExecuteOnce((JsInvocation) x);
      return func != null && removedClinits.contains(func);
    }
  }
}
