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

import com.google.gwt.dev.jjs.SourceInfo;
import com.google.gwt.dev.jjs.SourceOrigin;
import com.google.gwt.dev.jjs.impl.OptimizerStats;
import com.google.gwt.dev.js.ast.JsArrayLiteral;
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
import com.google.gwt.dev.js.ast.JsName;
import com.google.gwt.dev.js.ast.JsNameRef;
import com.google.gwt.dev.js.ast.JsNullLiteral;
import com.google.gwt.dev.js.ast.JsProgram;
import com.google.gwt.dev.js.ast.JsStatement;
import com.google.gwt.dev.js.ast.JsStringLiteral;
import com.google.gwt.dev.js.ast.JsVars;
import com.google.gwt.dev.js.ast.JsVisitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two-mode clinit hoisting optimization: recording in development, inlining in production.
 *
 * <h3>DRAFT mode (recording)</h3>
 * <p>When the compiler runs in DRAFT optimization level, this pass instruments every
 * {@code $clinit} function to record when it is first called into a global ordered list. A 60-second
 * timer prints the list to {@code console.log} whenever it changes. The output is a comma-separated
 * list of clinit identifiers that reflects the actual runtime initialization order:
 * <pre>
 *   [GWT clinit order] java_util_HashMap_$clinit__V,java_util_Collections_$clinit__V,...
 * </pre>
 *
 * <h3>Production mode (inlining)</h3>
 * <p>When the compiler runs in any optimization level above DRAFT and the environment variable
 * {@code GWT_CLINIT_ORDER} is set to a comma-separated list of clinit identifiers (as captured
 * from DRAFT mode output), this pass:
 * <ol>
 *   <li>Inlines each listed clinit's body statements at fragment initialization scope (in the
 *       order specified by the list --- which reflects the runtime dependency order).</li>
 *   <li>Strips all per-access invocations of those clinits from the entire fragment.</li>
 *   <li>Guts the original clinit functions and unmarks them so
 *       {@link JsUnusedFunctionRemover} can clean them up.</li>
 * </ol>
 *
 * <p>Clinits NOT in the list retain their original self-replacing behavior unchanged.
 *
 * <p>For code-split applications, each fragment is processed independently --- only clinits
 * declared in a given fragment are hoisted there.
 *
 * <p>This pass runs after code splitting and before {@link DuplicateClinitRemover}.
 */
public class ClinitHoister {

  private static final String NAME = ClinitHoister.class.getSimpleName();

  /** Environment variable containing the comma-separated clinit order for production hoisting. */
  private static final String CLINIT_ORDER_ENV = "GWT_CLINIT_ORDER";

  /** Suffix stripped from clinit idents to produce shorter, more readable names. */
  private static final String CLINIT_SUFFIX = "_$clinit__V";

  /**
   * Executes clinit hoisting.
   *
   * @param jsProgram the JS program to process
   * @param isDraft true when running in DRAFT optimization level (recording mode)
   * @return the number of modifications made
   */
  public static int exec(JsProgram jsProgram, boolean isDraft) {
    try (OptimizerStats stats = OptimizerStats.optimization(NAME)) {
      int mods = new ClinitHoister(jsProgram).execImpl(isDraft);
      stats.recordModified(mods);
      return mods;
    }
  }

  private final JsProgram jsProgram;

  private ClinitHoister(JsProgram jsProgram) {
    this.jsProgram = jsProgram;
  }

  private int execImpl(boolean isDraft) {
    if (isDraft) {
      return execDraftRecording();
    }
    String clinitOrder = System.getenv(CLINIT_ORDER_ENV);
    if (clinitOrder == null || clinitOrder.trim().isEmpty()) {
      return 0;
    }
    return execProductionHoisting(clinitOrder);
  }

  // ===========================================================================
  // DRAFT mode: record clinit call order at runtime
  // ===========================================================================

  /**
   * Instruments all clinit functions to record their first-call order into a global array,
   * and installs a 60-second timer that prints the ordered list when it changes.
   *
   * <p>Generated JS (conceptually):
   * <pre>
   *   var GWT_CLINIT_ORDER = [];
   *   // ... at end of each $clinit body:
   *   GWT_CLINIT_ORDER.push('com_example_Foo');
   *   // Access from browser console: console.log(GWT_CLINIT_ORDER)
   * </pre>
   *
   * <p>The recorded order has all {@code java_lang_*} entries sorted to the front
   * (preserving their relative order), so production hoisting initializes JRE core
   * classes first.
   */
  private int execDraftRecording() {
    SourceInfo si = SourceOrigin.UNKNOWN;
    int totalMods = 0;

    // Declare: var GWT_CLINIT_ORDER = [];
    JsName orderName = jsProgram.getScope().declareName("GWT_CLINIT_ORDER");
    JsVars.JsVar orderVar = new JsVars.JsVar(si, orderName);
    orderVar.setInitExpr(new JsArrayLiteral(si));
    JsVars vars = new JsVars(si, orderVar);

    JsBlock fragment0 = jsProgram.getFragmentBlock(0);
    fragment0.getStatements().add(0, vars);
    totalMods++;

    // For each fragment, inject recording call at the end of each clinit body.
    // Placed at end so sub-clinit calls execute first, giving post-order recording.
    for (int i = 0; i < jsProgram.getFragmentCount(); i++) {
      JsBlock fragmentBlock = jsProgram.getFragmentBlock(i);
      Map<String, JsFunction> clinits = collectAllClinitFunctions(fragmentBlock);
      for (Map.Entry<String, JsFunction> entry : clinits.entrySet()) {
        String ident = entry.getKey();
        JsFunction func = entry.getValue();

        // Build: GWT_CLINIT_ORDER.push('ident')
        JsNameRef pushRef = new JsNameRef(si, "push", orderName.makeRef(si));
        JsInvocation pushCall = new JsInvocation(si, pushRef);
        pushCall.getArguments().add(new JsStringLiteral(si, ident));

        List<JsStatement> body = func.getBody().getStatements();
        body.add(body.size(), pushCall.makeStmt());
        totalMods++;
      }
    }

    return totalMods;
  }

  // ===========================================================================
  // Production mode: inline clinits from the recorded order
  // ===========================================================================

  /**
   * Reads the comma-separated clinit identifier list and inlines matching clinits at fragment
   * initialization scope. {@code java_lang_*} entries are sorted to the front (preserving
   * relative order) so JRE core classes are hoisted before everything else.
   */
  private int execProductionHoisting(String clinitOrder) {
    // Parse the ordered list of clinit idents.
    List<String> orderedIdents = new ArrayList<>();
    for (String entry : clinitOrder.split(",")) {
      String trimmed = entry.trim();
      if (!trimmed.isEmpty()) {
        orderedIdents.add(trimmed);
      }
    }
    if (orderedIdents.isEmpty()) {
      return 0;
    }

    int totalMods = 0;

    for (int i = 0; i < jsProgram.getFragmentCount(); i++) {
      JsBlock fragmentBlock = jsProgram.getFragmentBlock(i);
      List<JsStatement> statements = fragmentBlock.getStatements();

      // Map all clinits in this fragment by their ident.
      Map<String, JsFunction> clinitsByIdent = collectAllClinitFunctions(fragmentBlock);

      // Select clinits to hoist: those in the ordered list that exist in this fragment.
      List<JsFunction> toHoist = new ArrayList<>();
      for (String ident : orderedIdents) {
        JsFunction clinit = clinitsByIdent.get(ident);
        if (clinit != null) {
          toHoist.add(clinit);
        }
      }
      if (toHoist.isEmpty()) {
        continue;
      }

      Set<JsFunction> hoistSet = new LinkedHashSet<>(toHoist);

      // Phase 1: Inline clinit bodies at fragment init scope in recorded order.
      int insertionPoint = findInsertionPoint(statements, i);
      for (JsFunction clinit : toHoist) {
        // Copy the statement list to avoid issues when clearing later.
        List<JsStatement> bodyStmts = new ArrayList<>(clinit.getBody().getStatements());
        for (JsStatement stmt : bodyStmts) {
          if (isSelfReplaceStatement(stmt, clinit)) {
            continue;
          }
          statements.add(insertionPoint++, stmt);
          totalMods++;
        }
      }

      // Phase 2: Strip ALL per-access calls to these clinits from the entire fragment.
      // Must run before Phase 3 (unmark) because isExecuteOnce() checks the clinit flag.
      AllClinitCallRemover remover = new AllClinitCallRemover(hoistSet);
      remover.accept(fragmentBlock);
      totalMods += remover.getNumMods();

      // Phase 3: Gut the original clinit function bodies and unmark as clinit.
      for (JsFunction clinit : toHoist) {
        clinit.getBody().getStatements().clear();
        clinit.unmarkAsClinit();
        totalMods++;
      }
    }

    return totalMods;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Collects all clinit functions in a fragment block, regardless of declaration style.
   * Handles both function declarations ({@code function foo() {...}}) and property assignments
   * ({@code ns.foo = function foo() {...}}) which occur in DETAILED/DRAFT output modes.
   */
  private static Map<String, JsFunction> collectAllClinitFunctions(JsBlock fragment) {
    Map<String, JsFunction> result = new LinkedHashMap<>();
    new JsVisitor() {
      @Override
      public boolean visit(JsFunction x, JsContext ctx) {
        if (x.isClinit() && x.getName() != null) {
          result.put(stripClinitSuffix(x.getName().getIdent()), x);
        }
        return false; // don't descend into nested function bodies
      }
    }.accept(fragment);
    return result;
  }

  /**
   * Strips the {@code _$clinit__V} suffix from a clinit function identifier,
   * producing a shorter class-level name (e.g. {@code java_lang_Object}).
   */
  private static String stripClinitSuffix(String ident) {
    if (ident.endsWith(CLINIT_SUFFIX)) {
      return ident.substring(0, ident.length() - CLINIT_SUFFIX.length());
    }
    return ident;
  }

  /**
   * Determines where to insert hoisted clinit code in the fragment.
   *
   * <p>For the base fragment (index 0): at the end of compiled output, before linker bootstrap.
   * <p>For deferred fragments: before the AsyncFragmentLoader.onLoad() call (last stmt).
   */
  private static int findInsertionPoint(List<JsStatement> statements, int fragmentIndex) {
    if (fragmentIndex > 0 && !statements.isEmpty()) {
      return statements.size() - 1;
    }
    return statements.size();
  }

  /**
   * Returns true if the statement is the self-replace assignment: $clinit_X = emptyMethod.
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
    JsFunction lhsFunc = JsUtils.isFunction(binOp.getArg1());
    return lhsFunc == clinit;
  }

  // ---------------------------------------------------------------------------
  // Global clinit call removal
  // ---------------------------------------------------------------------------

  /**
   * Removes ALL invocations of the specified clinit functions from the entire fragment.
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
