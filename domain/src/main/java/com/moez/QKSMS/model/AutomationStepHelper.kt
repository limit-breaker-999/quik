package dev.octoshrimpy.quik.model

object AutomationStepHelper {

    val ALL_ACTIONS = listOf(
        AutomationRule.ACTION_FORWARD_SMS,
        AutomationRule.ACTION_FORWARD_HTTP,
        AutomationRule.ACTION_AUTO_REPLY,
        AutomationRule.ACTION_MARK_READ,
        AutomationRule.ACTION_ARCHIVE,
        AutomationRule.ACTION_DELETE
    )

    fun addStep(current: List<AutomationActionStep>): List<AutomationActionStep> {
        val used = current.map { it.action }.toSet()
        val available = ALL_ACTIONS.filter { it !in used }
        if (available.isEmpty()) return current

        val nextAction = available.first()
        val newStep = AutomationActionStep(action = nextAction)

        val deleteIndex = current.indexOfFirst { it.action == AutomationRule.ACTION_DELETE }
        return if (deleteIndex != -1) {
            val mutable = current.toMutableList()
            mutable.add(deleteIndex, newStep)
            enforceDeleteTerminal(mutable)
        } else {
            current + newStep
        }
    }

    fun removeStep(current: List<AutomationActionStep>, index: Int): List<AutomationActionStep> {
        if (current.size <= 1 || index !in current.indices) return current
        return current.filterIndexed { i, _ -> i != index }
    }

    fun moveUp(current: List<AutomationActionStep>, index: Int): List<AutomationActionStep> {
        if (index <= 0 || index >= current.size) return current
        // DELETE cannot move up
        if (current[index].action == AutomationRule.ACTION_DELETE) return current
        val mutable = current.toMutableList()
        val temp = mutable[index]
        mutable[index] = mutable[index - 1]
        mutable[index - 1] = temp
        return enforceDeleteTerminal(mutable)
    }

    fun moveDown(current: List<AutomationActionStep>, index: Int): List<AutomationActionStep> {
        if (index < 0 || index >= current.size - 1) return current
        // DELETE cannot move down
        if (current[index].action == AutomationRule.ACTION_DELETE) return current
        // Cannot move down past DELETE
        if (current[index + 1].action == AutomationRule.ACTION_DELETE) return current
        val mutable = current.toMutableList()
        val temp = mutable[index]
        mutable[index] = mutable[index + 1]
        mutable[index + 1] = temp
        return enforceDeleteTerminal(mutable)
    }

    fun updateStep(current: List<AutomationActionStep>, index: Int, updated: AutomationActionStep): List<AutomationActionStep> {
        if (index !in current.indices) return current
        val mutable = current.toMutableList()
        mutable[index] = updated
        return enforceDeleteTerminal(mutable)
    }

    fun enforceDeleteTerminal(steps: List<AutomationActionStep>): List<AutomationActionStep> {
        val deleteStep = steps.find { it.action == AutomationRule.ACTION_DELETE } ?: return steps
        val nonDelete = steps.filter { it.action != AutomationRule.ACTION_DELETE }
        return nonDelete + deleteStep
    }
}
