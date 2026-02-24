/**
 * Geolocation utility for mapping IP addresses to country codes.
 * Uses ip-api.com (free for non-commercial use, 45 requests/min).
 */

const geoCache = new Map();
const pendingRequests = new Map();

/**
 * Get country info for an IP address.
 * @param {string} ip 
 * @returns {Promise<{countryCode: string, country: string} | null>}
 */
export async function getIpCountry(ip) {
    if (!ip || ip === '127.0.0.1' || ip === '::1') return null;

    // Return from cache if available
    if (geoCache.has(ip)) return geoCache.get(ip);

    // Return pending request if already in flight
    if (pendingRequests.has(ip)) return pendingRequests.get(ip);

    const request = (async () => {
        try {
            const response = await fetch(`http://ip-api.com/json/${ip}?fields=status,message,countryCode,country`);
            const data = await response.json();

            if (data.status === 'success') {
                const result = { countryCode: data.countryCode, country: data.country };
                geoCache.set(ip, result);
                return result;
            }
            return null;
        } catch (error) {
            console.error('Geolocation error:', error);
            return null;
        } finally {
            pendingRequests.delete(ip);
        }
    })();

    pendingRequests.set(ip, request);
    return request;
}

/**
 * Convert ISO country code to emoji flag.
 * @param {string} countryCode - e.g. "US"
 * @returns {string} - e.g. "🇺🇸"
 */
export function getFlagEmoji(countryCode) {
    if (!countryCode) return '';
    const codePoints = countryCode
        .toUpperCase()
        .split('')
        .map(char => 127397 + char.charCodeAt());
    return String.fromCodePoint(...codePoints);
}
