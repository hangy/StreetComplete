package de.westnordost.streetcomplete.screens.main.bottom_sheet

import android.content.res.Configuration
import android.graphics.PointF
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toPointF
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import de.westnordost.countryboundaries.CountryBoundaries
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.AllEditTypes
import de.westnordost.streetcomplete.data.location.SurveyChecker
import de.westnordost.streetcomplete.data.meta.CountryInfos
import de.westnordost.streetcomplete.data.meta.LengthUnit
import de.westnordost.streetcomplete.data.meta.getByLocation
import de.westnordost.streetcomplete.data.osm.edits.ElementEditType
import de.westnordost.streetcomplete.data.osm.edits.ElementEditsController
import de.westnordost.streetcomplete.data.osm.edits.move.MoveNodeAction
import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.ElementKey
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.osm.mapdata.Node
import de.westnordost.streetcomplete.data.osm.mapdata.key
import de.westnordost.streetcomplete.databinding.FragmentMoveNodeBinding
import de.westnordost.streetcomplete.overlays.IsShowingElement
import de.westnordost.streetcomplete.screens.measure.MeasureDisplayUnit
import de.westnordost.streetcomplete.screens.measure.MeasureDisplayUnitFeetInch
import de.westnordost.streetcomplete.screens.measure.MeasureDisplayUnitMeter
import de.westnordost.streetcomplete.util.ktx.awaitLayout
import de.westnordost.streetcomplete.util.ktx.getLocationInWindow
import de.westnordost.streetcomplete.util.ktx.popIn
import de.westnordost.streetcomplete.util.ktx.popOut
import de.westnordost.streetcomplete.util.ktx.setMargins
import de.westnordost.streetcomplete.util.ktx.viewLifecycleScope
import de.westnordost.streetcomplete.util.math.distanceTo
import de.westnordost.streetcomplete.util.viewBinding
import de.westnordost.streetcomplete.view.RoundRectOutlineProvider
import de.westnordost.streetcomplete.view.confirmIsSurvey
import de.westnordost.streetcomplete.view.insets_animation.respectSystemInsets
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named

/** Fragment that lets the user move an OSM node */
class MoveNodeFragment :
    Fragment(R.layout.fragment_move_node), IsCloseableBottomSheet, IsShowingElement, IsMapPositionAware {

    private val binding by viewBinding(FragmentMoveNodeBinding::bind)

    private val elementEditsController: ElementEditsController by inject()
    private val allEditTypes: AllEditTypes by inject()
    private val countryBoundaries: Lazy<CountryBoundaries> by inject(named("CountryBoundariesLazy"))
    private val countryInfos: CountryInfos by inject()
    private val surveyChecker: SurveyChecker by inject()

    override val elementKey: ElementKey by lazy { node.key }

    private lateinit var node: Node
    private lateinit var editType: ElementEditType
    private lateinit var displayUnit: MeasureDisplayUnit

    private lateinit var arrowDrawable: ArrowDrawable

    private val hasChanges get() = getMarkerPosition() != node.position

    interface Listener {
        fun getMapPositionAt(screenPos: PointF): LatLon?
        fun getScreenPositionAt(mapPos: LatLon): PointF?

        fun onMovedNode(editType: ElementEditType, position: LatLon)
    }
    private val listener: Listener? get() = parentFragment as? Listener ?: activity as? Listener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        node = Json.decodeFromString(args.getString(ARG_NODE)!!)
        editType = allEditTypes.getByName(args.getString(ARG_QUEST_TYPE)!!) as ElementEditType

        val isFeetAndInch = countryInfos.getByLocation(
            countryBoundaries.value,
            node.position.longitude,
            node.position.latitude
        ).lengthUnits.firstOrNull() == LengthUnit.FOOT_AND_INCH
        displayUnit = if (isFeetAndInch) MeasureDisplayUnitFeetInch(4) else MeasureDisplayUnitMeter(10)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.bottomSheetContainer.respectSystemInsets(View::setMargins)

        arrowDrawable = ArrowDrawable(resources)
        binding.arrowView.setImageDrawable(arrowDrawable)
        arrowDrawable.setTint(requireContext().resources.getColor(R.color.accent))

        binding.okButton.setOnClickListener { onClickOk() }
        binding.cancelButton.setOnClickListener { activity?.onBackPressed() }
        binding.pin.pinIconView.setImageResource(editType.icon)

        val cornerRadius = resources.getDimension(R.dimen.speech_bubble_rounded_corner_radius)
        val margin = resources.getDimensionPixelSize(R.dimen.horizontal_speech_bubble_margin)
        binding.speechbubbleContentContainer.outlineProvider = RoundRectOutlineProvider(
            cornerRadius, margin, margin, margin, margin
        )

        if (savedInstanceState == null) {
            binding.speechbubbleContentContainer.startAnimation(
                AnimationUtils.loadAnimation(context, R.anim.inflate_answer_bubble)
            )
        }

        // to lay out the arrow drawable correctly, view must have been layouted first
        viewLifecycleOwner.lifecycleScope.launch {
            view.awaitLayout()
            updateArrowDrawable()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.centeredMarkerLayout.setPadding(
            resources.getDimensionPixelSize(R.dimen.quest_form_leftOffset),
            resources.getDimensionPixelSize(R.dimen.quest_form_topOffset),
            resources.getDimensionPixelSize(R.dimen.quest_form_rightOffset),
            resources.getDimensionPixelSize(R.dimen.quest_form_bottomOffset)
        )
    }

    private fun getMarkerScreenPosition(): PointF {
        val moveNodeMarker = binding.pin.root
        val screenPos = moveNodeMarker.getLocationInWindow()
        screenPos.offset(moveNodeMarker.width / 2, moveNodeMarker.height / 2)
        return screenPos.toPointF()
    }

    private fun getMarkerPosition(): LatLon? =
        listener?.getMapPositionAt(getMarkerScreenPosition())

    private fun onClickOk() {
        val position = getMarkerPosition() ?: return
        if (!checkIsDistanceOkAndUpdateText(position)) return
        viewLifecycleScope.launch {
            moveNodeTo(position)
        }
    }

    private suspend fun moveNodeTo(position: LatLon) {
        val isSurvey = surveyChecker.checkIsSurvey(ElementPointGeometry(position))
        if (isSurvey || confirmIsSurvey(requireContext())) {
            val action = MoveNodeAction(node, position)
            elementEditsController.add(editType, ElementPointGeometry(node.position), "survey", action, isSurvey)
            listener?.onMovedNode(editType, position)
        }
    }

    override fun onClickMapAt(position: LatLon, clickAreaSizeInMeters: Double) = false

    @UiThread override fun onMapMoved(position: LatLon) {
        updateArrowDrawable()

        if (checkIsDistanceOkAndUpdateText(position)) {
            binding.okButton.popIn()
        } else {
            binding.okButton.popOut()
        }
    }

    private fun updateArrowDrawable() {
        arrowDrawable.startPoint = listener?.getScreenPositionAt(node.position)
        arrowDrawable.endPoint = getMarkerScreenPosition()
    }

    private fun checkIsDistanceOkAndUpdateText(position: LatLon): Boolean {
        val moveDistance = position.distanceTo(node.position)
        return when {
            moveDistance < MIN_MOVE_DISTANCE -> {
                binding.titleLabel.setText(R.string.node_moved_not_far_enough)
                false
            }
            moveDistance > MAX_MOVE_DISTANCE -> {
                binding.titleLabel.setText(R.string.node_moved_too_far)
                false
            }
            else -> {
                binding.titleLabel.text = resources.getString(R.string.node_moved, displayUnit.format(moveDistance.toFloat()))
                true
            }
        }
    }

    @UiThread override fun onClickClose(onConfirmed: () -> Unit) {
        if (!hasChanges) {
            onConfirmed()
        } else {
            activity?.let {
                AlertDialog.Builder(it)
                    .setMessage(R.string.confirmation_discard_title)
                    .setPositiveButton(R.string.confirmation_discard_positive) { _, _ ->
                        onConfirmed()
                    }
                    .setNegativeButton(R.string.short_no_answer_on_button, null)
                    .show()
            }
        }
    }

    companion object {
        private const val ARG_NODE = "node"
        private const val ARG_QUEST_TYPE = "quest_type"

        fun create(elementEditType: ElementEditType, node: Node): MoveNodeFragment {
            val f = MoveNodeFragment()
            f.arguments = bundleOf(
                ARG_NODE to Json.encodeToString(node),
                ARG_QUEST_TYPE to elementEditType.name
            )
            return f
        }
    }
}

// Require a minimum distance because the map is not perfectly precise, it may be hard to tell
// whether something really is misplaced without good aerial imagery.
// Also, POIs are objects with a certain extent, so as long as the node is within this extent, it's
// fine, there is little value of putting the point at exactly the center point of the POI
private const val MIN_MOVE_DISTANCE = 1.0
// Move node functionality is meant for fixing slightly misplaced elements. If something moved far
// away, it is reasonable to assume there are more substantial changes required, also to nearby
// elements. Additionally, the default radius for highlighted elements is 30 m, so moving outside
// should not be allowed.
private const val MAX_MOVE_DISTANCE = 30.0
