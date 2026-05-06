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
package com.google.gwt.dev.util;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * An abstract base type to build TextOutput implementations.
 */
public abstract class AbstractTextOutput implements TextOutput {
  private final boolean compact;
  private int identLevel = 0;
  private int indentGranularity = 2;
  private char[][] indents = new char[][] {new char[0]};
  private boolean justNewlined;
  private PrintWriter out;
  private StringBuilder buf;
  private int position = 0;
  private int line = 0;
  private int column = 0;

  protected AbstractTextOutput(boolean compact) {
    this.compact = compact;
  }

  @Override
  public int getColumn() {
    return column;
  }

  @Override
  public int getLine() {
    return line;
  }

  @Override
  public int getPosition() {
    return position;
  }

  @Override
  public void indentIn() {
    ++identLevel;
    if (identLevel >= indents.length) {
      // Cache a new level of indentation string.
      //
      char[] newIndentLevel = new char[identLevel * indentGranularity];
      Arrays.fill(newIndentLevel, ' ');
      char[][] newIndents = new char[indents.length + 1][];
      System.arraycopy(indents, 0, newIndents, 0, indents.length);
      newIndents[identLevel] = newIndentLevel;
      indents = newIndents;
    }
  }

  @Override
  public void indentOut() {
    --identLevel;
  }

  @Override
  public void newline() {
    if (buf != null) {
      buf.append('\n');
    } else {
      out.print('\n');
    }
    position++;
    line++;
    column = 0;
    justNewlined = true;
  }

  @Override
  public void newlineOpt() {
    if (!compact) {
      newline();
    }
  }

  @Override
  public void print(char c) {
    maybeIndent();
    if (buf != null) {
      buf.append(c);
    } else {
      out.print(c);
    }
    position++;
    if (c == '\n') {
      line++;
      column = 0;
    } else {
      column++;
    }
    justNewlined = false;
  }

  @Override
  public void print(char[] s) {
    maybeIndent();
    printAndCount(s);
    justNewlined = false;
  }

  @Override
  public void print(String s) {
    maybeIndent();
    printAndCount(s);
    justNewlined = false;
  }

  @Override
  public void printOpt(char c) {
    if (!compact) {
      maybeIndent();
      if (buf != null) {
        buf.append(c);
      } else {
        out.print(c);
      }
      position += 1;
      if (c == '\n') {
        line++;
        column = 0;
      } else {
        column++;
      }
    }
  }

  @Override
  public void printOpt(char[] s) {
    if (!compact) {
      maybeIndent();
      printAndCount(s);
    }
  }

  @Override
  public void printOpt(String s) {
    if (!compact) {
      maybeIndent();
      printAndCount(s);
    }
  }

  protected void setPrintWriter(PrintWriter out) {
    this.out = out;
  }

  protected void setStringBuilder(StringBuilder buf) {
    this.buf = buf;
  }

  protected StringBuilder getStringBuilder() {
    return buf;
  }

  private void maybeIndent() {
    if (justNewlined && !compact) {
      printAndCount(indents[identLevel]);
      justNewlined = false;
    }
  }

  private void printAndCount(char[] chars) {
    position += chars.length;
    int lastNewline = -1;
    for (int i = 0; i < chars.length; i++) {
      if (chars[i] == '\n') {
        line++;
        lastNewline = i;
      }
    }
    if (lastNewline >= 0) {
      column = chars.length - lastNewline - 1;
    } else {
      column += chars.length;
    }
    if (buf != null) {
      buf.append(chars);
    } else {
      out.print(chars);
    }
  }

  private void printAndCount(String str) {
    int len = str.length();
    position += len;
    int lastNewline = -1;
    for (int i = 0; i < len; i++) {
      if (str.charAt(i) == '\n') {
        line++;
        lastNewline = i;
      }
    }
    if (lastNewline >= 0) {
      column = len - lastNewline - 1;
    } else {
      column += len;
    }
    if (buf != null) {
      buf.append(str);
    } else {
      out.print(str);
    }
  }
}
