package com.common.annotations

@Target(AnnotationTarget.CLASS)
annotation class AutoCompleteBindingFragment(
    val bindingClassName: String,
    val basePackageName: String = ""
)
