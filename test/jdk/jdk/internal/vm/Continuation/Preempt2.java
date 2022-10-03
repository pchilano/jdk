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
* @run testng/othervm/timeout=60 --enable-preview -Xint Preempt2
* @run testng/othervm --enable-preview -XX:-TieredCompilation -Xcomp -XX:CompileOnly=jdk/internal/vm/Continuation,Preempt2 Preempt2
* @run testng/othervm --enable-preview -XX:TieredStopAtLevel=3 -Xcomp -XX:CompileOnly=jdk/internal/vm/Continuation,Preempt2 Preempt2
* @run testng/othervm --enable-preview -XX:-UseTLAB -Xint Preempt2
*/

// * @run testng/othervm -XX:+UnlockExperimentalVMOptions -XX:-TieredCompilation -XX:+UseJVMCICompiler -Xcomp -XX:CompileOnly=jdk/internal/vm/Continuation,Preempt2 Preempt2

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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;

@Test
public class Preempt2 {
    static final ContinuationScope FOO = new ContinuationScope() {};
    final Continuation cont = new Continuation(FOO, ()-> { this.recurse(); });
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch preemptLatch = new CountDownLatch(1);
    volatile boolean firstRun = true;
    volatile int x;

    public void test2() throws Exception {
        System.out.println("test2");
        test(cont, "recurse");
    }

    private void test(Continuation cont, String methodName) throws Exception {
        final Thread t0 = Thread.currentThread();
        Thread t = new Thread(() -> {
            try {
                startLatch.await();
                {
                    long start_time = System.currentTimeMillis();
                    Continuation.PreemptStatus res;
                    int i = 0;
                    do {
                        res = cont.tryPreempt(t0);
                        Thread.sleep(10);
                        i++;
                    } while ((System.currentTimeMillis() < start_time + 10000) && res == Continuation.PreemptStatus.TRANSIENT_FAIL_PINNED_NATIVE);
                    assertEquals(res, Continuation.PreemptStatus.SUCCESS, "res: " + res + " i: " + i);
                }
                preemptLatch.await();
                {
                    long start_time = System.currentTimeMillis();
                    Continuation.PreemptStatus res;
                    int i = 0;
                    do {
                        res = cont.tryPreempt(t0);
                        Thread.sleep(10);
                        i++;
                    } while ((System.currentTimeMillis() < start_time + 10000) && res == Continuation.PreemptStatus.TRANSIENT_FAIL_PINNED_NATIVE);
                    assertEquals(res, Continuation.PreemptStatus.SUCCESS, "res: " + res + " i: " + i);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        t.start();

        cont.run();
        assertEquals(cont.isDone(), false);
        assertEquals(cont.isPreempted(), true);

        List<String> frames = cont.stackWalker().walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
        assertEquals(frames.containsAll(Arrays.asList(methodName, "enter")), true);

        firstRun = false;

        cont.run();
        assertEquals(cont.isDone(), false);
        assertEquals(cont.isPreempted(), true);

        t.join();
    }

    private void recurse() {
        while (true) {
            x++;

            if (startLatch.getCount() > 0) {
                startLatch.countDown();
            }
            if (!firstRun && preemptLatch.getCount() > 0) {
                preemptLatch.countDown();
            }

            recurse(20);
        }
    }

    private void recurse(int depth) {
        x++;
        if (depth > 0)
            recurse(depth - 1);
    }
}
