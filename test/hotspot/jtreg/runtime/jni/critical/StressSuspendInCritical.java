/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 8373839
 * @requires vm.jvmti
 * @library /test/lib /test/hotspot/jtreg/testlibrary
 * @build jdk.test.whitebox.WhiteBox StressSuspendInCritical
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm/native -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:+UseNewCode StressSuspendInCritical 1000
 */

import java.util.concurrent.Phaser;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

import jvmti.JVMTIUtils;

public class StressSuspendInCritical {
    private static volatile boolean done;
    private static Phaser sync = new Phaser(4);
    private static int baseSleepTime = 5;

    static native void criticalSection(byte[] array, int sleepMillis);

    public static void main(String[] args) throws Throwable {
        int iterations = Integer.parseInt(args[0]);
        System.loadLibrary("StressSuspendInCritical");

        Thread critical = Thread.ofPlatform().name("CriticalThread").start(() -> {
            byte[] array = new byte[64];
            for (int i = 0; i < iterations; i++) {
                sync.arriveAndAwaitAdvance();
                try {
                    Thread.sleep(baseSleepTime);
                } catch (Exception e) {}
                criticalSection(array, 4 * baseSleepTime);
            }
        });

        Thread suspender = Thread.ofPlatform().name("SuspenderThread").start(() -> {
            for (int i = 0; i < iterations; i++) {
                sync.arriveAndAwaitAdvance();
                try {
                    Thread.sleep(2 * baseSleepTime);
                    JVMTIUtils.suspendThread(critical);
                    JVMTIUtils.resumeThread(critical);
                } catch (Exception e) {}
            }
        });

        Thread handshaker = Thread.ofPlatform().name("HandshakerThread").start(() -> {
            for (int i = 0; i < iterations; i++) {
                sync.arriveAndAwaitAdvance();
                WhiteBox wb = WhiteBox.getWhiteBox();
                wb.handshakeReadMonitors(critical);
            }
        });

        for (int i = 0; i < iterations; i++) {
            sync.arriveAndAwaitAdvance();
        }

        critical.join();
        suspender.join();
        handshaker.join();
    }
}
