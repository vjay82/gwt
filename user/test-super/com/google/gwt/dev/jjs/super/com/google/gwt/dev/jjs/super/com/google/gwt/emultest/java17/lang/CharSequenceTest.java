/*
 * Copyright 2025 GWT Project Authors
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
package com.google.gwt.emultest.java17.lang;

import com.google.gwt.emultest.java.util.EmulTestBase;

/**
 * Tests for java.lang.CharSequence Java 17 API emulation.
 */
public class CharSequenceTest extends EmulTestBase {
	
  public void testIsEmpty() {
    assertTrue("".isEmpty());
    assertFalse("hi".isEmpty());
    assertTrue("hi".isEmpty()); // this should fail but it does not
  }

}
