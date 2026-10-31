package app.localdrop

import java.net.URI

object BrowserRules {
    fun address(text: String): String? {
        val raw=text.trim()
        if(raw.isEmpty() || raw.any { it.isWhitespace() }) return null
        val candidate=if("://" in raw) raw else "https://$raw"
        return runCatching {
            val uri=URI(candidate)
            if(uri.scheme.equals("https",true) && !uri.host.isNullOrBlank() &&
                uri.userInfo==null && (uri.host.contains('.') || uri.host=="localhost")) uri.toASCIIString() else null
        }.getOrNull()
    }
}
