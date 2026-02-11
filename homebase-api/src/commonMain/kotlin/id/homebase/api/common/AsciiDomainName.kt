package id.homebase.api.common

import kotlin.jvm.JvmInline

// ------------------------------------------------------------------
// IDN conversion – platform-specific (see the other two files)
// ------------------------------------------------------------------
expect object Idn {
    fun toAscii(idn: String): String
    fun toUnicode(puny: String): String
}

// ------------------------------------------------------------------
// Value class – identical behaviour to your original C# struct
// ------------------------------------------------------------------
@JvmInline
value class AsciiDomainName private constructor(
    /** public string DomainName { get; init; } from C# */
    val domainName: String
) {

    fun toIDN(): String = Idn.toUnicode(domainName)

    override fun toString(): String = domainName

    companion object {
        /**
         * Public constructor – exactly like your C# constructor.
         * Lowercases + validates. You write exactly the same as in C#:
         *     val d = AsciiDomainName("xn--bcher-kva.ch")
         */
        operator fun invoke(punyDomainName: String): AsciiDomainName {
            val normalized = punyDomainName.lowercase()
            AsciiDomainNameValidator.assertValidDomain(normalized)
            return AsciiDomainName(normalized)
        }

        /**
         * Exactly like the static FromIDN in C#.
         */
        fun fromIDN(idnDomainName: String): AsciiDomainName {
            val puny = Idn.toAscii(idnDomainName)
            return AsciiDomainName(puny)          // calls the operator invoke above
        }
    }
}

// ------------------------------------------------------------------
// Validator – pure Kotlin, works everywhere
// ------------------------------------------------------------------
object AsciiDomainNameValidator {

    const val MAX_DNS_LABEL_COUNT = 127
    const val MAX_DNS_LABEL_LENGTH = 63
    const val MAX_DNS_DOMAIN_LENGTH = 253

    private val codeAsciiMap: IntArray = intArrayOf(
        128,128,128,128,128,128,128,128,128,128,
        128,128,128,128,128,128,128,128,128,128,
        128,128,128,128,128,128,128,128,128,128,
        128,128,128,128,128,128,128,128,128,128,
        128,128,128,128,128, 26, 27,128, 28, 29,
        30, 31, 32, 33, 34, 35, 36, 37,128,128,
        128,128,128,128,128,  0,  1,  2,  3,  4,
        5,  6,  7,  8,  9, 10, 11, 12, 13, 14,
        15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
        25,128,128,128,128,128,128,  0,  1,  2,
        3,  4,  5,  6,  7,  8,  9, 10, 11, 12,
        13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
        23, 24, 25,128,128,128,128,128,128,128,
        128,128,128,128,128,128,128,128,128,128,
        128,128,128,128,128,128,128,128,128,128,
        128,128,128,128,128,128,128,128,128,128,
        128,128,128,128,128,128,128,128,128,128,
        128,128,128,128,128,128,128,128,128,128,
        128,128,128,128,128,128,128,128,128,128,
        128,128,128,128,128,128,128,128,128,128,
        128,128,128,128,128,128
    )

    fun tryValidateDomain(domainName: String?): Boolean {
        if (domainName == null) return false
        if (domainName.length < 3 || domainName.length > MAX_DNS_DOMAIN_LENGTH) return false

        var labelCount = 0
        var labelLength = 0

        for (i in domainName.indices) {
            val ch = domainName[i]
            if (ch.code > 255) return false
            if (codeAsciiMap[ch.code] == 128) return false

            if (ch == '.') {
                if (labelLength == 0) return false
                labelCount++
                if (labelLength > MAX_DNS_LABEL_LENGTH) return false
                if (domainName[i - 1] == '-') return false
                labelLength = 0
            } else {
                if (labelLength == 0 && ch == '-') return false
                labelLength++
            }
        }

        labelCount++

        if (labelLength == 0 ||
            labelLength > MAX_DNS_LABEL_LENGTH ||
            domainName.last() == '-'
        ) return false

        return labelCount >= 2 && labelCount < MAX_DNS_LABEL_COUNT
    }

    fun assertValidDomain(punyCodeDomain: String) {
        if (!tryValidateDomain(punyCodeDomain)) {
            throw IllegalArgumentException("Illegal puny code domain name: '$punyCodeDomain'")
        }
    }
}