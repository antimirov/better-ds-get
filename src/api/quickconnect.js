import { SynologyError } from './synology-client';

const QUICKCONNECT_API_URL = 'https://global.quickconnect.to/Serv.php';

/**
 * Probes a list of URLs in parallel and returns the first one that responds
 * with HTTP 200 OK to the DSM API info endpoint.
 * 
 * @param {string[]} urls - List of URLs to test (e.g., ['http://192.168.1.100:5000', 'https://192.168.1.100:5001'])
 * @param {number} timeoutMs - Timeout for each request
 * @returns {Promise<string>} The fastest responding URL
 * @throws {Error} If all URLs fail
 */
async function probeUrls(urls, timeoutMs = 5000) {
    if (!urls || urls.length === 0) {
        throw new Error("No URLs to probe.");
    }

    const abortController = new AbortController();

    const probePromises = urls.map(async (url) => {
        try {
            // Remove trailing slash if present
            const cleanUrl = url.replace(/\/+$/, '');
            // We ping the info endpoint to verify it's actually a Synology NAS
            const pingUrl = `${cleanUrl}/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=SYNO.API.Auth`;

            const response = await fetch(pingUrl, {
                method: 'GET',
                signal: abortController.signal,
            });

            if (response.ok) {
                const json = await response.json();
                if (json && json.success) {
                    return cleanUrl;
                }
            }
        } catch (error) {
            // Ignore fetch errors (timeout, connection refused, SSL error)
        }
        throw new Error('Probe failed');
    });

    // Timeout promise
    const timeoutPromise = new Promise((_, reject) =>
        setTimeout(() => reject(new Error('Probe timeout')), timeoutMs)
    );

    try {
        // Promise.any returns the first fulfilled promise
        const fastestUrl = await Promise.any([
            ...probePromises,
            timeoutPromise
        ]);

        // Cancel all other requests once one succeeds
        abortController.abort();

        return fastestUrl;
    } catch (aggregateError) {
        abortController.abort();
        throw new Error("Could not reach the NAS on any of the resolved QuickConnect addresses. Ensure it is powered on and accessible.");
    }
}

/**
 * Resolves a Synology QuickConnect ID to a direct IP/URL.
 * This interacts with Synology's global QuickConnect relay server.
 * 
 * @param {string} quickConnectId - The QuickConnect ID
 * @returns {Promise<string>} The fastest accessible direct URL (e.g., 'http://192.168.1.100:5000')
 */
export async function resolveQuickConnect(quickConnectId) {
    if (!quickConnectId || typeof quickConnectId !== 'string') {
        throw new Error("Invalid QuickConnect ID");
    }

    const payload = {
        version: 1,
        command: "get_server_info",
        serverID: quickConnectId,
        id: "dsm_portal_https",
        get_ca_fingerprints: true
    };

    let qcResponse;
    try {
        qcResponse = await fetch(QUICKCONNECT_API_URL, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(payload)
        });
    } catch (error) {
        throw new Error(`Failed to contact QuickConnect relay server: ${error.message}`);
    }

    if (!qcResponse.ok) {
        throw new Error(`QuickConnect API error: HTTP ${qcResponse.status}`);
    }

    const data = await qcResponse.json();

    if (data.errno !== 0) {
        throw new Error(`QuickConnect failed. The ID might be incorrect or the NAS is offline. (Error code: ${data.errno})`);
    }

    if (!data.server) {
        throw new Error("Unexpected QuickConnect response: Missing server data.");
    }

    const serverInfo = data.server;
    const serviceInfo = data.service;

    const candidateUrls = [];

    // 1. Try local interfaces (useful if the phone is on the same WiFi as the NAS)
    if (serverInfo.interface && Array.isArray(serverInfo.interface)) {
        for (const iface of serverInfo.interface) {
            if (iface.ip) {
                // If the user expects HTTPS, standard is 5001, HTTP is 5000.
                // We'll try HTTPS first, then fallback to HTTP.
                // Given self-signed certs fail often on Android, HTTP is more reliable locally.
                candidateUrls.push(`http://${iface.ip}:${serviceInfo.port || 5000}`);
                candidateUrls.push(`https://${iface.ip}:${serviceInfo.ext_port || 5001}`);
            }
        }
    }

    // 2. Try external IP (router WAN IP, if port forwarded)
    if (serverInfo.external && serverInfo.external.ip) {
        candidateUrls.push(`http://${serverInfo.external.ip}:${serviceInfo.port || 5000}`);
        candidateUrls.push(`https://${serverInfo.external.ip}:${serviceInfo.ext_port || 5001}`);
    }

    // 3. Try DDNS if available
    if (serverInfo.ddns && serverInfo.ddns !== 'null') {
        candidateUrls.push(`http://${serverInfo.ddns}:${serviceInfo.port || 5000}`);
        candidateUrls.push(`https://${serverInfo.ddns}:${serviceInfo.ext_port || 5001}`);
    }

    // We could add Synology Tunnel support here (ping DSM) but that requires
    // deep integration and implementing their custom socket protocol.
    // Usually, local IP or External IP port mapping resolves it.

    if (candidateUrls.length === 0) {
        throw new Error("QuickConnect returned no valid IP addresses or hostnames.");
    }

    // Remove duplicates
    const uniqueUrls = [...new Set(candidateUrls)];

    // Probe all URLs simultaneously and return the first winner
    return await probeUrls(uniqueUrls);
}
