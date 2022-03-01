/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.truffle.espresso.runtime;

import java.util.Arrays;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.espresso.EspressoLanguage;
import com.oracle.truffle.espresso.impl.ArrayKlass;
import com.oracle.truffle.espresso.impl.ContextAccess;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.JavaKind;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.substitutions.JavaType;

/**
 * Class responsible for creating guest objects in Espresso. a helper class {@link AllocationChecks}
 * is also available, and provides checks for validating invocations.
 * <p>
 * Note that methods in {@link GuestAllocator} does not perform validating checks, and assumes they
 * have been performed beforehand. This allows profiling in the caller (for example,
 * {@code BytecodeNode#checkNewMultiArray(Klass, int[])}).
 * <p>
 * Paths that do not require profiling can use the methods in {@link AllocationChecks} to validate
 * the arguments passed to the methods of {@link GuestAllocator}.
 * <p>
 * <p>
 * Methods in this class wil exploit as much as possible the constant-ness of arguments, exploding
 * initialization loops whenever possible. Note that performance will be impacted if {@code this} is
 * not PE constant.
 */
public final class GuestAllocator implements ContextAccess {
    private final EspressoContext context;
    private final EspressoLanguage lang;

    public GuestAllocator(EspressoContext context) {
        this.context = context;
        this.lang = context.getLanguage();
    }

    /**
     * Allocates a new instance of the given class; does not call any constructor. Initializes the
     * class.
     * 
     * @param klass The klass of the reference to allocate. If it is PE-constant, the field
     *            initialization loop can be exploded. This is expected to be the case when
     *            executing the {@code NEW} bytecode, but may not be the case always (for example in
     *            the interpretation of {@code Unsafe#allocateInstace(Class cls)}).
     */
    public StaticObject createNew(ObjectKlass klass) {
        assert AllocationChecks.canAllocateNewReference(klass);
        klass.safeInitialize();
        StaticObject newObj = klass.getLinkedKlass().getShape(false).getFactory().create(klass);
        initInstanceFields(newObj, klass);
        return trackAllocation(klass, newObj);
    }

    /**
     * The cloning mechanism for guest objects, respecting guest {@link Object#clone()}
     * specifications.
     */
    public StaticObject copy(StaticObject toCopy) {
        if (StaticObject.isNull(toCopy)) {
            return toCopy;
        }
        toCopy.checkNotForeign();
        StaticObject obj;
        if (toCopy.getKlass().isArray()) {
            obj = wrapArrayAs((ArrayKlass) toCopy.getKlass(), toCopy.cloneWrappedArray(lang));
        } else {
            try {
                // Call `this.clone()` rather than `super.clone()` to execute the `clone()` methods
                // of generated subtypes.
                obj = (StaticObject) toCopy.clone();
            } catch (CloneNotSupportedException e) {
                throw EspressoError.shouldNotReachHere(e);
            }
        }
        return trackAllocation(obj.getKlass(), obj);
    }

    /**
     * Creates the guest world {@linkplain Class representation} of {@link Klass}.
     * 
     * @param klass The klass for which to create the mirror (not guest {@link Class}).
     */
    public StaticObject createClass(Klass klass) {
        assert klass != null;
        CompilerAsserts.neverPartOfCompilation();
        ObjectKlass guestClass = klass.getMeta().java_lang_Class;
        StaticObject newObj = guestClass.getLinkedKlass().getShape(false).getFactory().create(guestClass);
        initInstanceFields(newObj, guestClass);
        klass.getMeta().java_lang_Class_classLoader.setObject(newObj, klass.getDefiningClassLoader());
        if (klass.getContext().getJavaVersion().modulesEnabled()) {
            setModule(newObj, klass);
        }
        if (klass.isArray() && klass.getMeta().java_lang_Class_componentType != null) {
            klass.getMeta().java_lang_Class_componentType.setObject(newObj, ((ArrayKlass) klass).getComponentType().mirror());
        }
        // Will be overriden if necessary, but should be initialized to non-host null.
        klass.getMeta().HIDDEN_PROTECTION_DOMAIN.setHiddenObject(newObj, StaticObject.NULL);
        // Final hidden field assignment
        klass.getMeta().HIDDEN_MIRROR_KLASS.setHiddenObject(newObj, klass);
        return trackAllocation(klass, newObj);
    }

    /**
     * Allocates and populates the static storage for the specified klass.
     */
    public StaticObject createStatics(ObjectKlass klass) {
        assert klass != null;
        CompilerAsserts.neverPartOfCompilation();
        StaticObject newObj = klass.getLinkedKlass().getShape(true).getFactory().create(klass);
        initInitialStaticFields(newObj, klass);
        return trackAllocation(klass, newObj);
    }

    /**
     * @see #createNewPrimitiveArray(byte, int)
     */
    public StaticObject createNewPrimitiveArray(Klass klass, int length) {
        assert klass.isPrimitive();
        return createNewPrimitiveArray(klass.getJavaKind(), length);
    }

    /**
     * @see #createNewPrimitiveArray(byte, int)
     */
    public StaticObject createNewPrimitiveArray(JavaKind kind, int length) {
        assert kind.isPrimitive();
        return createNewPrimitiveArray((byte) kind.getBasicType(), length);
    }

    /**
     * Allocates a guest primitive array.
     * 
     * @param jvmPrimitiveType see {@code JVMS Table 6.5.newarray-A. Array type codes}
     * @param length length of the array to allocate
     */
    public StaticObject createNewPrimitiveArray(byte jvmPrimitiveType, int length) {
        assert AllocationChecks.canAllocateNewArray(length);
        Meta meta = getMeta();
        // @formatter:off
        switch (jvmPrimitiveType) {
            case 4  : return wrapArrayAs(meta._boolean_array, new byte[length]); // boolean[] are internally represented as byte[] with _boolean_array Klass
            case 5  : return StaticObject.wrap(new char[length], meta);
            case 6  : return StaticObject.wrap(new float[length], meta);
            case 7  : return StaticObject.wrap(new double[length], meta);
            case 8  : return StaticObject.wrap(new byte[length], meta);
            case 9  : return StaticObject.wrap(new short[length], meta);
            case 10 : return StaticObject.wrap(new int[length], meta);
            case 11 : return StaticObject.wrap(new long[length], meta);
            default :
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw EspressoError.shouldNotReachHere();
        }
        // @formatter:on
    }

    /**
     * Allocates a guest reference array, and fills it with the guest {@link StaticObject#NULL}.
     * 
     * @param componentKlass The class of the references to store in the array
     */
    public StaticObject createNewReferenceArray(Klass componentKlass, int length) {
        assert length >= 0;
        assert !componentKlass.isPrimitive();
        assert AllocationChecks.canAllocateNewArray(length);
        StaticObject[] arr = new StaticObject[length];
        Arrays.fill(arr, StaticObject.NULL);
        return wrapArrayAs(componentKlass.getArrayClass(), arr);
    }

    /**
     * Creates a new guest multi-dimensional array. See jvms-6.5.multianewarray
     * 
     * @param component The class of what is stored in the top-most array.
     * @param dimensions The dimensions array
     */
    public StaticObject createNewMultiArray(Klass component, int[] dimensions) {
        assert dimensions != null && dimensions.length > 0;
        assert AllocationChecks.canAllocateMultiArray(getMeta(), component, dimensions);
        return createNewMultiArray(component, dimensions, 0);
    }

    /**
     * Given a host {@code array}, wraps in a guest object, and advertise it to be of class
     * {@code klass}.
     * 
     * @param klass The klass to wrap the given array with.
     * @param array A host array, either a primitive array (e.g.: {@code byte[]} or {@code int[]}),
     *            or a {@code StaticObject[]}.
     */
    public StaticObject wrapArrayAs(ArrayKlass klass, Object array) {
        assert klass != null;
        assert array != null;
        assert !(array instanceof StaticObject);
        assert array.getClass().isArray();
        assert klass.getComponentType().isPrimitive() || array instanceof StaticObject[];
        StaticObject newObj = lang.getArrayShape().getFactory().create(klass);
        lang.getArrayProperty().setObject(newObj, array);
        return trackAllocation(klass, newObj);
    }

    /**
     * Wraps a foreign {@link InteropLibrary#isException(Object) exception} as a guest
     * {@code ForeignException}.
     */
    public @JavaType(internalName = "Lcom/oracle/truffle/espresso/polyglot/ForeignException;") StaticObject createForeignException(
                    Object foreignObject,
                    InteropLibrary interopLibrary) {
        Meta meta = getMeta();
        assert meta.polyglot != null;
        assert meta.getContext().Polyglot;
        assert interopLibrary.isException(foreignObject);
        assert !(foreignObject instanceof StaticObject);
        return createForeign(lang, meta.polyglot.ForeignException, foreignObject, interopLibrary);
    }

    /**
     * Wraps a foreign object in a espresso guest object. This espresso object will be types as a
     * {@code klass}.
     */
    public static StaticObject createForeign(
                    EspressoLanguage lang,
                    Klass klass,
                    Object foreignObject,
                    InteropLibrary interopLibrary) {
        if (interopLibrary.isNull(foreignObject)) {
            return createForeignNull(lang, foreignObject);
        }
        return createForeign(lang, klass, foreignObject);
    }

    /**
     * Wraps a foreign null in an espresso null.
     */
    public static StaticObject createForeignNull(EspressoLanguage lang, Object foreignObject) {
        assert InteropLibrary.getUncached().isNull(foreignObject);
        return createForeign(lang, null, foreignObject);
    }

    private static void initInstanceFields(StaticObject obj, ObjectKlass thisKlass) {
        obj.checkNotForeign();
        if (CompilerDirectives.isPartialEvaluationConstant(thisKlass)) {
            initLoop(obj, thisKlass);
        } else {
            initLoopNoExplode(obj, thisKlass);
        }
    }

    @ExplodeLoop
    private static void initLoop(StaticObject obj, ObjectKlass thisKlass) {
        for (Field f : thisKlass.getFieldTable()) {
            assert !f.isStatic();
            if (!f.isHidden() && !f.isRemoved()) {
                if (f.getKind() == JavaKind.Object) {
                    f.setObject(obj, StaticObject.NULL);
                }
            }
        }
    }

    private static void initLoopNoExplode(StaticObject obj, ObjectKlass thisKlass) {
        for (Field f : thisKlass.getFieldTable()) {
            assert !f.isStatic();
            if (!f.isHidden() && !f.isRemoved()) {
                if (f.getKind() == JavaKind.Object) {
                    f.setObject(obj, StaticObject.NULL);
                }
            }
        }
    }

    private static void initInitialStaticFields(StaticObject obj, ObjectKlass thisKlass) {
        obj.checkNotForeign();
        if (CompilerDirectives.isPartialEvaluationConstant(thisKlass)) {
            staticInitLoop(obj, thisKlass);
        } else {
            staticInitLoopNoExplode(obj, thisKlass);
        }
    }

    @ExplodeLoop
    private static void staticInitLoop(StaticObject obj, ObjectKlass thisKlass) {
        for (Field f : thisKlass.getInitialStaticFields()) {
            assert f.isStatic();
            if (f.getKind() == JavaKind.Object && !f.isRemoved()) {
                if (f.isHidden()) { // extension field
                    f.setHiddenObject(obj, StaticObject.NULL);
                } else {
                    f.setObject(obj, StaticObject.NULL);
                }
            }
        }
    }

    private static void staticInitLoopNoExplode(StaticObject obj, ObjectKlass thisKlass) {
        for (Field f : thisKlass.getInitialStaticFields()) {
            assert f.isStatic();
            if (f.getKind() == JavaKind.Object && !f.isRemoved()) {
                if (f.isHidden()) { // extension field
                    f.setHiddenObject(obj, StaticObject.NULL);
                } else {
                    f.setObject(obj, StaticObject.NULL);
                }
            }
        }
    }

    private static StaticObject createForeign(EspressoLanguage lang, Klass klass, Object foreignObject) {
        assert foreignObject != null;
        StaticObject newObj = lang.getForeignShape().getFactory().create(klass, true);
        lang.getForeignProperty().setObject(newObj, foreignObject);
        if (klass != null) {
            klass.safeInitialize();
        }
        return trackForeignAllocation(klass, newObj);
    }

    private void setModule(StaticObject obj, Klass klass) {
        StaticObject module = klass.module().module();
        if (StaticObject.isNull(module)) {
            if (context.getRegistries().javaBaseDefined()) {
                context.getMeta().java_lang_Class_module.setObject(obj, klass.getRegistries().getJavaBaseModule().module());
            } else {
                context.getRegistries().addToFixupList(klass);
            }
        } else {
            context.getMeta().java_lang_Class_module.setObject(obj, module);
        }
    }

    private StaticObject createNewMultiArray(Klass component, int[] dimensions, int currentDimension) {
        assert dimensions != null && dimensions.length > 0;
        int dimLength = dimensions[currentDimension];
        if (currentDimension == dimensions.length - 1) {
            if (component.isPrimitive()) {
                return createNewPrimitiveArray(component, dimLength);
            } else {
                return createNewReferenceArray(component, dimLength);
            }
        }
        StaticObject[] wrapped = new StaticObject[dimLength];
        assert component instanceof ArrayKlass;
        Klass downComponent = ((ArrayKlass) component).getComponentType();
        if (CompilerDirectives.isPartialEvaluationConstant(dimLength)) {
            initMultiArrayLoop(downComponent, wrapped, dimLength, dimensions, currentDimension);
        } else {
            initMultiArrayLoopNoExplode(downComponent, wrapped, dimLength, dimensions, currentDimension);
        }
        return wrapArrayAs(component.array(), wrapped);
    }

    @ExplodeLoop
    private void initMultiArrayLoop(Klass klass, StaticObject[] wrapped, int dimLength, int[] dimensions, int pos) {
        for (int i = 0; i < dimLength; i++) {
            wrapped[i] = createNewMultiArray(klass, dimensions, pos + 1);
        }
    }

    private void initMultiArrayLoopNoExplode(Klass downComponent, StaticObject[] wrapped, int dimLength, int[] dimensions, int pos) {
        for (int i = 0; i < dimLength; i++) {
            wrapped[i] = createNewMultiArray(downComponent, dimensions, pos + 1);
        }
    }

    private StaticObject trackAllocation(Klass klass, StaticObject obj) {
        if (klass == null || context == null) {
            return obj;
        }
        if (!CompilerDirectives.isPartialEvaluationConstant(context)) {
            // TODO: fail here ?
            return trackAllocationBoundary(context, obj);
        }
        return context.trackAllocation(obj);
    }

    private static StaticObject trackForeignAllocation(Klass klass, StaticObject obj) {
        if (klass == null) {
            return obj;
        }
        if (CompilerDirectives.isPartialEvaluationConstant(klass)) {
            return klass.getContext().trackAllocation(obj);
        } else {
            return trackAllocationBoundary(klass.getContext(), obj);
        }
    }

    @TruffleBoundary
    private static StaticObject trackAllocationBoundary(EspressoContext context, StaticObject obj) {
        return context.trackAllocation(obj);
    }

    @Override
    public EspressoContext getContext() {
        return context;
    }

    public static final class AllocationChecks {
        private AllocationChecks() {
        }

        public static void checkCanAllocateNewReference(Meta meta, Klass klass) {
            if (!canAllocateNewReference(klass)) {
                throw meta.throwException(meta.java_lang_InstantiationException);
            }
        }

        public static void checkCanAllocateArray(Meta meta, int size) {
            if (!canAllocateNewArray(size)) {
                throw meta.throwException(meta.java_lang_NegativeArraySizeException);
            }
        }

        public static void checkCanAllocateMultiArray(Meta meta, Klass component, int[] dimensions) {
            if (invalidComponent(meta, component)) {
                throw meta.throwException(meta.java_lang_IllegalArgumentException);
            }
            if (invalidDimensionsArray(dimensions)) {
                throw meta.throwException(meta.java_lang_IllegalArgumentException);
            }
            if (invalidDimensions(dimensions)) {
                throw meta.throwException(meta.java_lang_NegativeArraySizeException);
            }
        }

        static boolean canAllocateNewReference(Klass klass) {
            return (klass instanceof ObjectKlass) && !klass.isAbstract() && !klass.isInterface();
        }

        static boolean canAllocateNewArray(int size) {
            return size >= 0;
        }

        static boolean canAllocateMultiArray(Meta meta, Klass component, int[] dimensions) {
            if (invalidComponent(meta, component)) {
                return false;
            }
            if (invalidDimensionsArray(dimensions)) {
                return false;
            }
            if (invalidDimensions(dimensions)) {
                return false;
            }
            return true;
        }

        private static boolean invalidDimensionsArray(int[] dimensions) {
            return dimensions.length == 0 || dimensions.length > 255;
        }

        private static boolean invalidDimensions(int[] dimensions) {
            for (int dim : dimensions) {
                if (!canAllocateNewArray(dim)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean invalidComponent(Meta meta, Klass component) {
            return component == meta._void;
        }
    }
}
