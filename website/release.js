// Progressive enhancement: show the newest published stable release when one exists.
// Draft and pre-release entries are excluded by the /releases/latest endpoint.
// Without network access or before a stable release exists, the static text stays.
(function () {
  var status = document.getElementById('release-status');
  var link = document.getElementById('release-link');
  if (!status || !link || !window.fetch) return;

  fetch('https://api.github.com/repos/07rjain/runlocal/releases/latest', {
    headers: { Accept: 'application/vnd.github+json' }
  })
    .then(function (response) {
      if (!response.ok) throw new Error('No stable release: HTTP ' + response.status);
      return response.json();
    })
    .then(function (release) {
      if (!release || !release.tag_name || release.draft || release.prerelease) return;
      var apk = (release.assets || []).find(function (asset) {
        return /\.apk$/i.test(asset.name);
      });
      var name = release.name || release.tag_name;
      status.textContent = '';
      var strong = document.createElement('strong');
      strong.textContent = name;
      status.appendChild(document.createTextNode('Latest stable release: '));
      status.appendChild(strong);
      if (release.published_at) {
        var date = new Date(release.published_at);
        if (!isNaN(date)) {
          status.appendChild(document.createTextNode(' · published ' + date.toLocaleDateString(undefined, {
            year: 'numeric', month: 'short', day: 'numeric'
          })));
        }
      }
      status.appendChild(document.createTextNode('.'));
      if (apk) {
        link.href = apk.browser_download_url;
        link.textContent = 'Download ' + apk.name;
        var meta = document.createElement('p');
        meta.className = 'release-meta';
        meta.textContent = 'Verify the checksum against SHA256SUMS on the release page before installing.';
        link.parentNode.insertAdjacentElement('afterend', meta);
      } else {
        link.href = release.html_url;
      }
    })
    .catch(function () {
      // Keep the static release-candidate text and the Releases page link.
    });
})();
