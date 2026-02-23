package zaujaani.roadsensebasic.ui.summary

import zaujaani.roadsensebasic.data.local.entity.*
import zaujaani.roadsensebasic.domain.engine.PCIRating

/**
 * SessionDetailUi — Model agregasi UI per sesi survey.
 *
 * FOTO (allPhotos):
 *   Gabungan semua sumber foto untuk mode apapun:
 *   - GENERAL  → semua EventType.PHOTO dari RoadEvent
 *   - SDI      → foto dari distress items + foto FAB kamera (RoadEvent PHOTO)
 *   - PCI      → foto dari PCI distress items + foto FAB kamera (RoadEvent PHOTO)
 *
 *   Dengan model ini, surveyor bisa melihat semua foto dokumentasi
 *   dari dalam aplikasi tanpa perlu buka galeri HP.
 */
data class SessionDetailUi(

    val session: SurveySession,

    // ── Mode info ──────────────────────────────────────────────────────────
    val mode: SurveyMode,

    // ── Basic summary ──────────────────────────────────────────────────────
    val totalDistance: Double,
    val durationMinutes: Long,
    val avgConfidence: Int,

    // ── SDI ────────────────────────────────────────────────────────────────
    val averageSdi: Int,
    val segmentsSdi: List<SegmentSdi>,
    val distressItems: List<DistressItem>,

    // ── PCI ────────────────────────────────────────────────────────────────
    val averagePci: Int,
    val pciRating: PCIRating?,
    val segmentsPci: List<SegmentPci>,
    val pciDistressItems: List<PCIDistressItem>,

    // ── Event + Media ──────────────────────────────────────────────────────
    val events: List<RoadEvent>,

    // ── Raw foto per kategori (tetap untuk backward compat & export) ──────
    val generalPhotos: List<RoadEvent>,
    val sdiPhotos: List<String>,
    val pciPhotos: List<String>,

    // ⭐ FOTO GABUNGAN — gunakan ini untuk photo slider di semua mode
    //    Sudah diurutkan: foto distress duluan, lalu foto FAB kamera umum.
    val allPhotos: List<PhotoItem>,

    // ── Distribution ──────────────────────────────────────────────────────
    val conditionDistribution: Map<String, Double>,
    val surfaceDistribution: Map<String, Double>,

    val photoCount: Int,
    val audioCount: Int
)