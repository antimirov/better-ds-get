const { withAndroidStringValues, withAndroidManifest, createRunOncePlugin } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

// This plugin injects a custom network security configuration into the Android app
// to allow trusting user-installed certificates (or even all certificates).
// This is necessary because React Native's fetch on Android rejects self-signed HTTPS certs.

const withNetworkSecurityConfig = (config) => {
    // 1. Tell AndroidManifest to use the network security config
    config = withAndroidManifest(config, (config) => {
        const mainApplication = config.modResults.manifest.application[0];

        // Add the networkSecurityConfig attribute to the application tag
        mainApplication.$['android:networkSecurityConfig'] = '@xml/network_security_config';

        return config;
    });

    // 2. Create the xml resources file during prebuild
    // We use withAndroidStringValues as a hook to intercept the build and write the file,
    // since Expo plugins don't have a direct "write raw file" hook without custom mods.
    config = withCustomXmlFile(config);

    return config;
};

// Create a custom mod to write the network_security_config.xml file
const withCustomXmlFile = (config) => {
    return require('@expo/config-plugins').withDangerousMod(config, [
        'android',
        async (config) => {
            const projectRoot = config.modRequest.projectRoot;
            const resDir = path.join(projectRoot, 'android', 'app', 'src', 'main', 'res', 'xml');

            // Ensure the xml directory exists
            if (!fs.existsSync(resDir)) {
                fs.mkdirSync(resDir, { recursive: true });
            }

            const xmlPath = path.join(resDir, 'network_security_config.xml');

            // We allow cleartext for HTTP, and we trust user certs for HTTPS.
            // Allowing all certs (trust anchors src="system" and src="user") helps with standard self-signed NAS.
            const xmlContent = `<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </base-config>
</network-security-config>
`;

            fs.writeFileSync(xmlPath, xmlContent);
            return config;
        },
    ]);
};

// Export the plugin wrapped with createRunOncePlugin to ensure it only runs once
module.exports = createRunOncePlugin(
    withNetworkSecurityConfig,
    'withNetworkSecurityConfig',
    '1.0.0'
);
