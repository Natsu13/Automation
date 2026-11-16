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
    /** index of "if" (and of each of its "otherwise if") -> index of its "otherwise" */
    val elseOf: Map<Int, Int>,
    /** index of "if", "otherwise if" or "otherwise" -> index of the matching "end" */
    val endOf: Map<Int, Int>,
    /** index of a step -> index of the innermost loop it is in */
    val loopOf: Map<Int, Int> = emptyMap(),
    /** index of "if" / "otherwise if" -> index of the "otherwise if" that follows it */
    val nextBranch: Map<Int, Int> = emptyMap(),
    /** index of "parallel" -> indexes of the separators between its branches */
    val branchesOf: Map<Int, List<Int>> = emptyMap(),
) {
    /** An "if" / "repeat while" / "try" whose end has not been reached yet. */
    private class Open(val start: Int, val groupId: String?, val kind: BlockKind) {
        var elseIndex = -1
        val elseIfs = mutableListOf<Int>()
        val branches = mutableListOf<Int>()
        val isLoop get() = kind == BlockKind.WhileStart
    }

    companion object {
        fun analyze(blocks: List<Block>): Structure {
            val depth = IntArray(blocks.size)
            val startOf = mutableMapOf<Int, Int>()
            val elseOf = mutableMapOf<Int, Int>()
            val endOf = mutableMapOf<Int, Int>()
            val loopOf = mutableMapOf<Int, Int>()
            val nextBranch = mutableMapOf<Int, Int>()
            val branchesOf = mutableMapOf<Int, List<Int>>()
            val stack = ArrayDeque<Open>()
            var valid = true

            for ((index, block) in blocks.withIndex()) {
                stack.lastOrNull { it.isLoop }?.let { loopOf[index] = it.start }
                when (val kind = Blocks.get(block.type)?.kind) {
                    BlockKind.IfStart, BlockKind.WhileStart, BlockKind.TryStart, BlockKind.ParallelStart, BlockKind.Scope -> {
                        depth[index] = stack.size
                        stack.addLast(Open(index, block.groupId, kind))
                    }
                    BlockKind.ElseIf -> {
                        val top = stack.lastOrNull()
                        // Only an "if" has more conditions, and they come before its "otherwise".
                        if (top == null || top.groupId != block.groupId || top.kind != BlockKind.IfStart || top.elseIndex != -1) {
                            valid = false
                            depth[index] = stack.size
                        } else {
                            depth[index] = stack.size - 1
                            nextBranch[top.elseIfs.lastOrNull() ?: top.start] = index
                            top.elseIfs += index
                        }
                    }
                    BlockKind.Branch -> {
                        val top = stack.lastOrNull()
                        if (top == null || top.groupId != block.groupId || top.kind != BlockKind.ParallelStart) {
                            valid = false
                            depth[index] = stack.size
                        } else {
                            depth[index] = stack.size - 1
                            top.branches += index
                        }
                    }
                    BlockKind.Else -> {
                        val top = stack.lastOrNull()
                        // A loop and parallel branches have no "otherwise" branch.
                        if (top == null || top.groupId != block.groupId || top.isLoop ||
                            top.kind == BlockKind.ParallelStart || top.elseIndex != -1
                        ) {
                            valid = false
                            depth[index] = stack.size
                        } else {
                            depth[index] = stack.size - 1
                            elseOf[top.start] = index
                            for (branch in top.elseIfs) elseOf[branch] = index
                            top.elseIndex = index
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
                            for (branch in top.elseIfs) endOf[branch] = index
                            for (branch in top.branches) endOf[branch] = index
                            if (top.branches.isNotEmpty()) branchesOf[top.start] = top.branches.toList()
                            if (top.elseIndex >= 0) endOf[top.elseIndex] = index
                            startOf[index] = top.start
                        }
                    }
                    else -> depth[index] = stack.size
                }
            }

            if (stack.isNotEmpty()) valid = false
            return Structure(valid, depth, startOf, elseOf, endOf, loopOf, nextBranch, branchesOf)
        }
    }
}
