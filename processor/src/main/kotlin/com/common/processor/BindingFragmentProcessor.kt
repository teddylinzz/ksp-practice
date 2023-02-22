package com.common.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.KotlinPoetKspPreview
import com.squareup.kotlinpoet.ksp.writeTo

@KotlinPoetKspPreview
class BindingFragmentProcessor(
    private val options: Map<String, String>,
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols =
            resolver.getSymbolsWithAnnotation("com.common.annotations.AutoCompleteBindingFragment")
                .filterIsInstance(KSClassDeclaration::class.java)
        if (!symbols.iterator().hasNext()) {
            return emptyList()
        }
        val ret = symbols.filterNot { it.validate() }.toList()
        symbols.filter { it.validate() }.forEach {
            it.accept(Visitor(), Unit)
        }
        return ret
    }

    inner class Visitor : KSVisitorVoid() {
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            if (classDeclaration.classKind != ClassKind.INTERFACE) {
                logger.error(
                    "Only interface can be annotated with @AutoCompleteBindingFragment",
                    classDeclaration
                )
                return
            }

            val annotation: KSAnnotation = classDeclaration.annotations.first {
                it.shortName.asString() == ANNOTATION_NAME
            }

            val bindingClassName: String = annotation.arguments
                .first { arg -> arg.name?.asString() == ARG_BINDING_CLASS_NAME }.value.toString()
            val bindingClassPackageName = options[ARG_BINDING_CLASS_PACKAGE_NAME] ?: ""

            var baseClassNameArgumentValue: String = options[ARG_BASE_CLASS_PACKAGE_NAME] ?: ""

            annotation.arguments.first { arg -> arg.name?.asString() == ARG_BASE_CLASS_PACKAGE_NAME }.value.toString()
                .takeIf { it.isNotEmpty() }
                ?.also {
                    baseClassNameArgumentValue = it
                }
            val indexOfBaseClassName =
                if (baseClassNameArgumentValue.isNotEmpty()) baseClassNameArgumentValue.indexOfLast { it == '.' } else -1
            val hasBaseClass = indexOfBaseClassName > 0
            val baseClassName = if (hasBaseClass) baseClassNameArgumentValue.substring(
                indexOfBaseClassName + 1
            ) else ""

            val basePackageName =
                if (hasBaseClass) baseClassNameArgumentValue.substring(
                    0,
                    indexOfBaseClassName
                ) else ""

            val newFragmentName = "${classDeclaration.simpleName.asString()}Impl"

            val fileSpec = FileSpec.builder("com.binding.fragment", newFragmentName)
            val classSpec = TypeSpec.classBuilder(newFragmentName).apply {
                addModifiers(KModifier.OPEN)

                if (hasBaseClass) {
                    addSuperinterface(TypeVariableName("${baseClassName}()"))
                } else {
                    addSuperinterface(TypeVariableName("Fragment()"))
                }

                addProperty(
                    PropertySpec.builder(
                        "_binding",
                        TypeVariableName("$bindingClassName?"),
                        KModifier.PRIVATE
                    ).apply {
                        mutable(true)
                        initializer(
                            "null"
                        )
                    }.build()
                )

                addProperty(
                    PropertySpec.builder(
                        "binding",
                        TypeVariableName("${bindingClassName}?"),
                        KModifier.PROTECTED
                    ).apply {
                        getter(FunSpec.getterBuilder().addStatement("return _binding").build())
                    }.build()
                )

                addFunction(
                    FunSpec.builder("onCreateView")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter(
                            ParameterSpec.builder(
                                "inflater",
                                TypeVariableName("LayoutInflater")
                            ).build()
                        )
                        .addParameter(
                            ParameterSpec.builder(
                                "container",
                                TypeVariableName("ViewGroup?")
                            ).build()
                        )
                        .addParameter(
                            ParameterSpec.builder(
                                "savedInstanceState",
                                TypeVariableName("Bundle?")
                            ).build()
                        )
                        .returns(TypeVariableName("View"))
                        .addStatement(
                            "return ${bindingClassName}\n" +
                                    "        .inflate(inflater, container, false)\n" +
                                    "        .also { _binding = it }\n" +
                                    "        .root"
                        )
                        .build()
                )

                addFunction(
                    FunSpec.builder("onDestroyView")
                        .addModifiers(KModifier.OVERRIDE)
                        .addStatement("super.onDestroyView()")
                        .addStatement("_binding = null")
                        .build()
                )
            }.build()


            if (hasBaseClass) {
                fileSpec.addImport(basePackageName, baseClassName)
            } else {
                fileSpec.addImport("androidx.fragment.app", "Fragment")
            }

            fileSpec
                .addImport("android.view", "View")
                .addImport("android.os", "Bundle")
                .addImport("android.view", "LayoutInflater")
                .addImport("android.view", "ViewGroup")
                .addImport(bindingClassPackageName, bindingClassName)
                .addType(classSpec)
                .build()
                .writeTo(codeGenerator, true)
        }
    }

    companion object {
        const val ANNOTATION_NAME = "AutoCompleteBindingFragment"
        const val ARG_BINDING_CLASS_NAME = "bindingClassName"
        const val ARG_BINDING_CLASS_PACKAGE_NAME = "bindingPackageName"
        const val ARG_BASE_CLASS_PACKAGE_NAME = "basePackageName"
    }
}