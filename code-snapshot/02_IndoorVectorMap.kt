package com.example.lottemallindoor

import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * ============================================================================
 *  벡터 기반 실내지도 (GroundOverlay 사진 대신 Polygon + Marker 라벨 사용)
 * ============================================================================
 *
 *  기존 GroundOverlay 앵커(lotteMallCenter / overlayWidth / overlayHeight / overlayBearing)를
 *  그대로 재사용해서, 도면 "픽셀 좌표"를 실제 위경도로 변환한다.
 *
 *  ⚠️ v2 수정사항: 회전 공식 버그 수정
 *  GroundOverlayOptions.bearing()은 "진북 기준 시계방향" 회전인데
 *  이전 버전은 수학 표준(반시계방향) 회전 공식을 썼음 → 사진 각도와 어긋난 원인.
 *  아래 toLatLng()의 rotatedX/rotatedY 부호를 시계방향 회전식으로 교체함.
 */

// ---------------------------------------------------------------------------
// 1. 픽셀 → 위경도 변환
// ---------------------------------------------------------------------------

data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * 도면 픽셀 좌표를 실제 위경도로 변환.
 *
 * 내부적으로 2x2 어파인 행렬(m11,m12,m21,m22)을 사용 — 가로/세로 축척이 달라도 대응 가능.
 * - 기준점 2개(fromTwoPoints): 가로세로 축척이 같다고 가정(회전+균일축척)
 * - 기준점 3개(fromThreePoints): 가로/세로 축척을 독립적으로 계산 → 그림이 한쪽으로
 *   갈수록 어긋나는 문제(안내도가 실측 CAD가 아니라 일러스트라 축척이 미묘하게
 *   다른 경우)를 해결함
 */
class FloorPlanProjector private constructor(
    private val originPx: Int,
    private val originPy: Int,
    private val originLat: Double,
    private val originLng: Double,
    private val m11: Double, private val m12: Double,
    private val m21: Double, private val m22: Double
) {
    private fun metersPerDegLng(lat: Double) = 111_320.0 * cos(Math.toRadians(lat))

    fun toLatLng(px: Int, py: Int): LatLng {
        val ex = (px - originPx).toDouble()
        val ey = -(py - originPy).toDouble() // 이미지 y는 아래로 증가 → 북쪽은 -ey

        val east = m11 * ex + m12 * ey
        val north = m21 * ex + m22 * ey

        val lat = originLat + north / 111_320.0
        val lng = originLng + east / metersPerDegLng(originLat)
        return LatLng(lat, lng)
    }

    /**
     * @param insetPx 사각형을 안쪽으로 줄이는 픽셀 값. 인접한 매장끼리 서로 붙지 않고
     *                사이에 통로(복도) 틈이 보이도록 하는 용도 (기본 6px)
     */
    fun toLatLngBounds(rect: PixelRect, insetPx: Int = 6): List<LatLng> {
        val halfW = ((rect.right - rect.left) / 2).coerceAtMost(insetPx)
        val halfH = ((rect.bottom - rect.top) / 2).coerceAtMost(insetPx)
        val l = rect.left + halfW
        val r = rect.right - halfW
        val t = rect.top + halfH
        val b = rect.bottom - halfH
        return listOf(
            toLatLng(l, t),
            toLatLng(r, t),
            toLatLng(r, b),
            toLatLng(l, b)
        )
    }

    /** 사각형이 아닌 임의의 다각형 꼭짓점 리스트를 위경도로 변환 */
    fun toLatLngPolygon(points: List<Pair<Int, Int>>): List<LatLng> =
        points.map { (px, py) -> toLatLng(px, py) }

    fun rectCenter(rect: PixelRect): LatLng {
        val cx = (rect.left + rect.right) / 2
        val cy = (rect.top + rect.bottom) / 2
        return toLatLng(cx, cy)
    }

    /** 이 프로젝터 기준 특정 픽셀 지점을 축으로 삼아 180도 회전한 새 프로젝터를 반환.
     *  (전체 위치는 그대로 두고 방향만 뒤집고 싶을 때 사용 — 보통 이미지 정중앙을 축으로 지정) */
    fun rotated180Around(pivotPx: Int, pivotPy: Int): FloorPlanProjector {
        val pivotLatLng = toLatLng(pivotPx, pivotPy)
        return FloorPlanProjector(pivotPx, pivotPy, pivotLatLng.latitude, pivotLatLng.longitude, -m11, -m12, -m21, -m22)
    }

    /**
     * 실측 기준점만으로는 남는 미세한 회전/축척 오차를 현장에서 눈으로 보며 보정하기 위한 함수.
     * pivotPx,pivotPy(보통 이미지 정중앙)를 축으로 추가 회전(시계방향, 도)과 축척 배율을 곱해서 적용한다.
     * MainActivity의 디버그/보정 모드에서 +/- 버튼으로 extraRotationDeg, extraScaleMultiplier를
     * 조금씩 바꿔가며 위성지도 위에 겹쳐보고 맞을 때까지 조정하는 용도.
     */
    fun withCorrection(pivotPx: Int, pivotPy: Int, extraRotationDeg: Double, extraScaleMultiplier: Double): FloorPlanProjector {
        if (extraRotationDeg == 0.0 && extraScaleMultiplier == 1.0) return this
        val pivotLatLng = toLatLng(pivotPx, pivotPy)
        val rad = Math.toRadians(extraRotationDeg)
        val c = cos(rad)
        val s = sin(rad)
        val k = extraScaleMultiplier
        // 새 행렬 = k * R(시계방향 extraRotationDeg) * 기존 행렬
        val nm11 = k * (c * m11 + s * m21)
        val nm12 = k * (c * m12 + s * m22)
        val nm21 = k * (-s * m11 + c * m21)
        val nm22 = k * (-s * m12 + c * m22)
        return FloorPlanProjector(pivotPx, pivotPy, pivotLatLng.latitude, pivotLatLng.longitude, nm11, nm12, nm21, nm22)
    }

    /** 디버그 오버레이에서 "지금 이 프로젝터의 회전각이 몇 도인가"를 보여주기 위한 계산.
     *  m11,m21에서 시계방향 회전각(도)을 역산 (fromCenterScaleRotation의 부호 규약과 대응). */
    fun currentRotationDeg(): Double = Math.toDegrees(kotlin.math.atan2(m21, m11))

    /** 디버그 오버레이용: 현재 1픽셀당 실제 미터(축척) */
    fun currentScaleMPerPx(): Double = kotlin.math.hypot(m11, m21)

    companion object {
        /**
         * 기준점 2개(픽셀 좌표 ↔ 실측 위경도)로 축척+회전을 자동 계산.
         * @param px1,py1,latLng1   기준점 1 (보통 도면 이미지의 (0,0) = 왼쪽 위)
         * @param px2,py2,latLng2   기준점 2 (보통 도면 이미지의 (width,height) = 오른쪽 아래)
         */
        fun fromTwoPoints(
            px1: Int, py1: Int, latLng1: LatLng,
            px2: Int, py2: Int, latLng2: LatLng
        ): FloorPlanProjector {
            val metersPerDegLat = 111_320.0
            val metersPerDegLng1 = 111_320.0 * cos(Math.toRadians(latLng1.latitude))

            val east2 = (latLng2.longitude - latLng1.longitude) * metersPerDegLng1
            val north2 = (latLng2.latitude - latLng1.latitude) * metersPerDegLat

            val ex2 = (px2 - px1).toDouble()
            val ey2 = -(py2 - py1).toDouble()

            val denom = ex2 * ex2 + ey2 * ey2
            val a = (east2 * ex2 + north2 * ey2) / denom
            val b = (north2 * ex2 - east2 * ey2) / denom

            // 균일축척 특수 케이스: m11=a, m12=-b, m21=b, m22=a
            return FloorPlanProjector(px1, py1, latLng1.latitude, latLng1.longitude, a, -b, b, a)
        }

        /**
         * 기준점 3개로 어파인 변환(가로/세로 축척을 독립적으로) 계산.
         * "왼쪽은 맞는데 오른쪽으로 갈수록 어긋난다" 같은 문제는 가로/세로 축척이
         * 실제로 다르기 때문일 수 있어서, 이 경우 2점 대신 3점을 써야 함.
         *
         * @param p0 원점 기준점 (보통 왼쪽 위 NW 모서리)
         * @param p1 두 번째 기준점 (보통 오른쪽 아래 SE 모서리)
         * @param p2 세 번째 기준점 (보통 오른쪽 위 NE 모서리, 또는 왼쪽 아래)
         */
        fun fromThreePoints(
            p0: Triple<Int, Int, LatLng>,
            p1: Triple<Int, Int, LatLng>,
            p2: Triple<Int, Int, LatLng>
        ): FloorPlanProjector {
            val (px0, py0, latLng0) = p0
            val (px1, py1, latLng1) = p1
            val (px2, py2, latLng2) = p2

            val metersPerDegLat = 111_320.0
            val metersPerDegLng0 = 111_320.0 * cos(Math.toRadians(latLng0.latitude))

            val east1 = (latLng1.longitude - latLng0.longitude) * metersPerDegLng0
            val north1 = (latLng1.latitude - latLng0.latitude) * metersPerDegLat
            val east2 = (latLng2.longitude - latLng0.longitude) * metersPerDegLng0
            val north2 = (latLng2.latitude - latLng0.latitude) * metersPerDegLat

            val ex1 = (px1 - px0).toDouble()
            val ey1 = -(py1 - py0).toDouble()
            val ex2 = (px2 - px0).toDouble()
            val ey2 = -(py2 - py0).toDouble()

            // [ex1 ey1] [m11]   [east1]      [ex1 ey1] [m21]   [north1]
            // [ex2 ey2] [m12] = [east2]  and [ex2 ey2] [m22] = [north2]
            val det = ex1 * ey2 - ex2 * ey1

            val m11 = (east1 * ey2 - east2 * ey1) / det
            val m12 = (ex1 * east2 - ex2 * east1) / det
            val m21 = (north1 * ey2 - north2 * ey1) / det
            val m22 = (ex1 * north2 - ex2 * north1) / det

            return FloorPlanProjector(px0, py0, latLng0.latitude, latLng0.longitude, m11, m12, m21, m22)
        }

        /**
         * 위치/축척은 그대로 두고 회전각만 따로 조절하고 싶을 때 사용.
         * centerPx,centerPy(보통 이미지 정중앙)를 축으로 회전하기 때문에,
         * 각도를 바꿔도 전체 위치가 크게 안 틀어짐 (모서리를 축으로 돌리면 위치가 확 밀려남).
         *
         * @param scaleMPerPx     1픽셀당 실제 미터 (보통 fromTwoPoints로 계산한 scale 재사용)
         * @param rotationDeg     회전각(도). 늘리면 반시계방향, 줄이면 시계방향으로 회전.
         */
        fun fromCenterScaleRotation(
            centerPx: Int, centerPy: Int, centerLatLng: LatLng,
            scaleMPerPx: Double, rotationDeg: Double
        ): FloorPlanProjector {
            val rad = Math.toRadians(rotationDeg)
            val a = scaleMPerPx * cos(rad)
            val b = -scaleMPerPx * sin(rad) // 시계방향 회전 (예전에 검증됐던 -137도 방식과 동일한 방향)
            return FloorPlanProjector(centerPx, centerPy, centerLatLng.latitude, centerLatLng.longitude, a, -b, b, a)
        }

        /** fromTwoPoints 결과에서 축척(m/px)만 뽑아내고 싶을 때 사용 */
        fun scaleOf(
            px1: Int, py1: Int, latLng1: LatLng,
            px2: Int, py2: Int, latLng2: LatLng
        ): Double {
            val metersPerDegLat = 111_320.0
            val metersPerDegLng1 = 111_320.0 * cos(Math.toRadians(latLng1.latitude))
            val east2 = (latLng2.longitude - latLng1.longitude) * metersPerDegLng1
            val north2 = (latLng2.latitude - latLng1.latitude) * metersPerDegLat
            val ex2 = (px2 - px1).toDouble()
            val ey2 = -(py2 - py1).toDouble()
            return kotlin.math.hypot(east2, north2) / kotlin.math.hypot(ex2, ey2)
        }
    }
}

// ---------------------------------------------------------------------------
// 2. 매장 데이터
// ---------------------------------------------------------------------------

enum class StoreTier { ANCHOR, DETAIL }

data class StoreZone(
    val name: String,
    val pixelRect: PixelRect,
    val tier: StoreTier,
    /** 사각형이 아닌 실제 매장 외곽선 모양(L자/사다리꼴 등)이 필요할 때 씀.
     *  null이면 pixelRect(단순 사각형)를 그대로 사용. 있으면 이 다각형을 우선 사용. */
    val polygon: List<Pair<Int, Int>>? = null,
    val fillColorArgb: Int = 0x33A08060
)

/** 층별 매장 데이터 묶음 (이미지 픽셀 크기 + 매장 리스트 + 시설 아이콘 리스트 + 중앙 통로/아트리움) */
data class FloorStoreSet(
    val floor: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val zones: List<StoreZone>,
    val facilities: List<FacilityPoint> = emptyList(),
    /** 매장이 아닌 사람이 다니는 빈 통로/아트리움 구역. 매장과 다른 스타일(연한 회색 선)로 그려서
     *  "여긴 매장이 아니라 걸어다니는 공간"이라는 걸 시각적으로 구분함 */
    val corridor: List<Pair<Int, Int>>? = null,
    /** 이 층 건물 전체 외곽선(도면 이미지에서 실제 매장동 실루엣만 따온 다각형).
     *  이 다각형을 흰색으로 채워서 맨 아래에 깔면, 건물 발밑을 지나는 지하철 노선/도로 등
     *  구글 기본 지도 요소가 건물 범위 안에서는 안 보이게 됨. null이면 마스크를 그리지 않음. */
    val outline: List<Pair<Int, Int>>? = null
)

// ---------------------------------------------------------------------------
// 2-1. 시설 아이콘 (에스컬레이터 / 계단) — 도면에 별도 엘리베이터 아이콘은 없었음
// ---------------------------------------------------------------------------

enum class FacilityType { ESCALATOR, STAIRS }

data class FacilityPoint(val type: FacilityType, val px: Int, val py: Int)

// ---------------------------------------------------------------------------
// 3-0. 지하1층 (floorB1.png, 1259 x 938) — 원본부터 정방향이라 그대로 사용
// ---------------------------------------------------------------------------

val floorB1StoreSet = FloorStoreSet(
    floor = 0,
    imageWidth = 1259,
    imageHeight = 938,
    zones = listOf(
        StoreZone("ABC마트", PixelRect(1056, 51, 1230, 191), StoreTier.ANCHOR),
        StoreZone("지오다노 메가샵", PixelRect(1059, 194, 1221, 274), StoreTier.ANCHOR),
        StoreZone("스노우피크", PixelRect(967, 402, 1155, 516), StoreTier.ANCHOR),
        StoreZone("디스커버리", PixelRect(967, 517, 1158, 629), StoreTier.ANCHOR),
        StoreZone("노스페이스 메가스토어", PixelRect(967, 631, 1157, 749), StoreTier.ANCHOR),
        StoreZone("올리브영", PixelRect(375, 340, 460, 520), StoreTier.ANCHOR),
        StoreZone("마인드브릿지", PixelRect(207, 347, 299, 559), StoreTier.ANCHOR),
        StoreZone("CK진", PixelRect(872, 51, 1056, 111), StoreTier.ANCHOR),
        StoreZone("리바이스", PixelRect(638, 50, 713, 159), StoreTier.DETAIL),
        StoreZone("게스 복합관", PixelRect(713, 53, 873, 153), StoreTier.DETAIL),
        StoreZone("프로젝트 M", PixelRect(347, 123, 445, 212), StoreTier.DETAIL),
        StoreZone("NBA", PixelRect(446, 125, 554, 218), StoreTier.DETAIL),
        StoreZone("WHO.A.U", PixelRect(555, 125, 639, 215), StoreTier.DETAIL),
        StoreZone("플레이스(플랙)", PixelRect(701, 194, 852, 253), StoreTier.DETAIL),
        StoreZone("파스퇴르밀크바", PixelRect(906, 185, 1009, 207), StoreTier.DETAIL),
        StoreZone("코오롱스포츠", PixelRect(924, 277, 1024, 358), StoreTier.DETAIL),
        StoreZone("인생네컷", PixelRect(295, 214, 339, 316), StoreTier.DETAIL),
        StoreZone("룸업티컬", PixelRect(369, 244, 450, 321), StoreTier.DETAIL),
        StoreZone("라이프워크", PixelRect(478, 249, 652, 293), StoreTier.DETAIL),
        StoreZone("바인드", PixelRect(480, 340, 555, 520), StoreTier.DETAIL),
        StoreZone("아트박스", PixelRect(586, 347, 656, 520), StoreTier.DETAIL),
        StoreZone("아트박스", PixelRect(637, 323, 784, 386), StoreTier.DETAIL),
        StoreZone("더블제이디", PixelRect(787, 316, 905, 385), StoreTier.DETAIL),
        StoreZone("알래스카바이플랭", PixelRect(126, 558, 299, 661), StoreTier.DETAIL),
        StoreZone("네파", PixelRect(326, 553, 453, 663), StoreTier.DETAIL),
        StoreZone("몽벨 오리지널스토어", PixelRect(483, 551, 604, 660), StoreTier.DETAIL),
        StoreZone("머렐", PixelRect(608, 551, 697, 661), StoreTier.DETAIL),
        StoreZone("컬럼비아", PixelRect(697, 549, 779, 661), StoreTier.DETAIL),
        StoreZone("노르디스크", PixelRect(780, 552, 906, 660), StoreTier.DETAIL),
        StoreZone("아이더", PixelRect(237, 692, 408, 791), StoreTier.DETAIL),
        StoreZone("K2", PixelRect(408, 694, 568, 794), StoreTier.DETAIL),
        StoreZone("블랙야크", PixelRect(599, 692, 788, 794), StoreTier.DETAIL),
        StoreZone("내셔널지오그래픽", PixelRect(787, 690, 967, 793), StoreTier.DETAIL),
        StoreZone("약국", PixelRect(104, 696, 171, 727), StoreTier.DETAIL)
    ),
    facilities = listOf(
        FacilityPoint(FacilityType.ESCALATOR, 959, 164),
        FacilityPoint(FacilityType.ESCALATOR, 781, 285),
        FacilityPoint(FacilityType.ESCALATOR, 345, 432),
        FacilityPoint(FacilityType.ESCALATOR, 830, 418),
        FacilityPoint(FacilityType.STAIRS, 970, 243),
        FacilityPoint(FacilityType.STAIRS, 33, 670),
        FacilityPoint(FacilityType.STAIRS, 550, 845)
    ),
    corridor = listOf(
        670 to 395, 894 to 394, 872 to 519, 670 to 520
    ),
    // ⚠️ floorB1.png 원본 파일이 리포지토리에 없어서 직접 외곽선을 딸 수 없었음.
    // 1층(floor1.png) 실루엣을 B1 이미지 비율(1259x938 / 1312x1031)로 스케일링한 근사치.
    // 실제 지하1층 도면 이미지를 구하면 trace_outline 스크립트로 다시 따서 교체할 것.
    outline = listOf(
        0 to 111, 0 to 747, 114 to 747, 200 to 828, 200 to 922,
        1258 to 922, 1258 to 45, 1011 to 45, 922 to 110, 802 to 110,
        801 to 76, 772 to 76, 771 to 110, 621 to 110, 618 to 45,
        87 to 45, 86 to 75, 50 to 111
    )
)
// ---------------------------------------------------------------------------
// 3-1. 1층 (floor1.png 원본은 180도 뒤집혀 있음 → 아래 좌표는 "정방향 기준")
//      1312 x 1031
// ---------------------------------------------------------------------------

val floor1StoreSet = FloorStoreSet(
    floor = 1,
    imageWidth = 1312,
    imageHeight = 1031,
    zones = listOf(
        StoreZone("유니클로", PixelRect(1146, 289, 1312, 753), StoreTier.ANCHOR),
        StoreZone("리스트", PixelRect(1080, 756, 1308, 1010), StoreTier.ANCHOR),
        StoreZone("나이키", PixelRect(594, 753, 863, 914), StoreTier.ANCHOR),
        StoreZone("쌤소나이트", PixelRect(90, 45, 251, 150), StoreTier.ANCHOR),
        StoreZone("이철헤어커커", PixelRect(12, 229, 95, 373), StoreTier.ANCHOR),
        StoreZone("타임힐피거", PixelRect(1186, 55, 1312, 138), StoreTier.ANCHOR),
        StoreZone("퍼블리크", PixelRect(357, 723, 490, 803), StoreTier.ANCHOR),
        StoreZone("S1복합관(폼보이,보브,지엇)", PixelRect(1055, 50, 1181, 261), StoreTier.DETAIL),
        StoreZone("타임", PixelRect(645, 125, 764, 262), StoreTier.DETAIL),
        StoreZone("미샤", PixelRect(766, 124, 864, 258), StoreTier.DETAIL),
        StoreZone("더아이잖 컬렉션", PixelRect(864, 125, 962, 258), StoreTier.DETAIL),
        StoreZone("뽀렁땅", PixelRect(964, 122, 1053, 261), StoreTier.DETAIL),
        StoreZone("패션컬러리", PixelRect(187, 224, 210, 304), StoreTier.DETAIL),
        StoreZone("로제도르", PixelRect(191, 340, 210, 439), StoreTier.DETAIL),
        StoreZone("나이스클랍", PixelRect(93, 438, 214, 495), StoreTier.DETAIL),
        StoreZone("버스데이아일랜드", PixelRect(93, 496, 214, 555), StoreTier.DETAIL),
        StoreZone("베네통", PixelRect(90, 553, 214, 616), StoreTier.DETAIL),
        StoreZone("CC골렉트", PixelRect(96, 614, 213, 675), StoreTier.DETAIL),
        StoreZone("시슬리", PixelRect(90, 677, 213, 730), StoreTier.DETAIL),
        StoreZone("ZOOC", PixelRect(91, 730, 213, 792), StoreTier.DETAIL),
        StoreZone("미니엄", PixelRect(257, 241, 334, 300), StoreTier.DETAIL),
        StoreZone("듀엘", PixelRect(335, 230, 412, 301), StoreTier.DETAIL),
        StoreZone("데무", PixelRect(414, 228, 484, 303), StoreTier.DETAIL),
        StoreZone("모조에스핀", PixelRect(488, 227, 567, 303), StoreTier.DETAIL),
        StoreZone("지고트", PixelRect(573, 226, 643, 301), StoreTier.DETAIL),
        StoreZone("온앤온", PixelRect(245, 340, 329, 401), StoreTier.DETAIL),
        StoreZone("올리브데올리브", PixelRect(362, 334, 423, 392), StoreTier.DETAIL),
        StoreZone("팝업", PixelRect(428, 333, 641, 395), StoreTier.DETAIL),
        StoreZone("쥐즈미스", PixelRect(251, 437, 329, 496), StoreTier.DETAIL),
        StoreZone("더아이잖", PixelRect(251, 494, 328, 552), StoreTier.DETAIL),
        StoreZone("랩(LAP)", PixelRect(250, 550, 322, 602), StoreTier.DETAIL),
        StoreZone("잇미샤", PixelRect(250, 604, 327, 657), StoreTier.DETAIL),
        StoreZone("엄브로", PixelRect(253, 692, 328, 806), StoreTier.DETAIL),
        StoreZone("안다르", PixelRect(244, 838, 359, 913), StoreTier.DETAIL),
        StoreZone("데상트", PixelRect(366, 835, 547, 912), StoreTier.DETAIL)
    ),
    facilities = listOf(
        FacilityPoint(FacilityType.ESCALATOR, 100, 190),
        FacilityPoint(FacilityType.ESCALATOR, 778, 373),
        FacilityPoint(FacilityType.ESCALATOR, 346, 551),
        FacilityPoint(FacilityType.ESCALATOR, 861, 546),
        FacilityPoint(FacilityType.ESCALATOR, 1095, 646),
        FacilityPoint(FacilityType.ESCALATOR, 707, 739),
        FacilityPoint(FacilityType.STAIRS, 950, 365),
        FacilityPoint(FacilityType.STAIRS, 29, 788),
        FacilityPoint(FacilityType.STAIRS, 534, 977)
    ),
    corridor = listOf(
        363 to 394, 1076 to 397, 1076 to 652, 859 to 653, 860 to 717, 475 to 719, 363 to 637
    ),
    // floor1.png에서 배경색을 빼고 남는 매장동 실루엣을 그대로 추출한 외곽선(OpenCV contour trace)
    outline = listOf(
        0 to 122, 0 to 821, 119 to 821, 208 to 910, 208 to 1013,
        1311 to 1013, 1311 to 50, 1054 to 50, 961 to 121, 836 to 121,
        835 to 84, 804 to 84, 803 to 121, 647 to 121, 644 to 50,
        91 to 50, 90 to 82, 52 to 122
    )
)

// ---------------------------------------------------------------------------
// 3-2. 2층 (floor2.png 원본 180도 뒤집힘 → 정방향 기준 좌표) 1327 x 1034
// ---------------------------------------------------------------------------

val floor2StoreSet = FloorStoreSet(
    floor = 2,
    imageWidth = 1327,
    imageHeight = 1034,
    zones = listOf(
        StoreZone("아디다스", PixelRect(1132, 38, 1327, 202), StoreTier.ANCHOR),
        StoreZone("푸마", PixelRect(1045, 41, 1129, 206), StoreTier.ANCHOR),
        StoreZone("AK골프(골프용품샵&시타실)", PixelRect(822, 924, 1212, 1034), StoreTier.ANCHOR),
        StoreZone("코닥아웃도어", PixelRect(1220, 648, 1326, 780), StoreTier.ANCHOR),
        StoreZone("의류수선실", PixelRect(149, 87, 255, 141), StoreTier.DETAIL),
        StoreZone("브렌우드", PixelRect(214, 142, 277, 245), StoreTier.DETAIL),
        StoreZone("앤드지", PixelRect(274, 143, 338, 243), StoreTier.DETAIL),
        StoreZone("R.지오지아", PixelRect(338, 145, 401, 243), StoreTier.DETAIL),
        StoreZone("킨록바이킨록엔더슨", PixelRect(407, 142, 460, 261), StoreTier.DETAIL),
        StoreZone("리버클래시", PixelRect(462, 145, 541, 264), StoreTier.DETAIL),
        StoreZone("레노마셔츠", PixelRect(540, 213, 590, 262), StoreTier.DETAIL),
        StoreZone("닥스셔츠", PixelRect(590, 211, 643, 265), StoreTier.DETAIL),
        StoreZone("웰메이드", PixelRect(642, 106, 717, 208), StoreTier.DETAIL),
        StoreZone("결력시라이프스타일", PixelRect(716, 105, 786, 208), StoreTier.DETAIL),
        StoreZone("빨질레리", PixelRect(787, 106, 855, 208), StoreTier.DETAIL),
        StoreZone("로가디스", PixelRect(856, 106, 920, 208), StoreTier.DETAIL),
        StoreZone("캠브리지", PixelRect(923, 106, 988, 208), StoreTier.DETAIL),
        StoreZone("본", PixelRect(989, 92, 1044, 206), StoreTier.DETAIL),
        StoreZone("지이크", PixelRect(670, 238, 867, 296), StoreTier.DETAIL),
        StoreZone("컨버스", PixelRect(897, 238, 1057, 301), StoreTier.DETAIL),
        StoreZone("스파이더", PixelRect(1083, 238, 1193, 298), StoreTier.DETAIL),
        StoreZone("에디션", PixelRect(254, 276, 336, 336), StoreTier.DETAIL),
        StoreZone("파렌", PixelRect(253, 341, 336, 400), StoreTier.DETAIL),
        StoreZone("페라로밀라노", PixelRect(368, 281, 419, 382), StoreTier.DETAIL),
        StoreZone("지오송지오", PixelRect(424, 281, 497, 385), StoreTier.DETAIL),
        StoreZone("율젠", PixelRect(494, 296, 574, 383), StoreTier.DETAIL),
        StoreZone("레노마정장", PixelRect(572, 297, 637, 382), StoreTier.DETAIL),
        StoreZone("카운테스마라셔츠", PixelRect(713, 332, 855, 349), StoreTier.DETAIL),
        StoreZone("다이나핏", PixelRect(1086, 340, 1188, 442), StoreTier.DETAIL),
        StoreZone("뉴발란스", PixelRect(1220, 367, 1324, 553), StoreTier.DETAIL),
        StoreZone("로엠", PixelRect(253, 433, 335, 492), StoreTier.DETAIL),
        StoreZone("페이지플린", PixelRect(254, 493, 333, 562), StoreTier.DETAIL),
        StoreZone("르샵", PixelRect(254, 561, 332, 619), StoreTier.DETAIL),
        StoreZone("크록스", PixelRect(1087, 443, 1190, 501), StoreTier.DETAIL),
        StoreZone("나이키스윔", PixelRect(1118, 544, 1187, 643), StoreTier.DETAIL),
        StoreZone("아레나", PixelRect(1118, 646, 1183, 743), StoreTier.DETAIL),
        StoreZone("휠라", PixelRect(1220, 557, 1327, 647), StoreTier.DETAIL),
        StoreZone("탠디", PixelRect(99, 424, 218, 526), StoreTier.DETAIL),
        StoreZone("소다", PixelRect(99, 523, 220, 609), StoreTier.DETAIL),
        StoreZone("미소페", PixelRect(99, 611, 221, 694), StoreTier.DETAIL),
        StoreZone("닥스", PixelRect(97, 696, 221, 773), StoreTier.DETAIL),
        StoreZone("더뮬버", PixelRect(251, 642, 286, 731), StoreTier.DETAIL),
        StoreZone("에이비에프지", PixelRect(292, 645, 336, 730), StoreTier.DETAIL),
        StoreZone("열하임", PixelRect(248, 730, 336, 811), StoreTier.DETAIL),
        StoreZone("KL", PixelRect(469, 544, 579, 614), StoreTier.DETAIL),
        StoreZone("라인", PixelRect(366, 730, 425, 814), StoreTier.DETAIL),
        StoreZone("케네스레이디", PixelRect(428, 730, 492, 812), StoreTier.DETAIL),
        StoreZone("테일러메이드", PixelRect(539, 712, 606, 808), StoreTier.DETAIL),
        StoreZone("캘러웨이", PixelRect(642, 741, 712, 811), StoreTier.DETAIL),
        StoreZone("아디다스골프", PixelRect(714, 743, 784, 812), StoreTier.DETAIL),
        StoreZone("파리게이츠", PixelRect(826, 709, 894, 809), StoreTier.DETAIL),
        StoreZone("페어라이어", PixelRect(895, 711, 964, 809), StoreTier.DETAIL),
        StoreZone("까스텔바작", PixelRect(965, 711, 1026, 809), StoreTier.DETAIL),
        StoreZone("와이드앵글", PixelRect(1032, 712, 1082, 809), StoreTier.DETAIL),
        StoreZone("비비안", PixelRect(67, 846, 124, 892), StoreTier.DETAIL),
        StoreZone("비너스", PixelRect(124, 848, 192, 957), StoreTier.DETAIL),
        StoreZone("벨리시양", PixelRect(191, 846, 236, 958), StoreTier.DETAIL),
        StoreZone("르니앤맥코이", PixelRect(287, 846, 339, 961), StoreTier.DETAIL),
        StoreZone("더레노마", PixelRect(339, 846, 392, 922), StoreTier.DETAIL),
        StoreZone("사틴", PixelRect(396, 845, 448, 917), StoreTier.DETAIL),
        StoreZone("발렌시아", PixelRect(448, 846, 503, 920), StoreTier.DETAIL),
        StoreZone("SI", PixelRect(504, 843, 563, 919), StoreTier.DETAIL),
        StoreZone("레노마골프", PixelRect(599, 846, 720, 919), StoreTier.DETAIL),
        StoreZone("팬텀", PixelRect(753, 845, 811, 922), StoreTier.DETAIL),
        StoreZone("핑", PixelRect(812, 846, 871, 922), StoreTier.DETAIL),
        StoreZone("볼빅", PixelRect(898, 845, 991, 894), StoreTier.DETAIL),
        StoreZone("벤세프", PixelRect(990, 848, 1094, 894), StoreTier.DETAIL),
        StoreZone("카포(축구용품전문샵)", PixelRect(1125, 846, 1259, 963), StoreTier.DETAIL),
        StoreZone("바쏘", PixelRect(191, 278, 215, 425), StoreTier.DETAIL),
        StoreZone("리븐", PixelRect(238, 849, 283, 960), StoreTier.DETAIL)
    ),
    facilities = listOf(
        FacilityPoint(FacilityType.ESCALATOR, 786, 369),
        FacilityPoint(FacilityType.ESCALATOR, 362, 537),
        FacilityPoint(FacilityType.ESCALATOR, 1094, 634),
        FacilityPoint(FacilityType.ESCALATOR, 718, 721),
        FacilityPoint(FacilityType.STAIRS, 957, 358),
        FacilityPoint(FacilityType.STAIRS, 15, 700),
        FacilityPoint(FacilityType.STAIRS, 553, 971)
    ),
    corridor = listOf(
        372 to 385, 664 to 385, 1081 to 383, 1085 to 706, 560 to 703, 488 to 699, 369 to 621
    ),
    // floor2.png에서 배경색을 빼고 남는 매장동 실루엣을 그대로 추출한 외곽선(OpenCV contour trace)
    outline = listOf(
        0 to 37, 0 to 179, 18 to 180, 18 to 807, 62 to 809,
        70 to 888, 107 to 937, 213 to 962, 213 to 1033, 1326 to 1033,
        1326 to 37, 1045 to 37, 966 to 102, 610 to 102, 608 to 37
    )
)

// ---------------------------------------------------------------------------
// 3-3. 3층 (floor3.png 원본 180도 뒤집힘 → 정방향 기준 좌표) 1351 x 1022
// ---------------------------------------------------------------------------

val floor3StoreSet = FloorStoreSet(
    floor = 3,
    imageWidth = 1351,
    imageHeight = 1022,
    zones = listOf(
        StoreZone("바운스 더퍼스트", PixelRect(227, 106, 715, 265), StoreTier.ANCHOR),
        StoreZone("하이마트메가스토어(삼성/LG프리미엄관)", PixelRect(873, 106, 1349, 264), StoreTier.ANCHOR),
        StoreZone("하이마트메가스토어(애플샵/모바일샵/체험존)", PixelRect(1098, 295, 1168, 508), StoreTier.ANCHOR),
        StoreZone("모던하우스(주방/라이프)", PixelRect(369, 712, 606, 810), StoreTier.ANCHOR),
        StoreZone("모던하우스(홈패션/패브릭)", PixelRect(227, 847, 574, 923), StoreTier.ANCHOR),
        StoreZone("정오의식탁", PixelRect(1201, 624, 1351, 782), StoreTier.ANCHOR),
        StoreZone("별국수", PixelRect(721, 123, 781, 264), StoreTier.DETAIL),
        StoreZone("더맨타코", PixelRect(784, 127, 871, 264), StoreTier.DETAIL),
        StoreZone("리틀킴", PixelRect(206, 296, 236, 421), StoreTier.DETAIL),
        StoreZone("풀템키즈", PixelRect(263, 299, 347, 394), StoreTier.DETAIL),
        StoreZone("양팡스", PixelRect(379, 296, 443, 378), StoreTier.DETAIL),
        StoreZone("AWK", PixelRect(441, 298, 501, 379), StoreTier.DETAIL),
        StoreZone("베네통키즈", PixelRect(503, 298, 566, 381), StoreTier.DETAIL),
        StoreZone("프로젝트키즈", PixelRect(567, 296, 626, 381), StoreTier.DETAIL),
        StoreZone("블랙야크키즈", PixelRect(627, 296, 686, 381), StoreTier.DETAIL),
        StoreZone("파스퇴르밀크바(토핑바)", PixelRect(719, 301, 784, 351), StoreTier.DETAIL),
        StoreZone("엔티앤스프레즐", PixelRect(785, 301, 865, 348), StoreTier.DETAIL),
        StoreZone("알찬밥상", PixelRect(1202, 298, 1351, 438), StoreTier.DETAIL),
        StoreZone("아가방플레스", PixelRect(75, 423, 234, 501), StoreTier.DETAIL),
        StoreZone("Lee키즈", PixelRect(265, 427, 345, 475), StoreTier.DETAIL),
        StoreZone("에스키즈", PixelRect(263, 484, 345, 600), StoreTier.DETAIL),
        StoreZone("스파오키즈", PixelRect(33, 500, 230, 577), StoreTier.DETAIL),
        StoreZone("무나무나", PixelRect(265, 600, 342, 651), StoreTier.DETAIL),
        StoreZone("뉴발란스키즈", PixelRect(33, 579, 231, 651), StoreTier.DETAIL),
        StoreZone("크리스피크림도넛", PixelRect(1129, 568, 1172, 708), StoreTier.DETAIL),
        StoreZone("차이홍", PixelRect(1202, 437, 1351, 546), StoreTier.DETAIL),
        StoreZone("코코이찌방야", PixelRect(1202, 550, 1351, 624), StoreTier.DETAIL),
        StoreZone("유아휴게실", PixelRect(33, 651, 105, 710), StoreTier.DETAIL),
        StoreZone("모던하우스(아동)", PixelRect(114, 651, 234, 771), StoreTier.DETAIL),
        StoreZone("모던하우스(욕실용품)", PixelRect(263, 679, 347, 810), StoreTier.DETAIL),
        StoreZone("레노마침구", PixelRect(654, 742, 695, 813), StoreTier.DETAIL),
        StoreZone("닥스침구", PixelRect(697, 742, 751, 810), StoreTier.DETAIL),
        StoreZone("박홍근홈패션", PixelRect(753, 742, 797, 810), StoreTier.DETAIL),
        StoreZone("코렐", PixelRect(845, 712, 937, 810), StoreTier.DETAIL),
        StoreZone("댄비", PixelRect(938, 712, 1031, 813), StoreTier.DETAIL),
        StoreZone("한식온기", PixelRect(1107, 780, 1292, 974), StoreTier.DETAIL),
        StoreZone("엔제리너스", PixelRect(74, 808, 225, 965), StoreTier.DETAIL),
        StoreZone("지누스", PixelRect(610, 847, 736, 921), StoreTier.DETAIL),
        StoreZone("템퍼", PixelRect(769, 844, 853, 921), StoreTier.DETAIL),
        StoreZone("씰리", PixelRect(853, 846, 929, 920), StoreTier.DETAIL),
        StoreZone("피자오", PixelRect(839, 923, 1021, 1022), StoreTier.DETAIL),
        StoreZone("쿠마키친", PixelRect(1021, 851, 1108, 1022), StoreTier.DETAIL)
    ),
    facilities = listOf(
        FacilityPoint(FacilityType.ESCALATOR, 796, 372),
        FacilityPoint(FacilityType.ESCALATOR, 365, 535),
        FacilityPoint(FacilityType.ESCALATOR, 1116, 639),
        FacilityPoint(FacilityType.ESCALATOR, 729, 710),
        FacilityPoint(FacilityType.STAIRS, 972, 360),
        FacilityPoint(FacilityType.STAIRS, 50, 765),
        FacilityPoint(FacilityType.STAIRS, 564, 971)
    ),
    corridor = listOf(
        381 to 384, 736 to 388, 1094 to 390, 1093 to 708, 793 to 707, 483 to 704, 378 to 616
    ),
    // floor3.png에서 배경색을 빼고 남는 매장동 실루엣을 그대로 추출한 외곽선(OpenCV contour trace)
    outline = listOf(
        0 to 0, 0 to 1021, 12 to 809, 73 to 809, 95 to 913,
        147 to 954, 224 to 962, 225 to 1021, 1350 to 1021, 1350 to 38,
        1057 to 38, 978 to 103, 622 to 103, 620 to 38, 13 to 38
    )
)

// ---------------------------------------------------------------------------
// 3-4. 전체 데이터 묶음
// ---------------------------------------------------------------------------

object IndoorMapData {
    val byFloor = mapOf(
        0 to floorB1StoreSet,
        1 to floor1StoreSet,
        2 to floor2StoreSet,
        3 to floor3StoreSet
    )
}

// ---------------------------------------------------------------------------
// 4. 줌 티어 렌더러 — 층 전환 + 줌 변경에 반응
// ---------------------------------------------------------------------------

class VectorFloorRenderer(private val map: GoogleMap) {
    private val activePolygons = mutableListOf<Polygon>()
    private val activeMarkers = mutableListOf<Marker>()
    private var lastRenderedFloor: Int? = null
    private var lastRenderedShowDetailLabels: Boolean? = null

    companion object {
        // 이 줌부터 "몰 전체 구조"가 보여야 함 → 모든 매장 폴리곤을 다 그림 (이름은 대형매장만)
        const val ZOOM_SHOW_STRUCTURE = 17.0f
        // 이 줌부터 작은 매장들 이름까지 전부 표시
        const val ZOOM_SHOW_DETAIL_LABELS = 18.8f
    }

    /**
     * 층/줌 변경 시 호출. 같은 층+같은 라벨단계면 다시 안 그림(깜빡임 방지).
     *
     * 동작 방식:
     *  - ZOOM_SHOW_STRUCTURE 미만: 아무것도 안 그림 (너무 축소된 상태)
     *  - ZOOM_SHOW_STRUCTURE 이상: 모든 매장 폴리곤(구획)을 그려서 몰 전체 구조가 보이게 함.
     *    단, 이름 라벨은 ANCHOR(대형) 매장만 표시 → 화면이 휑하지 않으면서도 안 복잡함.
     *  - ZOOM_SHOW_DETAIL_LABELS 이상: DETAIL(일반) 매장 이름까지 추가로 표시.
     *
     * @param floor        현재 표시 중인 층 (0=B1, 1,2,3)
     * @param currentZoom  현재 카메라 줌 레벨
     * @param projector    해당 층 이미지에 맞춰 만든 FloorPlanProjector
     */
    fun render(floor: Int, currentZoom: Float, projector: FloorPlanProjector) {
        val storeSet = IndoorMapData.byFloor[floor] ?: run { clear(); return }

        if (currentZoom < ZOOM_SHOW_STRUCTURE) {
            if (lastRenderedFloor != null) clear()
            return
        }

        val showDetailLabels = currentZoom >= ZOOM_SHOW_DETAIL_LABELS

        if (lastRenderedFloor == floor && lastRenderedShowDetailLabels == showDetailLabels) return

        clear()

        // 📍 건물 외곽선 흰색 마스크: 건물 발밑을 지나는 지하철 노선/도로 등 구글 기본 지도
        // 요소가 매장 사이 틈(통로/아트리움)으로 비쳐 보이지 않도록, 매장 폴리곤보다 먼저(맨 아래)
        // 건물 실루엣 전체를 흰색으로 채워서 깐다. 통로 구역은 이 흰 배경이 그대로 "빈 통로"처럼 보임.
        storeSet.outline?.let { outlinePoints ->
            val footprint = map.addPolygon(
                PolygonOptions()
                    .addAll(projector.toLatLngPolygon(outlinePoints))
                    .strokeWidth(0f)
                    .fillColor(0xFFFFFFFF.toInt())
                    .zIndex(-1f)
                    .clickable(false)
            )
            activePolygons.add(footprint)
        }

        // 매장 폴리곤: 티어 상관없이 전부 그림 (구조를 보여주기 위함)
        for (zone in storeSet.zones) {
            val points = zone.polygon?.let { projector.toLatLngPolygon(it) }
                ?: projector.toLatLngBounds(zone.pixelRect)
            val polygon = map.addPolygon(
                PolygonOptions()
                    .addAll(points)
                    .strokeWidth(if (zone.tier == StoreTier.ANCHOR) 2f else 1.2f)
                    .strokeColor(0x552B2118)
                    .fillColor(zone.fillColorArgb)
            )
            activePolygons.add(polygon)

            // 이름 라벨: ANCHOR는 항상, DETAIL은 showDetailLabels일 때만
            val shouldShowLabel = zone.tier == StoreTier.ANCHOR || showDetailLabels
            if (shouldShowLabel) {
                val labelIcon = TextMarkerFactory.createLabelBitmap(
                    text = zone.name,
                    textSizeSp = if (zone.tier == StoreTier.ANCHOR) 13f else 10f
                )
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(projector.rectCenter(zone.pixelRect))
                        .icon(BitmapDescriptorFactory.fromBitmap(labelIcon))
                        .anchor(0.5f, 0.5f)
                        .flat(true)
                )
                if (marker != null) activeMarkers.add(marker)
            }
        }

        // 시설 아이콘(에스컬레이터/계단)은 구조가 보이는 시점부터 항상 표시
        for (facility in storeSet.facilities) {
            val icon = FacilityIconFactory.createIconBitmap(facility.type)
            val marker = map.addMarker(
                MarkerOptions()
                    .position(projector.toLatLng(facility.px, facility.py))
                    .icon(BitmapDescriptorFactory.fromBitmap(icon))
                    .anchor(0.5f, 0.5f)
                    .flat(true)
                    .zIndex(10f)
            )
            if (marker != null) activeMarkers.add(marker)
        }

        lastRenderedFloor = floor
        lastRenderedShowDetailLabels = showDetailLabels
    }

    fun clear() {
        activePolygons.forEach { it.remove() }
        activePolygons.clear()
        activeMarkers.forEach { it.remove() }
        activeMarkers.clear()
        lastRenderedFloor = null
        lastRenderedShowDetailLabels = null
    }
}

// ---------------------------------------------------------------------------
// 5-0. 시설 아이콘(에스컬레이터/계단) → 작은 원형 비트맵 마커
// ---------------------------------------------------------------------------

object FacilityIconFactory {
    private val cache = mutableMapOf<FacilityType, android.graphics.Bitmap>()

    fun createIconBitmap(type: FacilityType): android.graphics.Bitmap {
        cache[type]?.let { return it }

        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val sizePx = (26 * density).toInt().coerceAtLeast(1)

        val bgColor: Int
        val label: String
        when (type) {
            FacilityType.ESCALATOR -> {
                bgColor = android.graphics.Color.parseColor("#E67E22") // 주황
                label = "E"
            }
            FacilityType.STAIRS -> {
                bgColor = android.graphics.Color.parseColor("#27AE60") // 초록
                label = "S"
            }
        }

        val bitmap = android.graphics.Bitmap.createBitmap(
            sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)

        val bgPaint = android.graphics.Paint().apply {
            color = bgColor
            isAntiAlias = true
        }
        val radius = sizePx / 2f
        canvas.drawCircle(radius, radius, radius, bgPaint)

        val borderPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.5f * density
            isAntiAlias = true
        }
        canvas.drawCircle(radius, radius, radius - (1f * density), borderPaint)

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 13f * density
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val fm = textPaint.fontMetrics
        canvas.drawText(label, radius, radius - (fm.ascent + fm.descent) / 2f, textPaint)

        cache[type] = bitmap
        return bitmap
    }
}

// ---------------------------------------------------------------------------
// 5. 매장명 텍스트 → 비트맵 마커 아이콘
// ---------------------------------------------------------------------------

object TextMarkerFactory {
    fun createLabelBitmap(text: String, textSizeSp: Float): android.graphics.Bitmap {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val textSizePx = textSizeSp * density

        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#1A1A1A")
            textSize = textSizePx
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val padding = 6 * density
        val textWidth = paint.measureText(text)
        val fm = paint.fontMetrics
        val textHeight = fm.descent - fm.ascent

        val bmpWidth = (textWidth + padding * 2).toInt().coerceAtLeast(1)
        val bmpHeight = (textHeight + padding * 2).toInt().coerceAtLeast(1)

        val bitmap = android.graphics.Bitmap.createBitmap(
            bmpWidth, bmpHeight, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)

        val bgPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(230, 255, 255, 255)
            isAntiAlias = true
        }
        val rect = android.graphics.RectF(0f, 0f, bmpWidth.toFloat(), bmpHeight.toFloat())
        canvas.drawRoundRect(rect, bmpHeight / 2f, bmpHeight / 2f, bgPaint)

        canvas.drawText(text, bmpWidth / 2f, bmpHeight / 2f - fm.ascent / 2f - fm.descent / 2f, paint)
        return bitmap
    }
}