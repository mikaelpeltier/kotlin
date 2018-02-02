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
import org.jetbrains.kotlin.diagnostics.DiagnosticUtils
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import java.io.File

class ForInRangeInstanceLoopGenerator(
    codegen: ExpressionCodegen,
    forExpression: KtForExpression,
    private val rangeExpression: KtExpression,
    private val reversed: Boolean
) : AbstractForInRangeLoopGenerator(codegen, forExpression, if (reversed) -1 else 1) {

    override fun storeRangeStartAndEnd() {
        val loopRangeType = codegen.bindingContext.getType(rangeExpression)!!
        val asmLoopRangeType = codegen.asmType(loopRangeType)
        codegen.gen(rangeExpression, asmLoopRangeType)
        v.dup()

        // ranges inherit first and last from corresponding progressions
        if (reversed) {
            generateRangeOrProgressionProperty(asmLoopRangeType, "getLast", asmElementType, loopParameterType, loopParameterVar)
            generateRangeOrProgressionProperty(asmLoopRangeType, "getFirst", asmElementType, asmElementType, endVar)
        } else {
            generateRangeOrProgressionProperty(asmLoopRangeType, "getFirst", asmElementType, loopParameterType, loopParameterVar)
            generateRangeOrProgressionProperty(asmLoopRangeType, "getLast", asmElementType, asmElementType, endVar)
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
        mess += " ForInSimpleProgressionLoopGenerator:\n"
        mess += forExpression.loopRange!!.text + " in file " + DiagnosticUtils.atLocation(forExpression.loopRange) + "\n"
        mess += detailStat(forExpression.loopRange!!, codegen, bindingContext)
        println(mess)
        f.appendText(mess + "\n")
    }
}
