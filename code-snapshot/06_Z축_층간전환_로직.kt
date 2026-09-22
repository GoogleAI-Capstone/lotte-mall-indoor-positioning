package com.example.lottemallindoor

// ============================================================================
// [발췌 정리본] Z축(층) 판별 · 전환 로직
// ----------------------------------------------------------------------------
// 원본: MainActivity.kt (com.example.lottemallindoor)
// 목적: "실내에서 몇 층에 있는가"를 GPS 고도값 대신 BLE 비콘 신호 세기(RSSI)로
//       추정하는 로직만 따로 뽑아 정리한 참고용 코드다.
//       이 파일 자체는 그대로 컴파일되는 완결 파일이 아니라(다른 클래스 멤버와
//       뒤섞여 있던 부분을 발췌한 것), 로직 이해 및 보관용이다.
//
// 핵심 아이디어
//   - X, Y(위경도)는 GPS/FusedLocationProvider가 담당.
//   - Z(층)는 완전히 다른 센서 소스인 "고정 설치된 BLE 비콘의 RSSI 비교"로 담당.
//   - 층이 바뀌면 3D로 이동하는 게 아니라, currentDisplayedFloor라는 정수 하나가
//     Z축 역할을 하며, 그 값에 맞는 "층 전용 2D 평면 지도"로 화면을 통째로 교체한다.
// ============================================================================

import java.util.concurrent.ConcurrentHashMap

// ----------------------------------------------------------------------------
// 1) 비콘 → 층(Z) 매핑 테이블
//    brain1~3 = 1층, brain4~6 = 2층, brain7~9 = 3층, brain10 = B1(지하1층)
// ----------------------------------------------------------------------------
data class BeaconSpec(val name: String, val floor: Int)

private val beaconMap = mapOf(
    "C3:00:00:6A:5F:DE" to BeaconSpec("brain1", 1),
    "C3:00:00:6A:5F:DF" to BeaconSpec("brain2", 1),
    "C3:00:00:6A:5F:DD" to BeaconSpec("brain3", 1),
    "C3:00:00:6A:5F:DC" to BeaconSpec("brain4", 2),
    "C3:00:00:6A:5F:9D" to BeaconSpec("brain5", 2),
    "C3:00:00:6A:5F:9C" to BeaconSpec("brain6", 2),
    "C3:00:00:6A:5F:A7" to BeaconSpec("brain7", 3),
    "C3:00:00:6A:5F:A6" to BeaconSpec("brain8", 3),
    "C3:00:00:6A:5F:A2" to BeaconSpec("brain9", 3),
    "C3:00:00:6A:5F:A3" to BeaconSpec("brain10", 0)   // 지하 1층
)

// MAC 주소 -> (마지막 수신 RSSI, 마지막 수신 시각[ms])
private val brainBeaconRssiMap = ConcurrentHashMap<String, Pair<Int, Long>>()

// ----------------------------------------------------------------------------
// 2) BLE 스캔 결과에서 brain 비콘만 걸러 RSSI/시각을 기록 (scanCallback 발췌)
// ----------------------------------------------------------------------------
// override fun onScanResult(callbackType: Int, result: ScanResult) {
//     val macAddress = result.device.address?.uppercase()?.trim() ?: ""
//     val rssi = result.rssi
//     val currentTime = System.currentTimeMillis()
//
//     if (beaconMap.containsKey(macAddress)) {
//         brainBeaconRssiMap[macAddress] = Pair(rssi, currentTime)
//     }
// }

// ----------------------------------------------------------------------------
// 3) 층 전환 안정화 상수/상태
//    - 4000ms: 이 시간 이내에 수신된 비콘만 "살아있는" 신호로 인정
//    - FLOOR_SWITCH_CONFIRM_MS(2000ms): 새 층 후보가 이 시간 이상 계속 1위를
//      유지해야만 실제로 층을 전환한다 (에스컬레이터/아트리움 근처에서 순간적으로
//      다른 층 신호가 잘못 강하게 잡히는 오탐을 막기 위한 디바운스)
// ----------------------------------------------------------------------------
private const val FLOOR_SWITCH_CONFIRM_MS = 2000L
private var pendingFloor: Int? = null
private var pendingFloorSince: Long = 0L
private var currentDisplayedFloor: Int = -1   // = 현재 확정된 Z(층)

/**
 * Z(층) 자동 판별의 핵심 함수.
 * 1초 주기(uiUpdateRunnable)로 호출된다.
 */
private fun checkAndSwitchFloor() {
    val currentTime = System.currentTimeMillis()

    // (1) 최근 4초 이내 신호 중 RSSI가 가장 강한(=가장 가까운) 비콘 1개를 고른다.
    val strongestBrain = brainBeaconRssiMap.entries
        .filter { (currentTime - it.value.second) < 4000 }
        .maxByOrNull { it.value.first }

    if (strongestBrain == null) {
        // 신호가 완전히 끊김(매장 안쪽처럼 에스컬레이터에서 먼 곳)
        // -> 층을 바꾸지 않고 마지막으로 확정된 층에 지도를 그대로 고정한다.
        pendingFloor = null
        return
    }

    // (2) 그 비콘이 속한 층(Z 후보값)을 찾는다.
    val targetFloor = beaconMap[strongestBrain.key]?.floor ?: return

    if (targetFloor == currentDisplayedFloor) {
        // 이미 그 층이면 할 일 없음.
        pendingFloor = null
        return
    }

    if (pendingFloor != targetFloor) {
        // (3) 지금까지와 다른 층 신호가 새로 감지됨 -> 확인 타이머 시작 (아직 전환 안 함)
        pendingFloor = targetFloor
        pendingFloorSince = currentTime
        return
    }

    // (4) 같은 후보 층이 FLOOR_SWITCH_CONFIRM_MS(2초) 이상 유지됐으면 실제 전환 확정.
    if (currentTime - pendingFloorSince >= FLOOR_SWITCH_CONFIRM_MS) {
        updateFloorOverlay(targetFloor)   // Z(층) 확정 반영 -> 지도 레이어 교체
        pendingFloor = null
    }
}

// ----------------------------------------------------------------------------
// 4) Z(층) 확정 시 화면(지도 레이어)을 교체하는 함수
// ----------------------------------------------------------------------------
private fun updateFloorOverlay(newFloor: Int) {
    if (currentDisplayedFloor == newFloor) return

    currentDisplayedFloor = newFloor
    // tvFloorStatus.text = if (newFloor == 0) "현재 층: B1" else "현재 층: ${newFloor}층"

    // 층이 바뀔 때마다 그 층 전용 FloorPlanProjector로 벡터 매장 레이어를 다시 그림
    // refreshVectorLayer()
}

// ----------------------------------------------------------------------------
// 5) 수동 층 전환 (사용자가 B1/1/2/3 버튼을 직접 누른 경우)
//    10초간 자동판별을 무시하고 강제로 그 층을 보여준 뒤 자동판별로 복귀한다.
// ----------------------------------------------------------------------------
// private fun onManualFloorSelect(floor: Int) {
//     isUserOverride = true
//     updateFloorOverlay(floor)
//     pendingFloor = null
//     resetHandler.removeCallbacks(resetRunnable)
//     resetHandler.postDelayed(resetRunnable, 10000)  // 10초 후 isUserOverride = false
// }

// ----------------------------------------------------------------------------
// 6) Z(층)마다 독립된 2D 좌표 변환기를 미리 만들어 두는 부분 (IndoorVectorMap.kt 연동)
//    -> "3D의 Z축 이동"을 "층별 2D 평면 레이어 스위칭"으로 구현한 핵심 지점.
// ----------------------------------------------------------------------------
// private var floorProjectors: Map<Int, FloorPlanProjector> = emptyMap()
//
// private fun rebuildFloorProjectors() {
//     val sizesByFloor = mapOf(
//         0 to (floorB1StoreSet.imageWidth to floorB1StoreSet.imageHeight),  // B1
//         1 to (floor1StoreSet.imageWidth to floor1StoreSet.imageHeight),    // 1층
//         2 to (floor2StoreSet.imageWidth to floor2StoreSet.imageHeight),    // 2층
//         3 to (floor3StoreSet.imageWidth to floor3StoreSet.imageHeight)     // 3층
//     )
//     floorProjectors = sizesByFloor.mapValues { (_, size) ->
//         val (w, h) = size
//         FloorPlanProjector.fromThreePoints(
//             Triple(0, 0, REF_NW_LATLNG),
//             Triple(w, h, REF_SE_LATLNG),
//             Triple(w, 0, REF_NE_LATLNG)
//         ).rotated180Around(w / 2, h / 2)
//          .withCorrection(w / 2, h / 2, calibRotationDeg, calibScaleMultiplier)
//     }
// }

// ============================================================================
// 정리: Z축(층) 전환 파이프라인 한눈에 보기
// ----------------------------------------------------------------------------
//  BLE 광고 패킷 수신(MAC, RSSI, 시각)
//     -> beaconMap으로 brain 비콘만 필터링, brainBeaconRssiMap에 저장
//     -> checkAndSwitchFloor(): 4초 이내 신호 중 RSSI 최댓값 비콘 선정
//     -> 그 비콘의 floor 값 = Z 후보
//     -> 같은 후보가 2초(FLOOR_SWITCH_CONFIRM_MS) 유지되면 확정
//     -> updateFloorOverlay(): currentDisplayedFloor 갱신 (Z 확정)
//     -> refreshVectorLayer(): floorProjectors[Z]로 해당 층 벡터지도 다시 그림
//     -> (사용자가 버튼을 누르면 이 파이프라인을 10초간 우회하고 강제 지정)
// ============================================================================
