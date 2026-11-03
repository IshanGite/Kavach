package com.kavach.core.baseline

import com.kavach.core.text.Extractors

data class PastPayment(val payee: String, val amountRupees: Double, val atMs: Long)

/**
 * The user's own payment history (from debit SMS and observed pay screens).
 * This is what keeps everyday payments friction-free.
 */
interface PaymentBaseline {
    fun historySize(): Int
    fun medianAmount(): Double?
    fun paymentCount(payee: String?): Int
    fun record(payment: PastPayment)
}

class InMemoryBaseline(initial: List<PastPayment> = emptyList()) : PaymentBaseline {
    private val payments = initial.toMutableList()

    override fun historySize() = payments.size

    override fun medianAmount(): Double? {
        if (payments.isEmpty()) return null
        val sorted = payments.map { it.amountRupees }.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    override fun paymentCount(payee: String?): Int {
        if (payee.isNullOrBlank()) return 0
        val key = Extractors.normalizePayee(payee)
        return payments.count { Extractors.normalizePayee(it.payee) == key }
    }

    override fun record(payment: PastPayment) {
        payments += payment
    }

    /** For persistence by the app layer. */
    fun snapshot(): List<PastPayment> = payments.toList()

    fun replaceAll(list: List<PastPayment>) {
        payments.clear(); payments.addAll(list)
    }
}
