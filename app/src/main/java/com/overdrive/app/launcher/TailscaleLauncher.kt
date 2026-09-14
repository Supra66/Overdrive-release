package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.logging.LogManager
import com.overdrive.app.mqtt.ProxyHelper

/**
 * Launches Tailscale tunnel processes via ADB shell for remote access.
 * 
 * Uses AdbShellExecutor for shell operations.
 */
class TailscaleLauncher(
    private val context: Context,
    private val adbShellExecutor: AdbShellExecutor,
    private val logManager: LogManager
) {
    companion object {
        private const val TAG = "TailscaleLauncher"

        // Tailscale paths
        private const val TAILSCALE_HOME = "/data/local/tmp/.tailscale"
        private const val TAILSCALE_LOG = "$TAILSCALE_HOME/tailscale.log"
        private const val TAILSCALE_PATH = "$TAILSCALE_HOME/tailscale"
        private const val TAILSCALED_PATH = "$TAILSCALE_HOME/tailscaled"

        private const val TAILSCALE_COMMUNICATION_PORT = "8532"

        private const val TAILSCALE_PROXY_FILE = "$TAILSCALE_HOME/proxy_enabled"
        private const val TAILSCALE_PROXY_PORT = "8539"

        // Proxy settings for sing-box (socks5 for tailscale)
        private const val PROXY_HOST = "127.0.0.1"
        private const val PROXY_PORT = 8119

        // Remote-ADB opt-in sentinel + the adbd port we forward to. Same
        // sentinel-file pattern as TAILSCALE_PROXY_FILE so the UID-2000 shell
        // side and the app agree on state across restarts.
        private const val TAILSCALE_ADB_FILE = "$TAILSCALE_HOME/adb_enabled"
        private const val ADB_PORT = "5555"

        // HTTPS opt-in sentinel and the local port the web server listens on.
        // Same sentinel-file pattern as the two flags above.
        private const val TAILSCALE_HTTPS_FILE = "$TAILSCALE_HOME/https_enabled"
        private const val HTTP_PORT = "8080"

        // Retries after the initial replay attempt, ~2s apart — covers a slow
        // tailscaled cold start without spinning if serve is genuinely broken.
        private const val ADB_SERVE_REPLAY_ATTEMPTS = 3
        private const val ADB_SERVE_REPLAY_DELAY_MS = 2000L

        // Past the whole replay ladder (attempts * delay) plus slack, so the
        // confirmation sweep runs after any in-flight replay could have landed.
        private const val ADB_SERVE_WITHDRAW_SWEEP_MS =
            ADB_SERVE_REPLAY_DELAY_MS * (ADB_SERVE_REPLAY_ATTEMPTS + 1) + 2000L

        // Daemon thread: only ever holds short retry tasks, and must not keep the
        // JVM alive. Shared across instances — replays are idempotent.
        // First https:// host in `serve status`, e.g. https://od.tail1234.ts.net.
        // The optional port matters: without it a share on a non-default port
        // would be reported as a bare host and point at 443, which is not what
        // is listening.
        private val SERVED_HTTPS_URL = Regex("https://[A-Za-z0-9._-]+(?::\\d+)?")

        private val replayScheduler: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "TailscaleAdbReplay").apply { isDaemon = true }
            }
    }

    interface TailscaleCallback {
        fun onLog(message: String)
        fun onTunnelUrl(url: String?)
        fun onError(error: String)
    }

    fun launchTailscale(callback: TailscaleCallback) {
        isTunnelRunning { isRunning ->
            if (isRunning) {
                getTunnelUrl { url ->
                    if (url != null) {
                        logManager.info(TAG, "Tailscale already running at $url")
                        callback.onLog("Tailscale already running at $url")
                        callback.onTunnelUrl(url)
                    } else {
                        logManager.error(TAG, "Failed to get tailscale url. Are you logged in?")
                        callback.onError("Failed to get tailscale url. Are you logged in?")
                        callback.onTunnelUrl(null)
                    }
                }
            } else {
                checkAndInstallTailscale(callback) {
                    val useProxy = ProxyHelper.probePort(PROXY_PORT)
                    isProxyEnabled { enableProxy ->
                        launchTailscaleDaemon(useProxy, enableProxy, callback)
                    }
                }
            }
        }
    }

    fun launchTailscaleDaemon(useProxy: Boolean, enableProxy: Boolean, callback: TailscaleCallback) {
        val cmd = buildString {
            append("nohup sh -c '")

            if (useProxy) {
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }
            append(TAILSCALED_PATH)
            // Userspace networking required for android
            append(" --tun userspace-networking")
            // Where to store tailscale data
            append(" --statedir $TAILSCALE_HOME")
            // Communication port to listen to for tailscale commands
            append(" --socket 127.0.0.1:$TAILSCALE_COMMUNICATION_PORT")

            // Optionally start socks5 proxy to access other tailscale devices
            if (enableProxy) {
                append(" --socks5-server 127.0.0.1:$TAILSCALE_PROXY_PORT")
            }

            append("' > $TAILSCALE_LOG 2>&1 &")
        }
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Tailscale daemon started")
                    callback.onLog("Tailscale daemon started")
                    // serve config lives in tailscaled, so a restart drops it —
                    // replay the persisted opt-ins or remote ADB and the HTTPS
                    // share both die silently. The URL is read last, so it
                    // reflects the share this start has just re-published.
                    isAdbEnabled { adbOn ->
                        if (adbOn) replayAdbServe(0)
                        isHttpsEnabled { httpsOn ->
                            if (httpsOn) replayHttpsServe(0)
                            getTunnelUrl { url ->
                                callback.onLog("Connect to tailscale to access $url")
                                callback.onTunnelUrl(url)
                            }
                        }
                    }
                }

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to start tailscale daemon: $error")
                    callback.onError("Failed to start tailscale daemon: $error")
                }
            }
        )
    }

    private fun checkAndInstallTailscale(callback: TailscaleCallback, onComplete: () -> Unit) {
        adbShellExecutor.execute(
            command = "test -x $TAILSCALE_PATH && test -x $TAILSCALED_PATH",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    onComplete()
                }

                override fun onError(error: String) {
                    installTailscale(callback, onComplete)
                }
            }
        )
    }

    private fun installTailscale(callback: TailscaleCallback, onComplete: () -> Unit) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val srcPath = "$nativeLibDir/libtailscale.so"

        callback.onLog("Installing tailscale...")

        adbShellExecutor.execute(
            command = "test -f $srcPath && mkdir -p $TAILSCALE_HOME && cp $srcPath $TAILSCALE_PATH && ln -s $TAILSCALE_PATH $TAILSCALED_PATH && chmod +x $TAILSCALE_PATH",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback.onLog("Tailscale installed")
                    onComplete()
                }

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to install tailscale: $error")
                    callback.onError("Failed to install tailscale: $error")
                }
            }
        )
    }

    fun generateLoginUrl(loginUrl: (String?) -> Unit) {
        launchTailscale(object : TailscaleCallback {
            override fun onLog(message: String) {}
            override fun onTunnelUrl(url: String?) {
                // The login command waits for login completion. Instead, call it with a 1ms timeout so we can get the login url from the status command
                runTailscaleCommand(
                    cmd = "login --hostname overdrive --timeout 1ms || echo done",
                    callback = object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(output: String) {
                            waitForLoginUrl(0, loginUrl)
                        }

                        override fun onError(error: String) {}
                    }
                )
            }
            override fun onError(error: String) {}
        })
    }

    fun waitForLoginUrl(attempt: Int, loginUrl: (String?) -> Unit) {
        if (attempt > 20) {
            loginUrl(null)
            return
        }

        Thread.sleep(2000)

        runTailscaleCommand(
            cmd = "status || echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val loginPattern = Regex("Log in at: (\\S+)", RegexOption.IGNORE_CASE)
                    val match = loginPattern.find(output)

                    if (match != null) {
                        val url = match.groupValues[1]
                        logManager.info(TAG, "Fetched login URL: $url")
                        loginUrl(url)
                    } else {
                        waitForLoginUrl(attempt + 1, loginUrl)
                    }
                }

                override fun onError(error: String) {}
            }
        )
    }

    fun needsLogin(callback: (Boolean) -> Unit) {
        isTunnelRunning { isRunning ->
            if (isRunning) {
                runTailscaleCommand(
                    cmd = "status || echo done",
                    callback = object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(output: String) {
                            if (output.contains("Logged out", ignoreCase = true)) {
                                callback(true)
                            } else if (output.contains("Tailscale is stopped", ignoreCase = true)) {
                                // This should never happen but can if the user runs tailscale down manually
                                callback(true)
                            } else {
                                callback(false)
                            }
                        }

                        override fun onError(error: String) {
                            // MUST complete the chain (issue #209). This used to be a
                            // no-op: when the status command failed (e.g. shell/socket
                            // still stale right after ACC-on), the getTunnelUrl chain
                            // died here, the tunnelUrl LiveData was never posted, and
                            // the UI wedged on "Waiting for tunnel URL" until a full
                            // app restart. Answer "no login needed" so getTunnelUrl
                            // proceeds to `tailscale ip` — which has its own error
                            // path — and every refresh cycle terminates; the periodic
                            // status refresh then recovers once the shell responds.
                            logManager.warn(TAG, "needsLogin: status check failed ($error), assuming logged in")
                            callback(false)
                        }
                    }
                )
            } else {
                callback(false)
            }
        }
    }

    fun runTailscaleCommand(cmd: String, callback: AdbShellExecutor.ShellCallback) {
        adbShellExecutor.execute(
            command = "$TAILSCALE_PATH --socket 127.0.0.1:$TAILSCALE_COMMUNICATION_PORT $cmd",
            callback = callback
        )
    }

    fun saveProxySettings(enabled: Boolean, callback: ((Boolean?) -> Unit)? = null) {
        isProxyEnabled { isEnabled ->
            if (enabled != isEnabled) {
                adbShellExecutor.execute(
                    command = "mkdir -p $TAILSCALE_HOME && echo $enabled > $TAILSCALE_PROXY_FILE && chmod 666 $TAILSCALE_PROXY_FILE",
                    callback = object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(output: String) {
                            logManager.info(TAG, "Proxy settings saved to $TAILSCALE_PROXY_FILE")
                            callback?.invoke(true)
                        }
                        override fun onError(error: String) {
                            logManager.info(TAG, "Proxy settings failed to save to $TAILSCALE_PROXY_FILE")
                            callback?.invoke(false)
                        }
                    }
                )
            } else {
                callback?.invoke(null)
            }
        }
    }

    /**
     * Expose adbd to the tailnet via `tailscale serve --tcp`.
     *
     * tailscaled must run with `--tun userspace-networking` (no root => no TUN
     * device), so the kernel has no tailnet interface and an inbound connection
     * to the tailnet IP cannot reach adbd on its own. `serve --tcp` makes
     * tailscaled itself accept the inbound port and forward it into loopback,
     * which is the only root-free path to remote ADB.
     */
    fun applyAdbServe(enabled: Boolean, callback: ((Boolean) -> Unit)? = null) {
        if (enabled) {
            runTailscaleCommand(
                cmd = "serve --bg --tcp $ADB_PORT tcp://127.0.0.1:$ADB_PORT",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        logManager.info(TAG, "remote ADB exposed on tailnet :$ADB_PORT")
                        callback?.invoke(true)
                    }
                    override fun onError(error: String) {
                        logManager.warn(TAG, "serve --tcp failed: $error")
                        callback?.invoke(false)
                    }
                }
            )
            return
        }
        // `--tcp <port> off` with no positional target: adding the target makes the
        // CLI read it as the retired `serve <spec> off` form and reject it before
        // it ever contacts the daemon. Deliberately never `serve reset` (drops ALL
        // serve config, including the HTTP share this launcher may publish) nor
        // `serve clear` (scoped to Tailscale Services, not node serve config).
        runTailscaleCommand(
            cmd = "serve --tcp $ADB_PORT off",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "remote ADB withdrawn from tailnet")
                    callback?.invoke(true)
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "serve --tcp off failed: $error")
                    callback?.invoke(false)
                }
            }
        )
    }

    /**
     * Publish the web server over HTTPS on the tailnet via `tailscale serve`.
     *
     * tailscaled terminates TLS with a real Let's Encrypt certificate for the
     * node's MagicDNS name and proxies into the local HTTP port, so clients get
     * a genuinely trusted `https://<host>.<tailnet>.ts.net` with no certificate
     * to install and no port forwarding.
     *
     * That matters beyond neatness: a browser treats the plain-HTTP endpoint as
     * an insecure context, which switches off APIs this app's own web UI uses.
     * notifications.html already disables its controls on an insecure origin;
     * pwa-init.js cannot register /sw.js, so there is no install and no push;
     * communicate.js and genai.js lose getUserMedia for cabin audio and voice;
     * and stream.js falls back off the WebCodecs decode path. Served over this
     * URL, all of them work as they do on 127.0.0.1.
     *
     * Requires MagicDNS and HTTPS Certificates to be enabled for the tailnet;
     * without them tailscaled refuses and the error is surfaced to the caller
     * rather than swallowed, since there is nothing the head unit can do about
     * it and the user has to change it in the admin console.
     */
    fun applyHttpsServe(enabled: Boolean, callback: ((Boolean) -> Unit)? = null) {
        if (enabled) {
            runTailscaleCommand(
                cmd = "serve --bg $HTTP_PORT",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        logManager.info(TAG, "web UI published over HTTPS on tailnet")
                        callback?.invoke(true)
                    }
                    override fun onError(error: String) {
                        // Nearly always "HTTPS must be enabled in the admin console".
                        logManager.warn(TAG, "serve --bg $HTTP_PORT failed: $error")
                        callback?.invoke(false)
                    }
                }
            )
            return
        }
        // Withdraw only the HTTPS share, and only the handler we created.
        //
        // Never `serve reset`: that drops the whole serve config including the
        // --tcp forwarder remote ADB depends on.
        //
        // Scoped to --set-path=/ as well as the port, because `serve --bg $PORT`
        // publishes at 443 on path / — so / is precisely what we own. Turning off
        // 443 alone is a broader statement than we are entitled to make: anything
        // else mounted under another path on 443 would go with it, and this runs
        // on a head unit whose serve config we do not exclusively control.
        runTailscaleCommand(
            cmd = "serve --https=443 --set-path=/ off",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "web UI withdrawn from tailnet HTTPS")
                    callback?.invoke(true)
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "serve --https=443 --set-path=/ off failed: $error")
                    callback?.invoke(false)
                }
            }
        )
    }

    /**
     * Replay the HTTPS share after a daemon start, on the same ladder as remote
     * ADB: serve config lives in tailscaled, so a restart drops it, and the
     * launch shell returns as soon as `nohup ... &` forks — the first attempt
     * can beat the daemon binding its socket.
     */
    private fun replayHttpsServe(attempt: Int) {
        // Re-read the opt-in before EVERY attempt, so a user who switches it off
        // inside the retry window is not re-published by a later attempt.
        isHttpsEnabled { stillEnabled ->
            if (!stillEnabled) {
                logManager.info(TAG, "HTTPS replay aborted — opt-in withdrawn")
                return@isHttpsEnabled
            }
            applyHttpsServe(true) { ok ->
                if (ok) return@applyHttpsServe
                if (attempt >= ADB_SERVE_REPLAY_ATTEMPTS) {
                    logManager.warn(TAG, "HTTPS replay gave up after ${attempt + 1} attempts")
                    return@applyHttpsServe
                }
                replayScheduler.schedule(
                    { replayHttpsServe(attempt + 1) },
                    ADB_SERVE_REPLAY_DELAY_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
            }
        }
    }

    /**
     * Confirmation withdrawal for the HTTPS share, run once the replay ladder can
     * no longer fire. Re-reads the opt-in first: the user may have re-enabled it
     * meanwhile, and an unconditional withdrawal would kill that fresh enable.
     */
    private fun sweepWithdrawHttpsIfStillDisabled() {
        isHttpsEnabled { enabledNow ->
            if (!enabledNow) applyHttpsServe(false)
        }
    }

    /**
     * Persist the HTTPS opt-in and apply it when the tunnel is already up.
     *
     * Ordering mirrors saveAdbSettings and fails toward "not published": an
     * enable is applied before it is persisted, a disable is persisted before it
     * is applied.
     */
    fun saveHttpsSettings(enabled: Boolean, callback: ((Boolean) -> Unit)? = null) {
        isTunnelRunning { running ->
            if (!enabled) {
                writeHttpsSentinel(false) { persisted ->
                    if (!running) {
                        callback?.invoke(persisted)
                    } else {
                        applyHttpsServe(false) { applied ->
                            callback?.invoke(persisted && applied)
                            // A replay attempt that read the sentinel just before
                            // it flipped can still land after this withdrawal and
                            // re-publish the share, leaving the switch reading OFF
                            // with the URL live. Sweep once past the retry window
                            // so it cannot outlive the toggle — same guard the ADB
                            // path uses, and the same reason.
                            replayScheduler.schedule(
                                { sweepWithdrawHttpsIfStillDisabled() },
                                ADB_SERVE_WITHDRAW_SWEEP_MS,
                                java.util.concurrent.TimeUnit.MILLISECONDS
                            )
                        }
                    }
                }
                return@isTunnelRunning
            }
            if (!running) {
                // Nothing live to apply to — the sentinel is the whole state and
                // launchTailscaleDaemon replays it on next start.
                writeHttpsSentinel(true, callback)
                return@isTunnelRunning
            }
            applyHttpsServe(true) { applied ->
                if (!applied) {
                    callback?.invoke(false)
                    return@applyHttpsServe
                }
                writeHttpsSentinel(true) { persisted ->
                    if (persisted) {
                        callback?.invoke(true)
                    } else {
                        // Couldn't record the opt-in, so withdraw what was just
                        // published: the UI reads the sentinel and would show OFF
                        // with the share actually live.
                        applyHttpsServe(false) { callback?.invoke(false) }
                    }
                }
            }
        }
    }

    private fun writeHttpsSentinel(enabled: Boolean, callback: ((Boolean) -> Unit)?) {
        adbShellExecutor.execute(
            command = "mkdir -p $TAILSCALE_HOME && echo $enabled > $TAILSCALE_HTTPS_FILE && chmod 600 $TAILSCALE_HTTPS_FILE",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback?.invoke(true)
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to persist HTTPS setting: $error")
                    callback?.invoke(false)
                }
            }
        )
    }

    fun isHttpsEnabled(callback: ((Boolean) -> Unit)) {
        adbShellExecutor.execute(
            command = "cat $TAILSCALE_HTTPS_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim() == "true")
                }
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }

    /**
     * Re-apply the persisted serve config after a daemon start, retrying while
     * tailscaled is still coming up. The launch shell returns as soon as `nohup
     * ... &` forks, so the first attempt can beat the daemon binding its socket;
     * without a retry remote ADB would stay down until the next restart.
     */
    private fun replayAdbServe(attempt: Int) {
        // Re-read the opt-in before EVERY attempt. The retry window spans seconds,
        // and a user who disables ADB inside it would otherwise be silently
        // re-exposed by a later attempt carrying the stale "on" decision.
        isAdbEnabled { stillEnabled ->
            if (!stillEnabled) {
                logManager.info(TAG, "remote ADB replay aborted — opt-in withdrawn")
                return@isAdbEnabled
            }
            applyAdbServe(true) { ok ->
                if (ok) return@applyAdbServe
                if (attempt >= ADB_SERVE_REPLAY_ATTEMPTS) {
                    logManager.warn(TAG, "remote ADB replay gave up after ${attempt + 1} attempts")
                    return@applyAdbServe
                }
                // Back off on a dedicated timer, never by sleeping here: this callback
                // runs on the single shell executor that also serves the settings-dialog
                // probes, and blocking it would let the dialog read stale switch state.
                replayScheduler.schedule(
                    { replayAdbServe(attempt + 1) },
                    ADB_SERVE_REPLAY_DELAY_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
            }
        }
    }

    /**
     * Confirmation withdrawal, run after the replay ladder can no longer fire.
     * Re-reads the opt-in first: the user may have re-enabled ADB in the meantime,
     * and an unconditional withdrawal here would silently kill that fresh enable.
     */
    private fun sweepWithdrawIfStillDisabled() {
        isAdbEnabled { enabledNow ->
            if (!enabledNow) applyAdbServe(false)
        }
    }

    /**
     * Persist the remote-ADB opt-in and apply it when the tunnel is already up.
     * Remote ADB grants UID-2000 shell to anyone on the tailnet, so this stays
     * opt-in and defaults off.
     */
    fun saveAdbSettings(enabled: Boolean, callback: ((Boolean) -> Unit)? = null) {
        // Ordering is deliberately ASYMMETRIC, always failing toward "not exposed":
        // an enable is applied before it is persisted (so a failed apply can't
        // silently expose ADB on a later restart), a disable is persisted before it
        // is applied (so a failed withdrawal can't re-arm on the next restart).
        isTunnelRunning { running ->
            if (!enabled) {
                writeAdbSentinel(false) { persisted ->
                    if (!running) {
                        callback?.invoke(persisted)
                    } else {
                        applyAdbServe(false) { applied ->
                            callback?.invoke(persisted && applied)
                            // A replay attempt that read the sentinel just before it
                            // flipped can still land after this withdrawal; sweep once
                            // more past the retry window so it can't outlive the toggle.
                            replayScheduler.schedule(
                                { sweepWithdrawIfStillDisabled() },
                                ADB_SERVE_WITHDRAW_SWEEP_MS,
                                java.util.concurrent.TimeUnit.MILLISECONDS
                            )
                        }
                    }
                }
                return@isTunnelRunning
            }
            if (!running) {
                // Nothing live to apply to — the sentinel is the whole state and
                // launchTailscaleDaemon replays it on next start.
                writeAdbSentinel(true, callback)
                return@isTunnelRunning
            }
            applyAdbServe(true) { applied ->
                if (!applied) {
                    callback?.invoke(false)
                    return@applyAdbServe
                }
                writeAdbSentinel(true) { persisted ->
                    if (persisted) {
                        callback?.invoke(true)
                    } else {
                        // Couldn't record the opt-in, so withdraw what we just
                        // exposed: the UI reads the sentinel and would otherwise
                        // show OFF with ADB actually reachable and no way to revoke.
                        applyAdbServe(false) { callback?.invoke(false) }
                    }
                }
            }
        }
    }

    private fun writeAdbSentinel(enabled: Boolean, callback: ((Boolean) -> Unit)?) {
        // 600 rather than the 666 used by the sibling proxy flag — least privilege
        // only. It is NOT a security boundary: this file is owned by the shell UID
        // and any shell-UID process keeps write access via the owner bits, so a
        // local foothold can still arm this. The real gate is ADB key auth.
        adbShellExecutor.execute(
            command = "mkdir -p $TAILSCALE_HOME && echo $enabled > $TAILSCALE_ADB_FILE && chmod 600 $TAILSCALE_ADB_FILE",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback?.invoke(true)
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to persist ADB setting: $error")
                    callback?.invoke(false)
                }
            }
        )
    }

    fun isAdbEnabled(callback: ((Boolean) -> Unit)) {
        adbShellExecutor.execute(
            command = "cat $TAILSCALE_ADB_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim() == "true")
                }
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }

    /**
     * Whether tailscaled is really forwarding the ADB port right now. Falls back
     * to the stored opt-in if the status probe itself fails, so a transient shell
     * error doesn't hide a working endpoint.
     */
    private fun isAdbServeActive(callback: (Boolean) -> Unit) {
        isAdbEnabled { adbOn ->
            if (!adbOn) {
                callback(false)
                return@isAdbEnabled
            }
            runTailscaleCommand(
                cmd = "serve status",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        // ":5555" rather than a bare "5555" so a stray digit run
                        // elsewhere in the status output can't read as a live
                        // forwarder. With no serve config the output names no port.
                        callback(output.contains(":$ADB_PORT"))
                    }
                    // Probe failed, not "not serving" — defer to the stored opt-in
                    // rather than hiding an endpoint that may well be working.
                    override fun onError(error: String) = callback(true)
                }
            )
        }
    }

    /**
     * Tailnet address to point `adb connect` at, or null when unavailable.
     * Gated on the daemon's reported serve config rather than the stored opt-in
     * alone, so a replay that never took doesn't advertise a dead address. The
     * gate fails open if the status probe itself errors — see [isAdbServeActive].
     */
    fun getAdbEndpoint(callback: (String?) -> Unit) {
        isAdbServeActive { serving ->
            if (!serving) {
                callback(null)
                return@isAdbServeActive
            }
            isTunnelRunning { isRunning ->
                needsLogin { needsLogin ->
                    if (isRunning && !needsLogin) {
                        runTailscaleCommand(
                            cmd = "ip --1",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(output: String) {
                                    val ip = output.trim()
                                    callback(if (ip.isEmpty()) null else "$ip:$ADB_PORT")
                                }
                                override fun onError(error: String) = callback(null)
                            }
                        )
                    } else {
                        callback(null)
                    }
                }
            }
        }
    }

    fun isProxyEnabled(callback: ((Boolean) -> Unit)) {
        adbShellExecutor.execute(
            command = "cat $TAILSCALE_PROXY_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val enabledText = output.trim()
                    if (enabledText == "true") {
                        callback(true)
                    } else {
                        callback(false)
                    }
                }
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }

    fun isTunnelRunning(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "ps -A | grep tailscaled | grep -v grep",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim().isNotEmpty())
                }

                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }

    fun stopTunnel(callback: TailscaleCallback) {
        logManager.info(TAG, "Stopping tailscale tunnel...")
        callback.onLog("Stopping tailscale tunnel...")

        adbShellExecutor.execute(
            command = "pkill 'tailscaled' 2>/dev/null; rm -f $TAILSCALE_LOG; echo stopped",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Tailscale tunnel stopped")
                    callback.onLog("Tailscale tunnel stopped")
                    callback.onTunnelUrl(null)
                }

                override fun onError(error: String) {
                    // Even on error, consider it stopped
                    logManager.info(TAG, "Tailscale tunnel stopped (with warning: $error)")
                    callback.onLog("Tailscale tunnel stopped")
                    callback.onTunnelUrl(null)
                }
            }
        )
    }

    /**
     * The `https://<host>.<tailnet>.ts.net` URL currently serving the web port,
     * or null when nothing is published.
     *
     * Read back from `serve status` rather than assembled from the MagicDNS
     * name: that reports what tailscaled is ACTUALLY serving, so a stale
     * sentinel, a share that failed to apply, or a tailnet without HTTPS
     * certificates all resolve to null and fall back to the plain address
     * instead of advertising a URL that answers nothing.
     */
    private fun resolveServedHttpsUrl(callback: (String?) -> Unit) {
        isHttpsEnabled { httpsOn ->
            if (!httpsOn) {
                callback(null)
                return@isHttpsEnabled
            }
            runTailscaleCommand(
                cmd = "serve status",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        // Only accept a share that proxies OUR port; the same
                        // status output also lists the ADB --tcp forwarder.
                        val servesHttpPort = output.contains(":$HTTP_PORT")
                        val url = SERVED_HTTPS_URL.find(output)?.value
                        callback(if (servesHttpPort) url else null)
                    }
                    override fun onError(error: String) = callback(null)
                }
            )
        }
    }

    fun getTunnelUrl(callback: (String?) -> Unit) {
        isTunnelRunning { isRunning ->
            needsLogin { needsLogin ->
                if (isRunning && !needsLogin) {
                    // Prefer the HTTPS share when it is published: same server,
                    // but a secure context, which is what the web UI's camera, QR
                    // pairing and service worker all require. Falls back to the
                    // raw tailnet address whenever the share is not up, so the
                    // URL never points at something that is not listening.
                    resolveServedHttpsUrl { httpsUrl ->
                        if (httpsUrl != null) {
                            callback(httpsUrl)
                            return@resolveServedHttpsUrl
                        }
                        runTailscaleCommand(
                            cmd = "ip --1",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(output: String) {
                                    callback("http://${output.trim()}:$HTTP_PORT")
                                }

                                override fun onError(error: String) {
                                    callback(null)
                                }
                            }
                        )
                    }
                } else {
                    callback(null)
                }
            }
        }
    }

    /**
     * Disable tailscale environment (cleanup).
     * WARNING: This will not remove the device from the tailscale console but will disconnect
     */
    fun disableEnvironment(callback: TailscaleCallback? = null) {
        logManager.warn(TAG, "⚠️ Disabling tailscale environment - will need to login again!")
        callback?.onLog("⚠️ Disabling environment (will need login again)...")

        adbShellExecutor.execute(
            command = "pkill 'tailscaled' 2>/dev/null; rm -rf $TAILSCALE_HOME; echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Tailscale environment disabled")
                    callback?.onLog("Environment disabled")
                    callback?.onTunnelUrl(null)
                }

                override fun onError(error: String) {}
            }
        )
    }
}
