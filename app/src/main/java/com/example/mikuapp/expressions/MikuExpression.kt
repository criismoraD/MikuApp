package com.example.mikuapp.expressions

import com.example.mikuapp.R
import java.text.Normalizer

enum class MikuExpression(val label: String, val drawableRes: Int) {
    RIENDO("riendo", R.drawable.miku_expr_riendo),
    LLORANDO("llorando", R.drawable.miku_expr_llorando),
    ENOJADA("enojada", R.drawable.miku_expr_enojada),
    ENOJADA_2("furiosa", R.drawable.miku_expr_enojada2),
    ENAMORADA("enamorada", R.drawable.miku_expr_enamorada),
    CONFUNDIDA("confundida", R.drawable.miku_expr_confundida),
    DORMIDA("dormida", R.drawable.miku_expr_dormida),
    COOL("cool", R.drawable.miku_expr_cool),
    TRISTE("triste", R.drawable.miku_expr_triste),
    FELIZ("feliz", R.drawable.miku_expr_feliz),
    FELIZ_2("radiante", R.drawable.miku_expr_feliz2),
}

object ExpressionDetector {

    private var alternateVariant = false

    fun detect(rawText: String): MikuExpression? {
        val text = normalize(rawText)
        if (text.length < 3) return null
        return when {
            hasAny(text, "jaja", "jeje", "jiji", "jojo", "juas", "risa", "chiste", "lol") ->
                MikuExpression.RIENDO

            hasAny(text, "llorar", "llorando", "lloro", "llore", "llorando") ->
                MikuExpression.LLORANDO

            hasAny(
                text,
                "enoj", "enfad", "rabia", "odio", "furio", "brav", "coraj", "malgeni",
                "callate", "idiota", "imbecil", "babos", "pendej", "gil", "floj",
                "fastidi", "molest", "groser", "tonto", "tonta", "tarad",
            ) -> angryVariant()

            hasAny(
                text,
                "te amo", "te quiero", "enamor", "amor", "beso", "besos", "corazon",
                "hermos", "guap", "lind", "novi", "casar",
            ) -> MikuExpression.ENAMORADA

            hasAny(
                text,
                "no entiendo", "no comprendo", "que dices", "que dijiste", "como asi",
                "como es eso", "que es eso", "que fue eso", "ni idea", "explicame",
                "confund", "a que te refieres", "extranad", "que?",
            ) -> MikuExpression.CONFUNDIDA

            hasAny(
                text,
                "dormir", "dormid", "duerm", "sueno", "cans", "agotad", "exhaust",
                "descans", "perezos",
            ) -> MikuExpression.DORMIDA

            hasAny(
                text,
                "bacan", "brutal", "genial", "increible", "espectacular", "asombro",
                "wow", "firm", "chimba", "elegante", "el mejor", "la mejor",
                "de otro nivel", "cool", "asi se hace",
            ) -> MikuExpression.COOL

            hasAny(
                text,
                "trist", "deprim", "decepc", "melancol", "estoy mal",
                "me siento mal", "mal dia",
            ) -> MikuExpression.TRISTE

            hasAny(
                text,
                "feliz", "content", "alegre", "gracias", "excelente", "buenisim",
                "me encanta", "divertid", "fiesta", "festej", "celebr", "baile",
            ) -> happyVariant()

            else -> null
        }
    }

    private fun hasAny(text: String, vararg keywords: String): Boolean =
        keywords.any { text.contains(it) }

    private fun angryVariant(): MikuExpression =
        alternate(MikuExpression.ENOJADA, MikuExpression.ENOJADA_2)

    private fun happyVariant(): MikuExpression =
        alternate(MikuExpression.FELIZ, MikuExpression.FELIZ_2)

    private fun alternate(first: MikuExpression, second: MikuExpression): MikuExpression {
        alternateVariant = !alternateVariant
        return if (alternateVariant) second else first
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
}
