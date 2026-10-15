package dev.octoshrimpy.quik.model

object AutomationActionStepSerializer {

    fun toJson(steps: List<AutomationActionStep>): String {
        return steps.joinToString(prefix = "[", postfix = "]", separator = ",") { step ->
            buildString {
                append("{")
                append("\"action\":").append(escape(step.action)).append(",")
                append("\"forwardToPhone\":").append(escape(step.forwardToPhone)).append(",")
                append("\"forwardToUrl\":").append(escape(step.forwardToUrl)).append(",")
                append("\"appendSenderToForward\":").append(step.appendSenderToForward).append(",")
                append("\"replyTemplate\":").append(escape(step.replyTemplate))
                append("}")
            }
        }
    }

    private fun escape(s: String): String {
        val out = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (c.code in 0..0x1f) {
                        out.append(String.format("\\u%04x", c.code))
                    } else {
                        out.append(c)
                    }
                }
            }
        }
        out.append("\"")
        return out.toString()
    }

    fun fromJson(json: String?): List<AutomationActionStep> {
        if (json.isNullOrBlank()) return emptyList()
        val trimmed = json.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
        val content = trimmed.substring(1, trimmed.length - 1).trim()
        if (content.isEmpty()) return emptyList()

        val steps = mutableListOf<AutomationActionStep>()
        var inString = false
        var escape = false
        var braceDepth = 0
        val objBuilder = StringBuilder()

        for (ch in content) {
            if (escape) {
                objBuilder.append(ch)
                escape = false
                continue
            }
            if (ch == '\\') {
                objBuilder.append(ch)
                if (inString) escape = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                objBuilder.append(ch)
                continue
            }
            if (!inString) {
                if (ch == '{') {
                    braceDepth++
                    objBuilder.append(ch)
                    continue
                } else if (ch == '}') {
                    braceDepth--
                    objBuilder.append(ch)
                    if (braceDepth == 0) {
                        parseSingleObject(objBuilder.toString())?.let { steps.add(it) }
                        objBuilder.setLength(0)
                    }
                    continue
                } else if (ch == ',' && braceDepth == 0) {
                    continue
                }
            }
            if (braceDepth > 0) {
                objBuilder.append(ch)
            }
        }
        return steps
    }

    private fun parseSingleObject(objStr: String): AutomationActionStep? {
        val str = objStr.trim()
        if (!str.startsWith("{") || !str.endsWith("}")) return null

        var action = AutomationRule.ACTION_DELETE
        var forwardToPhone = ""
        var forwardToUrl = ""
        var appendSenderToForward = true
        var replyTemplate = ""

        val kvPattern = Regex(""""(\w+)"\s*:\s*("(?:[^"\\]|\\.)*"|true|false)""")
        for (match in kvPattern.findAll(str)) {
            val key = match.groupValues[1]
            val rawVal = match.groupValues[2]
            val unquoted = if (rawVal.startsWith("\"") && rawVal.endsWith("\"")) {
                unescape(rawVal.substring(1, rawVal.length - 1))
            } else {
                rawVal
            }
            when (key) {
                "action" -> action = unquoted
                "forwardToPhone" -> forwardToPhone = unquoted
                "forwardToUrl" -> forwardToUrl = unquoted
                "appendSenderToForward" -> appendSenderToForward = unquoted.toBoolean()
                "replyTemplate" -> replyTemplate = unquoted
            }
        }
        return AutomationActionStep(action, forwardToPhone, forwardToUrl, appendSenderToForward, replyTemplate)
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                when (next) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                            } else {
                                sb.append(c)
                                i++
                            }
                        } else {
                            sb.append(c)
                            i++
                        }
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
