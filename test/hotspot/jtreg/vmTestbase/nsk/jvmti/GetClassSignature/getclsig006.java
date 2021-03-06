/*
 * Copyright (c) 2003, 2018, Oracle and/or its affiliates. All rights reserved.
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

package nsk.jvmti.GetClassSignature;

import java.io.*;
import java.util.*;

import nsk.share.*;

/**
 * This test checks that the JVMTI function <code>GetClassSignature()</code>
 * returns generic signature information properly.<br>
 * The test creates instances of tested classes. Some of them are generic.
 * Then an agent part obtains the class signatures. Proper generic signature
 * should be returned for the generic classes, or NULL otherwise.
 */
public class getclsig006 {
    static {
        try {
            System.loadLibrary("getclsig006");
        } catch (UnsatisfiedLinkError ule) {
            System.err.println("Could not load \"getclsig006\" library");
            System.err.println("java.library.path:"
                + System.getProperty("java.library.path"));
            throw ule;
        }
    }

    native int check();

    public static void main(String[] argv) {
        argv = nsk.share.jvmti.JVMTITest.commonInit(argv);

        // produce JCK-like exit status
        System.exit(run(argv, System.out) + Consts.JCK_STATUS_BASE);
    }

    public static int run(String argv[], PrintStream out) {
        return new getclsig006().runThis(argv, out);
    }

    private int runThis(String argv[], PrintStream out) {
        // create instances of tested classes
        getclsig006b<String> _getclsig006b =
            new getclsig006b<String>();
        getclsig006c<Boolean, Integer> _getclsig006c =
            new getclsig006c<Boolean, Integer>();
        getclsig006if<Object> _getclsig006if =
            new getclsig006d<Object>();
        getclsig006g<getclsig006f> _getclsig006g =
            new getclsig006g<getclsig006f>();

        return check();
    }
}

/*
 * Dummy classes used only for verifying generic signature information
 * in an agent.
 */

class getclsig006b<L extends String> {}

class getclsig006c<A, B extends Integer> {}

interface getclsig006if<I> {}

class getclsig006d<T> implements getclsig006if<T> {}

class getclsig006e {}

class getclsig006f extends getclsig006e implements getclsig006if {}

class getclsig006g<E extends getclsig006e & getclsig006if> {}
