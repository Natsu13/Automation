package cz.natsu.automation.engine

import cz.natsu.automation.data.Block

/**
 * Nesting information of a flat block list: which "if" belongs to which
 * "otherwise" / "end" and how deep every block is indented.
 */
class Structure(
    val valid: Boolean,
    val depth: IntArray,
    /** index of "if" -> index of its "otherwise" */
    val elseOf: Map<Int, Int>,
    /** index of "if" or "otherwise" -> index of the matching "end" */
    val endOf: Map<Int, Int>,
) {
    companion object {
        fun analyze(blocks: List<Block>): Structure {
            val depth = IntArray(blocks.size)
            val elseOf = mutableMapOf<Int, Int>()
            val endOf = mutableMapOf<Int, Int>()
            // (index of if, group id, index of else or -1)
            val stack = ArrayDeque<Triple<Int, String?, Int>>()
            var valid = true

            for ((index, block) in blocks.withIndex()) {
                when (Blocks.get(block.type)?.kind) {
                    BlockKind.IfStart -> {
                        depth[index] = stack.size
                        stack.addLast(Triple(index, block.groupId, -1))
                    }
                    BlockKind.Else -> {
                        val top = stack.lastOrNull()
                        if (top == null || top.second != block.groupId || top.third != -1) {
                            valid = false
                            depth[index] = stack.size
                        } else {
                            depth[index] = stack.size - 1
                            elseOf[top.first] = index
                            stack[stack.size - 1] = top.copy(third = index)
                        }
                    }
                    BlockKind.End -> {
                        val top = stack.lastOrNull()
                        if (top == null || top.second != block.groupId) {
                            valid = false
                            depth[index] = stack.size
                        } else {
                            stack.removeLast()
                            depth[index] = stack.size
                            endOf[top.first] = index
                            if (top.third >= 0) endOf[top.third] = index
                        }
                    }
                    else -> depth[index] = stack.size
                }
            }

            if (stack.isNotEmpty()) valid = false
            return Structure(valid, depth, elseOf, endOf)
        }
    }
}
