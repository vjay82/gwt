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
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.linker.ArtifactSet;
import com.google.gwt.core.ext.linker.Shardable;

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
@Shardable
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

  @Override
  protected String getModulePrefix(TreeLogger logger, LinkerContext context, String strongName)
      throws UnableToCompleteException {
    return super.getModulePrefix(logger, context, strongName)
        .replace("var $wnd = $wnd || window.parent;", ""); // already defined by loader, also window.parent would not be correct
  }

  @Override
  protected String getJsComputeScriptBase(LinkerContext context) {
    return "function computeScriptBase() {\n"
        + "  if (typeof $module_base === 'string' && $module_base.length > 0) {\n"
        + "    return $module_base;\n"
        + "  }\n"
        + "  var scripts = $doc.getElementsByTagName('script');\n"
        + "  for (var i = 0; i < scripts.length; i++) {\n"
        + "    var src = scripts[i].src || '';\n"
        + "    var idx = src.indexOf('__MODULE_NAME__.');\n"
        + "    if (idx >= 0) {\n"
        + "      return src.substring(0, idx);\n"
        + "    }\n"
        + "  }\n"
        + "  return '';\n"
        + "}\n";
  }

  /**
   * In the final linking pass, also emits {@code <moduleName>.embed.nocache.js} —
   * the bootstrap/loader script prefixed with a patchable {@code $module_base} variable.
   * This file can be inlined in the host HTML to save one round trip.
   */
  @Override
  public ArtifactSet link(TreeLogger logger, LinkerContext context,
      ArtifactSet artifacts, boolean onePermutation)
      throws UnableToCompleteException {

    ArtifactSet toReturn = super.link(logger, context, artifacts, onePermutation);

    if (!onePermutation) {
      // Final pass — the nocache.js selection script has been generated.
      // Emit an embeddable copy with a patchable $module_base variable.
      String selectionScript = generateSelectionScript(logger, context, artifacts);
      String embed = "var $module_base = 'PATCH_PATH_HERE';" + selectionScript;
      toReturn = new ArtifactSet(toReturn);
      toReturn.add(emitString(logger, embed,
          context.getModuleName() + ".embed.nocache.js",
          System.currentTimeMillis()));
    }

    return toReturn;
  }
}
