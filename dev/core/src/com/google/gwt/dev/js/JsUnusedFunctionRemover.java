/*
 * Copyright 2007 Google Inc.
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

import com.google.gwt.dev.jjs.InternalCompilerException;
import com.google.gwt.dev.jjs.impl.OptimizerStats;
import com.google.gwt.dev.js.ast.JsContext;
import com.google.gwt.dev.js.ast.JsExprStmt;
import com.google.gwt.dev.js.ast.JsFunction;
import com.google.gwt.dev.js.ast.JsName;
import com.google.gwt.dev.js.ast.JsNameRef;
import com.google.gwt.dev.js.ast.JsProgram;
import com.google.gwt.dev.js.ast.JsProgramFragment;
import com.google.gwt.dev.js.ast.JsStatement;
import com.google.gwt.dev.js.ast.JsVisitor;
import com.google.gwt.dev.util.collect.IdentityHashSet;

import java.util.Iterator;
import java.util.Set;

/**
 * Removes JsFunctions that are never referenced in the program.
 *
 * <p>Uses a single full traversal to collect all referenced names, then a
 * lightweight direct iteration over fragment block statements to remove
 * unreferenced function declarations (avoiding a second full AST traversal).
 */
public class JsUnusedFunctionRemover {

  /**
   * Finds all function references in the program.
   */
  private class RescueVisitor extends JsVisitor {

    @Override
    public void endVisit(JsNameRef x, JsContext ctx) {
      seen.add(x.getName());
    }
  }

  public static final String NAME = JsUnusedFunctionRemover.class.getSimpleName();

  public static int exec(JsProgram program) {
    return new JsUnusedFunctionRemover(program).execImpl();
  }

  private final JsProgram program;
  private final Set<JsName> seen = new IdentityHashSet<JsName>();

  private JsUnusedFunctionRemover(JsProgram program) {
    this.program = program;
  }

  public int execImpl() {
    try (OptimizerStats stats = OptimizerStats.optimization(NAME)) {

      // Rescue all referenced functions via a single full traversal.
      new RescueVisitor().accept(program);

      // Remove unreferenced function declarations by directly iterating
      // fragment block statements, avoiding a full JsModVisitor traversal.
      int removed = 0;
      for (JsProgramFragment fragment : program.getFragments()) {
        removed += removeUnusedFromBlock(fragment.getGlobalBlock().getStatements().iterator());
      }

      stats.recordModified(removed);
      return stats.getNumMods();
    }
  }

  private int removeUnusedFromBlock(Iterator<JsStatement> it) {
    int removed = 0;
    while (it.hasNext()) {
      JsStatement stmt = it.next();
      if (!(stmt instanceof JsExprStmt)) {
        continue;
      }
      JsExprStmt exprStmt = (JsExprStmt) stmt;
      if (!(exprStmt.getExpression() instanceof JsFunction)) {
        continue;
      }
      JsFunction f = (JsFunction) exprStmt.getExpression();
      JsName name = f.getName();

      // Anonymous function, ignore it
      if (name == null || seen.contains(name)) {
        continue;
      }

      // Removing a static initializer indicates a problem in JsInliner.
      if (f.isClinit()) {
        throw new InternalCompilerException("Tried to remove clinit "
            + name.getStaticRef().toSource());
      }
      it.remove();
      removed++;
    }
    return removed;
  }
}
