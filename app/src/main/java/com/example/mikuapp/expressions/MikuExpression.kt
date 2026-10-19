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

    fun detect(rawText: String, fallbackWhenSpeaking: Boolean = false): MikuExpression? {
        val text = normalize(rawText)
        if (text.length < 3) return null
        return when {
            hasAny(
                text,
                "jaja", "jeje", "jiji", "jojo", "juas", "risa", "chiste", "lol",
                "sarcasm", "ironi", "burla", "comedia", "gracios", "ridicul", "divertid",
            ) -> MikuExpression.RIENDO

            hasAny(
                text,
                "llorar", "llorando", "lloro", "llore", "lagrima", "funeral", "ataud", "tumba",
            ) -> MikuExpression.LLORANDO

            hasAny(
                text,
                "enoj", "enfad", "rabia", "odio", "furio", "brav", "coraj", "malgeni",
                "callate", "idiota", "imbecil", "babos", "inutil", "mediocr", "patetic",
                "fastidi", "molest", "groser", "tonto", "tonta", "tarad", "desastre", "basura",
            ) -> angryVariant()

            hasAny(
                text,
                "te amo", "te quiero", "enamor", "amor", "beso", "besos", "corazon",
                "hermos", "guap", "lind", "novi", "casar", "ternura", "tierno", "adorable",
            ) -> MikuExpression.ENAMORADA

            hasAny(
                text,
                "no entiendo", "no comprendo", "que dices", "que dijiste", "como asi",
                "como es eso", "que es eso", "en serio", "ni idea", "explicame",
                "confund", "a que te refieres", "extranad", "que?", "es broma", "milagro",
            ) -> MikuExpression.CONFUNDIDA

            hasAny(
                text,
                "dormir", "dormid", "duerm", "sueno", "cans", "agotad", "exhaust",
                "descans", "perezos", "aburr",
            ) -> MikuExpression.DORMIDA

            hasAny(
                text,
                "genial", "increible", "espectacular", "asombro", "inmortal", "superior",
                "wow", "perfect", "elegante", "el mejor", "la mejor", "neurona", "sinapsis",
                "de otro nivel", "cool", "asi se hace", "seleccion natural", "diva", "obvio",
            ) -> MikuExpression.COOL

            hasAny(
                text,
                "trist", "deprim", "decepc", "melancol", "estoy mal", "muert", "morir",
                "me siento mal", "mal dia", "tragedia", "vacio", "existencia", "condenad", "fracaso",
            ) -> MikuExpression.TRISTE

            hasAny(
                text,
                "feliz", "content", "alegre", "gracias", "excelente", "buenisim",
                "me encanta", "fiesta", "festej", "celebr", "baile", "maravillos", "fantast",
            ) -> happyVariant()

            fallbackWhenSpeaking -> alternate(MikuExpression.COOL, MikuExpression.FELIZ_2)

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
