package com.beust.kobalt.app

import com.beust.kobalt.Args
import com.beust.kobalt.Constants
import com.beust.kobalt.Plugins
import com.beust.kobalt.TaskResult
import com.beust.kobalt.api.Kobalt
import com.beust.kobalt.api.KobaltContext
import com.beust.kobalt.api.PluginProperties
import com.beust.kobalt.api.Project
import com.beust.kobalt.internal.IncrementalManager
import com.beust.kobalt.internal.KobaltSettings
import com.beust.kobalt.internal.ParallelLogger
import com.beust.kobalt.internal.PluginInfo
import com.beust.kobalt.internal.build.BuildFile
import com.beust.kobalt.internal.build.VersionFile
import com.beust.kobalt.maven.DependencyManager
import com.beust.kobalt.maven.PomGenerator
import com.beust.kobalt.maven.aether.KobaltMavenResolver
import com.beust.kobalt.misc.KFiles
import com.beust.kobalt.misc.KobaltExecutors
import com.beust.kobalt.misc.kobaltLog
import com.beust.kobalt.plugin.kotlin.kotlinCompilePrivate
import com.google.inject.assistedinject.Assisted
import java.io.File
import java.net.URL
import java.nio.file.Paths
import javax.inject.Inject

/**
 * Manage the compilation of Build.kt. There are two passes for this processing:
 * 1) Extract the repos() and plugins() statements in a separate .kt and compile it into preBuildScript.jar.
 * 2) Actually build the whole Build.kt file after adding to the classpath whatever phase 1 found (plugins, repos)
 */
class BuildFileCompiler @Inject constructor(@Assisted("buildFiles") val buildFiles: List<BuildFile>,
        @Assisted val pluginInfo: PluginInfo, val files: KFiles, val plugins: Plugins,
        val dependencyManager: DependencyManager, val pluginProperties: PluginProperties,
        val executors: KobaltExecutors, val buildScriptUtil: BuildScriptUtil, val settings: KobaltSettings,
        val incrementalManagerFactory: IncrementalManager.IFactory, val args: Args,
        val resolver: KobaltMavenResolver, val pomGeneratorFactory: PomGenerator.IFactory,
        val parallelLogger: ParallelLogger) {

    interface IFactory {
        fun create(@Assisted("buildFiles") buildFiles: List<BuildFile>, pluginInfo: PluginInfo) : BuildFileCompiler
    }

    private val SCRIPT_JAR = "buildScript.jar"

    fun compileBuildFiles(args: Args, forceRecompile: Boolean = false): FindProjectResult {
        //
        // Create the KobaltContext
        // Note: can't use apply{} here or each field will refer to itself instead of the constructor field
        //
        val context = KobaltContext(args)
        context.pluginInfo = pluginInfo
        context.pluginProperties = pluginProperties
        context.dependencyManager = dependencyManager
        context.executors = executors
        context.settings = settings
        context.incrementalManager = incrementalManagerFactory.create()
        context.resolver = resolver
        context.pomGeneratorFactory = pomGeneratorFactory
        context.logger = parallelLogger
        Kobalt.context = context

        // The list of dynamic plug-ins has to be a companion since it's modified directly from
        // the build file, so make sure to reset it so that dynamic plug-ins don't bleed from
        // one build compilation to the next
        Plugins.dynamicPlugins.clear()

        //
        // Find all the projects in the build file, possibly compiling them
        //
        val projectResult = findProjects(context, forceRecompile)

        return projectResult
    }

    val parsedBuildFiles = arrayListOf<ParsedBuildFile>()

    class FindProjectResult(val context: KobaltContext, val projects: List<Project>, val pluginUrls: List<URL>,
            val taskResult: TaskResult)

    private fun findProjects(context: KobaltContext, forceRecompile: Boolean): FindProjectResult {
        var errorTaskResult: TaskResult? = null
        val projects = arrayListOf<Project>()
        buildFiles.forEach { buildFile ->
            val parsedBuildFile = parseBuildFile(context, buildFile)
            parsedBuildFiles.add(parsedBuildFile)
            val pluginUrls = parsedBuildFile.pluginUrls
            val buildScriptJarFile = File(KFiles.findBuildScriptLocation(buildFile, SCRIPT_JAR))

            //
            // Save the current build script absolute directory
            //
            context.internalContext.absoluteDir = buildFile.absoluteDir

            // If the script jar files were generated by a different version, wipe them in case the API
            // changed in-between
            buildScriptJarFile.parentFile.let { dir ->
                if (! VersionFile.isSameVersionFile(dir)) {
                    kobaltLog(1, "Detected new installation, wiping $dir")
                    dir.listFiles().map { it.delete() }
                }
            }

            // Write the modified Build.kt (e.g. maybe profiles were applied) to a temporary file,
            // compile it, jar it in buildScript.jar and run it
            val modifiedBuildFile = KFiles.createTempBuildFileInTempDirectory(deleteOnExit = true)
            KFiles.saveFile(modifiedBuildFile, parsedBuildFile.buildScriptCode)
            val taskResult = maybeCompileBuildFile(context, BuildFile(Paths.get(modifiedBuildFile.path),
                    "Modified ${Constants.BUILD_FILE_NAME}", buildFile.realPath),
                    buildScriptJarFile, pluginUrls, forceRecompile)
            if (taskResult.success) {
                projects.addAll(buildScriptUtil.runBuildScriptJarFile(buildScriptJarFile, pluginUrls, context))
            } else {
                if (errorTaskResult == null) {
                    errorTaskResult = taskResult
                }
            }

            // Clear the absolute dir
            context.internalContext.absoluteDir = null

        }
        val pluginUrls = parsedBuildFiles.flatMap { it.pluginUrls }
        return FindProjectResult(context, projects, pluginUrls,
                if (errorTaskResult != null) errorTaskResult!! else TaskResult())
    }

    private fun maybeCompileBuildFile(context: KobaltContext, buildFile: BuildFile, buildScriptJarFile: File,
            pluginUrls: List<URL>, forceRecompile: Boolean) : TaskResult {
        kobaltLog(2, "Running build file ${buildFile.name} jar: $buildScriptJarFile")

        // If the user specifed --profiles, always recompile the build file since we don't know if
        // the current buildScript.jar we have contains the correct value for these profiles
        // There is still a potential bug if the user builds with a profile and then without any:
        // in this case, we won't recompile the build file. A potential solution for this would be
        // to have a side file that describes which profiles the current buildScript.jar was
        // compiled with.
        val bs = BuildScriptJarFile(buildScriptJarFile)
        val same = bs.sameProfiles(args.profiles)
        if (same && ! forceRecompile && buildScriptUtil.isUpToDate(buildFile, buildScriptJarFile)) {
            kobaltLog(2, "  Build file $buildScriptJarFile is up to date")
            return TaskResult()
        } else {
            kobaltLog(2, "  Need to recompile ${buildFile.name}")

            buildScriptJarFile.delete()
            val buildFileClasspath = Kobalt.buildFileClasspath.map { it.jarFile.get() }.map { it.absolutePath }
            val result = kotlinCompilePrivate {
                classpath(files.kobaltJar)
                classpath(pluginUrls.map { it.file })
                classpath(buildFileClasspath)
                sourceFiles(listOf(buildFile.path.toFile().absolutePath))
                output = buildScriptJarFile
            }.compile(context = context)


            //
            // Generate the file that contains the list of active profiles for this build file
            //
            BuildScriptJarFile(buildScriptJarFile).saveProfiles(args.profiles)

            return result
        }
    }

    /**
     * Generate the script file with only the plugins()/repos() directives and run it. Then return
     * - the source code for the modified Build.kt (after profiles are applied)
     * - the URL's of all the plug-ins that were found.
     */
    private fun parseBuildFile(context: KobaltContext, buildFile: BuildFile) =
            ParsedBuildFile(buildFile, context, buildScriptUtil, dependencyManager, files)
}
