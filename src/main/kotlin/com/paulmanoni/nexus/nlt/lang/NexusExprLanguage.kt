package com.paulmanoni.nexus.nlt.lang

import com.intellij.lang.Language

/**
 * NexusExpr is the tiny "language" we inject into expression
 * attribute values inside .nlt files so the lexer + highlighter
 * + (no-op) parser machinery hangs onto something concrete.
 *
 * It's not meant to be a file language — users will never open an
 * .nexusexpr file. The point is to claim an ID that the
 * MultiHostInjector can target so IntelliJ knows "treat this
 * embedded range as NexusExpr, run its lexer, color its tokens."
 *
 * The "base language" trick (Language) means we don't need a full
 * PSI grammar to participate in editor features — minimum-viable
 * language registration is enough for the highlighting pass.
 */
class NexusExprLanguage private constructor() : Language(ID) {
    companion object {
        const val ID: String = "NexusExpr"
        val INSTANCE: NexusExprLanguage = NexusExprLanguage()
    }
}