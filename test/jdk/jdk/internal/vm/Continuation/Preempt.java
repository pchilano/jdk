/*
* Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

/**
* @test
* @summary Tests for jdk.internal.vm.Continuation preemption
* @requires vm.continuations
* @modules java.base/jdk.internal.vm
*
* @run testng/othervm --enable-preview -XX:+IgnoreUnrecognizedVMOptions -XX:-VerifyContinuations -Xint Preempt
* @run testng/othervm --enable-preview -XX:+IgnoreUnrecognizedVMOptions -XX:-VerifyContinuations -XX:-TieredCompilation -Xcomp -XX:CompileOnly=jdk/internal/vm/Continuation,Preempt Preempt
* @run testng/othervm --enable-preview -XX:+IgnoreUnrecognizedVMOptions -XX:-VerifyContinuations -XX:TieredStopAtLevel=3 -Xcomp -XX:CompileOnly=jdk/internal/vm/Continuation,Preempt Preempt
* @run testng/othervm --enable-preview -XX:+IgnoreUnrecognizedVMOptions -XX:-VerifyContinuations -XX:-UseTLAB -Xint Preempt
*/

/**
* @test
* @summary Tests for jdk.internal.vm.Continuation preemption
* @requires vm.continuations
* @requires vm.debug
* @modules java.base/jdk.internal.vm
*
* @run testng/othervm --enable-preview -XX:+VerifyContinuations -Xint Preempt
* @run testng/othervm --enable-preview -XX:+VerifyContinuations -XX:+DeoptimizeALot -XX:+UseZGC Preempt
*/

import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import static org.testng.Assert.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;

public class Preempt {
    static final ContinuationScope FOO = new ContinuationScope() {};
    volatile int x = 0;

    class PreemptTest {
        Semaphore runContSem = new Semaphore(1);
        Continuation cont;
        Thread target;
        boolean done = false;
        boolean doGC = false;
        static final Integer countToGC = 20;

        PreemptTest(Continuation c) {
            cont = c;
            target = Thread.currentThread();
        }

        PreemptTest(Continuation c, Boolean gc) {
            cont = c;
            target = Thread.currentThread();
            doGC = gc;
        }

        void runOnce() throws Exception {
            runContSem.acquire();
            assertEquals(runContSem.availablePermits(), 0);
            cont.run();
        }

        void preempterLoop() {
            Continuation.PreemptStatus res;
            long iterStartTime = System.currentTimeMillis();
            boolean slowdown = false;
            Integer freezeCount = 0;

            while (!done) {
                res = cont.tryPreempt(target);
                if (res == Continuation.PreemptStatus.SUCCESS) {
                    List<String> frames = cont.stackWalker().walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
                    assertEquals(frames.containsAll(Arrays.asList("enter")), true);
                    if (doGC && freezeCount++ == countToGC) {
                        System.gc();
                        freezeCount = 0;
                    }
                    assertEquals(runContSem.availablePermits(), 0);
                    runContSem.release();
                } else {
                    assertEquals(res != Continuation.PreemptStatus.TRANSIENT_FAIL_PINNED_CRITICAL_SECTION && res != Continuation.PreemptStatus.PERM_FAIL_UNSUPPORTED, true);
                }
                if (slowdown) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                if (System.currentTimeMillis() - iterStartTime > 50) {
                    slowdown = !slowdown;
                    iterStartTime = System.currentTimeMillis();
                }
            }
        }

        void startPreempter() {
            Thread t = new Thread(() -> preempterLoop());
            t.start();
        }

        void endTest() {
            done = true;
        }
    }

    @Test
    public void test1() throws Exception {
        // Basic preempt test
        System.out.println("test1");
        Continuation cont = new Continuation(FOO, () -> { this.loop(); });

        PreemptTest ptest = new PreemptTest(cont);
        ptest.startPreempter();

        Integer iterations = 100;
        while (iterations-- > 0) {
            ptest.runOnce();
            assertEquals(cont.isDone(), false);
            assertEquals(cont.isPreempted(), true);

            List<String> frames = cont.stackWalker().walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
            assertEquals(frames.containsAll(Arrays.asList("loop", "enter")), true);
        }
        ptest.endTest();
    }

    private void loop() {
        while (true) {
            x++;
            recurse(20);
        }
    }

    private void recurse(int depth) {
        if (depth > 0) {
            recurse(depth - 1);
        }
    }

    @Test
    public void test2() throws Exception {
        // Methods with stack-passed arguments
        System.out.println("test2");
        final AtomicInteger res = new AtomicInteger(0);
        Continuation cont = new Continuation(FOO, () -> { this.foo(res); });

        PreemptTest ptest = new PreemptTest(cont);
        ptest.startPreempter();

        while(!cont.isDone()) {
            ptest.runOnce();
            if (cont.isDone()) break;
            assertEquals(cont.isPreempted(), true);

            List<String> frames = cont.stackWalker().walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
            assertEquals(frames.containsAll(Arrays.asList("foo", "enter")) || res.get() == 1, true);
        }
        assertEquals(cont.isPreempted(), false);
        ptest.endTest();
    }

    private double foo(AtomicInteger ai) {
        String r = "";
        for (int k = 1; k < 300; k++) {
            int x = 3;
            String s = "abc";
            r = bar(3L, 3, 4, 5, 6, 7, 8,
                    1.1f, 2.2, 3.3, 4.4, 5.5, 6.6, 7.7,
                    1001, 1002, 1003, 1004, 1005, 1006, 1007);
        }
        double result = Double.parseDouble(r)+1;
        ai.set(1);
        return result;
    }

    private String bar(long l1, int i1, int i2, int i3, int i4, int i5, int i6,
                       float f1, double d1, double d2, double d3, double d4, double d5, double d6,
                       Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7) {
        double x = 9.99;
        String s = "zzz";
        String r = baz(i6, i5, i4, i3, i2, i1, l1,
                       f1, d1 + x, d2, d3, d4, d5, d6,
                       o1, o2, o3, o4, o5, o6, o7);
        return "" + r;
    }

    private String baz(int i1, int i2, int i3, int i4, int i5, int i6, long l1,
                       float f1, double d1, double d2, double d3, double d4, double d5, double d6,
                       Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7) {
        double x = 9.99 + d3;
        String s = "zzz";
        loop(i4, i5, i6);
        double r = l1 + x;
        return "" + r;
    }

    private void loop(int a, int b, int c) {
        long start_time = System.currentTimeMillis();
        // loop for 10 ms
        while (System.currentTimeMillis() < start_time + 10) {
            x++;
        }
    }

    @Test
    public void test3() throws Exception {
        // Return oop on stub case
        System.out.println("test3");
        final AtomicInteger res = new AtomicInteger(0);
        Continuation cont = new Continuation(FOO, () -> { this.foo2(res); });

        PreemptTest ptest = new PreemptTest(cont, true /* doGC */);
        ptest.startPreempter();

        while(!cont.isDone()) {
            ptest.runOnce();
            if (cont.isDone()) break;
            assertEquals(cont.isPreempted(), true);

            List<String> frames = cont.stackWalker().walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
            assertEquals(frames.containsAll(Arrays.asList("foo2", "enter")) || res.get() == 1, true);
        }
        assertEquals(cont.isPreempted(), false);
        ptest.endTest();
    }

    private void foo2(AtomicInteger ai) {
        for (int k = 1; k < 15000; k++) {
            x++;
            List<Integer> list = new ArrayList<>();
            recurse(list, 40);
            checkListContents(list, 41);
        }
        ai.set(1);
    }

    private List<Integer> recurse(List<Integer> list, int depth) {
        x++;
        list.add(depth);
        if (depth > 0) {
            return recurse(list, depth - 1);
        }
        return list;
    }

    private void checkListContents(List<Integer> l, int n) {
        for (int i = 0; i < n; i++) {
            assertEquals(l.contains(i), true);
        }
    }

    @Test
    public void test4() throws Exception {
        // Multiple preempters
        System.out.println("test4");
        Continuation cont = new Continuation(FOO, () -> { this.loop(); });

        PreemptTest ptest = new PreemptTest(cont);
        ptest.startPreempter();
        ptest.startPreempter();
        ptest.startPreempter();

        Integer iterations = 100;
        while (iterations-- > 0) {
            ptest.runOnce();
            assertEquals(cont.isDone(), false);
            assertEquals(cont.isPreempted(), true);

            List<String> frames = cont.stackWalker().walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
            assertEquals(frames.containsAll(Arrays.asList("loop", "enter")), true);
        }
        ptest.endTest();
    }
}