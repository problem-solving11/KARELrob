package vm

import logic.StackOverflow
import logic.World
import logic.WorldRef

// If "step over" or "step return" do not finish within 1 second,
// we assume the code contains an infinite loop.
const val TIMEOUT = 1_000_000_000L

// The first instruction starts at address 256.
// This makes it easier to distinguish addresses
// from truth values and loop counters on the stack.
const val ENTRY_POINT = 256

class VirtualMachine(
    private val program: List<Instruction>,
    private val worldRef: WorldRef,
    private val callbacks: Callbacks,
    private val onBeeper: (World) -> Unit = {},
    private val onMove: (World) -> Unit = {},
) {

    interface Callbacks {
        fun onCall(callerPosition: Int, calleePosition: Int) {}
        fun onReturn() {}
        fun onInfiniteLoop() {}
    }

    val world: World
        get() = worldRef.world

    var pc: Int = ENTRY_POINT
        private set

    val currentInstruction: Instruction
        get() = program[pc]

    private val returnInstructionPositions = IntArray(program.size).apply {
        for (index in lastIndex downTo ENTRY_POINT) {
            if (program[index].bytecode == RETURN) {
                this[index] = program[index].position
            } else {
                this[index] = this[index + 1]
            }
        }
    }

    private var top = -1
    private val stack = IntArray(1000)
    fun usedStack() = stack.copyOf(top + 1)

    private var callDepth: Int = 0

    private fun push(x: Int) {
        val top1 = top + 1
        if (top1 >= stack.size) throw StackOverflow()
        stack[top1] = x
        top = top1
    }

    private fun push(x: Boolean) {
        push(if (x) -1 else 0)
    }

    private fun pop(): Int {
        return stack[top--]
    }

    fun stepInto(virtualMachineVisible: Boolean) {
        executeUnpausedInstructions(virtualMachineVisible)
        executeOneInstruction()
        executeUnpausedInstructions(virtualMachineVisible)
    }

    private fun executeUnpausedInstructions(virtualMachineVisible: Boolean) {
        if (!virtualMachineVisible) {
            while (!currentInstruction.shouldPause()) {
                executeOneInstruction()
            }
        }
    }

    fun stepOver() {
        stepUntil(callDepth)
    }

    fun stepReturn() {
        stepUntil(callDepth - 1)
    }

    private fun stepUntil(targetDepth: Int) {
        val start = System.nanoTime()
        stepInto(false)
        while ((callDepth > targetDepth) && (System.nanoTime() - start < TIMEOUT)) {
            executeOneInstruction()
        }
        if (callDepth > targetDepth) {
            callbacks.onInfiniteLoop()
        }
    }

    fun executeUserProgram() {
        val start = System.nanoTime()
        while (System.nanoTime() - start < TIMEOUT) {
            repeat(1000) {
                executeOneInstruction()
            }
        }
        callbacks.onInfiniteLoop()
    }

    fun executeGoalProgram() {
        while (true) {
            executeOneInstruction()
        }
    }

    private fun executeOneInstruction() {
        with(currentInstruction) {
            when (category shr 12) {
                NORM shr 12 -> executeBasicInstruction(bytecode)

                PUSH shr 12 -> executePush()
                LOOP shr 12 -> executeLoop()
                CALL shr 12 -> executeCall()

                JUMP shr 12 -> pc = target
                ELSE shr 12 -> pc = if (pop() == 0) target else pc + 1
                THEN shr 12 -> pc = if (pop() != 0) target else pc + 1

                ELSE_INSTRUMENTED shr 12 -> {
                    if (pop() == 0) {
                        pc = target
                        branchTaken = true
                    } else {
                        pc += 1
                        branchSkipped = true
                    }
                }

                THEN_INSTRUMENTED shr 12 -> {
                    if (pop() != 0) {
                        pc = target
                        branchTaken = true
                    } else {
                        pc += 1
                        branchSkipped = true
                    }
                }

                else -> throw IllegalBytecode(bytecode)
            }
        }
    }

    private fun Instruction.executePush() {
        push(target)
        ++pc
    }

    private fun Instruction.executeLoop() {
        if (--stack[top] > 0) {
            pc = target
        } else {
            --top
            ++pc
        }
    }

    private fun Instruction.executeCall() {
        callbacks.onCall(position, returnInstructionPositions[target])
        push(pc)
        ++callDepth
        pc = target
    }

    object Finished : RuntimeException()

    private fun executeReturn() {
        if (top < 0) throw Finished
        callbacks.onReturn()
        pc = pop()
        --callDepth
    }

    private fun executeBasicInstruction(bytecode: Int) {
        when (bytecode) {
            RETURN -> executeReturn()

            MOVE_FORWARD -> onMove(worldRef.moveForward())
            TURN_LEFT -> worldRef.turnLeft()
            TURN_AROUND -> worldRef.turnAround()
            TURN_RIGHT -> worldRef.turnRight()
            PICK_BEEPER -> onBeeper(worldRef.pickBeeper())
            DROP_BEEPER -> onBeeper(worldRef.dropBeeper())

            ON_BEEPER -> push(worldRef.world.onBeeper())
            BEEPER_AHEAD -> push(worldRef.world.beeperAhead())
            LEFT_IS_CLEAR -> push(worldRef.world.leftIsClear())
            FRONT_IS_CLEAR -> push(worldRef.world.frontIsClear())
            RIGHT_IS_CLEAR -> push(worldRef.world.rightIsClear())

            NOT -> stack[top] = stack[top].inv()
            AND -> stack[--top] = stack[top] and stack[top + 1]
            OR -> stack[--top] = stack[top] or stack[top + 1]
            XOR -> stack[--top] = stack[top] xor stack[top + 1]

            else -> throw IllegalBytecode(bytecode)
        }
        ++pc
    }
}
