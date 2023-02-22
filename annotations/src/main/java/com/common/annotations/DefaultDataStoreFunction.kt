package com.common.annotations

@Target(AnnotationTarget.CLASS)
annotation class DefaultDataStoreFunction(
    val functionName: String,
    val typeName: String,
    val protoFileName: String,
    val dsFileName: String = ""
)