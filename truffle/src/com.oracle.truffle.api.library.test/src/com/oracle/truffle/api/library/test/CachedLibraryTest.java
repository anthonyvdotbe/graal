/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.library.test;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.library.GenerateLibrary;
import com.oracle.truffle.api.library.Library;
import com.oracle.truffle.api.library.test.CachedLibraryTestFactory.AssumptionNodeGen;
import com.oracle.truffle.api.library.test.CachedLibraryTestFactory.ConstantLimitNodeGen;
import com.oracle.truffle.api.library.test.CachedLibraryTestFactory.ConstantNodeGen;
import com.oracle.truffle.api.library.test.CachedLibraryTestFactory.DoubleNodeGen;
import com.oracle.truffle.api.library.test.CachedLibraryTestFactory.ExcludeNodeGen;
import com.oracle.truffle.api.library.test.CachedLibraryTestFactory.FromCached1NodeGen;
import com.oracle.truffle.api.library.test.CachedLibraryTestFactory.FromCached2NodeGen;
import com.oracle.truffle.api.library.test.CachedLibraryTestFactory.SimpleDispatchedNodeGen;
import com.oracle.truffle.api.library.test.CachedLibraryTestFactory.SimpleNodeGen;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.test.ExpectError;

public class CachedLibraryTest extends AbstractLibraryTest {

    @GenerateLibrary
    @SuppressWarnings("unused")
    public abstract static class SomethingLibrary extends Library {

        public String call(Object receiver) {
            return "default";
        }

    }

    @ExportLibrary(SomethingLibrary.class)
    public static class Something {

        private final String name;

        Something() {
            this(null);
        }

        Something(String name) {
            this.name = name;
        }

        @ExportMessage
        boolean accepts(@Cached(value = "this") Something cachedS) {
            // use identity caches to make it easier to overflow
            return this == cachedS;
        }

        @ExportMessage
        static final String call(Something s) {
            if (s.name != null) {
                return s.name + "_uncached";
            } else {
                return "uncached";
            }

        }

        @ExportMessage
        static class CallNode extends Node {
            @Specialization
            static final String call(Something s) {
                if (s.name != null) {
                    return s.name + "_cached";
                } else {
                    return "cached";
                }
            }
        }

    }

    @GenerateUncached
    public abstract static class SimpleNode extends Node {

        abstract String execute(Object receiver);

        @Specialization(limit = "2")
        public static String s0(Object receiver,
                        @CachedLibrary("receiver") SomethingLibrary lib1) {
            return lib1.call(receiver);
        }
    }

    @Test
    public void testSimple() {
        Something s1 = new Something();
        Something s2 = new Something();
        Something s3 = new Something();

        assertEquals("uncached", SimpleNodeGen.getUncached().execute(s1));
        assertEquals("uncached", SimpleNodeGen.getUncached().execute(s2));

        SimpleNode node = adopt(SimpleNodeGen.create());
        assertEquals("cached", node.execute(s1));
        assertEquals("cached", node.execute(s2));
        assertEquals("uncached", node.execute(s3));
        assertEquals("uncached", node.execute(s1));
        assertEquals("uncached", node.execute(s2));
    }

    @GenerateUncached
    public abstract static class ExcludeNode extends Node {

        abstract String execute(Object receiver);

        private static int call = 0;

        @Specialization(limit = "2", rewriteOn = ArithmeticException.class)
        public static String s0(Object receiver,
                        @CachedLibrary("receiver") SomethingLibrary lib1) throws ArithmeticException {
            if (call++ == 1) {
                throw new ArithmeticException();
            }
            return lib1.call(receiver) + "_s0";
        }

        @Specialization(replaces = "s0", limit = "2")
        public static String s1(Object receiver,
                        @CachedLibrary("receiver") SomethingLibrary lib1) throws ArithmeticException {
            return lib1.call(receiver) + "_s1";
        }

    }

    @Test
    public void testExclude() {
        Something s1 = new Something();
        Something s2 = new Something();
        Something s3 = new Something();

        assertEquals("uncached_s1", ExcludeNodeGen.getUncached().execute(s1));
        assertEquals("uncached_s1", ExcludeNodeGen.getUncached().execute(s2));
        assertEquals("uncached_s1", ExcludeNodeGen.getUncached().execute(s3));

        ExcludeNode.call = 0;
        ExcludeNode node = adopt(ExcludeNodeGen.create());
        assertEquals("cached_s0", node.execute(s1));
        assertEquals("cached_s1", node.execute(s2));
        assertEquals("cached_s1", node.execute(s3));
        assertEquals("uncached_s1", node.execute(s1));
        assertEquals("uncached_s1", node.execute(s2));
    }

    @GenerateUncached
    public abstract static class AssumptionNode extends Node {

        abstract String execute(Object receiver);

        static Assumption a;

        @Specialization(limit = "2", assumptions = "a")
        public static String s0(Object receiver,
                        @CachedLibrary("receiver") SomethingLibrary lib1) throws ArithmeticException {
            return lib1.call(receiver) + "_s0";
        }

        @Specialization(replaces = "s0", limit = "2")
        public static String s1(Object receiver,
                        @CachedLibrary("receiver") SomethingLibrary lib1) throws ArithmeticException {
            return lib1.call(receiver) + "_s1";
        }

    }

    @Test
    public void testAssumption() {
        Something s1 = new Something();
        Something s2 = new Something();
        Something s3 = new Something();

        assertEquals("uncached_s1", AssumptionNodeGen.getUncached().execute(s1));
        assertEquals("uncached_s1", AssumptionNodeGen.getUncached().execute(s2));
        assertEquals("uncached_s1", AssumptionNodeGen.getUncached().execute(s3));

        // test with null assumption
        AssumptionNode.a = null;
        AssumptionNode node = adopt(AssumptionNodeGen.create());
        assertEquals("cached_s1", node.execute(s1));
        assertEquals("cached_s1", node.execute(s2));
        assertEquals("uncached_s1", node.execute(s3));
        assertEquals("uncached_s1", node.execute(s2));
        assertEquals("uncached_s1", node.execute(s1));

        node = adopt(AssumptionNodeGen.create());
        Assumption a0 = AssumptionNode.a = Truffle.getRuntime().createAssumption();
        assertEquals("cached_s0", node.execute(s1));
        AssumptionNode.a = Truffle.getRuntime().createAssumption();
        assertEquals("cached_s0", node.execute(s2));
        a0.invalidate();
        AssumptionNode.a = Truffle.getRuntime().createAssumption();
        assertEquals("cached_s0", node.execute(s3));

        Assumption uncached = AssumptionNode.a = Truffle.getRuntime().createAssumption();
        assertEquals("uncached_s0", node.execute(s1));

        assertEquals("uncached_s0", node.execute(s2));
        assertEquals("uncached_s0", node.execute(s3));
        uncached.invalidate();
        assertEquals("cached_s1", node.execute(s1));
        assertEquals("cached_s1", node.execute(s2));
        assertEquals("uncached_s1", node.execute(s3));
    }

    @GenerateUncached
    @SuppressWarnings("unused")
    public abstract static class ConstantNode extends Node {

        abstract String execute(Object receiver);

        @Specialization
        public static String s1(Object receiver,
                        @CachedLibrary("42") SomethingLibrary lib) {
            return lib.call(42);
        }
    }

    @Test
    public void testConstant() {
        assertEquals("default", ConstantNodeGen.getUncached().execute(42));

        ConstantNode node = adopt(ConstantNodeGen.create());
        assertEquals("default", node.execute(42));
    }

    /*
     * Test that if the library receiver is bound to a cached we don't actually need to insert an
     * accepts guard. So we will not trigger multiple instances (unless the specialization does so).
     */
    @GenerateUncached
    @SuppressWarnings("unused")
    public abstract static class FromCached1Node extends Node {

        abstract String execute(Object receiver);

        @Specialization(guards = "receiver == cachedReceiver")
        public static String s1(Object receiver,
                        @Cached("receiver") Object cachedReceiver,
                        @CachedLibrary("cachedReceiver") SomethingLibrary lib) {
            return lib.call(cachedReceiver);
        }
    }

    @Test
    public void testFromCached1() {
        Something s1 = new Something("s1");
        Something s2 = new Something("s2");
        FromCached1Node uncached = FromCached1NodeGen.getUncached();
        assertEquals("s1_uncached", uncached.execute(s1));
        assertEquals("s2_uncached", uncached.execute(s2));

        FromCached1Node cached = adopt(FromCached1NodeGen.create());
        assertEquals("s1_cached", cached.execute(s1));
        assertEquals("s2_cached", cached.execute(s2));
    }

    /*
     * Same as FromCached1Node but without identity cache.
     */
    @GenerateUncached
    @SuppressWarnings("unused")
    public abstract static class FromCached2Node extends Node {

        abstract String execute(Object receiver);

        @Specialization
        public static String s1(Object receiver,
                        @Cached("receiver") Object cachedReceiver,
                        @CachedLibrary("cachedReceiver") SomethingLibrary lib) {
            return lib.call(cachedReceiver);
        }
    }

    @Test
    public void testFromCached2() {
        Something s1 = new Something("s1");
        Something s2 = new Something("s2");
        FromCached2Node uncached = FromCached2NodeGen.getUncached();
        assertEquals("s1_uncached", uncached.execute(s1));
        assertEquals("s2_uncached", uncached.execute(s2));

        FromCached2Node cached = adopt(FromCached2NodeGen.create());
        assertEquals("s1_cached", cached.execute(s1));
        assertEquals("s1_cached", cached.execute(s2));
    }

    @GenerateUncached
    @SuppressWarnings("unused")
    public abstract static class DoubleNode extends Node {

        abstract String execute(Object a0, Object a1);

        @Specialization(limit = "4")
        public static String s1(Object a0, Object a1,
                        @CachedLibrary("a0") SomethingLibrary lib1,
                        @CachedLibrary("a1") SomethingLibrary lib2) {
            return lib1.call(a0) + "_" + lib2.call(a1);
        }
    }

    @Test
    public void testDouble() {
        Something s1 = new Something("s1");
        Something s2 = new Something("s2");
        Something s3 = new Something("s3");
        DoubleNode uncached = adopt(DoubleNodeGen.getUncached());
        assertEquals("s1_uncached_s1_uncached", uncached.execute(s1, s1));
        assertEquals("s1_uncached_s2_uncached", uncached.execute(s1, s2));
        assertEquals("s2_uncached_s1_uncached", uncached.execute(s2, s1));
        assertEquals("s1_uncached_s3_uncached", uncached.execute(s1, s3));
        assertEquals("s3_uncached_s1_uncached", uncached.execute(s3, s1));

        DoubleNode cached = adopt(DoubleNodeGen.create());
        assertEquals("s1_cached_s1_cached", cached.execute(s1, s1));
        assertEquals("s1_cached_s1_cached", cached.execute(s1, s1));
        assertEquals("s1_cached_s2_cached", cached.execute(s1, s2));
        assertEquals("s2_cached_s1_cached", cached.execute(s2, s1));
        assertEquals("s2_cached_s2_cached", cached.execute(s2, s2));

        assertEquals("s1_cached_s1_cached", cached.execute(s1, s1));
        assertEquals("s1_cached_s1_cached", cached.execute(s1, s1));
        assertEquals("s1_cached_s2_cached", cached.execute(s1, s2));
        assertEquals("s2_cached_s1_cached", cached.execute(s2, s1));
        assertEquals("s2_cached_s2_cached", cached.execute(s2, s2));

        assertEquals("s3_uncached_s1_uncached", cached.execute(s3, s1));
        assertEquals("s1_uncached_s1_uncached", cached.execute(s1, s1));
        assertEquals("s2_uncached_s1_uncached", cached.execute(s2, s1));
        assertEquals("s1_uncached_s2_uncached", cached.execute(s1, s2));
        assertEquals("s2_uncached_s2_uncached", cached.execute(s2, s2));
    }

    @GenerateUncached
    @SuppressWarnings("unused")
    public abstract static class SimpleDispatchedNode extends Node {

        static int limit = 2;

        abstract String execute(Object a0);

        @Specialization
        public static String s1(Object a0,
                        @CachedLibrary(limit = "limit") SomethingLibrary lib1) {
            return lib1.call(a0);
        }
    }

    @Test
    public void testDispatched() {
        SimpleDispatchedNode uncached;
        SimpleDispatchedNode cached;
        Something s1 = new Something("s1");
        Something s2 = new Something("s2");
        Something s3 = new Something("s3");

        uncached = adopt(SimpleDispatchedNodeGen.getUncached());
        assertEquals("s1_uncached", uncached.execute(s1));
        assertEquals("s1_uncached", uncached.execute(s1));
        assertEquals("s2_uncached", uncached.execute(s2));
        assertEquals("s3_uncached", uncached.execute(s3));

        SimpleDispatchedNode.limit = 0;
        cached = adopt(SimpleDispatchedNodeGen.create());
        assertEquals("s1_uncached", cached.execute(s1));
        assertEquals("s1_uncached", cached.execute(s1));
        assertEquals("s2_uncached", cached.execute(s2));
        assertEquals("s3_uncached", cached.execute(s3));
        assertEquals("s1_uncached", cached.execute(s1));

        SimpleDispatchedNode.limit = 1;
        cached = adopt(SimpleDispatchedNodeGen.create());
        assertEquals("s1_cached", cached.execute(s1));
        assertEquals("s1_cached", cached.execute(s1));
        assertEquals("s2_uncached", cached.execute(s2));
        assertEquals("s3_uncached", cached.execute(s3));
        assertEquals("s1_uncached", cached.execute(s1));

        SimpleDispatchedNode.limit = 2;
        cached = adopt(SimpleDispatchedNodeGen.create());
        assertEquals("s1_cached", cached.execute(s1));
        assertEquals("s1_cached", cached.execute(s1));
        assertEquals("s2_cached", cached.execute(s2));
        assertEquals("s3_uncached", cached.execute(s3));
        assertEquals("s2_uncached", cached.execute(s2));
        assertEquals("s1_uncached", cached.execute(s1));

        SimpleDispatchedNode.limit = 3;
        cached = adopt(SimpleDispatchedNodeGen.create());
        assertEquals("s1_cached", cached.execute(s1));
        assertEquals("s1_cached", cached.execute(s1));
        assertEquals("s2_cached", cached.execute(s2));
        assertEquals("s3_cached", cached.execute(s3));
    }

    @SuppressWarnings("unused")
    public abstract static class ConstantLimitNode extends Node {

        static int limit = 2;

        abstract String execute(Object a0);

        @Specialization
        public static String s1(Object a0,
                        @CachedLibrary(limit = "0") SomethingLibrary lib1) {
            return lib1.call(a0);
        }
    }

    @Test
    public void testZeroConstantLimit() {
        Something s1 = new Something("s1");
        Something s2 = new Something("s2");

        ConstantLimitNode cached = ConstantLimitNodeGen.create();
        assertEquals("s1_uncached", cached.execute(s1));
        assertEquals("s2_uncached", cached.execute(s2));
    }

    @SuppressWarnings("unused")
    public abstract static class FallbackTest extends Node {

        static final String TEST_STRING = "test";

        static int limit = 2;

        abstract String execute(Object a0);

        @Specialization(guards = "lib1.call(a0).equals(TEST_STRING)", limit = "3")
        public static String s1(Object a0,
                        @CachedLibrary("a0") SomethingLibrary lib1) {
            return lib1.call(a0);
        }

        @Fallback
        public static String fallback(Object a0) {
            return "";
        }

    }

    @GenerateUncached
    public abstract static class CachedLibraryErrorNode1 extends Node {

        abstract String execute(Object receiver);

        @ExpectError("The limit attribute must be specified if @CachedLibrary is used with a dynamic parameter. E.g. add limit=\"3\" to resolve this.")
        @Specialization
        public static String s1(Object receiver,
                        @CachedLibrary("receiver") SomethingLibrary lib2) {
            return lib2.call(receiver);
        }
    }

    public abstract static class CachedLibraryErrorNode2 extends Node {

        abstract String execute(Object receiver);

        @Specialization(limit = "3")
        public static String s1(Object receiver,
                        @CachedLibrary("receiver") SomethingLibrary lib2) {
            return lib2.call(receiver);
        }
    }

    public abstract static class CachedLibraryErrorNode3 extends Node {

        abstract String execute(Object receiver);

        @Specialization
        public static String s1(Object receiver,
                        @ExpectError("A limit must be specified for a dispatched @CachedLibrary. A @CachedLibrary annotation without value attribute needs to specifiy a limit for the number of " +
                                        "entries in the cache per library. Either specify the limit or specify a value attribute to resolve this.") @CachedLibrary SomethingLibrary lib2) {
            return lib2.call(receiver);
        }
    }

    public abstract static class CachedLibraryErrorNode4 extends Node {

        abstract String execute(Object receiver);

        @Specialization
        public static String s1(Object receiver,
                        @ExpectError("The use of @Cached is not supported for libraries. Use @CachedLibrary instead.") //
                        @Cached SomethingLibrary lib2) {
            return lib2.call(receiver);
        }
    }

    public abstract static class CachedLibraryErrorNode5 extends Node {

        abstract String execute(Object receiver);

        @Specialization
        public static String s1(Object receiver,
                        @ExpectError("Error parsing expression 'foobar': foobar cannot be resolved.") //
                        @CachedLibrary(limit = "foobar") SomethingLibrary lib2) {
            return lib2.call(receiver);
        }
    }

    static class ExplicitReceiver {

    }

    @GenerateLibrary(receiverType = ExplicitReceiver.class)
    @SuppressWarnings("unused")
    public abstract static class ExplicitReceiverLibrary extends Library {

        public String call(Object receiver) {
            return "default";
        }

    }

    @GenerateUncached
    public abstract static class SimpleExplicitReceiverNode extends Node {

        abstract String execute(Object receiver);

        @Specialization(limit = "2")
        public static String s0(Object receiver,
                        @CachedLibrary("receiver") ExplicitReceiverLibrary lib1) {
            return lib1.call(receiver);
        }
    }

}
