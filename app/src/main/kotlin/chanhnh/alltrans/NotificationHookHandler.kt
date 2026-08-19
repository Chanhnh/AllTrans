package chanhnh.alltrans

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import androidx.annotation.RequiresApi
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.Arrays
import java.util.Objects

class NotificationHookHandler : XC_MethodReplacement(), OriginalCallable {
    override fun callOriginalMethod(translatedString: CharSequence?, userData: Any?) {
        val notificationHookUserData = userData as NotificationHookUserData
        val methodHookParam = notificationHookUserData.methodHookParam
        val originalString = notificationHookUserData.originalString
        val myMethod = methodHookParam.method as Method
        myMethod.setAccessible(true)
        val myArgs = methodHookParam.args
        val notification = methodHookParam.args[methodHookParam.args.size - 1] as Notification

        if (translatedString != null) {
            changeText(notification, originalString, translatedString.toString())
        }
        try {
            Utils.debugLog(
                "In Thread " + Thread.currentThread()
                    .getId() + " Invoking original function " + methodHookParam.method.getName()
            )
            XposedBridge.invokeOriginalMethod(myMethod, methodHookParam.thisObject, myArgs)
        } catch (e: Throwable) {
            Log.e(
                "AllTrans",
                "AllTrans: Got error in invoking method as : " + Log.getStackTraceString(e)
            )
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private fun getAllText(notification: Notification): Array<CharSequence?> {
        val allNotificationText = ArrayList<CharSequence?>()
        if (notification.extras == null) {
            return allNotificationText.toTypedArray<CharSequence?>()
        }
        Utils.debugLog(
            "In Thread " + Thread.currentThread()
                .getId() + " and it has extras " + notification.extras
        )

        var charseqs = arrayOf<String?>(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT
        )
        Utils.debugLog(charseqs.contentToString() + "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val newCharseqs = Array<String?>(charseqs.size + 1) { i ->
                if (i < charseqs.size) charseqs[i] else Notification.EXTRA_BIG_TEXT
            }
            charseqs = newCharseqs
            Utils.debugLog(charseqs.contentToString() + "")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val newCharseqs2 = Array<String?>(charseqs.size + 2) { i ->
                if (i < charseqs.size) charseqs[i]
                else if (i == charseqs.size) Notification.EXTRA_SELF_DISPLAY_NAME
                else Notification.EXTRA_CONVERSATION_TITLE
            }
            charseqs = newCharseqs2
            Utils.debugLog(charseqs.contentToString() + "")
        }

        for (key in charseqs) {
            if (notification.extras.containsKey(key) && notification.extras.getCharSequence(key) != null) {
                Utils.debugLog(
                    "Got string " + key + " as " + notification.extras.getCharSequence(
                        key
                    )
                )
                allNotificationText.add(notification.extras.getCharSequence(key))
            }
        }

        val charSeqArr = arrayOf(Notification.EXTRA_TEXT_LINES, Notification.EXTRA_PEOPLE)
        for (key in charSeqArr) {
            if (notification.extras.containsKey(key) && notification.extras.getCharSequenceArray(key) != null) {
                allNotificationText.addAll(
                    Arrays.asList<CharSequence?>(
                        *Objects.requireNonNull<Array<CharSequence?>?>(
                            notification.extras.getCharSequenceArray(key)
                        )
                    )
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (notification.extras.containsKey(Notification.EXTRA_MESSAGING_PERSON)) {
                val person =
                    notification.extras.getParcelable<Person?>(Notification.EXTRA_MESSAGING_PERSON)
                if (person != null) {
                    allNotificationText.add(person.getName())
                }
            }
            if (notification.extras.containsKey(Notification.EXTRA_PEOPLE_LIST)) {
                val people =
                    notification.extras.getParcelableArrayList<Person?>(Notification.EXTRA_PEOPLE_LIST)
                if (people != null) {
                    for (person in people) {
                        allNotificationText.add(person.getName())
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            var messageArr = arrayOf(Notification.EXTRA_MESSAGES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                messageArr = arrayOf(
                    Notification.EXTRA_MESSAGES,
                    Notification.EXTRA_HISTORIC_MESSAGES
                )
            }
            for (key in messageArr) {
                if (notification.extras.containsKey(key) && notification.extras.getParcelableArray(
                        key
                    ) != null
                ) {
                    val histMessages = notification.extras.getParcelableArray(key)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val messages =
                            Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                                histMessages
                            )
                        for (message in messages) {
                            if (message == null) {
                                continue
                            }
                            if (message.getText() != null) {
                                allNotificationText.add(message.getText())
                            }
                            if (message.getSenderPerson() != null) {
                                allNotificationText.add(message.getSenderPerson()!!.getName())
                            }
                        }
                    }
                }
            }
        }
        return allNotificationText.toTypedArray<CharSequence?>()
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private fun changeText(
        notification: Notification,
        originalString: String?,
        translatedString: String?
    ) {
        Utils.debugLog(
            "In Thread " + Thread.currentThread()
                .getId() + " and it has extras " + notification.extras
        )

        var charseqs = arrayOf<String?>(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val newCharseqs = Array<String?>(charseqs.size + 1) { i ->
                if (i < charseqs.size) charseqs[i] else Notification.EXTRA_BIG_TEXT
            }
            charseqs = newCharseqs
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val newCharseqs2 = Array<String?>(charseqs.size + 2) { i ->
                if (i < charseqs.size) charseqs[i]
                else if (i == charseqs.size) Notification.EXTRA_SELF_DISPLAY_NAME
                else Notification.EXTRA_CONVERSATION_TITLE
            }
            charseqs = newCharseqs2
        }

        for (key in charseqs) {
            if (notification.extras.containsKey(key) && notification.extras.getCharSequence(key) != null) {
                if (notification.extras.getCharSequence(key).toString() == originalString) {
                    notification.extras.putCharSequence(key, translatedString)
                }
            }
        }

        val charSeqArr = arrayOf(Notification.EXTRA_TEXT_LINES, Notification.EXTRA_PEOPLE)
        for (key in charSeqArr) {
            if (notification.extras.containsKey(key) && notification.extras.getCharSequenceArray(key) != null) {
                val textList = notification.extras.getCharSequenceArray(key)
                if (textList == null) {
                    continue
                }
                val newTextList = ArrayList<CharSequence?>()
                for (charSequence in textList) {
                    if (charSequence.toString() == originalString) {
                        newTextList.add(translatedString)
                    } else {
                        newTextList.add(charSequence)
                    }
                }
                notification.extras.putCharSequenceArray(
                    key,
                    newTextList.toTypedArray<CharSequence?>()
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (notification.extras.containsKey(Notification.EXTRA_MESSAGING_PERSON)) {
                val person =
                    notification.extras.getParcelable<Person?>(Notification.EXTRA_MESSAGING_PERSON)
                if (person != null) {
                    if (person.getName() === originalString) {
                        val newPerson = person.toBuilder().setName(translatedString).build()
                        notification.extras.putParcelable(
                            Notification.EXTRA_MESSAGING_PERSON,
                            newPerson
                        )
                    }
                }
            }
            if (notification.extras.containsKey(Notification.EXTRA_PEOPLE_LIST)) {
                val people =
                    notification.extras.getParcelableArrayList<Person?>(Notification.EXTRA_PEOPLE_LIST)
                val newPeople = ArrayList<Person?>()
                if (people != null) {
                    for (person in people) {
                        if (person.getName() === originalString) {
                            val newPerson = person.toBuilder().setName(translatedString).build()
                            newPeople.add(newPerson)
                        } else {
                            newPeople.add(person)
                        }
                    }
                    notification.extras.putParcelableArrayList(
                        Notification.EXTRA_PEOPLE_LIST,
                        newPeople
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            var messageArr = arrayOf(Notification.EXTRA_MESSAGES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                messageArr = arrayOf(
                    Notification.EXTRA_MESSAGES,
                    Notification.EXTRA_HISTORIC_MESSAGES
                )
            }
            for (key in messageArr) {
                if (notification.extras.containsKey(key) && notification.extras.getParcelableArray(
                        key
                    ) != null
                ) {
                    val histMessages = notification.extras.getParcelableArray(key)
                    val newmessages: Array<Parcelable?>? =
                        getMessagesFromBundleArray2(histMessages, originalString, translatedString)
                    notification.extras.putParcelableArray(key, newmessages)
                }
            }
        }
    }

    @Throws(Throwable::class)
    override fun replaceHookedMethod(methodHookParam: MethodHookParam): Any? {
        // Verificar se o AllTrans está habilitado antes de processar
        val context = try {
            // Tentar obter contexto do NotificationManager
            val notificationManagerInstance = methodHookParam.thisObject
            val contextField = notificationManagerInstance.javaClass.getDeclaredField("mContext")
            contextField.isAccessible = true
            contextField.get(notificationManagerInstance) as? android.content.Context
        } catch (e: Exception) {
            // Se não conseguir obter contexto, usar o contexto global
            Alltrans.context
        }

        val packageName = context?.packageName
        if (!PreferenceManager.isEnabledForPackage(context, packageName)) {
            Utils.debugLog("NotificationHookHandler: AllTrans DESABILITADO para este app ($packageName). Chamando método original.")
            // Chamar o método original sem modificação
            return try {
                val method = methodHookParam.method as Method
                method.isAccessible = true
                XposedBridge.invokeOriginalMethod(method, methodHookParam.thisObject, methodHookParam.args)
            } catch (e: Exception) {
                Log.e("AllTrans", "Error calling original notification method", e)
                null
            }
        }

        Utils.debugLog("Notification : in notificationhook ")
        val notification = methodHookParam.args[methodHookParam.args.size - 1] as Notification
        val userDataOut = NotificationHookUserData(methodHookParam, "")
        callOriginalMethod("", userDataOut)

        val allNotificationTexts = getAllText(notification)

        var textIndex = 0
        while (textIndex < allNotificationTexts.size) {
            val text = allNotificationTexts[textIndex]
            textIndex++

            if (text == null || !SetTextHookHandler.isNotWhiteSpace(text.toString())) {
                continue
            }
            val stringArgs = text.toString()
            Utils.debugLog(
                "In Thread " + Thread.currentThread()
                    .getId() + " Recognized non-english string: " + stringArgs
            )

            val compositeKey = stringArgs.hashCode()

            var shouldSkip = false
            synchronized(Alltrans.pendingTextViewTranslations) {
                if (Alltrans.pendingTextViewTranslations.contains(compositeKey)) {
                    Utils.debugLog("NotificationHookHandler: Skipping translation for [$stringArgs], already pending with key ($compositeKey)")
                    shouldSkip = true
                } else {
                    Alltrans.pendingTextViewTranslations.add(compositeKey)
                    Utils.debugLog("NotificationHookHandler: Added composite key ($compositeKey) to pending set.")
                }
            }

            if (shouldSkip) {
                continue
            }

            val userData = NotificationHookUserData(methodHookParam, text.toString())

            Alltrans.cacheAccess.acquireUninterruptibly()
            var cacheHit = false
            var translatedStringFromCache: String? = null
            try {
                val cacheRef = Alltrans.cache
                if (PreferenceList.Caching && cacheRef != null) {
                    translatedStringFromCache = cacheRef.get(stringArgs)
                    if (translatedStringFromCache != null) {
                        Utils.debugLog(
                            "In Thread " + Thread.currentThread()
                                .getId() + " found string in cache: " + stringArgs + " as " + translatedStringFromCache
                        )
                        cacheHit = true
                    }
                }
            } finally {
                if (Alltrans.cacheAccess.availablePermits() == 0) {
                    Alltrans.cacheAccess.release()
                }
            }

            if (cacheHit) {
                synchronized(Alltrans.pendingTextViewTranslations) {
                    Alltrans.pendingTextViewTranslations.remove(compositeKey)
                    Utils.debugLog("NotificationHookHandler: Removed composite key ($compositeKey) from pending set after cache hit.")
                }
                callOriginalMethod(translatedStringFromCache, userData)
                continue
            }

            val getTranslate = GetTranslate()
            getTranslate.stringToBeTrans = stringArgs
            getTranslate.originalCallable = this
            getTranslate.userData = userData
            getTranslate.canCallOriginal = true
            getTranslate.pendingCompositeKey = compositeKey

            val getTranslateToken = GetTranslateToken()
            getTranslateToken.getTranslate = getTranslate
            getTranslateToken.doAll()
        }
        return null
    }

    companion object {
        fun getMessagesFromBundleArray2(
            bundles: Array<Parcelable?>?,
            originalString: String?,
            translatedString: String?
        ): Array<Parcelable?>? {
            if (bundles == null) {
                return null
            }
            for (i in bundles.indices) {
                val bundle = bundles[i] as Bundle?
                if (bundle == null) {
                    continue
                }
                if (bundle.containsKey("text") && bundle.getCharSequence("text") != null &&
                    bundle.getCharSequence("text").toString() == originalString
                ) {
                    bundle.putCharSequence("text", translatedString)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val senderPerson = bundle.getParcelable<Person?>("sender_person")
                    if (senderPerson != null && senderPerson.getName() === originalString) {
                        val newPerson = senderPerson.toBuilder().setName(translatedString).build()
                        bundle.putParcelable("sender_person", newPerson)
                    }
                }
                val senderName = bundle.getCharSequence("sender")
                if (senderName != null && senderName == originalString) {
                    bundle.putCharSequence("sender", translatedString)
                }
                bundles[i] = bundle
            }
            return bundles
        }
    }
}

internal class NotificationHookUserData(
    val methodHookParam: MethodHookParam,
    val originalString: String?
)