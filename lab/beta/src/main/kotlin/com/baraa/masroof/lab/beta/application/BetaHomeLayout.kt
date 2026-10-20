package com.baraa.masroof.lab.beta.application

enum class BetaHomeSection { HERO, SHORTCUTS, COMMITMENTS, METRICS, MERCHANTS, TRANSACTIONS }
data class BetaHomeSectionEntry(val section: BetaHomeSection, val visible: Boolean = true)
data class BetaHomeLayout(val sections: List<BetaHomeSectionEntry> = BetaHomeSection.entries.map(::BetaHomeSectionEntry),
    val expensesVisible: Boolean = true, val averageVisible: Boolean = true) {
    init { require(sections.map { it.section }.toSet() == BetaHomeSection.entries.toSet() && sections.size == BetaHomeSection.entries.size) }
    fun toggle(section: BetaHomeSection) = copy(sections = sections.map { if (it.section == section) it.copy(visible = !it.visible) else it })
    fun move(section: BetaHomeSection, direction: Int): BetaHomeLayout {
        require(direction == -1 || direction == 1)
        val index = sections.indexOfFirst { it.section == section }
        val destination = index + direction
        if (destination !in sections.indices) return this
        return copy(sections = sections.toMutableList().apply { add(destination, removeAt(index)) })
    }
    internal fun encode() = (listOf("v2", expensesVisible.toString(), averageVisible.toString()) +
        sections.map { "${it.section.name}:${it.visible}" }).joinToString("|")
    companion object {
        internal fun decode(value: String?): BetaHomeLayout {
            if (value == null) return BetaHomeLayout()
            val parts = value.split('|')
            require(parts.size >= 3)
            val flags=parts.drop(3).map {
                val row=it.split(':');require(row.size==2)
                row[0] to row[1].toBooleanStrict()
            }
            require(flags.map {it.first}.distinct().size==flags.size)
            val sections=when(parts[0]) {
                "v2" -> {require(flags.size==BetaHomeSection.entries.size)
                    flags.map {(name,visible) -> BetaHomeSectionEntry(BetaHomeSection.valueOf(name),visible)}}
                "v1" -> {
                    require(flags.map {it.first}.toSet()==BetaHomeSection.entries.map {it.name}.toSet()+"TREND")
                    val mergedVisible=flags.any {it.first in setOf("TREND","MERCHANTS") && it.second}
                    val seen=mutableSetOf<BetaHomeSection>()
                    flags.mapNotNull {(name,visible) ->
                        val section=if(name=="TREND") BetaHomeSection.MERCHANTS else BetaHomeSection.valueOf(name)
                        if(!seen.add(section)) null else BetaHomeSectionEntry(section,if(section==BetaHomeSection.MERCHANTS) mergedVisible else visible)
                    }
                }
                else -> error("Unsupported home layout")
            }
            return BetaHomeLayout(sections,parts[1].toBooleanStrict(),parts[2].toBooleanStrict())
        }
    }
}
