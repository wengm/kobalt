package com.beust.kobalt

import com.beust.kobalt.api.Project
import com.beust.kobalt.api.annotation.Directive

class TestConfig(val project: Project, val isDefault : Boolean = false) {
    val testArgs = arrayListOf<String>()
    val jvmArgs = arrayListOf<String>()
    val testIncludes = arrayListOf("**/*Test.class")
    val testExcludes = arrayListOf<String>()

    @Directive
    var name: String = ""

    @Directive
    fun args(vararg arg: String) {
        testArgs.addAll(arg)
    }

    @Directive
    fun jvmArgs(vararg arg: String) {
        jvmArgs.addAll(arg)
    }

    @Directive
    fun include(vararg arg: String) {
        testIncludes.apply {
            clear()
            addAll(arg)
        }
    }

    @Directive
    fun exclude(vararg arg: String) {
        testExcludes.apply {
            clear()
            addAll(arg)
        }
    }
}