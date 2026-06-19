package vip.mystery0.pixel.text.domain.sample

import kotlin.random.Random

class FakeSampleGenerator(
    private val random: Random = Random.Default,
) {
    fun generate(type: SensitiveType, source: String): String {
        return when (type) {
            SensitiveType.NAME -> generateName(source)
            SensitiveType.PHONE -> generatePhone()
            SensitiveType.ID_CARD -> generateIdCard()
            SensitiveType.ADDRESS -> generateAddress()
            SensitiveType.BANK_CARD -> generateBankCard(source)
            SensitiveType.ORDER_ID -> generateByShape(source, letterMode = LetterMode.PRESERVE_CASE)
            SensitiveType.VERIFICATION_CODE -> generateByShape(source, letterMode = LetterMode.UPPERCASE)
            SensitiveType.AMOUNT -> generateAmount(source)
            SensitiveType.OTHER -> generateOther(source)
        }
    }

    private fun generateName(source: String): String {
        val length = source.length.coerceAtLeast(1)
        val surname = surnames.random(random).toString()
        if (length == 1) return surname
        val name = buildString {
            append(surname)
            repeat(length - 1) {
                append(givenNameChars.random(random))
            }
        }
        return if (name == source) "李明".take(length) else name
    }

    private fun generatePhone(): String {
        val prefix = phonePrefixes.random(random)
        return buildString {
            append(prefix)
            repeat(9) {
                append(randomDigit())
            }
        }
    }

    private fun generateIdCard(): String {
        val area = idCardAreas.random(random)
        val year = random.nextInt(1975, 2005).toString()
        val month = random.nextInt(1, 13).toString().padStart(2, '0')
        val day = random.nextInt(1, 29).toString().padStart(2, '0')
        val sequence = random.nextInt(1, 999).toString().padStart(3, '0')
        val first17 = "$area$year$month$day$sequence"
        return first17 + idCardChecksum(first17)
    }

    private fun generateAddress(): String {
        val city = cities.random(random)
        val district = districts.random(random)
        val road = roads.random(random)
        val number = random.nextInt(18, 299)
        val community = communities.random(random)
        val building = random.nextInt(1, 18)
        val room = random.nextInt(101, 2604)
        return "$city$district$road${number}号$community${building}号楼${room}室"
    }

    private fun generateBankCard(source: String): String {
        val digitCount = source.count { it.isAsciiDigit() }
        val length = if (digitCount > 16) 19 else 16
        return buildString {
            append("6222")
            repeat(length - 4) {
                append(randomDigit())
            }
        }
    }

    private fun generateAmount(source: String): String {
        val match = amountRegex.find(source) ?: return generateOther(source)
        val prefix = match.groupValues[1]
        val integer = match.groupValues[2]
        val decimal = match.groupValues[3]
        val suffix = match.groupValues[4]
        val fakeInteger = randomNumber(integer.length.coerceAtLeast(1), firstNonZero = true)
        val fakeDecimal = if (decimal.isBlank()) {
            ""
        } else {
            "." + randomNumber(decimal.length, firstNonZero = false)
        }
        return "$prefix$fakeInteger$fakeDecimal$suffix"
    }

    private fun generateOther(source: String): String {
        return buildString {
            source.forEach { char ->
                append(
                    when {
                        char.isChinese() -> '某'
                        char.isAsciiDigit() -> randomDigit()
                        char in 'A'..'Z' -> randomUppercaseLetter()
                        char in 'a'..'z' -> randomLowercaseLetter()
                        else -> char
                    }
                )
            }
        }
    }

    private fun generateByShape(
        source: String,
        letterMode: LetterMode,
    ): String {
        return buildString {
            source.forEach { char ->
                append(
                    when {
                        char.isAsciiDigit() -> randomDigit()
                        char in 'A'..'Z' -> randomUppercaseLetter()
                        char in 'a'..'z' -> when (letterMode) {
                            LetterMode.PRESERVE_CASE -> randomLowercaseLetter()
                            LetterMode.UPPERCASE -> randomUppercaseLetter()
                        }

                        char.isChinese() -> '某'
                        else -> char
                    }
                )
            }
        }
    }

    private fun randomNumber(
        length: Int,
        firstNonZero: Boolean,
    ): String = buildString {
        repeat(length) { index ->
            val digit = if (index == 0 && firstNonZero) {
                random.nextInt(1, 10)
            } else {
                random.nextInt(0, 10)
            }
            append(digit)
        }
    }

    private fun randomDigit(): Char = ('0'.code + random.nextInt(10)).toChar()

    private fun randomUppercaseLetter(): Char = ('A'.code + random.nextInt(26)).toChar()

    private fun randomLowercaseLetter(): Char = ('a'.code + random.nextInt(26)).toChar()

    private fun idCardChecksum(first17: String): Char {
        val sum = first17.mapIndexed { index, char ->
            (char - '0') * idCardWeights[index]
        }.sum()
        return idCardChecksums[sum % 11]
    }

    private enum class LetterMode {
        PRESERVE_CASE,
        UPPERCASE,
    }

    private companion object {
        private val amountRegex = Regex("""^(\D*?)(\d+)(?:\.(\d+))?(\D*)$""")
        private val surnames = "赵钱孙李周吴郑王冯陈刘杨黄林何高马罗"
        private val givenNameChars = "明华芳娜敏静强磊军洋勇艳杰娟涛超秀霞平刚"
        private val phonePrefixes = listOf("13", "15", "17", "18", "19")
        private val idCardAreas = listOf("110101", "310101", "320106", "440106", "510104")
        private val idCardWeights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        private val idCardChecksums = charArrayOf('1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2')
        private val cities = listOf("上海市", "杭州市", "南京市", "成都市", "广州市")
        private val districts = listOf("浦东新区", "西湖区", "鼓楼区", "锦江区", "天河区")
        private val roads = listOf("星河路", "青云路", "云杉路", "望江路", "晨光路")
        private val communities = listOf("未来小区", "星辰花园", "云栖公寓", "晴川苑", "南山里")
    }
}
