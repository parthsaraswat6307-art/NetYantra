package com.example.netyantra.portal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

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

    private val _isWifiConnected = MutableStateFlow(false)
    val isWifiConnected: StateFlow<Boolean> = _isWifiConnected.asStateFlow()

    private val _isAutoLoggingIn = MutableStateFlow(false)
    val isAutoLoggingIn: StateFlow<Boolean> = _isAutoLoggingIn.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var activeCheckJob: Job? = null

    init {
        // Initial eager check for active Wi-Fi upon startup
        findActiveWifiNetwork()?.let { initialNet ->
            _currentWifiNetwork.value = initialNet
            _isWifiConnected.value = true
            resolveWifiSsid(initialNet)
        }

        // Register a continuous Wi-Fi network listener regardless of whether background measurement service is running.
        // Importantly, does NOT require NET_CAPABILITY_INTERNET so captive portal Wi-Fi is detected immediately.
        try {
            val wifiReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            connectivityManager.registerNetworkCallback(wifiReq, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onWifiConnected(network)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    onWifiConnected(network, networkCapabilities)
                }

                override fun onLost(network: Network) {
                    onWifiLost(network)
                }
            })
        } catch (e: Exception) {
            // Ignore if callback registration fails on legacy system
        }
    }

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
        // 1. Check cached current Wi-Fi network from our callback
        _currentWifiNetwork.value?.let { cached ->
            val caps = connectivityManager.getNetworkCapabilities(cached)
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return cached
            }
        }
        // 2. Check active default network
        val active = connectivityManager.activeNetwork
        if (active != null) {
            val caps = connectivityManager.getNetworkCapabilities(active)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                _currentWifiNetwork.value = active
                _isWifiConnected.value = true
                return active
            }
        }
        // 3. Iterate all available networks
        for (net in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(net)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                _currentWifiNetwork.value = net
                _isWifiConnected.value = true
                return net
            }
        }
        return null
    }

    private fun isPrivateOrLocalIp(host: String): Boolean {
        val clean = host.substringBefore(':').trim()
        if (clean.equals("localhost", ignoreCase = true) || clean.endsWith(".local", ignoreCase = true)) return true
        val parts = clean.split('.')
        if (parts.size == 4) {
            val p0 = parts[0].toIntOrNull() ?: return false
            val p1 = parts[1].toIntOrNull() ?: return false
            if (p0 == 10) return true
            if (p0 == 172 && p1 in 16..31) return true
            if (p0 == 192 && p1 == 168) return true
        }
        return false
    }

    private fun createPrivateIpTrustManager(): Pair<SSLSocketFactory, X509TrustManager>? {
        return try {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), SecureRandom())
            Pair(sslContext.socketFactory, trustManager)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Builds an [OkHttpClient] strictly bound to the specified [network] instance.
     * Note: Standard SSL verification is maintained for public internet endpoints;
     * internal self-signed gateway certs are trusted only for RFC 1918 private IPs (e.g. 172.16.x.x, 10.x.x.x).
     */
    fun buildClientForNetwork(
        network: Network,
        timeoutSeconds: Long = 10,
        trustLocalGatewayCert: Boolean = false
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    // Fast path for raw IPv4 addresses (e.g. 172.16.16.16) to avoid blocking DNS timeouts
                    if (hostname.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
                        return try {
                            listOf(InetAddress.getByName(hostname))
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
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

        if (trustLocalGatewayCert) {
            val sslPair = createPrivateIpTrustManager()
            if (sslPair != null) {
                builder.sslSocketFactory(sslPair.first, sslPair.second)
                builder.hostnameVerifier { hostname, session ->
                    isPrivateOrLocalIp(hostname) || HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
                }
            }
        }

        return builder.build()
    }

    /**
     * Checks for captive portal presence on the specified [network] and initiates auto-login
     * if a matching profile exists for the connected SSID.
     */
    suspend fun checkAndAutoLogin(network: Network? = null): PortalState = withContext(Dispatchers.Default) {
        val targetNetwork = network ?: findActiveWifiNetwork()
        if (targetNetwork == null) {
            _portalState.value = PortalState.None
            _isWifiConnected.value = false
            _statusMessage.value = "Wi-Fi not connected. Please connect to Wi-Fi in Android Settings."
            return@withContext PortalState.None
        }

        _isWifiConnected.value = true
        _currentWifiNetwork.value = targetNetwork
        val ssid = resolveWifiSsid(targetNetwork) ?: ""
        _portalState.value = PortalState.Checking
        _statusMessage.value = "Probing captive portal on ${if (ssid.isNotBlank()) ssid else "Wi-Fi"}..."

        val caps = connectivityManager.getNetworkCapabilities(targetNetwork)
        val hasCaptiveCapability = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
        val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        val previousBound = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) connectivityManager.boundNetworkForProcess else null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.bindProcessToNetwork(targetNetwork)
        }

        // Probe via generate_204 bound to Wi-Fi Network
        val probeResult = try {
            val probeClient = buildClientForNetwork(targetNetwork, timeoutSeconds = 6)
            detector.checkForCaptivePortal(probeClient)
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connectivityManager.bindProcessToNetwork(previousBound)
            }
        }

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

        val detectedUrl = when (probeResult) {
            is PortalState.Detected -> probeResult.loginUrl.ifBlank { resolveGatewayLoginUrl() }
            else -> resolveGatewayLoginUrl()
        }

        val savedProfile = repository.getProfileForSsid(ssid, detectedUrl)

        if (isPortalPresent) {
            val detectedState = PortalState.Detected(loginUrl = detectedUrl, ssid = ssid)
            val networkName = if (ssid.isNotBlank()) ssid else savedProfile?.ssid?.ifBlank { "Wi-Fi" } ?: "Wi-Fi"

            if (savedProfile != null) {
                _statusMessage.value = "Captive portal detected on $networkName. Auto-logging in..."
                return@withContext performLogin(
                    network = targetNetwork,
                    profile = savedProfile,
                    fallbackUrl = detectedUrl
                )
            } else {
                _portalState.value = detectedState
                _statusMessage.value = if (isProbeBlocked) {
                    "Internet blocked on $networkName (captive portal present, no saved login)"
                } else {
                    "Captive portal detected on $networkName (no saved login profile found)"
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
            _isWifiConnected.value = false
            _statusMessage.value = "Wi-Fi not connected. Please connect to Wi-Fi in Android Settings."
            return@withContext PortalState.None
        }

        _isWifiConnected.value = true
        _currentWifiNetwork.value = targetNetwork
        val ssid = resolveWifiSsid(targetNetwork) ?: ""
        val gatewayUrl = resolveGatewayLoginUrl()
        val profile = repository.getProfileForSsid(ssid, gatewayUrl) ?: run {
            val msg = if (ssid.isNotBlank()) "No saved login profile for $ssid" else "No saved login profile found"
            _statusMessage.value = msg
            return@withContext PortalState.Error(msg)
        }

        val targetName = if (ssid.isNotBlank()) ssid else profile.ssid.ifBlank { "Wi-Fi" }
        _statusMessage.value = "Submitting auto-login for $targetName..."
        return@withContext performLogin(targetNetwork, profile, gatewayUrl)
    }

    /**
     * Sends the login POST request with OkHttp bound to the Wi-Fi [Network] object.
     * Automatically injects Sophos / Cyberoam mode=191 parameters if connecting to port 8090.
     * Never logs or transmits credentials anywhere.
     * Verifies success by checking response XML and re-running generate_204 check.
     */
    suspend fun performLogin(
        network: Network,
        profile: PortalProfile,
        fallbackUrl: String = ""
    ): PortalState = withContext(Dispatchers.IO) {
        _isAutoLoggingIn.value = true
        _portalState.value = PortalState.LoggingIn(profile.ssid)

        val previousBound = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) connectivityManager.boundNetworkForProcess else null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.bindProcessToNetwork(network)
        }

        try {
            val rawTarget = profile.loginUrl.ifBlank { fallbackUrl }.ifBlank { resolveGatewayLoginUrl() }.trim()
            var targetUrl = when {
                rawTarget.isBlank() -> ""
                rawTarget.startsWith("http://", ignoreCase = true) || rawTarget.startsWith("https://", ignoreCase = true) -> rawTarget
                else -> "http://$rawTarget"
            }

            if (targetUrl.isBlank()) {
                val errorMsg = "No login URL specified for ${profile.ssid}"
                val err = PortalState.Error(errorMsg)
                _portalState.value = err
                _statusMessage.value = errorMsg
                return@withContext err
            }

            val isSophosOrCyberoam = targetUrl.contains(":8090") || targetUrl.contains("httpclient", ignoreCase = true)
            val formFields = profile.extraFields.toMutableMap()

            if (isSophosOrCyberoam) {
                if (!formFields.containsKey("mode")) {
                    formFields["mode"] = "191"
                }
                if (!formFields.containsKey("a")) {
                    formFields["a"] = System.currentTimeMillis().toString()
                }
                if (!formFields.containsKey("producttype")) {
                    formFields["producttype"] = "0"
                }
            }

            val formBuilder = FormBody.Builder()
            formBuilder.add(profile.usernameField, profile.username)
            formBuilder.add(profile.passwordField, profile.password)

            formFields.forEach { (key, value) ->
                if (key.isNotBlank()) {
                    formBuilder.add(key, value)
                }
            }
            val formBody = formBuilder.build()

            val postClient = buildClientForNetwork(network, timeoutSeconds = 8, trustLocalGatewayCert = true)
                .newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            _statusMessage.value = "Connecting to $targetUrl..."

            var postSucceeded = false
            var portalFeedbackMsg: String? = null
            var postException: Exception? = null

            fun executePost(urlToPost: String): Boolean {
                val postRequest = Request.Builder()
                    .url(urlToPost)
                    .post(formBody)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Cache-Control", "no-cache")
                    .build()

                return postClient.newCall(postRequest).execute().use { response ->
                    val body = try { response.body?.string() ?: "" } catch (e: Exception) { "" }
                    val code = response.code
                    val ok = response.isSuccessful || code in 200..399

                    if (body.contains("<message>", ignoreCase = true)) {
                        val msgMatch = Regex("""<message>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</message>""", RegexOption.IGNORE_CASE)
                            .find(body)?.groupValues?.get(1)?.trim()
                        if (!msgMatch.isNullOrBlank()) {
                            portalFeedbackMsg = msgMatch
                            if (msgMatch.contains("successfully", ignoreCase = true) ||
                                msgMatch.contains("already logged in", ignoreCase = true) ||
                                msgMatch.contains("LIVE", ignoreCase = true)
                            ) {
                                return@use true
                            } else {
                                return@use false
                            }
                        }
                    }
                    if (body.contains("<status>LIVE</status>", ignoreCase = true) ||
                        body.contains("<status>1</status>", ignoreCase = true)) {
                        portalFeedbackMsg = portalFeedbackMsg ?: "Status LIVE"
                        return@use true
                    }
                    if (body.contains("<status>FAIL</status>", ignoreCase = true)) {
                        return@use false
                    }
                    ok
                }
            }

            try {
                postSucceeded = executePost(targetUrl)
            } catch (e: Exception) {
                // If HTTP failed on port 8090, auto-retry with HTTPS
                if (targetUrl.startsWith("http://", ignoreCase = true) && targetUrl.contains(":8090")) {
                    val httpsUrl = "https://" + targetUrl.removePrefix("http://")
                    try {
                        _statusMessage.value = "Retrying over HTTPS to $httpsUrl..."
                        postSucceeded = executePost(httpsUrl)
                        if (postSucceeded) targetUrl = httpsUrl
                    } catch (e2: Exception) {
                        postException = e2
                        postSucceeded = false
                    }
                } else {
                    postException = e
                    postSucceeded = false
                }
            }

            // Direct rejection by portal firewall (e.g. incorrect password, max limit reached)
            if (portalFeedbackMsg != null && (
                portalFeedbackMsg!!.contains("could not log you on", ignoreCase = true) ||
                portalFeedbackMsg!!.contains("password is correct", ignoreCase = true) ||
                portalFeedbackMsg!!.contains("Maximum Login Limit", ignoreCase = true) ||
                portalFeedbackMsg!!.contains("expired", ignoreCase = true)
            )) {
                val failureMsg = "Portal: $portalFeedbackMsg"
                _statusMessage.value = failureMsg
                val errState = PortalState.Error(failureMsg)
                _portalState.value = errState
                return@withContext errState
            }

            if (!postSucceeded && postException != null) {
                val err = if (postException is java.net.ConnectException) {
                    "Unable to connect to portal at $targetUrl (make sure your phone is connected to Wi-Fi)"
                } else {
                    "Portal connection error: ${postException?.localizedMessage ?: "Failed to reach portal"}"
                }
                _statusMessage.value = err
                val errState = PortalState.Error(err)
                _portalState.value = errState
                return@withContext errState
            }

            _statusMessage.value = "Login submitted! Verifying internet access..."
            delay(600)

            val verified = verifyGenerate204(network)
            val isExplicitSuccess = portalFeedbackMsg != null && (
                portalFeedbackMsg!!.contains("successfully", ignoreCase = true) ||
                portalFeedbackMsg!!.contains("already logged in", ignoreCase = true) ||
                portalFeedbackMsg!!.contains("LIVE", ignoreCase = true)
            )

            val displayName = profile.ssid.ifBlank { "Wi-Fi" }
            val finalState = if (verified || isExplicitSuccess) {
                val successMsg = portalFeedbackMsg?.let { "Auto-login successful: $it" } ?: "Auto-login successful on $displayName"
                _statusMessage.value = successMsg
                PortalState.Authenticated
            } else {
                val msg = when {
                    portalFeedbackMsg != null -> "Portal: $portalFeedbackMsg"
                    postSucceeded -> "Login submitted to $targetUrl, but internet verification timed out"
                    else -> "Failed to log in to $targetUrl"
                }
                _statusMessage.value = msg
                PortalState.Error(msg)
            }

            _portalState.value = finalState
            return@withContext finalState
        } catch (e: Exception) {
            val errorMsg = "Login error: ${e.localizedMessage ?: "Unexpected failure"}"
            _statusMessage.value = errorMsg
            val errState = PortalState.Error(errorMsg)
            _portalState.value = errState
            return@withContext errState
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connectivityManager.bindProcessToNetwork(previousBound)
            }
            _isAutoLoggingIn.value = false
        }
    }

    /**
     * Executes generate_204 probe bound to Wi-Fi [Network].
     * Returns true if HTTP 204 or validated network status is detected.
     * Wrapped in a short 3.5s timeout to guarantee UI never freezes.
     */
    suspend fun verifyGenerate204(network: Network): Boolean = withContext(Dispatchers.IO) {
        val caps = connectivityManager.getNetworkCapabilities(network)
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == false
        ) {
            return@withContext true
        }

        withTimeoutOrNull(3500) {
            val client = buildClientForNetwork(network, timeoutSeconds = 2)
                .newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            val checkUrls = listOf(
                "http://connectivitycheck.gstatic.com/generate_204",
                "http://captive.apple.com/hotspot-detect.html"
            )

            for (url in checkUrls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Android; NetYantra)")
                        .header("Cache-Control", "no-cache")
                        .build()

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
                    if (ok) return@withTimeoutOrNull true
                } catch (e: Exception) {
                    // Try next endpoint
                }
            }
            false
        } ?: false
    }

    /**
     * Called when Wi-Fi network availability or capabilities change.
     */
    fun onWifiConnected(network: Network, capabilities: NetworkCapabilities? = null) {
        _currentWifiNetwork.value = network
        _isWifiConnected.value = true
        resolveWifiSsid(network)

        activeCheckJob?.cancel()
        activeCheckJob = managerScope.launch {
            delay(500) // Brief stabilization
            checkAndAutoLogin(network)
        }
    }

    fun onWifiLost(network: Network) {
        if (_currentWifiNetwork.value == network) {
            val replacement = connectivityManager.allNetworks.firstOrNull { net ->
                net != network && connectivityManager.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (replacement != null) {
                _currentWifiNetwork.value = replacement
                _isWifiConnected.value = true
                resolveWifiSsid(replacement)
            } else {
                _currentWifiNetwork.value = null
                _currentWifiSsid.value = null
                _isWifiConnected.value = false
                _portalState.value = PortalState.None
                _statusMessage.value = "Wi-Fi disconnected"
                activeCheckJob?.cancel()
            }
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
