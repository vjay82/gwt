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

import com.google.gwt.core.ext.linker.StatementRanges;
import com.google.gwt.core.ext.linker.impl.NamedRange;
import com.google.gwt.core.ext.linker.impl.StandardStatementRanges;
import com.google.gwt.dev.js.ast.HasName;
import com.google.gwt.dev.js.ast.JsArrayAccess;
import com.google.gwt.dev.js.ast.JsArrayLiteral;
import com.google.gwt.dev.js.ast.JsArrowFunction;
import com.google.gwt.dev.js.ast.JsAwait;
import com.google.gwt.dev.js.ast.JsBigIntLiteral;
import com.google.gwt.dev.js.ast.JsBinaryOperation;
import com.google.gwt.dev.js.ast.JsBinaryOperator;
import com.google.gwt.dev.js.ast.JsBlock;
import com.google.gwt.dev.js.ast.JsBooleanLiteral;
import com.google.gwt.dev.js.ast.JsBreak;
import com.google.gwt.dev.js.ast.JsCase;
import com.google.gwt.dev.js.ast.JsCatch;
import com.google.gwt.dev.js.ast.JsClass;
import com.google.gwt.dev.js.ast.JsClass.JsClassMember;
import com.google.gwt.dev.js.ast.JsComputedPropertyKey;
import com.google.gwt.dev.js.ast.JsConditional;
import com.google.gwt.dev.js.ast.JsContext;
import com.google.gwt.dev.js.ast.JsContinue;
import com.google.gwt.dev.js.ast.JsDebugger;
import com.google.gwt.dev.js.ast.JsDefault;
import com.google.gwt.dev.js.ast.JsDestructuring;
import com.google.gwt.dev.js.ast.JsDestructuring.JsDestructuringElement;
import com.google.gwt.dev.js.ast.JsDoWhile;
import com.google.gwt.dev.js.ast.JsEmpty;
import com.google.gwt.dev.js.ast.JsExprStmt;
import com.google.gwt.dev.js.ast.JsExpression;
import com.google.gwt.dev.js.ast.JsFor;
import com.google.gwt.dev.js.ast.JsForIn;
import com.google.gwt.dev.js.ast.JsForOf;
import com.google.gwt.dev.js.ast.JsFunction;
import com.google.gwt.dev.js.ast.JsIf;
import com.google.gwt.dev.js.ast.JsInvocation;
import com.google.gwt.dev.js.ast.JsLabel;
import com.google.gwt.dev.js.ast.JsName;
import com.google.gwt.dev.js.ast.JsNameOf;
import com.google.gwt.dev.js.ast.JsNameRef;
import com.google.gwt.dev.js.ast.JsNew;
import com.google.gwt.dev.js.ast.JsNullLiteral;
import com.google.gwt.dev.js.ast.JsNumberLiteral;
import com.google.gwt.dev.js.ast.JsNumericEntry;
import com.google.gwt.dev.js.ast.JsObjectLiteral;
import com.google.gwt.dev.js.ast.JsOperator;
import com.google.gwt.dev.js.ast.JsParameter;
import com.google.gwt.dev.js.ast.JsPositionMarker;
import com.google.gwt.dev.js.ast.JsPostfixOperation;
import com.google.gwt.dev.js.ast.JsPrefixOperation;
import com.google.gwt.dev.js.ast.JsProgram;
import com.google.gwt.dev.js.ast.JsProgramFragment;
import com.google.gwt.dev.js.ast.JsPropertyInitializer;
import com.google.gwt.dev.js.ast.JsRegExp;
import com.google.gwt.dev.js.ast.JsRestParameter;
import com.google.gwt.dev.js.ast.JsReturn;
import com.google.gwt.dev.js.ast.JsSpread;
import com.google.gwt.dev.js.ast.JsStatement;
import com.google.gwt.dev.js.ast.JsStringLiteral;
import com.google.gwt.dev.js.ast.JsSuperRef;
import com.google.gwt.dev.js.ast.JsSwitch;
import com.google.gwt.dev.js.ast.JsTemplateLiteral;
import com.google.gwt.dev.js.ast.JsThisRef;
import com.google.gwt.dev.js.ast.JsThrow;
import com.google.gwt.dev.js.ast.JsTry;
import com.google.gwt.dev.js.ast.JsUnaryOperator;
import com.google.gwt.dev.js.ast.JsVars;
import com.google.gwt.dev.js.ast.JsVars.JsVar;
import com.google.gwt.dev.js.ast.JsVisitor;
import com.google.gwt.dev.js.ast.JsWhile;
import com.google.gwt.dev.js.ast.JsYield;
import com.google.gwt.dev.util.TextOutput;
import com.google.gwt.dev.util.collect.HashSet;
import com.google.gwt.util.tools.shared.StringUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Produces text output from a JavaScript AST.
 */
@SuppressWarnings("checkstyle:MethodName")
public class JsToStringGenerationVisitor extends JsVisitor {

  private static final String BREAK = "break";
  private static final String CASE = "case";
  private static final String CATCH = "catch";
  private static final String CONTINUE = "continue";
  private static final String DEBUGGER = "debugger";
  private static final String DEFAULT = "default";
  private static final String DO = "do";
  private static final String ELSE = "else";
  private static final String FALSE = "false";
  private static final String FINALLY = "finally";
  private static final String FOR = "for";
  private static final String FUNCTION = "function";
  private static final String IF = "if";
  private static final String IN = "in";
  private static final String NEW = "new";
  private static final String NULL = "null";
  private static final String RETURN = "return";
  private static final String SWITCH = "switch";
  private static final String THIS = "this";
  private static final String THROW = "throw";
  private static final String TRUE = "true";
  private static final String TRY = "try";
  private static final String VAR = "var";
  private static final String WHILE = "while";
  private static final String LET = "let";
  private static final String CONST = "const";
  private static final String CLASS = "class";
  private static final String EXTENDS = "extends";
  private static final String SUPER = "super";
  private static final String STATIC = "static";
  private static final String GET = "get";
  private static final String SET = "set";
  private static final String OF = "of";
  private static final String ASYNC = "async";
  private static final String AWAIT = "await";
  private static final String YIELD = "yield";
  private static final String ARROW = "=>";
  private static final String SPREAD = "...";
  /**
   * How many lines of code to print inside of a JsBlock when printing terse.
   */
  private static final int JSBLOCK_LINES_TO_PRINT = 3;
  private static final long MAX_DECIMAL_VALUE = 999_999_999_999L;

  protected boolean needSemi = true;
  private final List<NamedRange> classRanges = new ArrayList<>();
  private NamedRange currentClassRange;
  private NamedRange programClassRange;

  /**
   * "Global" blocks are either the global block of a fragment, or a block
   * nested directly within some other global block. This definition matters
   * because the statements designated by statementEnds and statementStarts are
   * those that appear directly within these global blocks.
   */
  private final Set<JsBlock> globalBlocks = new HashSet<>();
  private final TextOutput p;
  private final ArrayList<Integer> statementEnds = new ArrayList<>();
  private final ArrayList<Integer> statementStarts = new ArrayList<>();
  private final boolean useLongIdents;
  private final boolean minifyLiterals;
  private final boolean emitArrowFunctions;

  public static class PrintOptions {
    public final boolean useLongIdents;
    public final boolean minifyLiterals;
    public final boolean emitArrowFunctions;

    public PrintOptions(boolean useLongIdents, boolean minifyLiterals) {
      this(useLongIdents, minifyLiterals, false);
    }

    public PrintOptions(boolean useLongIdents, boolean minifyLiterals, boolean emitArrowFunctions) {
      this.useLongIdents = useLongIdents;
      this.minifyLiterals = minifyLiterals;
      this.emitArrowFunctions = emitArrowFunctions;
    }
  }

  /**
   * Generate the output string using short identifiers.
   */
  public JsToStringGenerationVisitor(TextOutput out) {
    this(out, new PrintOptions(false, false));
  }

  /**
   * Generate the output string using short or long identifiers.
   *
   * @param options settings for minification
   */
  JsToStringGenerationVisitor(TextOutput out, PrintOptions options) {
    this.p = out;
    this.useLongIdents = options.useLongIdents;
    this.minifyLiterals = options.minifyLiterals;
    this.emitArrowFunctions = options.emitArrowFunctions;
  }

  public List<NamedRange> getClassRanges() {
    return classRanges;
  }

  /**
   * Returns a NamedRange pointing at the starting position of the first class in the program and
   * the ending position of the last class in the program. Any bytes before or after this range are
   * considered preamble and epilogue respectively.
   */
  public NamedRange getProgramClassRange() {
    return programClassRange;
  }

  public StatementRanges getStatementRanges() {
    return new StandardStatementRanges(statementStarts, statementEnds);
  }

  @Override
  public boolean visit(JsArrayAccess x, JsContext ctx) {
    JsExpression arrayExpr = x.getArrayExpr();
    _parenPush(x, arrayExpr, false);
    accept(arrayExpr);
    _parenPop(x, arrayExpr, false);
    if (x.isOptionalChaining()) {
      p.print("?.[");
    } else {
      _lsquare();
    }
    accept(x.getIndexExpr());
    _rsquare();
    return false;
  }

  @Override
  public boolean visit(JsArrayLiteral x, JsContext ctx) {
    _lsquare();
    boolean sep = false;
    for (JsExpression arg : x.getExpressions()) {
      sep = _sepCommaOptSpace(sep);
      _parenPushIfCommaExpr(arg);
      accept(arg);
      _parenPopIfCommaExpr(arg);
    }
    _rsquare();
    return false;
  }

  @Override
  public boolean visit(JsArrowFunction x, JsContext ctx) {
    if (x.isAsync()) {
      p.print(ASYNC);
      _space();
    }
    boolean singleParam = x.getParameters().size() == 1;
    if (!singleParam) {
      _lparen();
    }
    boolean sep = false;
    for (JsParameter param : x.getParameters()) {
      sep = _sepCommaOptSpace(sep);
      accept(param);
    }
    if (!singleParam) {
      _rparen();
    }
    _spaceOpt();
    p.print(ARROW);
    _spaceOpt();
    if (x.isConciseBody()) {
      // Expression body: emit just the expression, no braces
      JsExprStmt exprStmt = (JsExprStmt) x.getBody().getStatements().get(0);
      JsExpression expr = exprStmt.getExpression();
      // Wrap object literals in parens to avoid ambiguity with block
      boolean needsParens = expr instanceof JsObjectLiteral;
      if (needsParens) {
        _lparen();
      }
      accept(expr);
      if (needsParens) {
        _rparen();
      }
    } else {
      accept(x.getBody());
    }
    needSemi = true;
    return false;
  }

  @Override
  public boolean visit(JsAwait x, JsContext ctx) {
    p.print(AWAIT);
    _space();
    accept(x.getExpression());
    return false;
  }

  @Override
  public boolean visit(JsBinaryOperation x, JsContext ctx) {
    JsBinaryOperator op = x.getOperator();
    JsExpression arg1 = x.getArg1();
    _parenPush(x, arg1, !op.isLeftAssociative());
    accept(arg1);
    if (op.isKeyword()) {
      _parenPopOrSpace(x, arg1, !op.isLeftAssociative());
    } else {
      _parenPop(x, arg1, !op.isLeftAssociative());
      _spaceOpt();
    }
    p.print(op.getSymbol());
    JsExpression arg2 = x.getArg2();
    if (_spaceCalc(op, arg2)) {
      _parenPushOrSpace(x, arg2, op.isLeftAssociative());
    } else {
      _spaceOpt();
      _parenPush(x, arg2, op.isLeftAssociative());
    }
    accept(arg2);
    _parenPop(x, arg2, op.isLeftAssociative());
    return false;
  }

  @Override
  public boolean visit(JsBlock x, JsContext ctx) {
    printJsBlock(x, true, true);
    return false;
  }

  @Override
  public boolean visit(JsBooleanLiteral x, JsContext ctx) {
    if (minifyLiterals) {
      p.print(x.getValue() ? "!0" : "!1");
      return false;
    }
    if (x.getValue()) {
      _true();
    } else {
      _false();
    }
    return false;
  }

  @Override
  public boolean visit(JsBreak x, JsContext ctx) {
    _break();

    JsNameRef label = x.getLabel();
    if (label != null) {
      _space();
      _nameRef(label);
    }

    return false;
  }

  @Override
  public boolean visit(JsCase x, JsContext ctx) {
    _case();
    _space();
    accept(x.getCaseExpr());
    _colon();
    _newlineOpt();

    indent();
    for (Object element : x.getStmts()) {
      JsStatement stmt = (JsStatement) element;
      needSemi = true;
      accept(stmt);
      if (needSemi) {
        _semi();
      }
      _newlineOpt();
    }
    outdent();
    needSemi = false;
    return false;
  }

  @Override
  public boolean visit(JsCatch x, JsContext ctx) {
    _spaceOpt();
    _catch();
    _spaceOpt();
    _lparen();
    _nameDef(x.getParameter().getName());

    // Optional catch condition.
    //
    JsExpression catchCond = x.getCondition();
    if (catchCond != null) {
      _space();
      _if();
      _space();
      accept(catchCond);
    }

    _rparen();
    _spaceOpt();
    accept(x.getBody());

    return false;
  }

  @Override
  public boolean visit(JsClass x, JsContext ctx) {
    p.print(CLASS);
    if (x.getName() != null) {
      _space();
      _nameDef(x.getName());
    }
    if (x.getSuperExpr() != null) {
      _space();
      p.print(EXTENDS);
      _space();
      accept(x.getSuperExpr());
    }
    _spaceOpt();
    _lbrace();
    p.indentIn();
    _newlineOpt();
    if (x.getConstructor() != null) {
      p.print("constructor");
      _lparen();
      boolean sep = false;
      for (JsParameter param : x.getConstructor().getParameters()) {
        sep = _sepCommaOptSpace(sep);
        accept(param);
      }
      _rparen();
      accept(x.getConstructor().getBody());
      _newlineOpt();
    }
    for (JsClassMember member : x.getMembers()) {
      if (member.isStatic()) {
        p.print(STATIC);
        _space();
      }
      if (member.isGetter()) {
        p.print(GET);
        _space();
      } else if (member.isSetter()) {
        p.print(SET);
        _space();
      }
      if (member.isComputed()) {
        _lsquare();
        accept(member.getNameExpr());
        _rsquare();
      } else {
        accept(member.getNameExpr());
      }
      accept(member.getValueExpr());
      _newlineOpt();
    }
    p.indentOut();
    _rbrace();
    needSemi = false;
    return false;
  }

  @Override
  public boolean visit(JsClassMember x, JsContext ctx) {
    // Handled by visit(JsClass)
    return false;
  }

  @Override
  public boolean visit(JsComputedPropertyKey x, JsContext ctx) {
    _lsquare();
    accept(x.getExpression());
    _rsquare();
    return false;
  }

  @Override
  public boolean visit(JsPositionMarker x, JsContext ctx) {
    needSemi = false;

    switch (x.getType()) {
      case CLASS_START:
        assert currentClassRange
            == null : "Class start and end boundaries must be matched and not nested.";
        currentClassRange = new NamedRange(x.getName());
        currentClassRange.setStartPosition(p.getPosition());
        currentClassRange.setStartLineNumber(p.getLine());
        break;
      case CLASS_END:
        assert currentClassRange
            != null : "Class start and end boundaries must be matched and not nested.";
        currentClassRange.setEndPosition(p.getPosition());
        currentClassRange.setEndLineNumber(p.getLine());
        classRanges.add(currentClassRange);
        currentClassRange = null;
        break;
      case PROGRAM_START:
        programClassRange = new NamedRange("Program");
        programClassRange.setStartPosition(p.getPosition());
        programClassRange.setStartLineNumber(p.getLine());
        break;
      case PROGRAM_END:
        assert programClassRange != null : "Program start and end boundaries must be matched.";
        programClassRange.setEndPosition(p.getPosition());
        programClassRange.setEndLineNumber(p.getLine());
        break;
      default:
        assert false : x.getType() + " position type is not recognized.";
    }

    return super.visit(x, ctx);
  }

  @Override
  public boolean visit(JsConditional x, JsContext ctx) {
    // Associativity: for the then and else branches, it is safe to insert
    // another
    // ternary expression, but if the test expression is a ternary, it should
    // get parentheses around it.
    {
      JsExpression testExpression = x.getTestExpression();
      _parenPush(x, testExpression, true);
      accept(testExpression);
      _parenPop(x, testExpression, true);
    }
    _questionMark();
    {
      JsExpression thenExpression = x.getThenExpression();
      _parenPush(x, thenExpression, false);
      accept(thenExpression);
      _parenPop(x, thenExpression, false);
    }
    _colon();
    {
      JsExpression elseExpression = x.getElseExpression();
      _parenPush(x, elseExpression, false);
      accept(elseExpression);
      _parenPop(x, elseExpression, false);
    }
    return false;
  }

  @Override
  public boolean visit(JsContinue x, JsContext ctx) {
    _continue();

    JsNameRef label = x.getLabel();
    if (label != null) {
      _space();
      _nameRef(label);
    }

    return false;
  }

  @Override
  public boolean visit(JsDebugger x, JsContext ctx) {
    _debugger();
    return false;
  }

  @Override
  public boolean visit(JsDefault x, JsContext ctx) {
    _default();
    _colon();

    indent();
    for (JsStatement stmt : x.getStmts()) {
      needSemi = true;
      accept(stmt);
      if (needSemi) {
        _semi();
      }
      _newlineOpt();
    }
    outdent();
    needSemi = false;
    return false;
  }

  @Override
  public boolean visit(JsDestructuring x, JsContext ctx) {
    if (x.getDestructuringKind() == JsDestructuring.Kind.ARRAY) {
      _lsquare();
    } else {
      _lbrace();
    }
    boolean sep = false;
    for (JsDestructuringElement elem : x.getElements()) {
      sep = _sepCommaOptSpace(sep);
      if (elem.isRest()) {
        p.print(SPREAD);
      }
      if (elem.getKey() != null) {
        accept(elem.getKey());
        _colon();
        _spaceOpt();
      }
      if (elem.getTarget() != null) {
        accept(elem.getTarget());
      }
      if (elem.getDefaultValue() != null) {
        _spaceOpt();
        _assignment();
        _spaceOpt();
        accept(elem.getDefaultValue());
      }
    }
    if (x.getDestructuringKind() == JsDestructuring.Kind.ARRAY) {
      _rsquare();
    } else {
      _rbrace();
    }
    return false;
  }

  @Override
  public boolean visit(JsDestructuringElement x, JsContext ctx) {
    // Handled by visit(JsDestructuring)
    return false;
  }

  @Override
  public boolean visit(JsDoWhile x, JsContext ctx) {
    _do();
    _nestedPush(x.getBody(), true);
    accept(x.getBody());
    _nestedPop(x.getBody());
    if (needSemi) {
      _semi();
      _newlineOpt();
    } else {
      _spaceOpt();
      needSemi = true;
    }
    _while();
    _spaceOpt();
    _lparen();
    accept(x.getCondition());
    _rparen();
    return false;
  }

  @Override
  public boolean visit(JsEmpty x, JsContext ctx) {
    return false;
  }

  @Override
  public boolean visit(JsExprStmt x, JsContext ctx) {
    boolean surroundWithParentheses = JsFirstExpressionVisitor.exec(x);
    if (surroundWithParentheses && emitArrowFunctions) {
      surroundWithParentheses = needsExprStmtParens(x.getExpression());
    }
    if (surroundWithParentheses) {
      _lparen();
    }
    JsExpression expr = x.getExpression();
    accept(expr);
    if (surroundWithParentheses) {
      _rparen();
    }
    return false;
  }

  private boolean needsExprStmtParens(JsExpression expr) {
    while (true) {
      if (expr instanceof JsObjectLiteral) {
        return true;
      }
      if (expr instanceof JsFunction) {
        return !canEmitAsArrow((JsFunction) expr);
      }
      if (expr instanceof JsBinaryOperation) {
        expr = ((JsBinaryOperation) expr).getArg1();
      } else if (expr instanceof JsInvocation) {
        expr = ((JsInvocation) expr).getQualifier();
      } else if (expr instanceof JsNameRef) {
        JsExpression q = ((JsNameRef) expr).getQualifier();
        if (q != null) {
          expr = q;
        } else {
          return false;
        }
      } else if (expr instanceof JsArrayAccess) {
        expr = ((JsArrayAccess) expr).getArrayExpr();
      } else if (expr instanceof JsPostfixOperation) {
        expr = ((JsPostfixOperation) expr).getArg();
      } else if (expr instanceof JsConditional) {
        expr = ((JsConditional) expr).getTestExpression();
      } else {
        return false;
      }
    }
  }

  @Override
  public boolean visit(JsFor x, JsContext ctx) {
    _for();
    _spaceOpt();
    _lparen();

    // The init expressions or var decl.
    //
    if (x.getInitExpr() != null) {
      accept(x.getInitExpr());
    } else if (x.getInitVars() != null) {
      accept(x.getInitVars());
    }

    _semi();

    // The loop test.
    //
    if (x.getCondition() != null) {
      _spaceOpt();
      accept(x.getCondition());
    }

    _semi();

    // The incr expression.
    //
    if (x.getIncrExpr() != null) {
      _spaceOpt();
      accept(x.getIncrExpr());
    }

    _rparen();
    _nestedPush(x.getBody(), false);
    accept(x.getBody());
    _nestedPop(x.getBody());
    return false;
  }

  @Override
  public boolean visit(JsForIn x, JsContext ctx) {
    _for();
    _spaceOpt();
    _lparen();

    if (x.getIterVarName() != null) {
      _var();
      _space();
      _nameDef(x.getIterVarName());

      if (x.getIterExpr() != null) {
        _spaceOpt();
        _assignment();
        _spaceOpt();
        accept(x.getIterExpr());
      }
    } else {
      // Just a name ref.
      //
      accept(x.getIterExpr());
    }

    _space();
    _in();
    _space();
    accept(x.getObjExpr());

    _rparen();
    _nestedPush(x.getBody(), false);
    accept(x.getBody());
    _nestedPop(x.getBody());
    return false;
  }

  @Override
  public boolean visit(JsForOf x, JsContext ctx) {
    _for();
    _spaceOpt();
    _lparen();

    if (x.getIterVarName() != null) {
      p.print(LET);
      _space();
      _nameDef(x.getIterVarName());

      if (x.getIterExpr() != null) {
        _spaceOpt();
        _assignment();
        _spaceOpt();
        accept(x.getIterExpr());
      }
    } else {
      accept(x.getIterExpr());
    }

    _space();
    p.print(OF);
    _space();
    accept(x.getIterableExpr());

    _rparen();
    _nestedPush(x.getBody(), false);
    accept(x.getBody());
    _nestedPop(x.getBody());
    return false;
  }

  // function foo(a, b) {
  // stmts...
  // }
  //
  private static boolean canEmitAsArrow(JsFunction fn) {
    if (fn.getName() != null || fn.isGenerator()) {
      return false;
    }
    return !usesThisOrArguments(fn.getBody());
  }

  private static boolean usesThisOrArguments(JsBlock body) {
    if (body == null) {
      return false;
    }
    class Detector extends JsVisitor {
      boolean found = false;
      @Override
      public boolean visit(JsThisRef x, JsContext ctx) {
        found = true;
        return false;
      }
      @Override
      public boolean visit(JsNameRef x, JsContext ctx) {
        if (x.getQualifier() == null && "arguments".equals(x.getIdent())) {
          found = true;
          return false;
        }
        return true;
      }
      @Override
      public boolean visit(JsFunction x, JsContext ctx) {
        return false;
      }
      @Override
      public boolean visit(JsArrowFunction x, JsContext ctx) {
        return false;
      }
    }
    Detector d = new Detector();
    d.accept(body);
    return d.found;
  }

  @Override
  public boolean visit(JsFunction x, JsContext ctx) {
    if (emitArrowFunctions && canEmitAsArrow(x)) {
      return visitAsArrow(x);
    }
    if (x.isAsync()) {
      p.print(ASYNC);
      _space();
    }
    _function();
    if (x.isGenerator()) {
      p.print('*');
    }

    // Functions can be anonymous.
    //
    if (x.getName() != null) {
      _space();
      _nameOf(x);
    }

    _lparen();
    boolean sep = false;
    for (JsParameter param : x.getParameters()) {
      sep = _sepCommaOptSpace(sep);
      accept(param);
    }
    _rparen();

    accept(x.getBody());
    needSemi = true;
    return false;
  }

  private boolean visitAsArrow(JsFunction x) {
    if (x.isAsync()) {
      p.print(ASYNC);
      _space();
    }
    boolean singleParam = x.getParameters().size() == 1;
    if (!singleParam) {
      _lparen();
    }
    boolean sep = false;
    for (JsParameter param : x.getParameters()) {
      sep = _sepCommaOptSpace(sep);
      accept(param);
    }
    if (!singleParam) {
      _rparen();
    }
    _spaceOpt();
    p.print(ARROW);

    JsBlock body = x.getBody();
    JsExpression conciseExpr = getConciseArrowBody(body);
    if (conciseExpr != null) {
      _spaceOpt();
      boolean wrapParens = conciseExpr instanceof JsObjectLiteral
          || (conciseExpr instanceof JsBinaryOperation
              && ((JsBinaryOperation) conciseExpr).getOperator() == JsBinaryOperator.COMMA);
      if (wrapParens) {
        _lparen();
      }
      accept(conciseExpr);
      if (wrapParens) {
        _rparen();
      }
    } else {
      accept(body);
    }
    needSemi = true;
    return false;
  }

  private static JsExpression getConciseArrowBody(JsBlock body) {
    if (body == null) {
      return null;
    }
    List<JsStatement> stmts = body.getStatements();
    if (stmts.size() != 1) {
      return null;
    }
    JsStatement stmt = stmts.get(0);
    if (stmt instanceof JsReturn) {
      return ((JsReturn) stmt).getExpr();
    }
    return null;
  }

  @Override
  public boolean visit(JsIf x, JsContext ctx) {
    _if();
    _spaceOpt();
    _lparen();
    accept(x.getIfExpr());
    _rparen();
    JsStatement thenStmt = x.getThenStmt();
    _nestedPush(thenStmt, false);
    accept(thenStmt);
    _nestedPop(thenStmt);
    JsStatement elseStmt = x.getElseStmt();
    if (elseStmt != null) {
      if (needSemi) {
        _semi();
        _newlineOpt();
      } else {
        _spaceOpt();
        needSemi = true;
      }
      _else();
      boolean elseIf = elseStmt instanceof JsIf;
      if (!elseIf) {
        _nestedPush(elseStmt, true);
      } else {
        _space();
      }
      accept(elseStmt);
      if (!elseIf) {
        _nestedPop(elseStmt);
      }
    }
    return false;
  }

  @Override
  public boolean visit(JsInvocation x, JsContext ctx) {
    JsExpression qualifier = x.getQualifier();
    _parenPush(x, qualifier, false);
    accept(qualifier);
    _parenPop(x, qualifier, false);

    _lparen();
    boolean sep = false;
    for (JsExpression arg : x.getArguments()) {
      sep = _sepCommaOptSpace(sep);
      _parenPushIfCommaExpr(arg);
      accept(arg);
      _parenPopIfCommaExpr(arg);
    }
    _rparen();
    return false;
  }

  @Override
  public boolean visit(JsLabel x, JsContext ctx) {
    _nameOf(x);
    _colon();
    _spaceOpt();
    accept(x.getStmt());
    return false;
  }

  @Override
  public boolean visit(JsNameOf x, JsContext ctx) {
    if (useLongIdents) {
      printStringLiteral(x.getName().getIdent());
    } else {
      printStringLiteral(x.getName().getShortIdent());
    }

    return false;
  }

  @Override
  public boolean visit(JsNameRef x, JsContext ctx) {
    JsExpression q = x.getQualifier();
    if (q != null) {
      _parenPush(x, q, false);
      accept(q);
      if (q instanceof JsNumberLiteral) {
        /*
         * Fix for Issue #3796. "42.foo" is not allowed, but "42 .foo" is.
         */
        _space();
      }
      _parenPop(x, q, false);
      if (x.isOptionalChaining()) {
        p.print("?.");
      } else {
        _dot();
      }
    }
    _nameRef(x);
    return false;
  }

  @Override
  public boolean visit(JsNew x, JsContext ctx) {
    _new();
    _space();

    JsExpression ctorExpr = x.getConstructorExpression();
    boolean needsParens = JsConstructExpressionVisitor.exec(ctorExpr);
    if (needsParens) {
      _lparen();
    }
    accept(ctorExpr);
    if (needsParens) {
      _rparen();
    }

    /*
     * If a constructor call has no arguments, it may simply be replaced with
     * "new Constructor" with no parentheses.
     */
    List<JsExpression> args = x.getArguments();
    if (args.size() > 0) {
      _lparen();
      boolean sep = false;
      for (JsExpression arg : args) {
        sep = _sepCommaOptSpace(sep);
        _parenPushIfCommaExpr(arg);
        accept(arg);
        _parenPopIfCommaExpr(arg);
      }
      _rparen();
    }

    return false;
  }

  @Override
  public boolean visit(JsNullLiteral x, JsContext ctx) {
    _null();
    return false;
  }

  @Override
  public boolean visit(JsBigIntLiteral x, JsContext ctx) {
    p.print(Long.toString(x.getValue()));
    p.print('n');
    return false;
  }

  @Override
  public boolean visit(JsNumberLiteral x, JsContext ctx) {
    String val = _stringifyNumber(x);
    p.print(val);
    return false;
  }

  private String _stringifyNumber(JsNumberLiteral x) {
    double dvalue = x.getValue();
    if (dvalue == 0.0 && 1.0 / dvalue == Double.NEGATIVE_INFINITY) {
      // Negative zero is distinct from 0.0 and (integer) 0
      return "-0";
    }

    long lvalue = (long) dvalue;
    if (lvalue == dvalue) {
      String longVal = Long.toString(lvalue);
      if (minifyLiterals && lvalue != 0) {
        int trailingZeros = numberOfTrailingDecZeros(longVal);
        if (trailingZeros > 2) {
          // print 1000 as 1e3, keep 100 as is
          longVal = longVal.substring(0, longVal.length() - trailingZeros) + "e" + trailingZeros;
        } else if (Math.abs(lvalue) > MAX_DECIMAL_VALUE) {
          // from 1e12 we may save 1 or 2 bytes by using the hex code
          longVal = (lvalue < 0 ? "-0x" : "0x") + Long.toString(Math.abs(lvalue), 16);
        }
      }
      return longVal;
    } else {
      String doubleVal = Double.toString(dvalue);
      if (minifyLiterals) {
        if (doubleVal.startsWith("0.")) {
          doubleVal = doubleVal.substring(1);
        } else if (doubleVal.startsWith("-0.")) {
          doubleVal = "-" + doubleVal.substring(2);
        }
      }
      return doubleVal;
    }
  }

  private int numberOfTrailingDecZeros(String longVal) {
    int idx = longVal.length() - 1;
    while (longVal.charAt(idx) == '0') {
      idx--;
    }
    return longVal.length() - idx - 1;
  }

  @Override
  public boolean visit(JsNumericEntry x, JsContext ctx) {
    p.print(Integer.toString(x.getValue()));
    return false;
  }

  @Override
  public boolean visit(JsObjectLiteral x, JsContext ctx) {
    _lbrace();
    boolean sep = false;
    for (JsPropertyInitializer element : x.getPropertyInitializers()) {
      sep = _sepCommaOptSpace(sep);
      accept(element.getLabelExpr());
      _colon();
      JsExpression valueExpr = element.getValueExpr();
      _parenPushIfCommaExpr(valueExpr);
      accept(valueExpr);
      _parenPopIfCommaExpr(valueExpr);
    }
    _rbrace();
    return false;
  }

  @Override
  public boolean visit(JsParameter x, JsContext ctx) {
    _nameOf(x);
    return false;
  }

  @Override
  public boolean visit(JsPostfixOperation x, JsContext ctx) {
    JsUnaryOperator op = x.getOperator();
    JsExpression arg = x.getArg();
    // unary operators always associate correctly (I think)
    _parenPush(x, arg, false);
    accept(arg);
    _parenPop(x, arg, false);
    p.print(op.getSymbol());
    return false;
  }

  @Override
  public boolean visit(JsPrefixOperation x, JsContext ctx) {
    JsUnaryOperator op = x.getOperator();
    p.print(op.getSymbol());
    JsExpression arg = x.getArg();
    if (_spaceCalc(op, arg)) {
      _space();
    }
    // unary operators always associate correctly (I think)
    _parenPush(x, arg, false);
    accept(arg);
    _parenPop(x, arg, false);
    return false;
  }

  @Override
  public boolean visit(JsProgram x, JsContext ctx) {
    p.print("<JsProgram>");
    return false;
  }

  @Override
  public boolean visit(JsProgramFragment x, JsContext ctx) {
    p.print("<JsProgramFragment>");
    return false;
  }

  @Override
  public boolean visit(JsPropertyInitializer x, JsContext ctx) {
    // Since there are separators, we actually print the property init
    // in visit(JsObjectLiteral).
    //
    return false;
  }

  @Override
  public boolean visit(JsRegExp x, JsContext ctx) {
    _slash();
    p.print(x.getPattern());
    _slash();
    String flags = x.getFlags();
    if (flags != null) {
      p.print(flags);
    }
    return false;
  }

  @Override
  public boolean visit(JsReturn x, JsContext ctx) {
    _return();
    JsExpression expr = x.getExpr();
    if (expr != null) {
      _printReturnExpression(expr);
    }
    return false;
  }

  @Override
  public boolean visit(JsStringLiteral x, JsContext ctx) {
    printStringLiteral(x.getValue());
    return false;
  }

  @Override
  public boolean visit(JsSwitch x, JsContext ctx) {
    _switch();
    _spaceOpt();
    _lparen();
    accept(x.getExpr());
    _rparen();
    _spaceOpt();
    _blockOpen();
    acceptList(x.getCases());
    _blockClose();
    return false;
  }

  @Override
  public boolean visit(JsThisRef x, JsContext ctx) {
    _this();
    return false;
  }

  @Override
  public boolean visit(JsThrow x, JsContext ctx) {
    _throw();
    _space();
    accept(x.getExpr());
    return false;
  }

  @Override
  public boolean visit(JsTry x, JsContext ctx) {
    _try();
    _spaceOpt();
    accept(x.getTryBlock());

    acceptList(x.getCatches());

    JsBlock finallyBlock = x.getFinallyBlock();
    if (finallyBlock != null) {
      _spaceOpt();
      _finally();
      _spaceOpt();
      accept(finallyBlock);
    }

    return false;
  }

  @Override
  public boolean visit(JsVar x, JsContext ctx) {
    _nameOf(x);
    JsExpression initExpr = x.getInitExpr();
    if (initExpr != null) {
      _spaceOpt();
      _assignment();
      _spaceOpt();
      _parenPushIfCommaExpr(initExpr);
      accept(initExpr);
      _parenPopIfCommaExpr(initExpr);
    }
    return false;
  }

  @Override
  public boolean visit(JsVars x, JsContext ctx) {
    switch (x.getVarKind()) {
      case LET:
        p.print(LET);
        break;
      case CONST:
        p.print(CONST);
        break;
      default:
        _var();
        break;
    }
    _space();
    boolean sep = false;
    for (JsVar var : x) {
      sep = _sepCommaOptSpace(sep);
      accept(var);
    }
    return false;
  }

  @Override
  public boolean visit(JsWhile x, JsContext ctx) {
    _while();
    _spaceOpt();
    _lparen();
    accept(x.getCondition());
    _rparen();
    _nestedPush(x.getBody(), false);
    accept(x.getBody());
    _nestedPop(x.getBody());
    return false;
  }

  @Override
  public boolean visit(JsYield x, JsContext ctx) {
    p.print(YIELD);
    if (x.isDelegating()) {
      p.print('*');
    }
    if (x.getExpression() != null) {
      _space();
      accept(x.getExpression());
    }
    return false;
  }

  @Override
  public boolean visit(JsSpread x, JsContext ctx) {
    p.print(SPREAD);
    accept(x.getExpression());
    return false;
  }

  @Override
  public boolean visit(JsRestParameter x, JsContext ctx) {
    p.print(SPREAD);
    _nameDef(x.getName());
    return false;
  }

  @Override
  public boolean visit(JsSuperRef x, JsContext ctx) {
    p.print(SUPER);
    return false;
  }

  @Override
  public boolean visit(JsTemplateLiteral x, JsContext ctx) {
    if (x.getTag() != null) {
      accept(x.getTag());
    }
    p.print('`');
    List<String> strings = x.getStringParts();
    List<JsExpression> exprs = x.getExpressionParts();
    for (int i = 0; i < strings.size(); i++) {
      // Print the raw string part (no escaping of backtick internals needed at AST level)
      p.print(strings.get(i));
      if (i < exprs.size()) {
        p.print("${");
        accept(exprs.get(i));
        p.print('}');
      }
    }
    p.print('`');
    return false;
  }

  protected void _newline() {
    p.newline();
  }

  protected void _newlineOpt() {
    p.newlineOpt();
  }

  /**
   * Adds any unbilled JavaScript to the most recently finished child node (if any).
   */
  protected void billChildToHere() {
  }

  protected void printJsBlock(JsBlock x, boolean truncate, boolean finalNewline) {
    boolean needBraces = !x.isGlobalBlock();

    if (needBraces) {
      // Open braces.
      //
      _blockOpen();
    }

    int count = 0;
    for (Iterator<JsStatement> iter = x.getStatements().iterator(); iter.hasNext(); ++count) {
      boolean isGlobal = x.isGlobalBlock() || globalBlocks.contains(x);

      if (truncate && count > JSBLOCK_LINES_TO_PRINT) {
        p.print("[...]");
        _newlineOpt();
        break;
      }
      JsStatement stmt = iter.next();
      needSemi = true;
      boolean shouldRecordPositions = isGlobal && stmt.shouldRecordPosition();
      boolean stmtIsGlobalBlock = false;
      if (isGlobal) {
        if (stmt instanceof JsBlock) {
          // A block inside a global block is still considered global
          stmtIsGlobalBlock = true;
          globalBlocks.add((JsBlock) stmt);
        }
      }
      if (shouldRecordPositions) {
        statementStarts.add(p.getPosition());
      }
      accept(stmt);
      if (stmtIsGlobalBlock) {
        globalBlocks.remove(stmt);
      }
      if (needSemi) {
        /*
         * Special treatment of function decls: If they are the only item in a
         * statement (i.e. not part of an assignment operation), just give them
         * a newline instead of a semi.
         */
        boolean functionStmt = stmt instanceof JsExprStmt
            && ((JsExprStmt) stmt).getExpression() instanceof JsFunction;
        /*
         * Special treatment of the last statement in a block: only a few
         * statements at the end of a block require semicolons.
         */
        boolean lastStatement = !iter.hasNext() && needBraces
            && !JsRequiresSemiVisitor.exec(stmt);
        if (functionStmt) {
          _newlineOpt();
        } else {
          if (lastStatement) {
            _semiOpt();
          } else {
            _semi();
          }
          _newlineOpt();
          billChildToHere();
        }
      }
      if (shouldRecordPositions) {
        assert (statementStarts.size() == statementEnds.size() + 1);
        statementEnds.add(p.getPosition());
      }
    }

    if (needBraces) {
      // _blockClose() modified
      p.indentOut();
      p.print('}');
      if (finalNewline) {
        _newlineOpt();
      }
    }
    needSemi = false;
  }

  private void _assignment() {
    p.print('=');
  }

  private void _blockClose() {
    p.indentOut();
    p.print('}');
    _newlineOpt();
  }

  private void _blockOpen() {
    p.print('{');
    p.indentIn();
    _newlineOpt();
  }

  private void _break() {
    p.print(BREAK);
  }

  private void _case() {
    p.print(CASE);
  }

  private void _catch() {
    p.print(CATCH);
  }

  private void _colon() {
    p.print(':');
  }

  private void _continue() {
    p.print(CONTINUE);
  }

  private void _debugger() {
    p.print(DEBUGGER);
  }

  private void _default() {
    p.print(DEFAULT);
  }

  private void _do() {
    p.print(DO);
  }

  private void _dot() {
    p.print('.');
  }

  private void _else() {
    p.print(ELSE);
  }

  private void _false() {
    p.print(FALSE);
  }

  private void _finally() {
    p.print(FINALLY);
  }

  private void _for() {
    p.print(FOR);
  }

  private void _function() {
    p.print(FUNCTION);
  }

  private void _if() {
    p.print(IF);
  }

  private void _in() {
    p.print(IN);
  }

  private void _lbrace() {
    p.print('{');
  }

  private void _lparen() {
    p.print('(');
  }

  private void _lsquare() {
    p.print('[');
  }

  private void _nameDef(JsName name) {
    if (useLongIdents) {
      p.print(name.getIdent());
    } else {
      p.print(name.getShortIdent());
    }
  }

  private void _nameOf(HasName hasName) {
    _nameDef(hasName.getName());
  }

  private void _nameRef(JsNameRef nameRef) {
    if (useLongIdents) {
      p.print(nameRef.getIdent());
    } else {
      p.print(nameRef.getShortIdent());
    }
  }

  private boolean _nestedPop(JsStatement statement) {
    boolean pop = !(statement instanceof JsBlock);
    if (pop) {
      p.indentOut();
    }
    return pop;
  }

  private boolean _nestedPush(JsStatement statement, boolean needSpace) {
    boolean push = !(statement instanceof JsBlock);
    if (push) {
      if (needSpace) {
        _space();
      }
      p.indentIn();
      _newlineOpt();
    } else {
      _spaceOpt();
    }
    return push;
  }

  private void _new() {
    p.print(NEW);
  }

  private void _null() {
    p.print(NULL);
  }

  private boolean _parenCalc(JsExpression parent, JsExpression child,
      boolean wrongAssoc) {
    int parentPrec = JsPrecedenceVisitor.exec(parent);
    int childPrec = JsPrecedenceVisitor.exec(child);
    if (emitArrowFunctions && child instanceof JsFunction && canEmitAsArrow((JsFunction) child)) {
      childPrec = 2; // arrow precedence
    }
    return (parentPrec > childPrec || (parentPrec == childPrec && wrongAssoc));
  }

  private boolean _parenPop(JsExpression parent, JsExpression child,
      boolean wrongAssoc) {
    boolean doPop = _parenCalc(parent, child, wrongAssoc);
    if (doPop) {
      _rparen();
    }
    return doPop;
  }

  private boolean _parenPopIfCommaExpr(JsExpression x) {
    boolean doPop = x instanceof JsBinaryOperation
        && ((JsBinaryOperation) x).getOperator() == JsBinaryOperator.COMMA;
    if (doPop) {
      _rparen();
    }
    return doPop;
  }

  private boolean _parenPopOrSpace(JsExpression parent, JsExpression child,
      boolean wrongAssoc) {
    boolean doPop = _parenCalc(parent, child, wrongAssoc);
    if (doPop) {
      _rparen();
    } else {
      _space();
    }
    return doPop;
  }

  private boolean _parenPush(JsExpression parent, JsExpression child,
      boolean wrongAssoc) {
    boolean doPush = _parenCalc(parent, child, wrongAssoc);
    if (doPush) {
      _lparen();
    }
    return doPush;
  }

  private boolean _parenPushIfCommaExpr(JsExpression x) {
    boolean doPush = x instanceof JsBinaryOperation
        && ((JsBinaryOperation) x).getOperator() == JsBinaryOperator.COMMA;
    if (doPush) {
      _lparen();
    }
    return doPush;
  }

  private boolean _parenPushOrSpace(JsExpression parent, JsExpression child,
      boolean wrongAssoc) {
    boolean doPush = _parenCalc(parent, child, wrongAssoc);
    if (doPush) {
      _lparen();
    } else {
      _space();
    }
    return doPush;
  }

  private void _questionMark() {
    p.print('?');
  }

  private void _rbrace() {
    p.print('}');
  }

  private void _return() {
    p.print(RETURN);
  }

  private void _rparen() {
    p.print(')');
  }

  private void _rsquare() {
    p.print(']');
  }

  private void _semi() {
    p.print(';');
    billChildToHere();
  }

  private void _semiOpt() {
    p.printOpt(';');
    billChildToHere();
  }

  private boolean _sepCommaOptSpace(boolean sep) {
    if (sep) {
      p.print(',');
      _spaceOpt();
    }
    return true;
  }

  private void _slash() {
    p.print('/');
  }

  private void _space() {
    p.print(' ');
  }

  private void _printReturnExpression(JsExpression arg) {
    boolean space = true;
    if (arg instanceof JsBooleanLiteral) {
      space = !minifyLiterals;
    } else if (arg instanceof JsPrefixOperation) {
      space = ((JsPrefixOperation) arg).getOperator().isKeyword();
    } else if (arg instanceof JsNumberLiteral) {
      String value = _stringifyNumber((JsNumberLiteral) arg);
      space = value.charAt(0) != '-' && value.charAt(0) != '.';
    }
    if (space) {
      _space();
    } else {
      _spaceOpt();
    }
    accept(arg);
  }

  /**
   * Decide whether, if <code>op</code> is printed followed by <code>arg</code>,
   * there needs to be a space between the operator and expression.
   *
   * @return <code>true</code> if a space needs to be printed
   */
  private boolean _spaceCalc(JsOperator op, JsExpression arg) {
    if (op.isKeyword()) {
      return true;
    }
    if (arg instanceof JsBinaryOperation) {
      JsBinaryOperation binary = (JsBinaryOperation) arg;
      /*
       * If the binary operation has a higher precedence than op, then it won't
       * be parenthesized, so check the first argument of the binary operation.
       */
      if (binary.getOperator().getPrecedence() > op.getPrecedence()) {
        return _spaceCalc(op, binary.getArg1());
      }
      return false;
    }
    if (arg instanceof JsPrefixOperation) {
      JsOperator op2 = ((JsPrefixOperation) arg).getOperator();
      return (op == JsBinaryOperator.SUB || op == JsUnaryOperator.NEG)
          && (op2 == JsUnaryOperator.DEC || op2 == JsUnaryOperator.NEG)
          || (op == JsBinaryOperator.ADD || op == JsUnaryOperator.POS)
          && (op2 == JsUnaryOperator.INC || op2 == JsUnaryOperator.POS);
    }
    if (arg instanceof JsNumberLiteral) {
      JsNumberLiteral literal = (JsNumberLiteral) arg;
      return (op == JsBinaryOperator.SUB || op == JsUnaryOperator.NEG)
          && (literal.getValue() < 0);
    }
    return false;
  }

  private void _spaceOpt() {
    p.printOpt(' ');
  }

  private void _switch() {
    p.print(SWITCH);
  }

  private void _this() {
    p.print(THIS);
  }

  private void _throw() {
    p.print(THROW);
  }

  private void _true() {
    p.print(TRUE);
  }

  private void _try() {
    p.print(TRY);
  }

  private void _var() {
    p.print(VAR);
  }

  private void _while() {
    p.print(WHILE);
  }

  private void indent() {
    p.indentIn();
  }

  private void outdent() {
    p.indentOut();
  }

  private void printStringLiteral(String value) {
    String resultString = StringUtils.javaScriptString(value);
    p.print(resultString);
  }
}
