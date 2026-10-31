package app.localdrop

import java.net.URI

object LinkRules {
    fun extract(text: String): String? = Regex("https?://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE)
        .findAll(text).map { it.value.trimEnd('.', ',', ')', ']', ';', '!') }
        .firstOrNull { runCatching { val u=URI(it); u.host != null && u.userInfo == null }.getOrDefault(false) }
    fun youtube(url: String): Boolean {
        val h = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com")
    }
    fun error(e: Throwable): String {
        val s = generateSequence(e) { it.cause }.take(5).joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            "drm" in s -> "الفيديو محمي بنظام DRM ولا يمكن تنزيله."
            "private" in s -> "هذا الفيديو خاص ولا يمكن تنزيله."
            "not a bot" in s || "confirm you’re not" in s || "confirm you're not" in s || "429" in s ->
                "يوتيوب يطلب التحقق أو يقيّد الطلبات من اتصالك. حاول لاحقًا؛ تحديث الأداة وحده قد لا يحل ذلك."
            listOf("login", "sign in", "log in", "cookies", "age-restricted").any { it in s } ->
                "الفيديو يحتاج تسجيل دخول أو تحققًا من العمر. التطبيق لا يدعم تسجيل الدخول."
            listOf("javascript","ejs","signature","challenge","quickjs").any { it in s } ->
                "تعذر تشغيل مكونات يوتيوب. حدّث أداة التنزيل وتأكد من إمكانية الوصول إلى GitHub."
            "403" in s -> "رفض الموقع الوصول إلى رابط التنزيل. افحص رابط الفيديو مجددًا لتحديث الجودات والروابط."
            "requested format" in s -> "الجودة المختارة لم تعد متاحة. افحص الفيديو مجددًا واختر جودة أخرى."
            "no downloadable formats" in s -> "لم يعثر المستخرج على جودات قابلة للتنزيل. حدّث الأداة ثم افحص الرابط مجددًا."
            "unsupported" in s -> "الرابط أو صيغة الفيديو غير مدعومة. جرّب تحديث أداة التنزيل."
            "not available" in s || "unavailable" in s || "404" in s -> "الفيديو غير متاح أو تم حذفه."
            "space" in s || "enospc" in s -> "المساحة غير كافية. حرّر مساحة ثم أعد المحاولة."
            "permission" in s -> "تم رفض الإذن المطلوب. راجع التفاصيل وأذونات التطبيق."
            "timed out" in s || "resolve" in s || "network" in s -> "تعذر الاتصال. تحقق من الشبكة ثم أعد المحاولة."
            else -> "تعذرت معالجة الفيديو. حدّث أداة التنزيل ثم أعد المحاولة؛ يمكنك نسخ التفاصيل لمعرفة السبب."
        }
    }
    fun details(e: Throwable): String = generateSequence(e) { it.cause }.take(5)
        .joinToString("\n") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        .replace(Regex("https?://[^\\s<>]+"),"[رابط محذوف]")
        .replace(Regex("(?i)(cookie|authorization|token)\\s*[:=]\\s*[^\\s,;]+"),"$1=[محذوف]")
        .take(3000)
    fun report(e: Throwable): String = error(e) + "\n\nالتفاصيل الفنية:\n" + details(e)
}
