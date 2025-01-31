/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.lir.amd64.vector;

import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.code.ValueUtil.isRegister;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.STACK;

import jdk.vm.ci.amd64.AMD64;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.Value;
import org.graalvm.compiler.asm.amd64.AMD64Address;
import org.graalvm.compiler.asm.amd64.AMD64Assembler;
import org.graalvm.compiler.asm.amd64.AMD64BaseAssembler;
import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;
import org.graalvm.compiler.asm.amd64.AVXKind;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.Opcode;
import org.graalvm.compiler.lir.amd64.AMD64LIRInstruction;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;

import jdk.vm.ci.meta.AllocatableValue;

public class AMD64VectorBlend {
    private abstract static class AbstractBlendOp extends AMD64LIRInstruction {
        public static final LIRInstructionClass<AbstractBlendOp> TYPE = LIRInstructionClass.create(AbstractBlendOp.class);

        protected final AVXKind.AVXSize size;

        @Def({REG}) protected AllocatableValue result;
        @Use({REG}) protected AllocatableValue x;
        @Use({REG, STACK}) protected AllocatableValue y;
        @Use({REG}) protected AllocatableValue mask;

        AbstractBlendOp(LIRInstructionClass<? extends AbstractBlendOp> c, AVXKind.AVXSize size, AllocatableValue result, AllocatableValue x, AllocatableValue y, AllocatableValue mask) {
            super(c);
            this.size = size;
            this.result = result;
            this.x = x;
            this.y = y;
            this.mask = mask;
        }
    }

    public static class VexBlendOp extends AbstractBlendOp {
        public static final LIRInstructionClass<VexBlendOp> TYPE = LIRInstructionClass.create(VexBlendOp.class);

        @Opcode private final AMD64Assembler.VexRVMROp opcode;
        @Temp({REG}) Value[] temps;

        public VexBlendOp(AMD64Assembler.VexRVMROp opcode, AVXKind.AVXSize size, AllocatableValue result, AllocatableValue x, AllocatableValue y, AllocatableValue mask, AMD64 arch) {
            super(TYPE, size, result, x, y, mask);
            this.opcode = opcode;

            // we need temps when AVX512 is enabled and the vector register might be in the range
            // 16-31 which cannot be expressed with a VEX encoding and so we have to move the value
            // into a supported register
            temps = opcode.isSupported(arch, size, true) ? Value.NO_VALUES
                            : new Value[]{AMD64.xmm5.asValue(x.getValueKind()), AMD64.xmm6.asValue(y.getValueKind()), AMD64.xmm7.asValue(result.getValueKind()),
                                            AMD64.xmm8.asValue(mask.getValueKind())};
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            Register xReg = asRegister(x);
            if (AMD64BaseAssembler.isAVX512Register(xReg) && !opcode.isSupported(masm, size, true)) {
                AMD64Assembler.VexMoveOp.VMOVDQU32.emit(masm, size, AMD64.xmm5, xReg);
                xReg = AMD64.xmm5;
            }
            Register maskReg = asRegister(mask);
            if (AMD64BaseAssembler.isAVX512Register(maskReg) && !opcode.isSupported(masm, size, true)) {
                AMD64Assembler.VexMoveOp.VMOVDQU32.emit(masm, size, AMD64.xmm8, maskReg);
                maskReg = AMD64.xmm8;
            }
            Register finalReg = asRegister(result);
            Register resultReg = finalReg;
            if (AMD64BaseAssembler.isAVX512Register(finalReg) && !opcode.isSupported(masm, size, true)) {
                AMD64Assembler.VexMoveOp.VMOVDQU32.emit(masm, size, AMD64.xmm6, xReg);
                resultReg = AMD64.xmm6;
            }
            if (isRegister(y)) {
                Register yReg = asRegister(y);
                if (AMD64BaseAssembler.isAVX512Register(yReg) && !opcode.isSupported(masm, size, true)) {
                    AMD64Assembler.VexMoveOp.VMOVDQU32.emit(masm, size, AMD64.xmm7, yReg);
                    yReg = AMD64.xmm7;
                }
                opcode.emit(masm, size, resultReg, maskReg, xReg, yReg);
            } else {
                opcode.emit(masm, size, resultReg, maskReg, xReg, (AMD64Address) crb.asAddress(y));
            }
            if (!finalReg.equals(resultReg)) {
                AMD64Assembler.VexMoveOp.VMOVDQU32.emit(masm, size, finalReg, resultReg);
            }
        }
    }

    public static class EvexBlendOp extends AbstractBlendOp implements AVX512Support {
        public static final LIRInstructionClass<EvexBlendOp> TYPE = LIRInstructionClass.create(EvexBlendOp.class);

        @Opcode private final AMD64Assembler.VexRVMOp opcode;

        public EvexBlendOp(AMD64Assembler.VexRVMOp opcode, AVXKind.AVXSize size, AllocatableValue result, AllocatableValue x, AllocatableValue y, AllocatableValue mask) {
            super(TYPE, size, result, x, y, mask);
            this.opcode = opcode;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AMD64MacroAssembler masm) {
            if (isRegister(y)) {
                opcode.emit(masm, size, asRegister(result), asRegister(x), asRegister(y), asRegister(mask));
            } else {
                opcode.emit(masm, size, asRegister(result), asRegister(x), (AMD64Address) crb.asAddress(y), asRegister(mask));
            }
        }

        @Override
        public AllocatableValue getOpmask() {
            return mask;
        }
    }
}
