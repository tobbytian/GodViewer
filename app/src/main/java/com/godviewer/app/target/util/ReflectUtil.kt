package com.godviewer.app.target.util

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * libxposed API 102 迁移后的反射工具（沿用旧 XposedHelpers 的查找语义）：
 * 沿继承链查找 declared 成员并 setAccessible；找不到即抛异常，由调用方 runCatching 兜底。
 */

fun findField(clazz: Class<*>, name: String): Field {
    var c: Class<*>? = clazz
    while (c != null) {
        val current: Class<*> = c
        runCatching {
            return current.getDeclaredField(name).apply { isAccessible = true }
        }
        c = current.superclass
    }
    throw NoSuchFieldException("${clazz.name}#$name")
}

fun getObjectField(obj: Any?, name: String): Any? {
    obj ?: return null
    return findField(obj.javaClass, name).get(obj)
}

fun setObjectField(obj: Any?, name: String, value: Any?) {
    obj ?: return
    findField(obj.javaClass, name).set(obj, value)
}

fun setBooleanField(obj: Any?, name: String, value: Boolean) {
    obj ?: return
    findField(obj.javaClass, name).setBoolean(obj, value)
}

/** 按名字 + 参数类型沿继承链 best match（含非 public，如 View.getListenerInfo / PopupWindow.invokePopup）。 */
fun findMethodBestMatch(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method {
    var c: Class<*>? = clazz
    while (c != null) {
        for (m in c.declaredMethods) {
            if (m.name == name && paramsMatch(m.parameterTypes, parameterTypes)) {
                m.isAccessible = true
                return m
            }
        }
        c = c.superclass
    }
    throw NoSuchMethodException("${clazz.name}#$name(${parameterTypes.joinToString { it.name }})")
}

/** 按名字 + 实参值 best match（替代 XposedHelpers.callMethod 的查找语义）。 */
fun callMethod(obj: Any?, name: String, vararg args: Any?): Any? {
    obj ?: return null
    var c: Class<*>? = obj.javaClass
    while (c != null) {
        for (m in c.declaredMethods) {
            if (m.name != name || m.parameterTypes.size != args.size) continue
            if (argsAssignable(m.parameterTypes, args)) {
                m.isAccessible = true
                return m.invoke(obj, *args)
            }
        }
        c = c.superclass
    }
    throw NoSuchMethodException("${obj.javaClass.name}#$name(${args.size} args)")
}

private fun paramsMatch(actual: Array<Class<*>>, wanted: Array<out Class<*>>): Boolean {
    if (actual.size != wanted.size) return false
    for (i in actual.indices) {
        val w = wanted[i]
        if (w != actual[i] && !w.isAssignableFrom(actual[i])) return false
    }
    return true
}

private fun argsAssignable(params: Array<Class<*>>, args: Array<out Any?>): Boolean {
    for (i in params.indices) {
        val arg = args[i] ?: continue
        val pt = params[i]
        if (pt.isPrimitive) {
            if (primitiveBox(pt)?.isInstance(arg) != true) return false
        } else if (!pt.isInstance(arg)) {
            return false
        }
    }
    return true
}

private fun primitiveBox(type: Class<*>): Class<*>? = when (type) {
    java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
    java.lang.Integer.TYPE -> java.lang.Integer::class.java
    java.lang.Long.TYPE -> java.lang.Long::class.java
    java.lang.Float.TYPE -> java.lang.Float::class.java
    java.lang.Double.TYPE -> java.lang.Double::class.java
    java.lang.Short.TYPE -> java.lang.Short::class.java
    java.lang.Byte.TYPE -> java.lang.Byte::class.java
    java.lang.Character.TYPE -> java.lang.Character::class.java
    else -> null
}
