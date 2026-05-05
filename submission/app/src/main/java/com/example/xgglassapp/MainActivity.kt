package com.example.xgglassapp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.universalglasses.appcontract.AIApiSettings
import com.universalglasses.appcontract.HostEnvironment
import com.universalglasses.appcontract.HostKind
import com.universalglasses.appcontract.UniversalAppContext
import com.universalglasses.appcontract.UniversalAppEntry
import com.universalglasses.appcontract.UserSettingField
import com.universalglasses.appcontract.UserSettingInputType
import com.universalglasses.appcontract.commandsWithDefaults
import com.universalglasses.core.ConnectionState
import com.universalglasses.core.ExternalActivityBridge
import com.universalglasses.core.ExternalActivityResult
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesClient
import com.universalglasses.core.GlassesModel
import com.universalglasses.device.frame.embedded.EmbeddedFrameGlassesClient
import com.universalglasses.device.rayneo.installer.RayNeoApkSource
import com.universalglasses.device.rayneo.installer.RayNeoInstallerConfig
import com.universalglasses.device.rayneo.installer.RayNeoInstallerGlassesClient
import com.universalglasses.device.rokid.RokidGlassesClient
import com.universalglasses.device.omi.OmiGlassesClient
import com.universalglasses.device.sim.SimulatorGlassesClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var spDevice: Spinner
    private lateinit var btnConnect: Button
    private lateinit var ivPreview: ImageView
    private lateinit var swPreview: Switch
    private lateinit var tvDisplay: TextView
    private lateinit var tvDisplayTitle: TextView
    private lateinit var etRayNeoIp: EditText
    private lateinit var llCommands: LinearLayout
    private lateinit var tvSettingsTitle: TextView
    private lateinit var llSettings: LinearLayout
    private lateinit var btnApplySettings: Button
    private lateinit var btnCopyDebugReport: Button

    // Rokid runtime credential UI
    private lateinit var llRokidConfig: LinearLayout
    private lateinit var btnPickSnLicense: Button
    private lateinit var tvSnLicenseFile: TextView
    private lateinit var etRokidSecret: EditText

    private val entry: UniversalAppEntry? by lazy { loadEntryOrNull() }

    /** Map of setting key → EditText widget, populated by [renderSettings]. */
    private val settingEdits = mutableMapOf<String, EditText>()

    /** Current applied settings (key → value). */
    private var appliedSettings: Map<String, String> = emptyMap()

    private var client: GlassesClient? = null
    private var connectJob: Job? = null
    private var stateJob: Job? = null
    private var eventsJob: Job? = null
    private var pendingConnectModel: GlassesModel? = null
    private var simulatorAutoConnectRequested: Boolean = false
    private var simulatorAutoValidationStarted: Boolean = false
    private var externalActivityContinuation: kotlinx.coroutines.CancellableContinuation<ExternalActivityResult>? = null
    private val externalActivityMutex = Mutex()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        appendLog("Permission result: ${result.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}")
        val allGranted = result.values.all { it }
        if (!allGranted) {
            appendLog("Permissions denied; cannot connect.")
            tvStatus.text = "Status: permissions denied"
            pendingConnectModel = null
            return@registerForActivityResult
        }
        val model = pendingConnectModel
        pendingConnectModel = null
        if (model != null) connect(model)
    }

    override fun onStart() {
        super.onStart()
        maybeAutoConnectSimulator()
    }

    private val externalActivityLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        externalActivityContinuation?.let { continuation ->
            if (continuation.isActive) {
                continuation.resume(
                    ExternalActivityResult(
                        resultCode = result.resultCode,
                        data = result.data,
                    )
                )
            }
        }
        externalActivityContinuation = null
    }

    /** Launcher for picking the Rokid SN license (.lc) file at runtime. */
    private val pickSnLicenseLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                appendLog("Rokid: selected file is empty.")
                return@registerForActivityResult
            }
            // Persist the .lc bytes into internal storage so we survive app restarts.
            val lcFile = File(filesDir, ROKID_LC_FILENAME)
            lcFile.writeBytes(bytes)
            // Extract a display name for the UI.
            val displayName = queryFileName(uri) ?: "sn_license.lc"
            rokidPrefs.edit().putString(PREF_ROKID_LC_DISPLAY_NAME, displayName).apply()
            tvSnLicenseFile.text = displayName
            appendLog("Rokid: SN license loaded (${bytes.size} bytes).")
        } catch (e: Exception) {
            appendLog("Rokid: failed to read file – ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        spDevice = findViewById(R.id.spDevice)
        btnConnect = findViewById(R.id.btnConnect)
        ivPreview = findViewById(R.id.ivPreview)
        swPreview = findViewById(R.id.swPreview)
        swPreview.setOnCheckedChangeListener { _, isChecked ->
            // Enable/disable video stream based on switch state
            val simClient = client as? SimulatorGlassesClient ?: return@setOnCheckedChangeListener
            scope.launch {
                if (isChecked) {
                    simClient.setVideoPreviewEnabled(true) { bitmap ->
                        runOnUiThread {
                            ivPreview.setImageBitmap(bitmap)
                        }
                    }
                } else {
                    simClient.setVideoPreviewEnabled(false, null)
                    runOnUiThread {
                        ivPreview.setImageBitmap(null)
                    }
                }
            }
        }
        tvDisplay = findViewById(R.id.tvDisplay)
        tvDisplayTitle = findViewById(R.id.tvDisplayTitle)
        etRayNeoIp = findViewById(R.id.etRayNeoIp)
        llCommands = findViewById(R.id.llCommands)
        tvSettingsTitle = findViewById(R.id.tvSettingsTitle)
        llSettings = findViewById(R.id.llSettings)
        btnApplySettings = findViewById(R.id.btnApplySettings)
        btnCopyDebugReport = findViewById(R.id.btnCopyDebugReport)

        // Rokid runtime credential UI
        llRokidConfig = findViewById(R.id.llRokidConfig)
        btnPickSnLicense = findViewById(R.id.btnPickSnLicense)
        tvSnLicenseFile = findViewById(R.id.tvSnLicenseFile)
        etRokidSecret = findViewById(R.id.etRokidSecret)

        // Restore previously-saved Rokid credentials into the UI.
        restoreRokidCredentialUI()

        btnPickSnLicense.setOnClickListener {
            pickSnLicenseLauncher.launch(arrayOf("*/*"))
        }

        val deviceItems = if (BuildConfig.XG_SIMULATOR) {
            listOf("SIMULATOR")
        } else {
            listOf("ROKID", "META", "FRAME", "RAYNEO", "OMI", "SIMULATOR")
        }
        spDevice.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            deviceItems,
        )

        spDevice.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                onDeviceSelectionChanged(spDevice.selectedItem?.toString() ?: "ROKID")
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        btnConnect.setOnClickListener {
            val selected = spDevice.selectedItem?.toString() ?: "ROKID"
            val model = when (selected) {
                "SIMULATOR" -> GlassesModel.SIMULATOR
                "META" -> GlassesModel.META
                "FRAME" -> GlassesModel.FRAME
                "RAYNEO" -> GlassesModel.RAYNEO
                "OMI" -> GlassesModel.OMI
                else -> GlassesModel.ROKID
            }
            // Save Rokid credentials entered in the UI before connecting.
            if (model == GlassesModel.ROKID) {
                saveRokidCredentials()
            }
            ensurePermissionsThenConnect(model)
        }

        renderSettings()

        btnApplySettings.setOnClickListener { applySettings() }
        btnCopyDebugReport.setOnClickListener { copyDebugReport() }

        // Settings title click toggles collapsible content
        tvSettingsTitle.setOnClickListener { toggleSettingsCollapse() }

        renderCommandsForCurrentSelection(connected = false)

        // Set initial display visibility based on default device selection
        onDeviceSelectionChanged(spDevice.selectedItem?.toString() ?: "ROKID")

    }

    private fun maybeAutoConnectSimulator() {
        if (!BuildConfig.XG_SIMULATOR || simulatorAutoConnectRequested) return
        simulatorAutoConnectRequested = true
        appendLog("Auto-connect requested for SIMULATOR.")
        ensurePermissionsThenConnect(GlassesModel.SIMULATOR)
    }

    private fun connect(model: GlassesModel) {
        connectJob?.cancel()
        connectJob = scope.launch {
            btnConnect.isEnabled = false
            tvStatus.text = "Status: switching to ${model.name}..."
            simulatorAutoValidationStarted = false

            try {
                // Disconnect previous client FIRST (sequentially) so we don't accidentally destroy the new one.
                val old = client
                client = null
                try {
                    old?.disconnect()
                } catch (e: Exception) {
                    appendLog("WARN: disconnect previous client failed: ${e.message}")
                }

                val newClient = when (model) {
                    GlassesModel.SIMULATOR -> SimulatorGlassesClient(
                        activity = this@MainActivity,
                        displaySink = { text -> tvDisplay.text = text },
                        videoPath = BuildConfig.XG_SIM_VIDEO_PATH.takeIf { it.isNotEmpty() },
                    )
                    GlassesModel.ROKID -> createRokidClient()
                    GlassesModel.META -> createMetaClient()
                    GlassesModel.FRAME -> {
                    // SDK-owned Flutter engine + bridge
                    EmbeddedFrameGlassesClient(this@MainActivity)
                    }
                    GlassesModel.RAYNEO -> {
                        val host = etRayNeoIp.text?.toString()?.trim().orEmpty()
                        if (host.isBlank()) {
                            appendLog("RayNeo: please input glasses IP address first.")
                            tvStatus.text = "Status: RayNeo IP missing"
                            return@launch
                        }
                        RayNeoInstallerGlassesClient(
                            context = this@MainActivity,
                            config = RayNeoInstallerConfig(
                                host = host,
                                apk = RayNeoApkSource.Asset("rayneo_glass_app.apk"),
                            )
                        )
                    }
                    GlassesModel.OMI -> OmiGlassesClient(this@MainActivity)
                }

                client = newClient

                // Apply preview switch state to new client if SIMULATOR.
                // In camera-backed simulator mode, default preview on so users can verify
                // whether Android Emulator is receiving the host webcam instead of a fake scene.
                val simClient = newClient as? SimulatorGlassesClient
                if (simClient != null && BuildConfig.XG_SIM_VIDEO_PATH.isEmpty() && !swPreview.isChecked) {
                    swPreview.isChecked = true
                    appendLog("Simulator preview auto-enabled for camera source.")
                } else if (simClient != null && BuildConfig.XG_SIM_VIDEO_PATH.isNotEmpty()) {
                    appendLog("Simulator preview uses video source: ${BuildConfig.XG_SIM_VIDEO_PATH}")
                }
                if (simClient != null && swPreview.isChecked) {
                    simClient.setVideoPreviewEnabled(true) { bitmap ->
                        runOnUiThread {
                            ivPreview.setImageBitmap(bitmap)
                        }
                    }
                }

                // Restart collectors for the new client.
                stateJob?.cancel()
                eventsJob?.cancel()

                stateJob = launch {
                    newClient.state.collectLatest { st ->
                        val statusText = "Status: $st"
                        if (tvStatus.text.toString() != statusText) {
                            tvStatus.text = statusText
                            appendLog(statusText)
                        }
                        val connected = st is ConnectionState.Connected
                        renderCommandsForCurrentSelection(connected = connected)
                        maybeAutoRunSimulatorValidation(model, connected)
                    }
                }

                eventsJob = launch {
                    newClient.events.collectLatest { ev ->
                        when (ev) {
                            is GlassesEvent.Log -> appendLog(ev.message)
                            is GlassesEvent.Warning -> appendLog("WARN: ${ev.message}")
                            is GlassesEvent.Tap -> appendLog("TAP: ${ev.count}")
                        }
                    }
                }

                val r = newClient.connect()
                appendLog("connect(${model.name}) => ${r.isSuccess} ${r.exceptionOrNull()?.message ?: ""}")

                // After successful RayNeo install, push the current user settings to the glasses.
                if (r.isSuccess && newClient is RayNeoInstallerGlassesClient && appliedSettings.isNotEmpty()) {
                    val pushR = newClient.pushUserSettings(appliedSettings)
                    if (pushR.isSuccess) appendLog("Settings synced to RayNeo glasses.")
                    else appendLog("Settings sync failed: ${pushR.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                client = null
                appendLog("connect(${model.name}) crashed: ${e.message ?: e.javaClass.simpleName}")
                tvStatus.text = "Status: connect failed"
                renderCommandsForCurrentSelection(connected = false)
            } finally {
                btnConnect.isEnabled = true
            }
        }
    }

    private fun createRokidClient(): RokidGlassesClient {
        // 1. Try runtime credentials (user-provided via in-app UI).
        val auth = loadRokidAuthFromRuntime()
            // 2. Fall back to build-time credentials (local.properties / env / res/raw).
            ?: loadRokidAuthFromBuildConfig()

        if (auth == null) {
            appendLog(
                "Rokid: SN auth missing.\n" +
                    "  Option A (recommended): select your .lc file and enter client secret in the UI above.\n" +
                    "  Option B: put .lc under app/src/main/res/raw/ and set in local.properties:\n" +
                    "    rokid.clientSecret=<your-client-secret>\n" +
                    "    rokid.snRawName=<raw_resource_name_without_extension>"
            )
        }

        return RokidGlassesClient(
            this,
            RokidGlassesClient.RokidOptions(authorization = auth),
        )
    }

    private fun createMetaClient(): GlassesClient {
        return try {
            val clazz = Class.forName("com.universalglasses.device.meta.MetaWearablesGlassesClient")
            val ctor = clazz.getConstructor(AppCompatActivity::class.java, ExternalActivityBridge::class.java)
            ctor.newInstance(
                this,
                ExternalActivityBridge { intent -> launchExternalActivity(intent) },
            ) as GlassesClient
        } catch (_: ClassNotFoundException) {
            throw IllegalStateException(
                "Meta DAT module is not available in this build. Set github_token in ~/.gradle/gradle.properties or export GITHUB_TOKEN, then sync again."
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to create MetaWearablesGlassesClient: ${e.message}", e)
        }
    }

    private suspend fun launchExternalActivity(intent: Intent): ExternalActivityResult {
        return externalActivityMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                externalActivityContinuation = continuation
                continuation.invokeOnCancellation {
                    if (externalActivityContinuation === continuation) {
                        externalActivityContinuation = null
                    }
                }
                externalActivityLauncher.launch(intent)
            }
        }
    }

    /**
     * Load Rokid authorization from runtime user input (internal storage + SharedPreferences).
     */
    private fun loadRokidAuthFromRuntime(): RokidGlassesClient.RokidAuthorization? {
        val secret = rokidPrefs.getString(PREF_ROKID_CLIENT_SECRET, null)?.trim().orEmpty()
        if (secret.isBlank()) return null

        val lcFile = File(filesDir, ROKID_LC_FILENAME)
        if (!lcFile.exists()) return null
        val bytes = try { lcFile.readBytes() } catch (_: Exception) { ByteArray(0) }
        if (bytes.isEmpty()) return null

        return RokidGlassesClient.RokidAuthorization(
            snLc = bytes,
            clientSecret = secret,
        )
    }

    /**
     * Load Rokid authorization from build-time config (BuildConfig fields + res/raw resource).
     * This is the legacy path: developer sets rokid.clientSecret / rokid.snRawName in
     * local.properties (or env vars) and places the .lc file in app/src/main/res/raw/.
     */
    private fun loadRokidAuthFromBuildConfig(): RokidGlassesClient.RokidAuthorization? {
        val secret = BuildConfig.ROKID_CLIENT_SECRET.trim()
        val rawName = BuildConfig.ROKID_SN_RAW_NAME.trim()
        if (rawName.isBlank() || secret.isBlank()) return null

        val resId = resources.getIdentifier(rawName, "raw", packageName)
        if (resId == 0) return null

        val bytes = try {
            resources.openRawResource(resId).use { it.readBytes() }
        } catch (_: Exception) {
            ByteArray(0)
        }
        if (bytes.isEmpty()) return null

        return RokidGlassesClient.RokidAuthorization(
            snLc = bytes,
            clientSecret = secret,
        )
    }

    private fun ensurePermissionsThenConnect(model: GlassesModel) {
        val required = requiredPermissionsFor(model)
        val missing = required.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            appendLog("Permissions ready for ${model.name}; connecting.")
            connect(model)
            return
        }
        pendingConnectModel = model
        appendLog("Requesting permissions: ${missing.joinToString()}")
        requestPermissionsLauncher.launch(missing.toTypedArray())
    }

    private fun requiredPermissionsFor(model: GlassesModel): List<String> {
        val perms = mutableListOf<String>()

        if (model == GlassesModel.SIMULATOR) {
            perms += Manifest.permission.CAMERA
        }

        if (model == GlassesModel.META) {
            perms += Manifest.permission.RECORD_AUDIO
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                perms += Manifest.permission.BLUETOOTH_CONNECT
            }
        }

        // BLE permissions (Frame + Rokid + OMI)
        if (model == GlassesModel.ROKID || model == GlassesModel.FRAME || model == GlassesModel.OMI) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                perms += Manifest.permission.BLUETOOTH_SCAN
                perms += Manifest.permission.BLUETOOTH_CONNECT
            } else {
                perms += Manifest.permission.ACCESS_FINE_LOCATION
                perms += Manifest.permission.BLUETOOTH
                perms += Manifest.permission.BLUETOOTH_ADMIN
            }
        }

        // Rokid needs Wi‑Fi P2P on Android 13+
        if (model == GlassesModel.ROKID && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        return perms.distinct()
    }

    private fun appendLog(msg: String) {
        val stamped = "${timestamp()} $msg"
        tvLog.text = tvLog.text.toString() + "\n" + stamped
        Log.i(LOG_TAG, stamped)
    }

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun currentSettingsSnapshot(): Map<String, String> {
        if (settingEdits.isEmpty()) return appliedSettings
        return settingEdits.mapValues { (_, editText) ->
            editText.text?.toString().orEmpty()
        }
    }

    private fun buildDebugReport(): String {
        val settings = currentSettingsSnapshot()
        val baseUrl = AIApiSettings.baseUrl(settings)
        val model = AIApiSettings.model(settings)
        val hasApiKey = AIApiSettings.apiKey(settings).isNotBlank()
        val displayText = tvDisplay.text?.toString().orEmpty().ifBlank { "(empty)" }
        val logsText = tvLog.text?.toString().orEmpty()

        return """
XgGlass Debug Report
time: ${timestamp()}
status: ${tvStatus.text}
selectedDevice: ${spDevice.selectedItem?.toString().orEmpty()}
client: ${client?.javaClass?.simpleName ?: "null"}
previewEnabled: ${swPreview.isChecked}
commandsVisible: ${llCommands.childCount}
settings:
  baseUrl: ${if (baseUrl.isBlank()) "(blank)" else baseUrl}
  model: ${if (model.isBlank()) "(blank)" else model}
  apiKeyConfigured: $hasApiKey

display:
$displayText

logs:
$logsText
        """.trimIndent()
    }

    private fun copyDebugReport() {
        val report = buildDebugReport()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("xg-glass-debug-report", report))
        appendLog("Debug report copied to clipboard.")
        Toast.makeText(this, "Debug report copied", Toast.LENGTH_SHORT).show()
    }

    /** Update UI elements that depend on the currently selected device. */
    private fun onDeviceSelectionChanged(selected: String) {
        etRayNeoIp.visibility =
            if (selected == "RAYNEO") android.view.View.VISIBLE else android.view.View.GONE
        llRokidConfig.visibility =
            if (selected == "ROKID") android.view.View.VISIBLE else android.view.View.GONE

        // Display section is only useful for SIMULATOR mode
        val showDisplay = selected == "SIMULATOR"
        tvDisplayTitle.visibility =
            if (showDisplay) android.view.View.VISIBLE else android.view.View.GONE
        tvDisplay.visibility =
            if (showDisplay) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** Toggle the collapsible settings panel. */
    private fun toggleSettingsCollapse() {
        val isVisible = llSettings.visibility == android.view.View.VISIBLE
        if (isVisible) {
            llSettings.visibility = android.view.View.GONE
            btnApplySettings.visibility = android.view.View.GONE
            tvSettingsTitle.text = "▶ Settings"
        } else {
            llSettings.visibility = android.view.View.VISIBLE
            btnApplySettings.visibility = android.view.View.VISIBLE
            tvSettingsTitle.text = "▼ Settings"
        }
    }

    private fun renderCommandsForCurrentSelection(connected: Boolean) {
        val model = when (spDevice.selectedItem?.toString()) {
            "SIMULATOR" -> GlassesModel.SIMULATOR
            "META" -> GlassesModel.META
            "FRAME" -> GlassesModel.FRAME
            "RAYNEO" -> GlassesModel.RAYNEO
            "OMI" -> GlassesModel.OMI
            else -> GlassesModel.ROKID
        }

        llCommands.removeAllViews()
        val e = entry
        if (e == null) {
            llCommands.addView(TextView(this).apply { text = "No UniversalAppEntry (meta-data com.universalglasses.app_entry_class)" })
            return
        }

        if (!connected || client == null) {
            llCommands.addView(TextView(this).apply { text = "Connect first to enable commands." })
            return
        }

        val env = HostEnvironment(hostKind = HostKind.PHONE, model = model)
        val cmds = e.commandsWithDefaults(env)
        if (cmds.isEmpty()) {
            llCommands.addView(TextView(this).apply { text = "No commands for PHONE/${model.name}" })
            return
        }

        cmds.forEach { cmd ->
            llCommands.addView(Button(this).apply {
                text = cmd.title
                setOnClickListener {
                    scope.launch { runCommand(cmd, env) }
                }
            })
        }
    }

    private fun maybeAutoRunSimulatorValidation(model: GlassesModel, connected: Boolean) {
        if (model != GlassesModel.SIMULATOR || !connected || simulatorAutoValidationStarted) return
        if (BuildConfig.XG_SIM_VIDEO_PATH.isEmpty()) return

        val e = entry ?: return
        val currentClient = client ?: return
        val env = HostEnvironment(hostKind = HostKind.PHONE, model = model)
        val command = e.commandsWithDefaults(env).firstOrNull { it.id == "scan_barcode" } ?: return

        simulatorAutoValidationStarted = true
        appendLog("Auto-validation requested for simulator video source.")
        llCommands.post {
            if (client !== currentClient) return@post
            scope.launch { runCommand(command, env) }
        }
    }

    private suspend fun runCommand(cmd: com.universalglasses.appcontract.UniversalCommand, env: HostEnvironment) {
        try {
            val currentClient = client ?: run {
                appendLog("Command skipped: no active client.")
                return
            }
            val ctx = UniversalAppContext(
                environment = env,
                client = currentClient,
                scope = scope,
                log = { appendLog(it) },
                onCapturedImage = { img ->
                    val bytes = img.jpegBytes
                    if (bytes.isNotEmpty()) {
                        scope.launch {
                            val bmp = withContext(Dispatchers.Default) {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            if (bmp != null) ivPreview.setImageBitmap(bmp)
                        }
                    }
                },
                settings = appliedSettings,
            )
            val r = cmd.run(ctx)
            if (r.isFailure) appendLog("Command failed: ${r.exceptionOrNull()?.message ?: "unknown"}")
        } catch (e: Exception) {
            appendLog("Command error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ===================================================================
    // User settings UI
    // ===================================================================

    private val settingsPrefs by lazy {
        getSharedPreferences("ug_user_settings", Context.MODE_PRIVATE)
    }

    /**
     * Render input fields for the entry's [UniversalAppEntry.userSettings].
     * Values are pre-filled from SharedPreferences (falling back to defaults).
     */
    private fun renderSettings() {
        val e = entry ?: return
        val fields = e.userSettings()
        if (fields.isEmpty()) return

        tvSettingsTitle.visibility = android.view.View.VISIBLE
        // Start collapsed — user clicks title to expand
        llSettings.visibility = android.view.View.GONE
        btnApplySettings.visibility = android.view.View.GONE
        tvSettingsTitle.text = "▶ AI Settings"
        llSettings.removeAllViews()
        settingEdits.clear()

        for (field in fields) {
            val label = TextView(this).apply {
                text = field.label
                setPadding(0, 12, 0, 2)
            }
            llSettings.addView(label)

            val editText = EditText(this).apply {
                hint = field.hint
                inputType = when (field.inputType) {
                    UserSettingInputType.PASSWORD ->
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    UserSettingInputType.URL ->
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    UserSettingInputType.NUMBER ->
                        InputType.TYPE_CLASS_NUMBER
                    else ->
                        InputType.TYPE_CLASS_TEXT
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                // Restore from prefs, or use default
                val stored = settingsPrefs.getString(field.key, null)
                setText(stored ?: field.defaultValue)
            }
            llSettings.addView(editText)
            settingEdits[field.key] = editText
        }

        // Build the initial applied settings from stored/default values.
        appliedSettings = buildSettingsMap(fields)
    }

    /** Save current input values to SharedPreferences and update [appliedSettings]. */
    private fun applySettings() {
        val e = entry ?: return
        val fields = e.userSettings()
        val editor = settingsPrefs.edit()
        for (field in fields) {
            val value = settingEdits[field.key]?.text?.toString().orEmpty()
            editor.putString(field.key, value)
        }
        editor.apply()
        appliedSettings = buildSettingsMap(fields)
        appendLog("Settings applied.")

        // For RayNeo: also push the settings file to the glasses via ADB so the
        // on-glasses host can read them.
        pushSettingsToRayNeoIfNeeded()
    }

    /**
     * If the current (or last-configured) glasses model is RAYNEO and we have an IP,
     * push the settings JSON to the glasses via ADB.
     */
    private fun pushSettingsToRayNeoIfNeeded() {
        if (appliedSettings.isEmpty()) return

        // Use existing client if it's already a RayNeo installer …
        val rayNeoClient = client as? RayNeoInstallerGlassesClient

        // … otherwise create a transient one if the user has selected RAYNEO and entered an IP.
        val selected = spDevice.selectedItem?.toString()
        val ip = etRayNeoIp.text?.toString()?.trim().orEmpty()

        if (rayNeoClient == null && (selected != "RAYNEO" || ip.isBlank())) return

        scope.launch {
            try {
                val pusher = rayNeoClient ?: RayNeoInstallerGlassesClient(
                    context = this@MainActivity,
                    config = RayNeoInstallerConfig(
                        host = ip,
                        apk = RayNeoApkSource.Asset("rayneo_glass_app.apk"),
                    ),
                )
                val r = pusher.pushUserSettings(appliedSettings)
                if (r.isSuccess) {
                    appendLog("Settings pushed to RayNeo glasses.")
                } else {
                    appendLog("Settings push failed: ${r.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                appendLog("Settings push error: ${e.message}")
            }
        }
    }

    /** Build a key→value map from current SharedPreferences (or defaults). */
    private fun buildSettingsMap(fields: List<UserSettingField>): Map<String, String> {
        return fields.associate { field ->
            val stored = settingsPrefs.getString(field.key, null)
            field.key to (stored ?: field.defaultValue)
        }
    }

    // ===================================================================
    // Rokid runtime credentials
    // ===================================================================

    private val rokidPrefs by lazy {
        getSharedPreferences("ug_rokid_credentials", Context.MODE_PRIVATE)
    }

    /** Save the client secret from the UI into SharedPreferences. */
    private fun saveRokidCredentials() {
        val secret = etRokidSecret.text?.toString().orEmpty().trim()
        rokidPrefs.edit().putString(PREF_ROKID_CLIENT_SECRET, secret).apply()
    }

    /** Restore previously-saved credentials into the Rokid config UI. */
    private fun restoreRokidCredentialUI() {
        val secret = rokidPrefs.getString(PREF_ROKID_CLIENT_SECRET, null).orEmpty()
        if (secret.isNotBlank()) etRokidSecret.setText(secret)
        val displayName = rokidPrefs.getString(PREF_ROKID_LC_DISPLAY_NAME, null)
        if (!displayName.isNullOrBlank()) tvSnLicenseFile.text = displayName
    }

    /** Try to extract a display file name from a content URI. */
    private fun queryFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null) ?: return null
        return cursor.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) it.getString(idx) else null
            } else null
        }
    }

    private companion object {
        const val LOG_TAG = "XgGlassApp"
        /** Internal storage file name for the persisted Rokid SN license bytes. */
        const val ROKID_LC_FILENAME = "rokid_sn_license.lc"
        const val PREF_ROKID_CLIENT_SECRET = "rokid_client_secret"
        const val PREF_ROKID_LC_DISPLAY_NAME = "rokid_lc_display_name"
    }

    // ===================================================================

    private fun loadEntryOrNull(): UniversalAppEntry? {
        val cls = try {
            val appInfo = packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
            appInfo.metaData?.getString("com.universalglasses.app_entry_class")?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (cls.isBlank()) return null
        return try {
            val k = Class.forName(cls)
            k.getDeclaredConstructor().newInstance() as UniversalAppEntry
        } catch (_: Throwable) {
            null
        }
    }
}
