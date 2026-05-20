package com.example.gemmabuddy

import android.graphics.Bitmap

enum class TemplateType(val displayName: String) {
    HUMAN("人型"),
    ANIMAL("動物（ネコ系）"),
    ROBOT("ロボット"),
    DRAGON("ドラゴン")
}

/**
 * 32×32 ピクセルキャラクターテンプレート群。
 *
 * テンプレート文字の意味:
 *   '.' transparent  'H' HAIR   'F' FACE   'E' EYE   'M' MOUTH
 *   'S' SHIRT        'A' ARM    'G' HAND   'P' PANTS 'B' FOOT
 *
 * 各テンプレートで Region の見た目上の意味が変わる（Region テーブルは plan 参照）。
 */
object CharacterTemplate {

    enum class Region(val char: Char) {
        TRANSPARENT('.'),
        HAIR('H'), FACE('F'), EYE('E'), MOUTH('M'),
        SHIRT('S'), ARM('A'), HAND('G'), PANTS('P'), FOOT('B')
    }

    // ─────────────────────────────────────────────────────────────────────
    // テンプレート定義（32 行 × 32 桁）
    // ─────────────────────────────────────────────────────────────────────

    /** 人型（既存） */
    private val TEMPLATE_HUMAN = arrayOf(
        "................................", //  0
        "................................", //  1
        "............HHHHHHHH............", //  2 hair top
        "..........HHHHHHHHHHHH..........", //  3
        ".........HHHHHHHHHHHHHH.........", //  4
        "........HHHHHHHHHHHHHHHH........", //  5
        "........HHHFFFFFFFFFFHHH........", //  6 face
        "........HHFFFFFFFFFFFFHH........", //  7
        "........HHFFEEFFFFEEFFHH........", //  8 EYES
        "........HHFFEEFFFFEEFFHH........", //  9 EYES
        "........HHFFFFFFFFFFFFHH........", // 10
        "........HHFFFFMMMMFFFFHH........", // 11 MOUTH
        "........HHFFFFFFFFFFFFHH........", // 12
        ".........FFFFFFFFFFFFF..........", // 13 jaw
        "...........FFFFFFFFFF...........", // 14 chin
        "............SSSSSSSS............", // 15 collar
        "..........SSSSSSSSSSSS..........", // 16 shoulders
        "........SSSSSSSSSSSSSSSS........", // 17 body
        ".......SSSSSSSSSSSSSSSSSS.......", // 18
        ".....AAASSSSSSSSSSSSSSAAA.......", // 19 arms
        ".....AAASSSSSSSSSSSSSSAAA.......", // 20
        ".....AAASSSSSSSSSSSSSSAAA.......", // 21
        "....GGAASSSSSSSSSSSSSSAAGG......", // 22 HANDS
        "....GG.SSSSSSSSSSSSSSSS.GG......", // 23
        "........PPPPPPP..PPPPPPP........", // 24 pants
        "........PPPPPPP..PPPPPPP........", // 25
        "........PPPPPPP..PPPPPPP........", // 26
        "........PPPPPPP..PPPPPPP........", // 27
        "........PPPPPPP..PPPPPPP........", // 28
        "........PPPPPPP..PPPPPPP........", // 29
        ".......BBBBBBBBB.BBBBBBBBB......", // 30 FEET
        ".......BBBBBBBBB.BBBBBBBBB......"  // 31
    )

    /** 動物（ネコ系）：三角耳・丸顔・肉球 */
    private val TEMPLATE_ANIMAL = arrayOf(
        "................................", //  0
        ".......HHH..........HHH.........", //  1 耳
        "......HHHHH........HHHHH........", //  2 耳
        ".....HHHHHHHHHHHHHHHHHHHH.......", //  3 耳→頭に接続
        ".....HHHHHHHHHHHHHHHHHHHHH......", //  4 頭
        ".....HHHFFFFFFFFFFFFFFHHHH......", //  5 顔始まり
        ".....HHHFFFFFFFFFFFFFFHHHH......", //  6
        ".....HHFFEEFFFFEEFFFFFFFHH......", //  7 目(EE)
        ".....HHFFEEFFFFEEFFFFFFFHH......", //  8 目(EE)
        ".....HHFFFFFFFFFFFFFFFFHHH......", //  9
        ".....HHFFFFFMMMFFFFFFFFF.HH.....", // 10 鼻(M)
        ".....HHHFFFFFFFFFFFFFFFF.HH.....", // 11
        ".....HHHFFFFFFFFFFFFFFHHH.......", // 12
        "......HHHHHHHHHHHHHHHHHH........", // 13 顎
        ".........SSSSSSSSSSSS...........", // 14 首・服
        ".......SSSSSSSSSSSSSSSS.........", // 15 肩
        "......SSSSSSSSSSSSSSSSSS........", // 16 体
        ".....AAASSSSSSSSSSSSSSAAA.......", // 17 前脚
        ".....AAASSSSSSSSSSSSSSAAA.......", // 18
        ".....AAASSSSSSSSSSSSSSAAA.......", // 19
        "....GGGAAASSSSSSSSSSAAAGGG......", // 20 肉球(G)
        "....GGG..SSSSSSSSSS..GGG........", // 21 肉球分離
        "........PPPPPPP..PPPPPPP........", // 22 後脚
        "........PPPPPPP..PPPPPPP........", // 23
        "........PPPPPPP..PPPPPPP........", // 24
        "........PPPPPPP..PPPPPPP........", // 25
        "........PPPPPPP..PPPPPPP........", // 26
        "........PPPPPPP..PPPPPPP........", // 27
        "........PPPPPPP..PPPPPPP........", // 28
        "........PPPPPPP..PPPPPPP........", // 29
        ".......BBBBBBBBB.BBBBBBBBB......", // 30 後ろ肉球
        ".......BBBBBBBBB.BBBBBBBBB......"  // 31
    )

    /** ロボット：四角い頭・アンテナ・機械アーム */
    private val TEMPLATE_ROBOT = arrayOf(
        "...............HH...............", //  0 アンテナ(H)
        "...............HH...............", //  1
        "...............HH...............", //  2
        "..........HHHHHHHHHHHH..........", //  3 頭頂パネル(H)
        "..........HFFFFFFFFFFHH..........", //  4 顔プレート(F)
        ".........HFFFFFFFFFFFFFFF.......", //  5  ← 33? 修正
        ".........FFFFFFFFFFFFFFHHH......", //  6
        ".........FFFFFFFFFFFFFFFHH......", //  7
        ".........FFEEFFFFEEFFFFFHH......", //  8 センサー(E)
        ".........FFEEFFFFEEFFFFFHH......", //  9
        ".........FFFFFFFFFFFFFFFF.......", // 10
        ".........FFMMMMMMMFFFFFF........", // 11 スピーカー(M)
        ".........FFFFFFFFFFFFFFFHH......", // 12
        ".........HHHHHHHHHHHHHHHH.......", // 13 頭底パネル(H)
        "........SSSSSSSSSSSSSSSSSS......", // 14 胴体
        "........SSSSSSSSSSSSSSSSSS......", // 15
        ".......SSSSSSSSSSSSSSSSSSSS.....", // 16
        "......SSSSSSSSSSSSSSSSSSSSSS....", // 17
        "....AAASSSSSSSSSSSSSSSSSSAAA....", // 18 アーム(A)
        "....AAASSSSSSSSSSSSSSSSSSAAA....", // 19
        "....AAASSSSSSSSSSSSSSSSSSAAA....", // 20
        "...GGGAASSSSSSSSSSSSSSSSAAGG....", // 21 クロー(G)
        "...GGG..SSSSSSSSSSSSSSSS..GGG...", // 22
        ".........PPPPPPP.PPPPPPP........", // 23 脚部(P)
        ".........PPPPPPP.PPPPPPP........", // 24
        ".........PPPPPPP.PPPPPPP........", // 25
        ".........PPPPPPP.PPPPPPP........", // 26
        ".........PPPPPPP.PPPPPPP........", // 27
        ".........PPPPPPP.PPPPPPP........", // 28
        ".........PPPPPPP.PPPPPPP........", // 29
        "........BBBBBBBB.BBBBBBBB.......", // 30 スラスター(B)
        "........BBBBBBBB.BBBBBBBB......."  // 31
    )

    /** ドラゴン：角・口吻・翼・爪 */
    private val TEMPLATE_DRAGON = arrayOf(
        "....HH..................HH.......", //  0 角(H)
        ".....HHH................HHH.....", //  1
        "......HHH..............HHH......", //  2
        ".....HHHFFFFFFFFFFFFFFFHHHH.....", //  3 頭(F=鱗)
        "....HHHFFFFFFFFFFFFFFFFFFHHH....", //  4
        "....HHHFFFFFFFFFFFFFFFFFFHHH....", //  5
        "....HHFFFFFFEEFFFFFFEEFFFFFF....", //  6 目(E)
        "....HHFFFFFFEEFFFFFFEEFFFFFF....", //  7
        "....HHHFFFFFFFFFFFFFFFFFHHHH....", //  8
        ".....HHFFFFFFMMMFFFFFFFFFFF.....", //  9 口牙(M)
        "......HFFFFFFFFFFFFFFFFFF.......", // 10
        ".....HHFFFFFFFFFFFFFFFFFHH......", // 11
        "..AAAHHHHHHHHHHHHHHHHHHHHHAA....", // 12 翼(A)
        ".AAAAHHHHHHHHHHHHHHHHHHHHHHAAA..", // 13 翼広がる
        ".AAAA.SSSSSSSSSSSSSSSSSSSS.AAA..", // 14 胴体(S)
        ".AAAA.SSSSSSSSSSSSSSSSSSSS.AAA..", // 15
        ".GGAA.SSSSSSSSSSSSSSSSSSSS.AAGG.", // 16 前爪(G)
        ".GG...SSSSSSSSSSSSSSSSSSSS...GG.", // 17 爪分離
        "......SSSSSSSSSSSSSSSSSSSS......", // 18
        ".......PPPPPPPP.PPPPPPPP........", // 19 後脚(P)
        ".......PPPPPPPP.PPPPPPPP........", // 20
        ".......PPPPPPPP.PPPPPPPP........", // 21
        ".......PPPPPPPP.PPPPPPPP........", // 22
        ".......PPPPPPPP.PPPPPPPP........", // 23
        ".......PPPPPPPP.PPPPPPPP........", // 24
        ".......PPPPPPPP.PPPPPPPP........", // 25
        ".......PPPPPPPP.PPPPPPPP........", // 26
        ".......PPPPPPPP.PPPPPPPP........", // 27
        ".......PPPPPPPP.PPPPPPPP........", // 28
        ".......PPPPPPPP.PPPPPPPP........", // 29
        "......BBBBBBBBBB.BBBBBBBBBB.....", // 30 後爪(B)
        "......BBBBBBBBBB.BBBBBBBBBB....."  // 31
    )

    // ─────────────────────────────────────────────────────────────────────
    // 公開 API
    // ─────────────────────────────────────────────────────────────────────

    fun render(colors: Map<Region, Int>, type: TemplateType = TemplateType.HUMAN): Bitmap {
        val palette = PixelGridParser.PALETTE
        val tpl = templateFor(type)
        val pixels = IntArray(32 * 32)
        for (row in 0 until 32) {
            val line = tpl[row].padEnd(32, '.')  // 万一桁が足りなくても透明で補完
            for (col in 0 until 32) {
                val ch = line[col]
                val region = Region.values().firstOrNull { it.char == ch } ?: Region.TRANSPARENT
                val idx = colors[region] ?: 0
                pixels[row * 32 + col] = palette[idx.coerceIn(0, palette.size - 1)]
            }
        }
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, 32, 0, 0, 32, 32)
        return bmp
    }

    /** Gemma 出力テキストから色マッピングを抽出 */
    fun parseColors(text: String): Map<Region, Int> {
        val result = mutableMapOf<Region, Int>()
        val pattern = Regex("([A-Za-z]+)\\s*[=:]\\s*(\\d)")
        for (match in pattern.findAll(text)) {
            val key = match.groupValues[1].uppercase()
            val digit = match.groupValues[2].toInt()
            val region = when (key) {
                "HAIR" -> Region.HAIR
                "FACE", "SKIN" -> Region.FACE
                "EYE", "EYES" -> Region.EYE
                "MOUTH", "LIP", "LIPS" -> Region.MOUTH
                "SHIRT", "TOP", "BODY", "CLOTHING" -> Region.SHIRT
                "ARM", "ARMS", "SLEEVE" -> Region.ARM
                "HAND", "HANDS", "GLOVE", "GLOVES", "PAW", "PAWS", "CLAW" -> Region.HAND
                "PANTS", "LEG", "LEGS", "TROUSER", "TROUSERS", "SKIRT" -> Region.PANTS
                "FOOT", "FEET", "SHOE", "SHOES", "BOOT", "BOOTS", "THRUSTER" -> Region.FOOT
                else -> null
            }
            if (region != null && region !in result) result[region] = digit
        }
        return DEFAULTS + result
    }

    /** テンプレート種別ごとの色選定プロンプトを返す */
    fun colorPromptFor(type: TemplateType): String = buildString {
        append(BASE_PALETTE_PROMPT)
        when (type) {
            TemplateType.ANIMAL -> append(
                "\nThis is an ANIMAL (cat-like) character. Use these labels:\n" +
                "HAIR=fur/ear color, FACE=face fur, EYE=eye color, MOUTH=nose color,\n" +
                "SHIRT=body fur color, ARM=foreleg, HAND=paw, PANTS=hindleg, FOOT=hind paw.\n"
            )
            TemplateType.ROBOT -> append(
                "\nThis is a ROBOT character. Use these labels:\n" +
                "HAIR=antenna/head panel color, FACE=faceplate color, EYE=sensor color, MOUTH=speaker color,\n" +
                "SHIRT=chest plating, ARM=arm plating, HAND=claw, PANTS=leg plating, FOOT=thruster.\n"
            )
            TemplateType.DRAGON -> append(
                "\nThis is a DRAGON character. Use these labels:\n" +
                "HAIR=horn color, FACE=head/snout scale color, EYE=eye color, MOUTH=fang/mouth color,\n" +
                "SHIRT=body scale, ARM=wing membrane, HAND=front claw, PANTS=hindleg scale, FOOT=back claw.\n"
            )
            TemplateType.HUMAN -> append(
                "\nThis is a HUMAN character. Use these labels:\n" +
                "HAIR=hair, FACE=skin, EYE=eye color, MOUTH=lip color,\n" +
                "SHIRT=top/shirt, ARM=sleeve, HAND=hand skin, PANTS=pants, FOOT=shoes.\n"
            )
        }
        append(OUTPUT_FORMAT_PROMPT)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private
    // ─────────────────────────────────────────────────────────────────────

    private fun templateFor(type: TemplateType) = when (type) {
        TemplateType.HUMAN -> TEMPLATE_HUMAN
        TemplateType.ANIMAL -> TEMPLATE_ANIMAL
        TemplateType.ROBOT -> TEMPLATE_ROBOT
        TemplateType.DRAGON -> TEMPLATE_DRAGON
    }

    private const val BASE_PALETTE_PROMPT =
        "You are a pixel art color picker.\n" +
        "Choose ONE digit (0-9) per body part from this palette:\n" +
        "0=transparent  1=black  2=white  3=skin(peach)  4=brown\n" +
        "5=yellow  6=red  7=blue  8=green  9=gray\n"

    private const val OUTPUT_FORMAT_PROMPT =
        "\nOutput EXACTLY 9 lines, no extra text:\n" +
        "HAIR=<digit>\n" +
        "FACE=<digit>\n" +
        "EYE=<digit>\n" +
        "MOUTH=<digit>\n" +
        "SHIRT=<digit>\n" +
        "ARM=<digit>\n" +
        "HAND=<digit>\n" +
        "PANTS=<digit>\n" +
        "FOOT=<digit>\n"

    private val DEFAULTS = mapOf(
        Region.TRANSPARENT to 0,
        Region.HAIR to 4,
        Region.FACE to 3,
        Region.EYE to 1,
        Region.MOUTH to 6,
        Region.SHIRT to 7,
        Region.ARM to 3,
        Region.HAND to 3,
        Region.PANTS to 4,
        Region.FOOT to 1
    )
}
