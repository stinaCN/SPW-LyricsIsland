package com.liufe.spw.winisland.ws

internal object Json {

    fun stringify(value: Any?): String = StringBuilder(256).also { write(it, value) }.toString()

    fun parse(text: String): Any? = try {
        val reader = Reader(text)
        val value = reader.readValue()
        reader.skipWhitespace()
        if (reader.hasMore()) null else value
    } catch (_: Exception) {
        null
    }

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(value)
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Float -> if (value.isFinite()) sb.append(value) else sb.append("null")
            is Double -> if (value.isFinite()) sb.append(value) else sb.append("null")
            is Number -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, key.toString())
                    sb.append(':')
                    write(sb, item)
                }
                sb.append('}')
            }

            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }

            is Array<*> -> write(sb, value.toList())
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    private class Reader(private val text: String) {
        private var index = 0

        fun hasMore(): Boolean = index < text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (!hasMore()) throw IllegalArgumentException("内容意外结束")
            return when (val ch = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (ch == '-' || ch.isDigit()) readNumber() else throw IllegalArgumentException("非法字符 '$ch'")
            }
        }

        private fun readObject(): Map<String, Any?> {
            index++
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }

                    else -> throw IllegalArgumentException("对象缺少分隔符")
                }
            }
        }

        private fun readArray(): List<Any?> {
            index++
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }

                    else -> throw IllegalArgumentException("数组缺少分隔符")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (!hasMore()) throw IllegalArgumentException("字符串未闭合")
                when (val ch = text[index++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (!hasMore()) throw IllegalArgumentException("转义未闭合")
                        when (val esc = text[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw IllegalArgumentException("\\u 转义不完整")
                                sb.append(text.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }

                            else -> throw IllegalArgumentException("未知转义 '\\$esc'")
                        }
                    }

                    else -> sb.append(ch)
                }
            }
        }

        private fun readLiteral(token: String, value: Any?): Any? {
            if (!text.startsWith(token, index)) throw IllegalArgumentException("非法字面量")
            index += token.length
            return value
        }

        private fun readNumber(): Double {
            val start = index
            if (peek() == '-') index++
            while (hasMore()) {
                val ch = text[index]
                if (ch.isDigit() || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') index++ else break
            }
            return text.substring(start, index).toDoubleOrNull() ?: throw IllegalArgumentException("非法数字")
        }

        private fun peek(): Char = if (hasMore()) text[index] else throw IllegalArgumentException("内容意外结束")

        private fun expect(ch: Char) {
            if (peek() != ch) throw IllegalArgumentException("期望 '$ch'")
            index++
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asObjectMap(): Map<String, Any?>? = this as? Map<String, Any?>

internal fun Any?.asText(): String? = this as? String

internal fun Any?.asBool(): Boolean? = this as? Boolean

internal fun Any?.asNumber(): Double? = (this as? Number)?.toDouble()

internal fun Any?.asLong(): Long? = (this as? Number)?.toLong()
