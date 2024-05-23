/*
 * Copyright (c) 2021, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import jdk.internal.javac.PreviewFeature;
import jdk.internal.misc.InnocuousThread;
import jdk.internal.misc.ThreadFlock;

/**
 * An API for <em>structured concurrency</em>. {@code StructuredTaskScope} supports cases
 * where a main task splits into several concurrent subtasks, and where the subtasks must
 * complete before the main task continues. A {@code StructuredTaskScope} can be used to
 * ensure that the lifetime of a concurrent operation is confined by a <em>syntax block</em>,
 * just like that of a sequential operation in structured programming.
 *
 * <p> {@code StructuredTaskScope} defines the static method {@link #open(Policy) open} to
 * open a new {@code StructuredTaskScope} and the {@link #close() close} method to close it.
 * The API is designed to be used with the {@code try-with-resources} statement where
 * the {@code StructuredTaskScope} is opened as a resource and then closed automatically.
 * The code in the block uses the {@link #fork(Callable) fork} method to fork subtasks.
 * After forking, it uses the {@link #join() join} method to wait for all subtasks to
 * finish (or some other outcome) as a single operation. Forking a subtask starts a new
 * {@link Thread} to run the subtask. The thread executing the main task does not continue
 * beyond the {@code close} method until all threads started to execute subtasks have finished.
 * To ensure correct usage, the {@code fork}, {@code join} and {@code close} methods may
 * only be invoked by the <em>owner thread</em> (the thread that opened the {@code
 * StructuredTaskScope}), and the {@code close} method throws an exception after closing
 * if the owner did not invoke the {@code join} method.
 *
 * <p> A {@code StructuredTaskScope} is opened with a {@link Policy} that handles subtask
 * completion and produces the result returned by the {@link #join() join} method. The
 * {@code Policy} interface defines static methods to create a {@code Policy} for common
 * cases.
 *
 * <p> A {@code Policy} may <a id="CancelExecution"><em>cancel execution</em></a>
 * (sometimes called "short-circuiting") when some condition is reached that does not
 * require the result of subtasks that are still executing. Cancelling execution prevents
 * new threads from being started to execute further subtasks, {@linkplain Thread#interrupt()
 * interrupts} the threads executing subtasks that have not completed, and causes the
 * {@code join} method to wakeup with a result (or exception). The {@link #close() close}
 * method always waits for threads executing subtasks to finish, even if execution is
 * cancelled, so it cannot continue beyond the {@code close} method until the interrupted
 * threads finish. Subtasks should be coded so that they finish as soon as possible when
 * interrupted. Subtasks that block on methods that are not interruptible may delay the
 * closing of a task scope.
 *
 * <p> Consider the example of a main task that splits into two subtasks to concurrently
 * fetch resources from two URL locations "left" and "right". Both subtasks may complete
 * successfully, one subtask may succeed and the other may fail, or both subtasks may
 * fail. In this example, the code in the main task is interested in the result from the
 * first subtask to complete successfully. The example uses {@link
 * Policy#anySuccessfulResultOrThrow() Policy.anySuccessfulResultOrThrow()} to create a
 * {@code Policy} that makes available the result of the first subtask to complete
 * successfully. The type parameter in the example is "{@code String}" so that only subtasks
 * that return a {@code String} can be forked.
 * {@snippet lang=java :
 *    // @link substring="open" target="#open(Policy)" :
 *    try (var scope = StructuredTaskScope.open(Policy.<String>anySuccessfulResultOrThrow())) {
 *
 *        scope.fork(() -> query(left));  // @link substring="fork" target="#fork(Callable)"
 *        scope.fork(() -> query(right));
 *
 *        // throws if both subtasks fail
 *        String firstResult = scope.join();   // @link substring="join" target="#join()"
 *
      // @link substring="close" target="#close()" :
 *    } // close
 * }
 *
 * <p> In the example, the main task forks the two subtasks, then waits in the {@code
 * join} method for either subtask to complete successfully or for both subtasks to fail.
 * If one of the subtasks completes successfully then the other subtask is cancelled (by
 * way of interrupting the thread executing the subtask), and the {@code join} method
 * returns the result from the first subtask. Cancelling the other subtask avoids the
 * main task waiting for a result that it doesn't care about. If both subtasks fail then
 * the {@code join} method throws {@link ExecutionException} with the exception from one
 * of the subtasks as the {@linkplain Throwable#getCause() cause}.
 *
 * <p> Now consider another example that also splits into two subtasks to concurrently
 * fetch resources. One of the subtasks returns a {@code String} when it succeeds, the
 * other returns an {@code Integer}. The main task in this example is interested in the
 * successful result from both subtasks. It uses {@link Policy#ignoreSuccessfulOrThrow()
 * Policy.ignoreSuccessfulOrThrow()} to create a {@code Policy} that cancels execution and
 * causes {@code join} to throw if any subtask fails.
 * {@snippet lang=java :
 *    try (var scope = StructuredTaskScope.open(Policy.ignoreSuccessfulOrThrow())) {
 *
 *        // @link substring="Subtask" target="Subtask" :
 *        Subtask<String> subtask1 = scope.fork(() -> query(left));
 *        Subtask<Integer> subtask2 = scope.fork(() -> query(right));
 *
 *        // throws if either subtask fails
 *        scope.join();
 *
 *        // both subtasks completed successfully
 *        return new MyResult(subtask1.get(), subtask2.get()); // @link substring="get" target="Subtask#get()"
 *
 *    }
 * }
 *
 * <p> In this example, the main task forks the two subtasks. The {@code fork} method
 * returns a {@link Subtask Subtask} that is a handle to the forked subtask. The main task
 * waits in the {@code join} method for both subtasks to complete successfully or for either
 * subtask to fail. If both subtasks complete successfully then the {@code join} method
 * completes and the main task uses the {@link Subtask#get() Subtask.get()} method to get
 * the result of each subtask. If either subtask fails then the other is cancelled (by way
 * of interrupting the thread executing the subtask) and the {@code join} throws {@link
 * ExecutionException} with the exception from the failed subtask as the {@linkplain
 * Throwable#getCause() cause}.
 *
 * <p> Whether code uses the {@code Subtask} returned from {@code fork} will depend on
 * the {@code Policy} and usage. Some {@code Policy} implementations are suited to subtasks
 * that return results of the same type and where the {@code join} method returns a result
 * for the main task to use. Code that forks subtasks that return results of different
 * types, and uses a {@code Policy} such as {@code Policy.ignoreSuccessfulOrThrow()} that
 * does not return a result, will use {@link Subtask#get() Subtask.get()} after joining.
 *
 * <h2>Configuration</h2>
 *
 * A {@code StructuredTaskScope} is opened with {@linkplain Config configuration} that
 * consists of a {@link ThreadFactory} to create threads, an optional name for monitoring
 * and management purposes, and an optional timeout.
 *
 * <p> The 1-arg {@link #open(Policy) open} method creates a {@code StructuredTaskScope}
 * with the <a id="DefaultConfiguration"> <em>default configuration</em></a>. The default
 * configuration has a {@code ThreadFactory} that creates unnamed
 * <a href="{@docRoot}/java.base/java/lang/Thread.html#virtual-threads">virtual threads</a>,
 * is unnamed for monitoring and management purposes, and has no timeout.
 *
 * <p> The 2-arg {@link #open(Policy, Function) open} method can be used to create a
 * {@code StructuredTaskScope} that uses a different {@code ThreadFactory}, has a name for
 * the purposes of monitoring and management, or has a timeout that cancels execution if
 * the timeout expires before or while waiting for subtasks to finish. The {@code open}
 * method is called with a {@linkplain Function function} that is applied to the default
 * configuration and returns a {@link Config Config} for the {@code StructuredTaskScope}
 * under construction.
 *
 * <p> The following example opens a new {@code StructuredTaskScope} with a {@code
 * ThreadFactory} that creates virtual threads {@linkplain Thread#setName(String) named}
 * "duke-0", "duke-1" ...
 * {@snippet lang=java :
 *    // @link substring="name" target="Thread.Builder#name(String, long)" :
 *    ThreadFactory factory = Thread.ofVirtual().name("duke-", 0).factory();
 *
 *    // @link substring="withThreadFactory" target="Config#withThreadFactory(ThreadFactory)" :
 *    try (var scope = StructuredTaskScope.open(policy, cf -> cf.withThreadFactory(factory))) {
 *
 *        scope.fork( .. );   // runs in a virtual thread with name "duke-0"
 *        scope.fork( .. );   // runs in a virtual thread with name "duke-1"
 *
 *        scope.join();
 *
 *     }
 * }
 *
 * <p> A second example sets a timeout, represented by a {@link Duration}. The timeout
 * starts when the new task scope is opened. If the timeout expires before the {@code join}
 * method has completed then <a href="#CancelExecution">execution is cancelled</a>. This
 * interrupts the threads executing the two subtasks and causes the {@link #join() join}
 * method to throw {@link ExecutionException} with {@link TimeoutException} as the cause.
 * {@snippet lang=java :
 *    Duration timeout = Duration.ofSeconds(10);
 *
 *    // @link substring="allSuccessfulOrThrow" target="Policy#allSuccessfulOrThrow()" :
 *    try (var scope = StructuredTaskScope.open(Policy.<String>allSuccessfulOrThrow(),
      // @link substring="withTimeout" target="Config#withTimeout(Duration)" :
 *                                              cf -> cf.withTimeout(timeout))) {
 *
 *        scope.fork(() -> query(left));
 *        scope.fork(() -> query(right));
 *
 *        List<String> result = scope.join()
 *                                   .map(Subtask::get)
 *                                   .toList();
 *
 *   }
 * }
 *
 * <h2>Inheritance of scoped value bindings</h2>
 *
 * {@link ScopedValue} supports the execution of a method with a {@code ScopedValue} bound
 * to a value for the bounded period of execution of the method by the <em>current thread</em>.
 * It allows a value to be safely and efficiently shared to methods without using method
 * parameters.
 *
 * <p> When used in conjunction with a {@code StructuredTaskScope}, a {@code ScopedValue}
 * can also safely and efficiently share a value to methods executed by subtasks forked
 * in the task scope. When a {@code ScopedValue} object is bound to a value in the thread
 * executing the main task then that binding is inherited by the threads created to
 * execute the subtasks. The thread executing the main task does not continue beyond the
 * {@link #close() close} method until all threads executing the subtasks have finished.
 * This ensures that the {@code ScopedValue} is not reverted to being {@linkplain
 * ScopedValue#isBound() unbound} (or its previous value) while subtasks are executing.
 * In addition to providing a safe and efficient means to inherit a value into subtasks,
 * the inheritance allows sequential code using {@code ScopedValue} be refactored to use
 * structured concurrency.
 *
 * <p> To ensure correctness, opening a new {@code StructuredTaskScope} captures the
 * current thread's scoped value bindings. These are the scoped values bindings that are
 * inherited by the threads created to execute subtasks in the task scope. Forking a
 * subtask checks that the bindings in effect at the time that the subtask is forked
 * match the bindings when the {@code StructuredTaskScope} was created. This check ensures
 * that a subtask does not inherit a binding that is reverted in the main task before the
 * subtask has completed.
 *
 * <p> A {@code ScopedValue} that is shared across threads requires that the value be an
 * immutable object or for all access to the value to be appropriately synchronized.
 *
 * <p> The following example demonstrates the inheritance of scoped value bindings. The
 * scoped value USERNAME is bound to the value "duke" for the bounded period of a lambda
 * expression by the thread executing it. The code in the block opens a {@code
 * StructuredTaskScope} and forks two subtasks, it then waits in the {@code join} method
 * and aggregates the results from both subtasks. If code executed by the threads
 * running subtask1 and subtask2 uses {@link ScopedValue#get()}, to get the value of
 * USERNAME, then value "duke" will be returned.
 * {@snippet lang=java :
 *     // @link substring="newInstance" target="ScopedValue#newInstance()" :
 *     private static final ScopedValue<String> USERNAME = ScopedValue.newInstance();
 *
 *     // @link substring="callWhere" target="ScopedValue#callWhere" :
 *     Result result = ScopedValue.callWhere(USERNAME, "duke", () -> {
 *
 *         try (var scope = StructuredTaskScope.open(Policy.ignoreSuccessfulOrThrow())) {
 *
 *             Subtask<String> subtask1 = scope.fork( .. );    // inherits binding
 *             Subtask<Integer> subtask2 = scope.fork( .. );   // inherits binding
 *
 *             scope.join();
 *             return new MyResult(subtask1.get(), subtask2.get());
 *         }
 *
 *     });
 * }
 *
 * <p> A scoped value inherited into a subtask may be
 * <a href="{@docRoot}/java.base/java/lang/ScopedValues.html#rebind">rebound</a> to a new
 * value in the subtask for the bounded execution of some method executed in the subtask.
 * When the method completes, the value of the {@code ScopedValue} reverts to its previous
 * value, the value inherited from the thread executing the main task.
 *
 * <p> A subtask may execute code that itself opens a new {@code StructuredTaskScope}.
 * A main task executing in thread T1 opens a {@code StructuredTaskScope} and forks a
 * subtask that runs in thread T2. The scoped value bindings captured when T1 opens the
 * task scope are inherited into T2. The subtask (in thread T2) executes code that opens a
 * new {@code StructuredTaskScope} and forks a subtask that runs in thread T3. The scoped
 * value bindings captured when T2 opens the task scope are inherited into T3. These
 * include (or may be the same) as the bindings that were inherited from T1. In effect,
 * scoped values are inherited into a tree of subtasks, not just one level of subtask.
 *
 * <h2>Memory consistency effects</h2>
 *
 * <p> Actions in the owner thread of a {@code StructuredTaskScope} prior to
 * {@linkplain #fork forking} of a subtask
 * <a href="{@docRoot}/java.base/java/util/concurrent/package-summary.html#MemoryVisibility">
 * <i>happen-before</i></a> any actions taken by that subtask, which in turn
 * <i>happen-before</i> the subtask result is {@linkplain Subtask#get() retrieved}.
 *
 * <h2>General exceptions</h2>
 *
 * <p> Unless otherwise specified, passing a {@code null} argument to a method in this
 * class will cause a {@link NullPointerException} to be thrown.
 *
 * @param <T> the result type of tasks executed in the task scope
 * @param <R> the type of the result returned by the join method
 *
 * @jls 17.4.5 Happens-before Order
 * @since 21
 */
@PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
public class StructuredTaskScope<T, R> implements AutoCloseable {
    private static final VarHandle CANCELLED;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            CANCELLED = l.findVarHandle(StructuredTaskScope.class,"cancelled", boolean.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Policy<? super T, ? extends R> policy;
    private final ThreadFactory threadFactory;
    private final ThreadFlock flock;

    // fields that are only accessed by owner thread
    private boolean needToJoin;     // join attempted
    private boolean joined;         // join completed
    private boolean closed;
    private Future<?> timerTask;

    // set or read by any thread
    private volatile boolean cancelled;

    // set by the timer thread, read by the owner thread
    private volatile boolean timeoutExpired;

    /**
     * Throws IllegalStateException if the task scope is closed.
     */
    private void ensureOpen() {
        assert Thread.currentThread() == flock.owner();
        if (closed) {
            throw new IllegalStateException("Task scope is closed");
        }
    }

    /**
     * Throws WrongThreadException if the current thread is not the owner thread.
     */
    private void ensureOwner() {
        if (Thread.currentThread() != flock.owner()) {
            throw new WrongThreadException("Current thread not owner");
        }
    }

    /**
     * Throws IllegalStateException if invoked by the owner thread and the owner thread
     * has not joined.
     */
    private void ensureJoinedIfOwner() {
        if (Thread.currentThread() == flock.owner() && !joined) {
            String msg = needToJoin ? "Owner did not join" : "join did not complete";
            throw new IllegalStateException(msg);
        }
    }

    /**
     * Interrupts all threads in this task scope, except the current thread.
     */
    private void implInterruptAll() {
        flock.threads()
                .filter(t -> t != Thread.currentThread())
                .forEach(t -> {
                    try {
                        t.interrupt();
                    } catch (Throwable ignore) { }
                });
    }

    @SuppressWarnings("removal")
    private void interruptAll() {
        if (System.getSecurityManager() == null) {
            implInterruptAll();
        } else {
            PrivilegedAction<Void> pa = () -> {
                implInterruptAll();
                return null;
            };
            AccessController.doPrivileged(pa);
        }
    }

    /**
     * Cancel exception if not already cancelled.
     */
    private void cancelExecution() {
        if (!cancelled && CANCELLED.compareAndSet(this, false, true)) {
            // prevent new threads from starting
            flock.shutdown();

            // interrupt all unfinished threads
            interruptAll();

            // wakeup join
            flock.wakeup();
        }
    }

    /**
     * Schedules a task to cancel execution on timeout.
     */
    private void scheduleTimeout(Duration timeout) {
        assert Thread.currentThread() == flock.owner() && timerTask == null;
        timerTask = TimerSupport.schedule(timeout, () -> {
            if (!cancelled) {
                timeoutExpired = true;
                cancelExecution();
            }
        });
    }

    /**
     * Cancels the timer task if set.
     */
    private void cancelTimeout() {
        assert Thread.currentThread() == flock.owner();
        if (timerTask != null) {
            timerTask.cancel(false);
        }
    }

    /**
     * Invoked by the thread for a subtask when the subtask completes before execution
     * was cancelled.
     */
    private void onComplete(SubtaskImpl<? extends T> subtask) {
        assert subtask.state() != Subtask.State.UNAVAILABLE;
        if (policy.onComplete(subtask)) {
            cancelExecution();
        }
    }

    /**
     * Initialize a new StructuredTaskScope.
     */
    @SuppressWarnings("this-escape")
    private StructuredTaskScope(Policy<? super T, ? extends R> policy,
                                ThreadFactory threadFactory,
                                String name) {
        this.policy = policy;
        this.threadFactory = threadFactory;

        if (name == null)
            name = Objects.toIdentityString(this);
        this.flock = ThreadFlock.open(name);
    }

    /**
     * Represents a subtask forked with {@link #fork(Callable)} or {@link #fork(Runnable)}.
     * @param <T> the result type
     * @since 21
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public sealed interface Subtask<T> extends Supplier<T> permits SubtaskImpl {
        /**
         * Represents the state of a subtask.
         * @see Subtask#state()
         * @since 21
         */
        @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
        enum State {
            /**
             * The subtask result or exception is not available. This state indicates that
             * the subtask was forked but has not completed, it completed after execution
             * was cancelled, or it was forked after execution was cancelled (in which
             * case a thread was not created to execute the subtask).
             */
            UNAVAILABLE,
            /**
             * The subtask completed successfully. The {@link Subtask#get() Subtask.get()}
             * method can be used to get the result. This is a terminal state.
             */
            SUCCESS,
            /**
             * The subtask failed with an exception. The {@link Subtask#exception()
             * Subtask.exception()} method can be used to get the exception. This is a
             * terminal state.
             */
            FAILED,
        }

        /**
         * {@return the subtask state}
         */
        State state();

        /**
         * Returns the result of this subtask if it completed successfully. If
         * {@linkplain #fork(Callable) forked} to execute a value-returning task then the
         * result from the {@link Callable#call() call} method is returned. If
         * {@linkplain #fork(Runnable) forked} to execute a task that does not return a
         * result then {@code null} is returned.
         *
         * <p> Code executing in the scope owner thread can use this method to get the
         * result of a successful subtask only after it has {@linkplain #join() joined}.
         *
         * <p> Code executing in the {@code Policy} {@link Policy#onComplete(Subtask)
         * onComplete} method should test that the {@linkplain #state() subtask state} is
         * {@link State#SUCCESS SUCCESS} before using this method to get the result.
         *
         * @return the possibly-null result
         * @throws IllegalStateException if the subtask has not completed, did not complete
         * successfully, or the current thread is the task scope owner and it has not joined
         * @see State#SUCCESS
         */
        T get();

        /**
         * {@return the exception thrown by this subtask if it failed} If
         * {@linkplain #fork(Callable) forked} to execute a value-returning task then
         * the exception thrown by the {@link Callable#call() call} method is returned.
         * If {@linkplain #fork(Runnable) forked} to execute a task that does not return
         * a result then the exception thrown by the {@link Runnable#run() run} method is
         * returned.
         *
         * <p> Code executing in the scope owner thread can use this method to get the
         * exception thrown by a failed subtask only after it has {@linkplain #join() joined}.
         *
         * <p> Code executing in a {@code Policy} {@link Policy#onComplete(Subtask)
         * onComplete} method should test that the {@linkplain #state() subtask state} is
         * {@link State#FAILED FAILED} before using this method to get the exception.
         *
         * @throws IllegalStateException if the subtask has not completed, completed with
         * a result, or the current thread is the task scope owner and it has not joined
         * @see State#FAILED
         */
        Throwable exception();
    }

    /**
     * An object used with a {@link StructuredTaskScope} to handle subtask completion
     * and produce the result for a main task waiting in the {@link #join() join} method
     * for subtasks to complete.
     *
     * <p> Policy defines static methods to create {@code Policy} objects for common cases:
     * <ul>
     *   <li> {@link #allSuccessfulOrThrow() allSuccessfulOrThrow()} creates a {@code Policy}
     *   that yields a stream of the completed subtasks for {@code join} to return when
     *   all subtasks complete successfully. It cancels execution and causes {@code join}
     *   to throw if any subtask fails.
     *   <li> {@link #anySuccessfulResultOrThrow() anySuccessfulResultOrThrow()} creates a
     *   {@code Policy} that yields the result of the first subtask to succeed. It cancels
     *   execution and causes {@code join} to throw if all subtasks fail.
     *   <li> {@link #ignoreSuccessfulOrThrow() ignoreSuccessfulOrThrow()} creates a {@code
     *   Policy} that ignores all successful subtasks. It cancels execution and causes
     *   {@code join} to throw if any subtask fails.
     *   <li> {@link #ignoreAll() ignoreAll()} creates a {@code Policy} that ignores all
     *   completed subtasks, even subtasks that fail. The {@code join} method returns null
     *   when all subtasks finish.
     * </ul>
     *
     * <p> In addition to the methods to create {@code Policy} objects for common cases,
     * the {@link #all(Predicate) all(Predicate)} method is defined to create a {@code
     * Policy} that yields a stream of all subtasks. It is created with a {@link Predicate
     * Predicate} that determines if execution should continue or be cancelled. This policy
     * can be built upon to create custom policies that cancel execution based on some
     * condition.
     *
     * <p> More advanced policies can be developed by implementing the {@code Policy}
     * interface. The {@link #onFork(Subtask)} method is invoked when subtasks are forked.
     * The {@link #onComplete(Subtask)} method is invoked when subtasks complete with a
     * result or exception. These methods return a {@code boolean} to indicate if execution
     * should be cancelled. These methods can be used to collect subtasks, results, or
     * exceptions, and control when to cancel execution. The {@link #result()} method
     * must be implemented to produce the result (or exception) for the {@code join}
     * method.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a method
     * in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @implSpec Implementations of this interface must be thread safe. The {@link
     * #onComplete(Subtask)} method defined by this interface may be invoked by several
     * threads concurrently.
     *
     * @apiNote It is very important that a new {@code Policy} object is created for each
     * {@code StructuredTaskScope}. {@code Policy} objects should never be shared with
     * different task scopes or re-used after a task is closed.
     *
     * <p> Designing a {@code Policy} should take into account the code at the use-site
     * where the results from the {@link StructuredTaskScope#join() join} method are
     * processed. It should be clear what the {@code Policy} does vs. the application
     * code at the use-site. In general, the {@code Policy} implementation is not the
     * place to code "business logic". A {@code Policy} should be designed to be as
     * general purpose as possible.
     *
     * @param <T> the result type of tasks executed in the task scope
     * @param <R> the type of results returned by the join method
     * @since 24
     * @see #open(Policy)
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    @FunctionalInterface
    public interface Policy<T, R> {

        /**
         * Invoked by {@link #fork(Callable) fork(Callable)} and {@link #fork(Runnable)
         * fork(Runnable)} when forking a subtask. The method is invoked from the task
         * owner thread. The method is invoked before a thread is created to run the
         * subtask.
         *
         * @implSpec The default implementation throws {@code NullPointerException} if the
         * subtask is {@code null}. It throws {@code IllegalArgumentException} if the
         * subtask is not in the {@link Subtask.State#UNAVAILABLE UNAVAILABLE} state, it
         * otherwise returns {@code false}.
         *
         * @apiNote This method is invoked by the {@code fork} methods. It should not be
         * invoked directly.
         * 
         * @param subtask the subtask
         * @return {@code true} to cancel execution
         */
        default boolean onFork(Subtask<? extends T> subtask) {
            if (subtask.state() != Subtask.State.UNAVAILABLE) {
                throw new IllegalArgumentException();
            }
            return false;
        }

        /**
         * Invoked by the thread started to execute a subtask after the subtask completes
         * successfully or fails with an exception. This method is not invoked if a
         * subtask completes after execution has been cancelled.
         *
         * @implSpec The default implementation throws {@code NullPointerException} if the
         * subtask is {@code null}. It throws {@code IllegalArgumentException} if the
         * subtask is not in the {@link Subtask.State#SUCCESS SUCCESS} or {@link
         * Subtask.State#FAILED FAILED} state, it otherwise returns {@code false}.
         *
         * @apiNote This method is invoked by subtasks when they complete. It should not
         * be invoked directly.
         *
         * @param subtask the subtask
         * @return {@code true} to cancel execution
         */
        default boolean onComplete(Subtask<? extends T> subtask) {
            if (subtask.state() == Subtask.State.UNAVAILABLE) {
                throw new IllegalArgumentException();
            }
            return false;
        }

        /**
         * Invoked by {@link #join()} to produce the result (or exception) after waiting
         * for all subtasks to complete or execution to be cancelled. The result from this
         * method is returned by the {@code join} method. If this method throws, then
         * {@code join} throws {@link ExecutionException} with the exception thrown by
         * this method as the cause.
         *
         * <p> In normal usage, this method will be called at most once to produce the
         * result (or exception). If the {@code join} method is called more than once
         * then this method may be called more than once to produce the result. An
         * implementation should return an equal result (or throw the same exception) on
         * second or subsequent calls to produce the outcome.
         *
         * @apiNote This method is invoked by the {@code join} method. It should not be
         * invoked directly.
         *
         * @return the result
         * @throws Throwable the exception
         */
        R result() throws Throwable;

        /**
         * {@return a new policy object that yields a stream of all subtasks when all
         * subtasks complete successfully, or throws if any subtask fails}
         * If any subtask fails then execution is cancelled.
         *
         * <p> If all subtasks complete successfully, the policy's {@link Policy#result()}
         * method returns a stream of all subtasks in the order that they were forked.
         * If any subtask failed then the {@code result} method throws the exception from
         * the first subtask to fail.
         *
         * @apiNote This policy is intended for cases where the results for all subtasks
         * are required ("invoke all"); if any subtask fails then the results of other
         * unfinished subtasks are no longer needed. A typical usage will be when the
         * subtasks return results of the same type, the returned stream of forked
         * subtasks can be used to get the results.
         *
         * @param <T> the result type of subtasks
         */
        static <T> Policy<T, Stream<Subtask<T>>> allSuccessfulOrThrow() {
            return new AllSuccessful<>();
        }

        /**
         * {@return a new policy object that yields the result of a subtask that completed
         * successfully, or throws if all subtasks fail} If any subtask completes
         * successfully then execution is cancelled.
         *
         * <p> The policy's {@link Policy#result()} method returns the result of a subtask
         * that completed successfully. If all subtasks fail then the {@code result} method
         * throws the exception from one of the failed subtasks. The {@code result} method
         * throws {@code NoSuchElementException} if no subtasks were forked.
         *
         * @apiNote This policy is intended for cases where the result of any subtask will
         * do ("invoke any") and where the results of other unfinished subtasks are no
         * longer needed.
         *
         * @param <T> the result type of subtasks
         */
        static <T> Policy<T, T> anySuccessfulResultOrThrow() {
            return new AnySuccessful<>();
        }

        /**
         * {@return a new policy object that ignores all successful subtasks. It
         * <a href="StructuredTaskScope.html#CancelExecution">cancels execution</a> if
         * any subtask fails}
         *
         * The policy's {@link Policy#result() result} method returns {@code null} if
         * all subtasks complete successfully, or throws the exception from the first
         * subtask to fail.
         *
         * @apiNote This policy is intended for cases where the results for all subtasks
         * are required ("invoke all"), and where the code {@linkplain #fork(Callable) forking}
         * subtasks keeps a reference to the {@linkplain Subtask Subtask} objects. A
         * typical usage will be when subtasks return results of different types.
         *
         * @param <T> the result type of subtasks
         */
        static <T> Policy<T, Void> ignoreSuccessfulOrThrow() {
            return new IgnoreSuccessful<>();
        }

        /**
         * {@return a new policy object that ignores all completed subtasks, even subtasks
         * that fail} The policy's {@link Policy#result() result} method returns {@code null}.
         *
         * @apiNote This policy is intended for cases where subtasks make use of
         * <em>side-effects</em> rather than return results or fail with exceptions.
         * The {@link #fork(Runnable) fork(Runnable)} method can be used to fork subtasks
         * that do not return a result.
         *
         * @param <T> the result type of subtasks
         */
        static <T> Policy<T, Void> ignoreAll() {
            // ensure that new Policy object is returned
            return new Policy<T, Void>() {
                @Override
                public Void result() {
                    return null;
                }
            };
        }

        /**
         * {@return a new policy object that yields a stream of all subtasks, cancelling
         * execution when evaluating a completed subtask with the given predicate returns
         * {@code true}}
         *
         * <p> The policy's {@link Policy#onComplete(Subtask)} method invokes the
         * predicate's {@link Predicate#test(Object) test} method with the subtask that
         * completed successfully or failed with an exception. If the {@code test} method
         * returns {@code true} then <a href="StructuredTaskScope.html#CancelExecution">
         * execution is cancelled</a>. The {@code test} method must be thread safe as it
         * may be invoked concurrently from several threads.
         *
         * <p> The policy's {@link #result()} method returns the stream of all subtasks,
         * in fork order. The stream may contain subtasks that have completed
         * (in {@link Subtask.State#SUCCESS SUCCESS} or {@link Subtask.State#FAILED FAILED}
         * state) or subtasks in the {@link Subtask.State#UNAVAILABLE UNAVAILABLE} state
         * if execution was cancelled before all subtasks were forked or completed.
         *
         * <p> The following example uses this method to create a {@code Policy} that
         * <a href="StructuredTaskScope.html#CancelExecution">cancels execution</a> when
         * two or more subtasks fail.
         * {@snippet lang=java :
         *    class CancelAfterTwoFailures<T> implements Predicate<Subtask<? extends T>> {
         *         private final AtomicInteger failedCount = new AtomicInteger();
         *         @Override
         *         public boolean test(Subtask<? extends T> subtask) {
         *             return subtask.state() == Subtask.State.FAILED
         *                     && failedCount.incrementAndGet() >= 2;
         *         }
         *     }
         *
         *     var policy = Policy.all(new CancelAfterTwoFailures<String>());
         * }
         *
         * @param isDone the predicate to evaluate completed subtasks
         * @param <T> the result type of subtasks
         */
        static <T> Policy<T, Stream<Subtask<T>>> all(Predicate<Subtask<? extends T>> isDone) {
            return new AllSubtasks<>(isDone);
        }
    }

    /**
     * Represents the configuration for a {@code StructuredTaskScope}.
     *
     * <p> The configuration for a {@code StructuredTaskScope} consists of a {@link
     * ThreadFactory} to create threads, an optional name for the purposes of monitoring
     * and management, and an optional timeout.
     *
     * <p> Creating a {@code StructuredTaskScope} with its 1-arg {@link #open(Policy) open}
     * method uses the <a href="StructuredTaskScope.html#DefaultConfiguration">default
     * configuration</a>. The default configuration consists of a thread factory that
     * creates unnamed <a href="{@docRoot}/java.base/java/lang/Thread.html#virtual-threads">
     * virtual threads</a>, no name for monitoring and management purposes, and no timeout.
     *
     * <p> Creating a {@code StructuredTaskScope} with its 2-arg {@link #open(Policy, Function)
     * open} method allows a different configuration to be used. The function specified
     * to the {@code open} method is applied to the default configuration and returns the
     * configuration for the {@code StructuredTaskScope} under construction. The function
     * can use the {@code with-} prefixed methods defined here to specify the components
     * of the configuration to use.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a method
     * in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @since 24
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public sealed interface Config permits ConfigImpl {
        /**
         * {@return a new {@code Config} object with the given thread factory}
         * The other components are the same as this object. The thread factory is used by
         * a task scope to create threads when {@linkplain #fork(Callable) forking} subtasks.
         * @param threadFactory the thread factory
         *
         * @apiNote The thread factory will typically create
         * <a href="{@docRoot}/java.base/java/lang/Thread.html#virtual-threads">virtual threads</a>,
         * maybe with names for monitoring purposes, an {@linkplain Thread.UncaughtExceptionHandler
         * uncaught exception handler}, or other properties configured.
         *
         * @see #fork(Callable)
         */
        Config withThreadFactory(ThreadFactory threadFactory);

        /**
         * {@return a new {@code Config} object with the given name}
         * The other components are the same as this object. A task scope is optionally
         * named for the purposes of monitoring and management.
         * @param name the name
         * @see StructuredTaskScope#toString()
         */
        Config withName(String name);

        /**
         * {@return a new {@code Config} object with the given timeout}
         * The other components are the same as this object.
         * @param timeout the timeout
         *
         * @apiNote Applications using deadlines, expressed as an {@link java.time.Instant},
         * can use {@link Duration#between Duration.between(Instant.now(), deadline)} to
         * compute the timeout for this method.
         *
         * @see #join()
         */
        Config withTimeout(Duration timeout);
    }

    /**
     * Opens a new structured task scope to use the given policy object plus configuration
     * that is the result of applying the given function to the
     * <a href="#DefaultConfiguration">default configuration</a>.
     *
     * <p> The {@code configFunction} is called with the default configuration and returns
     * the configuration for the new structured task scope. The function may, for example,
     * set the {@linkplain Config#withThreadFactory(ThreadFactory) ThreadFactory} or set
     * a {@linkplain Config#withTimeout(Duration) timeout}.
     *
     * <p> If a {@linkplain Config#withThreadFactory(ThreadFactory) ThreadFactory} is set
     * then the {@code ThreadFactory}'s {@link ThreadFactory#newThread(Runnable) newThread}
     * method will be used to create threads when forking subtasks in this task scope.
     *
     * <p> If a {@linkplain Config#withTimeout(Duration) timeout} is set then it starts
     * when the task scope is opened. If the timeout expires before the task scope has
     * {@linkplain #join() joined} then execution is cancelled and the {@code join} method
     * throws {@link ExecutionException} with {@link TimeoutException} as the cause.
     *
     * <p> The new task scope is owned by the current thread. Only code executing in this
     * thread can {@linkplain #fork(Callable) fork}, {@linkplain #join() join}, or
     * {@linkplain #close close} the task scope.
     *
     * <p> Construction captures the current thread's {@linkplain ScopedValue scoped
     * value} bindings for inheritance by threads started in the task scope.
     *
     * @param policy the policy
     * @param configFunction a function to produce the configuration
     * @return a new task scope
     * @param <T> the result type of tasks executed in the task scope
     * @param <R> the type of the result returned by the join method
     * @since 24
     */
    public static <T, R> StructuredTaskScope<T, R> open(Policy<? super T, ? extends R> policy,
                                                        Function<Config, Config> configFunction) {
        Objects.requireNonNull(policy);

        var config = (ConfigImpl) configFunction.apply(ConfigImpl.defaultConfig());
        var scope = new StructuredTaskScope<T, R>(policy, config.threadFactory(), config.name());

        // schedule timeout
        Duration timeout = config.timeout();
        if (timeout != null) {
            boolean done = false;
            try {
                scope.scheduleTimeout(timeout);
                done = true;
            } finally {
                if (!done) {
                    scope.close();  // pop if scheduling timeout failed
                }
            }
        }

        return scope;
    }

    /**
     * Opens a new structured task scope to use the given policy. The task scope is
     * created with the <a href="#DefaultConfiguration">default configuration</a>.
     * The default configuration has a {@code ThreadFactory} that creates unnamed
     * <a href="{@docRoot}/java.base/java/lang/Thread.html#virtual-threads">virtual threads</a>,
     * is unnamed for monitoring and management purposes, and has no timeout.
     *
     * @implSpec
     * This factory method is equivalent to invoking the 2-arg open method with the given
     * policy and the {@linkplain Function#identity() identity function}.
     *
     * @param policy the policy
     * @return a new task scope
     * @param <T> the result type of tasks executed in the task scope
     * @param <R> the type of the result returned by the join method
     * @since 24
     */
    public static <T, R> StructuredTaskScope<T, R> open(Policy<? super T, ? extends R> policy) {
        return open(policy, Function.identity());
    }

    /**
     * Starts a new thread in this task scope to execute a value-returning task, thus
     * creating a <em>subtask</em>. The value-returning task is provided to this method
     * as a {@link Callable}, the thread executes the task's {@link Callable#call() call}
     * method.
     *
     * <p> This method first creates a {@link Subtask Subtask} to represent the <em>forked
     * subtask</em>. It invokes the policy's {@link Policy#onFork(Subtask) onFork} method
     * with the {@code Subtask} object. If the {@code onFork} completes with an exception
     * or error then it is propagated by the {@code fork} method. If execution is
     * {@linkplain #isCancelled() cancelled}, or {@code onFork} returns {@code true} to
     * cancel execution, then this method returns the {@code Subtask} (in the {@link
     * Subtask.State#UNAVAILABLE UNAVAILABLE} state) without creating a thread to execute
     * the subtask. If execution is not cancelled then a thread is created with the
     * {@link ThreadFactory} configured when the task scope was created, and the thread is
     * started. Forking a subtask inherits the current thread's {@linkplain ScopedValue
     * scoped value} bindings. The bindings must match the bindings captured when the
     * task scope was opened. If the subtask completes (successfully or with an exception)
     * before execution is cancelled, then the thread invokes the policy's
     * {@link Policy#onComplete(Subtask) onComplete} method with subtask in the
     * {@link Subtask.State#SUCCESS SUCCESS} or {@link Subtask.State#FAILED FAILED} state.
     *
     * <p> This method returns the {@link Subtask Subtask} object. In some usages, this
     * object may be used to get its result. In other cases it may be used for correlation
     * or just discarded. To ensure correct usage, the {@link Subtask#get() Subtask.get()}
     * method may only be called by the task scope owner to get the result after it has
     * waited for all threads to finish with the {@link #join() join} method and the subtask
     * completed successfully. Similarly, the {@link Subtask#exception() Subtask.exception()}
     * method may only be called by the task scope owner after it has joined and the subtask
     * failed. If execution was cancelled before the subtask was forked, or before it
     * completes, then neither method can be used to obtain the outcome.
     *
     * <p> This method may only be invoked by the task scope owner.
     *
     * @param task the value-returning task for the thread to execute
     * @param <U> the result type
     * @return the subtask
     * @throws IllegalStateException if this task scope is closed or the owner has already
     * joined
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws StructureViolationException if the current scoped value bindings are not
     * the same as when the task scope was created
     * @throws RejectedExecutionException if the thread factory rejected creating a
     * thread to run the subtask
     */
    public <U extends T> Subtask<U> fork(Callable<? extends U> task) {
        Objects.requireNonNull(task);
        ensureOwner();
        ensureOpen();
        if (joined) {
            throw new IllegalStateException("Already joined");
        }

        var subtask = new SubtaskImpl<U>(this, task);

        // notify policy, even if cancelled
        if (policy.onFork(subtask)) {
            cancelExecution();
        }

        if (!cancelled) {
            // create thread to run task
            Thread thread = threadFactory.newThread(subtask);
            if (thread == null) {
                throw new RejectedExecutionException("Rejected by thread factory");
            }

            // attempt to start the thread
            try {
                flock.start(thread);
            } catch (IllegalStateException e) {
                // shutdown by another thread, or underlying flock is shutdown due
                // to unstructured use
            }
        }

        needToJoin = true;
        return subtask;
    }

    /**
     * Starts a new thread in this task scope to execute a task that does not return a
     * result, creating a <em>subtask</em>.
     *
     * <p> This method works exactly the same as {@link #fork(Callable)} except that
     * the task is provided to this method as a {@link Runnable}, the thread executes
     * the task's {@link Runnable#run() run} method, and its result is {@code null}.
     *
     * @param task the task for the thread to execute
     * @return the subtask
     * @throws IllegalStateException if this task scope is closed or the owner has already
     * joined
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws StructureViolationException if the current scoped value bindings are not
     * the same as when the task scope was created
     * @throws RejectedExecutionException if the thread factory rejected creating a
     * thread to run the subtask
     * @since 24
     */
    public Subtask<? extends T> fork(Runnable task) {
        Objects.requireNonNull(task);
        return fork(() -> { task.run(); return null; });
    }

    /**
     * Waits for all subtasks started in this task scope to finish or execution to be
     * cancelled. If a {@linkplain  Config#withTimeout(Duration) timeout} has been set
     * then execution will be cancelled if the timeout expires before or while waiting.
     * Once finished waiting, the {@code Policy}'s {@link Policy#result() result}
     * method is invoked to get the result or throw an exception. If the {@code result}
     * method throws then this method throws {@code ExecutionException} with the policy's
     * exception as the cause.
     *
     * <p> This method waits for all subtasks by waiting for all threads {@linkplain
     * #fork(Callable) started} in this task scope to finish execution. It stops waiting
     * when all threads finish, the {@code Policy}'s {@link Policy#onFork(Subtask) onFork}
     * or {@link Policy#onComplete(Subtask) onComplete} returns {@code true} to cancel
     * execution, the timeout (if set) expires, or the current thread is {@linkplain
     * Thread#interrupt() interrupted}.
     *
     * <p> This method may only be invoked by the task scope owner.
     *
     * @return the {@link Policy#result() result}
     * @throws IllegalStateException if this task scope is closed
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws ExecutionException if the policy's {@code result} method throws, or with
     * cause {@link TimeoutException} if a timeout is set and the timeout expires
     * @throws InterruptedException if interrupted while waiting
     * @since 24
     */
    public R join() throws ExecutionException, InterruptedException {
        ensureOwner();
        ensureOpen();

        if (!joined) {
            // owner has attempted to join
            needToJoin = false;

            // wait for all threads, execution to be cancelled, or interrupt
            flock.awaitAll();

            // throw if timeout expired while waiting
            if (timeoutExpired) {
                throw new ExecutionException(new TimeoutException());
            }

            // join completed successfully
            cancelTimeout();
            joined = true;
        }

        // invoke policy to get result
        try {
            return policy.result();
        } catch (Throwable e) {
            throw new ExecutionException(e);
        }
    }

    /**
     * {@return {@code true} if <a href="#CancelExecution">execution is cancelled</a>,
     * or in the process of being cancelled, otherwise {@code false}}
     *
     * <p> Cancelling execution prevents new threads from starting in the task scope and
     * {@linkplain Thread#interrupt() interrupts} threads executing unfinished subtasks.
     * It may take some time before the interrupted threads finish execution; this
     * method may return {@code true} before all threads have been interrupted or before
     * all threads have finished.
     *
     * @apiNote A main task with a lengthy "forking phase" (the code that executes before
     * the main task invokes {@link #join() join}) may use this method to avoid doing work
     * in cases where execution was cancelled by the completion of a previously forked
     * subtask or timeout.
     *
     * @since 24
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Closes this task scope.
     *
     * <p> This method first <a href="#CancelExecution">cancels execution</a>, if not
     * already cancelled. This interrupts the threads executing unfinished subtasks. This
     * method then waits for all threads to finish. If interrupted while waiting then it
     * will continue to wait until the threads finish, before completing with the interrupt
     * status set.
     *
     * <p> This method may only be invoked by the task scope owner. If the task scope
     * is already closed then the task scope owner invoking this method has no effect.
     *
     * <p> A {@code StructuredTaskScope} is intended to be used in a <em>structured
     * manner</em>. If this method is called to close a task scope before nested task
     * scopes are closed then it closes the underlying construct of each nested task scope
     * (in the reverse order that they were created in), closes this task scope, and then
     * throws {@link StructureViolationException}.
     * Similarly, if this method is called to close a task scope while executing with
     * {@linkplain ScopedValue scoped value} bindings, and the task scope was created
     * before the scoped values were bound, then {@code StructureViolationException} is
     * thrown after closing the task scope.
     * If a thread terminates without first closing task scopes that it owns then
     * termination will cause the underlying construct of each of its open tasks scopes to
     * be closed. Closing is performed in the reverse order that the task scopes were
     * created in. Thread termination may therefore be delayed when the task scope owner
     * has to wait for threads forked in these task scopes to finish.
     *
     * @throws IllegalStateException thrown after closing the task scope if the task scope
     * owner did not attempt to join after forking
     * @throws WrongThreadException if the current thread is not the task scope owner
     * @throws StructureViolationException if a structure violation was detected
     */
    @Override
    public void close() {
        ensureOwner();
        if (closed) {
            return;
        }

        // cancel execution if not already joined
        if (!joined) {
            cancelExecution();
            cancelTimeout();
        }

        // wait for stragglers to finish
        try {
            flock.close();
        } finally {
            closed = true;
        }

        // throw ISE if the owner didn't attempt to join after forking
        if (needToJoin) {
            needToJoin = false;
            throw new IllegalStateException("Owner did not join");
        }
    }

    /**
     * {@inheritDoc}  If a {@link Config#withName(String) name} for monitoring and
     * monitoring purposes has been set then the string representation includes the name.
     */
    @Override
    public String toString() {
        return flock.name();
    }

    /**
     * Subtask implementation, runs the task specified to the fork method.
     */
    private static final class SubtaskImpl<T> implements Subtask<T>, Runnable {
        private static final AltResult RESULT_NULL = new AltResult(Subtask.State.SUCCESS);

        private record AltResult(Subtask.State state, Throwable exception) {
            AltResult(Subtask.State state) {
                this(state, null);
            }
        }

        private final StructuredTaskScope<? super T, ?> scope;
        private final Callable<? extends T> task;
        private volatile Object result;

        SubtaskImpl(StructuredTaskScope<? super T, ?> scope, Callable<? extends T> task) {
            this.scope = scope;
            this.task = task;
        }

        @Override
        public void run() {
            T result = null;
            Throwable ex = null;
            try {
                result = task.call();
            } catch (Throwable e) {
                ex = e;
            }

            // nothing to do if task scope is cancelled
            if (scope.isCancelled())
                return;

            // set result/exception and invoke onComplete
            if (ex == null) {
                this.result = (result != null) ? result : RESULT_NULL;
            } else {
                this.result = new AltResult(State.FAILED, ex);
            }
            scope.onComplete(this);
        }

        @Override
        public Subtask.State state() {
            Object result = this.result;
            if (result == null) {
                return State.UNAVAILABLE;
            } else if (result instanceof AltResult alt) {
                // null or failed
                return alt.state();
            } else {
                return State.SUCCESS;
            }
        }


        @Override
        public T get() {
            scope.ensureJoinedIfOwner();
            Object result = this.result;
            if (result instanceof AltResult) {
                if (result == RESULT_NULL) return null;
            } else if (result != null) {
                @SuppressWarnings("unchecked")
                T r = (T) result;
                return r;
            }
            throw new IllegalStateException(
                    "Result is unavailable or subtask did not complete successfully");
        }

        @Override
        public Throwable exception() {
            scope.ensureJoinedIfOwner();
            Object result = this.result;
            if (result instanceof AltResult alt && alt.state() == State.FAILED) {
                return alt.exception();
            }
            throw new IllegalStateException(
                    "Exception is unavailable or subtask did not complete with exception");
        }

        @Override
        public String toString() {
            String stateAsString = switch (state()) {
                case UNAVAILABLE -> "[Unavailable]";
                case SUCCESS     -> "[Completed successfully]";
                case FAILED      -> {
                    Throwable ex = ((AltResult) result).exception();
                    yield "[Failed: " + ex + "]";
                }
            };
            return Objects.toIdentityString(this) + stateAsString;
        }
    }

    /**
     * A policy that returns a stream of all subtasks when all subtasks complete
     * successfully. If any subtask fails then execution is cancelled.
     */
    private static final class AllSuccessful<T> implements Policy<T, Stream<Subtask<T>>> {
        private static final VarHandle FIRST_EXCEPTION;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                FIRST_EXCEPTION = l.findVarHandle(AllSuccessful.class, "firstException", Throwable.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        // list of forked subtasks, only accessed by owner thread
        private final List<Subtask<T>> subtasks = new ArrayList<>();
        private volatile Throwable firstException;
        
        @Override
        public boolean onFork(Subtask<? extends T> subtask) {
            @SuppressWarnings("unchecked")
            var tmp = (Subtask<T>) subtask;
            subtasks.add(tmp);
            return false;
        }

        @Override
        public boolean onComplete(Subtask<? extends T> subtask) {
            return (subtask.state() == Subtask.State.FAILED)
                    && (firstException == null)
                    && FIRST_EXCEPTION.compareAndSet(this, null, subtask.exception());
        }

        @Override
        public Stream<Subtask<T>> result() throws Throwable {
            Throwable ex = firstException;
            if (ex != null) {
                throw ex;
            } else {
                return subtasks.stream();
            }
        }
    }

    /**
     * A policy that returns the result of the first subtask to complete successfully.
     * If any subtask completes successfully then execution is cancelled.
     */
    private static final class AnySuccessful<T> implements Policy<T, T> {
        private static final VarHandle FIRST_SUCCESS;
        private static final VarHandle FIRST_EXCEPTION;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                FIRST_SUCCESS = l.findVarHandle(AnySuccessful.class, "firstSuccess", Subtask.class);
                FIRST_EXCEPTION = l.findVarHandle(AnySuccessful.class, "firstException", Throwable.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        private volatile Subtask<T> firstSuccess;
        private volatile Throwable firstException;

        @Override
        public boolean onComplete(Subtask<? extends T> subtask) {
            if (firstSuccess == null) {
                if (subtask.state() == Subtask.State.SUCCESS) {
                    // capture the first subtask that completes successfully
                    return FIRST_SUCCESS.compareAndSet(this, null, subtask);
                } else if (firstException == null) {
                    // capture the exception thrown by the first task to fail
                    FIRST_EXCEPTION.compareAndSet(this, null, subtask.exception());
                }
            }
            return false;
        }

        @Override
        public T result() throws Throwable {
            Subtask<T> firstSuccess = this.firstSuccess;
            if (firstSuccess != null) {
                return firstSuccess.get();
            }
            Throwable firstException = this.firstException;
            if (firstException != null) {
                throw firstException;
            } else {
                throw new NoSuchElementException("No subtasks completed");
            }
        }
    }

    /**
     * A policy that that ignores all successful subtasks. If any subtask fails the
     * execution is cancelled.
     */
    private static final class IgnoreSuccessful<T> implements Policy<T, Void> {
        private static final VarHandle FIRST_EXCEPTION;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                FIRST_EXCEPTION = l.findVarHandle(IgnoreSuccessful.class, "firstException", Throwable.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        private volatile Throwable firstException;

        @Override
        public boolean onComplete(Subtask<? extends T> subtask) {
            return (subtask.state() == Subtask.State.FAILED)
                    && (firstException == null)
                    && FIRST_EXCEPTION.compareAndSet(this, null, subtask.exception());
        }

        @Override
        public Void result() throws Throwable {
            Throwable ex = firstException;
            if (ex != null) {
                throw ex;
            } else {
                return null;
            }
        }
    }

    /**
     * A policy that returns a stream of all subtasks.
     */
    private static class AllSubtasks<T> implements Policy<T, Stream<Subtask<T>>> {
        private final Predicate<Subtask<? extends T>> isDone;
        // list of forked subtasks, only accessed by owner thread
        private final List<Subtask<T>> subtasks = new ArrayList<>();

        AllSubtasks(Predicate<Subtask<? extends T>> isDone) {
            this.isDone = Objects.requireNonNull(isDone);
        }

        @Override
        public boolean onFork(Subtask<? extends T> subtask) {
            @SuppressWarnings("unchecked")
            var tmp = (Subtask<T>) subtask;
            subtasks.add(tmp);
            return false;
        }

        @Override
        public boolean onComplete(Subtask<? extends T> subtask) {
            return isDone.test(subtask);
        }

        @Override
        public Stream<Subtask<T>> result() {
            return subtasks.stream();
        }
    }

    /**
     * Implementation of Config.
     */
    private record ConfigImpl(ThreadFactory threadFactory,
                              String name,
                              Duration timeout) implements Config {
        static Config defaultConfig() {
            return new ConfigImpl(Thread.ofVirtual().factory(),null,  null);
        }

        @Override
        public Config withThreadFactory(ThreadFactory threadFactory) {
            return new ConfigImpl(Objects.requireNonNull(threadFactory), name, timeout);
        }

        @Override
        public Config withName(String name) {
            return new ConfigImpl(threadFactory, Objects.requireNonNull(name), timeout);
        }

        @Override
        public Config withTimeout(Duration timeout) {
            return new ConfigImpl(threadFactory, name, Objects.requireNonNull(timeout));
        }
    }

    /**
     * Used to schedule a task to cancel exception when a timeout expires.
     */
    private static class TimerSupport {
        private static final ScheduledExecutorService DELAYED_TASK_SCHEDULER;
        static {
            ScheduledThreadPoolExecutor stpe = (ScheduledThreadPoolExecutor)
                Executors.newScheduledThreadPool(1, task -> {
                    Thread t = InnocuousThread.newThread("StructuredTaskScope-Timer", task);
                    t.setDaemon(true);
                    return t;
                });
            stpe.setRemoveOnCancelPolicy(true);
            DELAYED_TASK_SCHEDULER = stpe;
        }

        static Future<?> schedule(Duration timeout, Runnable task) {
            long nanos = TimeUnit.NANOSECONDS.convert(timeout);
            return DELAYED_TASK_SCHEDULER.schedule(task, nanos, TimeUnit.NANOSECONDS);
        }
    }
}
