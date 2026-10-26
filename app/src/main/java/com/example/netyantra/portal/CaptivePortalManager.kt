package com.example.netyantra.portal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Coordinates Wi-Fi captive portal detection and automated login.
 *
 * Core behaviors:
 * 1. Probes captive portals using [NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL] and a generate_204 check
 *    bound to the Wi-Fi [Network] (redirect, non-204, or blocked probe indicates portal).
 * 2. If connected to a saved SSID and a portal is detected (or internet probe is blocked), transmits the login POST
 *    using an [OkHttpClient] strictly bound to the Wi-Fi [Network].
 * 3. Verifies login success by re-running the generate_204 check and network validation.
 * 4. Strictly protects credentials: never logs or transmits credentials to any third party.
 * 5. Strictly preserves standard SSL verification (never disables SSL verification globally).
 */
class CaptivePortalManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val repository = PortalProfileRepository.getInstance(appContext)
    private val detector: CaptivePortalDetector = DefaultCaptivePortalDetector()

    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(Dispatchers.Default + managerJob)

    private val _portalState = MutableStateFlow<PortalState>(PortalState.None)
    val portalState: StateFlow<PortalState> = _portalState.asStateFlow()

    private val _currentWifiSsid = MutableStateFlow<String?>(null)
    val currentWifiSsid: StateFlow<String?> = _currentWifiSsid.asStateFlow()

    private val _currentWifiNetwork = MutableStateFlow<Network?>(null)
    val currentWifiNetwork: StateFlow<Network?> = _currentWifiNetwork.asStateFlow()

    private val _isAutoLoggingIn = MutableStateFlow(false)
    val isAutoLoggingIn: StateFlow<Boolean> = _isAutoLoggingIn.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var activeCheckJob: Job? = null

    /**
     * Resolves the SSID of the active Wi-Fi connection.
     */
    fun resolveWifiSsid(network: Network? = _currentWifiNetwork.value): String? {
        val targetNetwork = network ?: findActiveWifiNetwork()

        // Method 1: TransportInfo via NetworkCapabilities (Android 10+)
        if (targetNetwork != null) {
            val caps = connectivityManager.getNetworkCapabilities(targetNetwork)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val transportInfo = caps?.transportInfo
                if (transportInfo is WifiInfo) {
                    val ssid = cleanSsid(transportInfo.ssid)
                    if (ssid != null) {
                        _currentWifiSsid.value = ssid
                        return ssid
                    }
                }
            }
        }

        // Method 2: WifiManager connectionInfo fallback
        return try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val connectionInfo = wifiManager?.connectionInfo
            val ssid = cleanSsid(connectionInfo?.ssid)
            if (ssid != null) {
                _currentWifiSsid.value = ssid
            }
            ssid
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanSsid(rawSsid: String?): String? {
        if (rawSsid == null) return null
        val trimmed = rawSsid.trim()
        val stripped = if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
        return if (stripped.isBlank() || stripped == "<unknown ssid>") null else stripped
    }

    /**
     * Resolves the default gateway IP (e.g. 192.168.1.1 or 10.0.0.1) from DHCP.
     */
    fun resolveGatewayIp(): String? {
        return try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val dhcpInfo = wifiManager?.dhcpInfo ?: return null
            val gateway = dhcpInfo.gateway
            if (gateway == 0) return null
            "${gateway and 0xFF}.${(gateway shr 8) and 0xFF}.${(gateway shr 16) and 0xFF}.${(gateway shr 24) and 0xFF}"
        } catch (e: Exception) {
            null
        }
    }

    fun resolveGatewayLoginUrl(): String {
        val ip = resolveGatewayIp()
        return if (ip != null) "http://$ip/" else ""
    }

    @Suppress("DEPRECATION")
    private fun findActiveWifiNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        if (active != null) {
            val caps = connectivityManager.getNetworkCapabilities(active)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                return active
            }
        }
        // Iterate all networks if activeNetwork is not Wi-Fi (e.g. dual cellular active)
        for (net in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(net)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                return net
            }
        }
        return null
    }

    /**
     * Builds an [OkHttpClient] strictly bound to the specified [network] instance.
     * Note: Standard SSL verification is maintained; it is NEVER globally disabled.
     */
    fun buildClientForNetwork(network: Network, timeoutSeconds: Long = 15): OkHttpClient {
        return OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return try {
                        network.getAllByName(hostname).toList()
                    } catch (e: Exception) {
                        try {
                            Dns.SYSTEM.lookup(hostname)
                        } catch (e2: Exception) {
                            emptyList()
                        }
                    }
                }
            })
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds + 2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Checks for captive portal presence on the specified [network] and initiates auto-login
     * if a matching profile exists for the connected SSID.
     */
    suspend fun checkAndAutoLogin(network: Network? = null): PortalState = withContext(Dispatchers.Default) {
        val targetNetwork = network ?: findActiveWifiNetwork()
        if (targetNetwork == null) {
            _portalState.value = PortalState.None
            _statusMessage.value = "Wi-Fi not connected"
            return@withContext PortalState.None
        }

        _currentWifiNetwork.value = targetNetwork
        val ssid = resolveWifiSsid(targetNetwork) ?: ""
        _portalState.value = PortalState.Checking
        _statusMessage.value = "Probing captive portal on ${if (ssid.isNotBlank()) ssid else "Wi-Fi"}..."

        val caps = connectivityManager.getNetworkCapabilities(targetNetwork)
        val hasCaptiveCapability = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
        val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        // Probe via generate_204 bound to Wi-Fi Network
        val probeClient = buildClientForNetwork(targetNetwork, timeoutSeconds = 6)
        val probeResult = detector.checkForCaptivePortal(probeClient)

        // If probeResult returned 204, and Android has validated internet, network is fully authenticated
        if (probeResult is PortalState.None && (isValidated || !hasCaptiveCapability)) {
            _portalState.value = PortalState.None
            _statusMessage.value = "Internet connection active"
            return@withContext PortalState.None
        }

        // Determine if captive portal or blocked connection is present:
        // 1. Android reported NET_CAPABILITY_CAPTIVE_PORTAL
        // 2. Probe returned HTTP redirect (3xx) or non-204
        // 3. Probe failed to connect to internet verification endpoints (connection dropped/blocked by portal gateway)
        val isProbeBlocked = probeResult is PortalState.Error
        val isPortalPresent = hasCaptiveCapability || probeResult is PortalState.Detected || isProbeBlocked

        val savedProfile = repository.getProfileForSsid(ssid)

        if (isPortalPresent) {
            val detectedUrl = when (probeResult) {
                is PortalState.Detected -> probeResult.loginUrl
                else -> resolveGatewayLoginUrl()
            }
            val detectedState = PortalState.Detected(loginUrl = detectedUrl, ssid = ssid)

            if (savedProfile != null) {
                _statusMessage.value = "Captive portal detected on $ssid. Auto-logging in..."
                return@withContext performLogin(
                    network = targetNetwork,
                    profile = savedProfile,
                    fallbackUrl = detectedUrl
                )
            } else {
                _portalState.value = detectedState
                _statusMessage.value = if (isProbeBlocked) {
                    "Internet blocked on $ssid (captive portal present, no saved login)"
                } else {
                    "Captive portal detected on $ssid"
                }
                return@withContext detectedState
            }
        }

        val finalState = if (probeResult is PortalState.Error) probeResult else PortalState.None
        _portalState.value = finalState
        return@withContext finalState
    }

    /**
     * Directly triggers auto-login using the saved profile for the active Wi-Fi network.
     * Fulfills the "Auto-Login Now" action regardless of current probe state.
     */
    suspend fun performAutoLoginForCurrentWifi(): PortalState = withContext(Dispatchers.Default) {
        val targetNetwork = findActiveWifiNetwork() ?: run {
            _statusMessage.value = "Wi-Fi not connected"
            return@withContext PortalState.None
        }

        val ssid = resolveWifiSsid(targetNetwork) ?: ""
        val profile = repository.getProfileForSsid(ssid) ?: run {
            val msg = if (ssid.isNotBlank()) "No saved login profile for $ssid" else "No saved login profile found"
            _statusMessage.value = msg
            return@withContext PortalState.Error(msg)
        }

        _statusMessage.value = "Submitting auto-login for ${profile.ssid}..."
        return@withContext performLogin(targetNetwork, profile, resolveGatewayLoginUrl())
    }

    /**
     * Sends the login POST request with OkHttp bound to the Wi-Fi [Network] object.
     * Never logs or transmits credentials anywhere.
     * Verifies success by re-running the generate_204 check.
     */
    suspend fun performLogin(
        network: Network,
        profile: PortalProfile,
        fallbackUrl: String = ""
    ): PortalState = withContext(Dispatchers.IO) {
        _isAutoLoggingIn.value = true
        _portalState.value = PortalState.LoggingIn(profile.ssid)

        val rawTarget = profile.loginUrl.ifBlank { fallbackUrl }.ifBlank { resolveGatewayLoginUrl() }.trim()
        val targetUrl = when {
            rawTarget.isBlank() -> ""
            rawTarget.startsWith("http://", ignoreCase = true) || rawTarget.startsWith("https://", ignoreCase = true) -> rawTarget
            else -> "http://$rawTarget"
        }

        if (targetUrl.isBlank()) {
            val errorMsg = "No login URL specified for ${profile.ssid}"
            val err = PortalState.Error(errorMsg)
            _portalState.value = err
            _statusMessage.value = errorMsg
            _isAutoLoggingIn.value = false
            return@withContext err
        }

        // Construct form body with credentials and optional hidden fields.
        // SECURITY: Passwords and credentials are NEVER logged to logcat or printed.
        val formBuilder = FormBody.Builder()
        formBuilder.add(profile.usernameField, profile.username)
        formBuilder.add(profile.passwordField, profile.password)

        profile.extraFields.forEach { (key, value) ->
            if (key.isNotBlank()) {
                formBuilder.add(key, value)
            }
        }
        val formBody = formBuilder.build()

        val postClient = buildClientForNetwork(network, timeoutSeconds = 15)
            .newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val postRequest = Request.Builder()
            .url(targetUrl)
            .post(formBody)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Cache-Control", "no-cache")
            .build()

        var postSucceeded = false
        try {
            postClient.newCall(postRequest).execute().use { response ->
                // Response code received (portal usually responds 200, 302, etc.)
                postSucceeded = response.isSuccessful || response.code in 200..399
            }
        } catch (e: Exception) {
            // Some portal gateways disconnect the client immediately to force DHCP/firewall refresh
            postSucceeded = true
        }

        // Success verification: Re-run generate_204 check bound to Wi-Fi Network
        // Give gateway firewall a moment to update state table
        delay(800)
        var verified = verifyGenerate204(network)
        if (!verified) {
            // Secondary probe after short delay
            delay(1500)
            verified = verifyGenerate204(network)
        }

        val finalState = if (verified) {
            _statusMessage.value = "Auto-login successful on ${profile.ssid}"
            PortalState.Authenticated
        } else {
            val msg = if (postSucceeded) {
                "Login POST sent to $targetUrl, but internet verification failed"
            } else {
                "Failed to submit login POST to $targetUrl"
            }
            _statusMessage.value = msg
            PortalState.Error(msg)
        }

        _portalState.value = finalState
        _isAutoLoggingIn.value = false
        return@withContext finalState
    }

    /**
     * Executes generate_204 probe bound to Wi-Fi [Network].
     * Returns true if HTTP 204 or validated network status is detected.
     */
    suspend fun verifyGenerate204(network: Network): Boolean = withContext(Dispatchers.IO) {
        val caps = connectivityManager.getNetworkCapabilities(network)
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == false
        ) {
            return@withContext true
        }

        val client = buildClientForNetwork(network, timeoutSeconds = 6)
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val checkUrls = listOf(
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://clients3.google.com/generate_204",
            "http://captive.apple.com/hotspot-detect.html"
        )

        for (url in checkUrls) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; NetYantra)")
                .header("Cache-Control", "no-cache")
                .build()

            try {
                val ok = client.newCall(request).execute().use { response ->
                    if (response.code == 204) {
                        true
                    } else if (url.contains("hotspot-detect") && response.code == 200) {
                        val body = response.body?.string() ?: ""
                        body.contains("<TITLE>Success</TITLE>", ignoreCase = true) || body.trim() == "Success"
                    } else {
                        false
                    }
                }
                if (ok) return@withContext true
            } catch (e: Exception) {
                // Try next endpoint
            }
        }

        false
    }

    /**
     * Called when Wi-Fi network availability or capabilities change.
     */
    fun onWifiConnected(network: Network, capabilities: NetworkCapabilities? = null) {
        _currentWifiNetwork.value = network
        resolveWifiSsid(network)

        activeCheckJob?.cancel()
        activeCheckJob = managerScope.launch {
            delay(500) // Brief stabilization
            checkAndAutoLogin(network)
        }
    }

    fun onWifiLost(network: Network) {
        if (_currentWifiNetwork.value == network) {
            _currentWifiNetwork.value = null
            _currentWifiSsid.value = null
            _portalState.value = PortalState.None
            _statusMessage.value = "Wi-Fi disconnected"
            activeCheckJob?.cancel()
        }
    }

    /**
     * Deletes saved login and credentials for the currently connected Wi-Fi SSID.
     * Fulfills the "delete saved login" button requirement.
     */
    fun deleteSavedLoginForCurrentWifi(): Boolean {
        val ssid = _currentWifiSsid.value ?: resolveWifiSsid()
        val removed = repository.deleteSavedLoginForSsid(ssid ?: "")
        if (removed) {
            _statusMessage.value = "Saved login deleted for ${ssid ?: "Wi-Fi"}"
            _portalState.value = PortalState.None
        }
        return removed
    }

    companion object {
        @Volatile
        private var instance: CaptivePortalManager? = null

        fun getInstance(context: Context): CaptivePortalManager {
            return instance ?: synchronized(this) {
                instance ?: CaptivePortalManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
