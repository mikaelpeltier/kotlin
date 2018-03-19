/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.intrinsics

import org.jetbrains.kotlin.codegen.AsmUtil
import org.jetbrains.kotlin.codegen.ExpressionCodegen
import org.jetbrains.kotlin.codegen.StackValue
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.resolve.BindingContext.DOUBLE_COLON_LHS
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.expressions.DoubleColonLHS
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.kotlin.codegen.inline.ReifiedTypeInliner
import org.jetbrains.kotlin.resolve.jvm.AsmTypes


class KClassJavaObjectTypeProperty : IntrinsicPropertyGetter() {
    override fun generate(resolvedCall: ResolvedCall<*>?, codegen: ExpressionCodegen, returnType: Type, receiver: StackValue): StackValue? {
        val receiverValue = resolvedCall!!.extensionReceiver as? ExpressionReceiver ?: return null
        val classLiteralExpression = receiverValue.expression as? KtClassLiteralExpression ?: return null
        val receiverExpression = classLiteralExpression.receiverExpression ?: return null
        val lhs = codegen.bindingContext.get(DOUBLE_COLON_LHS, receiverExpression) ?: return null
        return StackValue.operation(returnType) { iv ->
            var lhsType = codegen.asmType(lhs.type)
            if (lhs is DoubleColonLHS.Expression && !lhs.isObjectQualifier) {
                val receiverStackValue = codegen.gen(receiverExpression)
                val extensionReceiverType  = receiverStackValue.type
                when {
                    extensionReceiverType == Type.VOID_TYPE -> {
                        receiverStackValue.put(Type.VOID_TYPE, iv)
                        StackValue.unit().put(AsmTypes.UNIT_TYPE, iv)
                        iv.invokevirtual("java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
                    }
                    AsmUtil.isPrimitive(extensionReceiverType) -> {
                        receiverStackValue.put(extensionReceiverType, iv)
                        AsmUtil.pop(iv, extensionReceiverType)
                        iv.aconst(AsmUtil.boxType(extensionReceiverType))
                    }
                    else -> {
                        receiverStackValue.put(extensionReceiverType, iv)
                        iv.invokevirtual("java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
                    }
                }
            } else {
                if (AsmUtil.isPrimitive(lhsType)) {
                    lhsType = AsmUtil.boxType(lhsType)
                } else {
                    if (TypeUtils.isTypeParameter(lhs.type)) {
                        assert(TypeUtils.isReifiedTypeParameter(lhs.type)) { "Non-reified type parameter under ::class should be rejected by type checker: " + lhs.type }
                        codegen.putReifiedOperationMarkerIfTypeIsReifiedParameter(lhs.type, ReifiedTypeInliner.OperationKind.JAVA_CLASS)
                    }
                }
                iv.aconst(lhsType)
            }
        }
    }
}
