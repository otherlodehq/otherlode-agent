package dev.soaktest.shop

class OrderSearch {
    fun search(
        q: String,
        limit: Int = 10,
        sort: String = "asc",
    ): List<String> = listOf("$q-1-$sort", "$q-2-$sort", "$q-3-$sort", "$q-4-$sort", "$q-5-$sort", "$q-6-$sort").take(limit)
}
