package com.example.twoupprint

/**
 * Controls how extra horizontal space (from user margins and aspect-ratio scaling)
 * is distributed between the outer sheet edges and the middle gutter.
 *
 * - [CENTER]: Balanced/uniform distribution. Space inside each slot is split 50/50,
 *   and internal gutter equals the average outer margin.
 * - [RATIO_2_1]: 2:1 ratio favoring the middle gutter over outer margins. Extra space
 *   is concentrated in the center for notes, annotations, and images.
 * - [MAX_MIDDLE]: Maximum middle space. Subpages are pushed flush against the outer
 *   margin boundaries, concentrating 100% of spare horizontal space into the center gutter.
 */
enum class SpaceDistributionMode(
    val id: String,
    val displayName: String,
    val factor: Float,
    val gutterMultiplier: Float
) {
    CENTER("center", "Center (Balanced)", 0.0f, 1.0f),
    RATIO_2_1("ratio_2_1", "2:1 Middle", 0.5f, 1.5f),
    MAX_MIDDLE("max_middle", "Max Middle", 1.0f, 2.0f);

    companion object {
        fun fromId(id: String?): SpaceDistributionMode {
            return values().firstOrNull { it.id == id } ?: CENTER
        }
    }
}
