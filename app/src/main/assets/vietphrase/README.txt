Place VietPhrase dictionary files in this folder using these exact names:

- Names.txt
- VietPhrase.txt
- ChinesePhienAmWords.txt
- Pronouns.txt
- rule.txt

The VietPhrase provider only supports one-way Chinese to Vietnamese translation.
If these files are missing, AllTrans will log a warning and return the original text.

`Pronouns.txt` and `rule.txt` are adapted from duongden's
vietphrase-translator userscript, which is licensed under GPL-3.0:

https://github.com/duongden/script-vietphrase-translator

The rule engine is implemented in Kotlin in VietPhraseRuleEngine.kt and
VietPhraseTranslationEngine.kt. No userscript runtime is embedded.
