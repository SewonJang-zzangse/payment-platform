package com.crossremit.payment.money

import java.math.BigDecimal

/**
 * Money value object.
 *
 * Rules (non-negotiable in payment systems):
 * - Never use floating point. Amounts are always integer minor units + currency code.
 * - Per-currency decimal exponents (JPY=0, USD=2, BHD=3) live in [CurrencyRegistry].
 * - Arithmetic across different currencies is rejected at the type level.
 */
data class Money(val amountMinor: Long, val currency: String) {

    init {
        require(currency in CurrencyRegistry.EXPONENTS) { "Unknown currency: $currency" }
    }

    operator fun plus(other: Money): Money {
        check(other)
        return Money(amountMinor + other.amountMinor, currency)
    }

    operator fun minus(other: Money): Money {
        check(other)
        return Money(amountMinor - other.amountMinor, currency)
    }

    operator fun unaryMinus(): Money = Money(-amountMinor, currency)

    private fun check(other: Money) {
        if (currency != other.currency) throw CurrencyMismatch(currency, other.currency)
    }

    override fun toString(): String {
        val exp = CurrencyRegistry.EXPONENTS.getValue(currency)
        if (exp == 0) return "$amountMinor $currency"
        val major = BigDecimal(amountMinor).movePointLeft(exp)
        return "$major $currency"
    }

    companion object {
        /** Convert a human-readable amount ("123.45") to minor units via BigDecimal (lossless). */
        fun of(major: String, currency: String): Money {
            val exp = CurrencyRegistry.EXPONENTS[currency]
                ?: throw IllegalArgumentException("Unknown currency: $currency")
            val scaled = BigDecimal(major).movePointRight(exp).setScale(0).toLong()
            return Money(scaled, currency)
        }

        fun zero(currency: String) = Money(0, currency)
    }
}

class CurrencyMismatch(a: String, b: String) : RuntimeException("$a vs $b")

object CurrencyRegistry {
    val EXPONENTS: Map<String, Int> = mapOf(
        "USD" to 2, "EUR" to 2, "GBP" to 2, "KRW" to 0, "JPY" to 0, "BHD" to 3, "BRL" to 2,
    )
}
