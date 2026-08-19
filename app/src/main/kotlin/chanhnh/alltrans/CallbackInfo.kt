package chanhnh.alltrans

data class CallbackInfo(
    val userData: Any?,
    val originalCallable: OriginalCallable?,
    val canCallOriginal: Boolean,
    val originalString: String?,
    val pendingCompositeKey: Int = 0 // Já é val e tem valor padrão
)