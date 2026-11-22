package com.mh.starrailcheckin.ui.profile

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.truncate

/** miao calc.js 的 JSON AST 求值器。只实现规则中实际使用的 JavaScript 语法，不执行任意 JS 源码。 */
internal object MiaoJsEvaluator {

    object Undefined {
        override fun toString(): String = "undefined"
    }

    class Env(val parent: Env? = null) {
        private val values = LinkedHashMap<String, Any?>()

        fun define(name: String, value: Any?) {
            values[name] = value
        }

        fun get(name: String): Any? = when {
            values.containsKey(name) -> values[name]
            parent != null -> parent.get(name)
            else -> Undefined
        }

        fun assign(name: String, value: Any?): Boolean {
            if (values.containsKey(name)) {
                values[name] = value
                return true
            }
            return parent?.assign(name, value) ?: false
        }
    }

    class NativeFunction(
        val block: (List<Any?>) -> Any?,
        val properties: Map<String, Any?> = emptyMap()
    ) {
        constructor(block: (List<Any?>) -> Any?) : this(block, emptyMap())

        operator fun invoke(args: List<Any?>): Any? = block(args)
    }

    private class JsFunction(
        val params: JSONArray,
        val body: JSONObject,
        val closure: Env
    )

    private class ReturnSignal(val value: Any?) : RuntimeException()

    fun globals(): Env = Env().also { env ->
        env.define("undefined", Undefined)
        env.define("NaN", Double.NaN)
        env.define("Math", mapOf(
            "min" to NativeFunction { args -> args.map(::toNumber).reduceOrNull(::min) ?: Double.POSITIVE_INFINITY },
            "max" to NativeFunction { args -> args.map(::toNumber).reduceOrNull(::max) ?: Double.NEGATIVE_INFINITY },
            "floor" to NativeFunction { floor(toNumber(it.firstOrNull())) },
            "ceil" to NativeFunction { ceil(toNumber(it.firstOrNull())) },
            "trunc" to NativeFunction { truncate(toNumber(it.firstOrNull())) },
            "round" to NativeFunction {
                val value = toNumber(it.firstOrNull())
                if (value.isFinite()) value.roundToLong().toDouble() else value
            },
            "abs" to NativeFunction { kotlin.math.abs(toNumber(it.firstOrNull())) },
            "pow" to NativeFunction { toNumber(it.getOrNull(0)).pow(toNumber(it.getOrNull(1))) }
        ))
        env.define("Number", NativeFunction(
            { toNumber(it.firstOrNull()) },
            mapOf("isFinite" to NativeFunction { args ->
                val value = args.firstOrNull()
                value is Number && value.toDouble().isFinite()
            })
        ))
        env.define("Format", mapOf(
            "percent" to NativeFunction { args ->
                val value = toNumber(args.firstOrNull()) * 100
                "${trimNumber(value, 1)}%"
            },
            "comma" to NativeFunction { args -> trimNumber(number(args.firstOrNull()), number(args.getOrNull(1)).toInt()) }
        ))
        env.define("logger", mapOf("info" to NativeFunction { null }))
    }

    fun execute(program: JSONObject): Env {
        val env = globals()
        executeNode(program, env)
        return env
    }

    fun isCallable(value: Any?): Boolean = value is NativeFunction || value is JsFunction

    private fun executeNode(node: JSONObject, env: Env): Any? {
        when (node.optString("type")) {
            "Program", "BlockStatement" -> {
                val body = node.optJSONArray("body") ?: return null
                var result: Any? = null
                for (i in 0 until body.length()) {
                    result = executeNode(body.getJSONObject(i), env)
                }
                return result
            }
            "ExportNamedDeclaration" -> {
                val declaration = node.optJSONObject("declaration") ?: return null
                return executeNode(declaration, env)
            }
            "ExportDefaultDeclaration" -> {
                val declaration = node.optJSONObject("declaration") ?: return null
                val value = if (declaration.optString("type") == "FunctionDeclaration") {
                    makeFunction(declaration, env).also { function ->
                        declaration.optJSONObject("id")?.optString("name")?.takeIf { it.isNotBlank() }
                            ?.let { env.define(it, function) }
                    }
                } else {
                    executeNode(declaration, env)
                }
                env.define("\$default", value)
                return value
            }
            "ImportDeclaration" -> return null
            "VariableDeclaration" -> {
                val declarations = node.optJSONArray("declarations") ?: return null
                for (i in 0 until declarations.length()) {
                    val declaration = declarations.getJSONObject(i)
                    val value = declaration.optJSONObject("init")?.let { eval(it, env) } ?: Undefined
                    bindPattern(declaration.getJSONObject("id"), value, env)
                }
                return null
            }
            "FunctionDeclaration" -> {
                val id = node.optJSONObject("id") ?: return null
                env.define(id.optString("name"), makeFunction(node, env))
                return null
            }
            "ExpressionStatement" -> return eval(node.optJSONObject("expression"), env)
            "ReturnStatement" -> {
                val argument = node.optJSONObject("argument")
                throw ReturnSignal(argument?.let { eval(it, env) })
            }
            "IfStatement" -> {
                return if (truthy(eval(node.optJSONObject("test"), env))) {
                    executeNode(node.getJSONObject("consequent"), env)
                } else {
                    node.optJSONObject("alternate")?.let { executeNode(it, env) }
                }
            }
            "ForStatement" -> {
                node.optJSONObject("init")?.let { init ->
                    if (init.optString("type") == "VariableDeclaration") executeNode(init, env) else eval(init, env)
                }
                var guard = 0
                while (node.optJSONObject("test")?.let { truthy(eval(it, env)) } != false) {
                    executeNode(node.getJSONObject("body"), env)
                    node.optJSONObject("update")?.let { eval(it, env) }
                    if (++guard > 10000) throw IllegalStateException("miao rule loop exceeded 10000 iterations")
                }
                return null
            }
            "EmptyStatement" -> return null
            else -> return eval(node, env)
        }
    }

    fun eval(node: JSONObject?, env: Env): Any? {
        if (node == null) return Undefined
        when (node.optString("type")) {
            "Literal" -> return jsonValue(node.opt("value"))
            "Identifier" -> {
                val name = node.optString("name")
                if (name == "Number") return env.get(name)
                return env.get(name)
            }
            "ArrowFunctionExpression", "FunctionExpression" -> return makeFunction(node, env)
            "ObjectExpression" -> {
                val result = LinkedHashMap<String, Any?>()
                val properties = node.optJSONArray("properties") ?: return result
                for (i in 0 until properties.length()) {
                    val property = properties.getJSONObject(i)
                    if (property.optString("type") == "SpreadElement") {
                        val source = eval(property.optJSONObject("argument"), env)
                        if (source is Map<*, *>) source.forEach { (k, v) -> result[string(k)] = v }
                    } else {
                        result[propertyKey(property, env)] = eval(property.optJSONObject("value"), env)
                    }
                }
                return result
            }
            "ArrayExpression" -> {
                val result = mutableListOf<Any?>()
                val elements = node.optJSONArray("elements") ?: return result
                for (i in 0 until elements.length()) {
                    val element = elements.optJSONObject(i)
                    if (element?.optString("type") == "SpreadElement") {
                        val spread = eval(element.optJSONObject("argument"), env)
                        if (spread is List<*>) result.addAll(spread)
                    } else {
                        result.add(element?.let { eval(it, env) } ?: Undefined)
                    }
                }
                return result
            }
            "MemberExpression" -> {
                val target = eval(node.optJSONObject("object"), env)
                return getMember(target, memberName(node, env))
            }
            "CallExpression" -> {
                val calleeNode = node.getJSONObject("callee")
                val function = eval(calleeNode, env)
                val args = mutableListOf<Any?>()
                val arguments = node.optJSONArray("arguments")
                if (arguments != null) {
                    for (i in 0 until arguments.length()) {
                        val argument = arguments.getJSONObject(i)
                        if (argument.optString("type") == "SpreadElement") {
                            val spread = eval(argument.optJSONObject("argument"), env)
                            if (spread is List<*>) args.addAll(spread)
                        } else {
                            args.add(eval(argument, env))
                        }
                    }
                }
                return call(function, args)
            }
            "UnaryExpression" -> {
                val value = eval(node.optJSONObject("argument"), env)
                return when (node.optString("operator")) {
                    "!" -> !truthy(value)
                    "-" -> -toNumber(value)
                    "+" -> toNumber(value)
                    "void" -> Undefined
                    "typeof" -> when (value) {
                        Undefined -> "undefined"
                        is Boolean -> "boolean"
                        is Number -> "number"
                        is String -> "string"
                        is NativeFunction, is JsFunction -> "function"
                        else -> "object"
                    }
                    else -> throw IllegalArgumentException("Unsupported unary ${node.optString("operator")}")
                }
            }
            "BinaryExpression" -> return binary(node.optString("operator"),
                eval(node.optJSONObject("left"), env), eval(node.optJSONObject("right"), env))
            "LogicalExpression" -> {
                val left = eval(node.optJSONObject("left"), env)
                return when (node.optString("operator")) {
                    "&&" -> if (truthy(left)) eval(node.optJSONObject("right"), env) else left
                    "||" -> if (truthy(left)) left else eval(node.optJSONObject("right"), env)
                    "??" -> if (left == null || left == Undefined) eval(node.optJSONObject("right"), env) else left
                    else -> throw IllegalArgumentException("Unsupported logical operator")
                }
            }
            "ConditionalExpression" -> return if (truthy(eval(node.optJSONObject("test"), env))) {
                eval(node.optJSONObject("consequent"), env)
            } else {
                eval(node.optJSONObject("alternate"), env)
            }
            "AssignmentExpression" -> {
                val left = node.getJSONObject("left")
                val right = eval(node.optJSONObject("right"), env)
                val operator = node.optString("operator")
                val value = if (operator == "=") right else {
                    val previous = readReference(left, env)
                    when (operator) {
                        "+=" -> plus(previous, right)
                        "-=" -> toNumber(previous) - toNumber(right)
                        "*=" -> toNumber(previous) * toNumber(right)
                        "/=" -> toNumber(previous) / toNumber(right)
                        "%=" -> toNumber(previous) % toNumber(right)
                        else -> throw IllegalArgumentException("Unsupported assignment $operator")
                    }
                }
                writeReference(left, value, env)
                return value
            }
            "UpdateExpression" -> {
                val left = node.getJSONObject("argument")
                val previous = number(readReference(left, env))
                val value = if (node.optString("operator") == "++") previous + 1 else previous - 1
                writeReference(left, value, env)
                return if (node.optBoolean("prefix")) value else previous
            }
            "SequenceExpression" -> {
                var result: Any? = Undefined
                val expressions = node.optJSONArray("expressions") ?: return result
                for (i in 0 until expressions.length()) result = eval(expressions.getJSONObject(i), env)
                return result
            }
            "TemplateLiteral" -> {
                val quasis = node.optJSONArray("quasis") ?: return ""
                val expressions = node.optJSONArray("expressions")
                val result = StringBuilder()
                for (i in 0 until quasis.length()) {
                    val cooked = quasis.getJSONObject(i).optJSONObject("value")?.opt("cooked")
                    result.append(if (cooked == null || cooked == JSONObject.NULL) "" else cooked.toString())
                    if (expressions != null && i < expressions.length()) {
                        result.append(string(eval(expressions.getJSONObject(i), env)))
                    }
                }
                return result.toString()
            }
            else -> throw IllegalArgumentException("Unsupported JS node ${node.optString("type")}")
        }
    }

    fun call(function: Any?, args: List<Any?>): Any? = when (function) {
        is NativeFunction -> function(args)
        is JsFunction -> {
            val local = Env(function.closure)
            for (i in 0 until function.params.length()) {
                val parameter = function.params.getJSONObject(i)
                if (parameter.optString("type") == "RestElement") {
                    bindPattern(parameter.getJSONObject("argument"), args.drop(i), local)
                    break
                }
                bindPattern(parameter, args.getOrElse(i) { Undefined }, local)
            }
            if (function.body.optString("type") != "BlockStatement") {
                eval(function.body, local)
            } else {
                try {
                    executeNode(function.body, local)
                    Undefined
                } catch (signal: ReturnSignal) {
                    signal.value
                }
            }
        }
        else -> throw IllegalArgumentException("Value is not callable: $function")
    }

    private fun makeFunction(node: JSONObject, env: Env): JsFunction =
        JsFunction(node.optJSONArray("params") ?: JSONArray(), node.getJSONObject("body"), env)

    private fun bindPattern(pattern: JSONObject, value: Any?, env: Env) {
        when (pattern.optString("type")) {
            "Identifier" -> env.define(pattern.optString("name"), value)
            "RestElement" -> bindPattern(pattern.getJSONObject("argument"), value, env)
            "ObjectPattern" -> {
                val properties = pattern.optJSONArray("properties") ?: return
                for (i in 0 until properties.length()) {
                    val property = properties.getJSONObject(i)
                    if (property.optString("type") == "RestElement") continue
                    val key = propertyKey(property, env)
                    bindPattern(property.getJSONObject("value"), getMember(value, key), env)
                }
            }
            "ArrayPattern" -> {
                val elements = pattern.optJSONArray("elements") ?: return
                for (i in 0 until elements.length()) {
                    val element = elements.optJSONObject(i) ?: continue
                    bindPattern(element, (value as? List<*>)?.getOrNull(i) ?: Undefined, env)
                }
            }
            else -> throw IllegalArgumentException("Unsupported binding ${pattern.optString("type")}")
        }
    }

    private fun propertyKey(property: JSONObject, env: Env): String {
        val key = property.opt("key")
        if (property.optBoolean("computed")) return string(eval(key as? JSONObject, env))
        if (key is JSONObject) {
            return if (key.optString("type") == "Identifier") key.optString("name") else string(jsonValue(key.opt("value")))
        }
        return string(key)
    }

    private fun memberName(node: JSONObject, env: Env): String {
        val property = node.optJSONObject("property") ?: return "undefined"
        return if (node.optBoolean("computed")) string(eval(property, env)) else property.optString("name")
    }

    private fun readReference(node: JSONObject, env: Env): Any? = when (node.optString("type")) {
        "Identifier" -> env.get(node.optString("name"))
        "MemberExpression" -> getMember(eval(node.optJSONObject("object"), env), memberName(node, env))
        else -> Undefined
    }

    // 泛型擦除下 as? 只校验容器类型（Map/List），键值类型无法在运行时校验，故抑制 unchecked 警告。
    @Suppress("UNCHECKED_CAST")
    private fun writeReference(node: JSONObject, value: Any?, env: Env) {
        when (node.optString("type")) {
            "Identifier" -> {
                val name = node.optString("name")
                if (!env.assign(name, value)) env.define(name, value)
            }
            "MemberExpression" -> {
                val target = eval(node.optJSONObject("object"), env)
                val key = memberName(node, env)
                when (target) {
                    is MutableMap<*, *> -> (target as? MutableMap<String, Any?>)?.let { it[key] = value }
                    is MutableList<*> -> key.toIntOrNull()?.let { index ->
                        (target as? MutableList<Any?>)?.let { list ->
                            if (index in list.indices) list[index] = value
                        }
                    }
                }
            }
        }
    }

    fun getMember(target: Any?, key: String): Any? = when (target) {
        null, Undefined -> Undefined
        is Map<*, *> -> target[key] ?: Undefined
        is JSONObject -> toJs(if (target.has(key)) target.opt(key) else Undefined)
        is JSONArray -> toJs(if (key == "length") target.length() else target.opt(key.toIntOrNull() ?: -1))
        is List<*> -> when (key) {
            "length" -> target.size
            "includes" -> NativeFunction { args ->
                val expected = args.firstOrNull()
                target.any { equals(it, expected, true) }
            }
            "reduce" -> NativeFunction { args ->
                val callback = args.getOrNull(0)
                if (target.isEmpty() && args.size < 2) throw IllegalArgumentException("Reduce of empty array")
                var accumulator = if (args.size >= 2) args[1] else target.first()
                val start = if (args.size >= 2) 0 else 1
                for (i in start until target.size) {
                    accumulator = call(callback, listOf(accumulator, target[i], i, target))
                }
                accumulator
            }
            else -> key.toIntOrNull()?.let { target.getOrNull(it) } ?: Undefined
        }
        is String -> if (key == "length") target.length else key.toIntOrNull()?.let { target.getOrNull(it)?.toString() } ?: Undefined
        is NativeFunction -> target.properties[key] ?: Undefined
        else -> Undefined
    }

    private fun binary(operator: String, left: Any?, right: Any?): Any? = when (operator) {
        "+" -> plus(left, right)
        "-" -> toNumber(left) - toNumber(right)
        "*" -> toNumber(left) * toNumber(right)
        "/" -> toNumber(left) / toNumber(right)
        "%" -> toNumber(left) % toNumber(right)
        "**" -> toNumber(left).pow(toNumber(right))
        "<" -> toNumber(left) < toNumber(right)
        "<=" -> toNumber(left) <= toNumber(right)
        ">" -> toNumber(left) > toNumber(right)
        ">=" -> toNumber(left) >= toNumber(right)
        "==", "===" -> equals(left, right, operator == "===")
        "!=", "!==" -> !equals(left, right, operator == "!==")
        else -> throw IllegalArgumentException("Unsupported binary $operator")
    }

    private fun plus(left: Any?, right: Any?): Any? =
        if (left is String || right is String || isAttrItem(left) || isAttrItem(right)) {
            string(left) + string(right)
        } else toNumber(left) + toNumber(right)

    private fun isAttrItem(value: Any?): Boolean = value is Map<*, *> &&
        value.containsKey("base") && value.containsKey("plus") && value.containsKey("pct")

    private fun attrNumber(value: Map<*, *>): Double =
        number(value["base"]) + number(value["plus"]) + number(value["base"]) * number(value["pct"]) / 100

    private fun equals(left: Any?, right: Any?, strict: Boolean): Boolean {
        if (left is Number && right is Number) return left.toDouble() == right.toDouble()
        if (strict) return left == right
        if ((left == null || left == Undefined) && (right == null || right == Undefined)) return true
        if (left is Boolean || right is Boolean) return toNumber(left) == toNumber(right)
        if (left is Number && right is String || left is String && right is Number) return toNumber(left) == toNumber(right)
        return left == right
    }

    fun truthy(value: Any?): Boolean = when (value) {
        null, Undefined -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0 && !value.toDouble().isNaN()
        is String -> value.isNotEmpty()
        else -> true
    }

    fun number(value: Any?): Double = when (value) {
        null -> 0.0
        Undefined -> 0.0
        is Number -> value.toDouble()
        is Boolean -> if (value) 1.0 else 0.0
        is String -> value.toDoubleOrNull() ?: 0.0
        is Map<*, *> -> if (isAttrItem(value)) attrNumber(value) else 0.0
        else -> 0.0
    }

    private fun toNumber(value: Any?): Double = when (value) {
        null -> 0.0
        Undefined -> Double.NaN
        is Number -> value.toDouble()
        is Boolean -> if (value) 1.0 else 0.0
        is String -> value.trim().toDoubleOrNull() ?: Double.NaN
        is Map<*, *> -> if (isAttrItem(value)) attrNumber(value) else Double.NaN
        else -> Double.NaN
    }

    fun string(value: Any?): String = when (value) {
        null -> "null"
        Undefined -> "undefined"
        is String -> value
        is Boolean -> value.toString()
        is Double -> {
            if (value.isNaN()) "NaN"
            else if (value == kotlin.math.floor(value) && value.isFinite()) value.toLong().toString()
            else value.toString()
        }
        is Float -> string(value.toDouble())
        is Int, is Long -> value.toString()
        is Number -> string(value.toDouble())
        is Map<*, *> -> if (isAttrItem(value)) string(attrNumber(value)) else value.toString()
        else -> value.toString()
    }

    private fun trimNumber(value: Double, digits: Int): String {
        val text = String.format(Locale.US, "%.${digits.coerceIn(0, 6)}f", value)
        return if ('.' in text) text.trimEnd('0').trimEnd('.').ifBlank { "0" } else text
    }

    fun toJs(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> {
            val result = LinkedHashMap<String, Any?>()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = toJs(value.opt(key))
            }
            result
        }
        is JSONArray -> (0 until value.length()).map { toJs(value.opt(it)) }
        else -> value
    }

    private fun jsonValue(value: Any?): Any? = if (value == JSONObject.NULL) null else value
}
