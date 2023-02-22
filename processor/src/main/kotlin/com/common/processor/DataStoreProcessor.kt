package com.common.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.KotlinPoetKspPreview
import com.squareup.kotlinpoet.ksp.writeTo
import java.util.*

@KotlinPoetKspPreview
class DataStoreProcessor(
    private val options: Map<String, String>,
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator
) : SymbolProcessor {
    private val visitor = Visitor()
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val annotation = "com.common.annotations.DefaultDataStoreFunction"
        val symbols =
            resolver.getSymbolsWithAnnotation(annotation)
                .filterIsInstance(KSClassDeclaration::class.java)

        if (!symbols.iterator().hasNext()) {
            return emptyList()
        }

        val ret = symbols.filterNot { it.validate() }.toList()

        symbols.filter { it.validate() }.forEach {
            it.accept(visitor, Unit)
        }

        return ret
    }

    inner class Visitor : KSVisitorVoid() {
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            if (classDeclaration.classKind != ClassKind.INTERFACE) {
                logger.error(
                    "Only interface can be annotated with @DefaultDataStoreFunction",
                    classDeclaration
                )
                return
            }

            val appPackageName = options["appPackageName"] ?: return
            val appDSPackageName = options["dsPackageName"] ?: return

            val annotation = classDeclaration.annotations.first()

            val typeName =
                annotation.arguments.first { it.name?.asString() == "typeName" }.value?.toString()
                    ?: ""

            val dsFileName =
                annotation.arguments.first { it.name?.asString() == "dsFileName" }.value?.toString()
                    ?: ""

            val functionName =
                annotation.arguments.first { it.name?.asString() == "functionName" }.value?.toString()
                    ?: ""

            val protoFileName =
                annotation.arguments.first { it.name?.asString() == "protoFileName" }.value?.toString()
                    ?: ""

            val dsName = "${functionName}Store"

            val fileSpec = FileSpec.builder(
                PACKAGE_NAME,
                "${functionName}Ext".capitalize()
            )

            val serializerName =
                "${protoFileName.capitalize()}Serializer"

            val property: PropertySpec? =
                if (dsFileName.isNotEmpty()) {
                    PropertySpec.builder(dsName, TypeVariableName("DataStore<$typeName>")).apply {
                        delegate(buildCodeBlock {
                            addStatement(
                                "dataStore(\n" +
                                        "    fileName = \"$dsFileName\",\n" +
                                        "    serializer = $serializerName\n" +
                                        ")"
                            )
                        })
                    }.apply {
                        receiver(TypeVariableName("Context"))
                    }.build()
                } else null


            val activityExt = FunSpec.builder(functionName).apply {
                addModifiers(KModifier.INLINE)
                receiver(TypeVariableName("AppCompatActivity"))
                returns(TypeVariableName("Job"))
                addParameter(
                    ParameterSpec.builder(
                        "context",
                        TypeVariableName("CoroutineContext")
                    )
                        .defaultValue("EmptyCoroutineContext")
                        .build()
                )

                addParameter(
                    ParameterSpec.builder(
                        "block",
                        TypeVariableName("suspend (${typeName}) -> Unit"),
                        KModifier.CROSSINLINE
                    ).build()
                )
                addStatement(
                    "return lifecycleScope.launch(context) {\n" +
                            "        block(this@${functionName}.$dsName.data.first())\n" +
                            "    }"
                )
            }.build()

            val fragmentExt = FunSpec.builder(functionName).apply {
                addModifiers(KModifier.INLINE)
                receiver(TypeVariableName("Fragment"))
                returns(TypeVariableName("Job"))
                addParameter(
                    ParameterSpec.builder(
                        "context",
                        TypeVariableName("CoroutineContext")
                    )
                        .defaultValue("EmptyCoroutineContext")
                        .build()
                )

                addParameter(
                    ParameterSpec.builder(
                        "block",
                        TypeVariableName("suspend (${typeName}) -> Unit"),
                        KModifier.CROSSINLINE
                    ).build()
                )
                addStatement(
                    "return lifecycleScope.launch(context) {\n" +
                            "        this@${functionName}.context?.let { context ->\n" +
                            "            block(context.$dsName.data.first())\n" +
                            "        }\n" +
                            "    }"
                )
            }.build()

            //Import outside or create by self
            if (property != null) {
                fileSpec.addProperty(property)
            } else {
                fileSpec.addImport(appDSPackageName, dsName)
            }

            fileSpec
                .addFunction(activityExt)
                .addFunction(fragmentExt)
                .addImport("androidx.appcompat.app", "AppCompatActivity")
                .addImport("kotlin.coroutines", "CoroutineContext")
                .addImport("androidx.lifecycle", "lifecycleScope")
                .addImport("kotlin.coroutines", "EmptyCoroutineContext")
                .addImport("kotlinx.coroutines", "Job")
                .addImport("kotlinx.coroutines", "launch")
                .addImport("kotlinx.coroutines.flow", "first")
                .addImport("androidx.fragment.app", "Fragment")
                .addImport("androidx.datastore", "dataStore")
                .addImport("androidx.datastore.core", "DataStore")
                .addImport("android.content", "Context")
                .addImport(appDSPackageName, serializerName)
                .addImport(appPackageName, protoFileName)
                .build().writeTo(codeGenerator, true)
        }
    }

    private fun String.capitalize() =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

    companion object {
        const val PACKAGE_NAME = "com.ds.ext"
    }
}