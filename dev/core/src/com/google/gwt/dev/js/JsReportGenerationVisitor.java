/*
 * Copyright 2008 Google Inc.
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

import com.google.gwt.core.ext.soyc.Range;
import com.google.gwt.dev.jjs.JsSourceMap;
import com.google.gwt.dev.jjs.SourceInfo;
import com.google.gwt.dev.jjs.SourceOrigin;
import com.google.gwt.dev.jjs.impl.JavaToJavaScriptMap;
import com.google.gwt.dev.js.ast.JsBlock;
import com.google.gwt.dev.js.ast.JsDoWhile;
import com.google.gwt.dev.js.ast.JsExpression;
import com.google.gwt.dev.js.ast.JsFunction;
import com.google.gwt.dev.js.ast.JsName;
import com.google.gwt.dev.js.ast.JsNameRef;
import com.google.gwt.dev.js.ast.JsNode;
import com.google.gwt.dev.js.ast.JsStatement;
import com.google.gwt.dev.js.ast.JsVisitable;
import com.google.gwt.dev.util.TextOutput;
import com.google.gwt.thirdparty.guava.common.annotations.VisibleForTesting;
import com.google.gwt.thirdparty.guava.common.collect.Lists;

import java.util.List;

/**
 * A variation on the standard source generation visitor that records the
 * locations of SourceInfo objects in the output.
 */
public class JsReportGenerationVisitor extends
    JsSourceGenerationVisitorWithSizeBreakdown {
  private final List<Range> ranges = Lists.newArrayList();
  private final TextOutput out;
  private final boolean needSourcemapNames;

  /**
   * The key of the most recently added Javascript range for a descendant
   * of the current node.
   */
  private Range previousRange = null;

  /**
   * The ancestor nodes whose Range and SourceInfo will be added to the sourcemap.
   */
  private List<JsNode> parentStack = Lists.newArrayList();

  /**
   * Stack of all enclosing JsFunctions, regardless of whether they were added
   * to {@link #parentStack}. Used to attribute statement ranges to the
   * enclosing function's source file when statements were spliced in from
   * elsewhere by JJS optimizations.
   */
  private List<JsFunction> functionStack = Lists.newArrayList();

  public JsReportGenerationVisitor(TextOutput out, JavaToJavaScriptMap map,
      boolean needSourcemapNames,
      JsToStringGenerationVisitor.PrintOptions options) {
    super(out, map, options);
    this.out = out;
    this.needSourcemapNames = needSourcemapNames;
  }

  @Override
  protected <T extends JsVisitable> T generateAndBill(T node, JsName nameToBillTo) {
    previousRange = null; // It's not our child because we haven't visited our children yet.

    if (!(node instanceof JsNode)) {
      return super.generateAndBill(node, nameToBillTo);
    }

    boolean willReportRange = false;
    if (node instanceof JsBlock) {
      willReportRange = false; // Only report the statements within the block
    } else if (parentStack.isEmpty()) {
      willReportRange = true;
    } else if (node instanceof JsStatement) {
      willReportRange = true;
    } else if ((node instanceof JsNameRef) && needSourcemapNames) {
      willReportRange = true;
    } else {
      JsNode parent = parentStack.get(parentStack.size() - 1);
      if ((node instanceof JsExpression) &&
          (parent instanceof JsDoWhile)) {
        // Always instrument the expression because it comes at the end.
        // (So we can stop there in a loop.)
        willReportRange = true;
      } else {
        // Instrument the expression if it was inlined in Java.
        SourceInfo info = ((JsNode) node).getSourceInfo();
        if (!surroundsInJavaSource(parent.getSourceInfo(), info)) {
          // Only emit a sub-range when the child stays in the SAME source
          // file as the parent. Cross-file mismatches almost always come
          // from shared/interned AST nodes (class literals, string literals,
          // JsLiteralInterner-generated NameRefs, JCastMap copies, etc.)
          // whose SourceInfo reflects only the FIRST creation site, not
          // where they are actually used. Emitting those wrong sub-ranges
          // overrides the parent's correct mapping for the bytes they
          // cover (V3 source-map segment-extension semantics), so the
          // resulting source map points exception throws and other
          // expressions at completely unrelated Java files.
          //
          // Same-file inlines are kept (e.g. inlined sub-expressions from
          // helpers in the same compilation unit), which preserves
          // fine-grained line attribution within a file.
          SourceInfo parentInfo = parent.getSourceInfo();
          if (info != null && parentInfo != null
              && info.getFileName() != null && parentInfo.getFileName() != null
              && info.getFileName().equals(parentInfo.getFileName())) {
            willReportRange = true;
          }
        }
      }
    }

    // Remember the position before generating the JavaScript.
    int beforePosition = out.getPosition();
    int beforeLine = out.getLine();
    int beforeColumn = out.getColumn();

    if (willReportRange) {
      parentStack.add((JsNode) node);
    }
    boolean pushedFunction = false;
    if (node instanceof JsFunction) {
      functionStack.add((JsFunction) node);
      pushedFunction = true;
    }

    // Write some JavaScript (changing the position).
    T toReturn = super.generateAndBill(node, nameToBillTo);

    if (pushedFunction) {
      functionStack.remove(functionStack.size() - 1);
    }
    if (!willReportRange) {
      return toReturn;
    }
    parentStack.remove(parentStack.size() - 1);

    SourceInfo info = ((JsNode) node).getSourceInfo();

    // File-correctness fallback: when emitting a top-level JsStatement, if its
    // SourceInfo's file doesn't match the enclosing JsFunction's SourceInfo,
    // attribute the range to the function instead. JJS optimizations
    // (especially MethodInliner) routinely splice statements from helper
    // methods into the body of an unrelated outer method while keeping the
    // helper's SourceInfo. Emitting that helper SourceInfo here makes
    // browser stack traces point at the wrong Java file. Snapping to the
    // enclosing function's SourceInfo guarantees that bytes inside e.g.
    // WTK.<clinit> always map to WTK.java, even when the actual statement
    // came from inlining a TableFilter helper.
    if (node instanceof JsStatement) {
      JsFunction enclosing = findEnclosingFunction();
      if (enclosing != null) {
        SourceInfo fnInfo = enclosing.getSourceInfo();
        if (fnInfo != null && fnInfo.getFileName() != null
            && info != null && info.getFileName() != null
            && !info.getFileName().equals(fnInfo.getFileName())) {
          info = fnInfo;
        }
      }
    }

    Range range = new Range(beforePosition, out.getPosition(), beforeLine, beforeColumn,
        out.getLine(), out.getColumn(), info);

    if (out.getPosition() <= beforePosition || beforeLine < 0 || out.getLine() < 0) {
      // Skip bogus entries.
      // Runtime:prototypesByTypeId is pruned here. Maybe others too?
      return toReturn;
    }

    if (info == SourceOrigin.UNKNOWN || info.getFileName() == null || info.getStartLine() < 0) {
      // Skip synthetic types (like 'true' and 'false' literals) with no Java source.
      return toReturn;
    }

    if (previousRange != null && previousRange.contains(range)) {
      // Skip duplicate and nested range.
      return toReturn;
    }

    // There is an opportunity to do a complex "overlapping range" combination here as well. But
    // it's difficult to verify. If we need more speed consider adding this transformation.

    ranges.add(range);
    previousRange = range;
    return toReturn;
  }

  /**
   * Returns the innermost JsFunction currently on the function stack, or null
   * if we are emitting top-level (non-function-enclosed) code.
   */
  private JsFunction findEnclosingFunction() {
    if (functionStack.isEmpty()) {
      return null;
    }
    return functionStack.get(functionStack.size() - 1);
  }

  /**
   * Returns true if the given parent's range as Java source code surrounds
   * the child.
   */
  @VisibleForTesting
  boolean surroundsInJavaSource(SourceInfo parent, SourceInfo child) {
    if (!hasValidJavaRange(parent) || !hasValidJavaRange(child)) {
      return false;
    }
    return parent.getStartPos() <= child.getStartPos() && child.getEndPos() <= parent.getEndPos()
        && child.getFileName().equals(parent.getFileName());
  }

  private boolean hasValidJavaRange(SourceInfo info) {
    return info != null && info.getStartPos() >= 0 && info.getEndPos() >= info.getStartPos();
  }

  @Override
  protected void billChildToHere() {
    if (previousRange != null && previousRange.getEnd() < out.getPosition()) {
      // Expand overlapping range.
      Range expandedRange =
          previousRange.withNewEnd(out.getPosition(), out.getLine(), out.getColumn());
      int lastIndex = ranges.size() - 1;
      Range removedRange = ranges.set(lastIndex, expandedRange);
      assert removedRange == previousRange;
      previousRange = expandedRange;
    }
  }

  @Override
  protected void reportFunctionScopeStart(JsFunction x) {
    // Chrome DevTools "Friendly Call Frames" renames a stack frame only when the source map has a `name` entry on the
    // mapping at the opening '(' of the function's parameter list (see the Chrome DevTools NamesResolver:
    // getFunctionNameFromScopeStart requires the mapped scope-start char to be '('). The output cursor is positioned
    // exactly at that '(' here, so emit a one-character range carrying the function's SourceInfo; the Java correlation
    // on that SourceInfo is turned into the frame name by SourceMapRecorder.getJavaName().
    SourceInfo info = x.getSourceInfo();
    if (info == null || info == SourceOrigin.UNKNOWN || info.getFileName() == null
        || info.getStartLine() < 0) {
      return;
    }
    int position = out.getPosition();
    int line = out.getLine();
    int column = out.getColumn();
    ranges.add(new Range(position, position + 1, line, column, line, column + 1, info));
  }

  @Override
  public JsSourceMap getSourceInfoMap() {
    return new JsSourceMap(ranges, out.getPosition(), out.getLine());
  }
}
