/*
 * Copyright (c) 1997, 2020, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

#ifndef SHARE_RUNTIME_INTERFACESUPPORT_INLINE_HPP
#define SHARE_RUNTIME_INTERFACESUPPORT_INLINE_HPP

#include "runtime/handles.inline.hpp"
#include "runtime/mutexLocker.hpp"
#include "runtime/orderAccess.hpp"
#include "runtime/os.hpp"
#include "runtime/safepointMechanism.inline.hpp"
#include "runtime/safepointVerifiers.hpp"
#include "runtime/thread.hpp"
#include "runtime/vmOperations.hpp"
#include "utilities/globalDefinitions.hpp"
#include "utilities/histogram.hpp"
#include "utilities/macros.hpp"
#include "utilities/preserveException.hpp"

// Wrapper for all entry points to the virtual machine.

// InterfaceSupport provides functionality used by the VM_LEAF_BASE and
// VM_ENTRY_BASE macros. These macros are used to guard entry points into
// the VM and perform checks upon leave of the VM.


class InterfaceSupport: AllStatic {
# ifdef ASSERT
 public:
  static unsigned int _scavenge_alot_counter;
  static unsigned int _fullgc_alot_counter;
  static int _fullgc_alot_invocation;

  // Helper methods used to implement +ScavengeALot and +FullGCALot
  static void check_gc_alot() { if (ScavengeALot || FullGCALot) gc_alot(); }
  static void gc_alot();

  static void walk_stack_from(vframe* start_vf);
  static void walk_stack();

  static void zombieAll();
  static void deoptimizeAll();
  static void verify_stack();
  static void verify_last_frame();
# endif
};



// Basic class for all thread transition classes.
// To  \  From  ||   java    |          native           |           vm             |          blocked          |   new    |
// -------------||-----------|---------------------------|--------------------------|---------------------------|----------|
//              ||           |    safepoint/handshakes   |   safepoint/handshakes   |                           |          |
//    java      ||    XXX    |     suspend/resume        |     suspend/resume       |            XXX            |   XXX    |
//              ||           |       JFR sampling        |      JFR sampling        |                           |          |
//              ||           |     async exceptions      |     async exceptions     |                           |          |
// -------------||-----------|---------------------------|--------------------------|---------------------------|----------|
//              ||           |                           |                          |                           |          |
//    native    ||   None    |           XXX             |          None            |            XXX            |   XXX    |
//              ||           |                           |                          |                           |          |
// -------------||-----------|---------------------------|--------------------------|---------------------------|----------|
//              ||           |                           |                          |                           |          |
//      vm      ||   None    |    safepoint/handshakes   |           XXX            |    safepoint/handshakes   |   None   |
//              ||           |                           |                          |                           |          |
// -------------||-----------|---------------------------|--------------------------|---------------------------|----------|
//    blocked   ||    XXX    |           XXX             |          None            |            XXX            |   XXX    |
// -------------||-----------|---------------------------|--------------------------|---------------------------|----------|

class TransitionFromSafeToVM {
 public:
  static inline void trans(JavaThread *thread, JavaThreadState from, JavaThreadState to) {
    assert(from == _thread_in_native || from == _thread_blocked, "Must be");
    assert(to == _thread_in_vm, "Must be");

    // Check NoSafepointVerifier
    // This also clears unhandled oops if CheckUnhandledOops is used.
    thread->check_possible_safepoint();

    thread->set_thread_state_fence(to);
    SafepointMechanism::process_if_requested(thread);
  }
};

class TransitionFromUnsafe {
 public:
  static inline void trans(JavaThread *thread, JavaThreadState from, JavaThreadState to) {
    assert(from != _thread_in_native && from != _thread_blocked, "Must be");
    assert(to != _thread_in_Java || thread->safepoint_state()->is_at_poll_safepoint(), "Should use TransitionFromVMToJava");
    assert(thread->is_Compiler_thread() || !thread->owns_locks() || to != _thread_in_native, "must release all locks when leaving VM");
    if (from == _thread_in_Java) {
      assert(to == _thread_in_native || to == _thread_in_vm, "Must be");
      thread->frame_anchor()->make_walkable(thread);
    } else {
      assert(!thread->has_last_Java_frame() || thread->frame_anchor()->walkable(), "must be walkable");
    }
    thread->set_thread_state(to);
  }
};

template<bool ASYNC>
class TransitionFromVMToJava {
 public:
  static inline void trans(JavaThread *thread, JavaThreadState from, JavaThreadState to) {
    assert(thread->thread_state() == _thread_in_vm, "coming from wrong thread state");
    if (thread->stack_overflow_state()->stack_yellow_reserved_zone_disabled()) {
      thread->stack_overflow_state()->enable_stack_yellow_reserved_zone();
    }
    // Check NoSafepointVerifier
    // This also clears unhandled oops if CheckUnhandledOops is used.
    thread->check_possible_safepoint();

    SafepointMechanism::process_if_requested_with_exit_check(thread, ASYNC);
    thread->set_thread_state(to);
  }
};

template<bool ASYNC = true>
class ThreadStateTransitionBase {
 public:
  static void transition(JavaThread *thread, JavaThreadState from, JavaThreadState to) {
    if (to == _thread_in_Java) {
      TransitionFromVMToJava<ASYNC>::trans(thread, from, to);
      return;
    }
    if (is_unsafe(from)) {
      TransitionFromUnsafe::trans(thread, from, to);
      return;
    }
    if (is_safe(from) && to == _thread_in_vm) {
      TransitionFromSafeToVM::trans(thread, from, to);
      return;
    }
    fatal("Illegal state change");
  }

  static bool is_safe(JavaThreadState state) {
    return state == _thread_in_native || state == _thread_blocked;
  }
  static bool is_unsafe(JavaThreadState state) {
    return !is_safe(state);
  }
};

template<JavaThreadState JTS_FROM, JavaThreadState JTS_TO, bool ASYNC = true>
class ThreadStateTransition : public ThreadStateTransitionBase<ASYNC> {
 protected:
  JavaThread* _thread;
 public:
  ThreadStateTransition(JavaThread* thread) : _thread(thread) {
    ThreadStateTransitionBase<ASYNC>::transition(_thread, JTS_FROM, JTS_TO);
  }
  ~ThreadStateTransition() {
    ThreadStateTransitionBase<ASYNC>::transition(_thread, JTS_TO, JTS_FROM);
  }
};

template<JavaThreadState JTS_FROM, JavaThreadState JTS_TO>
class ThreadStateTransitionHandleMark : public ThreadStateTransition<JTS_FROM, JTS_TO, false> {
 private:
  HandleMark _hm;
 public:
  ThreadStateTransitionHandleMark(JavaThread* thread) : ThreadStateTransition<JTS_FROM, JTS_TO, false>::ThreadStateTransition(thread), _hm(thread) { }
  ~ThreadStateTransitionHandleMark() {}
 // { ThreadStateTransition<JTS_FROM, JTS_TO, false>::transition(ThreadStateTransition<JTS_FROM, JTS_TO, false>::_thread, JTS_TO, JTS_FROM); }
};

typedef           ThreadStateTransition<_thread_in_Java,   _thread_in_vm     >       ThreadInVMfromJava;
typedef           ThreadStateTransition<_thread_in_Java,   _thread_in_vm,    false>  ThreadInVMfromJavaNoAsyncException;
typedef           ThreadStateTransition<_thread_in_native, _thread_in_vm     >       ThreadInVMfromNative;
typedef           ThreadStateTransition<_thread_in_vm,     _thread_blocked   >       ThreadBlockInVM;
typedef ThreadStateTransitionHandleMark<_thread_in_vm,     _thread_in_native >       ThreadToNativeFromVM;

template<JavaThreadState JTS_TO, bool ASYNC = false>
class ThreadInStatefromUnknown {
  JavaThread*     _thread;
  JavaThreadState _state;
 public:
  ThreadInStatefromUnknown() : _thread(NULL) {
    Thread* thread = Thread::current();
    if (thread == NULL || !thread->is_Java_thread()) {
      return;
    }
    JavaThread* jt = thread->as_Java_thread(); 
    _state = jt->thread_state();
    if (_state == JTS_TO) {
      return;
    }
    _thread = jt;
    ThreadStateTransitionBase<ASYNC>::transition(_thread, _state, JTS_TO);
  }
  ~ThreadInStatefromUnknown()  {
    if (_thread) {
      ThreadStateTransitionBase<ASYNC>::transition(_thread, JTS_TO, _state);
    }
  }
};

typedef ThreadInStatefromUnknown<_thread_in_vm> ThreadInVMfromUnknown;

template<JavaThreadState JTS_TO, bool ASYNC = false>
class ThreadInStatefromUnknownHandleMark : public ThreadInStatefromUnknown<JTS_TO, ASYNC> {
 private:
  HandleMark _hm;
 public:
  ThreadInStatefromUnknownHandleMark(JavaThread* thread) : _hm(thread) { }
  ~ThreadInStatefromUnknownHandleMark() { }
};

typedef ThreadInStatefromUnknownHandleMark<_thread_in_native> JvmtiThreadEventTransition;

// Unlike ThreadBlockInVM, this class is designed to avoid certain deadlock scenarios while making
// transitions inside class Mutex in cases where we need to block for a safepoint or handshake. It
// receives an extra argument compared to ThreadBlockInVM, the address of a pointer to the mutex we
// are trying to acquire. This will be used to access and release the mutex if needed to avoid
// said deadlocks.
// It works like ThreadBlockInVM but differs from it in two ways:
// - When transitioning in (constructor), it checks for safepoints without blocking, i.e., calls
//   back if needed to allow a pending safepoint to continue but does not block in it.
// - When transitioning back (destructor), if there is a pending safepoint or handshake it releases
//   the mutex that is only partially acquired.
class ThreadBlockInVMWithDeadlockCheck {
 private:
  JavaThread* _thread;
  Mutex** _in_flight_mutex_addr;

  void release_mutex() {
    assert(_in_flight_mutex_addr != NULL, "_in_flight_mutex_addr should have been set on constructor");
    Mutex* in_flight_mutex = *_in_flight_mutex_addr;
    if (in_flight_mutex != NULL) {
      in_flight_mutex->release_for_safepoint();
      *_in_flight_mutex_addr = NULL;
    }
  }
 public:
  ThreadBlockInVMWithDeadlockCheck(JavaThread* thread, Mutex** in_flight_mutex_addr)
  : _thread(thread), _in_flight_mutex_addr(in_flight_mutex_addr) {
    // All unsafe states are treated the same by the VMThread
    // so we can skip the _thread_in_vm_trans state here. Since
    // we don't read poll, it's enough to order the stores.
    OrderAccess::storestore();

    thread->set_thread_state(_thread_blocked);
  }
  ~ThreadBlockInVMWithDeadlockCheck() {
    // Change to transition state and ensure it is seen by the VM thread.
    _thread->set_thread_state_fence(_thread_in_vm);

    if (SafepointMechanism::should_process(_thread)) {
      release_mutex();
      SafepointMechanism::process_if_requested(_thread);
    }
  }
};



// Debug class instantiated in JRT_ENTRY macro.
// Can be used to verify properties on enter/exit of the VM.

#ifdef ASSERT
class VMEntryWrapper {
 public:
  VMEntryWrapper();
  ~VMEntryWrapper();
};


class VMNativeEntryWrapper {
 public:
  VMNativeEntryWrapper();
  ~VMNativeEntryWrapper();
};

class RuntimeHistogramElement : public HistogramElement {
  public:
   RuntimeHistogramElement(const char* name);
};
#endif // ASSERT

#ifdef ASSERT
#define TRACE_CALL(result_type, header)                            \
  if (CountRuntimeCalls) {                                         \
    static RuntimeHistogramElement* e = new RuntimeHistogramElement(#header); \
    if (e != NULL) e->increment_count();                           \
  }
#else
#define TRACE_CALL(result_type, header)                            \
  /* do nothing */
#endif // ASSERT


// LEAF routines do not lock, GC or throw exceptions

#define VM_LEAF_BASE(result_type, header)                            \
  TRACE_CALL(result_type, header)                                    \
  debug_only(NoHandleMark __hm;)                                     \
  os::verify_stack_alignment();                                      \
  /* begin of body */

#define VM_ENTRY_BASE_FROM_LEAF(result_type, header, thread)         \
  TRACE_CALL(result_type, header)                                    \
  debug_only(ResetNoHandleMark __rnhm;)                              \
  HandleMarkCleaner __hm(thread);                                    \
  Thread* THREAD = thread;                                           \
  os::verify_stack_alignment();                                      \
  /* begin of body */


// ENTRY routines may lock, GC and throw exceptions

#define VM_ENTRY_BASE(result_type, header, thread)                   \
  TRACE_CALL(result_type, header)                                    \
  HandleMarkCleaner __hm(thread);                                    \
  Thread* THREAD = thread;                                           \
  os::verify_stack_alignment();                                      \
  /* begin of body */


#define JRT_ENTRY(result_type, header)                               \
  result_type header {                                               \
    ThreadInVMfromJava __tiv(thread);                                \
    VM_ENTRY_BASE(result_type, header, thread)                       \
    debug_only(VMEntryWrapper __vew;)

// JRT_LEAF currently can be called from either _thread_in_Java or
// _thread_in_native mode.
//
// JRT_LEAF rules:
// A JRT_LEAF method may not interfere with safepointing by
//   1) acquiring or blocking on a Mutex or JavaLock - checked
//   2) allocating heap memory - checked
//   3) executing a VM operation - checked
//   4) executing a system call (including malloc) that could block or grab a lock
//   5) invoking GC
//   6) reaching a safepoint
//   7) running too long
// Nor may any method it calls.

#define JRT_LEAF(result_type, header)                                \
  result_type header {                                               \
  VM_LEAF_BASE(result_type, header)                                  \
  debug_only(NoSafepointVerifier __nsv;)


#define JRT_ENTRY_NO_ASYNC(result_type, header)                      \
  result_type header {                                               \
    ThreadInVMfromJavaNoAsyncException __tiv(thread);                \
    VM_ENTRY_BASE(result_type, header, thread)                       \
    debug_only(VMEntryWrapper __vew;)

// Same as JRT Entry but allows for return value after the safepoint
// to get back into Java from the VM
#define JRT_BLOCK_ENTRY(result_type, header)                         \
  result_type header {                                               \
    TRACE_CALL(result_type, header)                                  \
    HandleMarkCleaner __hm(thread);

#define JRT_BLOCK                                                    \
    {                                                                \
    ThreadInVMfromJava __tiv(thread);                                \
    Thread* THREAD = thread;                                         \
    debug_only(VMEntryWrapper __vew;)

#define JRT_BLOCK_NO_ASYNC                                           \
    {                                                                \
    ThreadInVMfromJavaNoAsyncException __tiv(thread);                \
    Thread* THREAD = thread;                                         \
    debug_only(VMEntryWrapper __vew;)

#define JRT_BLOCK_END }

#define JRT_END }

// Definitions for JNI

#define JNI_ENTRY(result_type, header)                               \
    JNI_ENTRY_NO_PRESERVE(result_type, header)                       \
    WeakPreserveExceptionMark __wem(thread);

#define JNI_ENTRY_NO_PRESERVE(result_type, header)                   \
extern "C" {                                                         \
  result_type JNICALL header {                                       \
    JavaThread* thread=JavaThread::thread_from_jni_environment(env); \
    assert( !VerifyJNIEnvThread || (thread == Thread::current()), "JNIEnv is only valid in same thread"); \
    ThreadInVMfromNative __tiv(thread);                              \
    debug_only(VMNativeEntryWrapper __vew;)                          \
    VM_ENTRY_BASE(result_type, header, thread)


#define JNI_LEAF(result_type, header)                                \
extern "C" {                                                         \
  result_type JNICALL header {                                       \
    JavaThread* thread=JavaThread::thread_from_jni_environment(env); \
    assert( !VerifyJNIEnvThread || (thread == Thread::current()), "JNIEnv is only valid in same thread"); \
    VM_LEAF_BASE(result_type, header)


// Close the routine and the extern "C"
#define JNI_END } }



// Definitions for JVM

#define JVM_ENTRY(result_type, header)                               \
extern "C" {                                                         \
  result_type JNICALL header {                                       \
    JavaThread* thread=JavaThread::thread_from_jni_environment(env); \
    ThreadInVMfromNative __tiv(thread);                              \
    debug_only(VMNativeEntryWrapper __vew;)                          \
    VM_ENTRY_BASE(result_type, header, thread)


#define JVM_ENTRY_NO_ENV(result_type, header)                        \
extern "C" {                                                         \
  result_type JNICALL header {                                       \
    JavaThread* thread = JavaThread::current();                      \
    ThreadInVMfromNative __tiv(thread);                              \
    debug_only(VMNativeEntryWrapper __vew;)                          \
    VM_ENTRY_BASE(result_type, header, thread)


#define JVM_LEAF(result_type, header)                                \
extern "C" {                                                         \
  result_type JNICALL header {                                       \
    VM_Exit::block_if_vm_exited();                                   \
    VM_LEAF_BASE(result_type, header)


#define JVM_ENTRY_FROM_LEAF(env, result_type, header)                \
  { {                                                                \
    JavaThread* thread=JavaThread::thread_from_jni_environment(env); \
    ThreadInVMfromNative __tiv(thread);                              \
    debug_only(VMNativeEntryWrapper __vew;)                          \
    VM_ENTRY_BASE_FROM_LEAF(result_type, header, thread)


#define JVM_END } }

#endif // SHARE_RUNTIME_INTERFACESUPPORT_INLINE_HPP
