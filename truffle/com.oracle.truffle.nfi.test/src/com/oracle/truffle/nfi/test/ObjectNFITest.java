/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.nfi.test;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.java.JavaInterop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.nfi.test.interop.TestCallback;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class ObjectNFITest extends NFITest {

    private static TruffleObject nativeEnv;

    private static class TestObject implements TruffleObject {

        int intField;

        TestObject() {
            this(0);
        }

        TestObject(int value) {
            intField = value;
        }

        int readField(String field) {
            Assert.assertEquals("field name", "intField", field);
            return intField;
        }

        void writeField(String field, int value) {
            Assert.assertEquals("field name", "intField", field);
            intField = value;
        }

        @Override
        public ForeignAccess getForeignAccess() {
            Assert.fail("unexpected interop access to TestObject");
            return null;
        }
    }

    interface ReadIntField {

        int read(TestObject obj, String field);
    }

    interface WriteIntField {

        void write(TestObject obj, String field, int value);
    }

    @BeforeClass
    public static void initEnv() {
        TruffleObject createNewObject = new TestCallback(0, (args) -> new TestObject());
        TruffleObject readIntField = new TestCallback(2, (args) -> {
            Assert.assertThat("args[0]", args[0], is(instanceOf(TestObject.class)));
            Assert.assertThat("args[1]", args[1], is(instanceOf(String.class)));
            return ((TestObject) args[0]).readField((String) args[1]);
        });
        TruffleObject writeIntField = new TestCallback(3, (args) -> {
            Assert.assertThat("args[0]", args[0], is(instanceOf(TestObject.class)));
            Assert.assertThat("args[1]", args[1], is(instanceOf(String.class)));
            Assert.assertThat("args[2]", args[2], is(instanceOf(Integer.class)));
            ((TestObject) args[0]).writeField((String) args[1], (Integer) args[2]);
            return null;
        });

        TruffleObject initializeEnv = lookupAndBind("initialize_env", "( ():object, (object,string):sint32, (object,string,sint32):void ) : pointer");
        try {
            nativeEnv = (TruffleObject) ForeignAccess.sendExecute(Message.createExecute(3).createNode(), initializeEnv, createNewObject, readIntField, writeIntField);
        } catch (InteropException ex) {
            throw new AssertionError(ex);
        }
    }

    interface DeleteEnv {

        void delete(TruffleObject env);
    }

    @AfterClass
    public static void deleteEnv() {
        TruffleObject deleteEnv = lookupAndBind("delete_env", "(pointer):void");
        JavaInterop.asJavaFunction(DeleteEnv.class, deleteEnv).delete(nativeEnv);
        nativeEnv = null;
    }

    @Test
    public void testCopyAndIncrement() {
        TruffleObject copyAndIncrement = lookupAndBind("copy_and_increment", "(pointer, object) : object");
        TestObject testArg = new TestObject(42);

        Object ret = sendExecute(copyAndIncrement, nativeEnv, testArg);
        Assert.assertThat("return value", ret, is(instanceOf(TestObject.class)));

        TestObject testRet = (TestObject) ret;
        Assert.assertNotSame("return value", testArg, testRet);
        Assert.assertEquals("intField", 43, testRet.intField);
    }

    @Test
    public void testKeepObject() {
        TestObject testArg = new TestObject();

        Object finalRet = run(new TestRootNode() {

            final TruffleObject keepExistingObject = lookupAndBind("keep_existing_object", "(object):pointer");
            final TruffleObject freeAndGetObject = lookupAndBind("free_and_get_object", "(pointer):object");
            final TruffleObject freeAndGetContent = lookupAndBind("free_and_get_content", "(pointer, pointer):sint32");

            @Child Node executeKeepExistingObject = Message.createExecute(1).createNode();
            @Child Node executeFreeAndGetObject = Message.createExecute(1).createNode();
            @Child Node executeFreeAndGetContent = Message.createExecute(2).createNode();

            @Override
            public Object executeTest(VirtualFrame frame) throws InteropException {
                Object obj = frame.getArguments()[0];

                testArg.intField = 42;

                Object nativePtr1 = ForeignAccess.sendExecute(executeKeepExistingObject, keepExistingObject, obj);
                Object nativePtr2 = ForeignAccess.sendExecute(executeKeepExistingObject, keepExistingObject, obj);
                Object nativePtr3 = ForeignAccess.sendExecute(executeKeepExistingObject, keepExistingObject, obj);

                Object ret = ForeignAccess.sendExecute(executeFreeAndGetContent, freeAndGetContent, nativeEnv, nativePtr1);
                assertEquals(42, (int) (Integer) ret);

                testArg.intField--;

                ret = ForeignAccess.sendExecute(executeFreeAndGetContent, freeAndGetContent, nativeEnv, nativePtr2);
                assertEquals(41, (int) (Integer) ret);

                return ForeignAccess.sendExecute(executeFreeAndGetObject, freeAndGetObject, nativePtr3);
            }
        }, testArg);

        Assert.assertThat("return value", finalRet, is(instanceOf(TestObject.class)));
        Assert.assertSame("return value", testArg, finalRet);
    }

    @Test
    public void testKeepNewObject() {
        Object ret = run(new TestRootNode() {

            final TruffleObject keepNewObject = lookupAndBind("keep_new_object", "(pointer):pointer");
            final TruffleObject freeAndGetObject = lookupAndBind("free_and_get_object", "(pointer):object");

            @Child Node executeKeepNewObject = Message.createExecute(1).createNode();
            @Child Node executeFreeAndGetObject = Message.createExecute(1).createNode();

            @Override
            public Object executeTest(VirtualFrame frame) throws InteropException {
                Object nativePtr = ForeignAccess.sendExecute(executeKeepNewObject, keepNewObject, nativeEnv);
                return ForeignAccess.sendExecute(executeFreeAndGetObject, freeAndGetObject, nativePtr);
            }
        });

        Assert.assertThat("return value", ret, is(instanceOf(TestObject.class)));
        Assert.assertEquals("intField", 8472, ((TestObject) ret).intField);
    }
}
