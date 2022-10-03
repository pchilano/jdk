/*
* Copyright (c) 2018, 2021, Oracle and/or its affiliates. All rights reserved.
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
* @modules java.base/jdk.internal.vm
*
* @run testng/othervm/timeout=60 --enable-preview -Xint Preempt3
* @run testng/othervm --enable-preview -XX:-TieredCompilation -Xcomp Preempt3
* @run testng/othervm --enable-preview -XX:TieredStopAtLevel=3 -Xcomp Preempt3
* @run testng/othervm --enable-preview -XX:-UseTLAB -Xint Preempt3
*/

// * @run testng/othervm -XX:+UnlockExperimentalVMOptions -XX:-TieredCompilation -XX:+UseJVMCICompiler -Xcomp -XX:CompileOnly=jdk/internal/vm/Continuation,Preempt3 Preempt3

// TODO:
// - Add tests for failed preemptions

import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import static org.testng.Assert.*;

@Test
public class Preempt3 {
    volatile int x;
    static java.util.concurrent.Semaphore allowPreempt = new java.util.concurrent.Semaphore(0);
    static java.util.concurrent.Semaphore allowRunCont = new java.util.concurrent.Semaphore(0);
    volatile boolean done = false;

    static final ContinuationScope FOO = new ContinuationScope() {};
    final Continuation cont = new Continuation(FOO, ()-> { this.foo(2); });

    public void test1() throws Exception {
        System.out.println("test1");

        final Thread t0 = Thread.currentThread();
        Thread t = new Thread(() -> {
            try {
                allowPreempt.acquire();
                Continuation.PreemptStatus res;
                int i = 0;
                while (true) {
                    res = cont.tryPreempt(t0);
                    if (res == Continuation.PreemptStatus.SUCCESS) {
                        if (!done) {
                            // Avoid checking the stack once done is set since we could have freezed the target
                            // while there is only a lambda method in the stack with random name.
                            List<String> frames = cont.stackWalker().walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
                            assertEquals(frames.removeAll(Arrays.asList("foo", "bar", "baz", "loop")), true);
                        }
                        allowRunCont.release();
                        allowPreempt.acquire();
                    }
                    Thread.sleep(10);
                    i++;
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        t.setDaemon(true);
        t.start();

        // give some time for the daemon to start
        Thread.sleep(5);

        while(true) {
            cont.run();
            if (cont.isDone()) break;
            allowRunCont.acquire();
        }
        assertEquals(cont.isDone(), true);
        System.out.println("Finished executing test");
    }

    private double foo(int a) {
        allowPreempt.release();
        String r = "";
        for (int k = 1; k < 300; k++) {
            int x = 3;
            String s = "abc";
            r = bar(a + 1, 3, 4, 5, 6, 7, 8);
            // System.out.println("finished loop " + k);
            // System.out.flush();
        }
        done = true;
        return Integer.parseInt(r)+1;
    }

    private String bar(long b, int a, int c, int d, int e, int f, int g) {
        double x = 9.99;
        String s = "zzz";
        String r = baz(b + 1, 3, 4, 5, 6, 7, 8);
        return "" + r;
    }

    private String baz(long b, int a, int c, int d, int e, int f, int g) {
        double x = 9.99;
        String s = "zzz";
        loop(4, 10, 16);
        long r = b+1;
        return "" + r;
    }

    private void loop(int a, int b, int c) {
        long start_time = System.currentTimeMillis();
        // loop for 10 ms
        while (System.currentTimeMillis() < start_time + 10) {
            x++;
            if (allowPreempt.hasQueuedThreads()) {
                allowPreempt.release();
            }
        }
    }
}

