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
* @run testng/othervm --enable-preview -XX:TieredStopAtLevel=3 -Xcomp Preempt4
**/

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
public class Preempt4 {
    volatile int x;
    static java.util.concurrent.Semaphore allowPreempt = new java.util.concurrent.Semaphore(0);
    static java.util.concurrent.Semaphore allowRunCont = new java.util.concurrent.Semaphore(0);
    volatile boolean done = false;

    static final ContinuationScope FOO = new ContinuationScope() {};
    final Continuation cont = new Continuation(FOO, ()-> { this.foo(); });

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
                            assertEquals(frames.removeAll(Arrays.asList("foo", "recurse", "checkListContents")), true);
                        }
                        System.gc();
                        allowRunCont.release();
                        allowPreempt.acquire();
                    }
                    Thread.sleep(2);
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

    private void foo() {
        for (int k = 1; k < 30000; k++) {
            x++;
            if (allowPreempt.hasQueuedThreads()) {
                allowPreempt.release();
            }
            List<Integer> list = new ArrayList<>();
            recurse(list, 40);
            checkListContents(list, 41);
        }
        done = true;
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
}

