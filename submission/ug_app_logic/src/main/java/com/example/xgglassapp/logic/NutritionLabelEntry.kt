package com.example.xgglassapp.logic

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.ListContent
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.universalglasses.appcontract.AIApiSettings
import com.universalglasses.appcontract.UniversalAppContext
import com.universalglasses.appcontract.UniversalAppEntrySimple
import com.universalglasses.appcontract.UniversalCommand
import com.universalglasses.appcontract.UserSettingField
import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.DisplayOptions
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * Simulator-first nutrition label assistant.
 *
 * This keeps the course project moving even when the developer only has iOS
 * hardware: the Android emulator acts as a virtual glasses host, while the
 * business logic remains the same as the eventual real-device flow.
 */
open class NutritionLabelEntry : UniversalAppEntrySimple {
    override val id: String = "nutrition_label_demo"
    override val displayName: String = "Nutrition Label Assistant"

    override fun userSettings(): List<UserSettingField> = AIApiSettings.fields(
        defaultBaseUrl = "https://app.manifest.build/v1/",
        defaultModel = "auto",
    )

    override fun commands(): List<UniversalCommand> = listOf(
        ScanBarcodeCommand(),
        AnalyzeNutritionCommand(),
    )
}

data class NutritionResult(
    val productName: String = "Unknown",
    val category: String = "food",
    val grade: String = "?",
    val tags: List<String> = emptyList(),
    val warning: String = "",
    val packageSummary: String = "",
    val displayText: String = "",
)

data class RawNutritionData(
    val productName: String = "",
    val category: String = "",
    val barcode: String = "",
    val quantityText: String = "",
    val servingSizeText: String = "",
    val sugarPer100ml: Double? = null,
    val sugarPer100g: Double? = null,
    val caloriesPer100ml: Double? = null,
    val caloriesPer100g: Double? = null,
    val saturatedFatPer100g: Double? = null,
    val sodiumPer100g: Double? = null,
    val proteinPer100g: Double? = null,
    val fiberPer100g: Double? = null,
)

private data class QuantityInfo(
    val amount: Double,
    val unit: String,
)

private data class AiClientBundle(
    val client: OpenAI,
    val model: String,
)

private data class BarcodeScanResult(
    val barcode: String?,
    val productHint: String = "",
)

private fun String.normalizedProductName(): String {
    val normalized = trim().trim('"', '\'', '`')
    return normalized
}

private fun RawNutritionData.isUnreadableResult(): Boolean {
    return productName.normalizedProductName().equals("Unreadable", ignoreCase = true)
}

private fun RawNutritionData.hasAnyNutritionValue(): Boolean {
    return listOf(
        sugarPer100ml,
        sugarPer100g,
        caloriesPer100ml,
        caloriesPer100g,
        saturatedFatPer100g,
        sodiumPer100g,
        proteinPer100g,
        fiberPer100g,
    ).any { it != null }
}

private fun firstNonBlank(vararg values: String): String {
    return values.firstOrNull { it.isNotBlank() }.orEmpty()
}

private fun isDrinkCategory(category: String): Boolean {
    val normalized = category.lowercase(Locale.US)
    return listOf(
        "drink", "beverage", "饮料", "juice", "soda", "tea", "coffee", "water", "milk"
    ).any { normalized.contains(it) }
}

private fun normalizeCategory(raw: String): String {
    val normalized = raw.lowercase(Locale.US)
    return when {
        isDrinkCategory(normalized) -> "drink"
        normalized.contains("snack") || normalized.contains("chips") || normalized.contains("cookie") || normalized.contains("candy") -> "snack"
        else -> "food"
    }
}

private fun normalizeBarcode(raw: String): String? {
    val digits = raw.filter { it.isDigit() }
    return digits.takeIf { it.length in 8..14 }
}

private fun isValidEAN13(code: String): Boolean {
    if (code.length != 13 || !code.all { it.isDigit() }) return false
    val expected = code.dropLast(1).mapIndexed { index, c ->
        val digit = c.digitToInt()
        if (index % 2 == 0) digit else digit * 3
    }.sum()
    val checkDigit = (10 - (expected % 10)) % 10
    return checkDigit == code.last().digitToInt()
}

private fun isSupportedBarcode(code: String): Boolean {
    return when (code.length) {
        13 -> isValidEAN13(code)
        8, 12, 14 -> code.all { it.isDigit() }
        else -> false
    }
}

private fun parseQuantityText(text: String): QuantityInfo? {
    val normalized = text.trim()
        .replace("毫升", "ml", ignoreCase = true)
        .replace("升", "l", ignoreCase = true)
        .replace("千克", "kg", ignoreCase = true)
        .replace("克", "g", ignoreCase = true)
        .replace("×", "x")
        .lowercase(Locale.US)

    val multiPack = Regex("""(\d+(?:\.\d+)?)\s*x\s*(\d+(?:\.\d+)?)\s*(ml|l|g|kg)""")
        .find(normalized)
    if (multiPack != null) {
        val count = multiPack.groupValues[1].toDoubleOrNull() ?: return null
        val each = multiPack.groupValues[2].toDoubleOrNull() ?: return null
        val unit = multiPack.groupValues[3]
        return canonicalQuantity(count * each, unit)
    }

    val single = Regex("""(\d+(?:\.\d+)?)\s*(ml|l|g|kg)""").find(normalized) ?: return null
    val value = single.groupValues[1].toDoubleOrNull() ?: return null
    val unit = single.groupValues[2]
    return canonicalQuantity(value, unit)
}

private fun canonicalQuantity(value: Double, unit: String): QuantityInfo {
    return when (unit.lowercase(Locale.US)) {
        "l" -> QuantityInfo(value * 1000.0, "ml")
        "kg" -> QuantityInfo(value * 1000.0, "g")
        else -> QuantityInfo(value, unit.lowercase(Locale.US))
    }
}

private fun formatCompactNumber(value: Double): String {
    val rounded = (value * 10.0).roundToInt() / 10.0
    return if (rounded % 1.0 == 0.0) {
        rounded.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", rounded)
    }
}

private fun computePackageSugarGrams(data: RawNutritionData): Double? {
    val quantity = parseQuantityText(data.quantityText) ?: return null
    return when {
        isDrinkCategory(data.category) && quantity.unit == "ml" && data.sugarPer100ml != null ->
            data.sugarPer100ml * quantity.amount / 100.0
        !isDrinkCategory(data.category) && quantity.unit == "g" && data.sugarPer100g != null ->
            data.sugarPer100g * quantity.amount / 100.0
        else -> null
    }
}

private fun computePackageCalories(data: RawNutritionData): Double? {
    val quantity = parseQuantityText(data.quantityText) ?: return null
    return when {
        isDrinkCategory(data.category) && quantity.unit == "ml" && data.caloriesPer100ml != null ->
            data.caloriesPer100ml * quantity.amount / 100.0
        !isDrinkCategory(data.category) && quantity.unit == "g" && data.caloriesPer100g != null ->
            data.caloriesPer100g * quantity.amount / 100.0
        else -> null
    }
}

private fun buildPackageSummary(data: RawNutritionData): String {
    val sugar = computePackageSugarGrams(data)
    val calories = computePackageCalories(data)
    val prefix = if (isDrinkCategory(data.category)) "整瓶" else "整包"
    return when {
        sugar != null -> "$prefix 糖≈${formatCompactNumber(sugar)}g"
        calories != null -> "$prefix ≈${formatCompactNumber(calories)}kcal"
        else -> ""
    }
}

private fun buildAiClient(ctx: UniversalAppContext): AiClientBundle {
    val rawBaseUrl = AIApiSettings.baseUrl(ctx.settings)
    val baseUrl = normalizeAiBaseUrl(rawBaseUrl)
    val apiKey = AIApiSettings.apiKey(ctx.settings)
    val apiModel = AIApiSettings.model(ctx.settings)

    require(baseUrl.isNotBlank()) {
        "API Base URL is not configured. Please fill in Settings and Apply."
    }
    require(apiKey.isNotBlank()) {
        "API Key is not configured. Please fill in Settings and Apply."
    }
    require(apiModel.isNotBlank()) {
        "Model is not configured. Please fill in Settings and Apply."
    }

    if (baseUrl != rawBaseUrl.trim()) {
        ctx.log("Normalized API Base URL to $baseUrl")
    }

    val client = OpenAI(
        token = apiKey,
        host = OpenAIHost(baseUrl = baseUrl),
        timeout = Timeout(request = 120.seconds, connect = 30.seconds, socket = 120.seconds),
    )
    ctx.log("AI client ready: model=$apiModel, timeout=120s")
    return AiClientBundle(client = client, model = apiModel)
}

private fun buildVisionRequest(prompt: String, model: String, jpegBytes: ByteArray): ChatCompletionRequest {
    val b64 = Base64.getEncoder().encodeToString(jpegBytes)
    return ChatCompletionRequest(
        model = ModelId(model),
        messages = listOf(
            ChatMessage(
                role = ChatRole.User,
                messageContent = ListContent(
                    listOf(
                        TextPart(text = prompt),
                        ImagePart(url = "data:image/jpeg;base64,$b64"),
                    )
                ),
            )
        )
    )
}

private suspend fun runVisionPrompt(ai: AiClientBundle, prompt: String, jpegBytes: ByteArray): String {
    val req = buildVisionRequest(prompt, ai.model, jpegBytes)
    return ai.client.chatCompletion(req).choices.firstOrNull()?.message?.content.orEmpty()
}

private fun buildBarcodePrompt(): String = """
You are a barcode reader for packaged food and drink products.

Return ONLY a JSON object (no markdown, no explanation):

{
  "barcode": "digits only or null",
  "productName": "visible product name or empty string"
}

Rules:
- Look for a retail barcode such as EAN-13, UPC-A, UPC-E, or EAN-8.
- Return digits only for the barcode, with no spaces or separators.
- If multiple barcodes are visible, choose the clearest consumer product barcode.
- Do not guess missing digits.
- If no barcode is clearly visible, return null for barcode.
- Barcode is the main target; productName is optional.
""".trimIndent()

private fun parseBarcodeResponse(rawText: String): BarcodeScanResult {
    val jsonStr = extractJsonBlock(rawText)
    if (jsonStr != null) {
        runCatching {
            val obj = JSONObject(jsonStr)
            return BarcodeScanResult(
                barcode = normalizeBarcode(obj.optString("barcode", "")),
                productHint = obj.optString("productName", "").normalizedProductName(),
            )
        }
    }

    val fallback = Regex("""\b\d{8,14}\b""").find(rawText)?.value
    return BarcodeScanResult(
        barcode = normalizeBarcode(fallback.orEmpty()),
        productHint = "",
    )
}

private fun optJsonDouble(obj: JSONObject, key: String): Double? {
    if (!obj.has(key) || obj.isNull(key)) return null
    val value = obj.opt(key)
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}

private fun normalizeSodiumToMg(value: Double?): Double? {
    return value?.let { if (it <= 10.0) it * 1000.0 else it }
}

private fun saltToSodiumMg(saltGrams: Double?): Double? {
    return saltGrams?.let { it * 393.0 }
}

private fun parseOpenFoodFactsProduct(barcode: String, responseText: String): RawNutritionData? {
    val root = JSONObject(responseText)
    if (root.optInt("status", 0) != 1) return null

    val product = root.optJSONObject("product") ?: return null
    val nutriments = product.optJSONObject("nutriments") ?: JSONObject()

    val sugar100ml = optJsonDouble(nutriments, "sugars_100ml")
    val sugar100g = optJsonDouble(nutriments, "sugars_100g")
    val calories100ml = optJsonDouble(nutriments, "energy-kcal_100ml")
    val calories100g = optJsonDouble(nutriments, "energy-kcal_100g")
    val sodium100g = normalizeSodiumToMg(optJsonDouble(nutriments, "sodium_100g"))
        ?: saltToSodiumMg(optJsonDouble(nutriments, "salt_100g"))

    val categoryText = listOf(
        product.optString("categories", ""),
        product.opt("categories_tags")?.toString().orEmpty(),
        product.optString("pnns_groups_1", ""),
        product.optString("pnns_groups_2", ""),
    ).joinToString(" ")

    val category = when {
        sugar100ml != null || calories100ml != null -> "drink"
        else -> normalizeCategory(categoryText)
    }

    val effectiveSugar100ml = if (category == "drink") sugar100ml ?: sugar100g else sugar100ml
    val effectiveCalories100ml = if (category == "drink") calories100ml ?: calories100g else calories100ml

    return RawNutritionData(
        productName = firstNonBlank(
            product.optString("product_name", ""),
            product.optString("product_name_en", ""),
            product.optString("generic_name", ""),
            product.optString("brands", ""),
        ).normalizedProductName(),
        category = category,
        barcode = barcode,
        quantityText = product.optString("quantity", ""),
        servingSizeText = product.optString("serving_size", ""),
        sugarPer100ml = effectiveSugar100ml,
        sugarPer100g = sugar100g,
        caloriesPer100ml = effectiveCalories100ml,
        caloriesPer100g = calories100g,
        saturatedFatPer100g = optJsonDouble(nutriments, "saturated-fat_100g"),
        sodiumPer100g = sodium100g,
        proteinPer100g = optJsonDouble(nutriments, "proteins_100g"),
        fiberPer100g = optJsonDouble(nutriments, "fiber_100g"),
    )
}

private suspend fun lookupOpenFoodFactsByBarcode(barcode: String): Result<RawNutritionData> = withContext(Dispatchers.IO) {
    runCatching {
        val url = URL("https://world.openfoodfacts.org/api/v2/product/$barcode.json")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "xg-glass-nutrition-demo/1.0")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (status !in 200..299) {
                throw IllegalStateException("OFF HTTP $status")
            }

            parseOpenFoodFactsProduct(barcode, responseBody)
                ?: throw IllegalStateException("Product not found in OFF")
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Fallback: search OFF for similar products in the same category
 * and average their nutrition values as a rough reference.
 */
private suspend fun lookupSimilarByCategory(category: String): RawNutritionData? = withContext(Dispatchers.IO) {
    runCatching {
        val keyword = category.split(" ", ",", ";").firstOrNull { it.length > 2 } ?: category
        val encoded = java.net.URLEncoder.encode(keyword.lowercase(), "UTF-8")
        val url = URL("https://world.openfoodfacts.org/cgi/search.pl?search_terms=$encoded&json=true&page_size=5&fields=product_name,nutriments,categories")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "xg-glass-nutrition-demo/1.0")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) return@runCatching null
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            val root = JSONObject(body)
            val products = root.optJSONArray("products") ?: return@runCatching null
            val results = mutableListOf<RawNutritionData>()

            for (i in 0 until products.length()) {
                val p = products.optJSONObject(i) ?: continue
                val nutr = p.optJSONObject("nutriments") ?: continue

                val sugar100ml = optJsonDouble(nutr, "sugars_100ml")
                val sugar100g = optJsonDouble(nutr, "sugars_100g")
                val cal100ml = optJsonDouble(nutr, "energy-kcal_100ml")
                val cal100g = optJsonDouble(nutr, "energy-kcal_100g")

                // Only include if at least some nutrition data exists
                if (listOf(sugar100ml, sugar100g, cal100ml, cal100g).all { it == null }) continue

                val catText = listOf(
                    p.optString("categories", ""),
                ).joinToString(" ")
                val isDrink = sugar100ml != null || cal100ml != null

                results.add(RawNutritionData(
                    productName = p.optString("product_name", "").normalizedProductName(),
                    category = if (isDrink) "drink" else normalizeCategory(catText),
                    barcode = "",
                    quantityText = p.optString("quantity", ""),
                    sugarPer100ml = sugar100ml,
                    sugarPer100g = sugar100g,
                    caloriesPer100ml = cal100ml,
                    caloriesPer100g = cal100g,
                    saturatedFatPer100g = optJsonDouble(nutr, "saturated-fat_100g"),
                    sodiumPer100g = normalizeSodiumToMg(optJsonDouble(nutr, "sodium_100g"))
                        ?: saltToSodiumMg(optJsonDouble(nutr, "salt_100g")),
                    proteinPer100g = optJsonDouble(nutr, "proteins_100g"),
                    fiberPer100g = optJsonDouble(nutr, "fiber_100g"),
                ))
            }

            if (results.isEmpty()) return@runCatching null

            // Average the results for a rough category-level estimate
            val drinkResults = results.filter { isDrinkCategory(it.category) }
            val foodResults = results.filter { !isDrinkCategory(it.category) }
            val primary = if (drinkResults.isNotEmpty()) drinkResults else foodResults
            if (primary.isEmpty()) return@runCatching null

            val avgSugar100ml = primary.mapNotNull { it.sugarPer100ml }.takeIf { it.isNotEmpty() }?.average()
            val avgSugar100g = primary.mapNotNull { it.sugarPer100g }.takeIf { it.isNotEmpty() }?.average()
            val avgCal100ml = primary.mapNotNull { it.caloriesPer100ml }.takeIf { it.isNotEmpty() }?.average()
            val avgCal100g = primary.mapNotNull { it.caloriesPer100g }.takeIf { it.isNotEmpty() }?.average()
            val avgSatFat = primary.mapNotNull { it.saturatedFatPer100g }.takeIf { it.isNotEmpty() }?.average()
            val avgSodium = primary.mapNotNull { it.sodiumPer100g }.takeIf { it.isNotEmpty() }?.average()
            val avgProtein = primary.mapNotNull { it.proteinPer100g }.takeIf { it.isNotEmpty() }?.average()
            val avgFiber = primary.mapNotNull { it.fiberPer100g }.takeIf { it.isNotEmpty() }?.average()

            RawNutritionData(
                productName = "Category: $category",
                category = primary.first().category,
                barcode = "",
                quantityText = primary.firstOrNull { it.quantityText.isNotBlank() }?.quantityText.orEmpty(),
                sugarPer100ml = avgSugar100ml,
                sugarPer100g = avgSugar100g,
                caloriesPer100ml = avgCal100ml,
                caloriesPer100g = avgCal100g,
                saturatedFatPer100g = avgSatFat,
                sodiumPer100g = avgSodium,
                proteinPer100g = avgProtein,
                fiberPer100g = avgFiber,
            )
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

private fun normalizeAiBaseUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return trimmed

    return runCatching {
        val uri = URI(trimmed)
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) return@runCatching trimmed

        val normalizedPath = when (val p = uri.path.orEmpty().trimEnd('/')) {
            "", "/" -> "/v1/"
            "/chat/completions", "/v1/chat/completions" -> "/v1/"
            else -> if (p.isBlank()) "/v1/" else "$p/"
        }

        URI(
            uri.scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            normalizedPath,
            uri.query,
            uri.fragment,
        ).toString()
    }.getOrElse { trimmed }
}

private fun noDataResult(productName: String, category: String, message: String): NutritionResult {
    return NutritionResult(
        productName = productName.ifBlank { "Unknown" },
        category = category,
        grade = "?",
        tags = emptyList(),
        warning = message,
        displayText = "Grade ?\n$message\nTry again",
    )
}

private fun unreadableCaptureResult(categoryHint: String = "food"): NutritionResult {
    val message = "Move closer to the nutrition label"
    return NutritionResult(
        productName = "Unreadable",
        category = categoryHint,
        grade = "?",
        tags = emptyList(),
        warning = message,
        displayText = "Grade ?\n$message\nNot barcode only",
    )
}

fun gradeDrink(data: RawNutritionData): NutritionResult {
    if (data.isUnreadableResult()) {
        return unreadableCaptureResult("drink")
    }

    val sugar = data.sugarPer100ml ?: return noDataResult(
        data.productName,
        "drink",
        "Capture the nutrition label, not only the front or barcode"
    )

    val grade = when {
        sugar <= 0.0 -> "A"
        sugar <= 2.5 -> "B"
        sugar <= 5.0 -> "C"
        sugar <= 10.0 -> "D"
        else -> "E"
    }

    val tags = mutableListOf<String>()
    if (sugar > 5.0) tags.add("高糖")
    if (sugar <= 2.5 && sugar > 0) tags.add("低糖")
    if (sugar <= 0.0) tags.add("无糖")

    val warning = when (grade) {
        "D", "E" -> if (sugar > 8.0) "减脂/控糖期不推荐" else "建议适量饮用"
        "C" -> "可偶尔饮用"
        else -> ""
    }

    val packageSummary = buildPackageSummary(data)
    val packageSugar = computePackageSugarGrams(data)
    val sugarCubes = if (packageSugar != null && packageSugar > 0) {
        val cubes = (packageSugar / 4.0).toInt()
        if (cubes > 0) "约${cubes}块方糖/瓶" else ""
    } else if (sugar > 0 && data.productName.isNotBlank()) {
        val totalSugar = sugar * 5.0
        val cubes = (totalSugar / 4.0).toInt()
        if (cubes > 0) "约${cubes}块方糖/瓶" else ""
    } else ""

    val displayParts = mutableListOf<String>()
    displayParts.add("Grade $grade")
    if (tags.isNotEmpty()) displayParts.add(tags.joinToString(" "))
    if (packageSummary.isNotBlank()) displayParts.add(packageSummary)
    if (sugarCubes.isNotBlank()) displayParts.add(sugarCubes)
    if (warning.isNotBlank()) displayParts.add(warning)

    return NutritionResult(
        productName = data.productName,
        category = "drink",
        grade = grade,
        tags = tags,
        warning = warning,
        packageSummary = packageSummary,
        displayText = displayParts.joinToString("\n"),
    )
}

fun gradeFood(data: RawNutritionData): NutritionResult {
    if (data.isUnreadableResult()) {
        return unreadableCaptureResult("food")
    }

    val hasAnyNutritionData = listOf(
        data.sugarPer100g,
        data.sodiumPer100g,
        data.saturatedFatPer100g,
        data.caloriesPer100g,
        data.proteinPer100g,
        data.fiberPer100g,
    ).any { it != null }

    if (!hasAnyNutritionData) {
        return noDataResult(
            data.productName,
            "food",
            "Capture the nutrition label, not only the front or barcode"
        )
    }

    val sugar = data.sugarPer100g ?: 0.0
    val sodium = data.sodiumPer100g ?: 0.0
    val satFat = data.saturatedFatPer100g ?: 0.0
    val calories = data.caloriesPer100g ?: 0.0
    val protein = data.proteinPer100g ?: 0.0
    val fiber = data.fiberPer100g ?: 0.0

    var penalty = 0.0
    penalty += when {
        sugar > 15.0 -> 3.0
        sugar > 10.0 -> 2.0
        sugar > 5.0 -> 1.0
        else -> 0.0
    }
    penalty += when {
        sodium > 600.0 -> 3.0
        sodium > 400.0 -> 2.0
        sodium > 200.0 -> 1.0
        else -> 0.0
    }
    penalty += when {
        satFat > 5.0 -> 3.0
        satFat > 3.0 -> 2.0
        satFat > 1.5 -> 1.0
        else -> 0.0
    }
    penalty += when {
        calories > 500.0 -> 2.0
        calories > 350.0 -> 1.0
        else -> 0.0
    }

    var bonus = 0.0
    bonus += when {
        protein > 10.0 -> 2.0
        protein > 5.0 -> 1.0
        else -> 0.0
    }
    bonus += when {
        fiber > 6.0 -> 2.0
        fiber > 3.0 -> 1.0
        else -> 0.0
    }

    val score = penalty - bonus
    val grade = when {
        score <= 0.0 -> "A"
        score <= 2.0 -> "B"
        score <= 5.0 -> "C"
        score <= 8.0 -> "D"
        else -> "E"
    }

    val tags = mutableListOf<String>()
    if (sugar > 10.0) tags.add("高糖")
    if (sodium > 400.0) tags.add("高钠")
    if (satFat > 3.0) tags.add("高脂")
    if (protein > 10.0) tags.add("高蛋白")
    if (fiber > 3.0) tags.add("高纤维")
    if (sugar <= 5.0 && sodium <= 200 && satFat <= 1.5) tags.add("较健康")

    val warning = when (grade) {
        "D", "E" -> "减脂期不推荐"
        "C" -> "可偶尔食用"
        else -> ""
    }

    val packageSummary = buildPackageSummary(data)
    val displayParts = mutableListOf<String>()
    displayParts.add("Grade $grade")
    if (tags.isNotEmpty()) displayParts.add(tags.joinToString(" "))
    if (packageSummary.isNotBlank()) displayParts.add(packageSummary)
    if (warning.isNotBlank()) displayParts.add(warning)

    return NutritionResult(
        productName = data.productName,
        category = "food",
        grade = grade,
        tags = tags,
        warning = warning,
        packageSummary = packageSummary,
        displayText = displayParts.joinToString("\n"),
    )
}

fun gradeNutrition(data: RawNutritionData): NutritionResult {
    return when (data.category.lowercase()) {
        "drink", "beverage", "饮料" -> gradeDrink(data)
        else -> gradeFood(data)
    }
}

fun parseNutritionResponse(rawText: String): RawNutritionData {
    if (rawText.isBlank() || rawText == "No response from AI") {
        return RawNutritionData()
    }

    val jsonStr = extractJsonBlock(rawText)
    if (jsonStr != null) {
        return try {
            parseFromJson(jsonStr)
        } catch (_: Exception) {
            extractFromPlainText(rawText)
        }
    }
    return extractFromPlainText(rawText)
}

private fun parseFromJson(jsonStr: String): RawNutritionData {
    val obj = JSONObject(jsonStr)
    fun optDouble(key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.optDouble(key, Double.NaN).takeIf { !it.isNaN() }
    }
    return RawNutritionData(
        productName = obj.optString("productName", "").normalizedProductName(),
        category = obj.optString("category", ""),
        barcode = obj.optString("barcode", ""),
        quantityText = obj.optString("quantityText", ""),
        servingSizeText = obj.optString("servingSizeText", ""),
        sugarPer100ml = optDouble("sugarPer100ml"),
        sugarPer100g = optDouble("sugarPer100g"),
        caloriesPer100ml = optDouble("caloriesPer100ml"),
        caloriesPer100g = optDouble("caloriesPer100g"),
        saturatedFatPer100g = optDouble("saturatedFatPer100g"),
        sodiumPer100g = optDouble("sodiumPer100g"),
        proteinPer100g = optDouble("proteinPer100g"),
        fiberPer100g = optDouble("fiberPer100g"),
    )
}

private fun extractJsonBlock(text: String): String? {
    val codeBlockRegex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    val match = codeBlockRegex.find(text)
    if (match != null) return match.groupValues[1].trim()

    val jsonRegex = Regex("""\{[\s\S]*?\"productName\"[\s\S]*?\}""")
    val jsonMatch = jsonRegex.find(text)
    if (jsonMatch != null) return jsonMatch.value

    val anyJsonRegex = Regex("""\{[\s\S]*?\}""")
    val anyMatch = anyJsonRegex.find(text)
    if (anyMatch != null) {
        return try {
            JSONObject(anyMatch.value)
            anyMatch.value
        } catch (_: Exception) {
            null
        }
    }

    return null
}

private fun extractFromPlainText(text: String): RawNutritionData {
    fun extractDouble(regex: Regex, source: String): Double? {
        val m = regex.find(source) ?: return null
        return m.groupValues.getOrNull(1)?.toDoubleOrNull()
    }

    fun extractString(regex: Regex, source: String): String {
        return regex.find(source)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    return RawNutritionData(
        productName = extractString(Regex("(?:Product|产品|Name|名称)[：:]\\s*(.+)", RegexOption.IGNORE_CASE), text).normalizedProductName(),
        category = extractString(Regex("(?:Category|类别|Type|类型)[：:]\\s*(.+)", RegexOption.IGNORE_CASE), text),
        barcode = extractString(Regex("(?:Barcode|条形码)[：:]\\s*(\\d{8,14})", RegexOption.IGNORE_CASE), text),
        sugarPer100ml = extractDouble(Regex("""[Ss]ugar.*?(\d+\.?\d*)\s*g\s*(?:per|/)\s*100\s*m[lL]"""), text),
        sugarPer100g = extractDouble(Regex("""[Ss]ugar.*?(\d+\.?\d*)\s*g\s*(?:per|/)\s*100\s*g"""), text),
        caloriesPer100ml = extractDouble(Regex("""[Cc]alor(?:y|ies).*?(\d+\.?\d*)\s*kcal\s*(?:per|/)\s*100\s*m[lL]"""), text),
        caloriesPer100g = extractDouble(Regex("""[Cc]alor(?:y|ies).*?(\d+\.?\d*)\s*kcal\s*(?:per|/)\s*100\s*g"""), text),
        saturatedFatPer100g = extractDouble(Regex("""[Ss]aturat.*?[Ff]at.*?(\d+\.?\d*)\s*g"""), text),
        sodiumPer100g = extractDouble(Regex("""[Ss]odium.*?(\d+\.?\d*)\s*mg"""), text),
        proteinPer100g = extractDouble(Regex("""[Pp]rotein.*?(\d+\.?\d*)\s*g"""), text),
        fiberPer100g = extractDouble(Regex("""[Ff]iber.*?(\d+\.?\d*)\s*g"""), text),
    )
}

private fun tryZxingDecode(jpegBytes: ByteArray): String? {
    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
    return try {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val source = RGBLuminanceSource(w, h, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A,
                BarcodeFormat.UPC_E,
            ),
            DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE,
        )

        val reader = MultiFormatReader()
        reader.setHints(hints)
        val zxResult: com.google.zxing.Result = reader.decode(binaryBitmap)
        zxResult.text
    } catch (_: Exception) {
        null
    } finally {
        bitmap.recycle()
    }
}

private class ScanBarcodeCommand : UniversalCommand {
    override val id: String = "scan_barcode"
    override val title: String = "Scan Barcode"

    override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
        ctx.log("Capturing barcode (1920x1080)...")
        ctx.client.display("📦 Capturing...", DisplayOptions(force = true))

        // Higher resolution helps ZXing decode smaller/angled barcodes
        val captureResult = try {
            ctx.client.capturePhoto(
                CaptureOptions(quality = 85, targetWidth = 1920, targetHeight = 1080, timeoutMs = 45_000)
            )
        } catch (e: Exception) {
            ctx.log("Barcode capture failed: ${e.message}")
            ctx.client.display("No barcode\nCapture failed\nTry again", DisplayOptions(force = true))
            return Result.failure(e)
        }

        val img = captureResult.getOrNull()
        if (img == null) {
            val error = captureResult.exceptionOrNull() ?: IllegalStateException("Barcode capture failed")
            ctx.log("Barcode capture failed: ${error.message}")
            ctx.client.display("No barcode\nCapture failed\nTry again", DisplayOptions(force = true))
            return Result.failure(error)
        }

        ctx.onCapturedImage?.invoke(img)
        ctx.log("Captured barcode image: ${img.jpegBytes.size} bytes")

        // --- Hybrid: ZXing first (algorithmic, fast), LLM fallback ---
        var barcode: String? = null
        var productHint: String = ""

        // Step 1: Try ZXing (native barcode decoder)
        ctx.log("Trying ZXing barcode decoder...")
        val zxingResult = tryZxingDecode(img.jpegBytes)
        if (zxingResult != null) {
            ctx.log("ZXing decoded barcode: $zxingResult")
            barcode = normalizeBarcode(zxingResult)
            if (barcode != null && isSupportedBarcode(barcode)) {
                ctx.log("ZXing barcode valid: $barcode")
            } else {
                ctx.log("ZXing result invalid, will try LLM")
                barcode = null
            }
        } else {
            ctx.log("ZXing found no barcode")
        }

        // Step 2: Fall back to Vision LLM if ZXing failed
        if (barcode == null) {
            val ai = try {
                buildAiClient(ctx)
            } catch (e: IllegalArgumentException) {
                ctx.log("ERROR: ${e.message}")
                return Result.failure(e)
            }

            ctx.log("Falling back to Vision LLM for barcode reading...")
            ctx.client.display("🔍 Reading with AI...", DisplayOptions(force = true))

            val rawText = try {
                runVisionPrompt(ai, buildBarcodePrompt(), img.jpegBytes)
            } catch (e: Exception) {
                ctx.log("Barcode AI call failed: ${e.message}")
                ctx.client.display("No barcode\nAI read failed\nTry again", DisplayOptions(force = true))
                return Result.failure(e)
            }

            if (rawText.isBlank()) {
                ctx.log("Barcode AI returned empty response")
                return ctx.client.display("No barcode\nEmpty response\nTry again", DisplayOptions(force = true))
            }

            ctx.log("Barcode AI response: ${rawText.take(200)}...")
            val barcodeResult = parseBarcodeResponse(rawText)
            barcode = barcodeResult.barcode
            productHint = barcodeResult.productHint
        }

        if (barcode.isNullOrBlank()) {
            ctx.log("No barcode found (ZXing + LLM both failed)")
            return ctx.client.display("No barcode found\nCenter barcode\nTry again", DisplayOptions(force = true))
        }

        if (!isSupportedBarcode(barcode)) {
            ctx.log("Invalid barcode candidate: $barcode")
            return ctx.client.display("Invalid barcode\nTry again", DisplayOptions(force = true))
        }

        ctx.log("Barcode recognized: $barcode")
        ctx.log("Looking up Open Food Facts...")
        ctx.client.display("🌐 Looking up...", DisplayOptions(force = true))

        val rawData = try {
            lookupOpenFoodFactsByBarcode(barcode).getOrThrow()
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            ctx.log("Barcode lookup failed: $message")
            // Try category-level search as fallback (use productHint from LLM if available)
            val searchTerm = productHint.ifBlank { "food" }
            ctx.log("Trying category search with term: $searchTerm")
            ctx.client.display("🔍 Category search...", DisplayOptions(force = true))
            val similar = lookupSimilarByCategory(searchTerm)
            if (similar != null && similar.hasAnyNutritionValue()) {
                ctx.log("Category fallback (lookup failure): found ${similar.productName}")
                val result = gradeNutrition(similar)
                val displayText = result.displayText.ifBlank { "Category avg\nGrade ?" }
                    .replace("整瓶", "同类参考·整瓶")
                    .replace("整包", "同类参考·整包")
                return ctx.client.display(displayText.take(180), DisplayOptions(force = true))
            }
            val displayText = when {
                message.contains("Product not found", ignoreCase = true) -> "Product not found\nTry label scan"
                message.contains("HTTP", ignoreCase = true) -> "Lookup failed\nCheck network"
                else -> "Lookup failed\nTry label scan"
            }
            return ctx.client.display(displayText, DisplayOptions(force = true))
        }

        if (rawData.isUnreadableResult()) {
            ctx.log("OFF returned unreadable product data for barcode $barcode")
            return ctx.client.display("Data unreadable\nTry label scan", DisplayOptions(force = true))
        }

        if (isDrinkCategory(rawData.category) && rawData.sugarPer100ml == null) {
            ctx.log(
                "OFF found product but missing drink nutriments for barcode $barcode | " +
                    "Page may still show category comparison data, not product nutriments | " +
                    "Product: ${rawData.productName.ifBlank { "Unknown" }} | " +
                    "Category: ${rawData.category}"
            )
            // Try category-level similar product search as fallback
            val catName = rawData.productName.ifBlank { rawData.category }
            ctx.client.display("🔍 Category search...", DisplayOptions(force = true))
            val similar = lookupSimilarByCategory(catName)
            if (similar != null && (similar.sugarPer100ml != null || similar.hasAnyNutritionValue())) {
                ctx.log("Category fallback: found ${similar.productName} | sugar=${similar.sugarPer100ml} | cal=${similar.caloriesPer100ml}")
                val result = gradeNutrition(similar)
                ctx.log("Category result: ${result.productName} | Grade ${result.grade}")
                val displayText = result.displayText.ifBlank { "Category avg\nGrade ?" }
                    .replace("整瓶", "同类参考·整瓶")
                    .replace("整包", "同类参考·整包")
                return ctx.client.display(displayText.take(180), DisplayOptions(force = true))
            }
            ctx.log("Category fallback returned insufficient data")
            return ctx.client.display("Product found\nNo nutrition data\nTry label scan", DisplayOptions(force = true))
        }

        if (!isDrinkCategory(rawData.category) && !rawData.hasAnyNutritionValue()) {
            ctx.log(
                "OFF found product but missing key nutriments for barcode $barcode | " +
                    "Page may still show category comparison data, not product nutriments | " +
                    "Product: ${rawData.productName.ifBlank { "Unknown" }} | " +
                    "Category: ${rawData.category}"
            )
            // Try category-level similar product search as fallback
            val catName = rawData.productName.ifBlank { rawData.category }
            ctx.client.display("🔍 Category search...", DisplayOptions(force = true))
            val similar = lookupSimilarByCategory(catName)
            if (similar != null && similar.hasAnyNutritionValue()) {
                ctx.log("Category fallback: found ${similar.productName} | sugar=${similar.sugarPer100g} | sodium=${similar.sodiumPer100g}")
                val result = gradeNutrition(similar)
                ctx.log("Category result: ${result.productName} | Grade ${result.grade}")
                val displayText = result.displayText.ifBlank { "Category avg\nGrade ?" }
                    .replace("整瓶", "同类参考·整瓶")
                    .replace("整包", "同类参考·整包")
                return ctx.client.display(displayText.take(180), DisplayOptions(force = true))
            }
            ctx.log("Category fallback returned insufficient data")
            return ctx.client.display("Product found\nNo nutrition data\nTry label scan", DisplayOptions(force = true))
        }

        val result = gradeNutrition(rawData)
        ctx.log(
            "Barcode result: ${result.productName} | Grade ${result.grade} | " +
                "Barcode: $barcode | Qty: ${rawData.quantityText} | Warning: ${result.warning}"
        )

        val displayText = result.displayText.ifBlank {
            "Grade ?\nLookup failed\nTry label scan"
        }
        return ctx.client.display(displayText.take(180), DisplayOptions(force = true))
    }
}

private class AnalyzeNutritionCommand : UniversalCommand {
    override val id: String = "analyze_nutrition"
    override val title: String = "Analyze Nutrition"

    private fun buildNutritionPrompt(): String = """
You are a nutrition analysis assistant. Analyze the packaging or nutrition label in the image.

The best image for this task is the nutrition facts table / 营养成分表. A front logo or barcode alone is usually insufficient.

Extract the following information and return ONLY a JSON object (no markdown, no extra text):

{
  "productName": "product or brand name",
  "category": "drink" or "food" or "snack",
  "sugarPer100ml": number or null (grams per 100mL, for drinks),
  "sugarPer100g": number or null (grams per 100g, for food),
  "caloriesPer100ml": number or null (kcal per 100mL),
  "caloriesPer100g": number or null (kcal per 100g),
  "saturatedFatPer100g": number or null (grams per 100g),
  "sodiumPer100g": number or null (milligrams per 100g),
  "proteinPer100g": number or null (grams per 100g),
  "fiberPer100g": number or null (grams per 100g)
}

Rules:
- Prefer values from the nutrition facts table over guesses from brand recognition.
- If the image only shows a barcode or only the front product name, keep numeric fields null.
- Treat barcode as auxiliary only; the main target is the nutrition label.
- If you see a nutrition facts table, extract exact numbers.
- Do not guess uncertain numeric values. Use null when a value is unreadable or not explicitly visible.
- If the image is not a food or drink package, return the same JSON schema with productName "Unknown", category "food", and all numeric fields null.
- Category: "drink" for beverages, "food" for packaged meals/snacks, "snack" for chips/cookies/candy.
- For drinks, prefer sugarPer100ml. For food/snack, prefer sugarPer100g.
- If text is unreadable, set productName to "Unreadable" and leave numeric fields as null.
- If the nutrition table is blurry, partially cut off, too far away, or blocked by glare, set productName to "Unreadable" and leave numeric fields as null.
- If the label uses Chinese nutrition tables, pay attention to per 100g values and energy/kcal lines.
- If the label uses English Nutrition Facts, extract serving-based values only when the per-100g/per-100mL value is explicit; otherwise use null.
- Output ONLY the JSON object, no explanation.
    """.trimIndent()

    override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
        val ai = try {
            buildAiClient(ctx)
        } catch (e: IllegalArgumentException) {
            ctx.log("ERROR: ${e.message}")
            return Result.failure(e)
        }

        ctx.log("Capturing nutrition label...")
        ctx.client.display("📸 Capturing...", DisplayOptions(force = true))

        val captureResult = try {
            ctx.client.capturePhoto(
                CaptureOptions(quality = 82, targetWidth = 1280, targetHeight = 960, timeoutMs = 45_000)
            )
        } catch (e: Exception) {
            ctx.log("Capture failed: ${e.message}")
            ctx.client.display("Grade ?\nCapture failed\nTry again", DisplayOptions(force = true))
            return Result.failure(e)
        }

        val img = captureResult.getOrNull()
        if (img == null) {
            val error = captureResult.exceptionOrNull() ?: IllegalStateException("Capture failed")
            ctx.log("Capture failed: ${error.message}")
            ctx.client.display("Grade ?\nCapture failed\nTry again", DisplayOptions(force = true))
            return Result.failure(error)
        }

        ctx.onCapturedImage?.invoke(img)
        ctx.log("Captured ${img.jpegBytes.size} bytes")

        ctx.log("Analyzing with AI...")
        ctx.client.display("🔍 Analyzing...", DisplayOptions(force = true))

        val rawText = try {
            runVisionPrompt(ai, buildNutritionPrompt(), img.jpegBytes)
        } catch (e: Exception) {
            ctx.log("AI call failed: ${e.message}")
            ctx.client.display("Grade ?\nAI call failed\nTry again", DisplayOptions(force = true))
            return Result.failure(e)
        }

        if (rawText.isBlank()) {
            ctx.log("AI returned empty response")
            ctx.client.display("Grade ?\nEmpty AI response\nTry again", DisplayOptions(force = true))
            return Result.failure(IllegalStateException("Empty AI response"))
        }

        ctx.log("AI response: ${rawText.take(200)}...")

        val rawData = parseNutritionResponse(rawText)
        if (rawData.isUnreadableResult()) {
            ctx.log("AI marked capture as unreadable; ask user to move closer to the nutrition label")
        } else if (!rawData.hasAnyNutritionValue()) {
            ctx.log("AI returned no structured nutrition values; likely front-of-pack or barcode-only shot")
        }
        val result = gradeNutrition(rawData)

        ctx.log(
            "Result: ${result.productName} | Grade ${result.grade} | " +
                "Tags: ${result.tags.joinToString()} | Warning: ${result.warning}"
        )

        val displayText = result.displayText.ifBlank {
            "Grade ?\nUnable to analyze\nTry again"
        }

        return ctx.client.display(displayText.take(180), DisplayOptions(force = true))
    }
}