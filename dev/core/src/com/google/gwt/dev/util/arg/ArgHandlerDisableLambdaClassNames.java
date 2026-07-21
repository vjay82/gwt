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
package com.google.gwt.dev.util.arg;

import com.google.gwt.util.tools.ArgHandlerFlag;

/**
 * Controls whether class names are generated for lambda and method-reference classes.
 */
public class ArgHandlerDisableLambdaClassNames extends ArgHandlerFlag {

  private final OptionDisableLambdaClassNames option;

  public ArgHandlerDisableLambdaClassNames(OptionDisableLambdaClassNames option) {
    this.option = option;

    addTagValue("-disableLambdaClassNames", false);
  }

  @Override
  public String getPurposeSnippet() {
    return "Generate class names (e.g. for Class.getName()) for lambda and method-reference "
        + "classes rather than empty strings.";
  }

  @Override
  public String getLabel() {
    return "lambdaClassNames";
  }

  @Override
  public boolean setFlag(boolean value) {
    option.setLambdaClassNamesDisabled(!value);
    return true;
  }

  @Override
  public boolean isExperimental() {
    return false;
  }

  @Override
  public boolean getDefaultValue() {
    return !option.isLambdaClassNamesDisabled();
  }
}
