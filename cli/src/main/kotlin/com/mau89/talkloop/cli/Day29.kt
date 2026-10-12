package com.mau89.talkloop.cli

import com.mau89.talkloop.llm.OllamaTuning
import com.mau89.talkloop.llm.COOKBOOK_OLLAMA_MODEL

/** Repeatable before/after experiment; all profiles use only the local provider. */
fun main(args: Array<String>) {
    fun option(name: String, default: String): String {
        val index = args.indexOf(name)
        return if (index < 0) default else args.getOrNull(index + 1) ?: error("После $name нужно значение")
    }
    val profile = option("--profile", "optimized")
    require(profile in listOf("baseline", "parameters", "optimized"))
    require(option("--provider", "local") == "local") { "День 29 сравнивает только локальные модели" }
    val tuning = when (profile) {
        "baseline" -> null
        "parameters" -> OllamaTuning(recipePrompts = false)
        else -> OllamaTuning()
    }
    val output = if ("--output" in args) emptyArray() else arrayOf("--output", "day29-data/$profile.json")
    val model = if (profile == "optimized" && "--model" !in args) arrayOf("--model", COOKBOOK_OLLAMA_MODEL) else emptyArray()
    runRagBenchmark(args + output + model, tuning)
}
