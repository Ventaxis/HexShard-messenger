package com.example.util

data class CountryItem(
    val nameEn: String,
    val nameRu: String,
    val dialCode: String,
    val flag: String,
    val isoCode: String
) {
    fun getDisplayName(isRussian: Boolean): String = if (isRussian) nameRu else nameEn
}

object CountryData {
    val countries: List<CountryItem> = listOf(
        CountryItem("United States", "США", "+1", "🇺🇸", "US"),
        CountryItem("United Kingdom", "Великобритания", "+44", "🇬🇧", "GB"),
        CountryItem("Russia", "Россия", "+7", "🇷🇺", "RU"),
        CountryItem("Germany", "Германия", "+49", "🇩🇪", "DE"),
        CountryItem("France", "Франция", "+33", "🇫🇷", "FR"),
        CountryItem("Canada", "Канада", "+1", "🇨🇦", "CA"),
        CountryItem("Australia", "Австралия", "+61", "🇦🇺", "AU"),
        CountryItem("Kazakhstan", "Казахстан", "+7", "🇰🇿", "KZ"),
        CountryItem("Belarus", "Беларусь", "+375", "🇧🇾", "BY"),
        CountryItem("Ukraine", "Украина", "+380", "🇺🇦", "UA"),
        CountryItem("Armenia", "Армения", "+374", "🇦🇲", "AM"),
        CountryItem("Georgia", "Грузия", "+995", "🇬🇪", "GE"),
        CountryItem("Uzbekistan", "Узбекистан", "+998", "🇺🇿", "UZ"),
        CountryItem("Turkey", "Турция", "+90", "🇹🇷", "TR"),
        CountryItem("United Arab Emirates", "ОАЭ", "+971", "🇦🇪", "AE"),
        CountryItem("Israel", "Израиль", "+972", "🇮🇱", "IL"),
        CountryItem("Japan", "Япония", "+81", "🇯🇵", "JP"),
        CountryItem("South Korea", "Южная Корея", "+82", "🇰🇷", "KR"),
        CountryItem("India", "Индия", "+91", "🇮🇳", "IN"),
        CountryItem("China", "Китай", "+86", "🇨🇳", "CN"),
        CountryItem("Brazil", "Бразилия", "+55", "🇧🇷", "BR"),
        CountryItem("Italy", "Италия", "+39", "🇮🇹", "IT"),
        CountryItem("Spain", "Испания", "+34", "🇪🇸", "ES"),
        CountryItem("Netherlands", "Нидерланды", "+31", "🇳🇱", "NL"),
        CountryItem("Poland", "Польша", "+48", "🇵🇱", "PL"),
        CountryItem("Sweden", "Швеция", "+46", "🇸🇪", "SE"),
        CountryItem("Switzerland", "Швейцария", "+41", "🇨🇭", "CH"),
        CountryItem("Austria", "Австрия", "+43", "🇦🇹", "AT"),
        CountryItem("Singapore", "Сингапур", "+65", "🇸🇬", "SG")
    )

    fun defaultCountry(): CountryItem = countries[0] // +1 US
}
