/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package tasks

import groovy.util.Node
import groovy.util.XmlParser
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.*

open class WriteCopyrightToFile : DefaultTask() {
    @InputFile
    var path = project.file("${project.rootDir}/.idea/copyright/apache.xml")

    @OutputFile
    lateinit var outputFile: File

    @Input
    var commented: Boolean = true

    @TaskAction
    fun write() {
        outputFile.writeText(if (commented) readCopyrightCommented() else readCopyright())
    }

    private fun readCopyright(): String {
        assert(path.exists()) {
            "File $path with copyright not found"
        }

        val xmlParser = XmlParser()
        val node = xmlParser.parse(path)
        assert(node.attribute("name") == "CopyrightManager") {
            "Format changed occasionally?"
        }

        val copyrightBlock = node.children().filterIsInstance<Node>().single()
        val noticeNode = copyrightBlock.children().filterIsInstance<Node>().single { it.attribute("name") == "notice" }
        return noticeNode.attribute("value").toString().replace("&#36;today.year", GregorianCalendar()[Calendar.YEAR].toString())
    }

    private fun readCopyrightCommented(): String {
        return "/*\n" + readCopyright().prependIndent(" * ") + "\n */"
    }
}