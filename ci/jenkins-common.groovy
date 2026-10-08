// Infortts Jenkins shared helpers — ported from ROOST manager/tenant pipeline version logic.
// COPIED INTO EACH PROJECT REPO AS <repo>/ci/jenkins-common.groovy (kept in-repo per decision).
// Load in a Jenkinsfile:  def common = load 'ci/jenkins-common.groovy'
def _sh(String cmd, boolean quiet=false) { sh(script: cmd, returnStdout: quiet)?.trim() ?: "" }

// Deploy-planning: tag-driven EPOCH.MAJOR.MINOR+PATCH, native-vs-logic change classification,
// per-track "playstore success" anchor tags (so logic-only changes don't bump Play base).
// opts:
//   label     : build node label        (mac | vps ...)
//   tagPrefix : 'manager-v' | 'tenant-v'| for infortts projects use the repo app dir e.g. '',
//               passed explicit: mitochondria → label 'mac', appDir 'apps/mitochondria'
//   appDir    : path to the app whose pubspec drives versions ('' for non-flutter)
//   track     : Play track (default 'internal')
//   isFlutter : true if structural detection ran against a pubspec.yaml
Map plan(Map opts = [:]) {
  def appDir      = opts.appDir ?: ''
  def track       = opts.track ?: env.TRACK ?: 'internal'
  def prefix      = opts.prefix ?: 'v-playstore-success-mitochondria'
  def isFlutter   = opts.isFlutter != null ? opts.isFlutter : (appDir != '')

  // 1) Sync & prune tags from origin to ensure freshest tag cache
  _sh("git fetch --tags --prune --force origin 2>/dev/null || true")

  // Helper: parse semantic version tuple [epoch, major, minor]
  def parseSemver = { String v ->
    if (!v) return [0, 0, 0]
    def clean = (v - 'v').trim()
    if (clean.contains('+')) clean = clean.tokenize('+')[0]
    if (clean.contains('-')) clean = clean.tokenize('-')[0]
    def parts = clean.tokenize('.')
    int e = parts.size() > 0 ? (parts[0] as int) : 0
    int ma = parts.size() > 1 ? (parts[1] as int) : 0
    int mi = parts.size() > 2 ? (parts[2] as int) : 0
    return [e, ma, mi]
  }

  // 1b) Read version from pubspec.yaml or .version
  def fileVer = ""
  def fileBuild = 0
  if (appDir && fileExists("${appDir}/pubspec.yaml")) {
    def lines = readFile("${appDir}/pubspec.yaml").readLines()
    for (String line : lines) {
      if (line.trim().startsWith('version:')) {
        def raw = line.replace('version:', '').trim()
        def parts = raw.tokenize('+')
        fileVer = parts[0]
        fileBuild = parts.size() > 1 ? (parts[1] as int) : 0
        break
      }
    }
  }
  if (!fileVer && fileExists('.version')) {
    fileVer = readFile('.version').trim()
  }

  // 2) Most recent successful Play Store release for this track (the anchor)
  def anchorBase = ""
  def anchorTag = _sh("git tag --list '${prefix}-${track}-*' --sort=-v:refname | head -n 1", true)
  if (anchorTag) {
    def a = anchorTag.tokenize('-').last()
    if (a != "0.0.0" && a != "") {
      anchorBase = a
    }
  }
  echo "anchor(${track})=${anchorBase} (tag ${anchorTag})"

  // 3) Latest published version from remote tags or pubspec
  def tags = _sh('git tag --list "v[0-9]*" "*+[0-9]*" | grep -E "^v?[0-9]+\\.[0-9]+" | sort -V | tail -n 1', true)
  def tagBase = ""
  def tagBuild = 0
  if (tags) {
    def v = (tags - 'v').tokenize('+')
    tagBase = v[0]
    tagBuild = (v.size() > 1 ? (v[1] as int) : 0)
  }

  // Find highest baseline semver between anchor, tags, and local file version
  def currentSemver = [2, 0, 0]
  for (String cand : [anchorBase, fileVer, tagBase]) {
    if (cand) {
      def p = parseSemver(cand)
      if (p[0] > currentSemver[0] ||
          (p[0] == currentSemver[0] && p[1] > currentSemver[1]) ||
          (p[0] == currentSemver[0] && p[1] == currentSemver[1] && p[2] > currentSemver[2])) {
        currentSemver = p
      }
    }
  }

  int EPOCH = currentSemver[0]
  int MAJOR = currentSemver[1]
  int MINOR = currentSemver[2]
  int buildNo = Math.max(fileBuild, tagBuild)
  if (buildNo == 0) {
    buildNo = EPOCH * 10000 + MAJOR * 100 + MINOR
  }
  def baseVer = "${EPOCH}.${String.format('%02d', MAJOR)}.${String.format('%02d', MINOR)}"
  echo "version source: base=${baseVer} build=${buildNo} (pubspec=${fileVer}+${fileBuild}, tag=${tags}, anchor=${anchorTag})"

  // 4) Change classification vs latest tag or anchor tag
  def cmpTag = tags ?: anchorTag
  def hasNativeChanges = false
  def hasLogicChanges = false
  def isMajorBump = false
  def isEpochBump = false

  if (cmpTag) {
    def changedFiles = _sh("git diff --name-only '${cmpTag}'..HEAD 2>/dev/null || true", true)
    def commitMsgs = _sh("git log '${cmpTag}'..HEAD --oneline 2>/dev/null || true", true)

    if (commitMsgs =~ /(?i)(BREAKING CHANGE|epoch:)/) {
      isEpochBump = true
    } else if (commitMsgs =~ /(?i)(major:|feat!:)/) {
      isMajorBump = true
    }

    for (String f : changedFiles.tokenize()) {
      // Ignore documentation, metadata, ci, test configs
      if (f =~ /(^|\/)(README\.md|CHANGELOG.*|VERSIONS.*|\.gitignore|\.env\.example|ci\/.*|\.github\/.*)$/) {
        continue
      }
      // Native changes: android, ios, macos, linux, windows, c++ backend, gradle, native plugins
      if (f =~ /(^|\/)(android|ios|macos|linux|windows|cpp|native|backend)\// ||
          f.endsWith('.gradle') || f.endsWith('.gradle.kts') || f.endsWith('.properties') ||
          f.endsWith('AndroidManifest.xml') || f.endsWith('Info.plist') || f.endsWith('Podfile')) {
        hasNativeChanges = true
      } else if (f.endsWith('pubspec.yaml')) {
        def depDiff = _sh("git diff '${cmpTag}'..HEAD -- '${f}' | grep -E '^\\+[ ]*(dependencies|dev_dependencies|[a-zA-Z0-9_-]+:)' | grep -vE 'version:' || true", true)
        if (depDiff) {
          hasNativeChanges = true
        }
      } else {
        // Any other code, bot, script, cloudflare, docker, or config change is a logic change
        hasLogicChanges = true
      }
    }
  } else {
    // Fresh repo or no previous tags -> initial release
    hasNativeChanges = true
  }

  // 5) Decide Action & Calculate Version (Strictly monotonic bump on each trigger)
  def action = isFlutter ? 'ota' : 'deploy'
  def reason = 'Trigger version bump'
  def newBase = ""
  def nextBuild = buildNo + 1

  if (isEpochBump) {
    action = isFlutter ? 'playstore' : 'deploy'
    EPOCH = EPOCH + 1
    MAJOR = 0
    MINOR = 0
    reason = "Epoch breaking change (new major generation)"
  } else if (hasNativeChanges || isMajorBump || !anchorBase) {
    action = isFlutter ? 'playstore' : 'deploy'
    MAJOR = MAJOR + 1
    MINOR = 0
    reason = "Native / structural change detected (Major bump)"
  } else {
    // Logic changes or incremental trigger release: increment MINOR
    MINOR = MINOR + 1
    reason = hasLogicChanges ? "Code / logic updates detected" : "Incremental trigger release"
  }

  newBase = "${EPOCH}.${String.format('%02d', MAJOR)}.${String.format('%02d', MINOR)}"
  nextBuild = Math.max(nextBuild, EPOCH * 10000 + MAJOR * 100 + MINOR)
  def newVersion = "${newBase}+${nextBuild}"

  // Automatically stamp pubspec.yaml and .version in workspace
  if (appDir && fileExists("${appDir}/pubspec.yaml")) {
    try {
      def content = readFile("${appDir}/pubspec.yaml")
      def updated = content.replaceAll(/(?m)^version:\s*.+$/, "version: ${newVersion}")
      writeFile file: "${appDir}/pubspec.yaml", text: updated
      echo "Stamped ${appDir}/pubspec.yaml with version: ${newVersion}"
    } catch (Exception e) {
      echo "pubspec stamp notice: ${e.message}"
    }
  }
  if (fileExists('.version')) {
    try {
      writeFile file: '.version', text: "${newBase}\n"
      echo "Stamped .version with: ${newBase}"
    } catch (Exception e) {
      echo ".version stamp notice: ${e.message}"
    }
  }

  echo "    PLAN: version=${newVersion} base=${newBase} build=${nextBuild}"
  echo "    ACTION: ${action} — ${reason}"

  return [
    new_version: newVersion, base_version: newBase, build_number: "${nextBuild}",
    action: action, reason: reason, track: track, structural: (action == 'playstore'),
  ]
}

// Tag the repo with release and anchor markers, pushing directly to origin
void tag(String kind, Map p, String tokenUser='', String tokenPass='') {
  if (!p || !p.new_version) {
    echo "Warning: No version in plan to tag."
    return
  }
  def verTag = "v${p.new_version}"
  _sh("git tag -f '${verTag}'")
  if (p.action == 'playstore' && kind) {
    def anchorName = "${kind}-${p.track ?: 'internal'}-${p.base_version}"
    _sh("git tag -f '${anchorName}'")
    _sh("git push origin '${anchorName}' --force || true")
    echo "Tagged Play Store anchor: ${anchorName}"
  } else if (kind) {
    def otaTagName = "${kind}-ota-${p.track ?: 'internal'}-${p.base_version}"
    _sh("git tag -f '${otaTagName}'")
    _sh("git push origin '${otaTagName}' --force || true")
    echo "Tagged OTA anchor: ${otaTagName}"
  }
  _sh("git push origin '${verTag}' --force")
  echo "Successfully tagged and pushed ${verTag} to origin"
}

// Telegram/console notify shim.
void notify(String msg, Map opts=[:]) {
  echo "[CI] ${msg}"
  // Optional: hook to your existing telegram pipeline: curl -s -X POST https://api.telegram.org/bot<TOKEN>/sendMessage -d chat_id=<> -d text="${msg}"
}

// Hugging Face Dataset CDN publisher (APKs, OTA differential patches, manifests)
void publishHuggingFace(Map opts = [:]) {
  def slug      = opts.slug ?: opts.project ?: ''
  def apkPath   = opts.apk ?: ''
  def patchPath = opts.patch ?: ''
  def version   = opts.version ?: '1.0.0'
  def track     = opts.track ?: 'internal'
  def repo      = opts.repo ?: 'rttss/ota-patches'

  echo "[HF CDN] Publishing ${slug} to Hugging Face dataset ${repo}..."
  
  // Find upload script in ci/ or shared/
  def scriptPath = fileExists('ci/upload_to_hf.py') ? 'ci/upload_to_hf.py' : (fileExists('upload_to_hf.py') ? 'upload_to_hf.py' : '/srv/infortts-jenkins/shared/upload_to_hf.py')
  
  def cmd = "python3 ${scriptPath} --slug '${slug}' --version '${version}' --track '${track}' --repo '${repo}'"
  if (apkPath && fileExists(apkPath)) {
    cmd += " --apk '${apkPath}'"
  }
  if (patchPath && fileExists(patchPath)) {
    cmd += " --patch '${patchPath}'"
  }
  
  _sh(cmd)
}

// OTA registry bump: writes ota-release.json and pushes patch/manifest to Hugging Face CDN
void otaBump(Map p, Map extra = [:]) {
  echo "OTA bump to ${p.new_version} (base ${p.base_version}, build ${p.build_number})"
  def publishedAt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX").format(new Date())
  writeFile file: 'ota-release.json', text: groovy.json.JsonOutput.toJson([
    version: p.new_version, base_version: p.base_version, latest_build: p.build_number as int,
    latest_patch: 0, published_at: publishedAt])
  
  if (extra.slug) {
    publishHuggingFace([
      slug: extra.slug,
      version: p.new_version,
      patch: extra.patch ?: '',
      apk: extra.apk ?: '',
      track: p.track ?: 'internal'
    ])
  }
}

// Rich Build Summary for Jenkins UI (displays all decision points, targets, and health checks cleanly)
void updateBuildSummary(Map p, Map extra = [:]) {
  try {
    def actionLabel = p.action ? p.action.toUpperCase() : 'BUILD'
    def versionStr = p.new_version ?: ''
    if (versionStr) {
      currentBuild.displayName = "#${env.BUILD_NUMBER} [${actionLabel}] v${versionStr}"
    }

    def androidStatus = extra.android ?: (p.action == 'playstore' ? '✅ Native .aab (Google Play internal track)' : (p.action == 'ota' ? '📦 OTA Differential Patch (HF CDN)' : (p.action == 'skip' ? '⏭️ Skipped (no native change)' : 'N/A')))
    def webStatus = extra.web ?: 'N/A'
    def backendStatus = extra.backend ?: (extra.deploy_host ? "${extra.deploy_host}" : 'None')
    def healthStatus = extra.health ?: (extra.deploy_host ? 'Pending execution' : 'N/A')
    def envDetails = extra.env ?: "Node: ${env.NODE_NAME ?: 'mac/vps'} | JDK: 17 | Flutter: 3.47.0"

    def summary = """<div style="background:#0f172a; border:1.5px solid #38bdf8; border-radius:10px; padding:16px; margin:10px 0; color:#e2e8f0; font-family:-apple-system,BlinkMacSystemFont,sans-serif;">
  <div style="margin-bottom:12px; border-bottom:1px solid #334155; padding-bottom:8px; display:flex; justify-content:space-between; align-items:center;">
    <span style="font-size:15px; font-weight:bold; color:#38bdf8;">🚀 Infortts CI/CD Decision Matrix: ${env.JOB_NAME} #${env.BUILD_NUMBER} [${actionLabel}]</span>
    <a href="pipeline-graph-view/" style="display:inline-block; margin-left:12px; background:#0284c7; color:#ffffff; padding:6px 14px; border-radius:6px; font-weight:bold; text-decoration:none; font-size:12px; border:1px solid #38bdf8;">📊 View Pipeline Graph Overview</a>
  </div>
  <table style="width:100%; border-collapse:collapse; margin-top:8px; font-size:13px; color:#e2e8f0; text-align:left;">
    <thead>
      <tr style="background:#1e293b; border-bottom:2px solid #38bdf8;">
        <th style="padding:8px 12px; font-weight:600; color:#38bdf8; width:28%;">Decision Parameter</th>
        <th style="padding:8px 12px; font-weight:600; color:#38bdf8;">Evaluated Value / Resolution</th>
      </tr>
    </thead>
    <tbody>
      <tr style="border-bottom:1px solid #334155;">
        <td style="padding:8px 12px; font-weight:bold; color:#94a3b8;">Action</td>
        <td style="padding:8px 12px;"><span style="background:#0369a1; color:#ffffff; padding:2px 8px; border-radius:4px; font-weight:bold;">${actionLabel}</span> <span style="color:#cbd5e1; margin-left:6px;">${p.reason ?: 'Standard build'}</span></td>
      </tr>
      <tr style="border-bottom:1px solid #334155; background:#0b1120;">
        <td style="padding:8px 12px; font-weight:bold; color:#94a3b8;">Version Planning</td>
        <td style="padding:8px 12px;"><code style="background:#1e293b; color:#38bdf8; padding:2px 6px; border-radius:4px; font-family:monospace;">${p.new_version ?: 'N/A'}</code> <span style="color:#94a3b8; font-size:12px; margin-left:6px;">(Base: ${p.base_version ?: 'N/A'}, Build: ${p.build_number ?: 'N/A'})</span></td>
      </tr>
      <tr style="border-bottom:1px solid #334155;">
        <td style="padding:8px 12px; font-weight:bold; color:#94a3b8;">Android Target</td>
        <td style="padding:8px 12px;">${androidStatus}</td>
      </tr>
      <tr style="border-bottom:1px solid #334155; background:#0b1120;">
        <td style="padding:8px 12px; font-weight:bold; color:#94a3b8;">Web / Cloudflare</td>
        <td style="padding:8px 12px;">${webStatus}</td>
      </tr>
      <tr style="border-bottom:1px solid #334155;">
        <td style="padding:8px 12px; font-weight:bold; color:#94a3b8;">Backend Target</td>
        <td style="padding:8px 12px;">${backendStatus}</td>
      </tr>
      <tr style="border-bottom:1px solid #334155; background:#0b1120;">
        <td style="padding:8px 12px; font-weight:bold; color:#94a3b8;">Health Verification</td>
        <td style="padding:8px 12px;">${healthStatus}</td>
      </tr>
      <tr>
        <td style="padding:8px 12px; font-weight:bold; color:#94a3b8;">Execution Environment</td>
        <td style="padding:8px 12px; font-size:12px; color:#cbd5e1;">${envDetails}</td>
      </tr>
    </tbody>
  </table>
</div>"""

    currentBuild.description = summary
  } catch (Exception e) {
    echo "Notice: Could not set build summary card: ${e.message}"
  }
}

return this