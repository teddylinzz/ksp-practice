package com.common.processor

import com.common.annotations.GlobalPropertyClassImport
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.KotlinPoetKspPreview
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

@KotlinPoetKspPreview
class GlobalVariableProcessor(
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols =
            resolver
                .getSymbolsWithAnnotation("com.common.annotations.GlobalPropertyClass")
                .filterIsInstance(KSClassDeclaration::class.java)

        if (!symbols.iterator().hasNext()) {
            return emptyList()
        }

        createGlobalPropertyChannel()

        val ret = symbols.filterNot { it.validate() }.toList()

        symbols.filter { it.validate() }.forEach {
            it.accept(FindFunctionsVisitor(), Unit)
        }
        return ret
    }

    inner class FindFunctionsVisitor : KSVisitorVoid() {

        private val properties: MutableList<PropertySpec> = arrayListOf()

        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            if (classDeclaration.classKind != ClassKind.INTERFACE) {
                logger.error(
                    "Only interface can be annotated with @GlobalProperty",
                    classDeclaration
                )
                return
            }

            val properties: Sequence<KSPropertyDeclaration> =
                classDeclaration
                    .getDeclaredProperties()
                    .filter { it.annotations.iterator().hasNext() && it.validate() }

            if (properties.iterator().hasNext()) {
                properties.forEach {
                    it.accept(this, Unit)
                }
            }
            classDeclaration.containingFile?.accept(this, Unit)
        }

        override fun visitPropertyDeclaration(property: KSPropertyDeclaration, data: Unit) {
            val channelPropertyName = "${property.simpleName.asString()}Channel"
            val typeClassName = property.type.resolve().toClassName().simpleName
            val channelPropertySpecBuilder = PropertySpec.builder(
                channelPropertyName,
                TypeVariableName("Channel<$typeClassName>")
            ).apply {
                initializer("Channel()")
            }
            properties.add(channelPropertySpecBuilder.build())
            val propertySpecBuilder = PropertySpec.builder(
                property.simpleName.asString(),
                TypeVariableName(typeClassName)
            ).apply {
                mutable(true)
                delegate(buildCodeBlock {
                    var defaultValue: String =
                        property.annotations.takeIf { it.iterator().hasNext() }
                            ?.first()?.arguments?.takeIf { it.iterator().hasNext() }
                            ?.first()?.value?.toString() ?: ""

                    if (defaultValue.isEmpty()) {
                        if (typeClassName == String::class.simpleName) {
                            defaultValue = "\"\""
                        } else {
                            logger.error(
                                "Should add init value to annotation. i.e. @GlobalProperty(\"Your initial value here.\")",
                                null
                            )
                            return
                        }
                    }
                    addStatement("$FUN_GLOBAL_DISPATCH_VAR($defaultValue,$channelPropertyName)")
                })
            }
            properties.add(propertySpecBuilder.build())
        }

        override fun visitFile(file: KSFile, data: Unit) {
            val name = file.fileName.split(".").first()
            val objectSpec = TypeSpec.objectBuilder(name)
            val fileSpec = FileSpec.builder(PACKAGE_NAME, name)

            file.declarations.filterIsInstance(KSClassDeclaration::class.java)
                .forEach { classDeclaration ->
                    classDeclaration.annotations
                        .filter { it.shortName.asString() == GlobalPropertyClassImport::class.simpleName }
                        .forEach {
                            it.arguments.forEach { argument ->
                                val argumentValue =
                                    argument.value.toString().replace("[", "").replace("]", "")

                                val indexOfDotLast =
                                    argumentValue.indexOfLast { char -> char == '.' }

                                fileSpec.addImport(
                                    argumentValue.substring(0, indexOfDotLast),
                                    argumentValue.substring(indexOfDotLast)
                                )
                            }
                        }
                }
            fileSpec
                .addImport("kotlinx.coroutines.channels", "Channel")
                .tag(fileSpec.name)

            properties.forEach { objectSpec.addProperty(it) }

            fileSpec
                .addType(objectSpec.build())
                .build()
                .writeTo(codeGenerator, true)
        }
    }


    private fun createGlobalPropertyChannel() {
        val className = "GlobalPropertyChannel"
        val fileSpec = FileSpec.builder(PACKAGE_NAME, className)
        val classSpec = TypeSpec.classBuilder(className).apply {
            primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("field", TypeVariableName("V"))
                    .addParameter("channel", TypeVariableName("Channel<V>"))
                    .build()
            )
            addProperty(
                PropertySpec.builder(
                    "field",
                    TypeVariableName("V"),
                    KModifier.PRIVATE
                ).mutable(true).initializer("field").build()
            )
            addProperty(
                PropertySpec.builder(
                    "channel",
                    TypeVariableName("Channel<V>"),
                    KModifier.PRIVATE
                ).initializer("channel").build()
            )
            addTypeVariable(TypeVariableName("V"))
            addSuperinterface(TypeVariableName("ReadWriteProperty<Any, V> "))
            addFunction(
                FunSpec.builder("getValue")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("thisRef", Any::class)
                    .addParameter("property", TypeVariableName("KProperty<*>"))
                    .returns(TypeVariableName("V"))
                    .addStatement("return field")
                    .build()
            )

            addFunction(
                FunSpec.builder("setValue")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("thisRef", Any::class)
                    .addParameter("property", TypeVariableName("KProperty<*>"))
                    .addParameter("value", TypeVariableName("V"))
                    .addStatement("this.field = value")
                    .addStatement("value?.let { newData -> GlobalScope.launch { channel.send(newData) } }")
                    .build()
            )
        }

        val extensionFun = FunSpec.builder(FUN_GLOBAL_DISPATCH_VAR)
            .apply {
                addModifiers(KModifier.INLINE)
                addTypeVariable(TypeVariableName("reified V"))
                addParameter(ParameterSpec.builder("initValue", TypeVariableName("V")).build())
                addParameter(
                    ParameterSpec.builder("channel", TypeVariableName("Channel<V>")).build()
                )
                addStatement("return GlobalPropertyChannel(initValue, channel)")

                returns(TypeVariableName("GlobalPropertyChannel<V>"))
            }
            .build()

        fileSpec
            .addImport("kotlinx.coroutines", "GlobalScope")
            .addImport("kotlin.reflect", "KProperty")
            .addImport("kotlinx.coroutines", "launch")
            .addImport("kotlin.properties", "ReadWriteProperty")
            .addImport("kotlinx.coroutines.channels", "Channel")
            .addFunction(extensionFun)
            .addType(classSpec.build())
            .build()
            .writeTo(codeGenerator, true)
    }

    companion object {
        const val PACKAGE_NAME = "com.global.properties"
        const val FUN_GLOBAL_DISPATCH_VAR = "globalDispatchVar"
    }
}