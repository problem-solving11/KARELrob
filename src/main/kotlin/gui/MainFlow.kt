package gui

import common.Diagnostic
import common.Stack
import logic.*
import syntax.lexer.Lexer
import syntax.parser.Parser
import syntax.parser.program
import vm.CodeGenerator
import vm.Instruction
import vm.VirtualMachine
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingWorker
import javax.swing.Timer

const val CHECK_TOTAL_NS = 2_000_000_000L
const val CHECK_REPAINT_NS = 100_000_000L

abstract class MainFlow : MainDesign(AtomicReference(Problem.karelsFirstProgram.randomWorld())),
    VirtualMachine.Callbacks {

    val currentProblem: Problem
        get() = controlPanel.problemPicker.selectedItem as Problem

    fun delay(): Int {
        val logarithm = controlPanel.delayLogarithm()
        return if (logarithm < 0) logarithm else 1.shl(logarithm)
    }

    var initialWorld: World = atomicWorld.get()

    lateinit var virtualMachine: VirtualMachine

    val timer: Timer = Timer(delay()) {
        step { virtualMachine.stepInto(virtualMachinePanel.isVisible) }
    }

    fun executeGoal(goal: String) {
        start(vm.createGoalInstructions(goal))
    }

    fun checkAgainst(goal: String) {
        editor.indent()
        editor.saveWithBackup()
        editor.clearDiagnostics()
        try {
            val lexer = Lexer(editor.text)
            val parser = Parser(lexer)
            parser.program()
            val main = parser.sema.command(currentProblem.name)
            if (main != null) {
                val instructions: List<Instruction> = CodeGenerator(parser.sema).generate(main)
                virtualMachinePanel.setProgram(instructions)

                val goalInstructions = vm.createGoalInstructions(goal)

                check(instructions, goalInstructions)
            } else {
                editor.setCursorTo(editor.length())
                showDiagnostic("void ${currentProblem.name}() not found")
            }
        } catch (diagnostic: Diagnostic) {
            showDiagnostic(diagnostic)
        }
    }

    private fun check(instructions: List<Instruction>, goalInstructions: List<Instruction>) {
        controlPanel.checkStarted()
        worldPanel.isEnabled = false

        object : SwingWorker<String, Unit>() {
            override fun doInBackground(): String {
                val start = System.nanoTime()
                var nextRepaint = CHECK_REPAINT_NS

                var worldCounter = 0
                for (world in currentProblem.randomWorlds()) {
                    initialWorld = world
                    checkOneWorld(instructions, goalInstructions)
                    ++worldCounter

                    val elapsed = System.nanoTime() - start
                    if (elapsed >= CHECK_TOTAL_NS) {
                        return if (currentProblem.numWorlds == UNKNOWN) {
                            "checked $worldCounter random worlds"
                        } else {
                            "checked $worldCounter random worlds\nfrom ${currentProblem.numWorlds} possible worlds"
                        }
                    } else if (elapsed >= nextRepaint) {
                        worldPanel.repaint()
                        nextRepaint += CHECK_REPAINT_NS
                    }
                }
                return "checked all ${currentProblem.numWorlds} possible worlds"
            }

            override fun done() {
                controlPanel.checkFinished(currentProblem.isRandom)
                worldPanel.isEnabled = true

                virtualMachinePanel.clearStack()
                editor.clearStack()
                update()

                try {
                    val successMessage = get()
                    showDiagnostic(successMessage)
                } catch (wrappedDiagnostic: ExecutionException) {
                    showDiagnostic(wrappedDiagnostic.cause as Diagnostic)
                }
            }
        }.execute()
    }

    private fun checkOneWorld(instructions: List<Instruction>, goalInstructions: List<Instruction>) {
        val goalWorldIterator = goalWorlds(goalInstructions).iterator()

        atomicWorld.set(initialWorld)
        createVirtualMachine(instructions) { world ->
            if (!goalWorldIterator.hasNext()) {
                throw Diagnostic(virtualMachine.currentInstruction.position, "overshoots goal")
            }
            if (!goalWorldIterator.next().equalsIgnoringDirection(world)) {
                throw Diagnostic(virtualMachine.currentInstruction.position, "deviates from goal")
            }
        }

        try {
            virtualMachine.executeUserProgram()
        } catch (_: Stack.Exhausted) {
            if (currentProblem.checkAfter === CheckAfter.FINISH) {
                if (!goalWorldIterator.next().equalsIgnoringDirection(virtualMachine.world)) {
                    throw Diagnostic(virtualMachine.currentInstruction.position, "fails goal")
                }
            }
        } catch (error: KarelError) {
            throw Diagnostic(virtualMachine.currentInstruction.position, error.message!!)
        }
        if (goalWorldIterator.hasNext()) {
            throw Diagnostic(virtualMachine.currentInstruction.position, "falls short of goal")
        }
    }

    private fun createVirtualMachine(instructions: List<Instruction>, callback: (World) -> Unit) {
        virtualMachine = when (currentProblem.checkAfter) {
            CheckAfter.BEEPER_MOVE ->
                VirtualMachine(instructions, atomicWorld, ignoreCallAndReturn, callback, callback)

            CheckAfter.BEEPER ->
                VirtualMachine(instructions, atomicWorld, ignoreCallAndReturn, callback)

            CheckAfter.FINISH ->
                VirtualMachine(instructions, atomicWorld, ignoreCallAndReturn)
        }
    }

    private fun goalWorlds(goalInstructions: List<Instruction>): List<World> {
        val goalWorlds = ArrayList<World>()
        atomicWorld.set(initialWorld)
        createVirtualMachine(goalInstructions, goalWorlds::add)
        try {
            virtualMachine.executeGoalProgram()
        } catch (_: Stack.Exhausted) {
            if (currentProblem.checkAfter === CheckAfter.FINISH) {
                goalWorlds.add(virtualMachine.world)
            }
        }
        return goalWorlds
    }

    private val ignoreCallAndReturn = object : VirtualMachine.Callbacks {
        override fun onInfiniteLoop() {
            throw Diagnostic(virtualMachine.currentInstruction.position, "infinite loop detected")
        }
    }

    fun parseAndExecute() {
        editor.indent()
        editor.saveWithBackup()
        editor.clearDiagnostics()
        try {
            val lexer = Lexer(editor.text)
            val parser = Parser(lexer)
            parser.program()
            val main = parser.sema.command(currentProblem.name)
            if (main != null) {
                val instructions = CodeGenerator(parser.sema).generate(main)
                start(instructions)
            } else {
                editor.setCursorTo(editor.length())
                showDiagnostic("void ${currentProblem.name}() not found")
            }
        } catch (diagnostic: Diagnostic) {
            showDiagnostic(diagnostic)
        }
    }

    fun start(instructions: List<Instruction>) {
        tabbedEditors.tabs.isEnabled = false
        virtualMachinePanel.setProgram(instructions)
        virtualMachine = VirtualMachine(instructions, atomicWorld, this)
        controlPanel.executionStarted()
        update()
        if (delay() >= 0) {
            timer.start()
        }
    }

    fun stop() {
        timer.stop()
        controlPanel.executionFinished(currentProblem.isRandom)
        virtualMachinePanel.clearStack()
        tabbedEditors.tabs.isEnabled = true
        editor.clearStack()
        editor.requestFocusInWindow()
    }

    override fun onCall(callerPosition: Int, calleePosition: Int) {
        editor.push(callerPosition, calleePosition)
    }

    override fun onReturn() {
        editor.pop()
    }

    override fun onInfiniteLoop() {
        showDiagnostic("infinite loop detected")
    }

    fun update() {
        val instruction = virtualMachine.currentInstruction
        val position = instruction.position
        if (position > 0) {
            editor.setCursorTo(position)
        }
        virtualMachinePanel.update(virtualMachine.pc, virtualMachine.stack)
        worldPanel.repaint()
    }

    fun stepInto() {
        step { virtualMachine.stepInto(virtualMachinePanel.isVisible) }
        editor.requestFocusInWindow()
    }

    fun step(how: () -> Unit) {
        try {
            how()
            update()
        } catch (_: Stack.Exhausted) {
            stop()
            update()
        } catch (error: KarelError) {
            stop()
            update()
            showDiagnostic(error.message!!)
        }
    }

    fun showDiagnostic(diagnostic: Diagnostic) {
        editor.setCursorTo(diagnostic.position)
        showDiagnostic(diagnostic.message)
    }

    fun showDiagnostic(message: String) {
        editor.requestFocusInWindow()
        editor.showDiagnostic(message)
    }

    init {
        story.load(currentProblem.story)
        if (editor.length() == 0) {
            editor.load(helloWorld)
        }
        editor.requestFocusInWindow()
    }
}

const val helloWorld = """/*
F1 = moveForward();
F2 = turnLeft();
F3 = turnAround();
F4 = turnRight();
F5 = pickBeeper();
F6 = dropBeeper();
*/

void karelsFirstProgram()
{
    // your code here
    
}
"""
