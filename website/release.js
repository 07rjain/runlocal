// Keep the published rc10 links usable when JavaScript or GitHub's API is unavailable.
(() => {
  const github = 'https://github.com/07rjain/runlocal';
  const status = document.getElementById('release-status');
  const apkLink = document.getElementById('apk-link');
  const notesLink = document.getElementById('release-link');

  function releaseDetails(release) {
    if (release?.draft !== false || typeof release.prerelease !== 'boolean') return null;
    if (typeof release.tag_name !== 'string' || !/^v[0-9][A-Za-z0-9.+-]{0,80}$/.test(release.tag_name)) return null;
    if (typeof release.body !== 'string') return null;

    const version = release.tag_name.slice(1);
    const markers = [...release.body.matchAll(/<!-- runlocal-version-code: ([0-9]+) -->/g)];
    const code = markers.length === 1 ? Number(markers[0][1]) : 0;
    if (!Number.isSafeInteger(code) || code < 16) return null;

    const notes = github + '/releases/tag/' + release.tag_name;
    const filename = 'runlocal-' + version + '-arm64.apk';
    const apk = github + '/releases/download/' + release.tag_name + '/' + filename;
    const asset = Array.isArray(release.assets) && release.assets.some(entry =>
      entry?.name === filename && entry.browser_download_url === apk && entry.size > 0);
    if (release.html_url !== notes || !asset) return null;

    return { version, code, notes, apk, candidate: release.prerelease };
  }

  fetch('https://api.github.com/repos/07rjain/runlocal/releases?per_page=30', {
    headers: { Accept: 'application/vnd.github+json' }
  })
    .then(response => response.ok ? response.json() : [])
    .then(releases => {
      if (!Array.isArray(releases)) return;
      const current = releases.map(releaseDetails).filter(Boolean).sort((a, b) => b.code - a.code)[0];
      if (!current) return;

      status.textContent = current.version + (current.candidate ? ' preview' : ' stable');
      apkLink.href = current.apk;
      notesLink.href = current.notes;
    })
    .catch(() => {
      // Leave the download and release notes available when the API doesn't respond.
    });
})();
