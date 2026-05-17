package com.paulmanoni.nexus.nlt

import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlElement
import com.intellij.psi.xml.XmlTag
import com.intellij.xml.XmlAttributeDescriptor
import com.intellij.xml.XmlAttributeDescriptorsProvider

/**
 * Tells the HTML editor that framework directives — anything
 * starting with `nl-`, `:`, `@`, or `#` — are valid attributes on
 * any element inside a .nlt file. Without this, the platform's
 * built-in "Attribute X is not allowed here" inspection lights up
 * every nl-model, @submit.prevent, :src, #header as red squigglies.
 *
 * Modifier suffixes (`.prevent`, `.lazy.number`, etc.) ride along
 * inside the same attribute name; the prefix-only recognition
 * covers them automatically.
 *
 * Scope is restricted to .nlt files so regular HTML editing is not
 * affected — a plain `nl-foo` attribute in an .html file still gets
 * the "unknown attribute" warning, which is the right behavior.
 */
class NltAttributeDescriptorsProvider : XmlAttributeDescriptorsProvider {

    override fun getAttributeDescriptors(context: XmlTag?): Array<XmlAttributeDescriptor> {
        if (!isInNltFile(context)) return XmlAttributeDescriptor.EMPTY
        return KNOWN_DIRECTIVES.map(::AnyValueAttrDescriptor).toTypedArray()
    }

    override fun getAttributeDescriptor(
        attributeName: String?,
        context: XmlTag?,
    ): XmlAttributeDescriptor? {
        if (attributeName.isNullOrEmpty()) return null
        if (!isInNltFile(context)) return null
        return if (isNexusAttributeName(attributeName)) AnyValueAttrDescriptor(attributeName) else null
    }
}

private fun isInNltFile(context: XmlTag?): Boolean {
    val name = context?.containingFile?.name ?: return false
    return name.endsWith(".nlt")
}

private fun isNexusAttributeName(name: String): Boolean =
    name.startsWith("nl-") || name.startsWith(":") ||
        name.startsWith("@") || name.startsWith("#")

// Catalog of structural directive names surfaced via
// getAttributeDescriptors. Prefix-bearing forms (nl-bind:, @click,
// :src, #header) are still recognized by getAttributeDescriptor —
// they don't appear in this list because we'd need an open-ended
// enumeration of every possible suffix.
private val KNOWN_DIRECTIVES = listOf(
    "nl-if", "nl-else-if", "nl-else", "nl-for",
    "nl-show", "nl-model", "nl-html", "nl-text",
    "nl-once", "nl-pre",
)

/**
 * AnyValueAttrDescriptor is the minimal XmlAttributeDescriptor
 * implementation: a recognized name that accepts any string value
 * without further validation. Used for framework attributes whose
 * values are expressions (we don't try to type-check them in the
 * editor; the runtime catches malformed ones at render time).
 */
private class AnyValueAttrDescriptor(private val attrName: String) : XmlAttributeDescriptor {
    override fun getName(): String = attrName
    override fun getName(context: PsiElement?): String = attrName
    override fun init(element: PsiElement?) {}
    override fun isRequired(): Boolean = false
    override fun isFixed(): Boolean = false
    override fun hasIdType(): Boolean = false
    override fun hasIdRefType(): Boolean = false
    override fun getDefaultValue(): String? = null
    override fun isEnumerated(): Boolean = false
    override fun getEnumeratedValues(): Array<String>? = null
    override fun validateValue(context: XmlElement?, value: String?): String? = null
    override fun getDeclaration(): PsiElement? = null
    override fun getDependencies(): Array<Any> = emptyArray()
    override fun getDeclarations(): Collection<PsiElement> = emptyList()
}

// Some platform builds reference XmlAttribute in default-method
// signatures; the unused import keeps the descriptor compatible.
@Suppress("unused")
private val keepXmlAttributeImport: Class<*> = XmlAttribute::class.java