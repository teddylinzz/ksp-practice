package com.common.annotations

@Target(AnnotationTarget.CLASS)
annotation class GlobalPropertyClass

@Target(AnnotationTarget.CLASS)
annotation class GlobalPropertyClassImport(val packages: Array<String> = [])

@Target(AnnotationTarget.PROPERTY)
annotation class GlobalProperty(val initValueString: String)