package com.common.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.squareup.kotlinpoet.ksp.KotlinPoetKspPreview

@KotlinPoetKspPreview
class BindingFragmentProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return BindingFragmentProcessor(
            options = environment.options,
            logger = environment.logger,
            codeGenerator = environment.codeGenerator
        )
    }
}