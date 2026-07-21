/*
 * Copyright 2009 Google Inc.
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
package com.google.gwt.dev.jjs.impl;

import com.google.gwt.core.ext.soyc.Range;
import com.google.gwt.dev.jjs.JsSourceMap;
import com.google.gwt.thirdparty.guava.common.collect.Lists;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Re-orders function declarations according to a given metric and clustering
 * algorithm in order to boost gzip/deflation compression efficiency.
 *
 * <p>Uses SimHash on character trigrams as a fast similarity metric instead of
 * expensive Levenshtein edit-distance, and ArrayList instead of LinkedList for
 * better cache locality and O(1) indexed access.
 */
public class JsFunctionClusterer extends JsAbstractTextTransformer {

  /**
   * Used by isFunctionDeclaration to check a statement is a function
   * declaration or not. This should match standard declarations, such as
   * "function a() { ... }" and "jslink.a=function() { ... }". The latter form
   * is typically emitted by the cross-site linker.
   */
  private static final Pattern functionDeclarationPattern = Pattern
      .compile("function |[a-zA-Z][.$_a-zA-Z0-9]*=function");

  /**
   * Maximum number of functions to search for minimal distance before
   * giving up.
   */
  private static final int SEARCH_LIMIT = 10;

  /**
   * Tells whether a statement is a function declaration or not.
   */
  private static boolean isFunctionDeclaration(String code) {
    return functionDeclarationPattern.matcher(code).lookingAt();
  }

  /**
   * Compute a SimHash fingerprint for a string based on character trigrams.
   * Similar strings produce fingerprints with low Hamming distance.
   */
  private static long computeSimHash(String code) {
    int[] bitCounts = new int[64];
    int len = code.length();
    for (int i = 0; i <= len - 3; i++) {
      // Hash each trigram
      long h = code.charAt(i) * 31L * 31L + code.charAt(i + 1) * 31L + code.charAt(i + 2);
      // Mix the bits (based on a multiplicative hash)
      h *= 0x9E3779B97F4A7C15L;
      h ^= (h >>> 33);
      h *= 0xFF51AFD7ED558CCDL;
      h ^= (h >>> 33);
      for (int bit = 0; bit < 64; bit++) {
        if ((h & (1L << bit)) != 0) {
          bitCounts[bit]++;
        } else {
          bitCounts[bit]--;
        }
      }
    }
    long hash = 0;
    for (int bit = 0; bit < 64; bit++) {
      if (bitCounts[bit] > 0) {
        hash |= (1L << bit);
      }
    }
    return hash;
  }

  /**
   * Hamming distance between two SimHash fingerprints.
   * Lower distance indicates more similar strings.
   */
  private static int simHashDistance(long hash1, long hash2) {
    return Long.bitCount(hash1 ^ hash2);
  }

  /**
   * Number of function declarations found.
   */
  private int numFunctions;

  /**
   * The statement indices after clustering. The element at index j represents
   * the index of the statement in the original code that is moved to index j
   * in the new code after clustering.
   */
  private int[] reorderedIndices;

  public JsFunctionClusterer(JsAbstractTextTransformer xformer) {
    super(xformer);
  }

  @Override
  public void exec() {
    ArrayList<Integer> functionIndices = new ArrayList<Integer>();

    // gather up all of the indices of function decl statements
    for (int i = 0; i < statementRanges.numStatements(); i++) {
      String code = getJsForRange(i);
      if (isFunctionDeclaration(code)) {
        functionIndices.add(i);
      }
    }

    numFunctions = functionIndices.size();

    if (functionIndices.size() < 2) {
      // No need to sort 0 or 1 functions.
      return;
    }

    // sort the indices according to size of statement range
    Collections.sort(functionIndices, new Comparator<Integer>() {
      @Override
      public int compare(Integer index1, Integer index2) {
        return stmtSize(index1) - (stmtSize(index2));
      }
    });

    // Pre-compute SimHash fingerprints for all function bodies
    Map<Integer, Long> simHashes = new HashMap<Integer, Long>();
    for (int idx : functionIndices) {
      simHashes.put(idx, computeSimHash(getJsForRange(idx)));
    }

    // used to hold the new output order
    int[] clusteredIndices = new int[functionIndices.size()];
    int currentFunction = 0;

    // remove the first function and stick it in the output array
    clusteredIndices[currentFunction] = functionIndices.get(0);
    functionIndices.remove(0);
    while (!functionIndices.isEmpty()) {
      // get the SimHash of the last outputted function
      long currentHash = simHashes.get(clusteredIndices[currentFunction]);

      int bestIndex = 0;
      int bestFunction = functionIndices.get(0);
      int bestDistance = Integer.MAX_VALUE;

      int count = 0;
      int limit = Math.min(SEARCH_LIMIT, functionIndices.size());
      for (int fi = 0; fi < limit; fi++) {
        int functionIndex = functionIndices.get(fi);
        int dist = simHashDistance(currentHash, simHashes.get(functionIndex));
        if (dist < bestDistance) {
          bestDistance = dist;
          bestIndex = fi;
          bestFunction = functionIndex;
        }
        count++;
      }
      // output the best match and remove it from worklist of functions
      currentFunction++;
      clusteredIndices[currentFunction] = bestFunction;
      functionIndices.remove(bestIndex);
    }

    reorderedIndices = Arrays.copyOf(clusteredIndices, statementRanges.numStatements());
    recomputeJsAndStatementRanges(clusteredIndices);
  }

  @Override
  protected void endStatements(StringBuilder newJs, ArrayList<Integer> starts,
      ArrayList<Integer> ends) {
    int j = numFunctions;
    // Then output everything else that is not a function.
    for (int i = 0; i < statementRanges.numStatements(); i++) {
      String code = getJsForRange(i);
      if (!isFunctionDeclaration(code)) {
        addStatement(j, code, newJs, starts, ends);
        reorderedIndices[j] = i;
        j++;
      }
    }
    super.endStatements(newJs, starts, ends);
  }

  /**
   * Fixes the index ranges of individual expressions in the generated
   * JS after function clustering has reordered statements. Loops over
   * each expression, determines which statement it falls in, and shifts
   * the indices according to where that statement moved.
   */
  @Override
  protected void updateSourceInfoMap() {
    if (sourceInfoMap != null) {
      // create mapping of statement ranges
      Map<Range, Range> statementShifts = new HashMap<Range, Range>();
      for (int j = 0; j < statementRanges.numStatements(); j++) {
        int permutedStart = statementRanges.start(j);
        int permutedEnd = statementRanges.end(j);
        int originalStart = originalStatementRanges.start(reorderedIndices[j]);
        int originalEnd = originalStatementRanges.end(reorderedIndices[j]);

        statementShifts.put(new Range(originalStart, originalEnd),
            new Range(permutedStart, permutedEnd));
      }

      Range[] oldStatementRanges = statementShifts.keySet().toArray(new Range[0]);
      Arrays.sort(oldStatementRanges, Range.SOURCE_ORDER_COMPARATOR);

      List<Range> oldExpressionRanges = Lists.newArrayList(sourceInfoMap.getRanges());
      Collections.sort(oldExpressionRanges, Range.SOURCE_ORDER_COMPARATOR);

      // iterate over expression ranges and shift
      List<Range> updatedRanges = Lists.newArrayList();
      Range entireProgram =
          new Range(0, oldStatementRanges[oldStatementRanges.length - 1].getEnd());
      for (int i = 0, j = 0; j < oldExpressionRanges.size(); j++) {
        Range oldExpressionRange = oldExpressionRanges.get(j);
        if (oldExpressionRange.equals(entireProgram)) {
          updatedRanges.add(oldExpressionRange);
          continue;
        }

        // Both the statement ranges and the expression ranges are sorted in source
        // order, and every expression is nested within exactly one statement. Walk the
        // statements forward in lock-step with the expressions until we reach the
        // statement that contains the current expression, then shift the expression by
        // the amount that statement moved during clustering. Without advancing 'i' every
        // expression would be shifted by the first statement's delta, which leaves the
        // ranges of any relocated function (e.g. lambda bodies) pointing at the byte
        // offsets they occupied before clustering -- producing wrong Java line numbers in
        // the source map for OBFUSCATED output.
        while (i < oldStatementRanges.length - 1
            && oldExpressionRange.getStart() >= oldStatementRanges[i].getEnd()) {
          i++;
        }

        Range oldStatement = oldStatementRanges[i];
        Range newStatement = statementShifts.get(oldStatement);
        int shift = newStatement.getStart() - oldStatement.getStart();

        Range newExpressionRange = new Range(oldExpressionRange.getStart() + shift,
            oldExpressionRange.getEnd() + shift, oldExpressionRange.getSourceInfo());
        updatedRanges.add(newExpressionRange);
      }

      sourceInfoMap =
          new JsSourceMap(updatedRanges, sourceInfoMap.getBytes(), sourceInfoMap.getLines());
    }
  }

  private int stmtSize(int index1) {
    return statementRanges.end(index1) - statementRanges.start(index1);
  }
}
