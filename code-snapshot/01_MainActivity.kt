package com.example.lottemallindoor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // UI View
    private lateinit var tvFloorStatus: TextView
    private lateinit var tvBeaconInfo: TextView
    private lateinit var tvCalibInfo: TextView
    private lateinit var btnFloorB1: Button
    private lateinit var btnFloor1: Button
    private lateinit var btnFloor2: Button
    private lateinit var btnFloor3: Button
    private lateinit var btnDebugToggle: Button
    private lateinit var debugPanel: android.widget.LinearLayout

    // 지도 요소
    private var currentLocationMarker: Marker? = null

    // 층 상태 (0 = B1, 1/2/3 = 지상층)
    private var currentDisplayedFloor: Int = -1
    private var isUserOverride: Boolean = false

    // 수동 버튼 클릭 후 10초 복구 타이머
    private val resetHandler = Handler(Looper.getMainLooper())
    private val resetRunnable = Runnable {
        isUserOverride = false
        checkAndSwitchFloor()
    }

    // UI 업데이트 주기적 실행을 위한 핸들러 (깜빡임 방지)
    private val uiUpdateHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateBrainDashboard()
            if (!isUserOverride) {
                checkAndSwitchFloor()
            }
            uiUpdateHandler.postDelayed(this, 1000) // 1초마다 UI 갱신
        }
    }

    // 📍 초기 카메라 위치용 (Google 지도 "롯데몰 광교점" 리스팅 좌표로 갱신)
    private val lotteMallCenter = LatLng(37.2863246, 127.0562904)

    // 📍 실측 기준점 3개 (구글맵 long-press로 직접 측정한 좌표)
    // NW: Tesla Supercharger 근처(왼쪽 위) / SE: 이철헤어커커 근처(오른쪽 아래) / NE: 오른쪽 위
    private val REF_NW_LATLNG = LatLng(37.286916, 127.055815)
    private val REF_SE_LATLNG = LatLng(37.285578, 127.056197)
    private val REF_NE_LATLNG = LatLng(37.2861185, 127.0568191)

    // 📍 검증용 기준점 2개 — Google 지도에서 "이철헤어커커 광교롯데몰점" / "UNIQLO Lotte Mall
    // Gwanggyo" 실제 업체 위치를 검색해서 얻은 좌표(실내 매장이라 GPS 실측만큼 정밀하진 않지만,
    // 도면 픽셀상 1층의 정반대 끝(서쪽/동쪽)에 있는 두 매장이라 회전·축척 오차를 눈으로 확인하기
    // 좋음). 디버그 모드에서 "예측 위치(주황)"와 "구글 실측 위치(초록)"를 함께 찍어서 얼마나
    // 어긋나는지 보고, 화면의 회전/축척 보정 버튼으로 좁혀가면 됨.
    private val VERIFY_LEE_HAIR_REAL = LatLng(37.2855895, 127.0561785)      // 이철헤어커커
    private val VERIFY_UNIQLO_REAL = LatLng(37.2862441, 127.0559215)       // 유니클로
    private val VERIFY_LEE_HAIR_PX = 53 to 301   // floor1StoreSet "이철헤어커커" 사각형 중심
    private val VERIFY_UNIQLO_PX = 1229 to 521   // floor1StoreSet "유니클로" 사각형 중심

    // 📍 실측 기준점만으로 못 잡는 잔여 회전/축척 오차를 현장에서 조정하는 보정값.
    // 디버그 패널의 회전+/-, 축척+/- 버튼으로 바뀌며, 위성지도와 눈으로 맞춰본 뒤
    // 여기 초기값을 최종적으로 확정해서 하드코딩하면 됨.
    private var calibRotationDeg = 0.0
    private var calibScaleMultiplier = 1.0
    private var debugModeOn = false
    private val debugMarkers = mutableListOf<Marker>()

    // BLE 비콘 관련
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var totalScanCount = 0
    private var lastScannedInfo: String = "없음"

    data class BeaconSpec(val name: String, val floor: Int)

    // brain1~3 → 1층, brain4~6 → 2층, brain7~9 → 3층, brain10 → B1(지하1층, 비콘 1개뿐)
    // brain0(5E:48:0D)은 더 이상 사용하지 않아 제거함
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
        "C3:00:00:6A:5F:A3" to BeaconSpec("brain10", 0)
    )

    private val brainBeaconRssiMap = ConcurrentHashMap<String, Pair<Int, Long>>()

    // ────────────────────────────────────────────────────────────────
    // 📍 벡터 실내지도 (IndoorVectorMap.kt) 연동
    // ────────────────────────────────────────────────────────────────
    private lateinit var vectorRenderer: VectorFloorRenderer

    // 층별 프로젝터: 실측 3점(NW/SE/NE)으로 가로축척+세로축척+회전을 각각 정확히 계산
    // (2점 방식은 가로세로 축척이 같다고 가정해서 오른쪽으로 갈수록 어긋났던 원인이었음)
    // calibRotationDeg/calibScaleMultiplier가 바뀔 때마다 rebuildFloorProjectors()로 다시 계산.
    private var floorProjectors: Map<Int, FloorPlanProjector> = emptyMap()

    private fun rebuildFloorProjectors() {
        val sizesByFloor = mapOf(
            0 to (floorB1StoreSet.imageWidth to floorB1StoreSet.imageHeight),
            1 to (floor1StoreSet.imageWidth to floor1StoreSet.imageHeight),
            2 to (floor2StoreSet.imageWidth to floor2StoreSet.imageHeight),
            3 to (floor3StoreSet.imageWidth to floor3StoreSet.imageHeight)
        )
        floorProjectors = sizesByFloor.mapValues { (_, size) ->
            val (w, h) = size
            FloorPlanProjector.fromThreePoints(
                Triple(0, 0, REF_NW_LATLNG),   // 왼쪽 위
                Triple(w, h, REF_SE_LATLNG),   // 오른쪽 아래
                Triple(w, 0, REF_NE_LATLNG)    // 오른쪽 위
            ).rotated180Around(w / 2, h / 2) // 제자리(정중앙)에서 180도 회전
                .withCorrection(w / 2, h / 2, calibRotationDeg, calibScaleMultiplier)
        }
    }

    private fun refreshVectorLayer() {
        if (!::mMap.isInitialized) return
        val floor = currentDisplayedFloor
        val projector = floorProjectors[floor] ?: return
        val zoom = mMap.cameraPosition.zoom
        vectorRenderer.render(floor, zoom, projector)
        if (debugModeOn) renderDebugOverlay()
    }

    // ────────────────────────────────────────────────────────────────
    // 📍 디버그/보정 모드: 실측 기준점 + 검증용 매장 위치(예측 vs 구글 실측)를
    // 화면에 마커로 찍어서, 회전/축척이 얼마나 맞는지 눈으로 확인하고 조정할 수 있게 함.
    // ────────────────────────────────────────────────────────────────
    private fun clearDebugOverlay() {
        debugMarkers.forEach { it.remove() }
        debugMarkers.clear()
    }

    private fun addDebugMarker(pos: LatLng, title: String, hue: Float) {
        val marker = mMap.addMarker(
            MarkerOptions()
                .position(pos)
                .title(title)
                .icon(BitmapDescriptorFactory.defaultMarker(hue))
                .zIndex(20f)
        )
        if (marker != null) debugMarkers.add(marker)
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
        return results[0].toDouble()
    }

    private fun renderDebugOverlay() {
        clearDebugOverlay()
        if (!::mMap.isInitialized) return

        addDebugMarker(REF_NW_LATLNG, "실측 기준점 NW", BitmapDescriptorFactory.HUE_YELLOW)
        addDebugMarker(REF_SE_LATLNG, "실측 기준점 SE", BitmapDescriptorFactory.HUE_YELLOW)
        addDebugMarker(REF_NE_LATLNG, "실측 기준점 NE", BitmapDescriptorFactory.HUE_YELLOW)

        addDebugMarker(VERIFY_LEE_HAIR_REAL, "구글 실측: 이철헤어커커", BitmapDescriptorFactory.HUE_GREEN)
        addDebugMarker(VERIFY_UNIQLO_REAL, "구글 실측: 유니클로", BitmapDescriptorFactory.HUE_GREEN)

        val floor1Projector = floorProjectors[1]
        var errLee = -1.0
        var errUniqlo = -1.0
        if (floor1Projector != null) {
            val predLee = floor1Projector.toLatLng(VERIFY_LEE_HAIR_PX.first, VERIFY_LEE_HAIR_PX.second)
            val predUniqlo = floor1Projector.toLatLng(VERIFY_UNIQLO_PX.first, VERIFY_UNIQLO_PX.second)
            addDebugMarker(predLee, "예측: 이철헤어커커", BitmapDescriptorFactory.HUE_ORANGE)
            addDebugMarker(predUniqlo, "예측: 유니클로", BitmapDescriptorFactory.HUE_ORANGE)
            errLee = distanceMeters(predLee, VERIFY_LEE_HAIR_REAL)
            errUniqlo = distanceMeters(predUniqlo, VERIFY_UNIQLO_REAL)
        }

        val proj = floorProjectors[currentDisplayedFloor]
        val rot = proj?.currentRotationDeg() ?: 0.0
        val scale = proj?.currentScaleMPerPx() ?: 0.0
        tvCalibInfo.visibility = android.view.View.VISIBLE
        tvCalibInfo.text = "회전보정 %.1f° / 축척배율 %.3f (실효각 %.1f°, %.4f m/px)\n오차: 이철 %.1fm / 유니클로 %.1fm".format(
            calibRotationDeg, calibScaleMultiplier, rot, scale, errLee, errUniqlo
        )
        android.util.Log.d(
            "IndoorCalib",
            "rotDeg=$calibRotationDeg scaleMul=$calibScaleMultiplier effRot=$rot mPerPx=$scale errLee=$errLee errUniqlo=$errUniqlo"
        )
    }

    private fun setDebugMode(on: Boolean) {
        debugModeOn = on
        debugPanel.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        tvCalibInfo.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        if (on) {
            renderDebugOverlay()
        } else {
            clearDebugOverlay()
        }
    }

    private fun nudgeCalibration(rotDelta: Double = 0.0, scaleDelta: Double = 0.0) {
        calibRotationDeg += rotDelta
        calibScaleMultiplier = (calibScaleMultiplier + scaleDelta).coerceIn(0.5, 1.5)
        rebuildFloorProjectors()
        vectorRenderer.clear()
        refreshVectorLayer()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        enableMyLocationOnMap()
        startLocationUpdates()
        startBleScan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvFloorStatus = findViewById(R.id.tvFloorStatus)
        tvBeaconInfo = findViewById(R.id.tvBeaconInfo)
        tvCalibInfo = findViewById(R.id.tvCalibInfo)
        btnFloorB1 = findViewById(R.id.btnFloorB1)
        btnFloor1 = findViewById(R.id.btnFloor1)
        btnFloor2 = findViewById(R.id.btnFloor2)
        btnFloor3 = findViewById(R.id.btnFloor3)
        btnDebugToggle = findViewById(R.id.btnDebugToggle)
        debugPanel = findViewById(R.id.debugPanel)

        btnFloorB1.setOnClickListener { onManualFloorSelect(0) }
        btnFloor1.setOnClickListener { onManualFloorSelect(1) }
        btnFloor2.setOnClickListener { onManualFloorSelect(2) }
        btnFloor3.setOnClickListener { onManualFloorSelect(3) }

        btnDebugToggle.setOnClickListener { setDebugMode(!debugModeOn) }
        findViewById<Button>(R.id.btnRotMinus).setOnClickListener { nudgeCalibration(rotDelta = -0.5) }
        findViewById<Button>(R.id.btnRotPlus).setOnClickListener { nudgeCalibration(rotDelta = 0.5) }
        findViewById<Button>(R.id.btnScaleMinus).setOnClickListener { nudgeCalibration(scaleDelta = -0.01) }
        findViewById<Button>(R.id.btnScalePlus).setOnClickListener { nudgeCalibration(scaleDelta = 0.01) }
        findViewById<Button>(R.id.btnToggleSatellite).setOnClickListener {
            if (::mMap.isInitialized) {
                mMap.mapType = if (mMap.mapType == GoogleMap.MAP_TYPE_SATELLITE)
                    GoogleMap.MAP_TYPE_NORMAL else GoogleMap.MAP_TYPE_SATELLITE
            }
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    val userLatLng = LatLng(location.latitude, location.longitude)
                    updateUserMarker(userLatLng)
                }
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.mapType = GoogleMap.MAP_TYPE_NORMAL
        mMap.uiSettings.isZoomControlsEnabled = true

        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(lotteMallCenter, 18.5f))

        vectorRenderer = VectorFloorRenderer(mMap)
        rebuildFloorProjectors()

        // 줌 변경 시마다 벡터 매장 레이어 갱신
        // (17.0=전체구조+대형매장명, 18.8=전체 매장명까지 표시)
        mMap.setOnCameraIdleListener {
            refreshVectorLayer()
        }

        updateFloorOverlay(1)
        checkAndRequestPermissions()
    }

    private fun updateFloorOverlay(newFloor: Int) {
        if (currentDisplayedFloor == newFloor) return

        // 📍 사진 GroundOverlay는 더 이상 쓰지 않음 — 전 층 벡터(Polygon+라벨)로만 표시
        currentDisplayedFloor = newFloor
        tvFloorStatus.text = if (newFloor == 0) "현재 층: B1" else "현재 층: ${newFloor}층"

        // 층이 바뀔 때마다 벡터 매장 레이어 다시 그림
        refreshVectorLayer()
    }

    private fun onManualFloorSelect(floor: Int) {
        isUserOverride = true
        updateFloorOverlay(floor)
        pendingFloor = null

        resetHandler.removeCallbacks(resetRunnable)
        resetHandler.postDelayed(resetRunnable, 10000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            totalScanCount++

            val macAddress = result.device.address?.uppercase()?.trim() ?: ""

            val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED) {
                    result.device.name ?: result.scanRecord?.deviceName ?: ""
                } else {
                    ""
                }
            } else {
                result.device.name ?: result.scanRecord?.deviceName ?: ""
            }.trim()

            val rssi = result.rssi
            val currentTime = System.currentTimeMillis()

            var matchedMac: String? = null
            lastScannedInfo = "MAC: $macAddress, Name: $deviceName"

            if (beaconMap.containsKey(macAddress)) {
                matchedMac = macAddress
            } else if (deviceName.isNotEmpty()) {
                val foundEntry = beaconMap.entries.firstOrNull {
                    it.value.name.equals(deviceName, ignoreCase = true)
                }
                if (foundEntry != null) {
                    matchedMac = foundEntry.key
                }
            }

            if (matchedMac != null) {
                brainBeaconRssiMap[matchedMac] = Pair(rssi, currentTime)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            runOnUiThread {
                tvBeaconInfo.text = "BLE 스캔 실패! 오류 코드: $errorCode"
            }
        }
    }

    private fun updateBrainDashboard() {
        val currentTime = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("=== Brain 비콘 실시간 수신 (총 패킷: ${totalScanCount}개) ===\n")

        val sortedBeacons = beaconMap.entries.sortedBy { it.value.name }

        var activeCount = 0
        for (entry in sortedBeacons) {
            val mac = entry.key
            val spec = entry.value
            val rssiData = brainBeaconRssiMap[mac]

            if (rssiData != null && (currentTime - rssiData.second) < 4000) {
                sb.append("● [${spec.name} (${spec.floor}층)] : ${rssiData.first} dBm\n")
                activeCount++
            } else {
                sb.append("○ [${spec.name} (${spec.floor}층)] : 미수신\n")
            }
        }

        if (activeCount == 0) {
            sb.append("\n※ 감지된 brain 비콘 신호가 없습니다.\n")
            sb.append("진단(마지막 신호): $lastScannedInfo")
        }

        tvBeaconInfo.text = sb.toString()
    }

    // 비콘이 에스컬레이터 근처에만 있어서, 자기 층 신호가 끊기는 바로 그 순간
    // 뚫려있는 에스컬레이터/아트리움 구조를 타고 위·아래·옆 층 비콘의 잔상 신호가
    // 스치듯 한 번 더 세게 잡히는 경우가 있음. 그 한 번의 스침으로 바로 층을 바꾸지 않도록,
    // "다른 층 신호가 이 시간 이상 계속 가장 강하게 유지될 때만" 실제로 전환한다.
    private val FLOOR_SWITCH_CONFIRM_MS = 2000L
    private var pendingFloor: Int? = null
    private var pendingFloorSince: Long = 0L

    private fun checkAndSwitchFloor() {
        val currentTime = System.currentTimeMillis()

        val strongestBrain = brainBeaconRssiMap.entries
            .filter { (currentTime - it.value.second) < 4000 }
            .maxByOrNull { it.value.first }

        if (strongestBrain == null) {
            // 신호가 완전히 끊김(매장 안쪽처럼 에스컬레이터에서 먼 곳) → 층 전환하지 않고
            // 마지막으로 확정된 층에 지도를 그대로 고정시킨다.
            pendingFloor = null
            return
        }

        val targetFloor = beaconMap[strongestBrain.key]?.floor ?: return

        if (targetFloor == currentDisplayedFloor) {
            pendingFloor = null
            return
        }

        if (pendingFloor != targetFloor) {
            // 지금까지와 다른 층 신호가 새로 감지됨 — 확인 타이머 시작 (아직 전환 안 함)
            pendingFloor = targetFloor
            pendingFloorSince = currentTime
            return
        }

        if (currentTime - pendingFloorSince >= FLOOR_SWITCH_CONFIRM_MS) {
            updateFloorOverlay(targetFloor)
            pendingFloor = null
        }
    }

    private fun startBleScan() {
        if (isScanning) return

        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        }

        if (bluetoothLeScanner == null) {
            tvBeaconInfo.text = "블루투스를 활성화해 주세요."
            return
        }

        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            tvBeaconInfo.text = "위치 권한이 필요합니다."
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasScan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val hasConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

            if (!hasScan || !hasConnect) {
                tvBeaconInfo.text = "블루투스 스캔/연결 권한이 필요합니다."
                return
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        bluetoothLeScanner?.startScan(null, settings, scanCallback)
        isScanning = true
        tvBeaconInfo.text = "BLE 스캔 진행 중... (Brain 비콘 신호 탐색 중)"
    }

    private fun stopBleScan() {
        if (!isScanning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) return

        bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
    }

    private fun updateUserMarker(latLng: LatLng) {
        if (currentLocationMarker == null) {
            val markerOptions = MarkerOptions()
                .position(latLng)
                .title("현재 위치")
                .anchor(0.5f, 0.5f)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            currentLocationMarker = mMap.addMarker(markerOptions)
        } else {
            currentLocationMarker?.position = latLng
        }
    }

    private fun enableMyLocationOnMap() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            mMap.isMyLocationEnabled = true
            mMap.uiSettings.isMyLocationButtonEnabled = true
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 2000L
            ).setMinUpdateIntervalMillis(1000L).build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest, locationCallback, Looper.getMainLooper()
            )
        }
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopBleScan()
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)
    }

    override fun onResume() {
        super.onResume()

        if (::mMap.isInitialized) {
            mMap.mapType = GoogleMap.MAP_TYPE_NORMAL
            mMap.animateCamera(CameraUpdateFactory.zoomBy(0.001f))

            startLocationUpdates()
            startBleScan()
        }
        uiUpdateHandler.post(uiUpdateRunnable)
    }
}