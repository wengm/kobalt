package com.beust.kobalt.plugin.kotlin;

import com.beust.kobalt.api.Kobalt
import com.beust.kobalt.api.Project
import com.beust.kobalt.internal.*
import com.beust.kobalt.maven.*
import com.beust.kobalt.misc.KobaltExecutors
import com.beust.kobalt.misc.log
import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.properties.Delegates

/**
 * @author Cedric Beust <cedric@beust.com>
 * @since 08 03, 2015
 */
@Singleton
class KotlinCompiler @Inject constructor(override val localRepo : LocalRepo,
        override val files: com.beust.kobalt.misc.KFiles,
        override val depFactory: DepFactory,
        override val dependencyManager: DependencyManager,
        override val executors: KobaltExecutors,
        override val jvmCompiler: JvmCompiler)
        : JvmCompilerPlugin(localRepo, files, depFactory, dependencyManager, executors, jvmCompiler) {
    private val KOTLIN_VERSION = "1.0.0-beta-1038"

    override val name = "kotlin"

    private fun getKotlinCompilerJar(name: String) : String {
        val id = "org.jetbrains.kotlin:$name:$KOTLIN_VERSION"
        val dep = MavenDependency.create(id, executors.miscExecutor)
        val result = dep.jarFile.get().absolutePath
        return result
    }

    /**
     * Create an ICompilerAction and a CompilerActionInfo suitable to be passed to doCompiler() to perform the
     * actual compilation.
     */
    fun compile(project: Project?, compileDependencies: List<IClasspathDependency>, otherClasspath: List<String>,
            source: List<String>, outputDir: String, args: List<String>) : TaskResult {

        val executor = executors.newExecutor("KotlinCompiler", 10)
        val compilerDep = depFactory.create("org.jetbrains.kotlin:kotlin-compiler-embeddable:$KOTLIN_VERSION", executor)
        val deps = compilerDep.transitiveDependencies(executor)

        // Force a download of the compiler dependencies
        deps.forEach { it.jarFile.get() }

        executor.shutdown()

        val classpathList = arrayListOf(
                getKotlinCompilerJar("kotlin-stdlib"),
                getKotlinCompilerJar("kotlin-compiler-embeddable"))
            .map { FileDependency(it) }

        val dependencies = arrayListOf<IClasspathDependency>()
            .plus(compileDependencies)
            .plus(classpathList)
            .plus(otherClasspath.map { FileDependency(it)})
        val info = CompilerActionInfo(dependencies, source, outputDir, args)
        val compilerAction = object: ICompilerAction {
            override fun compile(info: CompilerActionInfo): TaskResult {
                log(1, "Compiling ${source.size} files")
                val allArgs : Array<String> = arrayOf(
                        "-d", info.outputDir,
                        "-classpath", info.dependencies.map {it.jarFile.get()}.joinToString(File.pathSeparator),
                        *(info.compilerArgs.toTypedArray()),
                        info.sourceFiles.joinToString(" ")
                )
                log(2, "Calling kotlinc " + allArgs.joinToString(" "))
                CLICompiler.doMainNoExit(K2JVMCompiler(), allArgs)
                return TaskResult()
            }
        }
        return jvmCompiler.doCompile(project, context, compilerAction, info)
    }
}

class KConfiguration @Inject constructor(val compiler: KotlinCompiler){
    val classpath = arrayListOf<String>()
    val dependencies = arrayListOf<IClasspathDependency>()
    var source = arrayListOf<String>()
    var output: String by Delegates.notNull()
    val args = arrayListOf<String>()

    fun sourceFiles(s: String) = source.add(s)

    fun sourceFiles(s: List<String>) = source.addAll(s)

    fun classpath(s: String) = classpath.add(s)

    fun classpath(s: List<String>) = classpath.addAll(s)

    fun compilerArgs(s: List<String>) = args.addAll(s)

    fun compile(project: Project?) : TaskResult {
        return compiler.compile(project, dependencies, classpath, source, output, args)
    }
}

fun kotlinCompilePrivate(ini: KConfiguration.() -> Unit) : KConfiguration {
    val result = Kobalt.INJECTOR.getInstance(KConfiguration::class.java)
    result.ini()
    return result
}
