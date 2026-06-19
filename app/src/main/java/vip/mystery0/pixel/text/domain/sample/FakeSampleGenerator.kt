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
            repeat(8) {
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
        val prefix = bankCardPrefixes.random(random).take(length)
        return buildString {
            append(prefix)
            repeat(length - prefix.length) {
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
        private val surnames =
            "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元卜顾孟平黄和穆萧尹姚邵湛汪祁毛禹狄米贝明臧计伏成戴谈宋庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田胡凌霍虞万支柯昝管卢莫经房裘缪干解应宗丁宣邓郁单杭洪包诸左石崔吉龚程邢裴陆荣翁荀羊甄家封芮储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全郗班仰秋仲伊宫宁仇栾暴甘厉戎祖武符刘景詹龙叶幸司韶郜黎蓟薄印宿白怀蒲台从鄂索咸籍赖卓蔺屠蒙池乔阴郁胥能苍双闻莘党翟谭贡劳逄姬申扶堵冉宰雍桑寿通燕冀郏浦尚农温别庄晏柴瞿阎充慕连茹习宦艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国文寇广禄阙东欧殳沃利蔚越夔隆师巩厍聂晁勾敖融冷訾辛阚那简饶空曾毋沙养鞠须丰巢关蒯相查后荆红游竺权逯盖益桓公"
        private val givenNameChars =
            "子一宇梓欣雨浩晨泽轩睿思嘉佳怡诗雅语文博俊杰明华芳娜敏静强磊军洋勇艳涛超秀霞平刚鹏飞阳鑫凯宁乐然安可凡航皓铭辰诺昊彤悦涵瑞瑶琳琪瑜璐雪莹颖慧妍洁雯婷萱菲倩璇蕾莉媛丹欣妤依楠桐森柏榕川峰远诚毅恒清源洲洋舟帆卓越彦霖煜熙宸奕嘉祺锦程若曦沐阳"
        private val phonePrefixes = listOf(
            "130", "131", "132", "133", "135", "136", "137", "138", "139",
            "150", "151", "152", "155", "156", "157", "158", "159",
            "166", "170", "171", "172", "173", "175", "176", "177", "178",
            "180", "181", "182", "183", "185", "186", "187", "188",
            "191", "193", "195", "196", "198", "199"
        )
        private val idCardAreas = listOf(
            "110101", "110102", "110105", "110106", "110108", "110109",
            "120101", "120102", "120103", "120104", "120105",
            "130102", "130104", "130105", "130108", "130109",
            "310101", "310104", "310105", "310106", "310107", "310110", "310115",
            "320102", "320104", "320105", "320106", "320111", "320115",
            "330102", "330105", "330106", "330108", "330110",
            "340102", "340103", "340104", "340111",
            "350102", "350103", "350104", "350105",
            "370102", "370103", "370104", "370105",
            "420102", "420103", "420104", "420105", "420106", "420111",
            "430102", "430103", "430104", "430105", "430111",
            "440103", "440104", "440105", "440106", "440111", "440113",
            "440303", "440304", "440305", "440306", "440307",
            "500101", "500103", "500104", "500105", "500106", "500107",
            "510104", "510105", "510106", "510107", "510108",
            "610102", "610103", "610104", "610111", "610112"
        )
        private val idCardWeights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        private val idCardChecksums = charArrayOf('1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2')
        private val bankCardPrefixes = listOf(
            "102033", "103000", "185720", "303781", "356827", "356833",
            "402658", "403361", "409666", "421349", "433666", "438088",
            "451289", "458123", "489592", "512315", "518710", "520083",
            "524091", "552245", "622126", "622202", "622208", "622225",
            "622260", "622262", "622280", "622588", "622609", "622700",
            "622848", "623058", "623088", "625908", "628288", "955880"
        )
        private val cities = listOf(
            "北京市", "上海市", "广州市", "深圳市", "杭州市", "南京市", "成都市", "重庆市",
            "武汉市", "西安市", "苏州市", "天津市", "长沙市", "郑州市", "青岛市", "宁波市",
            "厦门市", "福州市", "济南市", "合肥市", "昆明市", "南昌市", "无锡市", "佛山市"
        )
        private val districts = listOf(
            "朝阳区", "海淀区", "西城区", "浦东新区", "徐汇区", "静安区", "天河区", "越秀区",
            "南山区", "福田区", "西湖区", "滨江区", "鼓楼区", "玄武区", "锦江区", "武侯区",
            "江汉区", "洪山区", "雁塔区", "碑林区", "姑苏区", "滨湖区", "渝中区", "江北区",
            "岳麓区", "金水区", "市南区", "鄞州区", "思明区", "包河区"
        )
        private val roads = listOf(
            "星河路", "青云路", "云杉路", "望江路", "晨光路", "海棠路", "梧桐路", "银杏路",
            "枫林路", "瑞景路", "锦绣路", "文华路", "长宁路", "和平路", "建设路", "人民路",
            "创新大道", "科技大道", "软件园路", "滨河路", "湖滨路", "花园路", "学院路", "春晓路",
            "丹桂路", "翠竹路", "金桥路", "云海路", "星光路", "明月路"
        )
        private val communities = listOf(
            "未来小区", "星辰花园", "云栖公寓", "晴川苑", "南山里", "锦云府", "望江庭",
            "海棠湾", "蓝湾国际", "翡翠城", "城市花园", "阳光家园", "和平里", "春晓苑",
            "桂花园", "观澜府", "江湾名邸", "滨河公馆", "星悦城", "云著华庭", "万和苑",
            "瑞景园", "书香府邸", "清河湾", "金色家园", "幸福里", "天玺公寓", "明月雅居"
        )
    }
}
