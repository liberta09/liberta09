package com.liberta09

import android.util.Base64
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

// ─── Primitives shared with the static fallback path ────────────────────────

const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

fun decodeBase64Binary(value: String): String {
    val cleaned = value.replace(Regex("[^A-Za-z0-9+/=]"), "")
    return try {
        val bytes = Base64.decode(cleaned, Base64.DEFAULT)
        bytes.joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
    } catch (e: IllegalArgumentException) {
        bail("invalid base64")
    }
}

fun encodeBase64Binary(value: String): String {
    val bytes = ByteArray(value.length) { i -> value[i].code.toByte() }
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

fun caesarShift(value: String, shift: Int): String {
    val normalized = ((shift % 26) + 26) % 26
    return value.replace(Regex("[a-zA-Z]")) { match ->
        val code = match.value[0].code
        val base = if (code <= 90) 65 else 97
        (((code - base + normalized) % 26) + base).toChar().toString()
    }
}

fun reverseString(value: String): String {
    return value.reversed()
}

// ─── Budgets ───────────────────────────────────────────────────────────────

const val MAX_STEPS = 5_000_000
const val MAX_STRING_LENGTH = 1_000_000
const val MAX_ARRAY_LENGTH = 1_000_000

class UnsupportedScript(message: String) : RuntimeException(message)

fun bail(reason: String): Nothing {
    throw UnsupportedScript(reason)
}

// ─── Tokenizer ─────────────────────────────────────────────────────────────

enum class TokenKind { Num, Str, RegexKind, Ident, Punct, Eof }

data class Token(
    val kind: TokenKind,
    val text: String,
    val flags: String? = null
)

val PUNCTUATORS = listOf(
    ">>>=", "===", "!==", ">>>", "<<=", ">>=", "&&=", "||=",
    "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
    "<<", ">>", "(", ")", "[", "]", "{", "}", ";", ",", ".", "?", ":", "+", "-", "*", "/", "%", "&", "|", "^", "~", "!", "<", ">", "="
)

fun regexCanFollow(previous: Token?): Boolean {
    if (previous == null) return true
    if (previous.kind == TokenKind.Num || previous.kind == TokenKind.Str || previous.kind == TokenKind.RegexKind) return false
    if (previous.kind == TokenKind.Ident) {
        return previous.text in listOf("return", "typeof", "in", "of", "new", "delete", "void", "case", "do", "else")
    }
    return previous.text !in listOf(")", "]", "}", "++", "--")
}

fun tokenize(source: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var index = 0

    while (index < source.length) {
        val char = source[index]

        if (char.isWhitespace()) {
            index++
            continue
        }

        if (char == '/' && source.getOrNull(index + 1) == '/') {
            while (index < source.length && source[index] != '\n') index++
            continue
        }
        if (char == '/' && source.getOrNull(index + 1) == '*') {
            val end = source.indexOf("*/", index + 2)
            index = if (end == -1) source.length else end + 2
            continue
        }

        if (char.isDigit() || (char == '.' && source.getOrNull(index + 1)?.isDigit() == true)) {
            var text = ""
            if (char == '0' && source.getOrNull(index + 1)?.let { it == 'x' || it == 'X' } == true) {
                text = source.substring(index, index + 2)
                index += 2
                while (index < source.length && source[index].let { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    text += source[index]
                    index++
                }
            } else {
                while (index < source.length && (source[index].isDigit() || source[index] == '.')) {
                    text += source[index]
                    index++
                }
                val eChar = source.getOrNull(index)
                if (eChar == 'e' || eChar == 'E') {
                    val signChar = source.getOrNull(index + 1)
                    if (signChar != null && (signChar == '+' || signChar == '-' || signChar.isDigit())) {
                        text += source[index]
                        index++
                        if (source[index] == '+' || source[index] == '-') {
                            text += source[index]
                            index++
                        }
                        while (index < source.length && source[index].isDigit()) {
                            text += source[index]
                            index++
                        }
                    }
                }
            }
            tokens.add(Token(TokenKind.Num, text))
            continue
        }

        if (char.isLetter() || char == '_' || char == '$') {
            var text = ""
            while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '$')) {
                text += source[index]
                index++
            }
            tokens.add(Token(TokenKind.Ident, text))
            continue
        }

        if (char == '\'' || char == '"' || char == '`') {
            val quote = char
            var text = ""
            index++
            while (index < source.length && source[index] != quote) {
                if (source[index] == '\\') {
                    val escape = source.getOrNull(index + 1)?.toString() ?: ""
                    text += when (escape) {
                        "n" -> "\n"
                        "t" -> "\t"
                        "r" -> "\r"
                        "0" -> "\u0000"
                        else -> escape
                    }
                    index += 2
                    continue
                }
                text += source[index]
                index++
            }
            if (index >= source.length) bail("unterminated string literal")
            index++
            tokens.add(Token(TokenKind.Str, text))
            continue
        }

        if (char == '/' && regexCanFollow(tokens.lastOrNull())) {
            var pattern = ""
            var inClass = false
            index++
            while (index < source.length) {
                val current = source[index]
                if (current == '\\') {
                    pattern += current.toString() + (source.getOrNull(index + 1)?.toString() ?: "")
                    index += 2
                    continue
                }
                if (current == '[') inClass = true
                else if (current == ']') inClass = false
                else if (current == '/' && !inClass) break
                else if (current == '\n') bail("unterminated regex literal")
                pattern += current
                index++
            }
            if (index >= source.length) bail("unterminated regex literal")
            index++
            var flags = ""
            while (index < source.length && source[index] in 'a'..'z') {
                flags += source[index]
                index++
            }
            tokens.add(Token(TokenKind.RegexKind, pattern, flags))
            continue
        }

        val punctuator = PUNCTUATORS.find { source.startsWith(it, index) }
        if (punctuator == null) bail("unexpected character: $char")
        tokens.add(Token(TokenKind.Punct, punctuator))
        index += punctuator.length
    }

    tokens.add(Token(TokenKind.Eof, ""))
    return tokens
}

// ─── AST ───────────────────────────────────────────────────────────────────

sealed class Expr {
    data class Num(val value: Double) : Expr()
    data class Str(val value: String) : Expr()
    data class RegexExpr(val pattern: String, val flags: String) : Expr()
    data class Ident(val name: String) : Expr()
    data class ArrayLit(val items: List<Expr>) : Expr()
    data class Func(val params: List<String>, val body: List<Stmt>) : Expr()
    data class Member(val obj: Expr, val property: String) : Expr()
    data class Index(val obj: Expr, val index: Expr) : Expr()
    data class Call(val callee: Expr, val args: List<Expr>) : Expr()
    data class Unary(val op: String, val argument: Expr) : Expr()
    data class Update(val op: String, val prefix: Boolean, val argument: Expr) : Expr()
    data class Binary(val op: String, val left: Expr, val right: Expr) : Expr()
    data class Logical(val op: String, val left: Expr, val right: Expr) : Expr()
    data class Conditional(val test: Expr, val consequent: Expr, val alternate: Expr) : Expr()
    data class Assign(val op: String, val target: Expr, val value: Expr) : Expr()
}

data class Declarator(val name: String, val init: Expr?)

sealed class Stmt {
    data class VarDecl(val declarations: List<Declarator>) : Stmt()
    data class ExprStmt(val expression: Expr) : Stmt()
    data class If(val test: Expr, val consequent: Stmt, val alternate: Stmt?) : Stmt()
    data class For(val init: Stmt?, val test: Expr?, val update: Expr?, val body: Stmt) : Stmt()
    data class While(val test: Expr, val body: Stmt) : Stmt()
    data class Block(val body: List<Stmt>) : Stmt()
    data class Return(val argument: Expr?) : Stmt()
    object Break : Stmt()
    object Continue : Stmt()
    object Empty : Stmt()
}

data class FunctionDecl(val name: String, val params: List<String>, val body: List<Stmt>)

// ─── Parser ────────────────────────────────────────────────────────────────

class Parser(private val tokens: List<Token>) {
    private var position = 0

    private val ASSIGN_OPERATORS = setOf("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=")

    val BINARY_PRECEDENCE = mapOf(
        "||" to 1, "&&" to 2, "|" to 3, "^" to 4, "&" to 5,
        "==" to 6, "!=" to 6, "===" to 6, "!==" to 6,
        "<" to 7, ">" to 7, "<=" to 7, ">=" to 7,
        "<<" to 8, ">>" to 8, ">>>" to 8,
        "+" to 9, "-" to 9, "*" to 10, "/" to 10, "%" to 10
    )

    private fun peek(offset: Int = 0): Token {
        return tokens.getOrNull(position + offset) ?: Token(TokenKind.Eof, "")
    }

    private fun next(): Token {
        val token = peek()
        if (token.kind != TokenKind.Eof) position++
        return token
    }

    private fun at(text: String): Boolean {
        val token = peek()
        return token.text == text && (token.kind == TokenKind.Punct || token.kind == TokenKind.Ident)
    }

    private fun eat(text: String): Boolean {
        if (at(text)) {
            position++
            return true
        }
        return false
    }

    private fun expect(text: String) {
        if (!eat(text)) bail("expected \"$text\" but found \"${peek().text}\"")
    }

    private fun semicolon() {
        eat(";")
    }

    fun parseFunctionDeclaration(): FunctionDecl {
        while (peek().kind != TokenKind.Eof) {
            if (peek().kind == TokenKind.Ident && peek().text == "function") {
                position++
                val nameToken = next()
                if (nameToken.kind != TokenKind.Ident) bail("expected function name")
                val params = parseParams()
                val body = parseBlock().body
                return FunctionDecl(nameToken.text, params, body)
            }
            position++
        }
        bail("no function declaration found")
    }

    private fun parseParams(): List<String> {
        expect("(")
        val params = mutableListOf<String>()
        if (eat(")")) return params
        while (true) {
            val token = next()
            if (token.kind != TokenKind.Ident) bail("expected parameter name")
            params.add(token.text)
            if (eat(")")) return params
            expect(",")
        }
    }

    private fun parseBlock(): Stmt.Block {
        expect("{")
        val body = mutableListOf<Stmt>()
        while (!at("}")) {
            if (peek().kind == TokenKind.Eof) bail("unterminated block")
            body.add(parseStatement())
        }
        expect("}")
        return Stmt.Block(body)
    }

    private fun parseStatement(): Stmt {
        val token = peek()
        if (token.text == "{") return parseBlock()
        if (token.text == ";") {
            position++
            return Stmt.Empty
        }

        if (token.kind == TokenKind.Ident) {
            when (token.text) {
                "var", "let", "const" -> {
                    position++
                    val declarations = parseDeclarators()
                    semicolon()
                    return Stmt.VarDecl(declarations)
                }
                "if" -> {
                    position++
                    expect("(")
                    val test = parseExpression()
                    expect(")")
                    val consequent = parseStatement()
                    val alternate = if (eat("else")) parseStatement() else null
                    return Stmt.If(test, consequent, alternate)
                }
                "for" -> return parseFor()
                "while" -> {
                    position++
                    expect("(")
                    val test = parseExpression()
                    expect(")")
                    return Stmt.While(test, parseStatement())
                }
                "return" -> {
                    position++
                    val argument = if (at(";") || at("}")) null else parseExpression()
                    semicolon()
                    return Stmt.Return(argument)
                }
                "break" -> {
                    position++
                    semicolon()
                    return Stmt.Break
                }
                "continue" -> {
                    position++
                    semicolon()
                    return Stmt.Continue
                }
                "function" -> bail("nested function declarations are not supported")
            }
        }

        val expression = parseExpression()
        semicolon()
        return Stmt.ExprStmt(expression)
    }

    private fun parseDeclarators(): List<Declarator> {
        val declarations = mutableListOf<Declarator>()
        while (true) {
            val nameToken = next()
            if (nameToken.kind != TokenKind.Ident) bail("expected declarator name")
            val init = if (eat("=")) parseAssignment() else null
            declarations.add(Declarator(nameToken.text, init))
            if (!eat(",")) return declarations
        }
    }

    private fun parseFor(): Stmt {
        expect("for")
        expect("(")

        var init: Stmt? = null
        if (!at(";")) {
            if (at("var") || at("let") || at("const")) {
                position++
                init = Stmt.VarDecl(parseDeclarators())
            } else {
                init = Stmt.ExprStmt(parseExpression())
            }
        }
        expect(";")

        val test = if (at(";")) null else parseExpression()
        expect(";")

        val update = if (at(")")) null else parseExpression()
        expect(")")

        return Stmt.For(init, test, update, parseStatement())
    }

    private fun parseExpression(): Expr {
        return parseAssignment()
    }

    private fun parseAssignment(): Expr {
        val left = parseConditional()
        val operator = peek().text
        if (peek().kind == TokenKind.Punct && operator in ASSIGN_OPERATORS) {
            if (left !is Expr.Ident && left !is Expr.Index && left !is Expr.Member) {
                bail("invalid assignment target")
            }
            position++
            return Expr.Assign(operator, left, parseAssignment())
        }
        return left
    }

    private fun parseConditional(): Expr {
        val test = parseBinary(0)
        if (!eat("?")) return test
        val consequent = parseAssignment()
        expect(":")
        return Expr.Conditional(test, consequent, parseAssignment())
    }

    private fun parseBinary(minPrecedence: Int): Expr {
        var left = parseUnary()
        while (true) {
            val token = peek()
            if (token.kind != TokenKind.Punct) return left
            val precedence = BINARY_PRECEDENCE[token.text]
            if (precedence == null || precedence < minPrecedence) return left
            position++
            val right = parseBinary(precedence + 1)
            left = if (token.text == "&&" || token.text == "||") {
                Expr.Logical(token.text, left, right)
            } else {
                Expr.Binary(token.text, left, right)
            }
        }
    }

    private fun parseUnary(): Expr {
        val token = peek()
        if (token.kind == TokenKind.Punct && token.text in listOf("-", "+", "~", "!")) {
            position++
            return Expr.Unary(token.text, parseUnary())
        }
        if (token.kind == TokenKind.Punct && (token.text == "++" || token.text == "--")) {
            position++
            return Expr.Update(token.text, true, parseUnary())
        }
        if (token.kind == TokenKind.Ident && token.text == "typeof") {
            bail("typeof is not supported")
        }
        return parsePostfix()
    }

    private fun parsePostfix(): Expr {
        var expression = parsePrimary()
        while (true) {
            if (eat(".")) {
                val member = next()
                if (member.kind != TokenKind.Ident) bail("expected property name")
                expression = Expr.Member(expression, member.text)
                continue
            }
            if (eat("[")) {
                val index = parseExpression()
                expect("]")
                expression = Expr.Index(expression, index)
                continue
            }
            if (at("(")) {
                expression = Expr.Call(expression, parseArguments())
                continue
            }
            val token = peek()
            if (token.kind == TokenKind.Punct && (token.text == "++" || token.text == "--")) {
                position++
                expression = Expr.Update(token.text, false, expression)
                continue
            }
            return expression
        }
    }

    private fun parseArguments(): List<Expr> {
        expect("(")
        val args = mutableListOf<Expr>()
        if (eat(")")) return args
        while (true) {
            args.add(parseAssignment())
            if (eat(")")) return args
            expect(",")
        }
    }

    private fun parsePrimary(): Expr {
        val token = peek()
        if (token.kind == TokenKind.Num) {
            position++
            val value = token.text.toDoubleOrNull()
            if (value == null || !value.isFinite()) bail("bad number literal: ${token.text}")
            return Expr.Num(value)
        }
        if (token.kind == TokenKind.Str) {
            position++
            return Expr.Str(token.text)
        }
        if (token.kind == TokenKind.RegexKind) {
            position++
            return Expr.RegexExpr(token.text, token.flags ?: "")
        }
        if (token.text == "(") {
            position++
            val expression = parseExpression()
            expect(")")
            return expression
        }
        if (token.text == "[") {
            position++
            val items = mutableListOf<Expr>()
            if (eat("]")) return Expr.ArrayLit(items)
            while (true) {
                items.add(parseAssignment())
                if (eat("]")) return Expr.ArrayLit(items)
                expect(",")
            }
        }
        if (token.kind == TokenKind.Ident) {
            if (token.text == "function") {
                position++
                if (peek().kind == TokenKind.Ident && !at("(")) position++
                val params = parseParams()
                val body = parseBlock().body
                return Expr.Func(params, body)
            }
            position++
            return Expr.Ident(token.text)
        }
        bail("unexpected token: ${if (token.text.isNotEmpty()) token.text else "<eof>"}")
    }
}

// ─── Runtime values ────────────────────────────────────────────────────────

data class Callable(val params: List<String>, val body: List<Stmt>, val scope: Scope)
data class Native(val name: String)
data class RegexValue(val pattern: String, val flags: String)
data class Namespace(val name: String)

class Scope(private val parent: Scope? = null) {
    private val values = mutableMapOf<String, Any?>()

    fun declare(name: String, value: Any?) {
        values[name] = value
    }

    fun has(name: String): Boolean {
        return values.containsKey(name) || (parent?.has(name) == true)
    }

    fun get(name: String): Any? {
        if (values.containsKey(name)) return values[name]
        if (parent != null) return parent.get(name)
        bail("unknown identifier: $name")
    }

    fun set(name: String, value: Any?) {
        if (values.containsKey(name)) {
            values[name] = value
            return
        }
        if (parent?.has(name) == true) {
            parent.set(name, value)
            return
        }
        values[name] = value
    }
}

class ReturnSignal(val value: Any?) : RuntimeException()
class BreakSignal : RuntimeException()
class ContinueSignal : RuntimeException()

fun compileRegex(pattern: String, flags: String): Regex {
    if (pattern.length > 200) bail("regex pattern too long")
    if (!flags.matches(Regex("^[gimsuy]*$"))) bail("unsupported regex flags: $flags")

    val skeleton = pattern.replace(Regex("\\\\."), "").replace(Regex("\\[(?:\\\\.|[^\\]\\\\])*\\]"), "")
    if (Regex("[*+?{}()|]").containsMatchIn(skeleton)) bail("unsupported regex construct: /$pattern/")

    val regexOptions = mutableSetOf<RegexOption>()
    if ('i' in flags) regexOptions.add(RegexOption.IGNORE_CASE)
    if ('m' in flags) regexOptions.add(RegexOption.MULTILINE)
    
    return try {
        Regex(pattern, regexOptions)
    } catch (e: Exception) {
        bail("invalid regex: /$pattern/")
    }
}

// ─── Interpreter ───────────────────────────────────────────────────────────

class Interpreter {
    private var steps = 0

    private fun tick() {
        steps++
        if (steps > MAX_STEPS) bail("step budget exceeded")
    }

    private fun checkString(value: String): String {
        if (value.length > MAX_STRING_LENGTH) bail("string budget exceeded")
        return value
    }

    fun execBlock(statements: List<Stmt>, scope: Scope) {
        for (statement in statements) exec(statement, scope)
    }

    private fun exec(statement: Stmt, scope: Scope) {
        tick()
        when (statement) {
            is Stmt.Empty -> return
            is Stmt.VarDecl -> {
                for (declaration in statement.declarations) {
                    scope.declare(declaration.name, if (declaration.init != null) evaluate(declaration.init, scope) else null)
                }
            }
            is Stmt.ExprStmt -> {
                evaluate(statement.expression, scope)
            }
            is Stmt.Block -> {
                execBlock(statement.body, Scope(scope))
            }
            is Stmt.If -> {
                if (truthy(evaluate(statement.test, scope))) {
                    exec(statement.consequent, scope)
                } else if (statement.alternate != null) {
                    exec(statement.alternate, scope)
                }
            }
            is Stmt.For -> {
                val loopScope = Scope(scope)
                if (statement.init != null) exec(statement.init, loopScope)
                while (true) {
                    tick()
                    if (statement.test != null && !truthy(evaluate(statement.test, loopScope))) break
                    try {
                        exec(statement.body, loopScope)
                    } catch (e: BreakSignal) {
                        break
                    } catch (e: ContinueSignal) {
                        // ignore and proceed to update
                    }
                    if (statement.update != null) evaluate(statement.update, loopScope)
                }
            }
            is Stmt.While -> {
                while (true) {
                    tick()
                    if (!truthy(evaluate(statement.test, scope))) break
                    try {
                        exec(statement.body, scope)
                    } catch (e: BreakSignal) {
                        break
                    } catch (e: ContinueSignal) {
                        // ignore and loop again
                    }
                }
            }
            is Stmt.Return -> {
                throw ReturnSignal(if (statement.argument != null) evaluate(statement.argument, scope) else null)
            }
            is Stmt.Break -> throw BreakSignal()
            is Stmt.Continue -> throw ContinueSignal()
        }
    }

    fun evaluate(expression: Expr, scope: Scope): Any? {
        tick()
        when (expression) {
            is Expr.Num -> return expression.value
            is Expr.Str -> return expression.value
            is Expr.RegexExpr -> return RegexValue(expression.pattern, expression.flags)
            is Expr.Ident -> return scope.get(expression.name)
            is Expr.ArrayLit -> return expression.items.map { evaluate(it, scope) }.toMutableList()
            is Expr.Func -> return Callable(expression.params, expression.body, scope)
            is Expr.Member -> return readProperty(evaluate(expression.obj, scope), expression.property)
            is Expr.Index -> {
                val obj = evaluate(expression.obj, scope)
                val index = evaluate(expression.index, scope)
                if (obj is List<*>) {
                    val indexInt = toNumber(index).toInt()
                    return if (indexInt in 0 until obj.size) obj[indexInt] else null
                }
                if (obj is String) {
                    val indexInt = toNumber(index).toInt()
                    return if (indexInt in 0 until obj.length) obj[indexInt].toString() else ""
                }
                bail("index on unsupported value")
            }
            is Expr.Call -> return evaluateCall(expression, scope)
            is Expr.Unary -> {
                val value = evaluate(expression.argument, scope)
                when (expression.op) {
                    "-" -> return -toNumber(value)
                    "+" -> return toNumber(value)
                    "~" -> return toInt32(toNumber(value)).inv().toDouble()
                    "!" -> return !truthy(value)
                }
                bail("unsupported unary operator: ${expression.op}")
            }
            is Expr.Update -> {
                val old = toNumber(evaluate(expression.argument, scope))
                val updated = if (expression.op == "++") old + 1.0 else old - 1.0
                assignTo(expression.argument, updated, scope)
                return if (expression.prefix) updated else old
            }
            is Expr.Logical -> {
                val left = evaluate(expression.left, scope)
                if (expression.op == "&&") return if (truthy(left)) evaluate(expression.right, scope) else left
                return if (truthy(left)) left else evaluate(expression.right, scope)
            }
            is Expr.Conditional -> {
                return if (truthy(evaluate(expression.test, scope))) {
                    evaluate(expression.consequent, scope)
                } else {
                    evaluate(expression.alternate, scope)
                }
            }
            is Expr.Binary -> return evaluateBinary(expression.op, evaluate(expression.left, scope), evaluate(expression.right, scope))
            is Expr.Assign -> {
                if (expression.op == "=") {
                    val value = evaluate(expression.value, scope)
                    assignTo(expression.target, value, scope)
                    return value
                }
                val current = evaluate(expression.target, scope)
                val operand = evaluate(expression.value, scope)
                val value = evaluateBinary(expression.op.dropLast(1), current, operand)
                assignTo(expression.target, value, scope)
                return value
            }
        }
    }

    private fun evaluateBinary(op: String, left: Any?, right: Any?): Any? {
        when (op) {
            "+" -> {
                if (left is String || right is String) {
                    return checkString(stringify(left) + stringify(right))
                }
                return toNumber(left) + toNumber(right)
            }
            "-" -> return toNumber(left) - toNumber(right)
            "*" -> return toNumber(left) * toNumber(right)
            "/" -> {
                val divisor = toNumber(right)
                if (divisor == 0.0) bail("division by zero")
                return toNumber(left) / divisor
            }
            "%" -> {
                val divisor = toNumber(right)
                if (divisor == 0.0) bail("modulo by zero")
                return toNumber(left) % divisor
            }
            "&" -> return (toInt32(toNumber(left)) and toInt32(toNumber(right))).toDouble()
            "|" -> return (toInt32(toNumber(left)) or toInt32(toNumber(right))).toDouble()
            "^" -> return (toInt32(toNumber(left)) xor toInt32(toNumber(right))).toDouble()
            "<<" -> return (toInt32(toNumber(left)) shl toInt32(toNumber(right))).toDouble()
            ">>" -> return (toInt32(toNumber(left)) shr toInt32(toNumber(right))).toDouble()
            ">>>" -> return ((toInt32(toNumber(left)) ushr toInt32(toNumber(right))).toLong() and 0xFFFFFFFFL).toDouble()
            "<" -> return compare(left, right) < 0
            ">" -> return compare(left, right) > 0
            "<=" -> return compare(left, right) <= 0
            ">=" -> return compare(left, right) >= 0
            "==", "===" -> return looseEqual(left, right)
            "!=", "!==" -> return !looseEqual(left, right)
        }
        bail("unsupported operator: $op")
    }

    private fun assignTo(target: Expr, value: Any?, scope: Scope) {
        if (target is Expr.Ident) {
            scope.set(target.name, value)
            return
        }
        if (target is Expr.Index) {
            val obj = evaluate(target.obj, scope)
            if (obj !is MutableList<*>) bail("element assignment on non-array")
            @Suppress("UNCHECKED_CAST")
            obj as MutableList<Any?>
            val indexNum = toNumber(evaluate(target.index, scope))
            val indexInt = indexNum.toInt()
            if (indexInt.toDouble() != indexNum || indexInt < 0 || indexInt >= MAX_ARRAY_LENGTH) {
                bail("array index out of range")
            }
            while (obj.size <= indexInt) obj.add(null)
            obj[indexInt] = value
            return
        }
        bail("unsupported assignment target")
    }

    private fun readProperty(obj: Any?, property: String): Any? {
        if (property == "length") {
            if (obj is String) return obj.length.toDouble()
            if (obj is List<*>) return obj.size.toDouble()
            bail(".length on unsupported value")
        }
        if (isNamespace(obj)) return Native("${(obj as Namespace).name}.$property")
        bail("unsupported property read: .$property")
    }

    private fun evaluateCall(expression: Expr.Call, scope: Scope): Any? {
        val callee = expression.callee

        if (callee is Expr.Member) {
            val receiver = evaluate(callee.obj, scope)
            if (isNamespace(receiver)) {
                val args = expression.args.map { evaluate(it, scope) }
                return callNamespace((receiver as Namespace).name, callee.property, args)
            }
            val args = expression.args.map { evaluate(it, scope) }
            return callMethod(receiver, callee.property, args, scope)
        }

        if (callee is Expr.Ident) {
            val args = expression.args.map { evaluate(it, scope) }
            if (!scope.has(callee.name)) bail("unsupported call: ${callee.name}()")
            val target = scope.get(callee.name)
            if (isNative(target)) return callNative((target as Native).name, args)
            if (isClosure(target)) return callClosure(target as Callable, args)
            bail("not callable: ${callee.name}")
        }

        val target = evaluate(callee, scope)
        val args = expression.args.map { evaluate(it, scope) }
        if (isClosure(target)) return callClosure(target as Callable, args)
        bail("unsupported call target")
    }

    fun callClosure(target: Callable, args: List<Any?>): Any? {
        tick()
        val scope = Scope(target.scope)
        target.params.forEachIndexed { index, name ->
            scope.declare(name, args.getOrNull(index))
        }
        try {
            execBlock(target.body, scope)
        } catch (e: ReturnSignal) {
            return e.value
        }
        return null
    }

    private fun callNative(name: String, args: List<Any?>): Any? {
        when (name) {
            "atob" -> return checkString(decodeBase64Binary(stringify(args.getOrNull(0))))
            "btoa" -> return checkString(encodeBase64Binary(stringify(args.getOrNull(0))))
            "parseInt" -> {
                val s = stringify(args.getOrNull(0)).trimStart()
                val radixArg = args.getOrNull(1)
                val radix = if (radixArg == null) 10 else toNumber(radixArg).toInt()
                if (s.isEmpty()) return Double.NaN
                val isNegative = s.startsWith("-")
                val hasSign = isNegative || s.startsWith("+")
                val start = if (hasSign) 1 else 0
                var end = start
                fun isValid(c: Char): Boolean {
                    val lower = c.lowercaseChar()
                    return if (radix <= 10) lower in '0'..('0' + radix - 1)
                    else lower in '0'..'9' || lower in 'a'..('a' + radix - 11)
                }
                while (end < s.length && isValid(s[end])) end++
                if (end == start) return Double.NaN
                val value = s.substring(start, end).toLongOrNull(radix)?.toDouble() ?: Double.NaN
                return if (isNegative) -value else value
            }
            "Number" -> return toNumber(args.getOrNull(0))
            "String.fromCharCode" -> {
                return checkString(args.joinToString("") { (toNumber(it).toInt() and 0xFFFF).toChar().toString() })
            }
            "Math.floor" -> return floor(toNumber(args.getOrNull(0)))
            "Math.ceil" -> return ceil(toNumber(args.getOrNull(0)))
            "Math.round" -> return round(toNumber(args.getOrNull(0))).toDouble()
            "Math.abs" -> return abs(toNumber(args.getOrNull(0)))
            "Math.max" -> return if (args.isEmpty()) Double.NEGATIVE_INFINITY else args.maxOf { toNumber(it) }
            "Math.min" -> return if (args.isEmpty()) Double.POSITIVE_INFINITY else args.minOf { toNumber(it) }
        }
        bail("unsupported call: $name()")
    }

    private fun callNamespace(namespace: String, property: String, args: List<Any?>): Any? {
        return callNative("$namespace.$property", args)
    }

    private fun callMethod(receiver: Any?, method: String, args: List<Any?>, scope: Scope): Any? {
        if (receiver is String) return callStringMethod(receiver, method, args, scope)
        if (receiver is List<*>) {
            @Suppress("UNCHECKED_CAST")
            val mutableReceiver = receiver as MutableList<Any?>
            return callArrayMethod(mutableReceiver, method, args)
        }
        if (receiver is Double) {
            if (method == "toString") {
                val radix = if (args.isEmpty() || args[0] == null) 10 else toNumber(args[0]).toInt()
                return receiver.toLong().toString(radix)
            }
        }
        bail("unsupported method: .$method()")
    }

    private fun callStringMethod(receiver: String, method: String, args: List<Any?>, scope: Scope): Any? {
        when (method) {
            "charCodeAt" -> {
                val idx = if (args.isEmpty() || args[0] == null) 0 else toNumber(args[0]).toInt()
                return if (idx in receiver.indices) receiver[idx].code.toDouble() else Double.NaN
            }
            "charAt" -> {
                val idx = if (args.isEmpty() || args[0] == null) 0 else toNumber(args[0]).toInt()
                return if (idx in receiver.indices) receiver[idx].toString() else ""
            }
            "split" -> {
                val sep = if (args.isEmpty() || args[0] == null) null else stringify(args[0])
                if (sep == "") {
                    val parts = receiver.map { it.toString() }.toMutableList()
                    if (parts.size > MAX_ARRAY_LENGTH) bail("array budget exceeded")
                    return parts
                }
                val parts = if (sep == null) mutableListOf(receiver) else receiver.split(sep).toMutableList()
                if (parts.size > MAX_ARRAY_LENGTH) bail("array budget exceeded")
                return parts
            }
            "slice" -> {
                val start = if (args.isEmpty() || args[0] == null) 0 else toNumber(args[0]).toInt()
                val end = if (args.size < 2 || args[1] == null) receiver.length else toNumber(args[1]).toInt()
                val s = if (start < 0) maxOf(receiver.length + start, 0) else minOf(start, receiver.length)
                val e = if (end < 0) maxOf(receiver.length + end, 0) else minOf(end, receiver.length)
                return if (s <= e) receiver.substring(s, e) else ""
            }
            "substring" -> {
                val start = if (args.isEmpty() || args[0] == null) 0 else toNumber(args[0]).toInt()
                val end = if (args.size < 2 || args[1] == null) receiver.length else toNumber(args[1]).toInt()
                val s = minOf(maxOf(0, start), receiver.length)
                val e = minOf(maxOf(0, end), receiver.length)
                val actualStart = minOf(s, e)
                val actualEnd = maxOf(s, e)
                return receiver.substring(actualStart, actualEnd)
            }
            "substr" -> {
                var start = if (args.isEmpty() || args[0] == null) 0 else toNumber(args[0]).toInt()
                var len = if (args.size < 2 || args[1] == null) receiver.length else toNumber(args[1]).toInt()
                start = if (start < 0) maxOf(receiver.length + start, 0) else minOf(start, receiver.length)
                len = maxOf(0, minOf(len, receiver.length - start))
                return receiver.substring(start, start + len)
            }
            "indexOf" -> return receiver.indexOf(if (args.isNotEmpty()) stringify(args[0]) else "undefined").toDouble()
            "lastIndexOf" -> return receiver.lastIndexOf(if (args.isNotEmpty()) stringify(args[0]) else "undefined").toDouble()
            "toLowerCase" -> return receiver.lowercase()
            "toUpperCase" -> return receiver.uppercase()
            "trim" -> return receiver.trim()
            "concat" -> return checkString(receiver + args.joinToString("") { stringify(it) })
            "toString" -> return receiver
            "join" -> bail("join on a string")
            "replace" -> return checkString(stringReplace(receiver, args, scope))
        }
        bail("unsupported string method: .$method()")
    }

    private fun stringReplace(receiver: String, args: List<Any?>, scope: Scope): String {
        val pattern = args.getOrNull(0)
        val replacement = args.getOrNull(1)

        fun replaceFn(matched: String): String {
            tick()
            if (isClosure(replacement)) return stringify(callClosure(replacement as Callable, listOf(matched)))
            if (replacement is String) {
                if (Regex("\\$[&`'0-9<]").containsMatchIn(replacement)) bail("replacement patterns are not supported")
                return replacement
            }
            bail("unsupported replacement argument")
        }

        if (isRegex(pattern)) {
            val regexVal = pattern as RegexValue
            val regex = compileRegex(regexVal.pattern, regexVal.flags)
            if ('g' in regexVal.flags) {
                return receiver.replace(regex) { replaceFn(it.value) }
            } else {
                val match = regex.find(receiver)
                if (match == null) return receiver
                return receiver.substring(0, match.range.first) + replaceFn(match.value) + receiver.substring(match.range.last + 1)
            }
        }
        if (pattern is String) {
            val index = receiver.indexOf(pattern)
            if (index == -1) return receiver
            return receiver.substring(0, index) + replaceFn(pattern) + receiver.substring(index + pattern.length)
        }
        bail("unsupported replace pattern")
    }

    private fun callArrayMethod(receiver: MutableList<Any?>, method: String, args: List<Any?>): Any? {
        when (method) {
            "join" -> {
                val sep = if (args.isNotEmpty() && args[0] != null) stringify(args[0]) else ","
                return checkString(receiver.joinToString(sep) { if (it == null) "" else stringify(it) })
            }
            "reverse" -> {
                receiver.reverse()
                return receiver
            }
            "push" -> {
                if (receiver.size + args.size > MAX_ARRAY_LENGTH) bail("array budget exceeded")
                receiver.addAll(args)
                return receiver.size.toDouble()
            }
            "pop" -> {
                return if (receiver.isEmpty()) null else receiver.removeAt(receiver.size - 1)
            }
            "shift" -> {
                return if (receiver.isEmpty()) null else receiver.removeAt(0)
            }
            "slice" -> {
                val startArg = if (args.isEmpty() || args[0] == null) 0.0 else toNumber(args[0])
                val start = startArg.toInt()
                val endArg = if (args.size < 2 || args[1] == null) receiver.size.toDouble() else toNumber(args[1])
                val end = endArg.toInt()
                val s = if (start < 0) maxOf(receiver.size + start, 0) else minOf(start, receiver.size)
                val e = if (end < 0) maxOf(receiver.size + end, 0) else minOf(end, receiver.size)
                return if (s <= e) receiver.subList(s, e).toMutableList() else mutableListOf<Any?>()
            }
            "indexOf" -> {
                val search = args.getOrNull(0)
                return receiver.indexOfFirst { looseEqual(it, search) }.toDouble()
            }
            "concat" -> {
                val result = receiver.toMutableList()
                for (arg in args) {
                    if (arg is List<*>) {
                        result.addAll(arg)
                    } else {
                        result.add(arg)
                    }
                }
                return result
            }
            "splice" -> {
                val startArg = if (args.isEmpty() || args[0] == null) 0.0 else toNumber(args[0])
                var start = startArg.toInt()
                start = if (start < 0) maxOf(receiver.size + start, 0) else minOf(start, receiver.size)
                val deleteCountArg = if (args.size < 2 || args[1] == null) (receiver.size - start).toDouble() else toNumber(args[1])
                var deleteCount = deleteCountArg.toInt()
                deleteCount = minOf(maxOf(deleteCount, 0), receiver.size - start)
                val inserted = args.drop(2)
                if (receiver.size - deleteCount + inserted.size > MAX_ARRAY_LENGTH) bail("array budget exceeded")

                val removed = receiver.subList(start, start + deleteCount).toMutableList()
                receiver.subList(start, start + deleteCount).clear()
                receiver.addAll(start, inserted)
                return removed
            }
        }
        bail("unsupported array method: .$method()")
    }
}

// ─── Value helpers ─────────────────────────────────────────────────────────

fun isClosure(value: Any?): Boolean = value is Callable
fun isNative(value: Any?): Boolean = value is Native
fun isRegex(value: Any?): Boolean = value is RegexValue
fun isNamespace(value: Any?): Boolean = value is Namespace

fun stringify(value: Any?): String {
    return when (value) {
        is String -> value
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        is Boolean -> value.toString()
        null -> "undefined"
        is List<*> -> value.joinToString(",") { stringify(it) }
        else -> bail("cannot stringify value")
    }
}

fun toNumber(value: Any?): Double {
    return when (value) {
        is Double -> value
        is Boolean -> if (value) 1.0 else 0.0
        is String -> {
            if (value.isEmpty()) 0.0
            else {
                val numeric = value.toDoubleOrNull()
                if (numeric == null || numeric.isNaN() || numeric.isInfinite()) bail("non-numeric operand")
                numeric
            }
        }
        else -> bail("non-numeric operand")
    }
}

fun truthy(value: Any?): Boolean {
    return when (value) {
        is Boolean -> value
        is Double -> value != 0.0 && !value.isNaN()
        is String -> value.isNotEmpty()
        null -> false
        else -> true
    }
}

fun toInt32(value: Double): Int {
    if (value.isNaN() || value.isInfinite()) return 0
    return value.toLong().toInt()
}

fun compare(left: Any?, right: Any?): Int {
    if (left is String && right is String) return left.compareTo(right)
    val a = toNumber(left)
    val b = toNumber(right)
    return a.compareTo(b)
}

fun looseEqual(left: Any?, right: Any?): Boolean {
    if (left == null || right == null) return left == right
    if (left::class == right::class) return left == right
    if (left is String && right is Double) return toNumber(left) == right
    if (left is Double && right is String) return left == toNumber(right)
    return left == right
}

// ─── Public entry point ────────────────────────────────────────────────────

/**
 * Execute a live decoder body against its parts array.
 *
 * `functionSource` must be the whole `function name(parts) { … }` text as it
 * appears on the embed page. Returns the decoded string, or null when the body
 * uses anything outside the supported subset — callers should then fall back
 * to the static schemes rather than treat null as "no stream".
 */
fun runRapidrameDecoder(functionSource: String, valueParts: List<String>): String? {
    return try {
        val parser = Parser(tokenize(functionSource))
        val declaration = parser.parseFunctionDeclaration()

        val globals = Scope(null)
        listOf("atob", "btoa", "parseInt", "Number").forEach { name ->
            globals.declare(name, Native(name))
        }
        globals.declare("String", Namespace("String"))
        globals.declare("Math", Namespace("Math"))
        globals.declare("undefined", null)

        val interpreter = Interpreter()
        val result = interpreter.callClosure(
            Callable(declaration.params, declaration.body, globals),
            listOf(valueParts.toMutableList<Any?>())
        )

        if (result is String) result else null
    } catch (e: Exception) {
        // UnsupportedScript, or any parse slip — fail closed.
        null
    }
}
