/*
 * Copyright (c) 2023, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include "jni.h"
#include "jvmti_common.hpp"

extern "C" {

JNIEXPORT void JNICALL
Java_StressSuspendInCritical_criticalSection(JNIEnv* env, jclass cls, jbyteArray array, jint sleep_millis) {
  jboolean is_copy = JNI_FALSE;
  jbyte* elements = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(array, &is_copy));
  if (elements == nullptr) {
    env->FatalError("GetPrimitiveArrayCritical returned null");
  }

  elements[0] = static_cast<jbyte>(elements[0] + 1);
  if (sleep_millis > 0) {
    sleep_ms(sleep_millis);
  }

  env->ReleasePrimitiveArrayCritical(array, elements, 0);
}

} // extern C