/*
 * Copyright (c) 1997, 2021, Oracle and/or its affiliates. All rights reserved.
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

#include "gc/shared/gc_globals.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/mutexLocker.hpp"
#include "runtime/orderAccess.hpp"
#include "runtime/os.hpp"
#include "runtime/safepointMechanism.inline.hpp"
#include "runtime/safepointVerifiers.hpp"
#include "runtime/thread.hpp"
#include "runtime/vmOperations.hpp"
#include "utilities/globalDefinitions.hpp"
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


// To  \  From  ||   java    |          native           |           vm             |          blocked          |   new    |
// -------------||-----------|---------------------------|--------------------------|---------------------------|----------|
//              ||           |    safepoint/handshakes   |   safepoint/handshakes   |                           |          |
//    java      ||    XXX    |         suspend           |         suspend          |            XXX            |   XXX    |
//              ||           |       JFR sampling        |      JFR sampling        |                           |          |
//              ||           |     async exceptions      |     async exceptions     |                           |          |
// -------------||-----------|---------------------------|--------------------------|---------------------------|----------|
//              ||           |                           |                          |                           |          |
//    native    ||   None    |           XXX             |          None            |            XXX            |   XXX    |
//              ||           |                           |                          |                           |          |
// -------------||-----------|---------------------------|--------------------------|---------------------------|----------|
//      vm      ||   None    |    safepoint/handshakes   |           XXX            |    safepoint/handshakes   |   None   |
//              ||           |         suspend           |                          |                           |          |
//              ||           |      JFR sampling         |                          |                           |          |
// -------------||-----------|---------------------------|--------------------------|---------------------------|----------|
//    blocked   ||    XXX    |           XXX             |          None            |            XXX            |   XXX    |
// -------------||-----------|---------------------------|--------------------------|---------------------------|----------|


// Basic class for all thread transition classes.
class ThreadStateTransition : public StackObj {
 protected:
  JavaThread* _thread;
 public:
  ThreadStateTransition(JavaThread *thread) : _thread(thread) {
    assert(thread != NULL, "must be active Java thread");
  }

  static inline void transition_to_java(JavaThread *thread, bool check_async = true) {
    assert(thread->thread_state() == _thread_in_vm || thread->thread_state() == _thread_in_native, "coming from wrong thread state");
    assert(!thread->owns_locks(), "should not own any vmlocks");

    if (thread->stack_overflow_state()->stack_yellow_reserved_zone_disabled()) {
      thread->stack_overflow_state()->enable_stack_yellow_reserved_zone();
    }

    // Change to transition state and ensure it is seen by the VM thread.
    thread->set_thread_state_fence((JavaThreadState)(thread->thread_state() + 1));

    SafepointMechanism::process_if_requested_with_exit_check(thread, check_async);
    thread->set_thread_state(_thread_in_Java);
  }

  static inline void transition_from_java(JavaThread *thread, JavaThreadState to) {
    assert(thread->thread_state() == _thread_in_Java, "coming from wrong thread state");
    assert(to == _thread_in_vm || to == _thread_in_native, "coming from wrong thread state");
    assert(!thread->owns_locks(), "should not own any vmlocks");

    thread->frame_anchor()->make_walkable(thread);
    thread->set_thread_state(to);
  }

  static inline void transition_to_native(JavaThread *thread) {
    assert(thread->thread_state() == _thread_in_vm || thread->thread_state() == _thread_in_Java, "coming from wrong thread state");
    assert(!thread->owns_locks(), "should not own any vmlocks");

    thread->frame_anchor()->make_walkable(thread);
    thread->set_thread_state(_thread_in_native);
  }

  static inline void transition_from_native(JavaThread *thread, JavaThreadState to) {
    assert(thread->thread_state() == _thread_in_native, "coming from wrong thread state");
    assert(to == _thread_in_vm || to == _thread_in_Java, "coming from wrong thread state");
    assert(!thread->owns_locks(), "should not own any vmlocks");
    assert(!thread->has_last_Java_frame() || thread->frame_anchor()->walkable(), "must be walkable");

    // This is needed only by JvmtiThreadEventTransition, where there is a possible transition
    // from thread_in_native back to _thread_in_Java
    if (to == _thread_in_Java) {
      transition_to_java(thread, false);
      return;
    }

    // Change to transition state and ensure it is seen by the VM thread.
    thread->set_thread_state_fence(_thread_in_native_trans);

    // We never install asynchronous exceptions when coming (back) in
    // to the runtime from native code because the runtime is not set
    // up to handle exceptions floating around at arbitrary points.
    SafepointMechanism::process_if_requested_with_exit_check(thread, false);

    thread->set_thread_state(_thread_in_vm);
  }
};

class ThreadInVMForHandshake : public ThreadStateTransition {
  const JavaThreadState _original_state;
 public:
  ThreadInVMForHandshake(JavaThread* thread) : ThreadStateTransition(thread),
      _original_state(thread->thread_state()) {

    if (thread->has_last_Java_frame()) {
      thread->frame_anchor()->make_walkable(thread);
    }

    thread->set_thread_state(_thread_in_vm);

    // Threads shouldn't block if they are in the middle of printing, but...
    ttyLocker::break_tty_lock_for_safepoint(os::current_thread_id());
  }

  ~ThreadInVMForHandshake() {
    assert(_thread->thread_state() == _thread_in_vm, "should only call when leaving VM after handshake");
    _thread->set_thread_state(_original_state);
  }

};

class ThreadInVMfromJava : public ThreadStateTransition {
  bool _check_async;
 public:
  ThreadInVMfromJava(JavaThread* thread, bool check_async = true)
  : ThreadStateTransition(thread), _check_async(check_async) {
    transition_from_java(_thread, _thread_in_vm);
  }
  ~ThreadInVMfromJava()  {
    transition_to_java(_thread, _check_async);
  }
};


class ThreadInVMfromUnknown {
  JavaThread* _thread;
 public:
  ThreadInVMfromUnknown() : _thread(NULL) {
    Thread* t = Thread::current();
    if (t->is_Java_thread()) {
      JavaThread* t2 = t->as_Java_thread();
      if (t2->thread_state() == _thread_in_native) {
        _thread = t2;
        ThreadStateTransition::transition_from_native(t2, _thread_in_vm);
        // Used to have a HandleMarkCleaner but that is dangerous as
        // it could free a handle in our (indirect, nested) caller.
        // We expect any handles will be short lived and figure we
        // don't need an actual HandleMark.
      }
    }
  }
  ~ThreadInVMfromUnknown()  {
    if (_thread) {
      ThreadStateTransition::transition_to_native(_thread);
    }
  }
};


class ThreadInVMfromNative : public ThreadStateTransition {
  ResetNoHandleMark __rnhm;
 public:
  ThreadInVMfromNative(JavaThread* thread) : ThreadStateTransition(thread) {
    transition_from_native(thread, _thread_in_vm);
  }
  ~ThreadInVMfromNative() {
    transition_to_native(_thread);
  }
};


class ThreadToNativeFromVM : public ThreadStateTransition {
 public:
  ThreadToNativeFromVM(JavaThread *thread) : ThreadStateTransition(thread) {
    transition_to_native(thread);
  }

  ~ThreadToNativeFromVM() {
    transition_from_native(_thread, _thread_in_vm);
    assert(!_thread->is_pending_jni_exception_check(), "Pending JNI Exception Check");
    // We don't need to clear_walkable because it will happen automagically when we return to java
  }
};


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
class ThreadBlockInVM : public ThreadStateTransition {
 private:
  Mutex** _in_flight_mutex_addr;

 public:
  ThreadBlockInVM(JavaThread* thread, Mutex** in_flight_mutex_addr = NULL)
  : ThreadStateTransition(thread), _in_flight_mutex_addr(in_flight_mutex_addr) {
    // Once we are blocked vm expects stack to be walkable
    thread->frame_anchor()->make_walkable(thread);

    // All unsafe states are treated the same by the VMThread
    // so we can skip the _thread_in_vm_trans state here. Since
    // we don't read poll, it's enough to order the stores.
    OrderAccess::storestore();
    thread->set_thread_state(_thread_blocked);
  }
  ~ThreadBlockInVM() {
    // Change to transition state and ensure it is seen by the VM thread.
    _thread->set_thread_state_fence(_thread_blocked_trans);

    if (SafepointMechanism::should_process(_thread)) {
      if (_in_flight_mutex_addr != NULL) {
        release_mutex();
      }
      SafepointMechanism::process_if_requested(_thread);
    }
    
    _thread->set_thread_state(_thread_in_vm);
  }

  void release_mutex() {
    assert(_in_flight_mutex_addr != NULL, "_in_flight_mutex_addr should have been set on constructor");
    Mutex* in_flight_mutex = *_in_flight_mutex_addr;
    if (in_flight_mutex != NULL) {
      in_flight_mutex->release_for_safepoint();
      *_in_flight_mutex_addr = NULL;
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

#endif // ASSERT

// LEAF routines do not lock, GC or throw exceptions

#define VM_LEAF_BASE(result_type, header)                            \
  debug_only(NoHandleMark __hm;)                                     \
  os::verify_stack_alignment();                                      \
  /* begin of body */

#define VM_ENTRY_BASE_FROM_LEAF(result_type, header, thread)         \
  debug_only(ResetNoHandleMark __rnhm;)                              \
  HandleMarkCleaner __hm(thread);                                    \
  Thread* THREAD = thread;                                           \
  os::verify_stack_alignment();                                      \
  /* begin of body */


// ENTRY routines may lock, GC and throw exceptions

#define VM_ENTRY_BASE(result_type, header, thread)                   \
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
    ThreadInVMfromJava __tiv(thread, false /* check async */);       \
    VM_ENTRY_BASE(result_type, header, thread)                       \
    debug_only(VMEntryWrapper __vew;)

// Same as JRT Entry but allows for return value after the safepoint
// to get back into Java from the VM
#define JRT_BLOCK_ENTRY(result_type, header)                         \
  result_type header {                                               \
    HandleMarkCleaner __hm(thread);

#define JRT_BLOCK                                                    \
    {                                                                \
    ThreadInVMfromJava __tiv(thread);                                \
    Thread* THREAD = thread;                                         \
    debug_only(VMEntryWrapper __vew;)

#define JRT_BLOCK_NO_ASYNC                                           \
    {                                                                \
    ThreadInVMfromJava __tiv(thread, false /* check async */);       \
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
