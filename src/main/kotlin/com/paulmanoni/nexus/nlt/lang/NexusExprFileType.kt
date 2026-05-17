package com.paulmanoni.nexus.nlt.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/**
 * Placeholder FileType for NexusExpr. IntelliJ's language
 * machinery wants a FileType paired with every Language, even
 * when the language only ever appears as an injection (never as
 * a real on-disk file). We register no extension, no icon, no
 * description that users would ever see.
 */
object NexusExprFileType : LanguageFileType(NexusExprLanguage.INSTANCE) {
    override fun getName(): String = "NexusExpr"
    override fun getDescription(): String = "Nexus live-template expression"
    override fun getDefaultExtension(): String = "" // never used; not a real file
    override fun getIcon(): Icon? = null
}