package il.ac.technion.cs.softwaredesign

sealed class ScrapeData

data class Scrape(
    val complete: Int,
    val downloaded: Int,
    val incomplete: Int,
    val name: String?
) : ScrapeData()

data class Failure(
    val reason: String
) : ScrapeData()