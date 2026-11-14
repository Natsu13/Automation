package cz.natsu.automation.engine

import cz.natsu.automation.data.Block

/**
 * Nesting information of a flat block list: which "if" belongs to which
 * "otherwise" / "end" and how deep every block is indented.
 */
class Structure(
    val valid: Boolean,
    val depth: IntArray,
    /** index of "end" -> index of the "if" / "repeat while" it closes */
    val startOf: Map<Int, Int>,
    /** index of "if" -> index of its "otherwise" */
    val elseOf: Map<Int, Int>,
    /** index of "if" or "otherwise" -> index of the matching "end" */
    val endOf: Map<Int, Int>,
    /** index of a step -> index of the innermost loop it is in */
    val loopOf: Map<Int, Int> = emptyMap(),
) {
    /** An "if" / "repeat while" whose end has not been reached yet. */
    private data class Open(val start: Int, val groupId: String?, val isLoop: Boolean, val elseIndex: Int = -1)

    companion object {
        fun analyze(blocks: List<Block>): Structure {
            val depth = IntArray(blocks.size)
            val startOf = mutableMapOf<Int, Int>()
            val elseOf = mutableMapOf<Int, Int>()
            val endOf = mutableMapOf<Int, Int>()
            val loopOf = mutableMapOf<Int, Int>()
            val stack = ArrayDeque<Open>()
            var valid = true

            for ((index, block) in blocks.withIndex()) {
                stack.lastOrNull { it.isLoop }?.let { loopOf[index] = it.start }
                when (val kind = Blocks.get(block.type)?.kind) {
                    BlockKind.IfStart, BlockKind.WhileStart, BlockKind.TryStart -> {
                        depth[index] = stack.size
                        stack.addLast(Open(index, block.groupId, isLoop = kind == BlockKind.WhileStart))
                    }
                    BlockKind.Else -> {
                        val top = stack.lastOrNull()
                        // A loop has no "otherwise" branch.
                        if (top == null || top.groupId != block.groupId || top.isLoop || top.elseIndex != -1) {
                            valid = false
                            depth[index] = stack.size
                        } else {
                            depth[index] = stack.size - 1
                            elseOf[top.start] = index
                            stack[stack.size - 1] = top.copy(elseIndex = index)
                        }
                    }
                    BlockKind.End -> {
                        val top = stack.lastOrNull()
                        if (top == null || top.groupId != block.groupId) {
                            valid = false
                            depth[index] = stack.size
                        } else {
                            stack.removeLast()
                            depth[index] = stack.size
                            endOf[top.start] = index
                            if (top.elseIndex >= 0) endOf[top.elseIndex] = index
                            startOf[index] = top.start
                        }
                    }
                    else -> depth[index] = stack.size
                }
            }

            if (stack.isNotEmpty()) valid = false
            return Structure(valid, depth, startOf, elseOf, endOf, loopOf)
        }
    }
}
