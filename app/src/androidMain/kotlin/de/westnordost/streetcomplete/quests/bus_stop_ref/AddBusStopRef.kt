package de.westnordost.streetcomplete.quests.bus_stop_ref

import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.osmquests.OsmFilterQuestType
import de.westnordost.streetcomplete.data.quest.AndroidQuest
import de.westnordost.streetcomplete.data.quest.NoCountriesExcept
import de.westnordost.streetcomplete.data.user.achievements.EditTypeAchievement.PEDESTRIAN
import de.westnordost.streetcomplete.osm.Tags

class AddBusStopRef : OsmFilterQuestType<BusStopRefAnswer>(), AndroidQuest {

    override val elementFilter = """
        nodes with
        (
          (public_transport = platform and ~bus|trolleybus|tram ~ yes)
          or
          (highway = bus_stop and public_transport != stop_position)
        )
        and access !~ no|private
        and !ref and noref != yes and ref:signed != no and !~"ref:.*"
    """
    override val enabledInCountries = NoCountriesExcept(
        "AU", // https://github.com/streetcomplete/StreetComplete/issues/4487
        "CA",
        "CO", // https://github.com/streetcomplete/StreetComplete/issues/5124
        "IE",
        "IR", // https://github.com/streetcomplete/StreetComplete/issues/6227
        "IL", // https://github.com/streetcomplete/StreetComplete/issues/5119
        "JE",
        "KR", // https://github.com/streetcomplete/StreetComplete/issues/6076
        "NZ", // https://wiki.openstreetmap.org/w/index.php?title=Talk:StreetComplete/Quests&oldid=2599288#Quests_in_New_Zealand
        "PT", // https://github.com/streetcomplete/StreetComplete/issues/5695
        "TR", // https://github.com/streetcomplete/StreetComplete/issues/4489
        "US",
    )
    override val changesetComment = "Determine bus/tram stop refs"
    override val wikiLink = "Tag:public_transport=platform"
    override val icon = R.drawable.ic_quest_bus_stop_name
    override val achievements = listOf(PEDESTRIAN)

    override fun getTitle(tags: Map<String, String>) = R.string.quest_busStopRef_title2

    override fun createForm() = AddBusStopRefForm()

    override fun applyAnswerTo(answer: BusStopRefAnswer, tags: Tags, geometry: ElementGeometry, timestampEdited: Long) {
        when (answer) {
            is NoVisibleBusStopRef -> tags["ref:signed"] = "no"
            is BusStopRef ->          tags["ref"] = answer.ref
        }
    }
}
