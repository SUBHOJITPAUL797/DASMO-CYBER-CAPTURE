const https = require('https');

const GITHUB_REPO = 'SUBHOJITPAUL797/DASMO-CYBER-CAPTURE';
const GITHUB_API_URL = `https://api.github.com/repos/${GITHUB_REPO}/releases/latest`;

function checkForDesktopUpdates(currentVersion = '1.0.0') {
    return new Promise((resolve) => {
        const options = {
            headers: {
                'User-Agent': 'DASMO-CYBER-CAPTURE-Desktop',
                'Accept': 'application/vnd.github.v3+json'
            },
            timeout: 6000
        };

        const req = https.get(GITHUB_API_URL, options, (res) => {
            if (res.statusCode !== 200) {
                return resolve({
                    isUpdateAvailable: false,
                    currentVersion,
                    message: `GitHub API returned status ${res.statusCode}`
                });
            }

            let rawData = '';
            res.on('data', chunk => rawData += chunk);
            res.on('end', () => {
                try {
                    const json = JSON.parse(rawData);
                    const tagName = (json.tag_name || '').replace(/^v/, '').trim();
                    const title = json.name || 'New DASMO Cyber Capture Release';
                    const notes = json.body || 'Performance improvements and bug fixes.';
                    const releaseUrl = json.html_url || `https://github.com/${GITHUB_REPO}/releases`;

                    let msiUrl = '';
                    let exeUrl = '';
                    let apkUrl = '';

                    if (Array.isArray(json.assets)) {
                        for (const asset of json.assets) {
                            const name = (asset.name || '').toLowerCase();
                            if (name.endsWith('.msi')) {
                                msiUrl = asset.browser_download_url;
                            } else if (name.endsWith('.exe')) {
                                exeUrl = asset.browser_download_url;
                            } else if (name.endsWith('.apk')) {
                                apkUrl = asset.browser_download_url;
                            }
                        }
                    }

                    const isUpdateAvailable = isNewerVersion(tagName, currentVersion);

                    resolve({
                        isUpdateAvailable,
                        currentVersion,
                        latestVersion: tagName,
                        releaseTitle: title,
                        releaseNotes: notes,
                        msiUrl: msiUrl || releaseUrl,
                        exeUrl: exeUrl || releaseUrl,
                        apkUrl: apkUrl || releaseUrl,
                        releaseUrl
                    });
                } catch (e) {
                    resolve({
                        isUpdateAvailable: false,
                        currentVersion,
                        error: e.message
                    });
                }
            });
        });

        req.on('error', (err) => {
            resolve({
                isUpdateAvailable: false,
                currentVersion,
                error: err.message
            });
        });

        req.on('timeout', () => {
            req.destroy();
            resolve({
                isUpdateAvailable: false,
                currentVersion,
                error: 'Request timed out'
            });
        });
    });
}

function isNewerVersion(latest, current) {
    if (!latest) return false;
    try {
        const lParts = latest.split('.').map(n => parseInt(n, 10) || 0);
        const cParts = current.split('.').map(n => parseInt(n, 10) || 0);
        const maxLen = Math.max(lParts.length, cParts.length);
        for (let i = 0; i < maxLen; i++) {
            const l = lParts[i] || 0;
            const c = cParts[i] || 0;
            if (l > c) return true;
            if (l < c) return false;
        }
    } catch (_) {
        return latest !== current;
    }
    return false;
}

module.exports = { checkForDesktopUpdates };
