/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen.range.forLoop

import org.jetbrains.kotlin.codegen.ExpressionCodegen
import org.jetbrains.kotlin.codegen.StackValue
import org.jetbrains.kotlin.codegen.range.SimpleBoundedValue
import org.jetbrains.kotlin.codegen.range.isLocalVarReference
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.diagnostics.DiagnosticUtils
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.Type
import java.io.File

class ForInSimpleProgressionLoopGenerator(
    codegen: ExpressionCodegen,
    forExpression: KtForExpression,
    private val startValue: StackValue,
    private val isStartInclusive: Boolean,
    private val endValue: StackValue,
    private val isEndInclusive: Boolean,
    private val inverseBoundsEvaluationOrder: Boolean,
    step: Int
) : AbstractForInRangeLoopGenerator(codegen, forExpression, step) {

    constructor(
        codegen: ExpressionCodegen,
        forExpression: KtForExpression,
        boundedValue: SimpleBoundedValue,
        inverseBoundsEvaluationOrder: Boolean,
        step: Int
    ) : this(
            codegen, forExpression,
            startValue = if (step == 1) boundedValue.lowBound else boundedValue.highBound,
            isStartInclusive = if (step == 1) boundedValue.isLowInclusive else boundedValue.isHighInclusive,
            endValue = if (step == 1) boundedValue.highBound else boundedValue.lowBound,
            isEndInclusive = if (step == 1) boundedValue.isHighInclusive else boundedValue.isLowInclusive,
            inverseBoundsEvaluationOrder = inverseBoundsEvaluationOrder,
            step = step
    )

    companion object {
        fun fromBoundedValueWithStep1(
            codegen: ExpressionCodegen,
            forExpression: KtForExpression,
            boundedValue: SimpleBoundedValue,
            inverseBoundsEvaluationOrder: Boolean = false
        ) =
            ForInSimpleProgressionLoopGenerator(codegen, forExpression, boundedValue, inverseBoundsEvaluationOrder, 1)

        fun fromBoundedValueWithStepMinus1(
            codegen: ExpressionCodegen,
            forExpression: KtForExpression,
            boundedValue: SimpleBoundedValue,
            inverseBoundsEvaluationOrder: Boolean = false
        ) =
            ForInSimpleProgressionLoopGenerator(codegen, forExpression, boundedValue, inverseBoundsEvaluationOrder, -1)
    }

    override fun storeRangeStartAndEnd() {
        if (inverseBoundsEvaluationOrder) {
            StackValue.local(endVar, asmElementType).store(endValue, v)
            loopParameter().store(startValue, v)
        } else {
            loopParameter().store(startValue, v)
            StackValue.local(endVar, asmElementType).store(endValue, v)
        }

        // Skip 1st element if start is not inclusive.
        if (!isStartInclusive) incrementLoopVariable()
    }

    override fun checkEmptyLoop(loopExit: Label) {
        // No check required if end is non-inclusive: loop is generated with pre-condition.
        if (isEndInclusive) {
            super.checkEmptyLoop(loopExit)
        }
    }

    override fun checkPreCondition(loopExit: Label) {
        // Generate pre-condition loop if end is not inclusive.
        if (!isEndInclusive) {
            loopParameter().put(asmElementType, v)
            v.load(endVar, asmElementType)
            if (asmElementType.sort == Type.LONG) {
                v.lcmp()
                if (step > 0) {
                    v.ifge(loopExit)
                } else {
                    v.ifle(loopExit)
                }
            } else {
                if (step > 0) {
                    v.ificmpge(loopExit)
                } else {
                    v.ificmple(loopExit)
                }
            }
        }
    }

    override fun checkPostConditionAndIncrement(loopExit: Label) {
        // Generate post-condition loop if end is inclusive.
        // Otherwise, just increment the loop variable.
        if (isEndInclusive) {
            super.checkPostConditionAndIncrement(loopExit)
        } else {
            incrementLoopVariable()
        }
    }

    fun dumpStat(
        loopCount: Long,
        forInSimpleProgressionLoopGeneratorCount: Long,
        forInRangeInstanceLoopGeneratorCount: Long,
        forInProgressionExpressionLoopGeneratorCount: Long,
        codegen: ExpressionCodegen,
        loopIntoInlineFunctions: Long,
        unsupportedLoopIntoInlineFunctions: Long
    ) {
        val badLoopCount = forInSimpleProgressionLoopGeneratorCount + forInRangeInstanceLoopGeneratorCount +
                +forInProgressionExpressionLoopGeneratorCount
        val f = File("/tmp/loop-statistics")
        var mess = "Loop (" + badLoopCount + "/" + loopCount + "/(" + forInSimpleProgressionLoopGeneratorCount + ", " +
                +forInRangeInstanceLoopGeneratorCount + ", " + forInProgressionExpressionLoopGeneratorCount + ")/(" +
                +loopIntoInlineFunctions + ", " + unsupportedLoopIntoInlineFunctions + "))"
        mess += " ForInSimpleProgressionLoopGenerator(Inclusive end range):\n"
        mess += forExpression.loopRange!!.text + " in file " + DiagnosticUtils.atLocation(forExpression.loopRange) + "\n"
        mess += detailStat(forExpression.loopRange!!, codegen, bindingContext)
        println(mess)
        f.appendText(mess + "\n")
    }

    fun isEndInclusive(): Boolean {
        return isEndInclusive
    }
}

fun detailStat(rangeExpression: KtExpression, codegen: ExpressionCodegen, bindingContext: BindingContext): String {
    val rangeExpression = KtPsiUtil.deparenthesize(rangeExpression)!!
    var endExpression: KtExpression? = null
    if (rangeExpression is KtQualifiedExpression) {
        return detailStat(rangeExpression.selectorExpression!!, codegen, bindingContext)
    } else if (rangeExpression is KtSimpleNameExpression) {
        endExpression = rangeExpression
    } else if (rangeExpression is KtCallExpression) {
        endExpression = rangeExpression
    } else if (rangeExpression is KtBinaryExpression) {
        val nameString = rangeExpression.operationReference.getResolvedCall(bindingContext)!!.resultingDescriptor.name.asString()
        if (nameString == "rangeTo") {
            // End expression is left part of binary
            endExpression = rangeExpression.right!!
        } else if (nameString == "step") {
            // range is the left part of the binary
            return detailStat(rangeExpression.left as KtExpression, codegen, bindingContext)
        } else {
            throw AssertionError("Not yet supported")
        }
    } else {
        throw AssertionError("Not yet supported")
    }
    val (isInterprocedural, cause) = isInterprocedural(endExpression as KtExpression, bindingContext, codegen)
    var mess = ""
    if (isInterprocedural) {
        mess += "=> Need interprocedural analsyis\n"
    } else {
        mess += "=> Need intraprocedural analysis\n"
    }
    mess += "Due to: " + cause + "\n"
    return mess
    return mess
}

fun isInterprocedural(
    endExpression: KtExpression,
    bindingContext: BindingContext,
    codegen: ExpressionCodegen
): Pair<Boolean, String> {
    if (endExpression is KtQualifiedExpression) {
        if (codegen.isArraySizeAccess(endExpression)) {
            return false to "Qualified array size (assume range [0, Integer.MAX_VALUE])"
        }
        val (isInterproceduralSelector, causeSelector) = isInterprocedural(endExpression.selectorExpression!!, bindingContext, codegen)
        return isInterproceduralSelector to "Qualified expression, " + causeSelector
    } else if (endExpression is KtCallExpression) {
        return true to "Call expression (int range unknown)"
    } else if (endExpression is KtSimpleNameExpression) {
        if (isParameterReference(endExpression, bindingContext)) {
            return true to "Parameter access (int range unknown)"
        } else if (isLocalVarReference(endExpression, bindingContext)) {
            return false to "Local variable access (int range can be known ?)"
        } else if (isPropertyReference(endExpression, bindingContext)) {
            return true to "Property access (int range unknown)"
        }
        return true to "Unknow KtSimpleNameExpression"
    } else if (endExpression is KtBinaryExpression) {
        val (isInterproceduralLeft, causeLeft) = isInterprocedural(endExpression.left!!, bindingContext, codegen)
        val (isInterproceduralRight, causeRight) = isInterprocedural(endExpression.right!!, bindingContext, codegen)
        return (isInterproceduralLeft || isInterproceduralRight) to "Binary expression, " + causeLeft + ", " + causeRight
    } else if (endExpression is KtConstantExpression) {
        return false to "Constant expression"
        return false to "Constant expression"
    } else {
        throw AssertionError("Not yet supported : " + endExpression.text + " " + DiagnosticUtils.atLocation(endExpression))
    }
}

fun isPropertyReference(rangeExpression: KtExpression, bindingContext: BindingContext): Boolean {
    if (rangeExpression !is KtSimpleNameExpression) return false
    val resultingDescriptor = rangeExpression.getResolvedCall(bindingContext)?.resultingDescriptor ?: return false
    return resultingDescriptor is PropertyDescriptor
}

fun isParameterReference(rangeExpression: KtExpression, bindingContext: BindingContext): Boolean {
    if (rangeExpression !is KtSimpleNameExpression) return false
    val resultingDescriptor = rangeExpression.getResolvedCall(bindingContext)?.resultingDescriptor ?: return false
    return resultingDescriptor is ValueParameterDescriptor
}

fun ExpressionCodegen.isArraySizeAccess(expression: KtExpression): Boolean {
    return when {
        expression is KtDotQualifiedExpression -> {
            val selector = expression.selectorExpression
            asmType(bindingContext.getType(expression.receiverExpression)!!).sort == Type.ARRAY &&
                    selector is KtNameReferenceExpression &&
                    selector.text == "size"
        }
        else -> false
    }
}

fun dumpEmptyStat(
    loopCount: Long,
    forInSimpleProgressionLoopGeneratorCount: Long,
    forInRangeInstanceLoopGeneratorCount: Long,
    forInProgressionExpressionLoopGeneratorCount: Long,
    loopIntoInlineFunctions: Long,
    unsupportedLoopIntoInlineFunctions: Long
) {
    val badLoopCount = forInSimpleProgressionLoopGeneratorCount + forInRangeInstanceLoopGeneratorCount +
            forInProgressionExpressionLoopGeneratorCount
    val f = File("/tmp/loop-statistics")
    var mess = "Loop (" + badLoopCount + "/" + loopCount + "/(" + forInSimpleProgressionLoopGeneratorCount + ", " +
            forInRangeInstanceLoopGeneratorCount + ", " + forInProgressionExpressionLoopGeneratorCount + ")/(" +
            loopIntoInlineFunctions + ", " + unsupportedLoopIntoInlineFunctions + "))"
    println(mess)
    f.appendText(mess + "\n")
}
