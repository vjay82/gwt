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

package com.google.gwt.core.linker;

import com.google.gwt.core.ext.LinkerContext;

/**
 * A linker that installs compiled JavaScript directly into the main browser window
 * instead of using a hidden iframe. The script is loaded via a plain {@code <script src>}
 * tag without the {@code onScriptDownloaded} string-wrapping mechanism.
 *
 * <p>This is suitable for single-page applications that own the host page and do not
 * need variable isolation from other scripts. It supports code splitting
 * ({@code GWT.runAsync}).
 *
 * <p>Usage in your {@code .gwt.xml}:
 * <pre>
 *   &lt;add-linker name="main_window" /&gt;
 * </pre>
 */
public class CrossSiteMainWindowLinker extends CrossSiteIframeLinker {

  @Override
  public String getDescription() {
    return "Main Window";
  }

  @Override
  protected String getJsInstallLocation(LinkerContext context) {
    return "com/google/gwt/core/ext/linker/impl/installLocationMainWindow.js";
  }

  @Override
  protected boolean shouldInstallCode(LinkerContext context) {
    return false;
  }
}
