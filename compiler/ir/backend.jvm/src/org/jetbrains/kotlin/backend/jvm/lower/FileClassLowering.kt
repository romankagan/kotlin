/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.backend.common.phaser.makeIrModulePhase
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.codegen.AsmUtil
import org.jetbrains.kotlin.config.JvmAnalysisFlags
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fileClasses.JvmFileClassInfo
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.fileClasses.JvmSimpleFileClassInfo
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrClassImpl
import org.jetbrains.kotlin.ir.descriptors.WrappedClassDescriptor
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.load.java.JavaVisibilities
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils
import org.jetbrains.kotlin.psi2ir.PsiSourceManager
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement
import java.util.*

internal val fileClassPhase = makeIrModulePhase(
    ::FileClassLowering,
    name = "FileClass",
    description = "Put file level function and property declaration into a class",
    stickyPostconditions = setOf(::checkAllFileLevelDeclarationsAreClasses)
)

private fun checkAllFileLevelDeclarationsAreClasses(irModuleFragment: IrModuleFragment) {
    assert(irModuleFragment.files.all { irFile ->
        irFile.declarations.all { it is IrClass }
    })
}

private class FileClassLowering(val context: JvmBackendContext) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        val classes = ArrayList<IrClass>()
        val fileClassMembers = ArrayList<IrDeclaration>()

        irFile.declarations.forEach {
            if (it is IrClass)
                classes.add(it)
            else
                fileClassMembers.add(it)
        }

        if (fileClassMembers.isEmpty() && (irFile.metadata as? MetadataSource.File)?.descriptors.isNullOrEmpty()) return

        val irFileClass = createFileClass(irFile, fileClassMembers)
        classes.add(irFileClass)

        irFile.declarations.clear()
        irFile.declarations.addAll(classes)
    }

    private fun createFileClass(irFile: IrFile, fileClassMembers: List<IrDeclaration>): IrClass {
        val fileEntry = irFile.fileEntry
        val fileClassInfo: JvmFileClassInfo?
        val descriptor: ClassDescriptor
        when (fileEntry) {
            is PsiSourceManager.PsiFileEntry -> {
                val ktFile = context.psiSourceManager.getKtFile(fileEntry)
                    ?: throw AssertionError("Unexpected file entry: $fileEntry")
                fileClassInfo = JvmFileClassUtil.getFileClassInfoNoResolve(ktFile)
                descriptor = WrappedClassDescriptor()
            }
            is NaiveSourceBasedFileEntryImpl -> {
                fileClassInfo = JvmSimpleFileClassInfo(PackagePartClassUtils.getPackagePartFqName(irFile.fqName, fileEntry.name), false)
                descriptor = WrappedClassDescriptor()
            }
            else -> error("unknown kind of file entry: $fileEntry")
        }
        return IrClassImpl(
            0, fileEntry.maxOffset,
            if (!fileClassInfo.withJvmMultifileClass || context.state.languageVersionSettings.getFlag(JvmAnalysisFlags.inheritMultifileParts))
                IrDeclarationOrigin.FILE_CLASS else IrDeclarationOrigin.SYNTHETIC_FILE_CLASS,
            symbol = IrClassSymbolImpl(descriptor),
            name = fileClassInfo.fileClassFqName.shortName(),
            kind = ClassKind.CLASS,
            visibility = if (!fileClassInfo.withJvmMultifileClass) Visibilities.PUBLIC else JavaVisibilities.PACKAGE_VISIBILITY,
            modality = Modality.FINAL
        ).apply {
            descriptor.bind(this)
            superTypes += context.irBuiltIns.anyType
            parent = irFile
            declarations.addAll(fileClassMembers)
            createImplicitParameterDeclarationWithWrappedDescriptor()
            fileClassMembers.forEach {
                it.parent = this
                if (it is IrProperty) {
                    it.getter?.let { it.parent = this }
                    it.setter?.let { it.parent = this }
                    it.backingField?.let { it.parent = this }
                }
            }

            annotations += irFile.annotations

            metadata = irFile.metadata

            val partClassType = AsmUtil.asmTypeByFqNameWithoutInnerClasses(fileClassInfo.fileClassFqName)
            val facadeClassType =
                if (fileClassInfo.withJvmMultifileClass) AsmUtil.asmTypeByFqNameWithoutInnerClasses(fileClassInfo.facadeClassFqName)
                else null
            context.state.factory.packagePartRegistry.addPart(irFile.fqName, partClassType.internalName, facadeClassType?.internalName)

            if (fileClassInfo.fileClassFqName != fqNameWhenAvailable) {
                context.classNameOverride[this] = JvmClassName.byInternalName(partClassType.internalName)
            }

            if (facadeClassType != null) {
                val jvmClassName = JvmClassName.byInternalName(facadeClassType.internalName)
                context.multifileFacadesToAdd.getOrPut(jvmClassName) { ArrayList() }.add(this)
            }
        }
    }
}
