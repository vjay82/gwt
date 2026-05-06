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
import com.google.gwt.dev.js.ast.JsNode;
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
 * {@code $clinit} function to record when it is first called into a global ordered list.
 * The output is a comma-separated list of clinit identifiers that reflects the actual
 * runtime initialization order:
 * <pre>
 *   [GWT clinit order] java_util_HashMap,java_util_Collections,...
 * </pre>
 *
 * <h3>Production mode (inlining)</h3>
 * <p>When the compiler runs in any optimization level above DRAFT and the environment variable
 * {@code GWT_CLINIT_ORDER} is set to a comma-separated list of clinit identifiers (as captured
 * from DRAFT mode output), this pass:
 * <ol>
 *   <li>Inlines each listed clinit's body statements at fragment initialization scope (in the
 *       order specified by the list --- which reflects the runtime dependency order).</li>
 *   <li>Strips per-access invocations of those clinits from the fragment, but only from
 *       top-level code and non-clinit functions. Non-hoisted clinit functions are left
 *       untouched so their lazy dependency calls remain intact.</li>
 *   <li>Guts the original clinit functions and unmarks them so
 *       {@link JsUnusedFunctionRemover} can clean them up.</li>
 * </ol>
 *
 * <p>Clinits NOT in the list retain their original self-replacing behavior unchanged.
 * Their bodies are never modified, so transitive dependencies through non-hoisted
 * intermediaries continue to work correctly.
 *
 * <p>Lambda-generated inner class clinits are excluded from recording and hoisting because
 * their names are unstable between DRAFT and production compilations.
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

  /** Substring present in lambda-generated inner class names. */
  private static final String LAMBDA_MARKER = "$lambda$";

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
   * Instruments all clinit functions to record their first-call order into a global array.
   * After the application has loaded, access the recorded order from the browser console:
   * {@code console.log(GWT_CLINIT_ORDER.join(','))}
   *
   * <p>Generated JS (conceptually):
   * <pre>
   *   var GWT_CLINIT_ORDER = [];
   *   // ... at end of each $clinit body:
   *   GWT_CLINIT_ORDER.push('com_example_Foo');
   * </pre>
   *
   * <p>The recording appends each clinit identifier at the end of its body (post-order),
   * so dependencies are naturally recorded before their dependents. Production mode
   * recomputes the exact order from the production dependency graph; the recording
   * only determines <em>which</em> clinits to hoist.
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
   * initialization scope.
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
        // Capture the clinit function's own SourceInfo so we can re-stamp
        // hoisted statements. Hoisted statements end up at fragment
        // top-level (no enclosing JsFunction), so the snap-to-enclosing-
        // function safeguard in JsReportGenerationVisitor cannot rescue
        // statements whose SourceInfo was polluted by upstream JJS passes
        // (e.g. MethodInliner splicing in helper bodies from other files).
        // Stamping the clinit's SourceInfo guarantees the hoisted bytes
        // map back to the clinit's source file.
        SourceInfo clinitSi = clinit.getSourceInfo();
        // Copy the statement list to avoid issues when clearing later.
        List<JsStatement> bodyStmts = new ArrayList<>(clinit.getBody().getStatements());
        for (JsStatement stmt : bodyStmts) {
          if (isSelfReplaceStatement(stmt, clinit)) {
            continue;
          }
          // Re-stamp the top-level SourceInfo with the clinit's own location.
          // Sub-expressions keep their finer-grained SourceInfo; the
          // cross-file gate in JsReportGenerationVisitor.surroundsInJavaSource
          // suppresses sub-ranges whose file disagrees with the parent
          // (now correctly the clinit file), so polluted inner SourceInfos
          // can no longer hijack the mapping.
          if (clinitSi != null) {
            stmt.replaceSourceInfo(clinitSi);
          }
          statements.add(insertionPoint++, stmt);
          totalMods++;
        }
      }

      // Phase 2: Strip per-access calls to hoisted clinits, but ONLY from top-level
      // code and non-clinit functions. Non-hoisted clinit functions are skipped so
      // their lazy dependency calls remain intact.
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
          String ident = stripClinitSuffix(x.getName().getIdent());
          if (!isLambdaClinit(ident)) {
            result.put(ident, x);
          }
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
   * Returns true if the ident belongs to a lambda-generated inner class.
   * Lambda class names are unstable between DRAFT and production compilations
   * and their clinits are trivial, so they should not be recorded or hoisted.
   */
  private static boolean isLambdaClinit(String ident) {
    return ident.contains(LAMBDA_MARKER);
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
   * Recursively replaces every JsNode's SourceInfo with {@link SourceOrigin#UNKNOWN}.
   * Used to suppress source-map entries for hoisted clinit code, since the original
   * source location no longer corresponds to where the code actually executes.
   */
  private static void stripSourceInfo(JsNode root) {
    new JsVisitor() {
      @Override
      protected <T extends com.google.gwt.dev.js.ast.JsVisitable> T doAccept(T node) {
        if (node instanceof JsNode) {
          ((JsNode) node).replaceSourceInfo(SourceOrigin.UNKNOWN);
        }
        return super.doAccept(node);
      }

      @Override
      protected <T extends com.google.gwt.dev.js.ast.JsVisitable> void doAcceptList(
          List<T> collection) {
        for (T node : collection) {
          if (node instanceof JsNode) {
            ((JsNode) node).replaceSourceInfo(SourceOrigin.UNKNOWN);
          }
        }
        super.doAcceptList(collection);
      }

      @Override
      protected JsExpression doAcceptLvalue(JsExpression expr) {
        if (expr != null) {
          expr.replaceSourceInfo(SourceOrigin.UNKNOWN);
        }
        return super.doAcceptLvalue(expr);
      }

      @Override
      protected <T extends com.google.gwt.dev.js.ast.JsVisitable> void doAcceptWithInsertRemove(
          List<T> collection) {
        for (T node : collection) {
          if (node instanceof JsNode) {
            ((JsNode) node).replaceSourceInfo(SourceOrigin.UNKNOWN);
          }
        }
        super.doAcceptWithInsertRemove(collection);
      }
    }.accept(root);
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
  // Scoped clinit call removal
  // ---------------------------------------------------------------------------

  /**
   * Removes invocations of the specified (hoisted) clinit functions, but only from
   * top-level fragment code and non-clinit function bodies. Non-hoisted clinit functions
   * are explicitly skipped so their lazy dependency calls remain intact.
   *
   * <p>This is critical for correctness: if a non-hoisted clinit (e.g. IsoFields) depends on
   * a hoisted clinit (e.g. DateTimeFormat), removing the call from inside IsoFields' body
   * would cause it to access uninitialized statics when it eventually runs lazily.
   */
  private static class AllClinitCallRemover extends JsModVisitor {
    private final Set<JsFunction> removedClinits;

    AllClinitCallRemover(Set<JsFunction> removedClinits) {
      this.removedClinits = removedClinits;
    }

    @Override
    public boolean visit(JsFunction x, JsContext ctx) {
      // Skip non-hoisted clinit function bodies entirely. Their internal clinit calls
      // must remain so lazy initialization triggers dependencies correctly.
      if (x.isClinit() && !removedClinits.contains(x)) {
        return false;
      }
      // For hoisted clinits (about to be gutted) and normal functions, descend and remove calls.
      return true;
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
