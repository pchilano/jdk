#include <stdio.h>
#include <string.h>
#include "jvmti.h"
#include "jvmti_common.h"

extern "C" {


#define PASSED 0
#define STATUS_FAILED 2

static jvmtiEnv *jvmti_env = NULL;
static jvmtiEventCallbacks callbacks;
static jint result = PASSED;

void JNICALL
Exception(jvmtiEnv *jvmti, JNIEnv *jni, jthread thr,
          jmethodID method, jlocation location, jobject exception,
          jmethodID catch_method, jlocation catch_location) {
  LOG(">>> retrieving Exception info ...\n");
}

jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jvmtiError err;
  jint res;
  jvmtiCapabilities caps;

  res = jvm->GetEnv((void **) &jvmti_env, JVMTI_VERSION_1_1);
  if (res != JNI_OK || jvmti_env == NULL) {
    LOG("Wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }

  memset(&caps, 0, sizeof(jvmtiCapabilities));
  caps.can_generate_exception_events = 1;

  err = jvmti_env->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    LOG("(AddCapabilities) unexpected error: %s (%d)\n", TranslateError(err), err);
    return JNI_ERR;
  }

  err = jvmti_env->GetCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    LOG("(GetCapabilities) unexpected error: %s (%d)\n", TranslateError(err), err);
    return JNI_ERR;
  }

  if (caps.can_generate_exception_events) {
    callbacks.Exception = &Exception;
    err = jvmti_env->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (err != JVMTI_ERROR_NONE) {
      LOG("(SetEventCallbacks) unexpected error: %s (%d)\n", TranslateError(err), err);
      return JNI_ERR;
    }
  } else {
    LOG("Warning: Exception event is not implemented\n");
  }

  return JNI_OK;
}

JNIEXPORT jint JNICALL
Java_exception02_enableEvent(JNIEnv *jni, jclass cls) {
  jvmtiError err;
  jthread thread;

  if (jvmti_env == NULL) {
    LOG("JVMTI client was not properly loaded!\n");
    return STATUS_FAILED;
  }

  err = jvmti_env->GetCurrentThread(&thread);
  if (err != JVMTI_ERROR_NONE) {
    LOG("Failed to get current thread: %s (%d)\n", TranslateError(err), err);
    result = STATUS_FAILED;
    return STATUS_FAILED;
  }

  err = jvmti_env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_EXCEPTION, thread);
  if (err == JVMTI_ERROR_NONE) {
  } else {
    LOG("Failed to enable JVMTI_EVENT_EXCEPTION: %s (%d)\n", TranslateError(err), err);
    result = STATUS_FAILED;
  }
  return result;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}


}
