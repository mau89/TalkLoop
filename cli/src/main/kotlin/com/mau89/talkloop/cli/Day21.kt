package com.mau89.talkloop.cli

import java.io.File
import kotlin.system.exitProcess

/** ONNX/E5 runs locally; JVM entry point keeps the daily CLI workflow consistent. */
fun main(args: Array<String>) {
    val runner = File("tools/day21/run.sh")
    check(runner.isFile) { "Запустите День 21 из корня TalkLoop." }
    val command = listOf("sh", runner.absolutePath) + args.ifEmpty { arrayOf("build") }
    exitProcess(ProcessBuilder(command).inheritIO().start().waitFor())
}
