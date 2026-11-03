package com.kavach.core.risk

import com.kavach.core.text.Extractors

/**
 * Whether a caller id is a normal Indian mobile, rather than a TRAI commercial series.
 *
 * Assumption, unverified against the TRAI directive text: promotional calls use 10-digit numbers
 * starting with 140, and service or transactional calls from banks and government use numbers
 * starting with 160 (the 1600 series). A personal mobile is a 10-digit national number starting
 * with 6, 7, 8 or 9. Those two series do not overlap that range; the prefix check stays so the
 * exemption is explicit. Metro landlines whose 10-digit form starts with 79 or 80 (Ahmedabad,
 * Bengaluru) are indistinguishable from mobiles here.
 */
internal object CallerNumber {
    fun isPersonalMobile(raw: String): Boolean {
        val national = indianNational10(raw) ?: return false
        if (national.startsWith("140") || national.startsWith("160")) return false
        return national[0] in '6'..'9'
    }

    /** 10-digit national significant number, or null when [raw] is not an Indian number of that shape. */
    private fun indianNational10(raw: String): String? {
        if (Extractors.isInternationalPrefix(raw)) return null
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.length == 12 && digits.startsWith("91") -> digits.substring(2)
            digits.length == 11 && digits.startsWith("0") -> digits.substring(1)
            digits.length == 10 -> digits
            else -> null
        }
    }
}
