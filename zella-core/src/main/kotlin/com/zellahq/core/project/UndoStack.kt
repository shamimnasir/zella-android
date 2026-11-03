package com.zellahq.core.project

/**
 * Undo and redo for every edit. Projects are immutable values, so a step is just the previous
 * value. Edits within [mergeWindowMs] with the same [label] merge into one step, so a slider drag
 * is one undo, not two hundred.
 */
class UndoStack(
    initial: Project,
    private val limit: Int = 200,
    private val mergeWindowMs: Long = 600,
) {
    data class Step(val before: Project, val label: String, val atMs: Long)

    var current: Project = initial
        private set
    private val undo = ArrayDeque<Step>()
    private val redo = ArrayDeque<Project>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /** The label of the step Undo would reverse, for "Undo split" style buttons. */
    val undoLabel get() = undo.lastOrNull()?.label

    /** Applies [next] as one step. An edit that changes nothing is not a step. */
    fun apply(
        next: Project,
        label: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (next == current) return false
        val last = undo.lastOrNull()
        if (last != null && last.label == label && nowMs - last.atMs < mergeWindowMs) {
            undo.removeLast()
            undo.addLast(last.copy(atMs = nowMs))
        } else {
            undo.addLast(Step(current, label, nowMs))
            while (undo.size > limit) undo.removeFirst()
        }
        redo.clear()
        current = next
        return true
    }

    fun undo(): Project? {
        val step = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        current = step.before
        return current
    }

    fun redo(): Project? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(Step(current, "redo", 0))
        current = next
        return current
    }
}
